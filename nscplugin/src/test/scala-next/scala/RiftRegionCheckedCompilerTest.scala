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

  @Test def remlStylePolymorphicRegionCellCanBeInferred(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val box: Box^{region} = new Box(40)
      |    val cell: Cell[Box^{region}]^{region} =
      |      new Cell[Box^{region}](box)
      |    cell.value.value + 2
      |  }
      |""".stripMargin)

  @Test def optionSomeOfRegionValueCanBeRegionLocal(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val box: Box^{region} = new Box(40)
      |    val option: Some[Box^{region}]^{region} = Some(box)
      |    option.value.value + 2
      |  }
      |""".stripMargin)

  @Test def optionSomeInlineNewArgumentCanBeRegionLocal(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val option: Some[Box^{region}]^{region} =
      |      Some(new Box(40))
      |    option.value.value + 2
      |  }
      |""".stripMargin)

  @Test def optionSupertypeSomeInlineNewArgumentCanBeRegionLocal(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val option: Option[Box^{region}]^{region} =
      |      Some(new Box(40))
      |    option.get.value + 2
      |  }
      |""".stripMargin)

  @Test def optionApplyOfRegionValueCanBeRegionLocal(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val box: Box^{region} = new Box(40)
      |    val option: Option[Box^{region}]^{region} = Option(box)
      |    option.get.value + 2
      |  }
      |""".stripMargin)

  @Test def optionApplyInlineNewArgumentCanBeRegionLocal(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val option: Option[Box^{region}]^{region} =
      |      Option(new Box(40))
      |    option.get.value + 2
      |  }
      |""".stripMargin)

  @Test def optionApplyNullCanBeRegionLocal(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val option: Option[Box^{region}]^{region} = Option(null)
      |    if option.isEmpty then 42 else 0
      |  }
      |""".stripMargin)

  @Test def optionNoneCanBeRegionLocal(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val option: Option[Box^{region}]^{region} = None
      |    if option.isEmpty then 42 else 0
      |  }
      |""".stripMargin)

  @Test def optionSupertypeSomeOrNoneCanBeRegionLocal(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val option: Option[Box^{region}]^{region} =
      |      if flag then Some(new Box(40)) else None
      |    option.map(_.value).getOrElse(40) + 2
      |  }
      |""".stripMargin)

  @Test def optionSomeOfRegionValueCannotEscapeHeap(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val box: Box^{region} = new Box(40)
      |    val option: Some[Box^{region}]^{region} = Some(box)
      |    Holder.retained = option
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def optionSupertypeSomeOfRegionValueCannotEscapeHeap(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val option: Option[Box^{region}]^{region} =
      |      Some(new Box(40))
      |    Holder.retained = option
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def optionSomeRegionPlacementRejectsUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val option: Some[Metadata]^{region} = Some(metadata)
      |    option.value.value + 2
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def optionSupertypeSomeRegionPlacementRejectsUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val option: Option[Metadata]^{region} = Some(metadata)
      |    option.get.value + 2
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def optionSomeOrNoneRegionPlacementRejectsUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val option: Option[Metadata]^{region} =
      |      if flag then Some(metadata) else None
      |    option.get.value + 2
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def optionApplyRegionPlacementRejectsUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val option: Option[Metadata]^{region} = Option(metadata)
      |    option.get.value + 2
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def optionApplyPrimitiveLiteralRequiresBoxingSupport(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val option: Option[Int]^{region} = Option(40)
      |    option.get + 2
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def tuple2OfRegionValueCanBeRegionLocal(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val left: Box^{region} = new Box(40)
      |    val right: Box^{region} = new Box(2)
      |    val pair: Tuple2[Box^{region}, Box^{region}]^{region} =
      |      Tuple2(left, right)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin)

  @Test def tuple2InlineNewArgumentsCanBeRegionLocal(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val pair: Tuple2[Box^{region}, Box^{region}]^{region} =
      |      Tuple2(new Box(40), new Box(2))
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin)

  @Test def tuple2PrimitiveLiteralAndRegionValueRequiresBoxingSupport(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val pair: Tuple2[Int, Box^{region}]^{region} =
      |      Tuple2(40, new Box(2))
      |    pair._1 + pair._2.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def tupleLiteralInlineNewArgumentsCanBeRegionLocal(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val pair: Tuple2[Box^{region}, Box^{region}]^{region} =
      |      (new Box(40), new Box(2))
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin)

  @Test def tuple2OfRegionValueCannotEscapeHeap(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val left: Box^{region} = new Box(40)
      |    val right: Box^{region} = new Box(2)
      |    val pair: Tuple2[Box^{region}, Box^{region}]^{region} =
      |      Tuple2(left, right)
      |    Holder.retained = pair
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def tuple2RegionPlacementRejectsUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val pair: Tuple2[Metadata, Metadata]^{region} =
      |      Tuple2(metadata, metadata)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def tuple2PrimitiveLiteralDoesNotPermitUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val pair: Tuple2[Int, Metadata]^{region} =
      |      Tuple2(2, metadata)
      |    pair._1 + pair._2.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def tuple2PreboxedPrimitiveCannotEnterRegionWithoutRoot(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def bad(): Any =
      |  RiftRegion.scoped { region ?=>
      |    val boxed: Any = 40
      |    val pair: Tuple2[Any, Int]^{region} =
      |      Tuple2(boxed, 2)
      |    pair._1
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def tupleLiteralRegionPlacementRejectsUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val pair: Tuple2[Metadata, Metadata]^{region} =
      |      (metadata, metadata)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def tuple2RegionPlacementRejectsHelperReturnedHeapArguments(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def makeMetadata(): Metadata = new Metadata(40)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val pair: Tuple2[Metadata, Metadata]^{region} =
      |      Tuple2(makeMetadata(), makeMetadata())
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def directConstructorInlineNewArgumentCanBeRegionLocal(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Wrapper(val box: Box^{region})
      |    val wrapper: Wrapper^{region} =
      |      new Wrapper(new Box(40))
      |    wrapper.box.value + 2
      |  }
      |""".stripMargin)

  @Test def directConstructorInlineNewArgumentCannotEscapeHeap(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Wrapper(val box: Box^{region})
      |    val wrapper: Wrapper^{region} =
      |      new Wrapper(new Box(40))
      |    Holder.retained = wrapper
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def directConstructorRejectsHelperReturnedHeapArgument(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def makeMetadata(): Metadata = new Metadata(40)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Wrapper(val metadata: Metadata)
      |    val wrapper: Wrapper^{region} =
      |      new Wrapper(makeMetadata())
      |    wrapper.metadata.value + 2
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

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

  @Test def remlStylePolymorphicRegionCellWidenedToAnyRefCannotEscape(): Unit =
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
      |    val box: Box^{region} = new Box(1)
      |    val cell: Cell[Box^{region}]^{region} =
      |      new Cell[Box^{region}](box)
      |    val erased: AnyRef = cell
      |    Holder.retained = erased
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
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

  @Test def remlStyleInferredPolymorphicRegionObjectCannotStoreUnrootedHeapValue(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(41)
      |    val cell: Cell[Metadata]^{region} =
      |      new Cell[Metadata](metadata)
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

  @Test def inferredRegionOwnedClosureCanStayLocal(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val add: Function1[Int, Int]^{region} =
      |      (n: Int) => n + 40
      |    add(2)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedCaptureFreeClosureCannotCaptureUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val add: Function1[Int, Int]^{region} =
      |      (n: Int) => metadata.value + n
      |    add(2)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureCanCaptureRegionValue(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val box: Box^{region} = new Box(40)
      |    val add: Function1[Int, Int]^{region} =
      |      (n: Int) => box.value + n
      |    add(2)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodCanReturnLocalCaptureFreeClosure()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  val add: Function1[Int, Int]^{r} =
      |    (n: Int) => n + 40
      |  add
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(2)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodCanReturnClosureCapturingRegionValue()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  val box: Box^{r} = new Box(40)
      |  val add: Function1[Int, Int]^{r} =
      |    (n: Int) => box.value + n
      |  add
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(2)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedOwnerAliasMethodCanReturnClosureCapturingRegionValue()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  val owner = r
      |  val box: Box^{owner} = new Box(40)
      |  val add: Function1[Int, Int]^{owner} =
      |    (n: Int) => box.value + n
      |  add
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(2)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedForwardedMethodCanReturnClosureCapturingRegionValue()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  val box: Box^{r} = new Box(40)
      |  val add: Function1[Int, Int]^{r} =
      |    (n: Int) => box.value + n
      |  add
      |
      |def wrap(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  make(using r)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    wrap(using region)(2)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedForwardedLocalAliasMethodCanReturnClosureCapturingRegionValue()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  val box: Box^{r} = new Box(40)
      |  val add: Function1[Int, Int]^{r} =
      |    (n: Int) => box.value + n
      |  add
      |
      |def wrap(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  val forwarded = make(using r)
      |  forwarded
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    wrap(using region)(2)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedForwardedBranchMethodCanReturnClosureCapturingRegionValue()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  val box: Box^{r} = new Box(40)
      |  val add: Function1[Int, Int]^{r} =
      |    (n: Int) => box.value + n
      |  add
      |
      |def wrap(flag: Boolean)(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  if flag then make(using r) else make(using r)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    wrap(true)(using region)(2)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedForwardedMatchMethodCanReturnClosureCapturingRegionValue()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  val box: Box^{r} = new Box(40)
      |  val add: Function1[Int, Int]^{r} =
      |    (n: Int) => box.value + n
      |  add
      |
      |def wrap(selector: Int)(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  selector match
      |    case 0 => make(using r)
      |    case _ => make(using r)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    wrap(0)(using region)(2)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val make: Function1[Int, Box^{region}]^{region} =
      |      (value: Int) =>
      |        val owner = region
      |        val box: Box^{owner} = new Box(value)
      |        box
      |    make(42).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val make: Function1[Int, Entry^{region}]^{region} =
      |      (value: Int) =>
      |        val owner = region
      |        val entry: Entry^{owner} = new Entry(metadata)
      |        entry
      |    make(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanReturnDirectOptionFactoryWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val make: Function1[Int, Option[Box^{region}]^{region}]^{region} =
      |      (value: Int) =>
      |        val owner = region
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        Some(new Box(value + keepOwner))
      |    make(42).get.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyOptionFactoryRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val make: Function1[Int, Option[Metadata]^{region}]^{region} =
      |      (value: Int) =>
      |        val owner = region
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        if keepOwner == -1 then Some(metadata)
      |        else Some(metadata)
      |    make(2).get.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanReturnOptionApplyFactoryWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val make: Function1[Int, Option[Box^{region}]^{region}]^{region} =
      |      (value: Int) =>
      |        val owner = region
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        Option(new Box(value + keepOwner))
      |    make(42).get.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyOptionApplyRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val make: Function1[Int, Option[Metadata]^{region}]^{region} =
      |      (value: Int) =>
      |        val owner = region
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        if keepOwner == -1 then Option(metadata)
      |        else Option(metadata)
      |    make(2).get.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanReturnSomeOrNoneWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val make: Function1[Boolean, Option[Box^{region}]^{region}]^{region} =
      |      (include: Boolean) =>
      |        val owner = region
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        if include then Some(new Box(42 + keepOwner)) else None
      |    make(true).get.value + make(false).fold(0)(_.value)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodySomeOrNoneRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val make: Function1[Boolean, Option[Metadata]^{region}]^{region} =
      |      (include: Boolean) =>
      |        val owner = region
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        if include || keepOwner == -1 then Some(metadata) else None
      |    make(true).get.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanReturnSelectedOptionFactoryWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val make: Function1[Int, Option[Box^{region}]^{region}]^{region} =
      |      (value: Int) =>
      |        val owner = region
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        val first = Some(new Box(value + keepOwner))
      |        val second = Some(new Box(value + 1 + keepOwner))
      |        val keepFactories =
      |          System.identityHashCode(first) + System.identityHashCode(second)
      |        val selected =
      |          if value + (keepFactories & 0) >= 0 then first else second
      |        selected
      |    make(42).get.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodySelectedOptionFactoryRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val make: Function1[Int, Option[Metadata]^{region}]^{region} =
      |      (value: Int) =>
      |        val owner = region
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        val first = Some(metadata)
      |        val second = Some(metadata)
      |        if value + keepOwner >= 0 then first else second
      |    make(2).get.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanReturnDirectTupleFactoryWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val make: Function1[Int, Tuple2[Box^{region}, Box^{region}]^{region}]^{region} =
      |      (value: Int) =>
      |        val owner = region
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        Tuple2(new Box(value + keepOwner), new Box(1 + keepOwner))
      |    val pair = make(41)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyTupleFactoryRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val make: Function1[Int, Tuple2[Metadata, Metadata]^{region}]^{region} =
      |      (value: Int) =>
      |        val owner = region
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        if keepOwner == -1 then Tuple2(metadata, metadata)
      |        else Tuple2(metadata, metadata)
      |    val pair = make(2)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanReturnSelectedTupleFactoryWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val make: Function1[Int, Tuple2[Box^{region}, Box^{region}]^{region}]^{region} =
      |      (value: Int) =>
      |        val owner = region
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        val first = Tuple2(new Box(value + keepOwner), new Box(1 + keepOwner))
      |        val second = Tuple2(new Box(value + 1 + keepOwner), new Box(0 + keepOwner))
      |        val keepFactories =
      |          System.identityHashCode(first) + System.identityHashCode(second)
      |        val selected =
      |          if value + (keepFactories & 0) >= 0 then first else second
      |        selected
      |    val pair = make(41)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodySelectedTupleFactoryRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val make: Function1[Int, Tuple2[Metadata, Metadata]^{region}]^{region} =
      |      (value: Int) =>
      |        val owner = region
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        val first = Tuple2(metadata, metadata)
      |        val second = Tuple2(metadata, metadata)
      |        if value + keepOwner >= 0 then first else second
      |    val pair = make(2)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanReturnOptionApplyInlineClosure()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val make: Function1[
      |      Int,
      |      Option[Function1[Int, Box^{region}]^{region}]^{region}
      |    ]^{region} =
      |      (base: Int) =>
      |        val owner = region
      |        Option(
      |          (value: Int) =>
      |            val keepOwner = System.identityHashCode(owner) & 0
      |            val box: Box^{owner} = new Box(base + value + keepOwner)
      |            box
      |        )
      |    make(40).get(2).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyOptionApplyInlineClosureRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val make: Function1[
      |      Int,
      |      Option[Function1[Int, Entry^{region}]^{region}]^{region}
      |    ]^{region} =
      |      (base: Int) =>
      |        val owner = region
      |        Option(
      |          (value: Int) =>
      |            val keepOwner = System.identityHashCode(owner) & 0
      |            val entry: Entry^{owner} = new Entry(metadata)
      |            if keepOwner == -1 then entry else entry
      |        )
      |    make(2).get(0).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanReturnSomeInlineClosure()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val make: Function1[
      |      Int,
      |      Option[Function1[Int, Box^{region}]^{region}]^{region}
      |    ]^{region} =
      |      (base: Int) =>
      |        val owner = region
      |        Some(
      |          (value: Int) =>
      |            val keepOwner = System.identityHashCode(owner) & 0
      |            val box: Box^{owner} = new Box(base + value + keepOwner)
      |            box
      |        )
      |    make(40).get(2).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodySomeInlineClosureRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val make: Function1[
      |      Int,
      |      Option[Function1[Int, Entry^{region}]^{region}]^{region}
      |    ]^{region} =
      |      (base: Int) =>
      |        val owner = region
      |        Some(
      |          (value: Int) =>
      |            val keepOwner = System.identityHashCode(owner) & 0
      |            val entry: Entry^{owner} = new Entry(metadata)
      |            if keepOwner == -1 then entry else entry
      |        )
      |    make(2).get(0).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanReturnOptionApplySelectedLocalClosure()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val make: Function1[
      |      Boolean,
      |      Option[Function1[Int, Box^{region}]^{region}]^{region}
      |    ]^{region} =
      |      (chooseFirst: Boolean) =>
      |        val owner = region
      |        val first = (value: Int) =>
      |          val keepOwner = System.identityHashCode(owner) & 0
      |          val box: Box^{owner} = new Box(value + 40 + keepOwner)
      |          box
      |        val second = (value: Int) =>
      |          val keepOwner = System.identityHashCode(owner) & 0
      |          val box: Box^{owner} = new Box(value + 41 + keepOwner)
      |          box
      |        val selected = if chooseFirst then first else second
      |        Option(selected)
      |    val expected = if flag then 42 else 43
      |    make(flag).get(2).value - expected + 42
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyOptionApplySelectedLocalClosureRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val make: Function1[
      |      Boolean,
      |      Option[Function1[Int, Entry^{region}]^{region}]^{region}
      |    ]^{region} =
      |      (chooseFirst: Boolean) =>
      |        val owner = region
      |        val first = (value: Int) =>
      |          val keepOwner = System.identityHashCode(owner) & 0
      |          val entry: Entry^{owner} = new Entry(metadata)
      |          if keepOwner == -1 then entry else entry
      |        val second = (value: Int) =>
      |          val keepOwner = System.identityHashCode(owner) & 0
      |          val entry: Entry^{owner} = new Entry(metadata)
      |          if keepOwner == -1 then entry else entry
      |        val selected = if chooseFirst then first else second
      |        Option(selected)
      |    make(flag).get(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanReturnSomeSelectedLocalClosure()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val make: Function1[
      |      Boolean,
      |      Option[Function1[Int, Box^{region}]^{region}]^{region}
      |    ]^{region} =
      |      (chooseFirst: Boolean) =>
      |        val owner = region
      |        val first = (value: Int) =>
      |          val keepOwner = System.identityHashCode(owner) & 0
      |          val box: Box^{owner} = new Box(value + 40 + keepOwner)
      |          box
      |        val second = (value: Int) =>
      |          val keepOwner = System.identityHashCode(owner) & 0
      |          val box: Box^{owner} = new Box(value + 41 + keepOwner)
      |          box
      |        val selected = if chooseFirst then first else second
      |        Some(selected)
      |    val expected = if flag then 42 else 43
      |    make(flag).get(2).value - expected + 42
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodySomeSelectedLocalClosureRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val make: Function1[
      |      Boolean,
      |      Option[Function1[Int, Entry^{region}]^{region}]^{region}
      |    ]^{region} =
      |      (chooseFirst: Boolean) =>
      |        val owner = region
      |        val first = (value: Int) =>
      |          val keepOwner = System.identityHashCode(owner) & 0
      |          val entry: Entry^{owner} = new Entry(metadata)
      |          if keepOwner == -1 then entry else entry
      |        val second = (value: Int) =>
      |          val keepOwner = System.identityHashCode(owner) & 0
      |          val entry: Entry^{owner} = new Entry(metadata)
      |          if keepOwner == -1 then entry else entry
      |        val selected = if chooseFirst then first else second
      |        Some(selected)
      |    make(flag).get(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodReturnedClosureBodyCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Box^{r}]^{r} =
      |  (value: Int) =>
      |    val owner = r
      |    val box: Box^{owner} = new Box(value)
      |    box
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(42).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodReturnedClosureBodyRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Entry^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  (value: Int) =>
      |    val owner = r
      |    val entry: Entry^{owner} = new Entry(metadata)
      |    entry
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanCallRegionReturningMethod()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def build(value: Int)(using r: RiftRegion.ScopedRegion^): Box^{r} =
      |  new Box(value)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Box^{r}]^{r} =
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    build(value + keepOwner)(using owner)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(42).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyCalleeRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def build(metadata: Metadata)(using r: RiftRegion.ScopedRegion^): Entry^{r} =
      |  new Entry(metadata)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Entry^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    if keepOwner == -1 then build(metadata)(using owner)
      |    else build(metadata)(using owner)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanCallForwardedRegionReturningMethod()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def build(value: Int)(using r: RiftRegion.ScopedRegion^): Box^{r} =
      |  new Box(value)
      |
      |def forward(value: Int)(using r: RiftRegion.ScopedRegion^): Box^{r} =
      |  build(value)(using r)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Box^{r}]^{r} =
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    forward(value + keepOwner)(using owner)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(42).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyForwardedCalleeRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def build(metadata: Metadata)(using r: RiftRegion.ScopedRegion^): Entry^{r} =
      |  new Entry(metadata)
      |
      |def forward(metadata: Metadata)(using r: RiftRegion.ScopedRegion^): Entry^{r} =
      |  build(metadata)(using r)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Entry^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    if keepOwner == -1 then forward(metadata)(using owner)
      |    else forward(metadata)(using owner)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanCallBranchForwardedRegionReturningMethod()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def build(value: Int)(using r: RiftRegion.ScopedRegion^): Box^{r} =
      |  new Box(value)
      |
      |def forward(flag: Boolean, value: Int)(using r: RiftRegion.ScopedRegion^): Box^{r} =
      |  if flag then build(value)(using r)
      |  else build(value + 1)(using r)
      |
      |def make(flag: Boolean)(using r: RiftRegion.ScopedRegion^): Function1[Int, Box^{r}]^{r} =
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    forward(flag, value + keepOwner)(using owner)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(true)(using region)(42).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyCanCallMatchForwardedRegionReturningMethod()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def build(value: Int)(using r: RiftRegion.ScopedRegion^): Box^{r} =
      |  new Box(value)
      |
      |def forward(selector: Int, value: Int)(using r: RiftRegion.ScopedRegion^): Box^{r} =
      |  selector match
      |    case 0 => build(value)(using r)
      |    case _ => build(value + 1)(using r)
      |
      |def make(selector: Int)(using r: RiftRegion.ScopedRegion^): Function1[Int, Box^{r}]^{r} =
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    forward(selector, value + keepOwner)(using owner)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(0)(using region)(42).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyBranchForwardedCalleeRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def build(metadata: Metadata)(using r: RiftRegion.ScopedRegion^): Entry^{r} =
      |  new Entry(metadata)
      |
      |def forward(flag: Boolean, metadata: Metadata)(using r: RiftRegion.ScopedRegion^): Entry^{r} =
      |  if flag then build(metadata)(using r)
      |  else build(metadata)(using r)
      |
      |def make(flag: Boolean)(using r: RiftRegion.ScopedRegion^): Function1[Int, Entry^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    forward(flag || keepOwner == -1, metadata)(using owner)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(true)(using region)(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanCallOptionReturningMethod()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def build(value: Int)(using r: RiftRegion.ScopedRegion^): Option[Box^{r}]^{r} =
      |  Some(new Box(value))
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Option[Box^{r}]^{r}]^{r} =
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    build(value + keepOwner)(using owner)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(42).get.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyOptionCalleeRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def build(metadata: Metadata)(using r: RiftRegion.ScopedRegion^): Option[Metadata]^{r} =
      |  Some(metadata)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Option[Metadata]^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    if keepOwner == -1 then build(metadata)(using owner)
      |    else build(metadata)(using owner)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(2).get.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanCallOptionApplyReturningMethod()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def build(value: Int)(using r: RiftRegion.ScopedRegion^): Option[Box^{r}]^{r} =
      |  Option(new Box(value))
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Option[Box^{r}]^{r}]^{r} =
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    build(value + keepOwner)(using owner)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(42).get.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyOptionApplyCalleeRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def build(metadata: Metadata)(using r: RiftRegion.ScopedRegion^): Option[Metadata]^{r} =
      |  Option(metadata)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Option[Metadata]^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    if keepOwner == -1 then build(metadata)(using owner)
      |    else build(metadata)(using owner)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(2).get.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanCallTupleReturningMethod()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def build(value: Int)(using r: RiftRegion.ScopedRegion^): Tuple2[Box^{r}, Box^{r}]^{r} =
      |  Tuple2(new Box(value), new Box(1))
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Tuple2[Box^{r}, Box^{r}]^{r}]^{r} =
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    build(value + keepOwner)(using owner)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val pair = make(using region)(41)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyTupleCalleeRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def build(metadata: Metadata)(using r: RiftRegion.ScopedRegion^): Tuple2[Metadata, Metadata]^{r} =
      |  Tuple2(metadata, metadata)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Tuple2[Metadata, Metadata]^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    if keepOwner == -1 then build(metadata)(using owner)
      |    else build(metadata)(using owner)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val pair = make(using region)(2)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanCallSelectedOptionApplyReturningMethod()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def build(value: Int)(using r: RiftRegion.ScopedRegion^): Option[Box^{r}]^{r} =
      |  val first = Option(new Box(value))
      |  val second = Option(new Box(value + 1))
      |  val keepFactories =
      |    System.identityHashCode(first) + System.identityHashCode(second)
      |  if value + (keepFactories & 0) >= 0 then first else second
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Option[Box^{r}]^{r}]^{r} =
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    build(value + keepOwner)(using owner)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(42).get.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodySelectedOptionApplyCalleeRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def build(metadata: Metadata)(using r: RiftRegion.ScopedRegion^): Option[Metadata]^{r} =
      |  val first = Option(metadata)
      |  val second = Option(metadata)
      |  if System.identityHashCode(first) >= 0 then first else second
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Option[Metadata]^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    build(metadata)(using owner)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(2).get.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyCanCallSelectedTupleReturningMethod()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def build(value: Int)(using r: RiftRegion.ScopedRegion^): Tuple2[Box^{r}, Box^{r}]^{r} =
      |  val first = Tuple2(new Box(value), new Box(1))
      |  val second = Tuple2(new Box(value + 1), new Box(0))
      |  val keepFactories =
      |    System.identityHashCode(first) + System.identityHashCode(second)
      |  if value + (keepFactories & 0) >= 0 then first else second
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Tuple2[Box^{r}, Box^{r}]^{r}]^{r} =
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    build(value + keepOwner)(using owner)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val pair = make(using region)(41)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodySelectedTupleCalleeRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def build(metadata: Metadata)(using r: RiftRegion.ScopedRegion^): Tuple2[Metadata, Metadata]^{r} =
      |  val first = Tuple2(metadata, metadata)
      |  val second = Tuple2(metadata, metadata)
      |  if System.identityHashCode(first) >= 0 then first else second
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Tuple2[Metadata, Metadata]^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  (value: Int) =>
      |    val owner = r
      |    val keepOwner = System.identityHashCode(owner) & 0
      |    build(metadata)(using owner)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val pair = make(using region)(2)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedForwardedMethodReturnedClosureBodyCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Box^{r}]^{r} =
      |  (value: Int) =>
      |    val owner = r
      |    val box: Box^{owner} = new Box(value)
      |    box
      |
      |def wrap(using r: RiftRegion.ScopedRegion^): Function1[Int, Box^{r}]^{r} =
      |  make(using r)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    wrap(using region)(42).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedForwardedMethodReturnedClosureBodyRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Entry^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  (value: Int) =>
      |    val owner = r
      |    val entry: Entry^{owner} = new Entry(metadata)
      |    entry
      |
      |def wrap(using r: RiftRegion.ScopedRegion^): Function1[Int, Entry^{r}]^{r} =
      |  make(using r)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    wrap(using region)(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedForwardedBranchMethodReturnedClosureBodyCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Box^{r}]^{r} =
      |  (value: Int) =>
      |    val owner = r
      |    val box: Box^{owner} = new Box(value)
      |    box
      |
      |def wrap(flag: Boolean)(
      |    using r: RiftRegion.ScopedRegion^
      |): Function1[Int, Box^{r}]^{r} =
      |  if flag then make(using r) else make(using r)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    wrap(flag)(using region)(42).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedForwardedBranchMethodReturnedClosureBodyRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Entry^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  (value: Int) =>
      |    val owner = r
      |    val entry: Entry^{owner} = new Entry(metadata)
      |    entry
      |
      |def wrap(flag: Boolean)(
      |    using r: RiftRegion.ScopedRegion^
      |): Function1[Int, Entry^{r}]^{r} =
      |  if flag then make(using r) else make(using r)
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    wrap(flag)(using region)(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedForwardedMatchMethodReturnedClosureBodyCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Box^{r}]^{r} =
      |  (value: Int) =>
      |    val owner = r
      |    val box: Box^{owner} = new Box(value)
      |    box
      |
      |def wrap(selector: Int)(
      |    using r: RiftRegion.ScopedRegion^
      |): Function1[Int, Box^{r}]^{r} =
      |  selector match
      |    case 0 => make(using r)
      |    case _ => make(using r)
      |
      |def ok(selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    wrap(selector)(using region)(42).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedForwardedMatchMethodReturnedClosureBodyRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Entry^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  (value: Int) =>
      |    val owner = r
      |    val entry: Entry^{owner} = new Entry(metadata)
      |    entry
      |
      |def wrap(selector: Int)(
      |    using r: RiftRegion.ScopedRegion^
      |): Function1[Int, Entry^{r}]^{r} =
      |  selector match
      |    case 0 => make(using r)
      |    case _ => make(using r)
      |
      |def bad(selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    wrap(selector)(using region)(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodReturnedLocalClosureBodyCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Box^{r}]^{r} =
      |  val makeBox =
      |    (value: Int) =>
      |      val owner = r
      |      val box: Box^{owner} = new Box(value)
      |      box
      |  makeBox
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(42).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodReturnedLocalClosureBodyRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Entry^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  val makeEntry =
      |    (value: Int) =>
      |      val owner = r
      |      val entry: Entry^{owner} = new Entry(metadata)
      |      entry
      |  makeEntry
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodReturnedSelectedLocalClosureBodyCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(flag: Boolean)(
      |    using r: RiftRegion.ScopedRegion^
      |): Function1[Int, Box^{r}]^{r} =
      |  val first =
      |    (value: Int) =>
      |      val owner = r
      |      val box: Box^{owner} = new Box(value)
      |      box
      |  val second =
      |    (value: Int) =>
      |      val owner = r
      |      val box: Box^{owner} = new Box(value + 1)
      |      box
      |  val selected = if flag then first else second
      |  selected
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(flag)(using region)(42).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodReturnedSelectedLocalClosureBodyRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def make(flag: Boolean)(
      |    using r: RiftRegion.ScopedRegion^
      |): Function1[Int, Entry^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  val first =
      |    (value: Int) =>
      |      val owner = r
      |      val entry: Entry^{owner} = new Entry(metadata)
      |      entry
      |  val second =
      |    (value: Int) =>
      |      val owner = r
      |      val entry: Entry^{owner} = new Entry(metadata)
      |      entry
      |  val selected = if flag then first else second
      |  selected
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(flag)(using region)(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodReturnedForwardedSelectedLocalClosureBodyCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(flag: Boolean)(
      |    using r: RiftRegion.ScopedRegion^
      |): Function1[Int, Box^{r}]^{r} =
      |  val first =
      |    (value: Int) =>
      |      val owner = r
      |      val box: Box^{owner} = new Box(value)
      |      box
      |  val second =
      |    (value: Int) =>
      |      val owner = r
      |      val box: Box^{owner} = new Box(value + 1)
      |      box
      |  val selected = if flag then first else second
      |  val forwarded = selected
      |  forwarded
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(flag)(using region)(42).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodReturnedForwardedSelectedLocalClosureBodyRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def make(flag: Boolean)(
      |    using r: RiftRegion.ScopedRegion^
      |): Function1[Int, Entry^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  val first =
      |    (value: Int) =>
      |      val owner = r
      |      val entry: Entry^{owner} = new Entry(metadata)
      |      entry
      |  val second =
      |    (value: Int) =>
      |      val owner = r
      |      val entry: Entry^{owner} = new Entry(metadata)
      |      entry
      |  val selected = if flag then first else second
      |  val forwarded = selected
      |  forwarded
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(flag)(using region)(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodArgumentLocalClosureBodyCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    makeBox: Function1[Int, Box^{r}]^{r}
      |): Int =
      |  makeBox(40).value + 2
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val makeBox =
      |      (value: Int) =>
      |        val owner = region
      |        val box: Box^{owner} = new Box(value)
      |        box
      |    consume(using region)(makeBox)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodArgumentLocalClosureBodyRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    makeEntry: Function1[Int, Entry^{r}]^{r}
      |): Int =
      |  makeEntry(2).metadata.value
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val makeEntry =
      |      (value: Int) =>
      |        val owner = region
      |        val entry: Entry^{owner} = new Entry(metadata)
      |        entry
      |    consume(using region)(makeEntry)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodArgumentBranchInlineClosureBodyCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    makeBox: Function1[Int, Box^{r}]^{r}
      |): Int =
      |  makeBox(40).value + 2
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    consume(using region)(
      |      if flag then
      |        (value: Int) =>
      |          val owner = region
      |          val box: Box^{owner} = new Box(value)
      |          box
      |      else
      |        (value: Int) =>
      |          val owner = region
      |          val box: Box^{owner} = new Box(value + 1)
      |          box
      |    )
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodArgumentBranchInlineClosureBodyRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    makeEntry: Function1[Int, Entry^{r}]^{r}
      |): Int =
      |  makeEntry(2).metadata.value
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    consume(using region)(
      |      if flag then
      |        (value: Int) =>
      |          val owner = region
      |          val entry: Entry^{owner} = new Entry(metadata)
      |          entry
      |      else
      |        (value: Int) =>
      |          val owner = region
      |          val entry: Entry^{owner} = new Entry(metadata)
      |          entry
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodArgumentMatchInlineClosureBodyCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    makeBox: Function1[Int, Box^{r}]^{r}
      |): Int =
      |  makeBox(40).value + 2
      |
      |def ok(selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    consume(using region)(
      |      selector match
      |        case 0 =>
      |          (value: Int) =>
      |            val owner = region
      |            val box: Box^{owner} = new Box(value)
      |            box
      |        case _ =>
      |          (value: Int) =>
      |            val owner = region
      |            val box: Box^{owner} = new Box(value + 1)
      |            box
      |    )
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodArgumentMatchInlineClosureBodyRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    makeEntry: Function1[Int, Entry^{r}]^{r}
      |): Int =
      |  makeEntry(2).metadata.value
      |
      |def bad(selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    consume(using region)(
      |      selector match
      |        case 0 =>
      |          (value: Int) =>
      |            val owner = region
      |            val entry: Entry^{owner} = new Entry(metadata)
      |            entry
      |        case _ =>
      |          (value: Int) =>
      |            val owner = region
      |            val entry: Entry^{owner} = new Entry(metadata)
      |            entry
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodArgumentBranchLocalClosureBodyCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    makeBox: Function1[Int, Box^{r}]^{r}
      |): Int =
      |  makeBox(40).value + 2
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val first =
      |      (value: Int) =>
      |        val owner = region
      |        val box: Box^{owner} = new Box(value)
      |        box
      |    val second =
      |      (value: Int) =>
      |        val owner = region
      |        val box: Box^{owner} = new Box(value + 1)
      |        box
      |    consume(using region)(if flag then first else second)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodArgumentBranchLocalClosureBodyRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    makeEntry: Function1[Int, Entry^{r}]^{r}
      |): Int =
      |  makeEntry(2).metadata.value
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val first =
      |      (value: Int) =>
      |        val owner = region
      |        val entry: Entry^{owner} = new Entry(metadata)
      |        entry
      |    val second =
      |      (value: Int) =>
      |        val owner = region
      |        val entry: Entry^{owner} = new Entry(metadata)
      |        entry
      |    consume(using region)(if flag then first else second)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodArgumentMatchLocalClosureBodyCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    makeBox: Function1[Int, Box^{r}]^{r}
      |): Int =
      |  makeBox(40).value + 2
      |
      |def ok(selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    val first =
      |      (value: Int) =>
      |        val owner = region
      |        val box: Box^{owner} = new Box(value)
      |        box
      |    val second =
      |      (value: Int) =>
      |        val owner = region
      |        val box: Box^{owner} = new Box(value + 1)
      |        box
      |    consume(using region)(
      |      selector match
      |        case 0 => first
      |        case _ => second
      |    )
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodArgumentMatchLocalClosureBodyRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    makeEntry: Function1[Int, Entry^{r}]^{r}
      |): Int =
      |  makeEntry(2).metadata.value
      |
      |def bad(selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val first =
      |      (value: Int) =>
      |        val owner = region
      |        val entry: Entry^{owner} = new Entry(metadata)
      |        entry
      |    val second =
      |      (value: Int) =>
      |        val owner = region
      |        val entry: Entry^{owner} = new Entry(metadata)
      |        entry
      |    consume(using region)(
      |      selector match
      |        case 0 => first
      |        case _ => second
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodArgumentSelectedLocalClosureBodyCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    makeBox: Function1[Int, Box^{r}]^{r}
      |): Int =
      |  makeBox(40).value + 2
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val first =
      |      (value: Int) =>
      |        val owner = region
      |        val box: Box^{owner} = new Box(value)
      |        box
      |    val second =
      |      (value: Int) =>
      |        val owner = region
      |        val box: Box^{owner} = new Box(value + 1)
      |        box
      |    val selected = if flag then first else second
      |    consume(using region)(selected)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodArgumentSelectedLocalClosureBodyRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    makeEntry: Function1[Int, Entry^{r}]^{r}
      |): Int =
      |  makeEntry(2).metadata.value
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val first =
      |      (value: Int) =>
      |        val owner = region
      |        val entry: Entry^{owner} = new Entry(metadata)
      |        entry
      |    val second =
      |      (value: Int) =>
      |        val owner = region
      |        val entry: Entry^{owner} = new Entry(metadata)
      |        entry
      |    val selected = if flag then first else second
      |    consume(using region)(selected)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedLocalSelectedInlineClosureBodyCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val makeBox: Function1[Int, Box^{region}]^{region} =
      |      if flag then
      |        (value: Int) =>
      |          val owner = region
      |          val box: Box^{owner} = new Box(value)
      |          box
      |      else
      |        (value: Int) =>
      |          val owner = region
      |          val box: Box^{owner} = new Box(value + 1)
      |          box
      |    makeBox(40).value + 2
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedLocalSelectedInlineClosureBodyRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val makeEntry: Function1[Int, Entry^{region}]^{region} =
      |      if flag then
      |        (value: Int) =>
      |          val owner = region
      |          val entry: Entry^{owner} = new Entry(metadata)
      |          entry
      |      else
      |        (value: Int) =>
      |          val owner = region
      |          val entry: Entry^{owner} = new Entry(metadata)
      |          entry
      |    makeEntry(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyReturnedClosureCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(using
      |    r: RiftRegion.ScopedRegion^
      |): Function1[Int, Function1[Int, Box^{r}]^{r}]^{r} =
      |  (base: Int) =>
      |    val owner = r
      |    ((value: Int) =>
      |      val keepOwner = System.identityHashCode(owner) & 0
      |      val box: Box^{owner} = new Box(base + value + keepOwner)
      |      box
      |    ): Function1[Int, Box^{owner}]^{owner}
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(40)(2).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyReturnedClosureRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def make(using
      |    r: RiftRegion.ScopedRegion^
      |): Function1[Int, Function1[Int, Entry^{r}]^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  (base: Int) =>
      |    val owner = r
      |    ((value: Int) =>
      |      val keepOwner = System.identityHashCode(owner) & 0
      |      val entry: Entry^{owner} = new Entry(metadata)
      |      if keepOwner == -1 then entry else entry
      |    ): Function1[Int, Entry^{owner}]^{owner}
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(1)(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyReturnedLocalClosureCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(using
      |    r: RiftRegion.ScopedRegion^
      |): Function1[Int, Function1[Int, Box^{r}]^{r}]^{r} =
      |  (base: Int) =>
      |    val owner = r
      |    val inner =
      |      (value: Int) =>
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        val box: Box^{owner} = new Box(base + value + keepOwner)
      |        box
      |    inner
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(40)(2).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyReturnedLocalClosureRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def make(using
      |    r: RiftRegion.ScopedRegion^
      |): Function1[Int, Function1[Int, Entry^{r}]^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  (base: Int) =>
      |    val owner = r
      |    val inner =
      |      (value: Int) =>
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        val entry: Entry^{owner} = new Entry(metadata)
      |        if keepOwner == -1 then entry else entry
      |    inner
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(1)(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedClosureBodyReturnedTypedLocalClosureCanAllocateWithCapturedOwnerTerm()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(using
      |    r: RiftRegion.ScopedRegion^
      |): Function1[Int, Function1[Int, Box^{r}]^{r}]^{r} =
      |  (base: Int) =>
      |    val owner = r
      |    val inner: Function1[Int, Box^{owner}]^{owner} =
      |      (value: Int) =>
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        val box: Box^{owner} = new Box(base + value + keepOwner)
      |        box
      |    inner
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(40)(2).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedClosureBodyReturnedTypedLocalClosureRejectsUnrootedMetadataWithCapturedOwnerTerm()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def make(using
      |    r: RiftRegion.ScopedRegion^
      |): Function1[Int, Function1[Int, Entry^{r}]^{r}]^{r} =
      |  val metadata = new Metadata(40)
      |  (base: Int) =>
      |    val owner = r
      |    val inner: Function1[Int, Entry^{owner}]^{owner} =
      |      (value: Int) =>
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        val entry: Entry^{owner} = new Entry(metadata)
      |        if keepOwner == -1 then entry else entry
      |    inner
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(1)(2).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def methodReturnedClosureCapturingOwnerNeedsCapturedResultType()
      : Unit =
    assertDoesNotCompile("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def bad(using r: RiftRegion.ScopedRegion^): Function1[Int, Int] =
      |  (value: Int) =>
      |    val owner = r
      |    value + System.identityHashCode(owner)
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodReturnedClosureRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  val metadata = new Metadata(40)
      |  val add: Function1[Int, Int]^{r} =
      |    (n: Int) => metadata.value + n
      |  add
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(2)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedOwnerAliasMethodReturnedClosureRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  val owner = r
      |  val metadata = new Metadata(40)
      |  val add: Function1[Int, Int]^{owner} =
      |    (n: Int) => metadata.value + n
      |  add
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)(2)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedForwardedBranchMethodReturnedClosureRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  val metadata = new Metadata(40)
      |  val add: Function1[Int, Int]^{r} =
      |    (n: Int) => metadata.value + n
      |  add
      |
      |def wrap(flag: Boolean)(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  if flag then make(using r) else make(using r)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    wrap(true)(using region)(2)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedForwardedMatchMethodReturnedClosureRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  val metadata = new Metadata(40)
      |  val add: Function1[Int, Int]^{r} =
      |    (n: Int) => metadata.value + n
      |  add
      |
      |def wrap(selector: Int)(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  selector match
      |    case 0 => make(using r)
      |    case _ => make(using r)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    wrap(0)(using region)(2)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedForwardedLocalAliasMethodReturnedClosureRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  val metadata = new Metadata(40)
      |  val add: Function1[Int, Int]^{r} =
      |    (n: Int) => metadata.value + n
      |  add
      |
      |def wrap(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  val forwarded = make(using r)
      |  forwarded
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    wrap(using region)(2)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedForwardedMethodReturnedClosureRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  val metadata = new Metadata(40)
      |  val add: Function1[Int, Int]^{r} =
      |    (n: Int) => metadata.value + n
      |  add
      |
      |def wrap(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
      |  make(using r)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    wrap(using region)(2)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedOwnerAliasLocalNew(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val owner = region
      |    val box: Box^{owner} = new Box(42)
      |    box.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedOwnerAliasRejectsUnrootedMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val owner = region
      |    final class Box(val metadata: Metadata^{owner})
      |    val metadata = new Metadata(40)
      |    val box: Box^{owner} = new Box(metadata)
      |    box.metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedOwnerAliasMethodReturnedLocalNew(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Box^{r} =
      |  val owner = r
      |  val box: Box^{owner} = new Box(42)
      |  val result: Box^{r} = box
      |  result
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedOwnerAliasMethodRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Box(val metadata: Metadata^)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Box^{r} =
      |  val owner = r
      |  val metadata = new Metadata(40)
      |  val box: Box^{owner} = new Box(metadata)
      |  val result: Box^{r} = box
      |  result
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(using region).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodReturnedSelectedLocalNewCanAllocate()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(flag: Boolean)(using r: RiftRegion.ScopedRegion^): Box^{r} =
      |  val first = new Box(40)
      |  val second = new Box(41)
      |  val selected = if flag then first else second
      |  selected
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(flag)(using region).value + 2
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodReturnedSelectedLocalNewRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata^)
      |
      |def make(flag: Boolean)(using r: RiftRegion.ScopedRegion^): Entry^{r} =
      |  val metadata = new Metadata(40)
      |  val first = new Entry(metadata)
      |  val second = new Entry(metadata)
      |  val selected = if flag then first else second
      |  selected
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(flag)(using region).metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodReturnedSelectedLocalSomeFactoryCanAllocate()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(flag: Boolean)(using r: RiftRegion.ScopedRegion^)
      |    : Option[Box^{r}]^{r} =
      |  val first = Some(new Box(40))
      |  val second = Some(new Box(41))
      |  val selected = if flag then first else second
      |  selected
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(flag)(using region).get.value + 2
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodReturnedSelectedLocalSomeFactoryRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def make(flag: Boolean)(using r: RiftRegion.ScopedRegion^)
      |    : Option[Metadata]^{r} =
      |  val metadata = new Metadata(40)
      |  val first = Some(metadata)
      |  val second = Some(metadata)
      |  val selected = if flag then first else second
      |  selected
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(flag)(using region).get.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodReturnedSelectedLocalOptionApplyFactoryCanAllocate()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(flag: Boolean)(using r: RiftRegion.ScopedRegion^)
      |    : Option[Box^{r}]^{r} =
      |  val first = Option(new Box(40))
      |  val second = Option(new Box(41))
      |  val selected = if flag then first else second
      |  selected
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(flag)(using region).get.value + 2
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodReturnedSelectedLocalOptionApplyFactoryRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def make(flag: Boolean)(using r: RiftRegion.ScopedRegion^)
      |    : Option[Metadata]^{r} =
      |  val metadata = new Metadata(40)
      |  val first = Option(metadata)
      |  val second = Option(metadata)
      |  val selected = if flag then first else second
      |  selected
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    make(flag)(using region).get.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodReturnedSelectedLocalTuple2FactoryCanAllocate()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def make(flag: Boolean)(using r: RiftRegion.ScopedRegion^)
      |    : Tuple2[Box^{r}, Box^{r}]^{r} =
      |  val first = Tuple2(new Box(40), new Box(1))
      |  val second = Tuple2(new Box(41), new Box(2))
      |  val selected = if flag then first else second
      |  selected
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val pair = make(flag)(using region)
      |    pair._1.value + pair._2.value + 1
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodReturnedSelectedLocalTuple2FactoryRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def make(flag: Boolean)(using r: RiftRegion.ScopedRegion^)
      |    : Tuple2[Metadata, Metadata]^{r} =
      |  val metadata = new Metadata(40)
      |  val first = Tuple2(metadata, metadata)
      |  val second = Tuple2(metadata, metadata)
      |  val selected = if flag then first else second
      |  selected
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val pair = make(flag)(using region)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodArgumentSelectedLocalNewCanAllocate()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(box: Box^{r}): Int =
      |  box.value + 2
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val first = new Box(40)
      |    val second = new Box(41)
      |    val selected = if flag then first else second
      |    consume(using region)(selected)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodArgumentSelectedLocalNewRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata^)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(entry: Entry^{r}): Int =
      |  entry.metadata.value
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val first = new Entry(metadata)
      |    val second = new Entry(metadata)
      |    val selected = if flag then first else second
      |    consume(using region)(selected)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodArgumentSelectedLocalSomeFactoryCanAllocate()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    option: Option[Box^{r}]^{r}
      |): Int =
      |  option.get.value + 2
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val first = Some(new Box(40))
      |    val second = Some(new Box(41))
      |    val selected = if flag then first else second
      |    consume(using region)(selected)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodArgumentSelectedLocalSomeFactoryRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    option: Option[Metadata]^{r}
      |): Int =
      |  option.get.value
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val first = Some(metadata)
      |    val second = Some(metadata)
      |    val selected = if flag then first else second
      |    consume(using region)(selected)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodArgumentSelectedLocalOptionApplyFactoryCanAllocate()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    option: Option[Box^{r}]^{r}
      |): Int =
      |  option.get.value + 2
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val first = Option(new Box(40))
      |    val second = Option(new Box(41))
      |    val selected = if flag then first else second
      |    consume(using region)(selected)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodArgumentSelectedLocalOptionApplyFactoryRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    option: Option[Metadata]^{r}
      |): Int =
      |  option.get.value
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val first = Option(metadata)
      |    val second = Option(metadata)
      |    val selected = if flag then first else second
      |    consume(using region)(selected)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodArgumentSelectedLocalTuple2FactoryCanAllocate()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    pair: Tuple2[Box^{r}, Box^{r}]^{r}
      |): Int =
      |  pair._1.value + pair._2.value + 1
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val first = Tuple2(new Box(40), new Box(1))
      |    val second = Tuple2(new Box(41), new Box(2))
      |    val selected = if flag then first else second
      |    consume(using region)(selected)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodArgumentSelectedLocalTuple2FactoryRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def consume(using r: RiftRegion.ScopedRegion^)(
      |    pair: Tuple2[Metadata, Metadata]^{r}
      |): Int =
      |  pair._1.value + pair._2.value
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val first = Tuple2(metadata, metadata)
      |    val second = Tuple2(metadata, metadata)
      |    val selected = if flag then first else second
      |    consume(using region)(selected)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedMethodArgumentSelectedLocalPolymorphicCellCanAllocate()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def consume[A](using r: RiftRegion.ScopedRegion^)(
      |    cell: Cell[A^{r}]^{r}
      |): Cell[A^{r}]^{r} =
      |  cell
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val first: Cell[Box^{region}]^{region} =
      |      new Cell[Box^{region}](new Box(40))
      |    val second: Cell[Box^{region}]^{region} =
      |      new Cell[Box^{region}](new Box(41))
      |    val selected = if flag then first else second
      |    val cell = consume[Box](using region)(selected)
      |    cell.value.value + 2
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedMethodArgumentSelectedLocalPolymorphicCellRejectsHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def consume[A](using r: RiftRegion.ScopedRegion^)(
      |    cell: Cell[A^{r}]^{r}
      |): Cell[A^{r}]^{r} =
      |  cell
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val first: Cell[Metadata]^{region} =
      |      new Cell[Metadata](metadata)
      |    val second: Cell[Metadata]^{region} =
      |      new Cell[Metadata](metadata)
      |    val selected: Cell[Metadata]^{region} =
      |      if flag then first else second
      |    consume[Metadata](using region)(selected).value.value
      |  }
      |""".stripMargin,
      "cannot flow into capture set"
    )

  @Test def inferredRegionOwnedClosureCannotCaptureUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val box: Box^{region} = new Box(1)
      |    val metadata = new Metadata(40)
      |    val add: Function1[Int, Int]^{region} =
      |      (n: Int) => box.value + metadata.value + n
      |    add(2)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
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

  @Test def inferredRegionOwnedArrayCanBeStoredInScopedObject(): Unit =
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
      |      new Array[Leaf^{region}](2)
      |    val bag: Bag^{region} = new Bag(items)
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

  @Test def inferredRegionOwnedArrayCanStoreRegionObject(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val items: Array[Leaf^{region}]^{region} =
      |      new Array[Leaf^{region}](1)
      |    val leaf: Leaf^{region} = new Leaf(42)
      |    items(0) = leaf
      |    leaf.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedArrayCanStoreInlineNewRegionObject(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val items: Array[Leaf^{region}]^{region} =
      |      new Array[Leaf^{region}](1)
      |    items(0) = new Leaf(42)
      |    items(0).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedArrayCanStoreInlineSomeFactory(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val items: Array[Option[Leaf^{region}]^{region}]^{region} =
      |      new Array[Option[Leaf^{region}]^{region}](1)
      |    items(0) = Some(new Leaf(42))
      |    items(0).get.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedArrayCanStoreInlineOptionApplyFactory(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val items: Array[Option[Leaf^{region}]^{region}]^{region} =
      |      new Array[Option[Leaf^{region}]^{region}](1)
      |    items(0) = Option(new Leaf(42))
      |    items(0).get.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedArrayCanStoreInlineTuple2Factory(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val items: Array[Tuple2[Leaf^{region}, Leaf^{region}]^{region}]^{region} =
      |      new Array[Tuple2[Leaf^{region}, Leaf^{region}]^{region}](1)
      |    items(0) = Tuple2(new Leaf(40), new Leaf(2))
      |    items(0)._1.value + items(0)._2.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedArrayCanStoreBranchMatchDirectFactories(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean, selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    val leaves: Array[Leaf^{region}]^{region} =
      |      new Array[Leaf^{region}](1)
      |    leaves(0) =
      |      if flag then new Leaf(40) else new Leaf(41)
      |
      |    val options: Array[Option[Leaf^{region}]^{region}]^{region} =
      |      new Array[Option[Leaf^{region}]^{region}](1)
      |    options(0) =
      |      selector match
      |        case 0 => Option(new Leaf(1))
      |        case _ => Option(new Leaf(2))
      |
      |    val pairs: Array[Tuple2[Leaf^{region}, Leaf^{region}]^{region}]^{region} =
      |      new Array[Tuple2[Leaf^{region}, Leaf^{region}]^{region}](1)
      |    pairs(0) =
      |      if flag then Tuple2(new Leaf(1), new Leaf(1))
      |      else Tuple2(new Leaf(2), new Leaf(2))
      |
      |    leaves(0).value + options(0).get.value +
      |      pairs(0)._1.value + pairs(0)._2.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedArrayCanStoreSelectedSyntheticFactories(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val someItems: Array[Option[Leaf^{region}]^{region}]^{region} =
      |      new Array[Option[Leaf^{region}]^{region}](1)
      |    val someFirst = Some(new Leaf(40))
      |    val someSecond = Some(new Leaf(41))
      |    val someSelected = if flag then someFirst else someSecond
      |    someItems(0) = someSelected
      |
      |    val optionItems: Array[Option[Leaf^{region}]^{region}]^{region} =
      |      new Array[Option[Leaf^{region}]^{region}](1)
      |    val optionFirst = Option(new Leaf(1))
      |    val optionSecond = Option(new Leaf(2))
      |    val optionSelected = if flag then optionFirst else optionSecond
      |    optionItems(0) = optionSelected
      |
      |    val tupleItems: Array[Tuple2[Leaf^{region}, Leaf^{region}]^{region}]^{region} =
      |      new Array[Tuple2[Leaf^{region}, Leaf^{region}]^{region}](1)
      |    val tupleFirst = Tuple2(new Leaf(1), new Leaf(1))
      |    val tupleSecond = Tuple2(new Leaf(2), new Leaf(2))
      |    val tupleSelected = if flag then tupleFirst else tupleSecond
      |    tupleItems(0) = tupleSelected
      |
      |    someItems(0).get.value +
      |      optionItems(0).get.value +
      |      tupleItems(0)._1.value +
      |      tupleItems(0)._2.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedArrayCanStoreInlineTuple3Factory(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val items: Array[Tuple3[Leaf^{region}, Leaf^{region}, Leaf^{region}]^{region}]^{region} =
      |      new Array[Tuple3[Leaf^{region}, Leaf^{region}, Leaf^{region}]^{region}](1)
      |    items(0) = Tuple3(new Leaf(20), new Leaf(20), new Leaf(2))
      |    items(0)._1.value + items(0)._2.value + items(0)._3.value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedArrayCanStoreInlineClosure(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val items: Array[Function1[Int, Int]^{region}]^{region} =
      |      new Array[Function1[Int, Int]^{region}](1)
      |    items(0) = (n: Int) => n + 40
      |    items(0)(2)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedArrayCanStoreInlineClosureBodyAllocation()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val owner = region
      |    val items: Array[Function1[Int, Box^{region}]^{region}]^{region} =
      |      new Array[Function1[Int, Box^{region}]^{region}](1)
      |    items(0) = (n: Int) =>
      |      val keepOwner = System.identityHashCode(owner) & 0
      |      val box: Box^{owner} = new Box(n + 40 + keepOwner)
      |      box
      |    items(0)(2).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedArrayCanStoreSelectedLocalClosure(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val items: Array[Function1[Int, Int]^{region}]^{region} =
      |      new Array[Function1[Int, Int]^{region}](1)
      |    val first = (n: Int) => n + 40
      |    val second = (n: Int) => n + 41
      |    val selected = if flag then first else second
      |    items(0) = selected
      |    items(0)(2)
      |  }
      |""".stripMargin)

  @Test def inferredRegionOwnedArrayClosureCannotCaptureUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val items: Array[Function1[Int, Int]^{region}]^{region} =
      |      new Array[Function1[Int, Int]^{region}](1)
      |    items(0) = (n: Int) => metadata.value + n
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedArrayClosureBodyCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val owner = region
      |    val metadata = new Metadata(40)
      |    val items: Array[Function1[Int, Entry^{region}]^{region}]^{region} =
      |      new Array[Function1[Int, Entry^{region}]^{region}](1)
      |    items(0) = (n: Int) =>
      |      val keepOwner = System.identityHashCode(owner) & 0
      |      val entry: Entry^{owner} = new Entry(metadata)
      |      if keepOwner == -1 then entry else entry
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

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

  @Test def inferredRegionOwnedArrayCannotStoreHeapObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val items: Array[Metadata]^{region} =
      |      new Array[Metadata](1)
      |    val metadata = new Metadata(41)
      |    items(0) = metadata
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedArrayCannotInlineStoreHeapElementType(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val items: Array[Metadata]^{region} =
      |      new Array[Metadata](1)
      |    items(0) = new Metadata(41)
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedArrayCannotInlineStoreSomeWithUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(41)
      |    val items: Array[Option[Metadata]^{region}]^{region} =
      |      new Array[Option[Metadata]^{region}](1)
      |    items(0) = Some(metadata)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedArrayCannotInlineStoreOptionApplyWithUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(41)
      |    val items: Array[Option[Metadata]^{region}]^{region} =
      |      new Array[Option[Metadata]^{region}](1)
      |    items(0) = Option(metadata)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedArrayCannotInlineStoreTuple2WithUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(41)
      |    val items: Array[Tuple2[Metadata, Metadata]^{region}]^{region} =
      |      new Array[Tuple2[Metadata, Metadata]^{region}](1)
      |    items(0) = Tuple2(metadata, metadata)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedArrayCannotStoreBranchFactoryWithUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(41)
      |    val items: Array[Option[Metadata]^{region}]^{region} =
      |      new Array[Option[Metadata]^{region}](1)
      |    items(0) =
      |      if flag then Some(metadata) else Some(metadata)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedArrayCannotStoreSelectedSomeWithUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(41)
      |    val items: Array[Option[Metadata]^{region}]^{region} =
      |      new Array[Option[Metadata]^{region}](1)
      |    val first = Some(metadata)
      |    val second = Some(metadata)
      |    val selected = if flag then first else second
      |    items(0) = selected
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedArrayCannotStoreSelectedOptionApplyWithUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(41)
      |    val items: Array[Option[Metadata]^{region}]^{region} =
      |      new Array[Option[Metadata]^{region}](1)
      |    val first = Option(metadata)
      |    val second = Option(metadata)
      |    val selected = if flag then first else second
      |    items(0) = selected
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedArrayCannotStoreSelectedTuple2WithUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(41)
      |    val items: Array[Tuple2[Metadata, Metadata]^{region}]^{region} =
      |      new Array[Tuple2[Metadata, Metadata]^{region}](1)
      |    val first = Tuple2(metadata, metadata)
      |    val second = Tuple2(metadata, metadata)
      |    val selected = if flag then first else second
      |    items(0) = selected
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedArrayCannotInlineStoreTuple3WithUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(41)
      |    val items: Array[Tuple3[Metadata, Metadata, Metadata]^{region}]^{region} =
      |      new Array[Tuple3[Metadata, Metadata, Metadata]^{region}](1)
      |    items(0) = Tuple3(metadata, metadata, metadata)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionOwnedArrayCannotEscapeHeap(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val items: Array[Leaf^{region}]^{region} =
      |      new Array[Leaf^{region}](1)
      |    Holder.retained = items
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def inferredMethodReturnedRegionOwnedArrayCanStoreRegionObject(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Array[Leaf^{r}]^{r} =
      |  val items: Array[Leaf^{r}]^{r} =
      |    new Array[Leaf^{r}](1)
      |  val leaf: Leaf^{r} = new Leaf(42)
      |  items(0) = leaf
      |  items
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val items = make(using region)
      |    items(0).value
      |  }
      |""".stripMargin)

  @Test def inferredMethodReturnedRegionOwnedArrayCannotStoreHeapObject(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Array[Metadata]^{r} =
      |  val items: Array[Metadata]^{r} =
      |    new Array[Metadata](1)
      |  val metadata = new Metadata(41)
      |  items(0) = metadata
      |  items
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def inferredMethodReturnedRegionOwnedArrayCannotEscapeHeap(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def make(using r: RiftRegion.ScopedRegion^): Array[Leaf^{r}]^{r} =
      |  new Array[Leaf^{r}](1)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val items = make(using region)
      |    Holder.retained = items
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def inferredForwardedMethodReturnedRegionOwnedArrayCanStoreRegionObject()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Array[Leaf^{r}]^{r} =
      |  val items: Array[Leaf^{r}]^{r} =
      |    new Array[Leaf^{r}](1)
      |  val leaf: Leaf^{r} = new Leaf(42)
      |  items(0) = leaf
      |  items
      |
      |def wrap(using r: RiftRegion.ScopedRegion^): Array[Leaf^{r}]^{r} =
      |  make(using r)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val items = wrap(using region)
      |    items(0).value
      |  }
      |""".stripMargin)

  @Test def inferredForwardedLocalAliasMethodReturnedRegionOwnedArrayCanStoreRegionObject()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Array[Leaf^{r}]^{r} =
      |  val items: Array[Leaf^{r}]^{r} =
      |    new Array[Leaf^{r}](1)
      |  val leaf: Leaf^{r} = new Leaf(42)
      |  items(0) = leaf
      |  items
      |
      |def wrap(using r: RiftRegion.ScopedRegion^): Array[Leaf^{r}]^{r} =
      |  val items = make(using r)
      |  items
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val items = wrap(using region)
      |    items(0).value
      |  }
      |""".stripMargin)

  @Test def inferredForwardedMethodReturnedRegionOwnedArrayCannotStoreHeapObject()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Array[Metadata]^{r} =
      |  val items: Array[Metadata]^{r} =
      |    new Array[Metadata](1)
      |  val metadata = new Metadata(41)
      |  items(0) = metadata
      |  items
      |
      |def wrap(using r: RiftRegion.ScopedRegion^): Array[Metadata]^{r} =
      |  make(using r)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    wrap(using region)
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def inferredForwardedMethodReturnedRegionOwnedArrayCannotEscapeHeap()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def make(using r: RiftRegion.ScopedRegion^): Array[Leaf^{r}]^{r} =
      |  new Array[Leaf^{r}](1)
      |
      |def wrap(using r: RiftRegion.ScopedRegion^): Array[Leaf^{r}]^{r} =
      |  make(using r)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val items = wrap(using region)
      |    Holder.retained = items
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def inferredForwardedBranchMethodReturnedRegionOwnedArrayCanStoreRegionObject()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Array[Leaf^{r}]^{r} =
      |  val items: Array[Leaf^{r}]^{r} =
      |    new Array[Leaf^{r}](1)
      |  val leaf: Leaf^{r} = new Leaf(42)
      |  items(0) = leaf
      |  items
      |
      |def wrap(flag: Boolean)(using r: RiftRegion.ScopedRegion^): Array[Leaf^{r}]^{r} =
      |  if flag then make(using r) else make(using r)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val items = wrap(true)(using region)
      |    items(0).value
      |  }
      |""".stripMargin)

  @Test def inferredForwardedMatchMethodReturnedRegionOwnedArrayCanStoreRegionObject()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Array[Leaf^{r}]^{r} =
      |  val items: Array[Leaf^{r}]^{r} =
      |    new Array[Leaf^{r}](1)
      |  val leaf: Leaf^{r} = new Leaf(42)
      |  items(0) = leaf
      |  items
      |
      |def wrap(selector: Int)(using r: RiftRegion.ScopedRegion^): Array[Leaf^{r}]^{r} =
      |  selector match
      |    case 0 => make(using r)
      |    case _ => make(using r)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val items = wrap(0)(using region)
      |    items(0).value
      |  }
      |""".stripMargin)

  @Test def inferredForwardedBranchMethodReturnedRegionOwnedArrayCannotStoreHeapObject()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def make(using r: RiftRegion.ScopedRegion^): Array[Metadata]^{r} =
      |  val items: Array[Metadata]^{r} =
      |    new Array[Metadata](1)
      |  val metadata = new Metadata(41)
      |  items(0) = metadata
      |  items
      |
      |def wrap(flag: Boolean)(using r: RiftRegion.ScopedRegion^): Array[Metadata]^{r} =
      |  if flag then make(using r) else make(using r)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    wrap(true)(using region)
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def inferredForwardedMatchMethodReturnedRegionOwnedArrayCannotEscapeHeap()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def make(using r: RiftRegion.ScopedRegion^): Array[Leaf^{r}]^{r} =
      |  new Array[Leaf^{r}](1)
      |
      |def wrap(selector: Int)(using r: RiftRegion.ScopedRegion^): Array[Leaf^{r}]^{r} =
      |  selector match
      |    case 0 => make(using r)
      |    case _ => make(using r)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val items = wrap(0)(using region)
      |    Holder.retained = items
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
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
      |    def makeMetadata(): Metadata = new Metadata(41)
      |    val buffer = RiftRegion.objectBuffer[Metadata](1)
      |    val metadata = makeMetadata()
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
      |    def makeMetadata(): Metadata = new Metadata(41)
      |    val buffer = RiftRegion.objectBuffer[Metadata](1)
      |    val metadata = makeMetadata()
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

  @Test def streamWindowRanksInferSelectedLocalSyntheticFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val indexed =
      |      RiftRegion.streamWindowIndexedRank[
      |        Option[Leaf^{stream}]^{stream}
      |      ](10, 8, 1)
      |    val someLow = Some(new Leaf(10))
      |    val someHigh = Some(new Leaf(40))
      |    val someSelected = if flag then someLow else someHigh
      |    RiftRegion.putWindowRank(stream, indexed, 1, someSelected, 1L)
      |
      |    val longIndexed =
      |      RiftRegion.streamWindowLongIndexedRankLexicographic[
      |        Option[Leaf^{stream}]^{stream}
      |      ](10, 1, 4)
      |    val longBucket =
      |      RiftRegion.streamWindowBucketFor(stream, longIndexed, 7L)
      |    val optionLow = Option(new Leaf(1))
      |    val optionHigh = Option(new Leaf(2))
      |    val optionSelected = if flag then optionLow else optionHigh
      |    RiftRegion.putWindowRankInBucket(
      |      stream,
      |      longIndexed,
      |      longBucket,
      |      10L,
      |      optionSelected,
      |      2L,
      |      3L,
      |      4L,
      |      5L
      |    )
      |
      |    val table =
      |      RiftRegion.streamWindowTableRankLexicographic[
      |        Tuple2[Leaf^{stream}, Leaf^{stream}]^{stream}
      |      ](10, 1, 4)
      |    val tableBucket =
      |      RiftRegion.streamWindowBucketFor(stream, table, 7L)
      |    val pairLow = Tuple2(new Leaf(1), new Leaf(1))
      |    val pairHigh = Tuple2(new Leaf(2), new Leaf(2))
      |    val pairSelected = if flag then pairLow else pairHigh
      |    RiftRegion.putTableRankInBucket(
      |      stream,
      |      table,
      |      tableBucket,
      |      20L,
      |      pairSelected,
      |      3L,
      |      4L,
      |      5L,
      |      6L
      |    )
      |
      |    RiftRegion.peekWindowRank(stream, indexed).get.value +
      |      RiftRegion.peekWindowRank(stream, longIndexed).get.value +
      |      RiftRegion.peekTableRank(stream, table)._1.value
      |  }
      |""".stripMargin)

  @Test def streamWindowRanksInferClosurePlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val indexed =
      |      RiftRegion.streamWindowIndexedRank[
      |        Function1[Int, Int]^{stream}
      |      ](10, 8, 1)
      |    RiftRegion.putWindowRank(stream, indexed, 1, (n: Int) => n + 40, 1L)
      |
      |    val longIndexed =
      |      RiftRegion.streamWindowLongIndexedRank[
      |        Function1[Int, Int]^{stream}
      |      ](10, 1, 4)
      |    val longBucket =
      |      RiftRegion.streamWindowBucketFor(stream, longIndexed, 7L)
      |    val first = (n: Int) => n + 10
      |    val second = (n: Int) => n + 20
      |    val selected = if flag then first else second
      |    RiftRegion.putWindowRankInBucket(
      |      stream,
      |      longIndexed,
      |      longBucket,
      |      10L,
      |      selected,
      |      2L
      |    )
      |
      |    val table =
      |      RiftRegion.streamWindowTableRank[
      |        Function1[Int, Int]^{stream}
      |      ](10, 1, 4)
      |    val tableBucket =
      |      RiftRegion.streamWindowBucketFor(stream, table, 7L)
      |    RiftRegion.putTableRankInBucket(
      |      stream,
      |      table,
      |      tableBucket,
      |      20L,
      |      (n: Int) => n + 1,
      |      3L
      |    )
      |
      |    RiftRegion.peekWindowRank(stream, indexed)(2) +
      |      RiftRegion.peekWindowRank(stream, longIndexed)(2) +
      |      RiftRegion.peekTableRank(stream, table)(2)
      |  }
      |""".stripMargin)

  @Test def streamWindowRanksInferBranchMatchSyntheticFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean, selector: Int): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val indexed =
      |      RiftRegion.streamWindowIndexedRank[
      |        Option[Leaf^{stream}]^{stream}
      |      ](10, 8, 1)
      |    RiftRegion.putWindowRank(
      |      stream,
      |      indexed,
      |      1,
      |      if flag then Some(new Leaf(10)) else Some(new Leaf(40)),
      |      1L
      |    )
      |
      |    val longIndexed =
      |      RiftRegion.streamWindowLongIndexedRank[
      |        Option[Leaf^{stream}]^{stream}
      |      ](10, 1, 4)
      |    val longBucket =
      |      RiftRegion.streamWindowBucketFor(stream, longIndexed, 7L)
      |    RiftRegion.putWindowRankInBucket(
      |      stream,
      |      longIndexed,
      |      longBucket,
      |      10L,
      |      (selector match
      |        case 0 => Option(new Leaf(1))
      |        case _ => Option(new Leaf(2))
      |      ),
      |      2L
      |    )
      |
      |    val table =
      |      RiftRegion.streamWindowTableRank[
      |        Tuple2[Leaf^{stream}, Leaf^{stream}]^{stream}
      |      ](10, 1, 4)
      |    val tableBucket =
      |      RiftRegion.streamWindowBucketFor(stream, table, 7L)
      |    RiftRegion.putTableRankInBucket(
      |      stream,
      |      table,
      |      tableBucket,
      |      20L,
      |      if flag then Tuple2(new Leaf(3), new Leaf(4))
      |      else Tuple2(new Leaf(5), new Leaf(6)),
      |      3L
      |    )
      |
      |    RiftRegion.peekWindowRank(stream, indexed).get.value +
      |      RiftRegion.peekWindowRank(stream, longIndexed).get.value +
      |      RiftRegion.peekTableRank(stream, table)._1.value
      |  }
      |""".stripMargin)

  @Test def streamWindowRanksInferInlineArrayPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val indexed =
      |      RiftRegion.streamWindowIndexedRank[
      |        Array[Leaf^{stream}]^{stream}
      |      ](10, 8, 1)
      |    RiftRegion.putWindowRank(
      |      stream,
      |      indexed,
      |      1,
      |      new Array[Leaf^{stream}](1),
      |      1L
      |    )
      |    val indexedLeaves = RiftRegion.peekWindowRank(stream, indexed)
      |    indexedLeaves(0) = new Leaf(10)
      |
      |    val longIndexed =
      |      RiftRegion.streamWindowLongIndexedRankLexicographic[
      |        Array[Leaf^{stream}]^{stream}
      |      ](10, 1, 4)
      |    val longBucket =
      |      RiftRegion.streamWindowBucketFor(stream, longIndexed, 7L)
      |    RiftRegion.putWindowRankInBucket(
      |      stream,
      |      longIndexed,
      |      longBucket,
      |      10L,
      |      new Array[Leaf^{stream}](1),
      |      2L,
      |      3L,
      |      4L,
      |      5L
      |    )
      |    val longLeaves = RiftRegion.peekWindowRank(stream, longIndexed)
      |    longLeaves(0) = new Leaf(20)
      |
      |    val table =
      |      RiftRegion.streamWindowTableRank[
      |        Array[Leaf^{stream}]^{stream}
      |      ](10, 1, 4)
      |    val tableBucket =
      |      RiftRegion.streamWindowBucketFor(stream, table, 7L)
      |    RiftRegion.putTableRankInBucket(
      |      stream,
      |      table,
      |      tableBucket,
      |      20L,
      |      new Array[Leaf^{stream}](1),
      |      3L
      |    )
      |    val tableLeaves = RiftRegion.peekTableRank(stream, table)
      |    tableLeaves(0) = new Leaf(30)
      |
      |    indexedLeaves(0).value + longLeaves(0).value +
      |      tableLeaves(0).value
      |  }
      |""".stripMargin)

  @Test def streamWindowIndexedRankInfersBranchLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val indexed = RiftRegion.streamWindowIndexedRank[Row](10, 8, 1)
      |    val low = new Row(10)
      |    val high = new Row(40)
      |    RiftRegion.putWindowRank(
      |      stream,
      |      indexed,
      |      1,
      |      if flag then low else high,
      |      1L
      |    )
      |    RiftRegion.peekWindowRank(stream, indexed).value
      |  }
      |""".stripMargin)

  @Test def streamWindowIndexedRankInfersMatchLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(selector: Int): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val indexed = RiftRegion.streamWindowIndexedRank[Row](10, 8, 1)
      |    val low = new Row(10)
      |    val high = new Row(40)
      |    RiftRegion.putWindowRank(
      |      stream,
      |      indexed,
      |      1,
      |      (selector match
      |        case 0 => low
      |        case _ => high
      |      ),
      |      1L
      |    )
      |    RiftRegion.peekWindowRank(stream, indexed).value
      |  }
      |""".stripMargin)

  @Test def streamWindowLongIndexedRankInfersBranchLocalNewPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val longIndexed =
      |      RiftRegion.streamWindowLongIndexedRank[Row](10, 1, 4)
      |    val longBucket =
      |      RiftRegion.streamWindowBucketFor(stream, longIndexed, 7L)
      |    val first = new Row(1)
      |    val second = new Row(2)
      |    RiftRegion.putWindowRankInBucket(
      |      stream,
      |      longIndexed,
      |      longBucket,
      |      10L,
      |      if flag then first else second,
      |      2L
      |    )
      |    RiftRegion.peekWindowRank(stream, longIndexed).value
      |  }
      |""".stripMargin)

  @Test def streamWindowLongIndexedRankInfersMatchLocalNewPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(selector: Int): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val longIndexed =
      |      RiftRegion.streamWindowLongIndexedRank[Row](10, 1, 4)
      |    val longBucket =
      |      RiftRegion.streamWindowBucketFor(stream, longIndexed, 7L)
      |    val first = new Row(1)
      |    val second = new Row(2)
      |    RiftRegion.putWindowRankInBucket(
      |      stream,
      |      longIndexed,
      |      longBucket,
      |      10L,
      |      (selector match
      |        case 0 => first
      |        case _ => second
      |      ),
      |      2L
      |    )
      |    RiftRegion.peekWindowRank(stream, longIndexed).value
      |  }
      |""".stripMargin)

  @Test def streamWindowTableRankInfersMatchLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(selector: Int): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val table = RiftRegion.streamWindowTableRank[Row](10, 1, 4)
      |    val tableBucket =
      |      RiftRegion.streamWindowBucketFor(stream, table, 7L)
      |    val left = new Row(3)
      |    val right = new Row(4)
      |    RiftRegion.putTableRankInBucket(
      |      stream,
      |      table,
      |      tableBucket,
      |      20L,
      |      (selector match
      |        case 0 => left
      |        case _ => right
      |      ),
      |      3L
      |    )
      |    RiftRegion.peekTableRank(stream, table).value
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

  @Test def streamWindowIndexedRankBranchLocalNewCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Row(val metadata: Metadata)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val rank = RiftRegion.streamWindowIndexedRank[Row](10, 8, 1)
      |    val metadata = new Metadata(10)
      |    val first = new Row(metadata)
      |    val second = new Row(metadata)
      |    RiftRegion.putWindowRank(
      |      stream,
      |      rank,
      |      1,
      |      if flag then first else second,
      |      1L
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def streamWindowTableRankMatchLocalNewCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Row(val metadata: Metadata)
      |
      |def bad(selector: Int): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val rank = RiftRegion.streamWindowTableRank[Row](10, 1, 4)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val metadata = new Metadata(10)
      |    val first = new Row(metadata)
      |    val second = new Row(metadata)
      |    RiftRegion.putTableRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      1L,
      |      (selector match
      |        case 0 => first
      |        case _ => second
      |      ),
      |      1L
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
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

  @Test def streamWindowIndexedRankArrayCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val rank =
      |      RiftRegion.streamWindowIndexedRank[Array[Metadata]^{stream}](10, 8, 1)
      |    RiftRegion.putWindowRank(stream, rank, 1, new Array[Metadata](1), 1L)
      |    val values = RiftRegion.peekWindowRank(stream, rank)
      |    val metadata = new Metadata(41)
      |    values(0) = metadata
      |    values(0).value
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def streamWindowLongIndexedRankArrayCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val rank =
      |      RiftRegion.streamWindowLongIndexedRankLexicographic[
      |        Array[Metadata]^{stream}
      |      ](10, 1, 4)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    RiftRegion.putWindowRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      10L,
      |      new Array[Metadata](1),
      |      2L,
      |      3L,
      |      4L,
      |      5L
      |    )
      |    val values = RiftRegion.peekWindowRank(stream, rank)
      |    val metadata = new Metadata(41)
      |    values(0) = metadata
      |    values(0).value
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def streamWindowTableRankArrayCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val rank =
      |      RiftRegion.streamWindowTableRank[Array[Metadata]^{stream}](10, 1, 4)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    RiftRegion.putTableRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      20L,
      |      new Array[Metadata](1),
      |      3L
      |    )
      |    val values = RiftRegion.peekTableRank(stream, rank)
      |    val metadata = new Metadata(41)
      |    values(0) = metadata
      |    values(0).value
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def streamWindowIndexedRankClosureCannotCaptureUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val rank =
      |      RiftRegion.streamWindowIndexedRank[
      |        Function1[Int, Int]^{stream}
      |      ](10, 8, 1)
      |    val metadata = new Metadata(40)
      |    RiftRegion.putWindowRank(
      |      stream,
      |      rank,
      |      1,
      |      (n: Int) => metadata.value + n,
      |      1L
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def streamWindowLongRankSelectedClosureCannotCaptureUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val rank =
      |      RiftRegion.streamWindowLongIndexedRank[
      |        Function1[Int, Int]^{stream}
      |      ](10, 1, 4)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val metadata = new Metadata(40)
      |    val first = (n: Int) => metadata.value + n
      |    val second = (n: Int) => metadata.value + n + 1
      |    val selected = if flag then first else second
      |    RiftRegion.putWindowRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      1L,
      |      selected,
      |      1L
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def streamWindowIndexedRankSelectedOptionCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val rank =
      |      RiftRegion.streamWindowIndexedRank[
      |        Option[Metadata]^{stream}
      |      ](10, 8, 1)
      |    val metadata = new Metadata(10)
      |    val first = Option(metadata)
      |    val second = Option(metadata)
      |    val selected = if flag then first else second
      |    RiftRegion.putWindowRank(stream, rank, 1, selected, 1L)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def streamWindowIndexedRankBranchOptionCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val rank =
      |      RiftRegion.streamWindowIndexedRank[
      |        Option[Metadata]^{stream}
      |      ](10, 8, 1)
      |    val metadata = new Metadata(10)
      |    RiftRegion.putWindowRank(
      |      stream,
      |      rank,
      |      1,
      |      if flag then Option(metadata) else Option(metadata),
      |      1L
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def streamWindowLongIndexedRankSelectedOptionCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val rank =
      |      RiftRegion.streamWindowLongIndexedRankLexicographic[
      |        Option[Metadata]^{stream}
      |      ](10, 1, 4)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val metadata = new Metadata(10)
      |    val first = Option(metadata)
      |    val second = Option(metadata)
      |    val selected = if flag then first else second
      |    RiftRegion.putWindowRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      1L,
      |      selected,
      |      1L,
      |      2L,
      |      3L,
      |      4L
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def streamWindowTableRankSelectedTuple2CannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val rank =
      |      RiftRegion.streamWindowTableRank[
      |        Tuple2[Metadata, Metadata]^{stream}
      |      ](10, 1, 4)
      |    val bucket = RiftRegion.streamWindowBucketFor(stream, rank, 7L)
      |    val metadata = new Metadata(10)
      |    val first = Tuple2(metadata, metadata)
      |    val second = Tuple2(metadata, metadata)
      |    val selected = if flag then first else second
      |    RiftRegion.putTableRankInBucket(
      |      stream,
      |      rank,
      |      bucket,
      |      1L,
      |      selected,
      |      1L
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
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
      "cannot flow into capture set"
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
      |    def makeMetadata(): Metadata = new Metadata(41)
      |    val buffer = RiftRegion.regionBuffer[Metadata](1)
      |    val metadata = makeMetadata()
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
      "cannot flow into capture set"
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

  @Test def regionPriorityQueueInfersLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue = RiftRegion.regionPriorityQueue[Row](1)
      |    val low = new Row(10)
      |    val high = new Row(40)
      |    region.push(queue, low, 1L)
      |    RiftRegion.push(region, queue, high, 3L)
      |    region.pop(queue).value + region.pop(queue).value + region.length(queue)
      |  }
      |""".stripMargin)

  @Test def regionPriorityQueuesInferSelectedLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val plain = RiftRegion.regionPriorityQueue[Row](1)
      |    val plainLow = new Row(10)
      |    val plainHigh = new Row(20)
      |    val plainSelected = if flag then plainLow else plainHigh
      |    region.push(plain, plainSelected, 1L)
      |
      |    val indexed = RiftRegion.regionIndexedPriorityQueue[Row](4, 1)
      |    val indexedLow = new Row(3)
      |    val indexedHigh = new Row(4)
      |    val indexedSelected = if flag then indexedLow else indexedHigh
      |    RiftRegion.put(region, indexed, 1, indexedSelected, 2L)
      |
      |    val longIndexed = RiftRegion.regionLongIndexedPriorityQueue[Row](1, 4)
      |    val longLow = new Row(5)
      |    val longHigh = new Row(6)
      |    val longSelected = if flag then longLow else longHigh
      |    region.put(longIndexed, 10L, longSelected, 3L)
      |
      |    region.peek(plain).value +
      |      region.peek(indexed).value +
      |      region.peek(longIndexed).value
      |  }
      |""".stripMargin)

  @Test def regionPriorityQueuesInferSelectedLocalSyntheticFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val plain =
      |      RiftRegion.regionPriorityQueue[Option[Leaf^{region}]^{region}](1)
      |    val someLow = Some(new Leaf(10))
      |    val someHigh = Some(new Leaf(40))
      |    val someSelected = if flag then someLow else someHigh
      |    region.push(plain, someSelected, 1L)
      |
      |    val indexed =
      |      RiftRegion.regionIndexedPriorityQueue[
      |        Option[Leaf^{region}]^{region}
      |      ](4, 1)
      |    val optionLow = Option(new Leaf(1))
      |    val optionHigh = Option(new Leaf(2))
      |    val optionSelected = if flag then optionLow else optionHigh
      |    RiftRegion.put(region, indexed, 1, optionSelected, 2L)
      |
      |    val longIndexed =
      |      RiftRegion.regionLongIndexedPriorityQueue[
      |        Tuple2[Leaf^{region}, Leaf^{region}]^{region}
      |      ](1, 4)
      |    val pairLow = Tuple2(new Leaf(1), new Leaf(1))
      |    val pairHigh = Tuple2(new Leaf(2), new Leaf(2))
      |    val pairSelected = if flag then pairLow else pairHigh
      |    region.put(longIndexed, 10L, pairSelected, 3L)
      |
      |    region.peek(plain).get.value +
      |      region.peek(indexed).get.value +
      |      region.peek(longIndexed)._1.value
      |  }
      |""".stripMargin)

  @Test def regionPriorityQueuesInferBranchMatchSyntheticFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean, selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    val plain =
      |      RiftRegion.regionPriorityQueue[Option[Leaf^{region}]^{region}](1)
      |    region.push(
      |      plain,
      |      if flag then Some(new Leaf(10)) else Some(new Leaf(40)),
      |      1L
      |    )
      |
      |    val indexed =
      |      RiftRegion.regionIndexedPriorityQueue[
      |        Option[Leaf^{region}]^{region}
      |      ](4, 1)
      |    RiftRegion.put(
      |      region,
      |      indexed,
      |      1,
      |      (selector match
      |        case 0 => Option(new Leaf(1))
      |        case _ => Option(new Leaf(2))
      |      ),
      |      2L
      |    )
      |
      |    val longIndexed =
      |      RiftRegion.regionLongIndexedPriorityQueue[
      |        Tuple2[Leaf^{region}, Leaf^{region}]^{region}
      |      ](1, 4)
      |    region.put(
      |      longIndexed,
      |      10L,
      |      if flag then Tuple2(new Leaf(1), new Leaf(1))
      |      else Tuple2(new Leaf(2), new Leaf(2)),
      |      3L
      |    )
      |
      |    region.peek(plain).get.value +
      |      region.peek(indexed).get.value +
      |      region.peek(longIndexed)._1.value
      |  }
      |""".stripMargin)

  @Test def regionPriorityQueuesInferSelectedNestedSyntheticFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class SomeNode(
      |        val option: Option[Leaf^{region}]^{region}
      |    )
      |    final class OptionNode(
      |        val option: Option[Leaf^{region}]^{region}
      |    )
      |    final class PairNode(
      |        val pair: Tuple2[Leaf^{region}, Leaf^{region}]^{region}
      |    )
      |
      |    val plain = RiftRegion.regionPriorityQueue[SomeNode](1)
      |    val someLow = Some(new Leaf(10))
      |    val someHigh = Some(new Leaf(40))
      |    val someSelected = if flag then someLow else someHigh
      |    region.push(plain, new SomeNode(someSelected), 1L)
      |
      |    val indexed = RiftRegion.regionIndexedPriorityQueue[OptionNode](4, 1)
      |    val optionLow = Option(new Leaf(1))
      |    val optionHigh = Option(new Leaf(2))
      |    val optionSelected = if flag then optionLow else optionHigh
      |    RiftRegion.put(region, indexed, 1, new OptionNode(optionSelected), 2L)
      |
      |    val longIndexed =
      |      RiftRegion.regionLongIndexedPriorityQueue[PairNode](1, 4)
      |    val pairLow = Tuple2(new Leaf(1), new Leaf(1))
      |    val pairHigh = Tuple2(new Leaf(2), new Leaf(2))
      |    val pairSelected = if flag then pairLow else pairHigh
      |    region.put(longIndexed, 10L, new PairNode(pairSelected), 3L)
      |
      |    region.peek(plain).option.get.value +
      |      region.peek(indexed).option.get.value +
      |      region.peek(longIndexed).pair._1.value
      |  }
      |""".stripMargin)

  @Test def regionLexicographicPriorityQueuesInferSelectedLocalSyntheticFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val indexed =
      |      RiftRegion.regionIndexedPriorityQueueLexicographic[
      |        Option[Leaf^{region}]^{region}
      |      ](4, 1)
      |    val someLow = Some(new Leaf(10))
      |    val someHigh = Some(new Leaf(40))
      |    val someSelected = if flag then someLow else someHigh
      |    region.put(indexed, 1, someSelected, 1L, 2L, 3L, 4L)
      |
      |    val optionLow = Option(new Leaf(1))
      |    val optionHigh = Option(new Leaf(2))
      |    val optionSelected = if flag then optionLow else optionHigh
      |    RiftRegion.put(region, indexed, 2, optionSelected, 2L, 3L, 4L, 5L)
      |
      |    val longIndexed =
      |      RiftRegion.regionLongIndexedPriorityQueueLexicographic[
      |        Tuple2[Leaf^{region}, Leaf^{region}]^{region}
      |      ](1, 4)
      |    val pairLow = Tuple2(new Leaf(1), new Leaf(1))
      |    val pairHigh = Tuple2(new Leaf(2), new Leaf(2))
      |    val pairSelected = if flag then pairLow else pairHigh
      |    region.put(longIndexed, 10L, pairSelected, 3L, 4L, 5L, 6L)
      |
      |    region.get(indexed, 1).get.value +
      |      region.get(indexed, 2).get.value +
      |      region.get(longIndexed, 10L)._1.value
      |  }
      |""".stripMargin)

  @Test def regionLexicographicPriorityQueuesInferBranchMatchSyntheticFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean, selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    val indexed =
      |      RiftRegion.regionIndexedPriorityQueueLexicographic[
      |        Option[Leaf^{region}]^{region}
      |      ](4, 1)
      |    region.put(
      |      indexed,
      |      1,
      |      if flag then Some(new Leaf(10)) else Some(new Leaf(40)),
      |      1L,
      |      2L,
      |      3L,
      |      4L
      |    )
      |    RiftRegion.put(
      |      region,
      |      indexed,
      |      2,
      |      (selector match
      |        case 0 => Option(new Leaf(1))
      |        case _ => Option(new Leaf(2))
      |      ),
      |      2L,
      |      3L,
      |      4L,
      |      5L
      |    )
      |
      |    val longIndexed =
      |      RiftRegion.regionLongIndexedPriorityQueueLexicographic[
      |        Tuple2[Leaf^{region}, Leaf^{region}]^{region}
      |      ](1, 4)
      |    region.put(
      |      longIndexed,
      |      10L,
      |      if flag then Tuple2(new Leaf(1), new Leaf(1))
      |      else Tuple2(new Leaf(2), new Leaf(2)),
      |      3L,
      |      4L,
      |      5L,
      |      6L
      |    )
      |
      |    region.get(indexed, 1).get.value +
      |      region.get(indexed, 2).get.value +
      |      region.get(longIndexed, 10L)._1.value
      |  }
      |""".stripMargin)

  @Test def regionPriorityQueuesInferInlineBlockNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val plain = RiftRegion.regionPriorityQueue[Row](1)
      |    region.push(plain, { val value = 10; new Row(value) }, 1L)
      |    RiftRegion.push(region, plain, { val value = 20; new Row(value) }, 2L)
      |
      |    val indexed = RiftRegion.regionIndexedPriorityQueue[Row](4, 1)
      |    region.put(indexed, 1, { val value = 3; new Row(value) }, 1L)
      |    RiftRegion.put(region, indexed, 2, { val value = 4; new Row(value) }, 2L)
      |
      |    val longIndexed = RiftRegion.regionLongIndexedPriorityQueue[Row](1, 4)
      |    region.put(longIndexed, 10L, { val value = 0; new Row(value) }, 1L)
      |    RiftRegion.put(
      |      region,
      |      longIndexed,
      |      20L,
      |      { val value = 1; new Row(value) },
      |      2L
      |    )
      |
      |    region.pop(plain).value +
      |      RiftRegion.pop(region, plain).value +
      |      region.peek(indexed).value +
      |      RiftRegion.get(region, indexed, 1).value +
      |      region.peek(longIndexed).value +
      |      RiftRegion.get(region, longIndexed, 10L).value +
      |      region.length(plain) +
      |      region.length(indexed) +
      |      region.length(longIndexed)
      |  }
      |""".stripMargin)

  @Test def inferredPriorityQueueCanPushInlineRegionArray(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionPriorityQueue[Array[Leaf^{region}]^{region}](1)
      |    region.push(queue, new Array[Leaf^{region}](1), 1L)
      |    val leaves = region.peek(queue)
      |    leaves(0) = new Leaf(40)
      |    leaves(0).value + 2
      |  }
      |""".stripMargin)

  @Test def inferredPriorityQueueArrayCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionPriorityQueue[Array[Metadata]^{region}](1)
      |    region.push(queue, new Array[Metadata](1), 1L)
      |    val values = region.peek(queue)
      |    val metadata = new Metadata(41)
      |    values(0) = metadata
      |    values(0).value
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def inferredIndexedPriorityQueuesCanPutInlineRegionArray(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val indexed =
      |      RiftRegion.regionIndexedPriorityQueue[
      |        Array[Leaf^{region}]^{region}
      |      ](4, 1)
      |    region.put(indexed, 1, new Array[Leaf^{region}](1), 1L)
      |    val indexedLeaves = region.get(indexed, 1)
      |    indexedLeaves(0) = new Leaf(10)
      |
      |    val longIndexed =
      |      RiftRegion.regionLongIndexedPriorityQueue[
      |        Array[Leaf^{region}]^{region}
      |      ](1, 4)
      |    RiftRegion.put(region, longIndexed, 10L, new Array[Leaf^{region}](1), 2L)
      |    val longLeaves = RiftRegion.peek(region, longIndexed)
      |    longLeaves(0) = new Leaf(30)
      |
      |    indexedLeaves(0).value + longLeaves(0).value + 2
      |  }
      |""".stripMargin)

  @Test def inferredIndexedPriorityQueueArrayCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionIndexedPriorityQueue[Array[Metadata]^{region}](4, 1)
      |    region.put(queue, 1, new Array[Metadata](1), 1L)
      |    val values = region.get(queue, 1)
      |    val metadata = new Metadata(41)
      |    values(0) = metadata
      |    values(0).value
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def inferredLongIndexedPriorityQueueArrayCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionLongIndexedPriorityQueue[
      |        Array[Metadata]^{region}
      |      ](1, 4)
      |    RiftRegion.put(region, queue, 10L, new Array[Metadata](1), 1L)
      |    val values = RiftRegion.peek(region, queue)
      |    val metadata = new Metadata(41)
      |    values(0) = metadata
      |    values(0).value
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def inferredLexicographicPriorityQueuesCanPutInlineRegionArray()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val indexed =
      |      RiftRegion.regionIndexedPriorityQueueLexicographic[
      |        Array[Leaf^{region}]^{region}
      |      ](4, 1)
      |    region.put(indexed, 1, new Array[Leaf^{region}](1), 1L, 2L, 3L, 4L)
      |    val indexedLeaves = region.get(indexed, 1)
      |    indexedLeaves(0) = new Leaf(10)
      |
      |    val longIndexed =
      |      RiftRegion.regionLongIndexedPriorityQueueLexicographic[
      |        Array[Leaf^{region}]^{region}
      |      ](1, 4)
      |    RiftRegion.put(
      |      region,
      |      longIndexed,
      |      10L,
      |      new Array[Leaf^{region}](1),
      |      2L,
      |      3L,
      |      4L,
      |      5L
      |    )
      |    val longLeaves = RiftRegion.peek(region, longIndexed)
      |    longLeaves(0) = new Leaf(30)
      |
      |    indexedLeaves(0).value + longLeaves(0).value + 2
      |  }
      |""".stripMargin)

  @Test def inferredLexicographicIndexedPriorityQueueArrayCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionIndexedPriorityQueueLexicographic[
      |        Array[Metadata]^{region}
      |      ](4, 1)
      |    region.put(queue, 1, new Array[Metadata](1), 1L, 2L, 3L, 4L)
      |    val values = region.get(queue, 1)
      |    val metadata = new Metadata(41)
      |    values(0) = metadata
      |    values(0).value
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def inferredLexicographicLongIndexedPriorityQueueArrayCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionLongIndexedPriorityQueueLexicographic[
      |        Array[Metadata]^{region}
      |      ](1, 4)
      |    RiftRegion.put(
      |      region,
      |      queue,
      |      10L,
      |      new Array[Metadata](1),
      |      1L,
      |      2L,
      |      3L,
      |      4L
      |    )
      |    val values = RiftRegion.peek(region, queue)
      |    val metadata = new Metadata(41)
      |    values(0) = metadata
      |    values(0).value
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def inferredRegionPriorityQueueNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Row(val metadata: Metadata)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val queue = RiftRegion.regionPriorityQueue[Row](1)
      |    val metadata = new Metadata(10)
      |    val row = new Row(metadata)
      |    region.push(queue, row, 1L)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionPriorityQueueInlineBlockNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Row(val metadata: Metadata)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val queue = RiftRegion.regionPriorityQueue[Row](1)
      |    val metadata = new Metadata(10)
      |    region.push(
      |      queue,
      |      {
      |        val tag = metadata
      |        new Row(tag)
      |      },
      |      1L
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedRegionPriorityQueueNewCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Row(val metadata: Metadata)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val queue = RiftRegion.regionPriorityQueue[Row](1)
      |    val metadata = new Metadata(10)
      |    val first = new Row(metadata)
      |    val second = new Row(metadata)
      |    val selected = if flag then first else second
      |    region.push(queue, selected, 1L)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedRegionPriorityQueueSomeCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionPriorityQueue[Option[Metadata]^{region}](1)
      |    val metadata = new Metadata(10)
      |    val first = Some(metadata)
      |    val second = Some(metadata)
      |    val selected = if flag then first else second
      |    region.push(queue, selected, 1L)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredBranchRegionPriorityQueueSomeCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionPriorityQueue[Option[Metadata]^{region}](1)
      |    val metadata = new Metadata(10)
      |    region.push(
      |      queue,
      |      if flag then Some(metadata) else Some(metadata),
      |      1L
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedRegionIndexedPriorityQueueOptionCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionIndexedPriorityQueue[Option[Metadata]^{region}](4)
      |    val metadata = new Metadata(10)
      |    val first = Option(metadata)
      |    val second = Option(metadata)
      |    val selected = if flag then first else second
      |    region.put(queue, 1, selected, 1L)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedRegionLongIndexedPriorityQueueTuple2CannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionLongIndexedPriorityQueue[
      |        Tuple2[Metadata, Metadata]^{region}
      |      ](1, 4)
      |    val metadata = new Metadata(10)
      |    val first = Tuple2(metadata, metadata)
      |    val second = Tuple2(metadata, metadata)
      |    val selected = if flag then first else second
      |    region.put(queue, 1L, selected, 1L)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedNestedRegionPriorityQueueTuple2CannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Row(
      |        val pair: Tuple2[Metadata, Metadata]^{region}
      |    )
      |    val queue = RiftRegion.regionPriorityQueue[Row](1)
      |    val metadata = new Metadata(10)
      |    val first = Tuple2(metadata, metadata)
      |    val second = Tuple2(metadata, metadata)
      |    val selected = if flag then first else second
      |    region.push(queue, new Row(selected), 1L)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedRegionIndexedPriorityQueueLexicographicOptionCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionIndexedPriorityQueueLexicographic[
      |        Option[Metadata]^{region}
      |      ](4, 1)
      |    val metadata = new Metadata(10)
      |    val first = Option(metadata)
      |    val second = Option(metadata)
      |    val selected = if flag then first else second
      |    region.put(queue, 1, selected, 1L, 2L, 3L, 4L)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedRegionLongIndexedPriorityQueueLexicographicTuple2CannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionLongIndexedPriorityQueueLexicographic[
      |        Tuple2[Metadata, Metadata]^{region}
      |      ](1, 4)
      |    val metadata = new Metadata(10)
      |    val first = Tuple2(metadata, metadata)
      |    val second = Tuple2(metadata, metadata)
      |    val selected = if flag then first else second
      |    RiftRegion.put(region, queue, 1L, selected, 1L, 2L, 3L, 4L)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

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
      |    def makeHeapRow(): Row = new Row(10)
      |    val row = makeHeapRow()
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
      "cannot flow into capture set"
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

  @Test def regionIndexedPriorityQueueInfersLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Row(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue = RiftRegion.regionIndexedPriorityQueue[Row](4, 1)
      |    val low = new Row(10)
      |    val high = new Row(40)
      |    region.put(queue, 1, low, 1L)
      |    RiftRegion.put(region, queue, 2, high, 3L)
      |    region.peek(queue).value + region.get(queue, 1).value + region.length(queue)
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
      |    def makeHeapRow(): Row = new Row(10)
      |    val row = makeHeapRow()
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
      "cannot flow into capture set"
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

  @Test def regionLongIndexedPriorityQueueInfersLocalNewPlacement(): Unit =
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
      |    val low = new Row(10)
      |    val high = new Row(40)
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
      |    region.peek(queue).value + region.get(queue, 0x100000001L).value +
      |      region.length(queue)
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
      |    def makeHeapRow(): Row = new Row(10)
      |    val row = makeHeapRow()
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
      "cannot flow into capture set"
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
      "cannot flow into capture set"
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

  @Test def streamPageTokenAppendWindowInfersChildRegionLocalNew(): Unit =
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
      |      RiftRegion.pageTokenAppendRegionFor(stream, window, 7L, 0L)(consume)
      |    val event: Event^{region} = new Event(41)
      |    val widened: Event^{stream} = event
      |    RiftRegion.appendPageToken(stream, window, widened)
      |    RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
      |      consume
      |    )
      |    total + 1
      |  }
      |""".stripMargin)

  @Test def streamPageTokenAppendWindowInfersOpenChildRegionLocalNew(): Unit =
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
      |    val event: Event^{region} = new Event(41)
      |    val widened: Event^{stream} = event
      |    RiftRegion.appendPageToken(stream, window, widened)
      |    RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
      |      consume
      |    )
      |    total + 1
      |  }
      |""".stripMargin)

  @Test def streamPageTokenAppendWindowInfersRiftOpenHandleLocalNew(): Unit =
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
      |      RiftRegion.pageTokenAppendRiftOpenHandleFor(stream, window, 7L, 0L)(
      |        consume
      |      )
      |    val event: Event^{region} = new Event(41)
      |    val widened: Event^{stream} = event
      |    RiftRegion.appendPageToken(stream, window, widened)
      |    RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
      |      consume
      |    )
      |    total + 1
      |  }
      |""".stripMargin)

  @Test def streamPageTokenRiftOpenHandleInferenceRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val window = RiftRegion.streamPageTokenAppendWindow[RiftRegion.StreamAppendNode](10)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamAppendCursor[RiftRegion.StreamAppendNode]^{stream}
      |    ): Unit = ()
      |    val metadata = new Metadata(7)
      |    val region =
      |      RiftRegion.pageTokenAppendRiftOpenHandleFor(stream, window, 7L, 0L)(
      |        consume
      |      )
      |    final class Event(val metadata: Metadata^{region})
      |        extends RiftRegion.StreamAppendNode
      |    val event: Event^{region} = new Event(metadata)
      |    event.metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def streamPageTokenOpenChildRegionInferenceRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val window = RiftRegion.streamPageTokenAppendWindow[RiftRegion.StreamAppendNode](10)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamAppendCursor[RiftRegion.StreamAppendNode]^{stream}
      |    ): Unit = ()
      |    val metadata = new Metadata(7)
      |    val region =
      |      RiftRegion.pageTokenAppendOpenRegionFor(stream, window, 7L, 0L)(consume)
      |    final class Event(val metadata: Metadata^{region})
      |        extends RiftRegion.StreamAppendNode
      |    val event: Event^{region} = new Event(metadata)
      |    event.metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def streamPageTokenChildRegionInferenceRejectsUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val window = RiftRegion.streamPageTokenAppendWindow[RiftRegion.StreamAppendNode](10)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamAppendCursor[RiftRegion.StreamAppendNode]^{stream}
      |    ): Unit = ()
      |    val metadata = new Metadata(7)
      |    val region =
      |      RiftRegion.pageTokenAppendRegionFor(stream, window, 7L, 0L)(consume)
      |    final class Event(val metadata: Metadata^{region})
      |        extends RiftRegion.StreamAppendNode
      |    val event: Event^{region} = new Event(metadata)
      |    event.metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

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

  @Test def pageTokenMapFilterInfersChildRegionLocalNew(): Unit =
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
      |    val event: Event^{region} = new Event(41)
      |    val widened: Event^{stream} = event
      |    RiftRegion.emitPageTokenMapFilter(stream, operator, widened)
      |    RiftRegion.closeAllPageTokenMapFilterBucketsWithCursor(stream, operator)(
      |      consume
      |    )
      |    total + 1
      |  }
      |""".stripMargin)

  @Test def pageTokenMapFilterInfersOpenChildRegionLocalNew(): Unit =
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
      |      RiftRegion.pageTokenMapFilterOpenRegionFor(
      |        stream,
      |        operator,
      |        7L,
      |        0L
      |      )(consume)
      |    val event: Event^{region} = new Event(41)
      |    val widened: Event^{stream} = event
      |    RiftRegion.emitPageTokenMapFilter(stream, operator, widened)
      |    RiftRegion.closeAllPageTokenMapFilterBucketsWithCursor(stream, operator)(
      |      consume
      |    )
      |    total + 1
      |  }
      |""".stripMargin)

  @Test def pageTokenMapFilterChildRegionInferenceRejectsUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val operator =
      |      RiftRegion.pageTokenMapFilter[RiftRegion.StreamAppendNode](10)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamAppendCursor[RiftRegion.StreamAppendNode]^{stream}
      |    ): Unit = ()
      |    val metadata = new Metadata(7)
      |    val region =
      |      RiftRegion.pageTokenMapFilterRegionFor(stream, operator, 7L, 0L)(
      |        consume
      |      )
      |    final class Event(val metadata: Metadata^{region})
      |        extends RiftRegion.StreamAppendNode
      |    val event: Event^{region} = new Event(metadata)
      |    event.metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def pageTokenMapFilterOpenChildRegionInferenceRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val operator =
      |      RiftRegion.pageTokenMapFilter[RiftRegion.StreamAppendNode](10)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamAppendCursor[RiftRegion.StreamAppendNode]^{stream}
      |    ): Unit = ()
      |    val metadata = new Metadata(7)
      |    val region =
      |      RiftRegion.pageTokenMapFilterOpenRegionFor(
      |        stream,
      |        operator,
      |        7L,
      |        0L
      |      )(consume)
      |    final class Event(val metadata: Metadata^{region})
      |        extends RiftRegion.StreamAppendNode
      |    val event: Event^{region} = new Event(metadata)
      |    event.metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

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

  @Test def pageTokenCountByKeyInfersChildRegionLocalNew(): Unit =
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
      |    val event: Event^{region} = new Event(2, 40)
      |    val widened: Event^{stream} = event
      |    RiftRegion.appendPageTokenCountByKey(
      |      stream,
      |      operator,
      |      widened,
      |      widened.key,
      |      widened.value.toLong
      |    )
      |    RiftRegion.closeAllPageTokenCountByKeyBuckets(stream, operator)(
      |      consume
      |    )
      |    total
      |  }
      |""".stripMargin)

  @Test def pageTokenCountByKeyInfersOpenChildRegionLocalNew(): Unit =
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
      |      RiftRegion.pageTokenCountByKeyOpenRegionFor(
      |        stream,
      |        operator,
      |        7L,
      |        0L
      |      )(consume)
      |    val event: Event^{region} = new Event(2, 40)
      |    val widened: Event^{stream} = event
      |    RiftRegion.appendPageTokenCountByKey(
      |      stream,
      |      operator,
      |      widened,
      |      widened.key,
      |      widened.value.toLong
      |    )
      |    RiftRegion.closeAllPageTokenCountByKeyBuckets(stream, operator)(
      |      consume
      |    )
      |    total
      |  }
      |""".stripMargin)

  @Test def pageTokenCountByKeyChildRegionInferenceRejectsUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val operator =
      |      RiftRegion.pageTokenCountByKey[RiftRegion.StreamAppendNode](10, 8, 2)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        key: Int,
      |        count: Int,
      |        valueSum: Long
      |    ): Unit = ()
      |    val metadata = new Metadata(7)
      |    val region =
      |      RiftRegion.pageTokenCountByKeyRegionFor(
      |        stream,
      |        operator,
      |        7L,
      |        0L
      |      )(consume)
      |    final class Event(
      |        val key: Int,
      |        val value: Int,
      |        val metadata: Metadata^{region}
      |    ) extends RiftRegion.StreamAppendNode
      |    val event: Event^{region} = new Event(1, 2, metadata)
      |    event.metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def pageTokenCountByKeyOpenChildRegionInferenceRejectsUnrootedMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val operator =
      |      RiftRegion.pageTokenCountByKey[RiftRegion.StreamAppendNode](10, 8, 2)
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
      |    final class Event(
      |        val key: Int,
      |        val value: Int,
      |        val metadata: Metadata^{region}
      |    ) extends RiftRegion.StreamAppendNode
      |    val event: Event^{region} = new Event(1, 2, metadata)
      |    event.metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

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

  @Test def epochBufferRegionForInfersChildRegionLocalNewPlacement(): Unit =
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
      |    val event: Event^{region} = new Event(41)
      |    val widened: Event^{stream} = event
      |    RiftRegion.appendEpochBuffer(stream, buffer, widened)
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

  @Test def epochBufferRegionFromMutableOwnerSlotDoesNotInferLocalNewPlacement()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val buffer = RiftRegion.epochBuffer[Event]()
      |    var currentRegion: RiftRegion.StreamingRegion^{stream} = null
      |    currentRegion = RiftRegion.epochBufferRegionFor(stream, buffer)
      |    val region = currentRegion
      |    val event: Event^{region} = new Event(41)
      |    val widened: Event^{stream} = event
      |    RiftRegion.appendEpochBuffer(stream, buffer, widened)
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

  @Test def transactionRegionInfersChildRegionLocalNew(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Event(val value: Int) extends RiftRegion.StreamAppendNode
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val tx = RiftRegion.transactionRegion(1)
      |    val input = RiftRegion.transactionList[Event](stream, tx, 0)
      |    val region = RiftRegion.transactionRegionFor(stream, tx)
      |    val event: Event^{region} = new Event(41)
      |    val widened: Event^{stream} = event
      |    RiftRegion.appendTransactionList(stream, input, widened)
      |
      |    var total = 0
      |    RiftRegion.drainTransactionListWithCursor(stream, input) { cursor =>
      |      while cursor.hasNext do
      |        total += cursor.next().value
      |    }
      |    RiftRegion.closeTransactionRegion(stream, tx)
      |    total + 1
      |  }
      |""".stripMargin)

  @Test def transactionRegionInferenceRejectsUnrootedMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val tx = RiftRegion.transactionRegion(1)
      |    RiftRegion.transactionList[RiftRegion.StreamAppendNode](stream, tx, 0)
      |    val metadata = new Metadata(7)
      |    val region = RiftRegion.transactionRegionFor(stream, tx)
      |    final class Event(val metadata: Metadata^{region})
      |        extends RiftRegion.StreamAppendNode
      |    val event: Event^{region} = new Event(metadata)
      |    event.metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

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

  @Test def streamChunkAppendWindowInfersChildRegionLocalNew(): Unit =
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
      |    val event: Event^{region} = new Event(41)
      |    val widened: Event^{stream} = event
      |    RiftRegion.appendChunkToken(stream, window, widened)
      |    RiftRegion.closeAllChunkAppendBucketsWithCursor(stream, window)(
      |      consume
      |    )
      |    total + 1
      |  }
      |""".stripMargin)

  @Test def streamChunkAppendWindowInferenceRejectsUnrootedMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    val window =
      |      RiftRegion.streamChunkAppendWindow[RiftRegion.StreamAppendNode](10, 4)
      |    def consume(
      |        bucket: RiftRegion.StreamBucket^{stream},
      |        cursor: RiftRegion.StreamChunkCursor[RiftRegion.StreamAppendNode]^{stream}
      |    ): Unit = ()
      |    val metadata = new Metadata(7)
      |    val region =
      |      RiftRegion.chunkAppendRegionFor(stream, window, 7L, 0L)(consume)
      |    final class Event(val metadata: Metadata^{region})
      |        extends RiftRegion.StreamAppendNode
      |    val event: Event^{region} = new Event(metadata)
      |    event.metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

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

  @Test def epochFoldRegionForInfersChildRegionLocalNewPlacement(): Unit =
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
      |    val event: Event^{region} = new Event(3, 41L, 5)
      |    val widened: Event^{stream} = event
      |    val total =
      |      RiftRegion.putEpochFold(stream, fold, widened.key, widened.delta, widened)
      |    var closed = 0L
      |    RiftRegion.closeEpochFoldCurrentBucketAndClear(stream, fold) {
      |      (_, cursor) =>
      |        while cursor.hasNext do
      |          closed += cursor.next().value
      |    }
      |    total + closed + RiftRegion.epochFoldKeyCount(stream, fold)
      |  }
      |""".stripMargin)

  @Test def epochFoldRegionForCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Event(val metadata: Metadata, val key: Int, val delta: Long)
      |    extends RiftRegion.StreamAppendNode
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    val fold = RiftRegion.epochFold[Event](10, 16)
      |    val region = RiftRegion.epochFoldRegionFor(stream, fold, 7L)
      |    val metadata = new Metadata(41)
      |    val event: Event^{region} = new Event(metadata, 3, 41L)
      |    val widened: Event^{stream} = event
      |    RiftRegion.putEpochFold(stream, fold, widened.key, widened.delta, widened)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

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

  @Test def scopedRegionInfersAnnotatedLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int, val next: Node^{region})
      |    var head: Node^{region} = null
      |    val first: Node^{region} = new Node(1, head)
      |    head = first
      |    head = new Node(2, head)
      |    head.value + head.next.value
      |  }
      |""".stripMargin)

  @Test def regionListInfersSelectedLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int) extends RiftRegion.RegionListNode
      |    val list = RiftRegion.regionList[Node]()
      |    val first = new Node(40)
      |    val second = new Node(42)
      |    val selected = if flag then first else second
      |    RiftRegion.prependRegionList(region, list, selected)
      |    RiftRegion.regionListHead(region, list).value
      |  }
      |""".stripMargin)

  @Test def openHandleInfersAnnotatedLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.epochOpenHandle {
      |    region ?=>
      |      final class Node(val value: Int, val next: Node^{region})
      |      var head: Node^{region} = null
      |      val first: Node^{region} = new Node(1, head)
      |      head = first
      |      head = new Node(2, head)
      |      head.value + head.next.value
      |  }
      |""".stripMargin)

  @Test def inferredOpenHandleNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.epochOpenHandle {
      |    region ?=>
      |      final class Entry(val metadata: Metadata^{region})
      |      val metadata = new Metadata(7)
      |      val entry: Entry^{region} = new Entry(metadata)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def resetOpenHandleInlineInfersAnnotatedLocalNewPlacement(): Unit =
    assertCompiles("""
      |package scala.scalanative.memory
      |
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.runtime.RiftAllocator
      |
      |def ok(): Int =
      |  RiftRegion.streamingOpenHandle {
      |    RiftRegion.resetOpenHandleInline {
      |      region ?=>
      |        final class Node(val value: Int, val next: Node^{region})
      |        var head: Node^{region} = null
      |        val first: Node^{region} = new Node(1, head)
      |        head = first
      |        head = new Node(2, head)
      |        head.value + head.next.value
      |    }
      |  }
      |""".stripMargin)

  @Test def resetOpenHandleInlineInfersRegionArrayPlacement(): Unit =
    assertCompiles("""
      |package scala.scalanative.memory
      |
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.runtime.RiftAllocator
      |
      |def ok(): Int =
      |  RiftRegion.streamingOpenHandle {
      |    RiftRegion.resetOpenHandleInline {
      |      region ?=>
      |        final class Entry(val key: Int) {
      |          var next: Entry^{region} = null
      |        }
      |        val entries: Array[Entry^{region}]^{region} =
      |          RiftAllocator.allocateOpenHandle(
      |            region,
      |            new Array[Entry^{region}](8)
      |          )
      |        val first: Entry^{region} = new Entry(1)
      |        val second: Entry^{region} = new Entry(2)
      |        second.next = first
      |        entries(0) = second
      |        entries(0).key + entries(0).next.key
      |    }
      |  }
      |""".stripMargin)

  @Test def resetOpenHandleInlineInfersDirectRegionArrayPlacement(): Unit =
    assertCompiles("""
      |package scala.scalanative.memory
      |
      |import scala.language.experimental.captureChecking
      |
      |def ok(): Int =
      |  RiftRegion.streamingOpenHandle {
      |    RiftRegion.resetOpenHandleInline {
      |      region ?=>
      |        final class Entry(val key: Int) {
      |          var next: Entry^{region} = null
      |        }
      |        val entries: Array[Entry^{region}]^{region} =
      |          new Array[Entry^{region}](8)
      |        val counts: Array[Int]^{region} =
      |          new Array[Int](8)
      |        val first: Entry^{region} = new Entry(1)
      |        val second: Entry^{region} = new Entry(2)
      |        second.next = first
      |        entries(0) = second
      |        counts(0) = 39
      |        entries(0).key + entries(0).next.key + counts(0)
      |    }
      |  }
      |""".stripMargin)

  @Test def resetOpenHandleInlineInferredArrayRejectsUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |package scala.scalanative.memory
      |
      |import scala.language.experimental.captureChecking
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.streamingOpenHandle {
      |    RiftRegion.resetOpenHandleInline {
      |      region ?=>
      |        val items: Array[Metadata]^{region} =
      |          new Array[Metadata](1)
      |        val metadata = new Metadata(1)
      |        items(0) = metadata
      |    }
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def inferredScopedBranchNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val key: Int)
      |    val entry: Entry^{region} =
      |      if flag then new Entry(40)
      |      else new Entry(2)
      |    entry.key
      |  }
      |""".stripMargin)

  @Test def inferredScopedBranchNewRejectsUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata^{region})
      |    val metadata = new Metadata(7)
      |    val entry: Entry^{region} =
      |      if flag then new Entry(metadata)
      |      else new Entry(metadata)
      |    entry.metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredScopedMatchNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val key: Int)
      |    val entry: Entry^{region} =
      |      selector match
      |        case 0 => new Entry(40)
      |        case _ => new Entry(2)
      |    entry.key
      |  }
      |""".stripMargin)

  @Test def inferredScopedMatchNewRejectsUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata^{region})
      |    val metadata = new Metadata(7)
      |    val entry: Entry^{region} =
      |      selector match
      |        case 0 => new Entry(metadata)
      |        case _ => new Entry(metadata)
      |    entry.metadata.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def resetOpenHandleInlineInfersRegionArrayPlacementFromInlineWrapper(): Unit =
    assertDoesNotCompileWith("""
      |package scala.scalanative.memory
      |
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.runtime.RiftAllocator
      |
      |private final case class GroupOutcome(checksum: Long, count: Int)
      |
      |private inline def run(inline inferredAllocations: Boolean): GroupOutcome =
      |  RiftRegion.streamingOpenHandle {
      |    RiftRegion.resetOpenHandleInline {
      |      region ?=>
      |        final class Entry(val key: Int) {
      |          var next: Entry^{region} = null
      |        }
      |        val entries: Array[Entry^{region}]^{region} =
      |          RiftAllocator.allocateOpenHandle(
      |            region,
      |            new Array[Entry^{region}](8)
      |          )
      |        val first: Entry^{region} =
      |          inline if (inferredAllocations) then new Entry(1)
      |          else RiftAllocator.allocateOpenHandle(region, new Entry(1))
      |        val second: Entry^{region} =
      |          inline if (inferredAllocations) then new Entry(2)
      |          else RiftAllocator.allocateOpenHandle(region, new Entry(2))
      |        second.next = first
      |        entries(0) = second
      |        GroupOutcome(entries(0).key.toLong + entries(0).next.key.toLong, 1)
      |    }
      |  }
      |
      |def ok(): Long = run(inferredAllocations = true).checksum
      |""".stripMargin,
      "cannot be tracked since its capture set is empty"
    )

  @Test def resetOpenHandleInlineInfersRegionArrayPlacementFromNonInlineWrapper(): Unit =
    assertCompiles("""
      |package scala.scalanative.memory
      |
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.runtime.RiftAllocator
      |
      |private final case class GroupOutcome(checksum: Long, count: Int)
      |
      |private def run(): GroupOutcome =
      |  RiftRegion.streamingOpenHandle {
      |    RiftRegion.resetOpenHandleInline {
      |      region ?=>
      |        final class Entry(val key: Int) {
      |          var next: Entry^{region} = null
      |        }
      |        val entries: Array[Entry^{region}]^{region} =
      |          RiftAllocator.allocateOpenHandle(
      |            region,
      |            new Array[Entry^{region}](8)
      |          )
      |        val first: Entry^{region} = new Entry(1)
      |        val second: Entry^{region} = new Entry(2)
      |        second.next = first
      |        entries(0) = second
      |        GroupOutcome(entries(0).key.toLong + entries(0).next.key.toLong, 1)
      |    }
      |  }
      |
      |def ok(): Long = run().checksum
      |""".stripMargin)

  @Test def resetOpenHandleInlineInfersMixedRuntimeBranchPlacement(): Unit =
    assertCompiles("""
      |package scala.scalanative.memory
      |
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.runtime.RiftAllocator
      |
      |private final case class GroupOutcome(checksum: Long, count: Int)
      |
      |private def run(inferredAllocations: Boolean): GroupOutcome =
      |  RiftRegion.streamingOpenHandle {
      |    RiftRegion.resetOpenHandleInline {
      |      region ?=>
      |        final class Entry(val key: Int)
      |        val entries: Array[Entry^{region}]^{region} =
      |          RiftAllocator.allocateOpenHandle(
      |            region,
      |            new Array[Entry^{region}](8)
      |          )
      |        val first: Entry^{region} =
      |          if (inferredAllocations) new Entry(1)
      |          else RiftAllocator.allocateOpenHandle(region, new Entry(1))
      |        entries(0) = first
      |        GroupOutcome(entries(0).key.toLong, 1)
      |    }
      |  }
      |
      |def ok(): Long = run(inferredAllocations = true).checksum
      |""".stripMargin)

  @Test def inferredScopedNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata^{region})
      |    val metadata = new Metadata(7)
      |    val entry: Entry^{region} = new Entry(metadata)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredEpochNewCannotEscapeDurableHeap(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    RiftRegion.epoch { epoch ?=>
      |      val box: Box^{epoch} = new Box(1)
      |      Holder.retained = box
      |    }
      |  }
      |""".stripMargin,
      "cannot flow into capture set"
    )

  @Test def localNewInfersThroughCapturedValDef(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    val node = new Node(40)
      |    val captured: Node^{region} = node
      |    captured.value + 2
      |  }
      |""".stripMargin)

  @Test def localBlockNewInfersThroughCapturedValDef(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    val node =
      |      val value = 40
      |      new Node(value)
      |    val captured: Node^{region} = node
      |    captured.value + 2
      |  }
      |""".stripMargin)

  @Test def annotatedLocalBlockNewInfersPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    val node: Node^{region} =
      |      val value = 40
      |      new Node(value)
      |    node.value + 2
      |  }
      |""".stripMargin)

  @Test def inferredLocalBlockNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    val entry: Entry^{region} =
      |      val metadata = new Metadata(1)
      |      new Entry(metadata)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localNewInfersThroughCapturedAssignment(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    var captured: Node^{region} = null
      |    val node = new Node(40)
      |    captured = node
      |    captured.value + 2
      |  }
      |""".stripMargin)

  @Test def localBlockNewInfersThroughCapturedAssignment(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    var captured: Node^{region} = null
      |    captured =
      |      val value = 40
      |      new Node(value)
      |    captured.value + 2
      |  }
      |""".stripMargin)

  @Test def inferredLocalBlockAssignmentNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    var captured: Entry^{region} = null
      |    captured =
      |      val metadata = new Metadata(1)
      |      new Entry(metadata)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamInfersCapturedNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      new Node(40)
      |    val node = make(using region)
      |    node.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodArgumentInfersInlineCapturedNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(leaf: Leaf^{region}): Int =
      |      leaf.value
      |    consume(new Leaf(42))
      |  }
      |""".stripMargin)

  @Test def localMethodArgumentInferredNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def consume(entry: Entry^{region}): Int =
      |      entry.metadata.value
      |    val metadata = new Metadata(41)
      |    consume(new Entry(metadata))
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamArgumentInfersInlineCapturedNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(leaf: Leaf^{r}): Int =
      |      leaf.value
      |    consume(using region)(new Leaf(42))
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamArgumentInfersInlineArrayPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        leaves: Array[Leaf^{r}]^{r}
      |    ): Int =
      |      leaves(0) = new Leaf(40)
      |      leaves(0).value
      |    consume(using region)(new Array[Leaf^{region}](1)) + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamArgumentInferredNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(entry: Entry^{r}): Int =
      |      entry.metadata.value
      |    val metadata = new Metadata(41)
      |    consume(using region)(new Entry(metadata))
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamArgumentInferredArrayCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        values: Array[Metadata]^{r},
      |        metadata: Metadata
      |    ): Int =
      |      values(0) = metadata
      |      values(0).value
      |    val metadata = new Metadata(41)
      |    consume(using region)(new Array[Metadata](1), metadata)
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamArgumentInfersInlineClosurePlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        add: Function1[Int, Int]^{r}
      |    ): Int =
      |      add(2)
      |    consume(using region)((n: Int) => n + 40)
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamArgumentInferredClosureCannotCaptureUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        add: Function1[Int, Int]^{r}
      |    ): Int =
      |      add(2)
      |    val metadata = new Metadata(40)
      |    consume(using region)((n: Int) => metadata.value + n)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamArgumentInfersInlineSomeFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        option: Option[Leaf^{r}]^{r}
      |    ): Int =
      |      option.get.value
      |    consume(using region)(Some(new Leaf(42)))
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamArgumentInferredSomeFactoryCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        option: Option[Metadata]^{r}
      |    ): Int =
      |      option.get.value
      |    val metadata = new Metadata(41)
      |    consume(using region)(Some(metadata))
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamArgumentInfersInlineOptionApplyFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        option: Option[Leaf^{r}]^{r}
      |    ): Int =
      |      option.get.value
      |    consume(using region)(Option(new Leaf(42)))
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamArgumentInferredOptionApplyCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        option: Option[Metadata]^{r}
      |    ): Int =
      |      option.get.value
      |    val metadata = new Metadata(41)
      |    consume(using region)(Option(metadata))
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamArgumentInfersInlineTuple2FactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        pair: Tuple2[Leaf^{r}, Leaf^{r}]^{r}
      |    ): Int =
      |      pair._1.value + pair._2.value
      |    consume(using region)(Tuple2(new Leaf(40), new Leaf(2)))
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamArgumentInfersBranchMatchSyntheticFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean, selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consumeOption(using r: RiftRegion.ScopedRegion^)(
      |        option: Option[Leaf^{r}]^{r}
      |    ): Int =
      |      option.get.value
      |    def consumePair(using r: RiftRegion.ScopedRegion^)(
      |        pair: Tuple2[Leaf^{r}, Leaf^{r}]^{r}
      |    ): Int =
      |      pair._1.value + pair._2.value
      |
      |    consumeOption(using region)(
      |      if flag then Some(new Leaf(40)) else Some(new Leaf(41))
      |    ) +
      |      consumeOption(using region)(
      |        (selector match
      |          case 0 => Option(new Leaf(1))
      |          case _ => Option(new Leaf(2))
      |        )
      |      ) +
      |      consumePair(using region)(
      |        if flag then Tuple2(new Leaf(10), new Leaf(11))
      |        else Tuple2(new Leaf(12), new Leaf(13))
      |      )
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamArgumentBranchSyntheticFactoryCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        option: Option[Metadata]^{r}
      |    ): Int =
      |      option.get.value
      |    val metadata = new Metadata(41)
      |    consume(using region)(
      |      if flag then Some(metadata) else Some(metadata)
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamArgumentPrimitiveTuple2RequiresBoxingSupport()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        pair: Tuple2[Int, Leaf^{r}]^{r}
      |    ): Int =
      |      pair._1 + pair._2.value
      |    consume(using region)(Tuple2(40, new Leaf(2)))
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamArgumentInfersInlineTuple3FactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        triple: Tuple3[Leaf^{r}, Leaf^{r}, Leaf^{r}]^{r}
      |    ): Int =
      |      triple._1.value + triple._2.value + triple._3.value
      |    consume(using region)(Tuple3(new Leaf(20), new Leaf(20), new Leaf(2)))
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamArgumentInfersInlineTupleLiteralPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        pair: Tuple2[Leaf^{r}, Leaf^{r}]^{r}
      |    ): Int =
      |      pair._1.value + pair._2.value
      |    consume(using region)((new Leaf(40), new Leaf(2)))
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamArgumentInferredTuple2CannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        pair: Tuple2[Metadata, Metadata]^{r}
      |    ): Int =
      |      pair._1.value + pair._2.value
      |    val metadata = new Metadata(41)
      |    consume(using region)(Tuple2(metadata, metadata))
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamArgumentInferredPrimitiveTuple2CannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        pair: Tuple2[Int, Metadata]^{r}
      |    ): Int =
      |      pair._1 + pair._2.value
      |    val metadata = new Metadata(41)
      |    consume(using region)(Tuple2(1, metadata))
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamArgumentInferredTuple3CannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        triple: Tuple3[Metadata, Metadata, Metadata]^{r}
      |    ): Int =
      |      triple._1.value + triple._2.value + triple._3.value
      |    val metadata = new Metadata(41)
      |    consume(using region)(Tuple3(metadata, metadata, metadata))
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamArgumentInfersInlineGenericCellPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        cell: Cell[Leaf^{r}]^{r}
      |    ): Int =
      |      cell.value.value
      |    consume(using region)(new Cell(new Leaf(42)))
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamArgumentInferredGenericCellCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        cell: Cell[Metadata]^{r}
      |    ): Int =
      |      cell.value.value
      |    val metadata = new Metadata(41)
      |    consume(using region)(new Cell[Metadata](metadata))
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamArgumentInferredGenericCellCannotEscapeAsAnyRef()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |final class Cell[A](val value: A)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        cell: Cell[Leaf^{r}]^{r}
      |    ): Unit =
      |      Holder.retained = cell
      |    consume(using region)(new Cell(new Leaf(42)))
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def localPolymorphicMethodRegionParamArgumentInfersGenericCellPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume[A](using r: RiftRegion.ScopedRegion^)(
      |        cell: Cell[A^{r}]^{r}
      |    ): A^{r} =
      |      cell.value
      |    val leaf = consume[Leaf](using region)(new Cell(new Leaf(42)))
      |    leaf.value
      |  }
      |""".stripMargin)

  @Test def localPolymorphicMethodRegionParamArgumentCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume[A](using r: RiftRegion.ScopedRegion^)(
      |        cell: Cell[A^{r}]^{r}
      |    ): A^{r} =
      |      cell.value
      |    val metadata = new Metadata(41)
      |    consume[Metadata](using region)(new Cell[Metadata](metadata)).value
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def localPolymorphicMethodRegionParamArgumentCannotEscapeAsAnyRef()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |final class Cell[A](val value: A)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def consume[A](using r: RiftRegion.ScopedRegion^)(
      |        cell: Cell[A^{r}]^{r}
      |    ): Unit =
      |      Holder.retained = cell
      |    consume[Leaf](using region)(new Cell(new Leaf(42)))
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def localPolymorphicMethodRegionParamInfersGenericCellFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def ok(): Int =
      |  def make[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Cell[A^{r}]^{r} =
      |    new Cell[A^{r}](value)
      |
      |  RiftRegion.scoped { region ?=>
      |    val cell = make[Leaf](using region)(new Leaf(40))
      |    cell.value.value + 2
      |  }
      |""".stripMargin)

  @Test def localPolymorphicMethodRegionParamGenericCellFactoryCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def heapMetadata(): Metadata = new Metadata(41)
      |
      |def bad(): Int =
      |  def make[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Cell[A^{r}]^{r} =
      |    new Cell[A^{r}](value)
      |
      |  RiftRegion.scoped { region ?=>
      |    make[Metadata](using region)(heapMetadata()).value.value
      |  }
      |""".stripMargin,
      "Rift checked region method argument cannot pass an unrooted heap object"
    )

  @Test def localPolymorphicMethodRegionParamGenericCellFactoryCannotEscapeAsAnyRef()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |final class Cell[A](val value: A)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  def make[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Cell[A^{r}]^{r} =
      |    new Cell[A^{r}](value)
      |
      |  RiftRegion.scoped { region ?=>
      |    val cell = make[Leaf](using region)(new Leaf(42))
      |    Holder.retained = cell
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def localPolymorphicMethodRegionParamInfersForwardedGenericCellFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def ok(): Int =
      |  def make[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Cell[A^{r}]^{r} =
      |    new Cell[A^{r}](value)
      |
      |  def wrap[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Cell[A^{r}]^{r} =
      |    make[A](using r)(value)
      |
      |  RiftRegion.scoped { region ?=>
      |    val cell = wrap[Leaf](using region)(new Leaf(40))
      |    cell.value.value + 2
      |  }
      |""".stripMargin)

  @Test def localPolymorphicMethodRegionParamForwardedGenericCellCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def heapMetadata(): Metadata = new Metadata(41)
      |
      |def bad(): Int =
      |  def make[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Cell[A^{r}]^{r} =
      |    new Cell[A^{r}](value)
      |
      |  def wrap[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Cell[A^{r}]^{r} =
      |    make[A](using r)(value)
      |
      |  RiftRegion.scoped { region ?=>
      |    wrap[Metadata](using region)(heapMetadata()).value.value
      |  }
      |""".stripMargin,
      "Rift checked region method argument cannot pass an unrooted heap object"
    )

  @Test def localPolymorphicMethodRegionParamForwardedGenericCellCannotEscapeAsAnyRef()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |final class Cell[A](val value: A)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  def make[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Cell[A^{r}]^{r} =
      |    new Cell[A^{r}](value)
      |
      |  def wrap[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Cell[A^{r}]^{r} =
      |    make[A](using r)(value)
      |
      |  RiftRegion.scoped { region ?=>
      |    val cell = wrap[Leaf](using region)(new Leaf(42))
      |    Holder.retained = cell
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def localPolymorphicMethodRegionParamInfersOptionFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  def make[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Option[A^{r}]^{r} =
      |    Some(value)
      |
      |  RiftRegion.scoped { region ?=>
      |    val option = make[Leaf](using region)(new Leaf(40))
      |    option.get.value + 2
      |  }
      |""".stripMargin)

  @Test def localPolymorphicMethodRegionParamOptionFactoryCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def heapMetadata(): Metadata = new Metadata(41)
      |
      |def bad(): Int =
      |  def make[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Option[A^{r}]^{r} =
      |    Some(value)
      |
      |  RiftRegion.scoped { region ?=>
      |    make[Metadata](using region)(heapMetadata()).get.value
      |  }
      |""".stripMargin,
      "Rift checked region method argument cannot pass an unrooted heap object"
    )

  @Test def localPolymorphicMethodRegionParamOptionFactoryCannotEscapeAsAnyRef()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  def make[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Option[A^{r}]^{r} =
      |    Some(value)
      |
      |  RiftRegion.scoped { region ?=>
      |    val option = make[Leaf](using region)(new Leaf(42))
      |    Holder.retained = option
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def localPolymorphicMethodRegionParamInfersOptionApplyFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  def make[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Option[A^{r}]^{r} =
      |    Option(value)
      |
      |  RiftRegion.scoped { region ?=>
      |    val option = make[Leaf](using region)(new Leaf(40))
      |    option.get.value + 2
      |  }
      |""".stripMargin)

  @Test def localPolymorphicMethodRegionParamOptionApplyCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def heapMetadata(): Metadata = new Metadata(41)
      |
      |def bad(): Int =
      |  def make[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Option[A^{r}]^{r} =
      |    Option(value)
      |
      |  RiftRegion.scoped { region ?=>
      |    make[Metadata](using region)(heapMetadata()).get.value
      |  }
      |""".stripMargin,
      "Rift checked region method argument cannot pass an unrooted heap object"
    )

  @Test def localPolymorphicMethodRegionParamOptionApplyCannotEscapeAsAnyRef()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  def make[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Option[A^{r}]^{r} =
      |    Option(value)
      |
      |  RiftRegion.scoped { region ?=>
      |    val option = make[Leaf](using region)(new Leaf(42))
      |    Holder.retained = option
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def localPolymorphicMethodRegionParamInfersBranchForwardedOptionApplyFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  def make[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Option[A^{r}]^{r} =
      |    Option(value)
      |
      |  def branch[A](flag: Boolean)(using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Option[A^{r}]^{r} =
      |    if flag then make[A](using r)(value) else make[A](using r)(value)
      |
      |  def matched[A](selector: Int)(using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Option[A^{r}]^{r} =
      |    selector match
      |      case 0 => make[A](using r)(value)
      |      case _ => make[A](using r)(value)
      |
      |  RiftRegion.scoped { region ?=>
      |    val left = branch[Leaf](true)(using region)(new Leaf(20))
      |    val right = matched[Leaf](0)(using region)(new Leaf(22))
      |    left.get.value + right.get.value
      |  }
      |""".stripMargin)

  @Test def localPolymorphicMethodRegionParamBranchForwardedOptionApplyCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def heapMetadata(): Metadata = new Metadata(41)
      |
      |def bad(): Int =
      |  def make[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Option[A^{r}]^{r} =
      |    Option(value)
      |
      |  def branch[A](flag: Boolean)(using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Option[A^{r}]^{r} =
      |    if flag then make[A](using r)(value) else make[A](using r)(value)
      |
      |  RiftRegion.scoped { region ?=>
      |    branch[Metadata](true)(using region)(heapMetadata()).get.value
      |  }
      |""".stripMargin,
      "Rift checked region method argument cannot pass an unrooted heap object"
    )

  @Test def localPolymorphicMethodRegionParamMatchForwardedOptionApplyCannotEscapeAsAnyRef()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  def make[A](using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Option[A^{r}]^{r} =
      |    Option(value)
      |
      |  def matched[A](selector: Int)(using r: RiftRegion.ScopedRegion^)(
      |      value: A^{r}
      |  ): Option[A^{r}]^{r} =
      |    selector match
      |      case 0 => make[A](using r)(value)
      |      case _ => make[A](using r)(value)
      |
      |  RiftRegion.scoped { region ?=>
      |    val option = matched[Leaf](0)(using region)(new Leaf(42))
      |    Holder.retained = option
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def localPolymorphicMethodRegionParamInfersTuple2FactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Left(val value: Int)
      |final class Right(val value: Int)
      |
      |def ok(): Int =
      |  def make[A, B](using r: RiftRegion.ScopedRegion^)(
      |      left: A^{r},
      |      right: B^{r}
      |  ): Tuple2[A^{r}, B^{r}]^{r} =
      |    Tuple2(left, right)
      |
      |  RiftRegion.scoped { region ?=>
      |    val pair =
      |      make[Left, Right](using region)(new Left(40), new Right(2))
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin)

  @Test def localPolymorphicMethodRegionParamTuple2FactoryCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Leaf(val value: Int)
      |
      |def heapMetadata(): Metadata = new Metadata(41)
      |
      |def bad(): Int =
      |  def make[A, B](using r: RiftRegion.ScopedRegion^)(
      |      left: A^{r},
      |      right: B^{r}
      |  ): Tuple2[A^{r}, B^{r}]^{r} =
      |    Tuple2(left, right)
      |
      |  RiftRegion.scoped { region ?=>
      |    val pair =
      |      make[Metadata, Leaf](using region)(heapMetadata(), new Leaf(1))
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin,
      "Rift checked region method argument cannot pass an unrooted heap object"
    )

  @Test def localPolymorphicMethodRegionParamTuple2FactoryCannotEscapeAsAnyRef()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Left(val value: Int)
      |final class Right(val value: Int)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  def make[A, B](using r: RiftRegion.ScopedRegion^)(
      |      left: A^{r},
      |      right: B^{r}
      |  ): Tuple2[A^{r}, B^{r}]^{r} =
      |    Tuple2(left, right)
      |
      |  RiftRegion.scoped { region ?=>
      |    val pair =
      |      make[Left, Right](using region)(new Left(40), new Right(2))
      |    Holder.retained = pair
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def localPolymorphicMethodRegionParamInfersBranchForwardedTuple2FactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Left(val value: Int)
      |final class Right(val value: Int)
      |
      |def ok(): Int =
      |  def make[A, B](using r: RiftRegion.ScopedRegion^)(
      |      left: A^{r},
      |      right: B^{r}
      |  ): Tuple2[A^{r}, B^{r}]^{r} =
      |    Tuple2(left, right)
      |
      |  def branch[A, B](flag: Boolean)(using r: RiftRegion.ScopedRegion^)(
      |      left: A^{r},
      |      right: B^{r}
      |  ): Tuple2[A^{r}, B^{r}]^{r} =
      |    if flag then make[A, B](using r)(left, right)
      |    else make[A, B](using r)(left, right)
      |
      |  def matched[A, B](selector: Int)(using r: RiftRegion.ScopedRegion^)(
      |      left: A^{r},
      |      right: B^{r}
      |  ): Tuple2[A^{r}, B^{r}]^{r} =
      |    selector match
      |      case 0 => make[A, B](using r)(left, right)
      |      case _ => make[A, B](using r)(left, right)
      |
      |  RiftRegion.scoped { region ?=>
      |    val first =
      |      branch[Left, Right](true)(using region)(new Left(20), new Right(1))
      |    val second =
      |      matched[Left, Right](0)(using region)(new Left(20), new Right(1))
      |    first._1.value + first._2.value + second._1.value + second._2.value
      |  }
      |""".stripMargin)

  @Test def localPolymorphicMethodRegionParamBranchForwardedTuple2CannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Leaf(val value: Int)
      |
      |def heapMetadata(): Metadata = new Metadata(41)
      |
      |def bad(): Int =
      |  def make[A, B](using r: RiftRegion.ScopedRegion^)(
      |      left: A^{r},
      |      right: B^{r}
      |  ): Tuple2[A^{r}, B^{r}]^{r} =
      |    Tuple2(left, right)
      |
      |  def branch[A, B](flag: Boolean)(using r: RiftRegion.ScopedRegion^)(
      |      left: A^{r},
      |      right: B^{r}
      |  ): Tuple2[A^{r}, B^{r}]^{r} =
      |    if flag then make[A, B](using r)(left, right)
      |    else make[A, B](using r)(left, right)
      |
      |  RiftRegion.scoped { region ?=>
      |    val pair =
      |      branch[Metadata, Leaf](true)(using region)(
      |        heapMetadata(),
      |        new Leaf(1)
      |      )
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin,
      "Rift checked region method argument cannot pass an unrooted heap object"
    )

  @Test def localPolymorphicMethodRegionParamMatchForwardedTuple2CannotEscapeAsAnyRef()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Left(val value: Int)
      |final class Right(val value: Int)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  def make[A, B](using r: RiftRegion.ScopedRegion^)(
      |      left: A^{r},
      |      right: B^{r}
      |  ): Tuple2[A^{r}, B^{r}]^{r} =
      |    Tuple2(left, right)
      |
      |  def matched[A, B](selector: Int)(using r: RiftRegion.ScopedRegion^)(
      |      left: A^{r},
      |      right: B^{r}
      |  ): Tuple2[A^{r}, B^{r}]^{r} =
      |    selector match
      |      case 0 => make[A, B](using r)(left, right)
      |      case _ => make[A, B](using r)(left, right)
      |
      |  RiftRegion.scoped { region ?=>
      |    val pair =
      |      matched[Left, Right](0)(using region)(new Left(40), new Right(2))
      |    Holder.retained = pair
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def localMethodRegionParamInfersSomeFactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^): Some[Node^{r}]^{r} =
      |      Some(new Node(40))
      |    val option = make(using region)
      |    option.value.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersOptionSupertypeSomeFactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^): Option[Node^{r}]^{r} =
      |      Some(new Node(40))
      |    val option = make(using region)
      |    option.get.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersOptionNoneFactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^): Option[Node^{r}]^{r} =
      |      None
      |    val option = make(using region)
      |    if option.isEmpty then 42 else 0
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersOptionSomeOrNoneFactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(value: Int, include: Boolean)(
      |        using r: RiftRegion.ScopedRegion^
      |    ): Option[Node^{r}]^{r} =
      |      if include then Some(new Node(value)) else None
      |    val option = make(40, flag)(using region)
      |    option.map(_.value).getOrElse(40) + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersOptionApplyFactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^): Option[Node^{r}]^{r} =
      |      Option(new Node(40))
      |    val option = make(using region)
      |    option.get.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersReturnedLocalOptionSomeFactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^): Option[Node^{r}]^{r} =
      |      val option: Option[Node^{r}]^{r} =
      |        Some(new Node(40))
      |      option
      |    val option = make(using region)
      |    option.get.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersBranchForwardedOptionSomeFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(value: Int)(using r: RiftRegion.ScopedRegion^)
      |        : Option[Node^{r}]^{r} =
      |      Some(new Node(value))
      |    def wrap(flag: Boolean)(using r: RiftRegion.ScopedRegion^)
      |        : Option[Node^{r}]^{r} =
      |      if flag then make(40)(using r) else make(0)(using r)
      |    val option = wrap(true)(using region)
      |    option.get.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersMatchForwardedOptionSomeFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(value: Int)(using r: RiftRegion.ScopedRegion^)
      |        : Option[Node^{r}]^{r} =
      |      Some(new Node(value))
      |    def wrap(selector: Int)(using r: RiftRegion.ScopedRegion^)
      |        : Option[Node^{r}]^{r} =
      |      selector match
      |        case 0 => make(40)(using r)
      |        case _ => make(0)(using r)
      |    val option = wrap(0)(using region)
      |    option.get.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersTuple2FactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^)
      |        : Tuple2[Node^{r}, Node^{r}]^{r} =
      |      Tuple2(new Node(40), new Node(2))
      |    val pair = make(using region)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamPrimitiveTuple2RequiresBoxingSupport(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^)
      |        : Tuple2[Int, Node^{r}]^{r} =
      |      Tuple2(40, new Node(2))
      |    val pair = make(using region)
      |    pair._1 + pair._2.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamInfersTuple3FactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^)
      |        : Tuple3[Node^{r}, Node^{r}, Node^{r}]^{r} =
      |      Tuple3(new Node(20), new Node(20), new Node(2))
      |    val triple = make(using region)
      |    triple._1.value + triple._2.value + triple._3.value
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersReturnedLocalTuple2FactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^)
      |        : Tuple2[Node^{r}, Node^{r}]^{r} =
      |      val pair: Tuple2[Node^{r}, Node^{r}]^{r} =
      |        Tuple2(new Node(40), new Node(2))
      |      pair
      |    val pair = make(using region)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersBranchForwardedTuple2FactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(left: Int, right: Int)(using r: RiftRegion.ScopedRegion^)
      |        : Tuple2[Node^{r}, Node^{r}]^{r} =
      |      Tuple2(new Node(left), new Node(right))
      |    def wrap(flag: Boolean)(using r: RiftRegion.ScopedRegion^)
      |        : Tuple2[Node^{r}, Node^{r}]^{r} =
      |      if flag then make(40, 2)(using r) else make(0, 0)(using r)
      |    val pair = wrap(true)(using region)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersMatchForwardedTuple2FactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(left: Int, right: Int)(using r: RiftRegion.ScopedRegion^)
      |        : Tuple2[Node^{r}, Node^{r}]^{r} =
      |      Tuple2(new Node(left), new Node(right))
      |    def wrap(selector: Int)(using r: RiftRegion.ScopedRegion^)
      |        : Tuple2[Node^{r}, Node^{r}]^{r} =
      |      selector match
      |        case 0 => make(40, 2)(using r)
      |        case _ => make(0, 0)(using r)
      |    val pair = wrap(0)(using region)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersTupleLiteralPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^)
      |        : Tuple2[Node^{r}, Node^{r}]^{r} =
      |      (new Node(40), new Node(2))
      |    val pair = make(using region)
      |    pair._1.value + pair._2.value
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersPolymorphicCellFactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def ok(): Int =
      |  def make(value: Int)(using r: RiftRegion.ScopedRegion^): Cell[Box^{r}]^{r} =
      |    val box: Box^{r} = new Box(value)
      |    new Cell[Box^{r}](box)
      |
      |  RiftRegion.scoped { region ?=>
      |    val cell = make(40)(using region)
      |    cell.value.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersReturnedLocalPolymorphicCellFactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def ok(): Int =
      |  def make(value: Int)(using r: RiftRegion.ScopedRegion^): Cell[Box^{r}]^{r} =
      |    val box: Box^{r} = new Box(value)
      |    val cell: Cell[Box^{r}]^{r} =
      |      new Cell[Box^{r}](box)
      |    cell
      |
      |  RiftRegion.scoped { region ?=>
      |    val cell = make(40)(using region)
      |    cell.value.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersBranchForwardedPolymorphicCellFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def ok(): Int =
      |  def make(value: Int)(using r: RiftRegion.ScopedRegion^): Cell[Box^{r}]^{r} =
      |    val box: Box^{r} = new Box(value)
      |    new Cell[Box^{r}](box)
      |
      |  def wrap(flag: Boolean)(using r: RiftRegion.ScopedRegion^): Cell[Box^{r}]^{r} =
      |    if flag then make(40)(using r) else make(0)(using r)
      |
      |  RiftRegion.scoped { region ?=>
      |    val cell = wrap(true)(using region)
      |    cell.value.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersMatchForwardedPolymorphicCellFactoryPlacement()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def ok(): Int =
      |  def make(value: Int)(using r: RiftRegion.ScopedRegion^): Cell[Box^{r}]^{r} =
      |    val box: Box^{r} = new Box(value)
      |    new Cell[Box^{r}](box)
      |
      |  def wrap(selector: Int)(using r: RiftRegion.ScopedRegion^): Cell[Box^{r}]^{r} =
      |    selector match
      |      case 0 => make(40)(using r)
      |      case _ => make(0)(using r)
      |
      |  RiftRegion.scoped { region ?=>
      |    val cell = wrap(0)(using region)
      |    cell.value.value + 2
      |  }
      |""".stripMargin)

  @Test def inferredLocalMethodRegionParamNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    val metadata = new Metadata(1)
      |    def make(using r: RiftRegion.ScopedRegion^): Entry^{r} =
      |      new Entry(metadata)
      |    val entry = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredLocalMethodSomeFactoryCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def make(using r: RiftRegion.ScopedRegion^): Some[Metadata]^{r} =
      |      val metadata = new Metadata(1)
      |      Some(metadata)
      |    val option = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredLocalMethodOptionSupertypeSomeFactoryCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def make(using r: RiftRegion.ScopedRegion^): Option[Metadata]^{r} =
      |      val metadata = new Metadata(1)
      |      Some(metadata)
      |    val option = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredLocalMethodOptionSomeOrNoneCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def make(using r: RiftRegion.ScopedRegion^): Option[Metadata]^{r} =
      |      val metadata = new Metadata(1)
      |      if flag then Some(metadata) else None
      |    val option = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredLocalMethodOptionApplyCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def make(using r: RiftRegion.ScopedRegion^): Option[Metadata]^{r} =
      |      val metadata = new Metadata(1)
      |      Option(metadata)
      |    val option = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredLocalMethodReturnedLocalOptionSomeFactoryCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def make(using r: RiftRegion.ScopedRegion^): Option[Metadata]^{r} =
      |      val metadata = new Metadata(1)
      |      val option: Option[Metadata]^{r} =
      |        Some(metadata)
      |      option
      |    val option = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredBranchForwardedLocalMethodOptionSomeFactoryCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def make(value: Int)(using r: RiftRegion.ScopedRegion^)
      |        : Option[Metadata]^{r} =
      |      val metadata = new Metadata(value)
      |      Some(metadata)
      |    def wrap(flag: Boolean)(using r: RiftRegion.ScopedRegion^)
      |        : Option[Metadata]^{r} =
      |      if flag then make(1)(using r) else make(2)(using r)
      |    val option = wrap(true)(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredMatchForwardedLocalMethodOptionSomeFactoryCannotEscapeHeap()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Node(val value: Int)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def make(value: Int)(using r: RiftRegion.ScopedRegion^)
      |    : Option[Node^{r}]^{r} =
      |  Some(new Node(value))
      |
      |def wrap(selector: Int)(using r: RiftRegion.ScopedRegion^)
      |    : Option[Node^{r}]^{r} =
      |  selector match
      |    case 0 => make(40)(using r)
      |    case _ => make(0)(using r)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val option = wrap(0)(using region)
      |    Holder.retained = option
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def inferredLocalMethodTuple2FactoryCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def make(using r: RiftRegion.ScopedRegion^)
      |        : Tuple2[Metadata, Metadata]^{r} =
      |      val metadata = new Metadata(1)
      |      Tuple2(metadata, metadata)
      |    val pair = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredLocalMethodPrimitiveTuple2FactoryCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def make(using r: RiftRegion.ScopedRegion^)
      |        : Tuple2[Int, Metadata]^{r} =
      |      val metadata = new Metadata(1)
      |      Tuple2(1, metadata)
      |    val pair = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredLocalMethodTuple3FactoryCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def make(using r: RiftRegion.ScopedRegion^)
      |        : Tuple3[Metadata, Metadata, Metadata]^{r} =
      |      val metadata = new Metadata(1)
      |      Tuple3(metadata, metadata, metadata)
      |    val triple = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredLocalMethodReturnedLocalTuple2FactoryCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def make(using r: RiftRegion.ScopedRegion^)
      |        : Tuple2[Metadata, Metadata]^{r} =
      |      val metadata = new Metadata(1)
      |      val pair: Tuple2[Metadata, Metadata]^{r} =
      |        Tuple2(metadata, metadata)
      |      pair
      |    val pair = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredBranchForwardedLocalMethodTuple2FactoryCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def make(value: Int)(using r: RiftRegion.ScopedRegion^)
      |        : Tuple2[Metadata, Metadata]^{r} =
      |      val metadata = new Metadata(value)
      |      Tuple2(metadata, metadata)
      |    def wrap(flag: Boolean)(using r: RiftRegion.ScopedRegion^)
      |        : Tuple2[Metadata, Metadata]^{r} =
      |      if flag then make(1)(using r) else make(2)(using r)
      |    val pair = wrap(true)(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredMatchForwardedLocalMethodTuple2FactoryCannotEscapeHeap()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Node(val value: Int)
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def make(left: Int, right: Int)(using r: RiftRegion.ScopedRegion^)
      |    : Tuple2[Node^{r}, Node^{r}]^{r} =
      |  Tuple2(new Node(left), new Node(right))
      |
      |def wrap(selector: Int)(using r: RiftRegion.ScopedRegion^)
      |    : Tuple2[Node^{r}, Node^{r}]^{r} =
      |  selector match
      |    case 0 => make(40, 2)(using r)
      |    case _ => make(0, 0)(using r)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val pair = wrap(0)(using region)
      |    Holder.retained = pair
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def localMethodPolymorphicCellFactoryCannotEscapeHeap(): Unit =
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
      |  def make(using r: RiftRegion.ScopedRegion^): Cell[Box^{r}]^{r} =
      |    val box: Box^{r} = new Box(1)
      |    new Cell[Box^{r}](box)
      |
      |  RiftRegion.scoped { region ?=>
      |    val cell = make(using region)
      |    Holder.retained = cell
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def inferredLocalMethodPolymorphicCellFactoryCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def bad(): Int =
      |  def make(using r: RiftRegion.ScopedRegion^): Cell[Metadata]^{r} =
      |    val metadata = new Metadata(1)
      |    new Cell[Metadata](metadata)
      |
      |  RiftRegion.scoped { region ?=>
      |    val cell = make(using region)
      |    cell.value.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredLocalMethodReturnedLocalPolymorphicCellFactoryCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def bad(): Int =
      |  def make(using r: RiftRegion.ScopedRegion^): Cell[Metadata]^{r} =
      |    val metadata = new Metadata(1)
      |    val cell: Cell[Metadata]^{r} =
      |      new Cell[Metadata](metadata)
      |    cell
      |
      |  RiftRegion.scoped { region ?=>
      |    val cell = make(using region)
      |    cell.value.value
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredBranchForwardedPolymorphicCellFactoryCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Cell[A](val value: A)
      |
      |def bad(): Unit =
      |  def make(value: Int)(using r: RiftRegion.ScopedRegion^): Cell[Metadata]^{r} =
      |    val metadata = new Metadata(value)
      |    new Cell[Metadata](metadata)
      |
      |  def wrap(flag: Boolean)(using r: RiftRegion.ScopedRegion^): Cell[Metadata]^{r} =
      |    if flag then make(1)(using r) else make(2)(using r)
      |
      |  RiftRegion.scoped { region ?=>
      |    val cell = wrap(true)(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredMatchForwardedPolymorphicCellFactoryCannotEscapeHeap()
      : Unit =
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
      |def make(value: Int)(using r: RiftRegion.ScopedRegion^): Cell[Box^{r}]^{r} =
      |  val box: Box^{r} = new Box(value)
      |  new Cell[Box^{r}](box)
      |
      |def wrap(selector: Int)(using r: RiftRegion.ScopedRegion^): Cell[Box^{r}]^{r} =
      |  selector match
      |    case 0 => make(40)(using r)
      |    case _ => make(0)(using r)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val cell = wrap(0)(using region)
      |    Holder.retained = cell
      |  }
      |""".stripMargin,
      "cannot flow into capture set {}"
    )

  @Test def localMethodRegionParamInfersReturnedLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      val node = new Node(40)
      |      node
      |    val node = make(using region)
      |    node.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersReturnedLocalBlockNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      val node =
      |        val value = 40
      |        new Node(value)
      |      node
      |    val node = make(using region)
      |    node.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersBlockReturnedNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      val value = 40
      |      new Node(value)
      |    val node = make(using region)
      |    node.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersForwardedMethodReturnPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(value: Int)(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      new Node(value)
      |    def wrap(value: Int)(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      make(value)(using r)
      |    val node = wrap(40)(using region)
      |    node.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersForwardedLocalAliasMethodReturnPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(value: Int)(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      new Node(value)
      |    def wrap(value: Int)(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      val node = make(value)(using r)
      |      node
      |    val node = wrap(40)(using region)
      |    node.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersForwardedBranchMethodReturnPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(value: Int)(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      new Node(value)
      |    def wrap(value: Int)(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      if flag then make(value)(using r) else make(value + 1)(using r)
      |    val node = wrap(40)(using region)
      |    node.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersForwardedMatchMethodReturnPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(value: Int)(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      new Node(value)
      |    def wrap(value: Int)(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      selector match
      |        case 0 => make(value)(using r)
      |        case _ => make(value + 1)(using r)
      |    val node = wrap(40)(using region)
      |    node.value + 2
      |  }
      |""".stripMargin)

  @Test def inferredLocalMethodBlockReturnedNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    val metadata = new Metadata(1)
      |    def make(using r: RiftRegion.ScopedRegion^): Entry^{r} =
      |      val offset = 1
      |      new Entry(metadata)
      |    val entry = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredLocalMethodReturnedLocalNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    val metadata = new Metadata(1)
      |    def make(using r: RiftRegion.ScopedRegion^): Entry^{r} =
      |      val entry = new Entry(metadata)
      |      entry
      |    val entry = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredLocalMethodReturnedLocalBlockNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    def make(using r: RiftRegion.ScopedRegion^): Entry^{r} =
      |      val entry =
      |        val metadata = new Metadata(1)
      |        new Entry(metadata)
      |      entry
      |    val entry = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localEpochMethodRegionParamInfersReturnedLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.streaming { stream ?=>
      |    RiftRegion.epoch { epoch ?=>
      |      final class Node(val value: Int)
      |      def make(using r: RiftRegion.OpenStreamingRegion^): Node^{r} =
      |        val node = new Node(40)
      |        node
      |      val node = make(using epoch)
      |      node.value + 2
      |    }
      |  }
      |""".stripMargin)

  @Test def inferredEpochMethodReturnedLocalNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.streaming { stream ?=>
      |    RiftRegion.epoch { epoch ?=>
      |      final class Entry(val metadata: Metadata)
      |      val metadata = new Metadata(1)
      |      def make(using r: RiftRegion.OpenStreamingRegion^): Entry^{r} =
      |        val entry = new Entry(metadata)
      |        entry
      |      val entry = make(using epoch)
      |    }
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamInfersBranchedNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      if flag then new Node(40) else new Node(41)
      |    val node = make(using region)
      |    node.value + 2
      |  }
      |""".stripMargin)

  @Test def localEpochMethodRegionParamInfersBranchedNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.streaming { stream ?=>
      |    RiftRegion.epoch { epoch ?=>
      |      final class Node(val value: Int)
      |      def make(using r: RiftRegion.OpenStreamingRegion^): Node^{r} =
      |        if flag then new Node(40) else new Node(41)
      |      val node = make(using epoch)
      |      node.value + 2
      |    }
      |  }
      |""".stripMargin)

  @Test def inferredBranchedMethodNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    val metadata = new Metadata(1)
      |    def make(using r: RiftRegion.ScopedRegion^): Entry^{r} =
      |      if flag then new Entry(metadata) else new Entry(metadata)
      |    val entry = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamInfersBranchReturnedLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      if flag then
      |        val left = new Node(40)
      |        left
      |      else
      |        val right = new Node(41)
      |        right
      |    val node = make(using region)
      |    node.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersBranchReturnedLocalBlockNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      if flag then
      |        val left =
      |          val value = 40
      |          new Node(value)
      |        left
      |      else
      |        val right =
      |          val value = 41
      |          new Node(value)
      |        right
      |    val node = make(using region)
      |    node.value + 2
      |  }
      |""".stripMargin)

  @Test def inferredBranchReturnedLocalNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    val metadata = new Metadata(1)
      |    def make(using r: RiftRegion.ScopedRegion^): Entry^{r} =
      |      if flag then
      |        val left = new Entry(metadata)
      |        left
      |      else
      |        val right = new Entry(metadata)
      |        right
      |    val entry = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def localMethodRegionParamInfersMatchedNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      selector match
      |        case 0 => new Node(40)
      |        case _ => new Node(41)
      |    val node = make(using region)
      |    node.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersMatchReturnedLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      selector match
      |        case 0 =>
      |          val left = new Node(40)
      |          left
      |        case _ =>
      |          val right = new Node(41)
      |          right
      |    val node = make(using region)
      |    node.value + 2
      |  }
      |""".stripMargin)

  @Test def localMethodRegionParamInfersMatchReturnedLocalBlockNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    def make(using r: RiftRegion.ScopedRegion^): Node^{r} =
      |      selector match
      |        case 0 =>
      |          val left =
      |            val value = 40
      |            new Node(value)
      |          left
      |        case _ =>
      |          val right =
      |            val value = 41
      |            new Node(value)
      |          right
      |    val node = make(using region)
      |    node.value + 2
      |  }
      |""".stripMargin)

  @Test def inferredMatchedMethodNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(selector: Int): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    val metadata = new Metadata(1)
      |    def make(using r: RiftRegion.ScopedRegion^): Entry^{r} =
      |      selector match
      |        case 0 => new Entry(metadata)
      |        case _ => new Entry(metadata)
      |    val entry = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredMatchReturnedLocalNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(selector: Int): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    val metadata = new Metadata(1)
      |    def make(using r: RiftRegion.ScopedRegion^): Entry^{r} =
      |      selector match
      |        case 0 =>
      |          val left = new Entry(metadata)
      |          left
      |        case _ =>
      |          val right = new Entry(metadata)
      |          right
      |    val entry = make(using region)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredCapturedLocalNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata^{region})
      |    val metadata = new Metadata(1)
      |    val entry = new Entry(metadata)
      |    val captured: Entry^{region} = entry
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def regionListInfersLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int) extends RiftRegion.RegionListNode
      |    val list = RiftRegion.regionList[Node]()
      |    val node = new Node(1)
      |    RiftRegion.prependRegionList(region, list, node)
      |    RiftRegion.regionListHead(region, list).value
      |  }
      |""".stripMargin)

  @Test def regionListInfersLocalBlockNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int) extends RiftRegion.RegionListNode
      |    val list = RiftRegion.regionList[Node]()
      |    val node =
      |      val value = 1
      |      new Node(value)
      |    RiftRegion.prependRegionList(region, list, node)
      |    RiftRegion.regionListHead(region, list).value
      |  }
      |""".stripMargin)

  @Test def regionListInfersInlineNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int) extends RiftRegion.RegionListNode
      |    val list = RiftRegion.regionList[Node]()
      |    RiftRegion.prependRegionList(region, list, new Node(1))
      |    RiftRegion.regionListHead(region, list).value
      |  }
      |""".stripMargin)

  @Test def regionListInfersInlineBlockNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int) extends RiftRegion.RegionListNode
      |    val list = RiftRegion.regionList[Node]()
      |    RiftRegion.prependRegionList(
      |      region,
      |      list,
      |      {
      |        val value = 1
      |        new Node(value)
      |      }
      |    )
      |    RiftRegion.regionListHead(region, list).value
      |  }
      |""".stripMargin)

  @Test def regionListInfersBranchMatchSyntheticFactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean, selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class SomeNode(
      |        val option: Option[Leaf^{region}]^{region}
      |    ) extends RiftRegion.RegionListNode
      |    final class OptionNode(
      |        val option: Option[Leaf^{region}]^{region}
      |    ) extends RiftRegion.RegionListNode
      |    final class PairNode(
      |        val pair: Tuple2[Leaf^{region}, Leaf^{region}]^{region}
      |    ) extends RiftRegion.RegionListNode
      |    val someList = RiftRegion.regionList[SomeNode]()
      |    val optionList = RiftRegion.regionList[OptionNode]()
      |    val pairList = RiftRegion.regionList[PairNode]()
      |
      |    RiftRegion.prependRegionList(
      |      region,
      |      someList,
      |      if flag then new SomeNode(Some(new Leaf(40)))
      |      else new SomeNode(Some(new Leaf(41)))
      |    )
      |    RiftRegion.prependRegionList(
      |      region,
      |      optionList,
      |      selector match
      |        case 0 => new OptionNode(Option(new Leaf(10)))
      |        case _ => new OptionNode(Option(new Leaf(11)))
      |    )
      |    RiftRegion.prependRegionList(
      |      region,
      |      pairList,
      |      if flag then new PairNode(Tuple2(new Leaf(1), new Leaf(2)))
      |      else new PairNode(Tuple2(new Leaf(3), new Leaf(4)))
      |    )
      |
      |    RiftRegion.regionListHead(region, someList).option.get.value +
      |      RiftRegion.regionListHead(region, optionList).option.get.value +
      |      RiftRegion.regionListHead(region, pairList).pair._1.value +
      |      RiftRegion.regionListHead(region, pairList).pair._2.value
      |  }
      |""".stripMargin)

  @Test def regionListInfersSelectedNestedSyntheticFactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class SomeNode(
      |        val option: Option[Leaf^{region}]^{region}
      |    ) extends RiftRegion.RegionListNode
      |    final class OptionNode(
      |        val option: Option[Leaf^{region}]^{region}
      |    ) extends RiftRegion.RegionListNode
      |    final class PairNode(
      |        val pair: Tuple2[Leaf^{region}, Leaf^{region}]^{region}
      |    ) extends RiftRegion.RegionListNode
      |    val someList = RiftRegion.regionList[SomeNode]()
      |    val optionList = RiftRegion.regionList[OptionNode]()
      |    val pairList = RiftRegion.regionList[PairNode]()
      |
      |    val someFirst = Some(new Leaf(40))
      |    val someSecond = Some(new Leaf(41))
      |    val someSelected = if flag then someFirst else someSecond
      |    RiftRegion.prependRegionList(
      |      region,
      |      someList,
      |      new SomeNode(someSelected)
      |    )
      |
      |    val optionFirst = Option(new Leaf(10))
      |    val optionSecond = Option(new Leaf(11))
      |    val optionSelected = if flag then optionFirst else optionSecond
      |    RiftRegion.prependRegionList(
      |      region,
      |      optionList,
      |      new OptionNode(optionSelected)
      |    )
      |
      |    val pairFirst = Tuple2(new Leaf(1), new Leaf(2))
      |    val pairSecond = Tuple2(new Leaf(3), new Leaf(4))
      |    val pairSelected = if flag then pairFirst else pairSecond
      |    RiftRegion.prependRegionList(
      |      region,
      |      pairList,
      |      new PairNode(pairSelected)
      |    )
      |
      |    RiftRegion.regionListHead(region, someList).option.get.value +
      |      RiftRegion.regionListHead(region, optionList).option.get.value +
      |      RiftRegion.regionListHead(region, pairList).pair._1.value +
      |      RiftRegion.regionListHead(region, pairList).pair._2.value
      |  }
      |""".stripMargin)

  @Test def objectBufferInfersLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    val buffer = RiftRegion.objectBuffer[Node](1)
      |    val node = new Node(40)
      |    RiftRegion.append(region, buffer, node)
      |    RiftRegion.get(region, buffer, 0).value + 2
      |  }
      |""".stripMargin)

  @Test def objectBufferInfersInlineNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    val buffer = RiftRegion.objectBuffer[Node](1)
      |    RiftRegion.append(region, buffer, new Node(40))
      |    RiftRegion.get(region, buffer, 0).value + 2
      |  }
      |""".stripMargin)

  @Test def objectBufferInfersInlineBlockNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    val buffer = RiftRegion.objectBuffer[Node](1)
      |    region.append(
      |      buffer,
      |      {
      |        val value = 40
      |        new Node(value)
      |      }
      |    )
      |    region.get(buffer, 0).value + 2
      |  }
      |""".stripMargin)

  @Test def objectBufferInfersLocalBlockNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    val buffer = RiftRegion.objectBuffer[Node](1)
      |    val node =
      |      val value = 40
      |      new Node(value)
      |    RiftRegion.append(region, buffer, node)
      |    RiftRegion.get(region, buffer, 0).value + 2
      |  }
      |""".stripMargin)

  @Test def objectBufferOwnerMethodInfersLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    val buffer = RiftRegion.objectBuffer[Node](1)
      |    val node = new Node(40)
      |    region.append(buffer, node)
      |    region.get(buffer, 0).value + 2
      |  }
      |""".stripMargin)

  @Test def objectBufferOwnerMethodInfersInlineNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    val buffer = RiftRegion.objectBuffer[Node](1)
      |    region.append(buffer, new Node(40))
      |    region.get(buffer, 0).value + 2
      |  }
      |""".stripMargin)

  @Test def objectBufferInfersSelectedLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    val buffer = RiftRegion.objectBuffer[Node](1)
      |    val first = new Node(40)
      |    val second = new Node(42)
      |    val selected = if flag then first else second
      |    RiftRegion.append(region, buffer, selected)
      |    RiftRegion.get(region, buffer, 0).value
      |  }
      |""".stripMargin)

  @Test def objectBufferInfersSelectedLocalSyntheticFactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val someBuffer =
      |      RiftRegion.objectBuffer[Option[Leaf^{region}]^{region}](1)
      |    val someFirst = Some(new Leaf(40))
      |    val someSecond = Some(new Leaf(41))
      |    val someSelected = if flag then someFirst else someSecond
      |    RiftRegion.append(region, someBuffer, someSelected)
      |
      |    val optionBuffer =
      |      RiftRegion.objectBuffer[Option[Leaf^{region}]^{region}](1)
      |    val optionFirst = Option(new Leaf(1))
      |    val optionSecond = Option(new Leaf(2))
      |    val optionSelected = if flag then optionFirst else optionSecond
      |    RiftRegion.append(region, optionBuffer, optionSelected)
      |
      |    val pairBuffer =
      |      RiftRegion.objectBuffer[
      |        Tuple2[Leaf^{region}, Leaf^{region}]^{region}
      |      ](1)
      |    val pairFirst = Tuple2(new Leaf(1), new Leaf(1))
      |    val pairSecond = Tuple2(new Leaf(2), new Leaf(2))
      |    val pairSelected = if flag then pairFirst else pairSecond
      |    RiftRegion.append(region, pairBuffer, pairSelected)
      |
      |    RiftRegion.get(region, someBuffer, 0).get.value +
      |      RiftRegion.get(region, optionBuffer, 0).get.value +
      |      RiftRegion.get(region, pairBuffer, 0)._1.value
      |  }
      |""".stripMargin)

  @Test def objectBufferInfersBranchMatchSyntheticFactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean, selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    val someBuffer =
      |      RiftRegion.objectBuffer[Option[Leaf^{region}]^{region}](1)
      |    RiftRegion.append(
      |      region,
      |      someBuffer,
      |      if flag then Some(new Leaf(40)) else Some(new Leaf(41))
      |    )
      |
      |    val optionBuffer =
      |      RiftRegion.objectBuffer[Option[Leaf^{region}]^{region}](1)
      |    RiftRegion.append(
      |      region,
      |      optionBuffer,
      |      (selector match
      |        case 0 => Option(new Leaf(1))
      |        case _ => Option(new Leaf(2))
      |      )
      |    )
      |
      |    val pairBuffer =
      |      RiftRegion.objectBuffer[
      |        Tuple2[Leaf^{region}, Leaf^{region}]^{region}
      |      ](1)
      |    RiftRegion.append(
      |      region,
      |      pairBuffer,
      |      if flag then Tuple2(new Leaf(1), new Leaf(1))
      |      else Tuple2(new Leaf(2), new Leaf(2))
      |    )
      |
      |    RiftRegion.get(region, someBuffer, 0).get.value +
      |      RiftRegion.get(region, optionBuffer, 0).get.value +
      |      RiftRegion.get(region, pairBuffer, 0)._1.value
      |  }
      |""".stripMargin)

  @Test def buffersInferSelectedNestedSyntheticFactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class SomeNode(
      |        val option: Option[Leaf^{region}]^{region}
      |    )
      |    final class OptionNode(
      |        val option: Option[Leaf^{region}]^{region}
      |    )
      |    final class PairNode(
      |        val pair: Tuple2[Leaf^{region}, Leaf^{region}]^{region}
      |    )
      |
      |    val objectSomeBuffer = RiftRegion.objectBuffer[SomeNode](1)
      |    val someFirst = Some(new Leaf(40))
      |    val someSecond = Some(new Leaf(41))
      |    val someSelected = if flag then someFirst else someSecond
      |    RiftRegion.append(region, objectSomeBuffer, new SomeNode(someSelected))
      |
      |    val regionOptionBuffer = RiftRegion.regionBuffer[OptionNode](1)
      |    val optionFirst = Option(new Leaf(10))
      |    val optionSecond = Option(new Leaf(11))
      |    val optionSelected = if flag then optionFirst else optionSecond
      |    region.append(regionOptionBuffer, new OptionNode(optionSelected))
      |
      |    val objectPairBuffer = RiftRegion.objectBuffer[PairNode](1)
      |    val pairFirst = Tuple2(new Leaf(1), new Leaf(2))
      |    val pairSecond = Tuple2(new Leaf(3), new Leaf(4))
      |    val pairSelected = if flag then pairFirst else pairSecond
      |    RiftRegion.append(region, objectPairBuffer, new PairNode(pairSelected))
      |
      |    RiftRegion.get(region, objectSomeBuffer, 0).option.get.value +
      |      region.get(regionOptionBuffer, 0).option.get.value +
      |      RiftRegion.get(region, objectPairBuffer, 0).pair._1.value +
      |      RiftRegion.get(region, objectPairBuffer, 0).pair._2.value
      |  }
      |""".stripMargin)

  @Test def regionBufferInfersLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    val buffer = RiftRegion.regionBuffer[Node](1)
      |    val first = new Node(20)
      |    val second = new Node(22)
      |    region.append(buffer, first)
      |    RiftRegion.append(region, buffer, second)
      |    region.get(buffer, 0).value + region.get(buffer, 1).value
      |  }
      |""".stripMargin)

  @Test def regionBufferInfersSelectedLocalNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    val buffer = RiftRegion.regionBuffer[Node](1)
      |    val first = new Node(40)
      |    val second = new Node(42)
      |    val selected = if flag then first else second
      |    region.append(buffer, selected)
      |    region.get(buffer, 0).value
      |  }
      |""".stripMargin)

  @Test def regionBufferInfersSelectedLocalSyntheticFactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val someBuffer =
      |      RiftRegion.regionBuffer[Option[Leaf^{region}]^{region}](1)
      |    val someFirst = Some(new Leaf(40))
      |    val someSecond = Some(new Leaf(41))
      |    val someSelected = if flag then someFirst else someSecond
      |    region.append(someBuffer, someSelected)
      |
      |    val optionBuffer =
      |      RiftRegion.regionBuffer[Option[Leaf^{region}]^{region}](1)
      |    val optionFirst = Option(new Leaf(1))
      |    val optionSecond = Option(new Leaf(2))
      |    val optionSelected = if flag then optionFirst else optionSecond
      |    region.append(optionBuffer, optionSelected)
      |
      |    val pairBuffer =
      |      RiftRegion.regionBuffer[
      |        Tuple2[Leaf^{region}, Leaf^{region}]^{region}
      |      ](1)
      |    val pairFirst = Tuple2(new Leaf(1), new Leaf(1))
      |    val pairSecond = Tuple2(new Leaf(2), new Leaf(2))
      |    val pairSelected = if flag then pairFirst else pairSecond
      |    region.append(pairBuffer, pairSelected)
      |
      |    region.get(someBuffer, 0).get.value +
      |      region.get(optionBuffer, 0).get.value +
      |      region.get(pairBuffer, 0)._1.value
      |  }
      |""".stripMargin)

  @Test def regionBufferInfersBranchMatchSyntheticFactoryPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(flag: Boolean, selector: Int): Int =
      |  RiftRegion.scoped { region ?=>
      |    val someBuffer =
      |      RiftRegion.regionBuffer[Option[Leaf^{region}]^{region}](1)
      |    region.append(
      |      someBuffer,
      |      if flag then Some(new Leaf(40)) else Some(new Leaf(41))
      |    )
      |
      |    val optionBuffer =
      |      RiftRegion.regionBuffer[Option[Leaf^{region}]^{region}](1)
      |    region.append(
      |      optionBuffer,
      |      (selector match
      |        case 0 => Option(new Leaf(1))
      |        case _ => Option(new Leaf(2))
      |      )
      |    )
      |
      |    val pairBuffer =
      |      RiftRegion.regionBuffer[
      |        Tuple2[Leaf^{region}, Leaf^{region}]^{region}
      |      ](1)
      |    region.append(
      |      pairBuffer,
      |      if flag then Tuple2(new Leaf(1), new Leaf(1))
      |      else Tuple2(new Leaf(2), new Leaf(2))
      |    )
      |
      |    region.get(someBuffer, 0).get.value +
      |      region.get(optionBuffer, 0).get.value +
      |      region.get(pairBuffer, 0)._1.value
      |  }
      |""".stripMargin)

  @Test def regionBufferInfersLocalBlockNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    val buffer = RiftRegion.regionBuffer[Node](1)
      |    val first =
      |      val value = 20
      |      new Node(value)
      |    val second =
      |      val value = 22
      |      new Node(value)
      |    region.append(buffer, first)
      |    RiftRegion.append(region, buffer, second)
      |    region.get(buffer, 0).value + region.get(buffer, 1).value
      |  }
      |""".stripMargin)

  @Test def regionBufferInfersInlineNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    val buffer = RiftRegion.regionBuffer[Node](1)
      |    region.append(buffer, new Node(20))
      |    RiftRegion.append(region, buffer, new Node(22))
      |    region.get(buffer, 0).value + region.get(buffer, 1).value
      |  }
      |""".stripMargin)

  @Test def regionBufferInfersInlineBlockNewPlacement(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int)
      |    val buffer = RiftRegion.regionBuffer[Node](1)
      |    region.append(
      |      buffer,
      |      {
      |        val value = 20
      |        new Node(value)
      |      }
      |    )
      |    RiftRegion.append(
      |      region,
      |      buffer,
      |      {
      |        val value = 22
      |        new Node(value)
      |      }
      |    )
      |    region.get(buffer, 0).value + region.get(buffer, 1).value
      |  }
      |""".stripMargin)

  @Test def inferredObjectBufferCanAppendInlineClosure(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val buffer =
      |      RiftRegion.objectBuffer[Function1[Int, Int]^{region}](1)
      |    RiftRegion.append(region, buffer, (n: Int) => n + 40)
      |    RiftRegion.get(region, buffer, 0)(2)
      |  }
      |""".stripMargin)

  @Test def inferredObjectBufferCanAppendInlineClosureBodyAllocation(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val owner = region
      |    val buffer =
      |      RiftRegion.objectBuffer[Function1[Int, Box^{region}]^{region}](1)
      |    RiftRegion.append(region, buffer, (n: Int) =>
      |      val keepOwner = System.identityHashCode(owner) & 0
      |      val box: Box^{owner} = new Box(n + 40 + keepOwner)
      |      box
      |    )
      |    RiftRegion.get(region, buffer, 0)(2).value
      |  }
      |""".stripMargin)

  @Test def inferredObjectBufferCanAppendInlineRegionArray(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val buffer =
      |      RiftRegion.objectBuffer[Array[Leaf^{region}]^{region}](1)
      |    RiftRegion.append(region, buffer, new Array[Leaf^{region}](1))
      |    val leaves = RiftRegion.get(region, buffer, 0)
      |    leaves(0) = new Leaf(40)
      |    leaves(0).value + 2
      |  }
      |""".stripMargin)

  @Test def inferredRegionBufferCanAppendInlineRegionArray(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Leaf(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val buffer =
      |      RiftRegion.regionBuffer[Array[Leaf^{region}]^{region}](1)
      |    region.append(buffer, new Array[Leaf^{region}](1))
      |    val leaves = region.get(buffer, 0)
      |    leaves(0) = new Leaf(40)
      |    leaves(0).value + 2
      |  }
      |""".stripMargin)

  @Test def inferredRegionBufferCanAppendSelectedLocalClosure(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val buffer =
      |      RiftRegion.regionBuffer[Function1[Int, Int]^{region}](1)
      |    val first = (n: Int) => n + 40
      |    val second = (n: Int) => n + 41
      |    val selected = if flag then first else second
      |    region.append(buffer, selected)
      |    region.get(buffer, 0)(2)
      |  }
      |""".stripMargin)

  @Test def inferredRegionBufferCanAppendSelectedLocalClosureBodyAllocation()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val owner = region
      |    val buffer =
      |      RiftRegion.regionBuffer[Function1[Int, Box^{region}]^{region}](1)
      |    val first = (n: Int) =>
      |      val keepOwner = System.identityHashCode(owner) & 0
      |      val box: Box^{owner} = new Box(n + 40 + keepOwner)
      |      box
      |    val second = (n: Int) =>
      |      val keepOwner = System.identityHashCode(owner) & 0
      |      val box: Box^{owner} = new Box(n + 41 + keepOwner)
      |      box
      |    val selected = if flag then first else second
      |    region.append(buffer, selected)
      |    region.get(buffer, 0)(2).value
      |  }
      |""".stripMargin)

  @Test def inferredPriorityQueueCanPushInlineClosure(): Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val queue =
      |      RiftRegion.regionPriorityQueue[Function1[Int, Int]^{region}](1)
      |    region.push(queue, (n: Int) => n + 40, 1L)
      |    region.peek(queue)(2)
      |  }
      |""".stripMargin)

  @Test def inferredPriorityQueueCanPushInlineClosureBodyAllocation()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val owner = region
      |    val queue =
      |      RiftRegion.regionPriorityQueue[
      |        Function1[Int, Box^{region}]^{region}
      |      ](1)
      |    region.push(
      |      queue,
      |      (n: Int) =>
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        val box: Box^{owner} = new Box(n + 40 + keepOwner)
      |        box,
      |      1L
      |    )
      |    region.peek(queue)(2).value
      |  }
      |""".stripMargin)

  @Test def inferredPriorityQueueCanPushSelectedLocalClosureBodyAllocation()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    val owner = region
      |    val queue =
      |      RiftRegion.regionPriorityQueue[
      |        Function1[Int, Box^{region}]^{region}
      |      ](1)
      |    val first = (n: Int) =>
      |      val keepOwner = System.identityHashCode(owner) & 0
      |      val box: Box^{owner} = new Box(n + 40 + keepOwner)
      |      box
      |    val second = (n: Int) =>
      |      val keepOwner = System.identityHashCode(owner) & 0
      |      val box: Box^{owner} = new Box(n + 41 + keepOwner)
      |      box
      |    val selected = if flag then first else second
      |    region.push(queue, selected, 1L)
      |    region.peek(queue)(2).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionWrapperCanStoreInlineClosureBodyAllocation()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Wrapper(
      |        val make: Function1[Int, Box^{region}]^{region}
      |    )
      |    val owner = region
      |    val wrapper: Wrapper^{region} =
      |      new Wrapper(
      |        (n: Int) =>
      |          val keepOwner = System.identityHashCode(owner) & 0
      |          val box: Box^{owner} = new Box(n + 40 + keepOwner)
      |          box
      |      )
      |    wrapper.make(2).value
      |  }
      |""".stripMargin)

  @Test def inferredRegionWrapperCanStoreSelectedClosureBodyAllocation()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    final class Wrapper(
      |        val make: Function1[Int, Box^{region}]^{region}
      |    )
      |    val owner = region
      |    val first = (n: Int) =>
      |      val keepOwner = System.identityHashCode(owner) & 0
      |      val box: Box^{owner} = new Box(n + 40 + keepOwner)
      |      box
      |    val second = (n: Int) =>
      |      val keepOwner = System.identityHashCode(owner) & 0
      |      val box: Box^{owner} = new Box(n + 41 + keepOwner)
      |      box
      |    val selected = if flag then first else second
      |    val wrapper: Wrapper^{region} = new Wrapper(selected)
      |    wrapper.make(2).value
      |  }
      |""".stripMargin)

  @Test def inferredMethodReturnedSomeCanStoreInlineClosureBodyAllocation()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def make(using
      |        r: RiftRegion.ScopedRegion^
      |    ): Option[Function1[Int, Box^{r}]^{r}]^{r} =
      |      val owner = r
      |      Some(
      |        (n: Int) =>
      |          val keepOwner = System.identityHashCode(owner) & 0
      |          val box: Box^{owner} = new Box(n + 40 + keepOwner)
      |          box
      |      )
      |
      |    make(using region).get(2).value
      |  }
      |""".stripMargin)

  @Test def inferredMethodReturnedSomeCanStoreSelectedClosureBodyAllocation()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    def make(flag: Boolean)(using
      |        r: RiftRegion.ScopedRegion^
      |    ): Option[Function1[Int, Box^{r}]^{r}]^{r} =
      |      val owner = r
      |      val first = (n: Int) =>
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        val box: Box^{owner} = new Box(n + 40 + keepOwner)
      |        box
      |      val second = (n: Int) =>
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        val box: Box^{owner} = new Box(n + 41 + keepOwner)
      |        box
      |      val selected = if flag then first else second
      |      Some(selected)
      |
      |    make(flag)(using region).get(2).value
      |  }
      |""".stripMargin)

  @Test def inferredMethodArgumentWrapperCanStoreInlineClosureBodyAllocation()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |final class Wrapper[A <: Object^](val make: Function1[Int, A]^)
      |
      |def ok(): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        wrapper: Wrapper[Box^{r}]^{r}
      |    ): Int =
      |      wrapper.make(2).value
      |
      |    val owner = region
      |    consume(using region)(
      |      new Wrapper[Box^{region}](
      |        (n: Int) =>
      |          val keepOwner = System.identityHashCode(owner) & 0
      |          val box: Box^{owner} = new Box(n + 40 + keepOwner)
      |          box
      |      )
      |    )
      |  }
      |""".stripMargin)

  @Test def inferredMethodArgumentWrapperCanStoreSelectedClosureBodyAllocation()
      : Unit =
    assertCompiles("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Box(val value: Int)
      |final class Wrapper[A <: Object^](val make: Function1[Int, A]^)
      |
      |def ok(flag: Boolean): Int =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        wrapper: Wrapper[Box^{r}]^{r}
      |    ): Int =
      |      wrapper.make(2).value
      |
      |    val owner = region
      |    val first = (n: Int) =>
      |      val keepOwner = System.identityHashCode(owner) & 0
      |      val box: Box^{owner} = new Box(n + 40 + keepOwner)
      |      box
      |    val second = (n: Int) =>
      |      val keepOwner = System.identityHashCode(owner) & 0
      |      val box: Box^{owner} = new Box(n + 41 + keepOwner)
      |      box
      |    val selected = if flag then first else second
      |    consume(using region)(new Wrapper[Box^{region}](selected))
      |  }
      |""".stripMargin)

  @Test def inferredObjectBufferClosureCannotCaptureUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val buffer =
      |      RiftRegion.objectBuffer[Function1[Int, Int]^{region}](1)
      |    RiftRegion.append(region, buffer, (n: Int) => metadata.value + n)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredPriorityQueueClosureCannotCaptureUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val metadata = new Metadata(40)
      |    val queue =
      |      RiftRegion.regionPriorityQueue[Function1[Int, Int]^{region}](1)
      |    region.push(queue, (n: Int) => metadata.value + n, 1L)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionWrapperClosureBodyCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Wrapper(
      |        val make: Function1[Int, Entry^{region}]^{region}
      |    )
      |    val owner = region
      |    val metadata = new Metadata(40)
      |    val wrapper: Wrapper^{region} =
      |      new Wrapper(
      |        (n: Int) =>
      |          val keepOwner = System.identityHashCode(owner) & 0
      |          val entry: Entry^{owner} = new Entry(metadata)
      |          if keepOwner == -1 then entry else entry
      |      )
      |    val entry = wrapper.make(2)
      |    System.identityHashCode(entry)
      |    ()
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredMethodReturnedSomeClosureBodyCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def make(using
      |        r: RiftRegion.ScopedRegion^
      |    ): Option[Function1[Int, Entry^{r}]^{r}]^{r} =
      |      val owner = r
      |      val metadata = new Metadata(40)
      |      Some(
      |        (n: Int) =>
      |          val keepOwner = System.identityHashCode(owner) & 0
      |          val entry: Entry^{owner} = new Entry(metadata)
      |          if keepOwner == -1 then entry else entry
      |      )
      |
      |    val entry = make(using region).get(2)
      |    System.identityHashCode(entry)
      |    ()
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredMethodArgumentWrapperClosureBodyCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |final class Wrapper[A <: Object^](val make: Function1[Int, A]^)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    def consume(using r: RiftRegion.ScopedRegion^)(
      |        wrapper: Wrapper[Entry^{r}]^{r}
      |    ): Unit =
      |      val entry = wrapper.make(2)
      |      System.identityHashCode(entry)
      |      ()
      |
      |    val owner = region
      |    val metadata = new Metadata(40)
      |    consume(using region)(
      |      new Wrapper[Entry^{region}](
      |        (n: Int) =>
      |          val keepOwner = System.identityHashCode(owner) & 0
      |          val entry: Entry^{owner} = new Entry(metadata)
      |          if keepOwner == -1 then entry else entry
      |      )
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredPriorityQueueClosureBodyCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |final class Entry(val metadata: Metadata)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val owner = region
      |    val metadata = new Metadata(40)
      |    val queue =
      |      RiftRegion.regionPriorityQueue[
      |        Function1[Int, Entry^{region}]^{region}
      |      ](1)
      |    region.push(
      |      queue,
      |      (n: Int) =>
      |        val keepOwner = System.identityHashCode(owner) & 0
      |        val entry: Entry^{owner} = new Entry(metadata)
      |        if keepOwner == -1 then entry else entry,
      |      1L
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredObjectBufferArrayCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val buffer =
      |      RiftRegion.objectBuffer[Array[Metadata]^{region}](1)
      |    RiftRegion.append(region, buffer, new Array[Metadata](1))
      |    val values = RiftRegion.get(region, buffer, 0)
      |    val metadata = new Metadata(41)
      |    values(0) = metadata
      |    values(0).value
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def inferredRegionBufferArrayCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Int =
      |  RiftRegion.scoped { region ?=>
      |    val buffer =
      |      RiftRegion.regionBuffer[Array[Metadata]^{region}](1)
      |    region.append(buffer, new Array[Metadata](1))
      |    val values = region.get(buffer, 0)
      |    val metadata = new Metadata(41)
      |    values(0) = metadata
      |    values(0).value
      |  }
      |""".stripMargin,
      "Rift checked region array store cannot store an unrooted heap object"
    )

  @Test def inferredObjectBufferNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    val buffer = RiftRegion.objectBuffer[Entry](1)
      |    val metadata = new Metadata(1)
      |    val entry = new Entry(metadata)
      |    RiftRegion.append(region, buffer, entry)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredInlineObjectBufferNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    val buffer = RiftRegion.objectBuffer[Entry](1)
      |    val metadata = new Metadata(1)
      |    RiftRegion.append(region, buffer, new Entry(metadata))
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedObjectBufferNewCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    val buffer = RiftRegion.objectBuffer[Entry](1)
      |    val metadata = new Metadata(1)
      |    val first = new Entry(metadata)
      |    val second = new Entry(metadata)
      |    val selected = if flag then first else second
      |    RiftRegion.append(region, buffer, selected)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedObjectBufferOptionApplyCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val buffer = RiftRegion.objectBuffer[Option[Metadata]^{region}](1)
      |    val metadata = new Metadata(1)
      |    val first = Option(metadata)
      |    val second = Option(metadata)
      |    val selected = if flag then first else second
      |    RiftRegion.append(region, buffer, selected)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredBranchObjectBufferOptionApplyCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val buffer = RiftRegion.objectBuffer[Option[Metadata]^{region}](1)
      |    val metadata = new Metadata(1)
      |    RiftRegion.append(
      |      region,
      |      buffer,
      |      if flag then Option(metadata) else Option(metadata)
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedObjectBufferSomeCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val buffer = RiftRegion.objectBuffer[Option[Metadata]^{region}](1)
      |    val metadata = new Metadata(1)
      |    val first = Some(metadata)
      |    val second = Some(metadata)
      |    val selected = if flag then first else second
      |    RiftRegion.append(region, buffer, selected)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedObjectBufferTuple2CannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val buffer =
      |      RiftRegion.objectBuffer[Tuple2[Metadata, Metadata]^{region}](1)
      |    val metadata = new Metadata(1)
      |    val first = Tuple2(metadata, metadata)
      |    val second = Tuple2(metadata, metadata)
      |    val selected = if flag then first else second
      |    RiftRegion.append(region, buffer, selected)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedNestedObjectBufferOptionCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(
      |        val option: Option[Metadata]^{region}
      |    )
      |    val buffer = RiftRegion.objectBuffer[Entry](1)
      |    val metadata = new Metadata(1)
      |    val first = Option(metadata)
      |    val second = Option(metadata)
      |    val selected = if flag then first else second
      |    RiftRegion.append(region, buffer, new Entry(selected))
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredInlineRegionBufferNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    val buffer = RiftRegion.regionBuffer[Entry](1)
      |    val metadata = new Metadata(1)
      |    region.append(buffer, new Entry(metadata))
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedRegionBufferNewCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    val buffer = RiftRegion.regionBuffer[Entry](1)
      |    val metadata = new Metadata(1)
      |    val first = new Entry(metadata)
      |    val second = new Entry(metadata)
      |    val selected = if flag then first else second
      |    region.append(buffer, selected)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedRegionBufferOptionApplyCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val buffer = RiftRegion.regionBuffer[Option[Metadata]^{region}](1)
      |    val metadata = new Metadata(1)
      |    val first = Option(metadata)
      |    val second = Option(metadata)
      |    val selected = if flag then first else second
      |    region.append(buffer, selected)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredMatchRegionBufferOptionApplyCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(selector: Int): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val buffer = RiftRegion.regionBuffer[Option[Metadata]^{region}](1)
      |    val metadata = new Metadata(1)
      |    region.append(
      |      buffer,
      |      (selector match
      |        case 0 => Option(metadata)
      |        case _ => Option(metadata)
      |      )
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedRegionBufferSomeCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val buffer = RiftRegion.regionBuffer[Option[Metadata]^{region}](1)
      |    val metadata = new Metadata(1)
      |    val first = Some(metadata)
      |    val second = Some(metadata)
      |    val selected = if flag then first else second
      |    region.append(buffer, selected)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedRegionBufferTuple2CannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    val buffer =
      |      RiftRegion.regionBuffer[Tuple2[Metadata, Metadata]^{region}](1)
      |    val metadata = new Metadata(1)
      |    val first = Tuple2(metadata, metadata)
      |    val second = Tuple2(metadata, metadata)
      |    val selected = if flag then first else second
      |    region.append(buffer, selected)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedNestedRegionBufferTuple2CannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(
      |        val pair: Tuple2[Metadata, Metadata]^{region}
      |    )
      |    val buffer = RiftRegion.regionBuffer[Entry](1)
      |    val metadata = new Metadata(1)
      |    val first = Tuple2(metadata, metadata)
      |    val second = Tuple2(metadata, metadata)
      |    val selected = if flag then first else second
      |    region.append(buffer, new Entry(selected))
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredInlineBlockRegionBufferNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Entry(val metadata: Metadata)
      |    val buffer = RiftRegion.regionBuffer[Entry](1)
      |    val metadata = new Metadata(1)
      |    region.append(
      |      buffer,
      |      {
      |        val tag = metadata
      |        new Entry(tag)
      |      }
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredRegionListNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val metadata: Metadata^{region})
      |        extends RiftRegion.RegionListNode
      |    val list = RiftRegion.regionList[Node]()
      |    val metadata = new Metadata(1)
      |    val node = new Node(metadata)
      |    RiftRegion.prependRegionList(region, list, node)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredInlineRegionListNewCannotStoreUnrootedHeapMetadata(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val metadata: Metadata^{region})
      |        extends RiftRegion.RegionListNode
      |    val list = RiftRegion.regionList[Node]()
      |    val metadata = new Metadata(1)
      |    RiftRegion.prependRegionList(region, list, new Node(metadata))
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedRegionListNewCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val metadata: Metadata^{region})
      |        extends RiftRegion.RegionListNode
      |    val list = RiftRegion.regionList[Node]()
      |    val metadata = new Metadata(1)
      |    val first = new Node(metadata)
      |    val second = new Node(metadata)
      |    val selected = if flag then first else second
      |    RiftRegion.prependRegionList(region, list, selected)
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredBranchRegionListOptionApplyCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(
      |        val option: Option[Metadata]^{region}
      |    ) extends RiftRegion.RegionListNode
      |    val list = RiftRegion.regionList[Node]()
      |    val metadata = new Metadata(1)
      |    RiftRegion.prependRegionList(
      |      region,
      |      list,
      |      if flag then new Node(Option(metadata))
      |      else new Node(Option(metadata))
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredMatchRegionListTupleCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(selector: Int): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(
      |        val pair: Tuple2[Metadata, Metadata]^{region}
      |    ) extends RiftRegion.RegionListNode
      |    val list = RiftRegion.regionList[Node]()
      |    val metadata = new Metadata(1)
      |    RiftRegion.prependRegionList(
      |      region,
      |      list,
      |      selector match
      |        case 0 => new Node(Tuple2(metadata, metadata))
      |        case _ => new Node(Tuple2(metadata, metadata))
      |    )
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def inferredSelectedNestedRegionListOptionCannotStoreUnrootedHeapMetadata()
      : Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |final class Metadata(val value: Int)
      |
      |def bad(flag: Boolean): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(
      |        val option: Option[Metadata]^{region}
      |    ) extends RiftRegion.RegionListNode
      |    val list = RiftRegion.regionList[Node]()
      |    val metadata = new Metadata(1)
      |    val first = Option(metadata)
      |    val second = Option(metadata)
      |    val selected = if flag then first else second
      |    RiftRegion.prependRegionList(region, list, new Node(selected))
      |  }
      |""".stripMargin,
      "Rift checked region allocation cannot store an unrooted heap object"
    )

  @Test def regionListDoesNotInferMutableLocalNewPlacement(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int) extends RiftRegion.RegionListNode
      |    val list = RiftRegion.regionList[Node]()
      |    var node = new Node(1)
      |    RiftRegion.prependRegionList(region, list, node)
      |  }
      |""".stripMargin,
      "Rift checked region method argument cannot pass an unrooted heap object"
    )

  @Test def inferredRegionListNewCannotEscapeDurableHeap(): Unit =
    assertDoesNotCompileWith("""
      |import scala.language.experimental.captureChecking
      |import scala.scalanative.memory.RiftRegion
      |
      |object Holder:
      |  var retained: AnyRef = null
      |
      |def bad(): Unit =
      |  RiftRegion.scoped { region ?=>
      |    final class Node(val value: Int) extends RiftRegion.RegionListNode
      |    val list = RiftRegion.regionList[Node]()
      |    val node = new Node(1)
      |    RiftRegion.prependRegionList(region, list, node)
      |    Holder.retained = node
      |  }
      |""".stripMargin,
      "Rift checked heap state cannot retain a region-captured value"
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
      |    def makeHeapNode(): Node = new Node(1, null)
      |    var head: Node^{region} = null
      |    val heapNode = makeHeapNode()
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
      "cannot flow into capture set"
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
