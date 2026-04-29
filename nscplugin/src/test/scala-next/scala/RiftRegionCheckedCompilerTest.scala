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
