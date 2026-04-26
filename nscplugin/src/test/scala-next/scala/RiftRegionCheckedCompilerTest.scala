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
