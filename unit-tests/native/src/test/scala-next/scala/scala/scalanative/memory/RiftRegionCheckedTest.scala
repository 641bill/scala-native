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
