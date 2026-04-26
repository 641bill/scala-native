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

  @Test def scopedValueCannotEscapeByReturn(): Unit =
    assertDoesNotCompile("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def bad(): AnyRef =
      |  RiftRegion.scoped { region ?=>
      |    RiftRegion.alloc(new Box(1))
      |  }
      |""".stripMargin)

  @Test def innerScopedValueCannotEscapeOuterScope(): Unit =
    assertDoesNotCompile("""
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
      |""".stripMargin)

  @Test def closureCapturingScopedValueCannotEscape(): Unit =
    assertDoesNotCompile("""
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
      |""".stripMargin)

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
    assertDoesNotCompile("""
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
      |""".stripMargin)

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

  @Test def objectBufferCannotStoreInnerScopedValue(): Unit =
    assertDoesNotCompile("""
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
      |""".stripMargin)

  @Test def objectBufferCannotEscapeScopedRegion(): Unit =
    assertDoesNotCompile("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def bad(): AnyRef =
      |  RiftRegion.scoped { region ?=>
      |    RiftRegion.objectBuffer[Leaf](1)
      |  }
      |""".stripMargin)

  @Test def streamingResetValueCannotEscapeEpoch(): Unit =
    assertDoesNotCompile("""
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
      |""".stripMargin)
}
