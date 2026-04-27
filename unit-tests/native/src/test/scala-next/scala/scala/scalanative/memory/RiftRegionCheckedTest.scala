package scala.scalanative.memory

import org.junit.Assert._
import org.junit.Test

import scala.scalanative.runtime.Intrinsics.castRawPtrToLong
import scala.scalanative.runtime.toRawPtr
import scala.scalanative.unsafe.Ptr

import language.experimental.captureChecking

private final class RiftCheckedLeaf(val value: Int)
private final class RiftCheckedNode(
    val value: Int,
    val left: RiftCheckedLeaf^,
    val next: RiftCheckedNode^)
private final class RiftCheckedMetadata(val value: Int)
private final class RiftCheckedRootedNode(
    val metadata: RiftRegion.HeapRoot[RiftCheckedMetadata]^)
private object RiftCheckedStaticMetadata:
  val metadata: RiftCheckedMetadata = new RiftCheckedMetadata(2)
private final class RiftCheckedStaticEntry(
    val metadata: RiftCheckedMetadata^)

class RiftRegionCheckedTest {
  private def address(ptr: Ptr[Byte]): Long =
    castRawPtrToLong(toRawPtr(ptr))

  @Test def scopedRegionAllocatesOrdinaryObjectGraph(): Unit = {
    RiftRegion.init(1)
    try {
      val sum = RiftRegion.scoped { region ?=>
        val left: RiftCheckedLeaf^{region} =
          RiftRegion.alloc(new RiftCheckedLeaf(21))
        val right: RiftCheckedNode^{region} =
          RiftRegion.alloc(new RiftCheckedNode(2, left, null))
        val root: RiftCheckedNode^{region} =
          region.alloc(new RiftCheckedNode(19, left, right))

        root.value + root.left.value + root.next.value
      }

      assertEquals(42, sum)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionAllowsNonEscapingClosureCapture(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.scoped { region ?=>
        val leaf = RiftRegion.alloc(new RiftCheckedLeaf(40))
        val addCaptured = (n: Int) => leaf.value + n

        addCaptured(2)
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionAllowsExplicitHeapRootHandle(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.scoped { region ?=>
        val metadata = new RiftCheckedMetadata(42)
        val rooted = RiftRegion.root(metadata)
        val node = RiftRegion.alloc(new RiftCheckedRootedNode(rooted))

        node.metadata.value.value
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionAllowsStaticHeapMetadata(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.scoped { region ?=>
        val entry: RiftCheckedStaticEntry^{region} =
          RiftRegion.alloc(
            new RiftCheckedStaticEntry(RiftCheckedStaticMetadata.metadata)
          )
        entry.metadata.value + 40
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionAllowsRegionOwnedArraysWithHeapRoots(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.scoped { region ?=>
        val leaves: Array[RiftCheckedLeaf^{region}]^{region} =
          RiftRegion.alloc(new Array[RiftCheckedLeaf^{region}](2))
        leaves(0) = RiftRegion.alloc(new RiftCheckedLeaf(20))
        leaves(1) = RiftRegion.alloc(new RiftCheckedLeaf(21))

        val roots:
          Array[RiftRegion.HeapRoot[RiftCheckedMetadata]^{region}]^{region} =
          RiftRegion.alloc(
            new Array[RiftRegion.HeapRoot[RiftCheckedMetadata]^{region}](1)
          )
        roots(0) = RiftRegion.root(new RiftCheckedMetadata(1))

        leaves(0).value + leaves(1).value + roots(0).value.value
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionAllowsObjectBufferWithHeapRoots(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.scoped { region ?=>
        val leaves = RiftRegion.objectBuffer[RiftCheckedLeaf](2)
        val left: RiftCheckedLeaf^{region} =
          RiftRegion.alloc(new RiftCheckedLeaf(20))
        val right: RiftCheckedLeaf^{region} =
          RiftRegion.alloc(new RiftCheckedLeaf(21))
        RiftRegion.append(region, leaves, left)
        RiftRegion.append(region, leaves, right)

        val roots =
          RiftRegion.objectBuffer[RiftRegion.HeapRoot[RiftCheckedMetadata]](1)
        RiftRegion.append(
          region,
          roots,
          RiftRegion.root(new RiftCheckedMetadata(1))
        )

        RiftRegion.get(region, leaves, 0).value +
          RiftRegion.get(region, leaves, 1).value +
          RiftRegion.get(region, roots, 0).value.value
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionAllowsObjectBufferOwnerMethods(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.scoped { region ?=>
        val leaves = RiftRegion.objectBuffer[RiftCheckedLeaf](2)
        val left: RiftCheckedLeaf^{region} =
          RiftRegion.alloc(new RiftCheckedLeaf(20))
        val right: RiftCheckedLeaf^{region} =
          RiftRegion.alloc(new RiftCheckedLeaf(21))
        region.append(leaves, left)
        region.append(leaves, right)

        val roots =
          RiftRegion.objectBuffer[RiftRegion.HeapRoot[RiftCheckedMetadata]](1)
        region.append(roots, RiftRegion.root(new RiftCheckedMetadata(1)))

        region.get(leaves, 0).value +
          region.get(leaves, 1).value +
          region.get(roots, 0).value.value +
          region.length(roots) - 1
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionAllowsRegionBufferGrowthWithHeapRoots(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.scoped { region ?=>
        val leaves = RiftRegion.regionBuffer[RiftCheckedLeaf](1)
        region.append(leaves, RiftRegion.alloc(new RiftCheckedLeaf(20)))
        region.append(leaves, RiftRegion.alloc(new RiftCheckedLeaf(21)))

        val roots =
          RiftRegion.regionBuffer[RiftRegion.HeapRoot[RiftCheckedMetadata]](0)
        region.append(roots, RiftRegion.root(new RiftCheckedMetadata(1)))

        region.get(leaves, 0).value +
          region.get(leaves, 1).value +
          region.get(roots, 0).value.value +
          region.length(roots) - 1
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionAllowsTopWordBufferWithRootedMetadata(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.scoped { region ?=>
        final class WordRecord(
            val key: Int,
            val weight: Int,
            val metadata: RiftRegion.HeapRoot[RiftCheckedMetadata]^{region})

        val buffer = RiftRegion.objectBuffer[WordRecord](2)
        val metadata = RiftRegion.root(new RiftCheckedMetadata(1))
        val first: WordRecord^{region} =
          RiftRegion.alloc(new WordRecord(10, 20, metadata))
        val second: WordRecord^{region} =
          RiftRegion.alloc(new WordRecord(11, 10, metadata))

        RiftRegion.append(region, buffer, first)
        RiftRegion.append(region, buffer, second)

        val a = RiftRegion.get(region, buffer, 0)
        val b = RiftRegion.get(region, buffer, 1)
        a.key + a.weight + b.weight + a.metadata.value.value
      }

      assertEquals(41, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamingResetBlockReturnsOnlyNonLocalValues(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        val first = RiftRegion.reset { region ?=>
          val leaf = RiftRegion.alloc(new RiftCheckedLeaf(17))
          leaf.value
        }
        val second = RiftRegion.reset { region ?=>
          val leaf = region.alloc(new RiftCheckedLeaf(25))
          leaf.value
        }

        first + second
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def childWindowClosesThroughCleanupBoundary(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int)

        val window = RiftRegion.childWindow
        val child = RiftRegion.childRegion(stream, window)
        val event: Event^{stream} =
          RiftRegion.alloc(new Event(41))(using child)
        var retained: Event^{stream} = event

        RiftRegion.closeChildWindow(stream, window) {
          retained = null
        }

        assertTrue(window.isClosed)
        assertThrows(
          classOf[IllegalStateException],
          () => RiftRegion.childRegion(stream, window)
        )

        42
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def childBucketClosesThroughCleanupBoundary(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int)

        val bucket = RiftRegion.childBucket
        val child = RiftRegion.childBucketRegion(stream, bucket)
        val event: Event^{stream} =
          RiftRegion.alloc(new Event(41))(using child)
        var retained: Event^{stream} = event

        RiftRegion.closeChildBucket(stream, bucket) {
          retained = null
        }

        assertTrue(bucket.isClosed)
        assertThrows(
          classOf[IllegalStateException],
          () => RiftRegion.childBucketRegion(stream, bucket)
        )

        42
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionAllowsMutableLinkedListBuilder(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.scoped { region ?=>
        final class Node(val value: Int, val next: Node^{region})
        var head: Node^{region} = null
        var i = 0
        while (i < 4) {
          head = RiftRegion.alloc(new Node(i, head))
          i += 1
        }

        var sum = 0
        var current = head
        while (current != null) {
          sum += current.value
          current = current.next
        }
        sum
      }

      assertEquals(6, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamingRegionAllowsGraphChiSubintervalUpdates(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        val values = new Array[Long](4)
        values(0) = 1L
        values(1) = 2L
        values(2) = 3L
        values(3) = 4L

        var subinterval = 0
        while (subinterval < 2) {
          val currentSubinterval = subinterval
          RiftRegion.reset { region ?=>
            final class EdgeUpdate(
                val src: Int,
                val dst: Int,
                val delta: Int,
                val next: EdgeUpdate^{region})

            var updates: EdgeUpdate^{region} = null
            var i = 0
            while (i < 2) {
              val src = (currentSubinterval + i) & 3
              val dst = ((currentSubinterval * 2) + i) & 3
              updates =
                RiftRegion.alloc(new EdgeUpdate(src, dst, i + 1, updates))
              i += 1
            }

            var current = updates
            while (current != null) {
              values(current.dst) =
                values(current.dst) + values(current.src) + current.delta
              current = current.next
            }
          }
          subinterval += 1
        }

        values.sum
      }

      assertEquals(28L, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamingResetRunsAfterException(): Unit = {
    RiftRegion.init(1)
    try {
      RiftRegion.streaming { stream ?=>
        var beforeThrow = 0L
        val expected = new RuntimeException("expected")

        try {
          RiftRegion.reset { region ?=>
            val first = region.alloc(64, 16)
            beforeThrow = address(first)
            throw expected
          }
        } catch {
          case actual: RuntimeException if actual eq expected => ()
        }

        val afterThrow = stream.alloc(64, 16)
        assertEquals(beforeThrow, address(afterThrow))
      }
    } finally {
      RiftRegion.shutdown()
    }
  }
}
