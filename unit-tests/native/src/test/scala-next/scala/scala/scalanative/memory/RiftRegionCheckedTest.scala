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

  @Test def safeZoneBackedChildBucketsCloseThroughCursorBoundary(): Unit = {
    val total = RiftRegion.streamingSafeZone { stream ?=>
      final class Event(val value: Int)
          extends RiftRegion.StreamAppendNode

      val window = RiftRegion.streamAppendWindow[Event](10L)
      val bucket = RiftRegion.streamAppendWindowBucketFor(stream, window, 7L)
      val child = RiftRegion.streamBucketRegion(stream, bucket)
      val event: Event^{stream} =
        RiftRegion.alloc(new Event(41))(using child)
      RiftRegion.appendWindow(stream, window, bucket, event)

      var sum = 0
      RiftRegion.closeAllAppendWindowBucketsWithCursor(stream, window) {
        (_, cursor) =>
          while (cursor.hasNext)
            sum += cursor.next().value
      }

      assertTrue(bucket.isClosed)
      assertThrows(
        classOf[IllegalStateException],
        () => {
          val afterClose = RiftRegion.alloc(new Event(1))(using child)
          java.lang.System.identityHashCode(afterClose)
          ()
        }
      )
      sum + 1
    }

    assertEquals(42, total)
  }

  @Test def safeZoneBackedStreamingRejectsUnsupportedRawAndReset(): Unit = {
    RiftRegion.streamingSafeZone { stream ?=>
      assertThrows(
        classOf[UnsupportedOperationException],
        () => stream.alloc(8)
      )
      assertThrows(
        classOf[UnsupportedOperationException],
        () => stream.reset()
      )
    }
  }

  @Test def streamBucketArenaReusesAndClosesBuckets(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int)

        val arena = RiftRegion.streamBucketArena(10)
        val first = RiftRegion.streamBucketFor(stream, arena, 7L)
        val same = RiftRegion.streamBucketFor(stream, arena, 9L)
        val next = RiftRegion.streamBucketFor(stream, arena, 12L)
        assertTrue(first.asInstanceOf[AnyRef] eq same.asInstanceOf[AnyRef])
        assertFalse(first.asInstanceOf[AnyRef] eq next.asInstanceOf[AnyRef])
        assertEquals(0L, first.startSeconds)
        assertEquals(10L, next.startSeconds)

        val firstRegion = RiftRegion.streamBucketRegion(stream, first)
        val event: Event^{stream} =
          RiftRegion.alloc(new Event(41))(using firstRegion)
        var retained: Event^{stream} = event

        assertFalse(RiftRegion.hasStreamBucketsBefore(stream, arena, 9L))
        assertTrue(RiftRegion.hasStreamBucketsBefore(stream, arena, 10L))

        RiftRegion.closeStreamBucketsBefore(stream, arena, 10L) { bucket =>
          if (bucket.startSeconds == 0L) retained = null
        }

        assertTrue(first.isClosed)
        assertTrue(next.isOpen)
        assertThrows(
          classOf[IllegalStateException],
          () => RiftRegion.streamBucketRegion(stream, first)
        )

        RiftRegion.closeAllStreamBuckets(stream, arena) { _ => () }
        assertTrue(next.isClosed)
        42
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def regionLongIndexedPriorityQueueRanksAndRehashes(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.scoped { region ?=>
        val queue =
          RiftRegion.regionLongIndexedPriorityQueueLexicographic[
            RiftCheckedLeaf
          ](1, 4)
        val ten: RiftCheckedLeaf^{region} =
          RiftRegion.alloc(new RiftCheckedLeaf(10))
        val twenty: RiftCheckedLeaf^{region} =
          RiftRegion.alloc(new RiftCheckedLeaf(20))
        val thirty: RiftCheckedLeaf^{region} =
          RiftRegion.alloc(new RiftCheckedLeaf(30))
        val forty: RiftCheckedLeaf^{region} =
          RiftRegion.alloc(new RiftCheckedLeaf(40))

        region.put(queue, 10L, ten, 5L, 100L, 10L, -10L)
        region.put(queue, 20L, twenty, 5L, 110L, 20L, -20L)
        region.put(queue, 30L, thirty, 5L, 110L, 20L, -30L)
        region.put(queue, 40L, forty, 1L, 0L, 0L, -40L)

        assertTrue(region.tableCapacity(queue) >= 8)
        assertEquals(4, region.length(queue))
        assertEquals(20L, region.peekKey(queue))
        assertEquals(20, region.peek(queue).value)

        assertTrue(
          region.updatePriority(queue, 10L, 6L, 0L, 0L, -10L)
        )
        assertEquals(10L, region.peekKey(queue))
        assertEquals(10, region.pop(queue).value)

        assertTrue(region.remove(queue, 20L))
        assertFalse(region.contains(queue, 20L))
        assertEquals(30, region.get(queue, 30L).value)
        assertEquals(2, region.length(queue))

        42
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def regionLongIndexedPriorityQueueReplacesValuesForKey(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.scoped { region ?=>
        val queue =
          RiftRegion.regionLongIndexedPriorityQueue[RiftCheckedLeaf](1, 4)
        val oldValue: RiftCheckedLeaf^{region} =
          RiftRegion.alloc(new RiftCheckedLeaf(10))
        val newValue: RiftCheckedLeaf^{region} =
          RiftRegion.alloc(new RiftCheckedLeaf(40))
        val other: RiftCheckedLeaf^{region} =
          RiftRegion.alloc(new RiftCheckedLeaf(2))

        RiftRegion.put(region, queue, 1234567890123L, oldValue, 1L)
        RiftRegion.put(region, queue, 1234567890123L, newValue, 5L)
        RiftRegion.put(region, queue, -7L, other, 3L)

        assertEquals(2, RiftRegion.length(region, queue))
        assertEquals(1234567890123L, RiftRegion.peekKey(region, queue))
        assertEquals(40, RiftRegion.get(region, queue, 1234567890123L).value)
        assertTrue(RiftRegion.updatePriority(region, queue, -7L, 6L))
        assertEquals(-7L, RiftRegion.peekKey(region, queue))

        RiftRegion.pop(region, queue).value
      }

      assertEquals(2, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamWindowLongIndexedRankRanksAndClosesBucket(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Row(val value: Int)

        val rank =
          RiftRegion.streamWindowLongIndexedRankLexicographic[Row](10, 1, 4)
        val firstBucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
        val child = RiftRegion.streamBucketRegion(stream, firstBucket)
        val first: Row^{stream} =
          RiftRegion.alloc(new Row(10))(using child)
        val second: Row^{stream} =
          RiftRegion.alloc(new Row(20))(using child)
        val lowerKey: Row^{stream} =
          RiftRegion.alloc(new Row(30))(using child)
        val lowPriority: Row^{stream} =
          RiftRegion.alloc(new Row(40))(using child)

        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          firstBucket,
          0x100000001L,
          first,
          5L,
          100L,
          1L,
          -1L
        )
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          firstBucket,
          0x200000002L,
          second,
          5L,
          110L,
          2L,
          -2L
        )
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          firstBucket,
          0x300000003L,
          lowerKey,
          5L,
          110L,
          2L,
          -3L
        )
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          firstBucket,
          0x400000004L,
          lowPriority,
          1L,
          0L,
          0L,
          -4L
        )

        assertEquals(0x200000002L, RiftRegion.peekWindowRankKey(stream, rank))
        assertEquals(20, RiftRegion.peekWindowRank(stream, rank).value)
        assertTrue(
          RiftRegion.updateWindowRankPriority(
            stream,
            rank,
            0x100000001L,
            6L,
            0L,
            0L,
            -1L
          )
        )
        assertEquals(0x100000001L, RiftRegion.peekWindowRankKey(stream, rank))

        var removedCount = 0
        var removedSum = 0
        RiftRegion.closeWindowRankBucketsBeforeWithEntries(
          stream,
          rank,
          10L
        ) { (_, _, value) =>
          removedCount += 1
          removedSum += value.value
        } { _ => () }

        assertTrue(firstBucket.isClosed)
        assertEquals(0, RiftRegion.windowRankLength(stream, rank))
        removedCount + removedSum
      }

      assertEquals(104, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamWindowLongIndexedRankMovesKeyBetweenBuckets(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Row(val value: Int)

        val rank = RiftRegion.streamWindowLongIndexedRank[Row](10, 1, 4)
        val firstBucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
        val firstChild = RiftRegion.streamBucketRegion(stream, firstBucket)
        val first: Row^{stream} =
          RiftRegion.alloc(new Row(20))(using firstChild)
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          firstBucket,
          1234567890123L,
          first,
          1L
        )

        val secondBucket = RiftRegion.streamWindowBucketFor(stream, rank, 17L)
        val secondChild = RiftRegion.streamBucketRegion(stream, secondBucket)
        val second: Row^{stream} =
          RiftRegion.alloc(new Row(43))(using secondChild)
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          secondBucket,
          1234567890123L,
          second,
          9L
        )

        RiftRegion.closeWindowRankBucketsBefore(stream, rank, 10L) { _ => () }

        assertTrue(firstBucket.isClosed)
        assertTrue(RiftRegion.containsWindowRank(stream, rank, 1234567890123L))
        assertEquals(1, RiftRegion.windowRankLength(stream, rank))
        assertEquals(1234567890123L, RiftRegion.peekWindowRankKey(stream, rank))
        RiftRegion.getWindowRank(stream, rank, 1234567890123L).value
      }

      assertEquals(43, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamWindowLongIndexedRankCloseSkipsAlreadyPoppedKeys(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Row(val value: Int)

        val rank = RiftRegion.streamWindowLongIndexedRank[Row](10, 1, 4)
        val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
        val child = RiftRegion.streamBucketRegion(stream, bucket)
        val row: Row^{stream} =
          RiftRegion.alloc(new Row(41))(using child)
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          bucket,
          -7L,
          row,
          7L
        )

        val popped = RiftRegion.popWindowRank(stream, rank).value
        var removedEntries = 0
        RiftRegion.closeAllWindowRankBucketsWithEntries(stream, rank) {
          (_, _, _) =>
            removedEntries += 1
        } { _ => () }

        assertTrue(bucket.isClosed)
        assertEquals(0, RiftRegion.windowRankLength(stream, rank))
        popped + removedEntries
      }

      assertEquals(41, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamWindowTableRankMovesKeyBetweenBuckets(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Row(val value: Int)

        val rank = RiftRegion.streamWindowTableRank[Row](10, 1, 4)
        val firstBucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
        val firstChild = RiftRegion.streamBucketRegion(stream, firstBucket)
        val first: Row^{stream} =
          RiftRegion.alloc(new Row(20))(using firstChild)
        RiftRegion.putTableRankInBucket(
          stream,
          rank,
          firstBucket,
          1234567890123L,
          first,
          1L
        )

        val secondBucket = RiftRegion.streamWindowBucketFor(stream, rank, 17L)
        val secondChild = RiftRegion.streamBucketRegion(stream, secondBucket)
        val second: Row^{stream} =
          RiftRegion.alloc(new Row(43))(using secondChild)
        RiftRegion.putTableRankInBucket(
          stream,
          rank,
          secondBucket,
          1234567890123L,
          second,
          9L
        )

        RiftRegion.closeTableRankBucketsBefore(stream, rank, 10L) { _ => () }

        assertTrue(firstBucket.isClosed)
        assertTrue(RiftRegion.containsTableRank(stream, rank, 1234567890123L))
        assertEquals(1, RiftRegion.tableRankLength(stream, rank))
        assertEquals(1234567890123L, RiftRegion.peekTableRankKey(stream, rank))
        RiftRegion.getTableRank(stream, rank, 1234567890123L).value
      }

      assertEquals(43, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamWindowTableRankReportsRemovedEntriesOnClose(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Row(val value: Int)

        val rank = RiftRegion.streamWindowTableRank[Row](10, 1, 4)
        val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
        val child = RiftRegion.streamBucketRegion(stream, bucket)
        val row: Row^{stream} =
          RiftRegion.alloc(new Row(37))(using child)
        RiftRegion.putTableRankInBucket(
          stream,
          rank,
          bucket,
          -3L,
          row,
          7L
        )

        var removedKey = 0L
        var removedValue = 0
        var bucketCleanupRan = false
        RiftRegion.closeTableRankBucketsBeforeWithEntries(
          stream,
          rank,
          10L
        ) { (_, key, value) =>
          removedKey = key
          removedValue = value.value
        } { _ =>
          bucketCleanupRan = true
        }

        assertTrue(bucket.isClosed)
        assertTrue(bucketCleanupRan)
        assertEquals(0, RiftRegion.tableRankLength(stream, rank))
        removedKey.toInt + removedValue
      }

      assertEquals(34, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamWindowTableRankSupportsLexicographicPriorities(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Row(val value: Int)

        val rank = RiftRegion.streamWindowTableRankLexicographic[Row](10, 1, 4)
        val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
        val child = RiftRegion.streamBucketRegion(stream, bucket)
        val older: Row^{stream} =
          RiftRegion.alloc(new Row(10))(using child)
        val newer: Row^{stream} =
          RiftRegion.alloc(new Row(20))(using child)
        val lowerKey: Row^{stream} =
          RiftRegion.alloc(new Row(30))(using child)

        RiftRegion.putTableRankInBucket(
          stream,
          rank,
          bucket,
          4L,
          older,
          5L,
          100L,
          10L,
          -4L
        )
        RiftRegion.putTableRankInBucket(
          stream,
          rank,
          bucket,
          6L,
          newer,
          5L,
          110L,
          20L,
          -6L
        )
        RiftRegion.putTableRankInBucket(
          stream,
          rank,
          bucket,
          2L,
          lowerKey,
          5L,
          110L,
          20L,
          -2L
        )

        assertEquals(2L, RiftRegion.peekTableRankKey(stream, rank))
        assertEquals(30, RiftRegion.peekTableRank(stream, rank).value)

        assertTrue(
          RiftRegion.updateTableRankPriority(
            stream,
            rank,
            4L,
            6L,
            1L,
            1L,
            -4L
          )
        )
        assertEquals(4L, RiftRegion.peekTableRankKey(stream, rank))
        RiftRegion.popTableRank(stream, rank).value
      }

      assertEquals(10, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamWindowTableRankCopiesTopKWithoutRemovingEntries(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Row(val value: Int)

        val rank = RiftRegion.streamWindowTableRank[Row](10, 1, 4)
        val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
        val child = RiftRegion.streamBucketRegion(stream, bucket)
        val low: Row^{stream} =
          RiftRegion.alloc(new Row(10))(using child)
        val high: Row^{stream} =
          RiftRegion.alloc(new Row(30))(using child)
        val middle: Row^{stream} =
          RiftRegion.alloc(new Row(20))(using child)

        RiftRegion.putTableRankInBucket(stream, rank, bucket, 1L, low, 1L)
        RiftRegion.putTableRankInBucket(stream, rank, bucket, 3L, high, 3L)
        RiftRegion.putTableRankInBucket(stream, rank, bucket, 2L, middle, 2L)

        val result: Array[Row^{stream}]^{stream} =
          RiftRegion.alloc(new Array[Row^{stream}](2))
        val candidates: Array[Int]^{stream} =
          RiftRegion.alloc(new Array[Int](2))
        val copied =
          RiftRegion.copyTableRankTopK(stream, rank, result, candidates, 2)

        assertEquals(2, copied)
        assertEquals(30, result(0).value)
        assertEquals(20, result(1).value)
        assertEquals(3, RiftRegion.tableRankLength(stream, rank))
        assertTrue(RiftRegion.removeTableRank(stream, rank, 3L))
        assertEquals(2L, RiftRegion.peekTableRankKey(stream, rank))

        result(0).value + result(1).value + RiftRegion.tableRankLength(stream, rank)
      }

      assertEquals(52, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamWindowTableRankDiagnosticsAreOptIn(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Row(val value: Int)

        val rank = RiftRegion.streamWindowTableRank[Row](10, 1, 4)
        RiftRegion.setTableRankDiagnosticsEnabled(stream, rank, enabled = true)
        RiftRegion.resetTableRankDiagnostics(stream, rank)
        val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
        val child = RiftRegion.streamBucketRegion(stream, bucket)
        val row: Row^{stream} =
          RiftRegion.alloc(new Row(41))(using child)

        RiftRegion.putTableRankInBucket(stream, rank, bucket, 1L, row, 1L)
        RiftRegion.updateTableRankPriority(stream, rank, 1L, 2L)
        assertTrue(RiftRegion.containsTableRank(stream, rank, 1L))
        RiftRegion.closeTableRankBucketsBefore(stream, rank, 10L) { _ => () }

        val diagnostics = RiftRegion.tableRankDiagnostics(stream, rank)
        assertTrue(diagnostics.contains("lookups="))
        assertTrue(diagnostics.contains("bucket_close_removals=1"))
        assertTrue(diagnostics.contains("table_active=0"))
        42
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamWindowIndexedRankRanksAndClosesBucket(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Row(val value: Int)

        val rank = RiftRegion.streamWindowIndexedRank[Row](10, 8, 1)
        val firstBucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
        val sameBucket = RiftRegion.streamWindowBucketFor(stream, rank, 9L)
        assertTrue(
          firstBucket.asInstanceOf[AnyRef] eq sameBucket.asInstanceOf[AnyRef]
        )

        val child = RiftRegion.streamBucketRegion(stream, firstBucket)
        val low: Row^{stream} = RiftRegion.alloc(new Row(20))(using child)
        val high: Row^{stream} = RiftRegion.alloc(new Row(41))(using child)

        RiftRegion.putWindowRank(stream, rank, 1, low, 1L)
        RiftRegion.putWindowRank(stream, rank, 2, high, 5L)
        assertEquals(2, RiftRegion.windowRankLength(stream, rank))
        assertEquals(2, RiftRegion.peekWindowRankKey(stream, rank))
        assertEquals(5L, RiftRegion.peekWindowRankPriority(stream, rank))
        val bestBeforeClose = RiftRegion.peekWindowRank(stream, rank).value

        assertTrue(
          RiftRegion.hasWindowRankBucketsBefore(stream, rank, 10L)
        )
        RiftRegion.closeWindowRankBucketsBefore(stream, rank, 10L) { _ =>
          assertTrue(RiftRegion.removeWindowRank(stream, rank, 1))
          assertTrue(RiftRegion.removeWindowRank(stream, rank, 2))
        }

        assertTrue(firstBucket.isClosed)
        assertEquals(0, RiftRegion.windowRankLength(stream, rank))
        bestBeforeClose + RiftRegion.windowRankLength(stream, rank)
      }

      assertEquals(41, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamWindowIndexedRankAutoRemovesBucketKeysOnClose(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Row(val value: Int)

        val rank = RiftRegion.streamWindowIndexedRank[Row](10, 8, 1)
        val firstBucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
        val firstChild = RiftRegion.streamBucketRegion(stream, firstBucket)
        val first: Row^{stream} =
          RiftRegion.alloc(new Row(20))(using firstChild)
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          firstBucket,
          1,
          first,
          1L
        )

        val secondBucket = RiftRegion.streamWindowBucketFor(stream, rank, 17L)
        val secondChild = RiftRegion.streamBucketRegion(stream, secondBucket)
        val second: Row^{stream} =
          RiftRegion.alloc(new Row(41))(using secondChild)
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          secondBucket,
          2,
          second,
          5L
        )

        var cleanupSawUnlinkedKey = false
        RiftRegion.closeWindowRankBucketsBefore(stream, rank, 10L) { _ =>
          cleanupSawUnlinkedKey =
            !RiftRegion.containsWindowRank(stream, rank, 1) &&
              RiftRegion.containsWindowRank(stream, rank, 2)
        }

        assertTrue(firstBucket.isClosed)
        assertTrue(cleanupSawUnlinkedKey)
        assertEquals(1, RiftRegion.windowRankLength(stream, rank))
        assertEquals(2, RiftRegion.peekWindowRankKey(stream, rank))
        RiftRegion.peekWindowRank(stream, rank).value
      }

      assertEquals(41, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamWindowIndexedRankReportsRemovedEntriesOnClose(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Row(val value: Int)

        val rank = RiftRegion.streamWindowIndexedRank[Row](10, 8, 1)
        val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
        val child = RiftRegion.streamBucketRegion(stream, bucket)
        val row: Row^{stream} =
          RiftRegion.alloc(new Row(37))(using child)
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          bucket,
          3,
          row,
          7L
        )

        var removedKey = -1
        var removedValue = 0
        var bucketCleanupRan = false
        RiftRegion.closeWindowRankBucketsBeforeWithEntries(
          stream,
          rank,
          10L
        ) { (_, key, value) =>
          removedKey = key
          removedValue = value.value
        } { _ =>
          bucketCleanupRan = true
        }

        assertTrue(bucket.isClosed)
        assertTrue(bucketCleanupRan)
        assertEquals(0, RiftRegion.windowRankLength(stream, rank))
        removedKey + removedValue
      }

      assertEquals(40, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamWindowIndexedRankCloseEntriesSkipsAlreadyPoppedKeys(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Row(val value: Int)

        val rank = RiftRegion.streamWindowIndexedRank[Row](10, 8, 1)
        val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
        val child = RiftRegion.streamBucketRegion(stream, bucket)
        val row: Row^{stream} =
          RiftRegion.alloc(new Row(41))(using child)
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          bucket,
          1,
          row,
          7L
        )

        val popped = RiftRegion.popWindowRank(stream, rank).value
        var removedEntries = 0
        RiftRegion.closeAllWindowRankBucketsWithEntries(stream, rank) {
          (_, _, _) =>
            removedEntries += 1
        } { _ => () }

        assertTrue(bucket.isClosed)
        assertEquals(0, RiftRegion.windowRankLength(stream, rank))
        popped + removedEntries
      }

      assertEquals(41, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamWindowIndexedRankSupportsLexicographicPriorities(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Row(val value: Int)

        val rank =
          RiftRegion.streamWindowIndexedRankLexicographic[Row](10, 8, 1)
        val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
        val child = RiftRegion.streamBucketRegion(stream, bucket)
        val oldest: Row^{stream} =
          RiftRegion.alloc(new Row(10))(using child)
        val newer: Row^{stream} =
          RiftRegion.alloc(new Row(20))(using child)
        val lowerKey: Row^{stream} =
          RiftRegion.alloc(new Row(30))(using child)

        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          bucket,
          4,
          oldest,
          5L,
          100L,
          10L,
          -4L
        )
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          bucket,
          6,
          newer,
          5L,
          110L,
          20L,
          -6L
        )
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          bucket,
          2,
          lowerKey,
          5L,
          110L,
          20L,
          -2L
        )

        assertEquals(2, RiftRegion.peekWindowRankKey(stream, rank))
        assertEquals(30, RiftRegion.peekWindowRank(stream, rank).value)

        assertTrue(
          RiftRegion.updateWindowRankPriority(
            stream,
            rank,
            4,
            6L,
            1L,
            1L,
            -4L
          )
        )
        assertEquals(4, RiftRegion.peekWindowRankKey(stream, rank))
        RiftRegion.popWindowRank(stream, rank).value
      }

      assertEquals(10, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamWindowIndexedRankMovesKeyBetweenBuckets(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Row(val value: Int)

        val rank = RiftRegion.streamWindowIndexedRank[Row](10, 8, 1)
        val firstBucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
        val firstChild = RiftRegion.streamBucketRegion(stream, firstBucket)
        val first: Row^{stream} =
          RiftRegion.alloc(new Row(20))(using firstChild)
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          firstBucket,
          1,
          first,
          1L
        )

        val secondBucket = RiftRegion.streamWindowBucketFor(stream, rank, 17L)
        val secondChild = RiftRegion.streamBucketRegion(stream, secondBucket)
        val second: Row^{stream} =
          RiftRegion.alloc(new Row(43))(using secondChild)
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          secondBucket,
          1,
          second,
          9L
        )

        RiftRegion.closeWindowRankBucketsBefore(stream, rank, 10L) { _ => () }

        assertTrue(firstBucket.isClosed)
        assertTrue(RiftRegion.containsWindowRank(stream, rank, 1))
        assertEquals(1, RiftRegion.windowRankLength(stream, rank))
        RiftRegion.getWindowRank(stream, rank, 1).value
      }

      assertEquals(43, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamWindowIndexedRankUpdatesKeyWithinSameBucket(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Row(val value: Int)

        val rank = RiftRegion.streamWindowIndexedRank[Row](10, 8, 1)
        val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
        val child = RiftRegion.streamBucketRegion(stream, bucket)
        val first: Row^{stream} =
          RiftRegion.alloc(new Row(20))(using child)
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          bucket,
          1,
          first,
          1L
        )

        val second: Row^{stream} =
          RiftRegion.alloc(new Row(44))(using child)
        RiftRegion.putWindowRankInBucket(
          stream,
          rank,
          bucket,
          1,
          second,
          9L
        )

        assertEquals(1, RiftRegion.windowRankLength(stream, rank))
        assertEquals(1, RiftRegion.peekWindowRankKey(stream, rank))
        val beforeClose = RiftRegion.getWindowRank(stream, rank, 1).value

        var cleanupSawUnlinkedKey = false
        RiftRegion.closeWindowRankBucketsBefore(stream, rank, 10L) { _ =>
          cleanupSawUnlinkedKey =
            !RiftRegion.containsWindowRank(stream, rank, 1)
        }

        assertTrue(bucket.isClosed)
        assertTrue(cleanupSawUnlinkedKey)
        assertEquals(0, RiftRegion.windowRankLength(stream, rank))
        beforeClose
      }

      assertEquals(44, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamAppendWindowConsumesAndClosesBuckets(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val window = RiftRegion.streamAppendWindow[Event](10)
        val firstBucket =
          RiftRegion.streamAppendWindowBucketFor(stream, window, 7L)
        val firstRegion = RiftRegion.streamBucketRegion(stream, firstBucket)
        val first: Event^{stream} =
          RiftRegion.alloc(new Event(20))(using firstRegion)
        RiftRegion.appendWindow(stream, window, firstBucket, first)

        val secondBucket =
          RiftRegion.streamAppendWindowBucketFor(stream, window, 17L)
        val secondRegion = RiftRegion.streamBucketRegion(stream, secondBucket)
        val second: Event^{stream} =
          RiftRegion.alloc(new Event(21))(using secondRegion)
        RiftRegion.appendWindow(stream, window, secondBucket, second)

        assertEquals(2, RiftRegion.appendWindowLength(stream, window))
        assertEquals(
          1,
          RiftRegion.appendWindowBucketLength(stream, window, firstBucket)
        )

        var sum = 0
        RiftRegion.closeAppendWindowBucketsBefore(stream, window, 10L) {
          (_, event) =>
            sum += event.value
        }

        assertTrue(firstBucket.isClosed)
        assertFalse(secondBucket.isClosed)
        assertEquals(1, RiftRegion.appendWindowLength(stream, window))

        RiftRegion.closeAllAppendWindowBuckets(stream, window) {
          (_, event) =>
            sum += event.value
        }

        assertTrue(secondBucket.isClosed)
        assertEquals(0, RiftRegion.appendWindowLength(stream, window))
        sum + 1
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamAppendWindowCursorConsumesAndClosesBuckets(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val window = RiftRegion.streamAppendWindow[Event](10)
        val firstBucket =
          RiftRegion.streamAppendWindowBucketFor(stream, window, 7L)
        val firstRegion = RiftRegion.streamBucketRegion(stream, firstBucket)
        val first: Event^{stream} =
          RiftRegion.alloc(new Event(20))(using firstRegion)
        RiftRegion.appendWindow(stream, window, firstBucket, first)

        val secondBucket =
          RiftRegion.streamAppendWindowBucketFor(stream, window, 17L)
        val secondRegion = RiftRegion.streamBucketRegion(stream, secondBucket)
        val second: Event^{stream} =
          RiftRegion.alloc(new Event(21))(using secondRegion)
        RiftRegion.appendWindow(stream, window, secondBucket, second)

        var sum = 0
        RiftRegion.closeAppendWindowBucketsBeforeWithCursor(stream, window, 10L) {
          (_, cursor) =>
            while (cursor.hasNext) sum += cursor.next().value
        }

        assertTrue(firstBucket.isClosed)
        assertFalse(secondBucket.isClosed)
        assertEquals(1, RiftRegion.appendWindowLength(stream, window))

        RiftRegion.closeAllAppendWindowBucketsWithCursor(stream, window) {
          (_, cursor) =>
            while (cursor.hasNext) sum += cursor.next().value
        }

        assertTrue(secondBucket.isClosed)
        assertEquals(0, RiftRegion.appendWindowLength(stream, window))
        sum + 1
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamAppendWindowRejectsStaleBucketAfterCloseBefore(): Unit = {
    RiftRegion.init(1)
    try {
      RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val window = RiftRegion.streamAppendWindow[Event](10)
        val bucket =
          RiftRegion.streamAppendWindowBucketFor(stream, window, 7L)
        val region = RiftRegion.streamBucketRegion(stream, bucket)
        val event: Event^{stream} =
          RiftRegion.alloc(new Event(41))(using region)
        RiftRegion.appendWindow(stream, window, bucket, event)
        RiftRegion.closeAppendWindowBucketsBeforeWithCursor(stream, window, 10L) {
          (_, cursor) =>
            while (cursor.hasNext) cursor.next()
        }

        assertTrue(bucket.isClosed)
        assertThrows(
          classOf[IllegalStateException],
          () => {
            RiftRegion.streamBucketRegion(stream, bucket)
            ()
          }
        )
      }
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamAppendWindowRejectsStaleBucketAfterCloseAll(): Unit = {
    RiftRegion.init(1)
    try {
      RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val window = RiftRegion.streamAppendWindow[Event](10)
        val bucket =
          RiftRegion.streamAppendWindowBucketFor(stream, window, 7L)
        val region = RiftRegion.streamBucketRegion(stream, bucket)
        val event: Event^{stream} =
          RiftRegion.alloc(new Event(41))(using region)
        RiftRegion.appendWindow(stream, window, bucket, event)
        RiftRegion.closeAllAppendWindowBucketsWithCursor(stream, window) {
          (_, cursor) =>
            while (cursor.hasNext) cursor.next()
        }

        assertTrue(bucket.isClosed)
        assertThrows(
          classOf[IllegalStateException],
          () => {
            RiftRegion.streamBucketRegion(stream, bucket)
            ()
          }
        )
      }
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamPageTokenAppendWindowAllocatesAndDrainsRecords(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val window = RiftRegion.streamPageTokenAppendWindow[Event](10)
        var sum = 0

        def consume(
            bucket: RiftRegion.StreamBucket^{stream},
            cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
        ): Unit =
          while (cursor.hasNext)
            sum += cursor.next().value + bucket.startSeconds.toInt

        val firstRegion =
          RiftRegion.pageTokenAppendRegionFor(stream, window, 7L, Long.MinValue)(
            consume
          )
        val first: Event^{stream} =
          RiftRegion.alloc(new Event(20))(using firstRegion)
        RiftRegion.appendPageToken(stream, window, first)

        val secondRegion =
          RiftRegion.pageTokenAppendRegionFor(stream, window, 17L, 10L)(consume)
        val second: Event^{stream} =
          RiftRegion.alloc(new Event(12))(using secondRegion)
        RiftRegion.appendPageToken(stream, window, second)

        RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
          consume
        )
        sum
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def pageTokenMapFilterAllocatesAndDrainsRecords(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val operator = RiftRegion.pageTokenMapFilter[Event](10)
        var sum = 0

        def consume(
            bucket: RiftRegion.StreamBucket^{stream},
            cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
        ): Unit =
          while (cursor.hasNext)
            sum += cursor.next().value + bucket.startSeconds.toInt

        val firstRegion =
          RiftRegion.pageTokenMapFilterRegionFor(
            stream,
            operator,
            7L,
            Long.MinValue
          )(consume)
        val first: Event^{stream} =
          RiftRegion.alloc(new Event(20))(using firstRegion)
        RiftRegion.emitPageTokenMapFilter(stream, operator, first)

        val secondRegion =
          RiftRegion.pageTokenMapFilterRegionFor(stream, operator, 17L, 10L)(
            consume
          )
        val second: Event^{stream} =
          RiftRegion.alloc(new Event(12))(using secondRegion)
        RiftRegion.emitPageTokenMapFilter(stream, operator, second)

        RiftRegion.closeAllPageTokenMapFilterBucketsWithCursor(stream, operator)(
          consume
        )
        sum
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def epochBufferAllocatesAndDrainsRecords(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val buffer = RiftRegion.epochBuffer[Event]()
        var sum = 0

        def consume(
            bucket: RiftRegion.StreamBucket^{stream},
            cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
        ): Unit =
          while (cursor.hasNext)
            sum += cursor.next().value + bucket.startSeconds.toInt

        val firstRegion = RiftRegion.epochBufferRegionFor(stream, buffer)
        val first: Event^{stream} =
          RiftRegion.alloc(new Event(20))(using firstRegion)
        val second: Event^{stream} =
          RiftRegion.alloc(new Event(21))(using firstRegion)
        RiftRegion.appendEpochBuffer(stream, buffer, first)
        RiftRegion.appendEpochBuffer(stream, buffer, second)
        assertEquals(2, RiftRegion.epochBufferLength(stream, buffer))

        RiftRegion.closeEpochBufferWithCursor(stream, buffer)(consume)
        assertEquals(0, RiftRegion.epochBufferLength(stream, buffer))

        val nextRegion = RiftRegion.epochBufferRegionFor(stream, buffer)
        val third: Event^{stream} =
          RiftRegion.alloc(new Event(1))(using nextRegion)
        RiftRegion.appendEpochBuffer(stream, buffer, third)
        RiftRegion.closeAllEpochBufferBucketsWithCursor(stream, buffer)(consume)

        sum
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamChunkAppendWindowAllocatesAndDrainsRecords(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int)

        val window = RiftRegion.streamChunkAppendWindow[Event](10, 2)
        var sum = 0

        def consume(
            bucket: RiftRegion.StreamBucket^{stream},
            cursor: RiftRegion.StreamChunkCursor[Event]^{stream}
        ): Unit =
          while (cursor.hasNext)
            sum += cursor.next().value + bucket.startSeconds.toInt

        val firstRegion =
          RiftRegion.chunkAppendRegionFor(stream, window, 7L, Long.MinValue)(
            consume
          )
        val first: Event^{stream} =
          RiftRegion.alloc(new Event(10))(using firstRegion)
        val second: Event^{stream} =
          RiftRegion.alloc(new Event(10))(using firstRegion)
        RiftRegion.appendChunkToken(stream, window, first)
        RiftRegion.appendChunkToken(stream, window, second)

        val secondRegion =
          RiftRegion.chunkAppendRegionFor(stream, window, 17L, 10L)(consume)
        val third: Event^{stream} =
          RiftRegion.alloc(new Event(12))(using secondRegion)
        RiftRegion.appendChunkToken(stream, window, third)

        assertEquals(1, RiftRegion.chunkAppendWindowLength(stream, window))
        RiftRegion.closeAllChunkAppendBucketsWithCursor(stream, window)(
          consume
        )
        sum
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamAppendWindowPrependConsumesAndClosesBuckets(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val window = RiftRegion.streamAppendWindow[Event](10)
        val bucket =
          RiftRegion.streamAppendWindowBucketFor(stream, window, 7L)
        val region = RiftRegion.streamBucketRegion(stream, bucket)
        val first: Event^{stream} =
          RiftRegion.alloc(new Event(20))(using region)
        val second: Event^{stream} =
          RiftRegion.alloc(new Event(21))(using region)
        RiftRegion.prependWindow(stream, window, bucket, first)
        RiftRegion.prependWindow(stream, window, bucket, second)

        assertEquals(2, RiftRegion.appendWindowLength(stream, window))
        assertEquals(2, RiftRegion.appendWindowBucketLength(stream, window, bucket))

        var sum = 0
        var order = 0
        RiftRegion.closeAllAppendWindowBucketsWithCursor(stream, window) {
          (_, cursor) =>
            val firstSeen = cursor.next()
            val secondSeen = cursor.next()
            order = firstSeen.value * 100 + secondSeen.value
            sum = firstSeen.value + secondSeen.value
        }

        assertTrue(bucket.isClosed)
        assertEquals(0, RiftRegion.appendWindowLength(stream, window))
        sum + order
      }

      assertEquals(2161, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamJoinWindowCountsAndClosesBuckets(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val side: Int, val key: Int, val value: Int)
            extends RiftRegion.StreamAppendNode

        val join = RiftRegion.streamJoinWindow[Event](10, 16)
        val firstBucket =
          RiftRegion.streamJoinWindowBucketFor(stream, join, 7L)
        val firstRegion = RiftRegion.streamBucketRegion(stream, firstBucket)
        val left: Event^{stream} =
          RiftRegion.alloc(new Event(0, 3, 20))(using firstRegion)
        val leftCounts =
          RiftRegion.putJoinLeftInBucketAndCounts(
            stream,
            join,
            firstBucket,
            3,
            left
          )
        val leftCount = (leftCounts >>> 32).toInt

        val secondBucket =
          RiftRegion.streamJoinWindowBucketFor(stream, join, 17L)
        val secondRegion = RiftRegion.streamBucketRegion(stream, secondBucket)
        val right: Event^{stream} =
          RiftRegion.alloc(new Event(1, 3, 21))(using secondRegion)
        val rightCounts =
          RiftRegion.putJoinRightInBucketAndCounts(
            stream,
            join,
            secondBucket,
            3,
            right
          )
        val rightCount = rightCounts.toInt

        assertEquals(1, leftCount)
        assertEquals(1, rightCount)
        assertEquals(2, RiftRegion.joinWindowLength(stream, join))
        assertEquals(1, RiftRegion.leftJoinWindowCount(stream, join, 3))
        assertEquals(1, RiftRegion.rightJoinWindowCount(stream, join, 3))

        var sum = 0
        RiftRegion.closeJoinWindowBucketsBeforeWithCursor(stream, join, 10L) {
          (_, cursor) =>
            while (cursor.hasNext) {
              val event = cursor.next()
              if (event.side == 0)
                RiftRegion.removeJoinLeftAndCounts(stream, join, event.key)
              else
                RiftRegion.removeJoinRightAndCounts(stream, join, event.key)
              sum += event.value
            }
        }

        assertTrue(firstBucket.isClosed)
        assertFalse(secondBucket.isClosed)
        assertEquals(1, RiftRegion.joinWindowLength(stream, join))
        assertEquals(0, RiftRegion.leftJoinWindowCount(stream, join, 3))
        assertEquals(1, RiftRegion.rightJoinWindowCount(stream, join, 3))

        RiftRegion.closeAllJoinWindowBucketsWithCursor(stream, join) {
          (_, cursor) =>
            while (cursor.hasNext) {
              val event = cursor.next()
              if (event.side == 0)
                RiftRegion.removeJoinLeftAndCounts(stream, join, event.key)
              else
                RiftRegion.removeJoinRightAndCounts(stream, join, event.key)
              sum += event.value
            }
        }

        assertTrue(secondBucket.isClosed)
        assertEquals(0, RiftRegion.joinWindowLength(stream, join))
        assertEquals(0, RiftRegion.rightJoinWindowCount(stream, join, 3))
        sum + 1
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

  @Test def streamWindowFoldAggregatesAndClosesBuckets(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val key: Int, val delta: Long, val value: Int)
            extends RiftRegion.StreamAppendNode

        val fold = RiftRegion.streamWindowFold[Event](10, 16)
        val firstBucket =
          RiftRegion.streamWindowFoldBucketFor(stream, fold, 7L)
        val firstRegion = RiftRegion.streamBucketRegion(stream, firstBucket)
        val first: Event^{stream} =
          RiftRegion.alloc(new Event(3, 20L, 20))(using firstRegion)
        val firstSum =
          RiftRegion.putFoldInBucket(
            stream,
            fold,
            firstBucket,
            first.key,
            first.delta,
            first
          )

        val secondBucket =
          RiftRegion.streamWindowFoldBucketFor(stream, fold, 17L)
        val secondRegion = RiftRegion.streamBucketRegion(stream, secondBucket)
        val second: Event^{stream} =
          RiftRegion.alloc(new Event(3, 21L, 21))(using secondRegion)
        val secondSum =
          RiftRegion.putFoldInBucket(
            stream,
            fold,
            secondBucket,
            second.key,
            second.delta,
            second
          )

        assertEquals(20L, firstSum)
        assertEquals(41L, secondSum)
        assertTrue(RiftRegion.containsFoldKey(stream, fold, 3))
        assertEquals(41L, RiftRegion.foldValue(stream, fold, 3))
        assertEquals(2, RiftRegion.foldCount(stream, fold, 3))
        assertEquals(2, RiftRegion.foldWindowLength(stream, fold))

        var sum = 0
        RiftRegion.closeFoldBucketsBeforeWithCursor(stream, fold, 10L) {
          (_, cursor) =>
            while (cursor.hasNext) {
              val event = cursor.next()
              RiftRegion.removeFoldContribution(
                stream,
                fold,
                event.key,
                event.delta
              )
              sum += event.value
            }
        }

        assertTrue(firstBucket.isClosed)
        assertFalse(secondBucket.isClosed)
        assertEquals(1, RiftRegion.foldWindowLength(stream, fold))
        assertEquals(21L, RiftRegion.foldValue(stream, fold, 3))
        assertEquals(1, RiftRegion.foldCount(stream, fold, 3))

        RiftRegion.closeAllFoldBucketsWithCursor(stream, fold) {
          (_, cursor) =>
            while (cursor.hasNext) {
              val event = cursor.next()
              RiftRegion.removeFoldContribution(
                stream,
                fold,
                event.key,
                event.delta
              )
              sum += event.value
            }
        }

        assertTrue(secondBucket.isClosed)
        assertEquals(0, RiftRegion.foldWindowLength(stream, fold))
        assertFalse(RiftRegion.containsFoldKey(stream, fold, 3))
        assertEquals(0L, RiftRegion.foldValue(stream, fold, 3))
        sum + 1
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def epochFoldAggregatesAndClearsCurrentBucket(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val key: Int, val delta: Long, val value: Int)
            extends RiftRegion.StreamAppendNode

        val fold = RiftRegion.epochFold[Event](10, 16)
        val region = RiftRegion.epochFoldRegionFor(stream, fold, 7L)
        val first: Event^{stream} =
          RiftRegion.alloc(new Event(3, 20L, 20))(using region)
        val second: Event^{stream} =
          RiftRegion.alloc(new Event(3, 21L, 21))(using region)
        val firstSum =
          RiftRegion.putEpochFold(stream, fold, first.key, first.delta, first)
        val secondSum =
          RiftRegion.putEpochFold(stream, fold, second.key, second.delta, second)

        assertEquals(20L, firstSum)
        assertEquals(41L, secondSum)
        assertTrue(RiftRegion.containsEpochFoldKey(stream, fold, 3))
        assertEquals(41L, RiftRegion.epochFoldValue(stream, fold, 3))
        assertEquals(2, RiftRegion.epochFoldCount(stream, fold, 3))

        var closed = 0
        RiftRegion.closeEpochFoldCurrentBucketAndClear(stream, fold) {
          (_, cursor) =>
            while (cursor.hasNext)
              closed += cursor.next().value
        }

        assertFalse(RiftRegion.containsEpochFoldKey(stream, fold, 3))
        assertEquals(0L, RiftRegion.epochFoldValue(stream, fold, 3))
        assertEquals(0, RiftRegion.epochFoldCount(stream, fold, 3))
        closed + 1
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def regionListBuildsAndTraversesNodes(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.scoped { region ?=>
        final class Node(val value: Int) extends RiftRegion.RegionListNode

        val list = RiftRegion.regionList[Node]()
        var i = 0
        while (i < 4) {
          val node: Node^{region} = RiftRegion.alloc(new Node(i + 1))
          RiftRegion.prependRegionList(region, list, node)
          i += 1
        }

        var sum = 0
        var cursor = RiftRegion.regionListHead(region, list)
        while (cursor != null) {
          sum += cursor.value
          cursor = RiftRegion.regionListNext(region, cursor)
        }
        sum + RiftRegion.regionListLength(region, list)
      }

      assertEquals(14, total)
    } finally {
      RiftRegion.shutdown()
    }
  }
}
