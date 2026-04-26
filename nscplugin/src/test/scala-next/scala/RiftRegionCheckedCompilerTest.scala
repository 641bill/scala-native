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
    // This covers closure escape when the closure retains the region handle.
    // Returning a closure that captures only a region-local value still
    // compiles today; HANDOFF.md records that as a remaining checker gap.
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
