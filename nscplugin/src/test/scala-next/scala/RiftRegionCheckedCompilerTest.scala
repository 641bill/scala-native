package org.scalanative

import org.junit.Assert._
import org.junit.Test

import scala.scalanative.api.CompilationFailedException

class RiftRegionCheckedCompilerTest {
  private def assertCompiles(source: String): Unit = {
    try scalanative.NIRCompiler(_.compile(source))
    catch {
      case ex: CompilationFailedException =>
        fail(s"Failed to compile source: $ex")
    }
  }

  private def assertDoesNotCompile(source: String): Unit = {
    val err = assertThrows(
      classOf[CompilationFailedException],
      () => scalanative.NIRCompiler(_.compile(source))
    )
    assertTrue("expected a compiler diagnostic", err.getMessage.nonEmpty)
  }

  private def assertDoesNotCompileWith(
      source: String,
      expectedMessage: String
  ): Unit = {
    val err = assertThrows(
      classOf[CompilationFailedException],
      () => scalanative.NIRCompiler(_.compile(source))
    )
    assertTrue(
      s"expected diagnostic containing '$expectedMessage', got: ${err.getMessage}",
      err.getMessage.contains(expectedMessage)
    )
  }

  @Test def checkedScopedObjectGraphCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |final class Node(val left: Leaf^, val next: Node^)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val leaf: Leaf^{region} = RiftRegion.alloc(new Leaf(40))
      |    val node: Node^{region} = region.alloc(new Node(leaf, null))
      |    node.left.value + 2
      |  }
      |""".stripMargin)

  @Test def checkedClosureCaptureCompilesWhenClosureDoesNotEscape(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val box = RiftRegion.alloc(new Box(40))
      |    val f = (n: Int) => box.value + n
      |    f(2)
      |  }
      |""".stripMargin)

  @Test def scopedForLoopAllocationCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    var total = 0
      |    for i <- 0 until 8 do
      |      val box: Box^{region} = region.alloc(new Box(i))
      |      total += box.value
      |    total
      |  }
      |""".stripMargin)

  @Test def nestedScopedRegionsReturningPureValueCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { outer ?=>
      |    val outerBox: Box^{outer} = RiftRegion.alloc(new Box(20))
      |    RiftRegion.scoped { inner ?=>
      |      val innerBox: Box^{inner} = RiftRegion.alloc(new Box(22))
      |      outerBox.value + innerBox.value
      |    }
      |  }
      |""".stripMargin)

  @Test def scopedHigherOrderConsumerCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def withBox(using region: RiftRegion.ScopedRegion^)(
      |    use: Box^{region} => Int
      |): Int =
      |  val box: Box^{region} = RiftRegion.alloc(new Box(40))
      |  use(box)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    withBox { box => box.value + 2 }
      |  }
      |""".stripMargin)

  @Test def remlStylePolymorphicLocalConsumerCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def consume[A](value: A)(use: A => Int): Int =
      |  use(value)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val box: Box^{region} = RiftRegion.alloc(new Box(40))
      |    consume[Box^{region}](box)(_.value + 2)
      |  }
      |""".stripMargin)

  @Test def remlStylePolymorphicIdentityCannotReturnRegionValue(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def id[A](value: A): A = value
      |
      |def bad(): AnyRef =
      |  RiftRegion.scoped { region ?=>
      |    val box: Box^{region} = RiftRegion.alloc(new Box(1))
      |    id[Box^{region}](box)
      |  }
      |""".stripMargin,
      "Capability `region` outlives its scope"
    )

  @Test def remlStylePolymorphicHeapCellCannotRetainRegionValue(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |final class Cell[A](val value: A)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val box: Box^{region} = RiftRegion.alloc(new Box(1))
      |    Holder.retained = new Cell[Box^{region}](box)
      |  }
      |""".stripMargin,
      "Rift checked heap state cannot retain a region-captured value"
    )

  @Test def remlStylePolymorphicHeapCellCanStayLocal(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val box: Box^{region} = RiftRegion.alloc(new Box(40))
      |    val cell = new Cell[Box^{region}](box)
      |    cell.value.value + 2
      |  }
      |""".stripMargin)

  @Test def remlStylePolymorphicHeapCellWidenedToAnyRefCannotEscape(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |final class Cell[A](val value: A)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val box: Box^{region} = RiftRegion.alloc(new Box(1))
      |    val cell: AnyRef = new Cell[Box^{region}](box)
      |    Holder.retained = cell
      |  }
      |""".stripMargin,
      "Rift checked heap state cannot retain a region-captured value"
    )

  @Test def remlStyleHeapArrayOfRegionCapturedTypeCannotEscape(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val box: Box^{region} = RiftRegion.alloc(new Box(1))
      |    val values = new Array[Box^{region}](1)
      |    values(0) = box
      |    Holder.retained = values
      |  }
      |""".stripMargin,
      "Rift checked heap state cannot retain a region-captured value"
    )

  @Test def remlStyleEscapingClosureCannotHideGenericRegionValue(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |final class Cell[A](val value: A)
      |
      |object Holder:
      |  var retained: () => AnyRef = null
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val box: Box^{region} = RiftRegion.alloc(new Box(1))
      |    Holder.retained = () => new Cell[Box^{region}](box)
      |  }
      |""".stripMargin,
      "Rift checked heap state cannot retain a region-captured value"
    )

  @Test def remlStylePolymorphicRegionObjectCannotStoreUnrootedHeapValue(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Cell[A](val value: A^)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(41)
      |    val cell: Cell[Metadata]^{region} =
      |      RiftRegion.alloc(new Cell[Metadata](metadata))
      |    cell.value.value + 1
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def scopedValueCannotEscapeByReturn(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def bad(): AnyRef =
      |  RiftRegion.scoped { region ?=>
      |    RiftRegion.alloc(new Box(1))
      |  }
      |""".stripMargin,
      "Capability `region` outlives its scope"
    )

  @Test def innerScopedValueCannotEscapeOuterScope(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def bad(): AnyRef =
      |  RiftRegion.scoped { outer ?=>
      |    RiftRegion.scoped { inner ?=>
      |      RiftRegion.alloc(new Box(1))
      |    }
      |  }
      |""".stripMargin,
      "Capability `inner` outlives its scope"
    )

  @Test def closureCapturingScopedValueCannotEscape(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |object Holder:
      |  var retained: () => Int = null
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    Holder.retained = () => region.alloc(new Box(1)).value
      |  }
      |""".stripMargin,
      "Reference `region` is not included in the allowed capture set"
    )

  @Test def closureCapturingScopedValueCannotEscapeByReturn(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def bad(): () => Int =
      |  RiftRegion.scoped { region ?=>
      |    val box: Box^{region} = RiftRegion.alloc(new Box(1))
      |    () => box.value
      |  }
      |""".stripMargin,
      "Rift checked regions cannot return function values yet"
    )

  @Test def heapObjectCannotRetainScopedValue(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    Holder.retained = RiftRegion.alloc(new Box(1))
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def rootedHeapValueCanBeStoredInScopedObject(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: RiftRegion.HeapRoot[Metadata]^)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(41)
      |    val rooted = RiftRegion.root(metadata)
      |    val entry = RiftRegion.alloc(new Entry(rooted))
      |    entry.metadata.value.value + 1
      |  }
      |""".stripMargin)

  @Test def staticModuleCanBeStoredInScopedObject(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |object StaticMetadata:
      |  val shard: Int = 41
      |
      |final class Entry(val metadata: StaticMetadata.type)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val entry: Entry^{region} =
      |      RiftRegion.alloc(new Entry(StaticMetadata))
      |    entry.metadata.shard + 1
      |  }
      |""".stripMargin)

  @Test def staticValCanBeStoredInScopedObject(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |object StaticMetadata:
      |  val metadata: Metadata = new Metadata(41)
      |
      |final class Entry(val metadata: Metadata^)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val entry: Entry^{region} =
      |      RiftRegion.alloc(new Entry(StaticMetadata.metadata))
      |    entry.metadata.value + 1
      |  }
      |""".stripMargin)

  @Test def staticVarCannotBeStoredInScopedObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |object StaticMetadata:
      |  var metadata: Metadata = new Metadata(41)
      |
      |final class Entry(val metadata: Metadata^)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val entry: Entry^{region} =
      |      RiftRegion.alloc(new Entry(StaticMetadata.metadata))
      |    entry.metadata.value + 1
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def directHeapValueCannotBeStoredInScopedObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata^)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(41)
      |    val entry: Entry^{region} = RiftRegion.alloc(new Entry(metadata))
      |    entry.metadata.value + 1
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def regionAllocatedAliasCanBeStoredInScopedObject(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |final class Node(val leaf: Leaf^)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val leaf: Leaf^{region} = RiftRegion.alloc(new Leaf(41))
      |    val alias: Leaf^{region} = leaf
      |    val node: Node^{region} = RiftRegion.alloc(new Node(alias))
      |    node.leaf.value + 1
      |  }
      |""".stripMargin)

  @Test def heapAliasCannotBeStoredInScopedObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata^)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(41)
      |    val alias = metadata
      |    val entry: Entry^{region} = RiftRegion.alloc(new Entry(alias))
      |    entry.metadata.value + 1
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def heapFieldSelectionCannotBeStoredInScopedObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Holder(val metadata: Metadata)
      |final class Entry(val metadata: Metadata^)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val holder = new Holder(new Metadata(41))
      |    val entry: Entry^{region} =
      |      RiftRegion.alloc(new Entry(holder.metadata))
      |    entry.metadata.value + 1
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def explicitRegionParamFieldCanBeStoredInScopedObject(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Pair(val leaf: Leaf^{region})
      |    final class Node(val leaf: Leaf^{region})
      |    val leaf: Leaf^{region} = RiftRegion.alloc(new Leaf(41))
      |    val pair: Pair^{region} = RiftRegion.alloc(new Pair(leaf))
      |    val node: Node^{region} = RiftRegion.alloc(new Node(pair.leaf))
      |    node.leaf.value + 1
      |  }
      |""".stripMargin)

  @Test def explicitRegionParamFieldAliasCanBeStoredInScopedObject(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Pair(val leaf: Leaf^{region})
      |    final class Node(val leaf: Leaf^{region})
      |    val leaf: Leaf^{region} = RiftRegion.alloc(new Leaf(41))
      |    val pair: Pair^{region} = RiftRegion.alloc(new Pair(leaf))
      |    val selected: Leaf^{region} = pair.leaf
      |    val node: Node^{region} = RiftRegion.alloc(new Node(selected))
      |    node.leaf.value + 1
      |  }
      |""".stripMargin)

  @Test def plainRegionOwnerParamFieldCannotBeStoredAsRegionValue(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |final class Pair(val leaf: Leaf^)
      |final class Node(val leaf: Leaf^)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val leaf: Leaf^{region} = RiftRegion.alloc(new Leaf(41))
      |    val pair: Pair^{region} = RiftRegion.alloc(new Pair(leaf))
      |    val node: Node^{region} = RiftRegion.alloc(new Node(pair.leaf))
      |    node.leaf.value + 1
      |  }
      |""".stripMargin,
      "cannot flow into capture set"
    )

  @Test def regionOwnedArrayCanBeStoredInScopedObject(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Bag(val items: Array[Leaf^{region}]^{region})
      |    val items: Array[Leaf^{region}]^{region} =
      |      RiftRegion.alloc(new Array[Leaf^{region}](2))
      |    val bag: Bag^{region} = RiftRegion.alloc(new Bag(items))
      |    bag.items.length + 40
      |  }
      |""".stripMargin)

  @Test def regionOwnedArrayCanStoreRegionObject(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val items: Array[Leaf^{region}]^{region} =
      |      RiftRegion.alloc(new Array[Leaf^{region}](1))
      |    val leaf: Leaf^{region} = RiftRegion.alloc(new Leaf(42))
      |    items(0) = leaf
      |    leaf.value
      |  }
      |""".stripMargin)

  @Test def regionOwnedArrayCannotStoreHeapObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val items =
      |      RiftRegion.alloc(new Array[Metadata](1))
      |    val metadata = new Metadata(41)
      |    items(0) = metadata
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def heapArrayCannotBeStoredInScopedObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Bag(val items: Array[Metadata]^)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val items = new Array[Metadata](1)
      |    val bag: Bag^{region} = RiftRegion.alloc(new Bag(items))
      |    bag.items.length
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def regionOwnedArrayCanStoreHeapRoot(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class RootBag(
      |        val roots: Array[RiftRegion.HeapRoot[Metadata]^{region}]^{region}
      |    )
      |    val roots: Array[RiftRegion.HeapRoot[Metadata]^{region}]^{region} =
      |      RiftRegion.alloc(
      |        new Array[RiftRegion.HeapRoot[Metadata]^{region}](1)
      |      )
      |    val metadata = new Metadata(41)
      |    roots(0) = RiftRegion.root(metadata)
      |    val bag: RootBag^{region} = RiftRegion.alloc(new RootBag(roots))
      |    bag.roots.length + 41
      |  }
      |""".stripMargin)

  @Test def objectBufferCanStoreRegionObjects(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val buffer = RiftRegion.objectBuffer[Leaf](2)
      |    val leaf: Leaf^{region} = RiftRegion.alloc(new Leaf(41))
      |    RiftRegion.append(region, buffer, leaf)
      |    RiftRegion.get(region, buffer, 0).value +
      |      RiftRegion.length(region, buffer)
      |  }
      |""".stripMargin)

  @Test def objectBufferOwnerMethodsCanStoreRegionObjects(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val buffer = RiftRegion.objectBuffer[Leaf](2)
      |    val leaf: Leaf^{region} = RiftRegion.alloc(new Leaf(41))
      |    region.append(buffer, leaf)
      |    region.get(buffer, 0).value + region.length(buffer)
      |  }
      |""".stripMargin)

  @Test def objectBufferOwnerMethodsCannotStoreHeapObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val buffer = RiftRegion.objectBuffer[Metadata](1)
      |    val metadata = new Metadata(41)
      |    region.append(buffer, metadata)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def objectBufferCannotStoreHeapObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val buffer = RiftRegion.objectBuffer[Metadata](1)
      |    val metadata = new Metadata(41)
      |    RiftRegion.append(region, buffer, metadata)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def objectBufferCanStoreHeapRoot(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val buffer =
      |      RiftRegion.objectBuffer[RiftRegion.HeapRoot[Metadata]](1)
      |    val metadata = new Metadata(41)
      |    RiftRegion.append(region, buffer, RiftRegion.root(metadata))
      |    RiftRegion.get(region, buffer, 0).value.value + 1
      |  }
      |""".stripMargin)

  @Test def streamBucketArenaCanAllocateRegionObjects(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val arena = RiftRegion.streamBucketArena(60)
      |    val bucket = RiftRegion.streamBucketFor(stream, arena, 42L)
      |    val child = RiftRegion.streamBucketRegion(stream, bucket)
      |    val event: Event^{stream} =
      |      RiftRegion.alloc(new Event(41))(using child)
      |    var retained: Event^{stream} = event
      |
      |    RiftRegion.closeStreamBucketsBefore(stream, arena, 60L) { _ =>
      |      retained = null
      |    }
      |
      |    42
      |  }
      |""".stripMargin)

  @Test def streamBucketArenaCannotEscapeStream(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def bad(): AnyRef =
      |  RiftRegion.streaming { stream ?=>
      |    RiftRegion.streamBucketArena(60)
      |  }
      |""".stripMargin,
      "Capability `stream` outlives its scope"
    )

  @Test def streamWindowIndexedRankStoresBucketRegionObjects(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val rank = RiftRegion.streamWindowIndexedRank[Row](10, 8, 1)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val child = RiftRegion.streamBucketRegion(stream, bucket)
      |    val low: Row^{stream} =
      |      RiftRegion.alloc(new Row(20))(using child)
      |    val high: Row^{stream} =
      |      RiftRegion.alloc(new Row(41))(using child)
      |
      |    RiftRegion.putWindowRank(stream, rank, 1, low, 1L)
      |    RiftRegion.putWindowRank(stream, rank, 2, high, 2L)
      |    val beforeClose = RiftRegion.peekWindowRank(stream, rank).value
      |
      |    RiftRegion.closeWindowRankBucketsBefore(stream, rank, 10L) { _ =>
      |      RiftRegion.removeWindowRank(stream, rank, 1)
      |      RiftRegion.removeWindowRank(stream, rank, 2)
      |    }
      |
      |    beforeClose + RiftRegion.windowRankLength(stream, rank)
      |  }
      |""".stripMargin)

  @Test def streamWindowIndexedRankInBucketAutoCleanupCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val rank = RiftRegion.streamWindowIndexedRank[Row](10, 8, 1)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val child = RiftRegion.streamBucketRegion(stream, bucket)
      |    val row: Row^{stream} =
      |      RiftRegion.alloc(new Row(41))(using child)
      |
      |    RiftRegion.putWindowRankInBucket(stream, rank, bucket, 1, row, 1L)
      |    val beforeClose = RiftRegion.peekWindowRank(stream, rank).value
      |
      |    RiftRegion.closeWindowRankBucketsBefore(stream, rank, 10L) { _ =>
      |      ()
      |    }
      |
      |    beforeClose + RiftRegion.windowRankLength(stream, rank)
      |  }
      |""".stripMargin)

  @Test def streamWindowIndexedRankCloseEntriesCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val rank = RiftRegion.streamWindowIndexedRank[Row](10, 8, 1)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val child = RiftRegion.streamBucketRegion(stream, bucket)
      |    val row: Row^{stream} =
      |      RiftRegion.alloc(new Row(41))(using child)
      |    RiftRegion.putWindowRankInBucket(stream, rank, bucket, 1, row, 1L)
      |
      |    var sum = 0
      |    RiftRegion.closeWindowRankBucketsBeforeWithEntries(
      |      stream,
      |      rank,
      |      10L
      |    ) { (_, key, value) =>
      |      sum += key + value.value
      |    } { _ =>
      |      ()
      |    }
      |    sum
      |  }
      |""".stripMargin)

  @Test def streamWindowIndexedRankLexicographicCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val rank =
      |      RiftRegion.streamWindowIndexedRankLexicographic[Row](10, 8, 1)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val child = RiftRegion.streamBucketRegion(stream, bucket)
      |    val row: Row^{stream} =
      |      RiftRegion.alloc(new Row(41))(using child)
      |    RiftRegion.putWindowRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      1,
      |      row,
      |      2L,
      |      3L,
      |      4L,
      |      -1L
      |    )
      |    RiftRegion.peekWindowRank(stream, rank).value
      |  }
      |""".stripMargin)

  @Test def streamWindowLongIndexedRankInBucketAutoCleanupCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val rank = RiftRegion.streamWindowLongIndexedRank[Row](10, 1, 4)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val child = RiftRegion.streamBucketRegion(stream, bucket)
      |    val row: Row^{stream} =
      |      RiftRegion.alloc(new Row(41))(using child)
      |
      |    RiftRegion.putWindowRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      0x100000001L,
      |      row,
      |      1L
      |    )
      |    val beforeClose = RiftRegion.peekWindowRank(stream, rank).value
      |
      |    RiftRegion.closeWindowRankBucketsBefore(stream, rank, 10L) { _ =>
      |      ()
      |    }
      |
      |    beforeClose + RiftRegion.windowRankLength(stream, rank)
      |  }
      |""".stripMargin)

  @Test def streamWindowLongIndexedRankCloseEntriesCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val rank = RiftRegion.streamWindowLongIndexedRank[Row](10, 1, 4)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val child = RiftRegion.streamBucketRegion(stream, bucket)
      |    val row: Row^{stream} =
      |      RiftRegion.alloc(new Row(41))(using child)
      |    RiftRegion.putWindowRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      0x100000001L,
      |      row,
      |      1L
      |    )
      |
      |    var sum = 0
      |    RiftRegion.closeWindowRankBucketsBeforeWithEntries(
      |      stream,
      |      rank,
      |      10L
      |    ) { (_, key, value) =>
      |      sum += key.toInt + value.value
      |    } { _ =>
      |      ()
      |    }
      |    sum
      |  }
      |""".stripMargin)

  @Test def streamWindowLongIndexedRankLexicographicCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val rank =
      |      RiftRegion.streamWindowLongIndexedRankLexicographic[Row](10, 1, 4)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val child = RiftRegion.streamBucketRegion(stream, bucket)
      |    val row: Row^{stream} =
      |      RiftRegion.alloc(new Row(41))(using child)
      |    RiftRegion.putWindowRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      0x100000001L,
      |      row,
      |      2L,
      |      3L,
      |      4L,
      |      -1L
      |    )
      |    RiftRegion.peekWindowRank(stream, rank).value
      |  }
      |""".stripMargin)

  @Test def streamWindowTableRankInBucketCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val rank = RiftRegion.streamWindowTableRank[Row](10, 1, 4)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val child = RiftRegion.streamBucketRegion(stream, bucket)
      |    val row: Row^{stream} =
      |      RiftRegion.alloc(new Row(41))(using child)
      |    RiftRegion.putTableRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      0x100000001L,
      |      row,
      |      1L
      |    )
      |    RiftRegion.peekTableRank(stream, rank).value
      |  }
      |""".stripMargin)

  @Test def streamWindowTableRankCloseEntriesCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val rank = RiftRegion.streamWindowTableRank[Row](10, 1, 4)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val child = RiftRegion.streamBucketRegion(stream, bucket)
      |    val row: Row^{stream} =
      |      RiftRegion.alloc(new Row(41))(using child)
      |    RiftRegion.putTableRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      0x100000001L,
      |      row,
      |      1L
      |    )
      |
      |    var sum = 0
      |    RiftRegion.closeTableRankBucketsBeforeWithEntries(
      |      stream,
      |      rank,
      |      10L
      |    ) { (_, key, value) =>
      |      sum += key.toInt + value.value
      |    } { _ =>
      |      ()
      |    }
      |    sum
      |  }
      |""".stripMargin)

  @Test def streamWindowTableRankLexicographicCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val rank =
      |      RiftRegion.streamWindowTableRankLexicographic[Row](10, 1, 4)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val child = RiftRegion.streamBucketRegion(stream, bucket)
      |    val row: Row^{stream} =
      |      RiftRegion.alloc(new Row(41))(using child)
      |    RiftRegion.putTableRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      0x100000001L,
      |      row,
      |      2L,
      |      3L,
      |      4L,
      |      -1L
      |    )
      |    RiftRegion.peekTableRank(stream, rank).value
      |  }
      |""".stripMargin)

  @Test def streamWindowTableRankTopKAndRemoveCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val rank = RiftRegion.streamWindowTableRank[Row](10, 1, 4)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val child = RiftRegion.streamBucketRegion(stream, bucket)
      |    val row: Row^{stream} =
      |      RiftRegion.alloc(new Row(41))(using child)
      |    RiftRegion.putTableRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      0x100000001L,
      |      row,
      |      1L
      |    )
      |    val result: Array[Row^{stream}]^{stream} =
      |      RiftRegion.alloc(new Array[Row^{stream}](1))
      |    val candidates: Array[Int]^{stream} =
      |      RiftRegion.alloc(new Array[Int](1))
      |    val copied =
      |      RiftRegion.copyTableRankTopK(stream, rank, result, candidates, 1)
      |    if (RiftRegion.removeTableRank(stream, rank, 0x100000001L))
      |      copied + result(0).value
      |    else copied
      |  }
      |""".stripMargin)

  @Test def streamWindowTableRankDiagnosticsCompile(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): String =
      |  RiftRegion.streaming { stream ?=>
      |    val rank = RiftRegion.streamWindowTableRank[Row](10, 1, 4)
      |    RiftRegion.setTableRankDiagnosticsEnabled(stream, rank, true)
      |    RiftRegion.resetTableRankDiagnostics(stream, rank)
      |    RiftRegion.tableRankDiagnostics(stream, rank)
      |  }
      |""".stripMargin)

  @Test def streamWindowIndexedRankCannotStoreDirectHeapObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val rank = RiftRegion.streamWindowIndexedRank[Row](10, 8, 1)
      |    val row = new Row(41)
      |    RiftRegion.putWindowRank(stream, rank, 1, row, 1L)
      |  }
      |""".stripMargin,
    "Rift checked object buffer cannot store an unrooted heap object"
  )

  @Test def streamWindowIndexedRankLexicographicCannotStoreDirectHeapObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val rank =
      |      RiftRegion.streamWindowIndexedRankLexicographic[Row](10, 8, 1)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val row = new Row(41)
      |    RiftRegion.putWindowRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      1,
      |      row,
      |      2L,
      |      3L,
      |      4L,
      |      -1L
      |    )
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def streamWindowLongIndexedRankCannotStoreDirectHeapObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val rank = RiftRegion.streamWindowLongIndexedRank[Row](10, 1, 4)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val row = new Row(41)
      |    RiftRegion.putWindowRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      0x100000001L,
      |      row,
      |      1L
      |    )
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def streamWindowTableRankCannotStoreDirectHeapObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val rank = RiftRegion.streamWindowTableRank[Row](10, 1, 4)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val row = new Row(41)
      |    RiftRegion.putTableRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      0x100000001L,
      |      row,
      |      1L
      |    )
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def streamWindowIndexedRankInBucketCannotStoreDirectHeapObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val rank = RiftRegion.streamWindowIndexedRank[Row](10, 8, 1)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val row = new Row(41)
      |    RiftRegion.putWindowRankInBucket(stream, rank, bucket, 1, row, 1L)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def objectBufferCannotStoreInnerScopedValue(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { outer ?=>
      |    val buffer = RiftRegion.objectBuffer[Leaf](1)
      |    RiftRegion.scoped { inner ?=>
      |      val leaf: Leaf^{inner} = RiftRegion.alloc(new Leaf(41))
      |      RiftRegion.append(outer, buffer, leaf)
      |    }
      |  }
      |""".stripMargin,
      "cannot flow into capture set {outer}"
    )

  @Test def objectBufferCannotEscapeScopedRegion(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def bad(): AnyRef =
      |  RiftRegion.scoped { region ?=>
      |    RiftRegion.objectBuffer[Leaf](1)
      |  }
      |""".stripMargin,
      "Capability `region` outlives its scope"
    )

  @Test def regionBufferCanGrowAndStoreRegionObjects(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val buffer = RiftRegion.regionBuffer[Leaf](1)
      |    val left: Leaf^{region} = RiftRegion.alloc(new Leaf(20))
      |    val right: Leaf^{region} = RiftRegion.alloc(new Leaf(21))
      |    region.append(buffer, left)
      |    region.append(buffer, right)
      |    region.get(buffer, 0).value +
      |      region.get(buffer, 1).value +
      |      region.length(buffer) - 1
      |  }
      |""".stripMargin)

  @Test def regionBufferCannotStoreHeapObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val buffer = RiftRegion.regionBuffer[Metadata](1)
      |    val metadata = new Metadata(41)
      |    region.append(buffer, metadata)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def regionBufferCanStoreHeapRoot(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val buffer =
      |      RiftRegion.regionBuffer[RiftRegion.HeapRoot[Metadata]](1)
      |    region.append(buffer, RiftRegion.root(new Metadata(41)))
      |    region.get(buffer, 0).value.value + region.length(buffer)
      |  }
      |""".stripMargin)

  @Test def regionBufferCannotStoreInnerScopedValue(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { outer ?=>
      |    val buffer = RiftRegion.regionBuffer[Leaf](1)
      |    RiftRegion.scoped { inner ?=>
      |      val leaf: Leaf^{inner} = RiftRegion.alloc(new Leaf(41))
      |      outer.append(buffer, leaf)
      |    }
      |  }
      |""".stripMargin,
      "cannot flow into capture set {outer}"
    )

  @Test def regionPriorityQueueCanStoreRegionObjects(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue = RiftRegion.regionPriorityQueue[Row](1)
      |    val low: Row^{region} = RiftRegion.alloc(new Row(10))
      |    val high: Row^{region} = RiftRegion.alloc(new Row(40))
      |    region.push(queue, low, 1L)
      |    region.push(queue, high, 3L)
      |    val first = region.pop(queue)
      |    val second = RiftRegion.pop(region, queue)
      |    first.value + second.value + region.length(queue)
      |  }
      |""".stripMargin)

  @Test def regionPriorityQueueCannotStoreHeapObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val queue = RiftRegion.regionPriorityQueue[Row](1)
      |    val row = new Row(10)
      |    region.push(queue, row, 1L)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def regionPriorityQueueCanStoreHeapRoot(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionPriorityQueue[RiftRegion.HeapRoot[Metadata]](1)
      |    region.push(queue, RiftRegion.root(new Metadata(40)), 2L)
      |    region.peekPriority(queue).toInt + region.peek(queue).value.value
      |  }
      |""".stripMargin)

  @Test def regionPriorityQueueCannotStoreInnerScopedValue(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { outer ?=>
      |    val queue = RiftRegion.regionPriorityQueue[Row](1)
      |    RiftRegion.scoped { inner ?=>
      |      val row: Row^{inner} = RiftRegion.alloc(new Row(10))
      |      outer.push(queue, row, 1L)
      |    }
      |  }
      |""".stripMargin,
      "cannot flow into capture set {outer}"
    )

  @Test def regionIndexedPriorityQueueCanUpdatePriority(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue = RiftRegion.regionIndexedPriorityQueue[Row](8, 1)
      |    val low: Row^{region} = RiftRegion.alloc(new Row(10))
      |    val high: Row^{region} = RiftRegion.alloc(new Row(40))
      |    region.put(queue, 1, low, 1L)
      |    region.put(queue, 2, high, 3L)
      |    region.updatePriority(queue, 1, 5L)
      |    val existing = region.get(queue, 1)
      |    val first = region.peek(queue)
      |    first.value + existing.value + region.peekKey(queue) + region.length(queue)
      |  }
      |""".stripMargin)

  @Test def regionIndexedPriorityQueueCanReplaceValueForKey(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue = RiftRegion.regionIndexedPriorityQueue[Row](4, 1)
      |    val first: Row^{region} = RiftRegion.alloc(new Row(10))
      |    val second: Row^{region} = RiftRegion.alloc(new Row(40))
      |    RiftRegion.put(region, queue, 1, first, 1L)
      |    RiftRegion.put(region, queue, 1, second, 2L)
      |    val stored = RiftRegion.pop(region, queue)
      |    stored.value + region.length(queue)
      |  }
      |""".stripMargin)

  @Test def regionIndexedPriorityQueueCannotStoreHeapObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val queue = RiftRegion.regionIndexedPriorityQueue[Row](4, 1)
      |    val row = new Row(10)
      |    region.put(queue, 0, row, 1L)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def regionIndexedPriorityQueueCanStoreHeapRoot(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionIndexedPriorityQueue[RiftRegion.HeapRoot[Metadata]](4)
      |    region.put(queue, 2, RiftRegion.root(new Metadata(40)), 5L)
      |    region.peekPriority(queue).toInt + region.peek(queue).value.value
      |  }
      |""".stripMargin)

  @Test def regionIndexedPriorityQueueCannotStoreInnerScopedValue(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { outer ?=>
      |    val queue = RiftRegion.regionIndexedPriorityQueue[Row](4, 1)
      |    RiftRegion.scoped { inner ?=>
      |      val row: Row^{inner} = RiftRegion.alloc(new Row(10))
      |      outer.put(queue, 0, row, 1L)
      |    }
      |  }
      |""".stripMargin,
      "cannot flow into capture set {outer}"
    )

  @Test def regionLongIndexedPriorityQueueCanUpdateLexicographicPriority(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionLongIndexedPriorityQueueLexicographic[Row](1, 4)
      |    val low: Row^{region} = RiftRegion.alloc(new Row(10))
      |    val high: Row^{region} = RiftRegion.alloc(new Row(40))
      |    region.put(queue, 0x100000001L, low, 1L, 5L, 2L, -1L)
      |    RiftRegion.put(
      |      region,
      |      queue,
      |      0x200000002L,
      |      high,
      |      1L,
      |      6L,
      |      1L,
      |      -2L
      |    )
      |    region.updatePriority(queue, 0x100000001L, 2L, 0L, 0L, -1L)
      |    val existing = region.get(queue, 0x100000001L)
      |    val first = region.peek(queue)
      |    first.value + existing.value + region.length(queue)
      |  }
      |""".stripMargin)

  @Test def regionLongIndexedPriorityQueueCannotStoreHeapObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionLongIndexedPriorityQueueLexicographic[Row](1, 4)
      |    val row = new Row(10)
      |    region.put(queue, 1L, row, 1L, 2L, 3L, 4L)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def regionLongIndexedPriorityQueueCanStoreHeapRoot(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionLongIndexedPriorityQueue[
      |        RiftRegion.HeapRoot[Metadata]
      |      ](1, 4)
      |    region.put(queue, 0x100000001L, RiftRegion.root(new Metadata(40)), 5L)
      |    region.peekPriority(queue).toInt + region.peek(queue).value.value
      |  }
      |""".stripMargin)

  @Test def regionLongIndexedPriorityQueueCannotStoreInnerScopedValue(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { outer ?=>
      |    val queue = RiftRegion.regionLongIndexedPriorityQueue[Row](1, 4)
      |    RiftRegion.scoped { inner ?=>
      |      val row: Row^{inner} = RiftRegion.alloc(new Row(10))
      |      outer.put(queue, 1L, row, 1L)
      |    }
      |  }
      |""".stripMargin,
      "cannot flow into capture set {outer}"
    )

  @Test def streamingResetRegionArrayEpochCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Record(val key: Int, val value: Int)
      |
      |def ok(): Long =
      |  RiftRegion.streaming { stream ?=>
      |    var total = 0L
      |    var epoch = 0
      |    while epoch < 2 do
      |      total += RiftRegion.reset { region ?=>
      |        val records: Array[Record^{region}]^{region} =
      |          RiftRegion.alloc(new Array[Record^{region}](2))
      |        records(0) = RiftRegion.alloc(new Record(epoch, 20))
      |        records(1) = RiftRegion.alloc(new Record(epoch + 1, 22))
      |        records(0).value.toLong + records(1).value.toLong
      |      }
      |      epoch += 1
      |    total
      |  }
      |""".stripMargin)

  @Test def streamingEpochOpenRegionCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Record(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    var sum = 0
      |    RiftRegion.epoch { epoch ?=>
      |      val record: Record^{epoch} =
      |        RiftRegion.allocOpen(new Record(42))
      |      sum = record.value
      |    }
      |    sum
      |  }
      |""".stripMargin)

  @Test def streamingEpochOpenRegionCannotBeClosedManually(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    RiftRegion.epoch { epoch ?=>
      |      epoch.close()
      |    }
      |  }
      |""".stripMargin,
      "OpenStreamingRegion handles cannot be closed or reset"
    )

  @Test def streamingEpochOpenRegionCannotBeResetManually(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    RiftRegion.epoch { epoch ?=>
      |      epoch.reset()
      |    }
      |  }
      |""".stripMargin,
      "OpenStreamingRegion handles cannot be closed or reset"
    )

  @Test def allocOpenRequiresOpenStreamingRegion(): Unit =
    assertDoesNotCompile("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Record(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val record: Record^{stream} =
      |      RiftRegion.allocOpen(new Record(42))(using stream)
      |    record.value
      |  }
      |""".stripMargin)

  @Test def streamingEpochAllowsStaticMetadataWithAllocOpen(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |object MetadataStore:
      |  val stable: Metadata = new Metadata(41)
      |final class Entry(val metadata: Metadata^)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    RiftRegion.epoch { epoch ?=>
      |      val entry: Entry^{epoch} =
      |        RiftRegion.allocOpen(new Entry(MetadataStore.stable))
      |      entry.metadata.value + 1
      |    }
      |  }
      |""".stripMargin)

  @Test def streamingEpochAllowsHeapRootBridgeWithAllocOpen(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: RiftRegion.HeapRoot[Metadata]^)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    RiftRegion.epoch { epoch ?=>
      |      val root: RiftRegion.HeapRoot[Metadata]^{epoch} =
      |        RiftRegion.root(new Metadata(41))
      |      val entry: Entry^{epoch} =
      |        RiftRegion.allocOpen(new Entry(root))
      |      entry.metadata.value.value + 1
      |    }
      |  }
      |""".stripMargin)

  @Test def streamingEpochOpenRegionHandleCannotEscapeEpoch(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    var leaked: RiftRegion.OpenStreamingRegion^{stream} = null
      |    RiftRegion.epoch { epoch ?=>
      |      leaked = epoch
      |    }
      |  }
      |""".stripMargin,
      "cannot flow into capture set {stream}"
    )

  @Test def streamingEpochValueCannotEscapeParent(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Record(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    var leaked: Record^{stream} = null
      |    RiftRegion.epoch { epoch ?=>
      |      val record: Record^{epoch} =
      |        RiftRegion.allocOpen(new Record(42))
      |      leaked = record
      |    }
      |  }
      |""".stripMargin,
      "cannot flow into capture set {stream}"
    )

  @Test def childStreamingBucketEventGraphCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    final class Bucket(val region: RiftRegion.StreamingRegion^{stream}) {
      |      final class Event(val value: Int, var next: Event^{region})
      |      var head: Event^{region} = null
      |    }
      |
      |    val child = RiftRegion.childStreaming
      |    val bucket: Bucket^{stream} = new Bucket(child)
      |    val bucketRegion = bucket.region
      |    val event: bucket.Event^{bucketRegion} =
      |      RiftRegion.alloc(new bucket.Event(41, null))(using bucketRegion)
      |    bucket.head = event
      |    val result = bucket.head.value + 1
      |    bucket.head = null
      |    child.close()
      |    result
      |  }
      |""".stripMargin)

  @Test def childStreamingHandleCannotEscapeParent(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def bad(): AnyRef =
      |  RiftRegion.streaming { stream ?=>
      |    RiftRegion.childStreaming
      |  }
      |""".stripMargin,
      "Capability `stream` outlives its scope"
    )

  @Test def childWindowBucketEventGraphCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    final class Bucket(val window: RiftRegion.ChildWindow^{stream}) {
      |      final class Event(val value: Int, var next: Event^{window.region})
      |      var head: Event^{window.region} = null
      |    }
      |
      |    val window = RiftRegion.childWindow
      |    val bucket: Bucket^{stream} = new Bucket(window)
      |    val region = bucket.window.region
      |    val event: bucket.Event^{region} =
      |      RiftRegion.alloc(new bucket.Event(41, null))(using region)
      |    bucket.head = event
      |    val result = bucket.head.value + 1
      |    RiftRegion.closeChildWindow(stream, bucket.window) {
      |      bucket.head = null
      |    }
      |    result
      |  }
      |""".stripMargin)

  @Test def childWindowDirectCloseCannotBeCalledFromUserCode(): Unit =
    assertDoesNotCompile("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val window = RiftRegion.childWindow
      |    window.close()
      |  }
      |""".stripMargin)

  @Test def childWindowCannotEscapeParent(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def bad(): AnyRef =
      |  RiftRegion.streaming { stream ?=>
      |    RiftRegion.childWindow
      |  }
      |""".stripMargin,
      "Capability `stream` outlives its scope"
    )

  @Test def childWindowOwnerTokenCanWidenChildRecordsToParent(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val window = RiftRegion.childWindow
      |    val buffer = RiftRegion.objectBuffer[Event](1)
      |    val region = RiftRegion.childRegion(stream, window)
      |    val event: Event^{stream} =
      |      RiftRegion.alloc(new Event(41))(using region)
      |    RiftRegion.append(stream, buffer, event)
      |    val result = RiftRegion.get(stream, buffer, 0).value + 1
      |    RiftRegion.closeChildWindow(stream, window) {
      |      ()
      |    }
      |    result
      |  }
      |""".stripMargin)

  @Test def childBucketEventGraphCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    final class Bucket(val child: RiftRegion.ChildBucket^{stream}) {
      |      final class Event(val value: Int, var next: Event^{child.region})
      |      var head: Event^{child.region} = null
      |    }
      |
      |    val child = RiftRegion.childBucket
      |    val bucket: Bucket^{stream} = new Bucket(child)
      |    val event: bucket.Event^{bucket.child.region} =
      |      RiftRegion.alloc(new bucket.Event(41, null))(using bucket.child.region)
      |    bucket.head = event
      |    val result = bucket.head.value + 1
      |    RiftRegion.closeChildBucket(stream, bucket.child) {
      |      bucket.head = null
      |    }
      |    result
      |  }
      |""".stripMargin)

  @Test def childBucketRawWindowCannotBeCalledFromUserCode(): Unit =
    assertDoesNotCompile("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val child = RiftRegion.childBucket
      |    child.window.close()
      |  }
      |""".stripMargin)

  @Test def streamAppendWindowStoresChildBucketRecords(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val window = RiftRegion.streamAppendWindow[Event](10)
      |    val bucket = RiftRegion.streamAppendWindowBucketFor(stream, window, 7L)
      |    val region = RiftRegion.streamBucketRegion(stream, bucket)
      |    val event: Event^{stream} =
      |      RiftRegion.alloc(new Event(41))(using region)
      |    RiftRegion.appendWindow(stream, window, bucket, event)
      |    var total = 0
      |    RiftRegion.closeAppendWindowBucketsBefore(stream, window, 10L) {
      |      (closedBucket, record) =>
      |        total += record.value
      |    }
      |    total + RiftRegion.appendWindowLength(stream, window) + 1
      |  }
      |""".stripMargin)

  @Test def streamAppendWindowCursorConsumesChildBucketRecords(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val window = RiftRegion.streamAppendWindow[Event](10)
      |    val bucket = RiftRegion.streamAppendWindowBucketFor(stream, window, 7L)
      |    val region = RiftRegion.streamBucketRegion(stream, bucket)
      |    val event: Event^{stream} =
      |      RiftRegion.alloc(new Event(41))(using region)
      |    RiftRegion.appendWindow(stream, window, bucket, event)
      |    var total = 0
      |    RiftRegion.closeAllAppendWindowBucketsWithCursor(stream, window) {
      |      (_, cursor) =>
      |        while cursor.hasNext do
      |          total += cursor.next().value
      |    }
      |    total + RiftRegion.appendWindowLength(stream, window) + 1
      |  }
      |""".stripMargin)

  @Test def streamPageTokenAppendWindowStoresChildBucketRecords(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val window = RiftRegion.streamPageTokenAppendWindow[Event](10)
      |    var total = 0
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
      |    ): Unit =
      |      var current = cursor.nextOwnedOrNull()
      |      while current != null do
      |        val event = current.asInstanceOf[Event^{stream}]
      |        total += event.value + bucket.startSeconds.toInt
      |        current = cursor.nextOwnedOrNull()
      |    val region =
      |      RiftRegion.pageTokenAppendOpenRegionFor(stream, window, 7L, 0L)(consume)
      |    val event: Event^{stream} =
      |      RiftRegion.allocOpen(new Event(41))(using region)
      |    RiftRegion.appendPageToken(stream, window, event)
      |    RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
      |      consume
      |    )
      |    total + 1
      |  }
      |""".stripMargin)

  @Test def streamPageTokenAppendWindowRejectsDirectHeapRecord(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val window = RiftRegion.streamPageTokenAppendWindow[Event](10)
      |    val event: Event^{stream} = new Event(41)
      |    RiftRegion.appendPageToken(stream, window, event)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def pageTokenOpenRegionAllowsStaticMetadata(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |object MetadataStore:
      |  val stable: Metadata = new Metadata(7)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    final class Event(val metadata: Metadata^{stream})
      |        extends RiftRegion.StreamAppendNode
      |    val window = RiftRegion.streamPageTokenAppendWindow[Event](10)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
      |    ): Unit = ()
      |    val region =
      |      RiftRegion.pageTokenAppendOpenRegionFor(stream, window, 7L, 0L)(consume)
      |    val event: Event^{stream} =
      |      RiftRegion.allocOpen(new Event(MetadataStore.stable))(using region)
      |    RiftRegion.appendPageToken(stream, window, event)
      |    RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
      |      consume
      |    )
      |    event.metadata.value
      |  }
      |""".stripMargin)

  @Test def pageTokenOpenRegionAllowsHeapRootBridge(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    final class Event(
      |        val metadata: RiftRegion.HeapRoot[Metadata]^{stream}
      |    ) extends RiftRegion.StreamAppendNode
      |    val window = RiftRegion.streamPageTokenAppendWindow[Event](10)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
      |    ): Unit = ()
      |    val region =
      |      RiftRegion.pageTokenAppendOpenRegionFor(stream, window, 7L, 0L)(consume)
      |    val rooted: RiftRegion.HeapRoot[Metadata]^{region} =
      |      RiftRegion.root(new Metadata(7))(using region)
      |    val event: Event^{stream} =
      |      RiftRegion.allocOpen(new Event(rooted))(using region)
      |    RiftRegion.appendPageToken(stream, window, event)
      |    RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
      |      consume
      |    )
      |    event.metadata.value.value
      |  }
      |""".stripMargin)

  @Test def pageTokenOpenRegionRejectsUnrootedDynamicHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    final class Event(val metadata: Metadata^{stream})
      |        extends RiftRegion.StreamAppendNode
      |    val window = RiftRegion.streamPageTokenAppendWindow[Event](10)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
      |    ): Unit = ()
      |    val metadata = new Metadata(7)
      |    val region =
      |      RiftRegion.pageTokenAppendOpenRegionFor(stream, window, 7L, 0L)(consume)
      |    val event: Event^{stream} =
      |      RiftRegion.allocOpen(new Event(metadata))(using region)
      |    RiftRegion.appendPageToken(stream, window, event)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def pageTokenMapFilterStoresChildBucketRecords(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val operator = RiftRegion.pageTokenMapFilter[Event](10)
      |    var total = 0
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
      |    ): Unit =
      |      while cursor.hasNext do
      |        total += cursor.next().value + bucket.startSeconds.toInt
      |    val region =
      |      RiftRegion.pageTokenMapFilterRegionFor(stream, operator, 7L, 0L)(
      |        consume
      |      )
      |    val event: Event^{stream} =
      |      RiftRegion.alloc(new Event(41))(using region)
      |    RiftRegion.emitPageTokenMapFilter(stream, operator, event)
      |    RiftRegion.closeAllPageTokenMapFilterBucketsWithCursor(stream, operator)(
      |      consume
      |    )
      |    total + 1
      |  }
      |""".stripMargin)

  @Test def pageTokenMapFilterRejectsDirectHeapRecord(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val operator = RiftRegion.pageTokenMapFilter[Event](10)
      |    val event: Event^{stream} = new Event(41)
      |    RiftRegion.emitPageTokenMapFilter(stream, operator, event)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def pageTokenMapFilterOpenRegionAllowsStaticMetadata(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |object MetadataStore:
      |  val stable: Metadata = new Metadata(7)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    final class Event(val metadata: Metadata^{stream})
      |        extends RiftRegion.StreamAppendNode
      |    val operator = RiftRegion.pageTokenMapFilter[Event](10)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
      |    ): Unit = ()
      |    val region =
      |      RiftRegion.pageTokenMapFilterOpenRegionFor(
      |        stream,
      |        operator,
      |        7L,
      |        0L
      |      )(consume)
      |    val event: Event^{stream} =
      |      RiftRegion.allocOpen(new Event(MetadataStore.stable))(using region)
      |    RiftRegion.emitPageTokenMapFilter(stream, operator, event)
      |    event.metadata.value
      |  }
      |""".stripMargin)

  @Test def pageTokenMapFilterOpenRegionAllowsHeapRootBridge(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    final class Event(
      |        val metadata: RiftRegion.HeapRoot[Metadata]^{stream}
      |    ) extends RiftRegion.StreamAppendNode
      |    val operator = RiftRegion.pageTokenMapFilter[Event](10)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
      |    ): Unit = ()
      |    val region =
      |      RiftRegion.pageTokenMapFilterOpenRegionFor(
      |        stream,
      |        operator,
      |        7L,
      |        0L
      |      )(consume)
      |    val root: RiftRegion.HeapRoot[Metadata]^{region} =
      |      RiftRegion.root(new Metadata(7))(using region)
      |    val event: Event^{stream} =
      |      RiftRegion.allocOpen(new Event(root))(using region)
      |    RiftRegion.emitPageTokenMapFilter(stream, operator, event)
      |    event.metadata.value.value
      |  }
      |""".stripMargin)

  @Test def pageTokenMapFilterOpenRegionRejectsUnrootedDynamicMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    final class Event(val metadata: Metadata^{stream})
      |        extends RiftRegion.StreamAppendNode
      |    val operator = RiftRegion.pageTokenMapFilter[Event](10)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
      |    ): Unit = ()
      |    val metadata = new Metadata(7)
      |    val region =
      |      RiftRegion.pageTokenMapFilterOpenRegionFor(
      |        stream,
      |        operator,
      |        7L,
      |        0L
      |      )(consume)
      |    val event: Event^{stream} =
      |      RiftRegion.allocOpen(new Event(metadata))(using region)
      |    RiftRegion.emitPageTokenMapFilter(stream, operator, event)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def pageTokenCountByKeyStoresChildBucketRecords(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val key: Int, val value: Int)
      |    extends RiftRegion.StreamAppendNode
      |
      |def ok(): Long =
      |  RiftRegion.streaming { stream ?=>
      |    val operator = RiftRegion.pageTokenCountByKey[Event](10, 8, 4)
      |    var total = 0L
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        key: Int,
      |        count: Int,
      |        sum: Long
      |    ): Unit =
      |      total += bucket.startSeconds + key.toLong + count.toLong + sum
      |    val region =
      |      RiftRegion.pageTokenCountByKeyRegionFor(
      |        stream,
      |        operator,
      |        7L,
      |        0L
      |      )(consume)
      |    val event: Event^{stream} =
      |      RiftRegion.alloc(new Event(2, 40))(using region)
      |    RiftRegion.appendPageTokenCountByKey(
      |      stream,
      |      operator,
      |      event,
      |      event.key,
      |      event.value.toLong
      |    )
      |    RiftRegion.closeAllPageTokenCountByKeyBuckets(stream, operator)(
      |      consume
      |    )
      |    total
      |  }
      |""".stripMargin)

  @Test def pageTokenCountByKeyRejectsDirectHeapRecord(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val key: Int, val value: Int)
      |    extends RiftRegion.StreamAppendNode
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val operator = RiftRegion.pageTokenCountByKey[Event](10, 8, 4)
      |    val event: Event^{stream} = new Event(2, 40)
      |    RiftRegion.appendPageTokenCountByKey(
      |      stream,
      |      operator,
      |      event,
      |      event.key,
      |      event.value.toLong
      |    )
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def pageTokenCountByKeyOpenRegionAllowsStaticMetadata(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |object MetadataStore:
      |  val stable: Metadata = new Metadata(7)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    final class Event(
      |        val key: Int,
      |        val value: Int,
      |        val metadata: Metadata^{stream}
      |    ) extends RiftRegion.StreamAppendNode
      |    val operator = RiftRegion.pageTokenCountByKey[Event](10, 8, 2)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        key: Int,
      |        count: Int,
      |        valueSum: Long
      |    ): Unit = ()
      |    val region =
      |      RiftRegion.pageTokenCountByKeyOpenRegionFor(
      |        stream,
      |        operator,
      |        7L,
      |        0L
      |      )(consume)
      |    val event: Event^{stream} =
      |      RiftRegion.allocOpen(new Event(1, 2, MetadataStore.stable))(
      |        using region
      |      )
      |    RiftRegion.appendPageTokenCountByKey(
      |      stream,
      |      operator,
      |      event,
      |      event.key,
      |      event.value.toLong
      |    )
      |    event.metadata.value
      |  }
      |""".stripMargin)

  @Test def pageTokenCountByKeyOpenRegionAllowsHeapRootBridge(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    final class Event(
      |        val key: Int,
      |        val value: Int,
      |        val metadata: RiftRegion.HeapRoot[Metadata]^{stream}
      |    ) extends RiftRegion.StreamAppendNode
      |    val operator = RiftRegion.pageTokenCountByKey[Event](10, 8, 2)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        key: Int,
      |        count: Int,
      |        valueSum: Long
      |    ): Unit = ()
      |    val region =
      |      RiftRegion.pageTokenCountByKeyOpenRegionFor(
      |        stream,
      |        operator,
      |        7L,
      |        0L
      |      )(consume)
      |    val root: RiftRegion.HeapRoot[Metadata]^{region} =
      |      RiftRegion.root(new Metadata(7))(using region)
      |    val event: Event^{stream} =
      |      RiftRegion.allocOpen(new Event(1, 2, root))(using region)
      |    RiftRegion.appendPageTokenCountByKey(
      |      stream,
      |      operator,
      |      event,
      |      event.key,
      |      event.value.toLong
      |    )
      |    event.metadata.value.value
      |  }
      |""".stripMargin)

  @Test def pageTokenCountByKeyOpenRegionRejectsUnrootedDynamicMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    final class Event(
      |        val key: Int,
      |        val value: Int,
      |        val metadata: Metadata^{stream}
      |    ) extends RiftRegion.StreamAppendNode
      |    val operator = RiftRegion.pageTokenCountByKey[Event](10, 8, 2)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        key: Int,
      |        count: Int,
      |        valueSum: Long
      |    ): Unit = ()
      |    val metadata = new Metadata(7)
      |    val region =
      |      RiftRegion.pageTokenCountByKeyOpenRegionFor(
      |        stream,
      |        operator,
      |        7L,
      |        0L
      |      )(consume)
      |    val event: Event^{stream} =
      |      RiftRegion.allocOpen(new Event(1, 2, metadata))(using region)
      |    RiftRegion.appendPageTokenCountByKey(
      |      stream,
      |      operator,
      |      event,
      |      event.key,
      |      event.value.toLong
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def epochBufferStoresChildEpochRecords(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val buffer = RiftRegion.epochBuffer[Event]()
      |    var total = 0
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
      |    ): Unit =
      |      while cursor.hasNext do
      |        total += cursor.next().value + bucket.startSeconds.toInt
      |    val region = RiftRegion.epochBufferRegionFor(stream, buffer)
      |    val event: Event^{stream} =
      |      RiftRegion.alloc(new Event(41))(using region)
      |    RiftRegion.appendEpochBuffer(stream, buffer, event)
      |    RiftRegion.closeEpochBufferWithCursor(stream, buffer)(consume)
      |    total + RiftRegion.epochBufferLength(stream, buffer)
      |  }
      |""".stripMargin)

  @Test def epochBufferOpenRegionStoresChildEpochRecords(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val buffer = RiftRegion.epochBuffer[Event]()
      |    var total = 0
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
      |    ): Unit =
      |      var current = cursor.nextOwnedOrNull()
      |      while current != null do
      |        val event = current.asInstanceOf[Event^{stream}]
      |        total += event.value + bucket.startSeconds.toInt
      |        current = cursor.nextOwnedOrNull()
      |    val region = RiftRegion.epochBufferOpenRegionFor(stream, buffer)
      |    val event: Event^{stream} =
      |      RiftRegion.allocOpen(new Event(41))(using region)
      |    RiftRegion.appendEpochBuffer(stream, buffer, event)
      |    RiftRegion.closeEpochBufferWithCursor(stream, buffer)(consume)
      |    total + RiftRegion.epochBufferLength(stream, buffer)
      |  }
      |""".stripMargin)

  @Test def epochBufferOpenRegionAllowsStaticMetadata(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |object MetadataStore:
      |  val stable: Metadata = new Metadata(7)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    final class Event(val metadata: Metadata^{stream})
      |        extends RiftRegion.StreamAppendNode
      |    val buffer = RiftRegion.epochBuffer[Event]()
      |    val region = RiftRegion.epochBufferOpenRegionFor(stream, buffer)
      |    val event: Event^{stream} =
      |      RiftRegion.allocOpen(new Event(MetadataStore.stable))(using region)
      |    RiftRegion.appendEpochBuffer(stream, buffer, event)
      |    event.metadata.value
      |  }
      |""".stripMargin)

  @Test def epochBufferOpenRegionAllowsHeapRootBridge(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    final class Event(
      |        val metadata: RiftRegion.HeapRoot[Metadata]^{stream}
      |    ) extends RiftRegion.StreamAppendNode
      |    val buffer = RiftRegion.epochBuffer[Event]()
      |    val region = RiftRegion.epochBufferOpenRegionFor(stream, buffer)
      |    val root: RiftRegion.HeapRoot[Metadata]^{region} =
      |      RiftRegion.root(new Metadata(7))(using region)
      |    val event: Event^{stream} =
      |      RiftRegion.allocOpen(new Event(root))(using region)
      |    RiftRegion.appendEpochBuffer(stream, buffer, event)
      |    event.metadata.value.value
      |  }
      |""".stripMargin)

  @Test def epochBufferOpenRegionRejectsUnrootedDynamicMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    final class Event(val metadata: Metadata^{stream})
      |        extends RiftRegion.StreamAppendNode
      |    val buffer = RiftRegion.epochBuffer[Event]()
      |    val metadata = new Metadata(7)
      |    val region = RiftRegion.epochBufferOpenRegionFor(stream, buffer)
      |    val event: Event^{stream} =
      |      RiftRegion.allocOpen(new Event(metadata))(using region)
      |    RiftRegion.appendEpochBuffer(stream, buffer, event)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def epochBufferRejectsDirectHeapRecord(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val buffer = RiftRegion.epochBuffer[Event]()
      |    val event: Event^{stream} = new Event(41)
      |    RiftRegion.appendEpochBuffer(stream, buffer, event)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def transactionRegionStoresChildRecordsInMultipleLists(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val tx = RiftRegion.transactionRegion(2)
      |    val input = RiftRegion.transactionList[Event](stream, tx, 0)
      |    val output = RiftRegion.transactionList[Event](stream, tx, 1)
      |    val region = RiftRegion.transactionRegionFor(stream, tx)
      |    val event: Event^{stream} =
      |      RiftRegion.alloc(new Event(41))(using region)
      |    RiftRegion.appendTransactionList(stream, input, event)
      |
      |    RiftRegion.drainTransactionListWithCursor(stream, input) { cursor =>
      |      while cursor.hasNext do
      |        val next: Event^{stream} =
      |          RiftRegion.alloc(new Event(cursor.next().value + 1))(using region)
      |        RiftRegion.appendTransactionList(stream, output, next)
      |    }
      |
      |    var total = 0
      |    RiftRegion.drainTransactionListWithCursor(stream, output) { cursor =>
      |      while cursor.hasNext do
      |        total += cursor.next().value
      |    }
      |    RiftRegion.closeTransactionRegion(stream, tx)
      |    total + RiftRegion.transactionListLength(stream, input)
      |  }
      |""".stripMargin)

  @Test def transactionRegionRejectsDirectHeapRecord(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val tx = RiftRegion.transactionRegion(1)
      |    val input = RiftRegion.transactionList[Event](stream, tx, 0)
      |    RiftRegion.transactionRegionFor(stream, tx)
      |    val event: Event^{stream} = new Event(41)
      |    RiftRegion.appendTransactionList(stream, input, event)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def streamChunkAppendWindowStoresChildBucketRecords(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val window = RiftRegion.streamChunkAppendWindow[Event](10, 4)
      |    var total = 0
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamChunkCursor[Event]^{stream}
      |    ): Unit =
      |      while cursor.hasNext do
      |        total += cursor.next().value + bucket.startSeconds.toInt
      |    val region =
      |      RiftRegion.chunkAppendRegionFor(stream, window, 7L, 0L)(consume)
      |    val event: Event^{stream} =
      |      RiftRegion.alloc(new Event(41))(using region)
      |    RiftRegion.appendChunkToken(stream, window, event)
      |    RiftRegion.closeAllChunkAppendBucketsWithCursor(stream, window)(
      |      consume
      |    )
      |    total + 1
      |  }
      |""".stripMargin)

  @Test def streamChunkAppendWindowRejectsDirectHeapRecord(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val window = RiftRegion.streamChunkAppendWindow[Event](10, 4)
      |    val event: Event^{stream} = new Event(41)
      |    RiftRegion.appendChunkToken(stream, window, event)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def streamAppendWindowPrependsChildBucketRecords(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val window = RiftRegion.streamAppendWindow[Event](10)
      |    val bucket = RiftRegion.streamAppendWindowBucketFor(stream, window, 7L)
      |    val region = RiftRegion.streamBucketRegion(stream, bucket)
      |    val event: Event^{stream} =
      |      RiftRegion.alloc(new Event(41))(using region)
      |    RiftRegion.prependWindow(stream, window, bucket, event)
      |    var total = 0
      |    RiftRegion.closeAllAppendWindowBucketsWithCursor(stream, window) {
      |      (_, cursor) =>
      |        while cursor.hasNext do
      |          total += cursor.next().value
      |    }
      |    total + RiftRegion.appendWindowLength(stream, window) + 1
      |  }
      |""".stripMargin)

  @Test def streamAppendWindowRejectsDirectHeapRecord(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val window = RiftRegion.streamAppendWindow[Event](10)
      |    val bucket = RiftRegion.streamAppendWindowBucketFor(stream, window, 7L)
      |    val event: Event^{stream} = new Event(41)
      |    RiftRegion.appendWindow(stream, window, bucket, event)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def streamAppendWindowPrependRejectsDirectHeapRecord(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val window = RiftRegion.streamAppendWindow[Event](10)
      |    val bucket = RiftRegion.streamAppendWindowBucketFor(stream, window, 7L)
      |    val event: Event^{stream} = new Event(41)
      |    RiftRegion.prependWindow(stream, window, bucket, event)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def streamJoinWindowStoresChildBucketRecords(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val join = RiftRegion.streamJoinWindow[Event](10, 16)
      |    val bucket = RiftRegion.streamJoinWindowBucketFor(stream, join, 7L)
      |    val region = RiftRegion.streamBucketRegion(stream, bucket)
      |    val event: Event^{stream} =
      |      RiftRegion.alloc(new Event(41))(using region)
      |    val counts = RiftRegion.putJoinLeftInBucketAndCounts(stream, join, bucket, 3, event)
      |    val count = (counts >>> 32).toInt
      |    var total = 0
      |    RiftRegion.closeAllJoinWindowBucketsWithCursor(stream, join) {
      |      (_, cursor) =>
      |        while cursor.hasNext do
      |          val item = cursor.next()
      |          total += item.value
      |          RiftRegion.removeJoinLeftAndCounts(stream, join, 3)
      |    }
      |    total + count + RiftRegion.joinWindowLength(stream, join)
      |  }
      |""".stripMargin)

  @Test def streamJoinWindowRejectsDirectHeapRecord(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val join = RiftRegion.streamJoinWindow[Event](10, 16)
      |    val bucket = RiftRegion.streamJoinWindowBucketFor(stream, join, 7L)
      |    val event: Event^{stream} = new Event(41)
      |    RiftRegion.putJoinLeftInBucket(stream, join, bucket, 3, event)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def streamJoinWindowPackedPutRejectsDirectHeapRecord(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val join = RiftRegion.streamJoinWindow[Event](10, 16)
      |    val bucket = RiftRegion.streamJoinWindowBucketFor(stream, join, 7L)
      |    val event: Event^{stream} = new Event(41)
      |    RiftRegion.putJoinLeftInBucketAndCounts(stream, join, bucket, 3, event)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def streamWindowFoldStoresChildBucketRecords(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val key: Int, val delta: Long, val value: Int)
      |    extends RiftRegion.StreamAppendNode
      |
      |def ok(): Long =
      |  RiftRegion.streaming { stream ?=>
      |    val fold = RiftRegion.streamWindowFold[Event](10, 16)
      |    val bucket = RiftRegion.streamWindowFoldBucketFor(stream, fold, 7L)
      |    val region = RiftRegion.streamBucketRegion(stream, bucket)
      |    val event: Event^{stream} =
      |      RiftRegion.alloc(new Event(3, 41L, 5))(using region)
      |    val total = RiftRegion.putFoldInBucket(stream, fold, bucket, 3, event.delta, event)
      |    var closed = 0L
      |    RiftRegion.closeAllFoldBucketsWithCursor(stream, fold) {
      |      (_, cursor) =>
      |        while cursor.hasNext do
      |          val item = cursor.next()
      |          closed += item.value
      |          RiftRegion.removeFoldContribution(stream, fold, item.key, item.delta)
      |    }
      |    total + closed + RiftRegion.foldWindowLength(stream, fold)
      |  }
      |""".stripMargin)

  @Test def streamWindowFoldRejectsDirectHeapRecord(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val key: Int, val delta: Long)
      |    extends RiftRegion.StreamAppendNode
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val fold = RiftRegion.streamWindowFold[Event](10, 16)
      |    val bucket = RiftRegion.streamWindowFoldBucketFor(stream, fold, 7L)
      |    val event: Event^{stream} = new Event(3, 41L)
      |    RiftRegion.putFoldInBucket(stream, fold, bucket, event.key, event.delta, event)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def epochFoldStoresChildBucketRecords(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val key: Int, val delta: Long, val value: Int)
      |    extends RiftRegion.StreamAppendNode
      |
      |def ok(): Long =
      |  RiftRegion.streaming { stream ?=>
      |    val fold = RiftRegion.epochFold[Event](10, 16)
      |    val region = RiftRegion.epochFoldRegionFor(stream, fold, 7L)
      |    val event: Event^{stream} =
      |      RiftRegion.alloc(new Event(3, 41L, 5))(using region)
      |    val total =
      |      RiftRegion.putEpochFold(stream, fold, event.key, event.delta, event)
      |    var closed = 0L
      |    RiftRegion.closeEpochFoldCurrentBucketAndClear(stream, fold) {
      |      (_, cursor) =>
      |        while cursor.hasNext do
      |          closed += cursor.next().value
      |    }
      |    total + closed + RiftRegion.epochFoldKeyCount(stream, fold)
      |  }
      |""".stripMargin)

  @Test def epochFoldRejectsDirectHeapRecord(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val key: Int, val delta: Long)
      |    extends RiftRegion.StreamAppendNode
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val fold = RiftRegion.epochFold[Event](10, 16)
      |    val event: Event^{stream} = new Event(3, 41L)
      |    RiftRegion.putEpochFold(stream, fold, event.key, event.delta, event)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def checkedMutableLinkedListBuilderCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int, val next: Node^{region})
      |    var head: Node^{region} = null
      |    var i = 0
      |    while i < 4 do
      |      head = RiftRegion.alloc(new Node(i, head))
      |      i += 1
      |    head.value + head.next.value
      |  }
      |""".stripMargin)

  @Test def regionListBuilderCompiles(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int) extends RiftRegion.RegionListNode
      |    val list = RiftRegion.regionList[Node]()
      |    val first: Node^{region} = RiftRegion.alloc(new Node(1))
      |    val second: Node^{region} = RiftRegion.alloc(new Node(2))
      |    RiftRegion.prependRegionList(region, list, first)
      |    RiftRegion.prependRegionList(region, list, second)
      |    val head = RiftRegion.regionListHead(region, list)
      |    head.value + RiftRegion.regionListNext(region, head).value
      |  }
      |""".stripMargin)

  @Test def regionListRejectsDirectHeapRecord(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int) extends RiftRegion.RegionListNode
      |    val list = RiftRegion.regionList[Node]()
      |    val node: Node^{region} = new Node(1)
      |    RiftRegion.prependRegionList(region, list, node)
      |  }
      |""".stripMargin,
      "Rift checked object buffer cannot store an unrooted heap object"
    )

  @Test def mutableRegionHeadCannotBeRetaggedFromHeapObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int, val next: Node^{region})
      |    final class Holder(val node: Node^{region})
      |    var head: Node^{region} = null
      |    val heapNode: Node^{region} = new Node(1, null)
      |    head = heapNode
      |    val holder: Holder^{region} = RiftRegion.alloc(new Holder(head))
      |    holder.node.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def topwordBufferCanStoreRecordsWithRootedMetadata(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val shard: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class WordRecord(
      |        val key: Int,
      |        val metadata: RiftRegion.HeapRoot[Metadata]^{region}
      |    )
      |    val buffer = RiftRegion.objectBuffer[WordRecord](2)
      |    val rooted = RiftRegion.root(new Metadata(2))
      |    val record: WordRecord^{region} =
      |      RiftRegion.alloc(new WordRecord(40, rooted))
      |    RiftRegion.append(region, buffer, record)
      |    val stored = RiftRegion.get(region, buffer, 0)
      |    stored.key + stored.metadata.value.shard
      |  }
      |""".stripMargin)

  @Test def graphChiSubintervalCanUseRootedHeapVertexMetadata(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Vertex(val value: Int)
      |final class EdgeUpdate(
      |    val src: RiftRegion.HeapRoot[Vertex]^,
      |    val dst: Int,
      |    val next: EdgeUpdate^
      |)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val vertex = new Vertex(41)
      |    RiftRegion.reset { region ?=>
      |      val update: EdgeUpdate^{region} =
      |        RiftRegion.alloc(new EdgeUpdate(RiftRegion.root(vertex), 1, null))
      |      update.src.value.value + update.dst
      |    }
      |  }
      |""".stripMargin)

  @Test def graphChiSubintervalCannotStoreUnrootedHeapVertex(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Vertex(val value: Int)
      |final class EdgeUpdate(val src: Vertex^, val dst: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val vertex = new Vertex(41)
      |    RiftRegion.reset { region ?=>
      |      val update: EdgeUpdate^{region} =
      |        RiftRegion.alloc(new EdgeUpdate(vertex, 1))
      |      update.src.value + update.dst
      |    }
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def streamingResetValueCannotBeStoredInOuterBuffer(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class EdgeUpdate(val dst: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val buffer = RiftRegion.objectBuffer[EdgeUpdate](1)
      |    RiftRegion.reset { region ?=>
      |      val update: EdgeUpdate^{region} =
      |        RiftRegion.alloc(new EdgeUpdate(1))
      |      RiftRegion.append(stream, buffer, update)
      |    }
      |    RiftRegion.get(stream, buffer, 0).dst
      |  }
      |""".stripMargin,
      "cannot flow into capture set {stream}"
    )

  @Test def trustedOpenAllocationAllowsBenchmarkLinkedObjects(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Node(val value: Int, val next: Node^)
      |
      |def ok(): Int =
      |  val region = RiftRegion.open(RiftRegion.HPZone)
      |  try
      |    var head: Node = null
      |    var i = 0
      |    while i < 4 do
      |      head = region.alloc(new Node(i, head))
      |      i += 1
      |    head.value
      |  finally region.close()
      |""".stripMargin)

  @Test def streamingResetValueCannotEscapeEpoch(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val leaked = RiftRegion.reset { region ?=>
      |      RiftRegion.alloc(new Box(1))
      |    }
      |    leaked.value
      |  }
      |""".stripMargin,
      "Capability `region` outlives its scope"
    )
}
