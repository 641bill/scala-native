package scala.scalanative.memory

import org.junit.Assert._
import org.junit.Test

import org.scalanative.testsuite.utils.AssertThrows.assertThrows

import scala.scalanative.runtime.toRawPtr
import scala.scalanative.unsafe._

final class RiftBox(val value: Int)

final class RiftDefaultCell {
  var ref: AnyRef = _
  var value: Int = _
}

class RiftRegionTest {
  private def withRegion(
      initialSlabs: Int = 0,
      kind: Int = RiftRegion.HPZone
  )(body: RiftRegion => Unit): Unit = {
    RiftRegion.init(initialSlabs)
    try {
      val region = RiftRegion.open(kind)
      try body(region)
      finally if (region.isOpen) region.close()
    } finally {
      RiftRegion.shutdown()
    }
  }

  private def assertAccessible(ptr: Ptr[Byte], n: Int): Unit = {
    val ints = ptr.asInstanceOf[Ptr[Int]]
    var i = 0
    while (i < n) {
      ints(i) = i * 3
      i += 1
    }

    i = 0
    var sum = 0
    while (i < n) {
      sum += ints(i)
      i += 1
    }

    assertEquals((0 until n).map(_ * 3).sum, sum)
  }

  @Test def canAllocateRawMemoryInAllKinds(): Unit = {
    Seq(RiftRegion.HPZone, RiftRegion.Scoped, RiftRegion.Streaming).foreach {
      kind =>
        withRegion(initialSlabs = 1, kind = kind) { region =>
          val ptr = region.alloc(64, 16)
          assertFalse(ptr == null)
          assertAccessible(ptr, 16)
        }
    }
  }

  @Test def canAllocateManagedObjectsAndArrays(): Unit = {
    withRegion(initialSlabs = 1) { region =>
      val box = region.alloc(new RiftBox(42))
      assertEquals(42, box.value)

      val arr = region.alloc(new Array[Int](4))
      arr(0) = 7
      arr(1) = 9
      arr(2) = 11
      arr(3) = 13
      assertArrayEquals(Array(7, 9, 11, 13), arr)
    }
  }

  @Test def resetRewindsTheRegionCursor(): Unit = {
    withRegion(initialSlabs = 1) { region =>
      val first = region.alloc(64, 16)
      val second = region.alloc(64, 16)

      assertFalse(toRawPtr(first) == toRawPtr(second))

      region.reset()

      val afterReset = region.alloc(64, 16)
      assertTrue(toRawPtr(first) == toRawPtr(afterReset))
      assertAccessible(afterReset, 16)
    }
  }

  @Test def resetZeroesRetainedSlabBeforeReuse(): Unit = {
    withRegion(initialSlabs = 1) { region =>
      val sentinel = new Object
      val cell = region.alloc(new RiftDefaultCell)
      cell.ref = sentinel
      cell.value = 12345

      region.reset()

      val afterReset = region.alloc(new RiftDefaultCell)
      assertNull(afterReset.ref)
      assertEquals(0, afterReset.value)
    }
  }

  @Test def closedRegionRejectsFurtherOperations(): Unit = {
    RiftRegion.init(0)
    val region = RiftRegion.open()
    try {
      assertTrue(region.isOpen)
      region.alloc(64, 16)
      region.close()

      assertTrue(region.isClosed)
      assertThrows(classOf[IllegalStateException], region.alloc(64, 16))
      assertThrows(classOf[IllegalStateException], region.reset())
      assertThrows(classOf[IllegalStateException], region.close())
    } finally {
      RiftRegion.shutdown()
    }
  }
}
