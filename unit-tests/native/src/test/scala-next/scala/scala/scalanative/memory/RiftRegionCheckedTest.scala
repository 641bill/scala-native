package scala.scalanative.memory

import org.junit.Assert._
import org.junit.Test

import scala.scalanative.runtime.{fromRawUSize, RiftAllocator, toRawPtr}
import scala.scalanative.runtime.Intrinsics.castRawPtrToLong
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
private final class RiftCheckedListNode(val value: Int)
    extends RiftRegion.RegionListNode

class RiftRegionCheckedTest {
  private def address(ptr: Ptr[Byte]): Long =
    castRawPtrToLong(toRawPtr(ptr))

  private def rawSizeToLong(value: scala.scalanative.runtime.RawSize): Long =
    fromRawUSize(value).toLong

  private def invokeRegionFunction(
      fn: Function1[Int, Int]^,
      value: Int
  ): Int =
    fn(value)

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

  @Test def scopedRegionInfersAnnotatedNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val list = RiftRegion.regionList[RiftCheckedListNode]()
        val first: RiftCheckedListNode^{region} =
          new RiftCheckedListNode(40)
        val second: RiftCheckedListNode^{region} =
          new RiftCheckedListNode(2)
        RiftRegion.prependRegionList(region, list, first)
        RiftRegion.prependRegionList(region, list, second)
        val head = RiftRegion.regionListHead(region, list)
        head.value + RiftRegion.regionListNext(region, head).value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersRegionListLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val list = RiftRegion.regionList[RiftCheckedListNode]()
        val first = new RiftCheckedListNode(40)
        val second = new RiftCheckedListNode(2)
        RiftRegion.prependRegionList(region, list, first)
        RiftRegion.prependRegionList(region, list, second)
        val head = RiftRegion.regionListHead(region, list)
        head.value + RiftRegion.regionListNext(region, head).value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersRegionListLocalBlockNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val list = RiftRegion.regionList[RiftCheckedListNode]()
        val first =
          val value = 40
          new RiftCheckedListNode(value)
        val second =
          val value = 2
          new RiftCheckedListNode(value)
        RiftRegion.prependRegionList(region, list, first)
        RiftRegion.prependRegionList(region, list, second)
        val head = RiftRegion.regionListHead(region, list)
        head.value + RiftRegion.regionListNext(region, head).value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersRegionListInlineNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val list = RiftRegion.regionList[RiftCheckedListNode]()
        RiftRegion.prependRegionList(
          region,
          list,
          new RiftCheckedListNode(40)
        )
        RiftRegion.prependRegionList(
          region,
          list,
          new RiftCheckedListNode(2)
        )
        val head = RiftRegion.regionListHead(region, list)
        head.value + RiftRegion.regionListNext(region, head).value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersRegionListInlineBlockNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val list = RiftRegion.regionList[RiftCheckedListNode]()
        RiftRegion.prependRegionList(
          region,
          list,
          {
            val value = 40
            new RiftCheckedListNode(value)
          }
        )
        RiftRegion.prependRegionList(
          region,
          list,
          {
            val value = 2
            new RiftCheckedListNode(value)
          }
        )
        val head = RiftRegion.regionListHead(region, list)
        head.value + RiftRegion.regionListNext(region, head).value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersCapturedValDefLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val node = new RiftCheckedListNode(40)
        val captured: RiftCheckedListNode^{region} = node
        val list = RiftRegion.regionList[RiftCheckedListNode]()
        RiftRegion.prependRegionList(region, list, captured)
        RiftRegion.regionListHead(region, list).value + 2
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 1L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersRegionListSelectedLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val list = RiftRegion.regionList[RiftCheckedListNode]()
        val first = new RiftCheckedListNode(40)
        val second = new RiftCheckedListNode(42)
        val keep =
          System.identityHashCode(first) + System.identityHashCode(second)
        val selected = if ((keep & 1) == 0) first else second
        val expected = if ((keep & 1) == 0) 40 else 42
        RiftRegion.prependRegionList(region, list, selected)
        RiftRegion.regionListHead(region, list).value - expected + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, sum)
      assertTrue(
        s"expected selected RegionList candidate allocations to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersRegionListBranchMatchSyntheticFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class SomeNode(
            val option: Option[RiftCheckedLeaf^{region}]^{region}
        ) extends RiftRegion.RegionListNode
        final class OptionNode(
            val option: Option[RiftCheckedLeaf^{region}]^{region}
        ) extends RiftRegion.RegionListNode
        final class PairNode(
            val pair: Tuple2[
              RiftCheckedLeaf^{region},
              RiftCheckedLeaf^{region}
            ]^{region}
        ) extends RiftRegion.RegionListNode
        val someList = RiftRegion.regionList[SomeNode]()
        val optionList = RiftRegion.regionList[OptionNode]()
        val pairList = RiftRegion.regionList[PairNode]()

        val chooseSome = (System.identityHashCode(region) & 1) == 0
        RiftRegion.prependRegionList(
          region,
          someList,
          if chooseSome then new SomeNode(Some(new RiftCheckedLeaf(40)))
          else new SomeNode(Some(new RiftCheckedLeaf(41)))
        )

        val optionSelector = System.identityHashCode(someList) & 1
        RiftRegion.prependRegionList(
          region,
          optionList,
          optionSelector match
            case 0 => new OptionNode(Option(new RiftCheckedLeaf(10)))
            case _ => new OptionNode(Option(new RiftCheckedLeaf(11)))
        )

        val choosePair = (System.identityHashCode(optionList) & 1) == 0
        RiftRegion.prependRegionList(
          region,
          pairList,
          if choosePair then new PairNode(
            Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(2))
          )
          else new PairNode(
            Tuple2(new RiftCheckedLeaf(3), new RiftCheckedLeaf(4))
          )
        )

        val expectedSome = if chooseSome then 40 else 41
        val expectedOption = if optionSelector == 0 then 10 else 11
        val expectedPair = if choosePair then 3 else 7
        RiftRegion.regionListHead(region, someList).option.get.value +
          RiftRegion.regionListHead(region, optionList).option.get.value +
          RiftRegion.regionListHead(region, pairList).pair._1.value +
          RiftRegion.regionListHead(region, pairList).pair._2.value -
          expectedSome - expectedOption - expectedPair + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch/match RegionList synthetic factories, payloads, and nodes to be region allocated, observed $delta region objects",
        delta >= 10L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersRegionListSelectedNestedSyntheticFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class SomeNode(
            val option: Option[RiftCheckedLeaf^{region}]^{region}
        ) extends RiftRegion.RegionListNode
        final class OptionNode(
            val option: Option[RiftCheckedLeaf^{region}]^{region}
        ) extends RiftRegion.RegionListNode
        final class PairNode(
            val pair: Tuple2[
              RiftCheckedLeaf^{region},
              RiftCheckedLeaf^{region}
            ]^{region}
        ) extends RiftRegion.RegionListNode
        val someList = RiftRegion.regionList[SomeNode]()
        val optionList = RiftRegion.regionList[OptionNode]()
        val pairList = RiftRegion.regionList[PairNode]()

        val chooseSome = (System.identityHashCode(region) & 1) == 0
        val someFirst = Some(new RiftCheckedLeaf(40))
        val someSecond = Some(new RiftCheckedLeaf(41))
        val someSelected = if chooseSome then someFirst else someSecond
        RiftRegion.prependRegionList(
          region,
          someList,
          new SomeNode(someSelected)
        )

        val chooseOption = (System.identityHashCode(someList) & 1) == 0
        val optionFirst = Option(new RiftCheckedLeaf(10))
        val optionSecond = Option(new RiftCheckedLeaf(11))
        val optionSelected = if chooseOption then optionFirst else optionSecond
        RiftRegion.prependRegionList(
          region,
          optionList,
          new OptionNode(optionSelected)
        )

        val choosePair = (System.identityHashCode(optionList) & 1) == 0
        val pairFirst = Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(2))
        val pairSecond = Tuple2(new RiftCheckedLeaf(3), new RiftCheckedLeaf(4))
        val pairSelected = if choosePair then pairFirst else pairSecond
        RiftRegion.prependRegionList(
          region,
          pairList,
          new PairNode(pairSelected)
        )

        val expectedSome = if chooseSome then 40 else 41
        val expectedOption = if chooseOption then 10 else 11
        val expectedPair = if choosePair then 3 else 7
        RiftRegion.regionListHead(region, someList).option.get.value +
          RiftRegion.regionListHead(region, optionList).option.get.value +
          RiftRegion.regionListHead(region, pairList).pair._1.value +
          RiftRegion.regionListHead(region, pairList).pair._2.value -
          expectedSome - expectedOption - expectedPair + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected selected nested RegionList synthetic factories, payloads, and nodes to be region allocated, observed $delta region objects",
        delta >= 17L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersRegionListEitherFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class EitherNode(
            val either: Either[
              RiftCheckedLeaf^{region},
              RiftCheckedLeaf^{region}
            ]^{region}
        ) extends RiftRegion.RegionListNode
        val selectedList = RiftRegion.regionList[EitherNode]()
        val branchList = RiftRegion.regionList[EitherNode]()
        val matchList = RiftRegion.regionList[EitherNode]()

        val chooseSelected = (System.identityHashCode(region) & 1) == 0
        val selectedFirst: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} = Left(new RiftCheckedLeaf(40))
        val selectedSecond: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} = Right(new RiftCheckedLeaf(41))
        val selected =
          if chooseSelected then selectedFirst else selectedSecond
        RiftRegion.prependRegionList(
          region,
          selectedList,
          new EitherNode(selected)
        )

        val chooseBranch = (System.identityHashCode(selectedList) & 1) == 0
        RiftRegion.prependRegionList(
          region,
          branchList,
          if chooseBranch then new EitherNode(Left(new RiftCheckedLeaf(10)))
          else new EitherNode(Right(new RiftCheckedLeaf(11)))
        )

        val selector = System.identityHashCode(branchList) & 1
        RiftRegion.prependRegionList(
          region,
          matchList,
          selector match
            case 0 => new EitherNode(Left(new RiftCheckedLeaf(1)))
            case _ => new EitherNode(Right(new RiftCheckedLeaf(2)))
        )

        def eitherValue(
            either: Either[
              RiftCheckedLeaf^{region},
              RiftCheckedLeaf^{region}
            ]^{region}
        ): Int =
          either match
            case Left(value)  => value.value
            case Right(value) => value.value

        val expectedSelected = if chooseSelected then 40 else 41
        val expectedBranch = if chooseBranch then 10 else 11
        val expectedMatch = if selector == 0 then 1 else 2
        eitherValue(RiftRegion.regionListHead(region, selectedList).either) +
          eitherValue(RiftRegion.regionListHead(region, branchList).either) +
          eitherValue(RiftRegion.regionListHead(region, matchList).either) -
          expectedSelected - expectedBranch - expectedMatch + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected RegionList selected and branch/match Either factories, payloads, and nodes to be region allocated, observed $delta region objects",
        delta >= 10L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersOptionSomeLocalPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val leaf: RiftCheckedListNode^{region} =
          new RiftCheckedListNode(40)
        val option: Some[RiftCheckedListNode^{region}]^{region} = Some(leaf)
        val identity = System.identityHashCode(option)

        option.value.value + 2 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected leaf and Some to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersOptionSomeInlineNewArgumentPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val option: Some[RiftCheckedListNode^{region}]^{region} =
          Some(new RiftCheckedListNode(40))
        val identity = System.identityHashCode(option)

        option.value.value + 2 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inline leaf and Some to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersOptionSupertypeSomePlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val option: Option[RiftCheckedListNode^{region}]^{region} =
          Some(new RiftCheckedListNode(40))
        val identity = System.identityHashCode(option)

        option.get.value + 2 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected Option-supertype Some and value to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionAcceptsOptionNoneWithoutRegionAllocation(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val option: Option[RiftCheckedListNode^{region}]^{region} = None

        if (option.isEmpty) 42 else 0
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertEquals(
        "None is a static empty option and should not allocate region objects",
        0L,
        after - before
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersOptionSomeOrNonePlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def maybe(include: Boolean)(
            using r: RiftRegion.ScopedRegion^
        ): Option[RiftCheckedListNode^{r}]^{r} =
          if (include)
            Some(new RiftCheckedListNode(40))
          else
            None

        val present = maybe(true)(using region)
        val absent = maybe(false)(using region)
        val identity = System.identityHashCode(present)

        present.get.value + (if (absent.isEmpty) 2 else 0) + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected Some and payload to be region allocated while None allocates no region object, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersOptionApplyInlineNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val option: Option[RiftCheckedListNode^{region}]^{region} =
          Option(new RiftCheckedListNode(40))
        val identity = System.identityHashCode(option)

        option.get.value + 2 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected Option.apply Some branch and payload to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionOptionApplyNullAllocatesNoRegionObject(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val option: Option[RiftCheckedListNode^{region}]^{region} =
          Option(null)

        if (option.isEmpty) 42 else 0
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertEquals(
        "Option(null) lowers to None and should not allocate a region object",
        0L,
        after - before
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodReturnedOptionApplyPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make(using r: RiftRegion.ScopedRegion^)
            : Option[RiftCheckedListNode^{r}]^{r} =
          Option(new RiftCheckedListNode(40))

        val option = make(using region)
        val identity = System.identityHashCode(option)

        option.get.value + 2 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned Option.apply Some branch and payload to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersTuple2LocalPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val left: RiftCheckedListNode^{region} =
          new RiftCheckedListNode(40)
        val right: RiftCheckedListNode^{region} =
          new RiftCheckedListNode(2)
        val pair: Tuple2[
          RiftCheckedListNode^{region},
          RiftCheckedListNode^{region}
        ]^{region} =
          Tuple2(left, right)
        val identity = System.identityHashCode(pair)

        pair._1.value + pair._2.value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected Tuple2 and both leaves to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersTuple2InlineNewArgumentPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val pair: Tuple2[
          RiftCheckedListNode^{region},
          RiftCheckedListNode^{region}
        ]^{region} =
          Tuple2(
            new RiftCheckedListNode(40),
            new RiftCheckedListNode(2)
          )
        val identity = System.identityHashCode(pair)

        pair._1.value + pair._2.value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inline Tuple2 and both leaves to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersTupleLiteralInlineNewArgumentPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val pair: Tuple2[
          RiftCheckedListNode^{region},
          RiftCheckedListNode^{region}
        ]^{region} =
          (
            new RiftCheckedListNode(40),
            new RiftCheckedListNode(2)
          )
        val identity = System.identityHashCode(pair)

        pair._1.value + pair._2.value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected tuple literal and both leaves to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersTuple3InlineNewArgumentPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val triple: Tuple3[
          RiftCheckedListNode^{region},
          RiftCheckedListNode^{region},
          RiftCheckedListNode^{region}
        ]^{region} =
          Tuple3(
            new RiftCheckedListNode(20),
            new RiftCheckedListNode(20),
            new RiftCheckedListNode(2)
          )
        val identity = System.identityHashCode(triple)

        triple._1.value + triple._2.value + triple._3.value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected Tuple3 and three leaves to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPolymorphicCellPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class Cell[A](val value: A)

        val leaf: RiftCheckedListNode^{region} =
          new RiftCheckedListNode(40)
        val cell: Cell[RiftCheckedListNode^{region}]^{region} =
          new Cell[RiftCheckedListNode^{region}](leaf)
        val identity = System.identityHashCode(cell)

        cell.value.value + 2 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected polymorphic Cell and leaf to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersDirectConstructorInlineNewArgumentPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class Wrapper(val node: RiftCheckedListNode^{region})
        val wrapper: Wrapper^{region} =
          new Wrapper(new RiftCheckedListNode(40))
        val identity = System.identityHashCode(wrapper)

        wrapper.node.value + 2 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected wrapper and inline leaf to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def openHandleInfersAnnotatedLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.epochOpenHandle {
        region ?=>
          final class Node(val value: Int, val next: Node^{region})
          val first: Node^{region} = new Node(40, null)
          val second: Node^{region} = new Node(2, first)
          val identity = System.identityHashCode(second)

          second.value + second.next.value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected open-handle inferred locals to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def openHandleNoZeroSkipsDefinitelyInitializedInferredRecord()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val beforeSkipped =
        rawSizeToLong(RiftAllocator.Impl.statsAllocZeroSkippedTotal())
      val beforeZeroed =
        rawSizeToLong(RiftAllocator.Impl.statsAllocZeroObjectTotal())
      val total = RiftRegion.epochOpenHandle {
        region ?=>
          final class Metadata(val salt: Int)
          final class Record(
              val value: Int,
              val metadata: Metadata^{region},
              var mutable: Int
          )

          val metadata: Metadata^{region} = new Metadata(2)
          val record: Record^{region} =
            new Record(39, metadata, 0)
          record.mutable += 1
          val identity =
            System.identityHashCode(metadata) ^
              System.identityHashCode(record)
          record.value + record.metadata.salt + record.mutable +
            (identity & 0)
      }
      val afterSkipped =
        rawSizeToLong(RiftAllocator.Impl.statsAllocZeroSkippedTotal())
      val afterZeroed =
        rawSizeToLong(RiftAllocator.Impl.statsAllocZeroObjectTotal())
      val skipped = afterSkipped - beforeSkipped
      val zeroed = afterZeroed - beforeZeroed

      assertEquals(42, total)
      assertTrue(
        s"expected definite-init open-handle records to skip zeroing, observed $skipped zero-skipped objects",
        skipped >= 2L
      )
      assertEquals(
        "definite-init open-handle record path should not zero these objects",
        0L,
        zeroed
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def resetOpenHandleInfersAnnotatedLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.streamingOpenHandle {
        RiftRegion.resetOpenHandle {
          region ?=>
            final class Node(val value: Int, val next: Node^{region})
            val first: Node^{region} = new Node(40, null)
            val second: Node^{region} = new Node(2, first)
            val identity = System.identityHashCode(second)

            second.value + second.next.value + (identity & 0)
        }
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected reset-open-handle inferred locals to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def resetOpenHandleInlineInfersAnnotatedLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.streamingOpenHandle {
        RiftRegion.resetOpenHandleInline {
          region ?=>
            final class Node(val value: Int, val next: Node^{region})
            val first: Node^{region} = new Node(40, null)
            val second: Node^{region} = new Node(2, first)
            val identity = System.identityHashCode(second)

            second.value + second.next.value + (identity & 0)
        }
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inline reset-open-handle inferred locals to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def resetOpenHandleInlineInfersRegionArrayPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.streamingOpenHandle {
        RiftRegion.resetOpenHandleInline {
          region ?=>
            final class Entry(val key: Int) {
              var next: Entry^{region} = null
            }
            val entries: Array[Entry^{region}]^{region} =
              RiftAllocator.allocateOpenHandle(
                region,
                new Array[Entry^{region}](8)
              )
            val first: Entry^{region} = new Entry(40)
            val second: Entry^{region} = new Entry(2)
            second.next = first
            entries(0) = second
            val identity = System.identityHashCode(entries)

            entries(0).key + entries(0).next.key + (identity & 0)
        }
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inline reset-open-handle array body to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def resetOpenHandleInlineInfersDirectRegionArrayPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.streamingOpenHandle {
        RiftRegion.resetOpenHandleInline {
          region ?=>
            final class Entry(val key: Int) {
              var next: Entry^{region} = null
            }
            val entries: Array[Entry^{region}]^{region} =
              new Array[Entry^{region}](8)
            val counts: Array[Int]^{region} =
              new Array[Int](8)
            val first: Entry^{region} = new Entry(1)
            val second: Entry^{region} = new Entry(2)
            second.next = first
            entries(0) = second
            counts(0) = 39
            val identity =
              System.identityHashCode(entries) ^
                System.identityHashCode(counts) ^
                System.identityHashCode(second)

            entries(0).key + entries(0).next.key + counts(0) +
              (identity & 0)
        }
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inline reset-open-handle direct arrays and entries to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def inferredScopedBranchNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class Entry(val key: Int)
        val entry: Entry^{region} =
          if (before >= 0L) new Entry(40)
          else new Entry(2)
        val identity = System.identityHashCode(entry)

        entry.key + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(40, total)
      assertTrue(
        s"expected branch-returned inferred local to be region allocated, observed $delta region objects",
        delta >= 1L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def inferredScopedMatchNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class Entry(val key: Int)
        val selector = (before & 1L).toInt
        val entry: Entry^{region} =
          selector match {
            case 0 => new Entry(41)
            case _ => new Entry(1)
          }
        val identity = System.identityHashCode(entry)

        entry.key + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(41, total)
      assertTrue(
        s"expected match-returned inferred local to be region allocated, observed $delta region objects",
        delta >= 1L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def resetOpenHandleInlineInfersMixedRuntimeBranchPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.streamingOpenHandle {
        RiftRegion.resetOpenHandleInline {
          region ?=>
            final class Entry(val key: Int)
            val entries: Array[Entry^{region}]^{region} =
              RiftAllocator.allocateOpenHandle(
                region,
                new Array[Entry^{region}](8)
              )
            val first: Entry^{region} =
              if (before >= 0L) new Entry(42)
              else RiftAllocator.allocateOpenHandle(region, new Entry(1))
            entries(0) = first
            val identity = System.identityHashCode(entries)

            entries(0).key + (identity & 0)
        }
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected mixed-branch inline reset body to region allocate array and selected entry, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersCapturedValDefLocalBlockNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val node =
          val value = 40
          new RiftCheckedListNode(value)
        val captured: RiftCheckedListNode^{region} = node
        val list = RiftRegion.regionList[RiftCheckedListNode]()
        RiftRegion.prependRegionList(region, list, captured)
        RiftRegion.regionListHead(region, list).value + 2
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 1L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersAnnotatedLocalBlockNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val node: RiftCheckedListNode^{region} =
          val value = 40
          new RiftCheckedListNode(value)
        val list = RiftRegion.regionList[RiftCheckedListNode]()
        RiftRegion.prependRegionList(region, list, node)
        RiftRegion.regionListHead(region, list).value + 2
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 1L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersCapturedAssignmentLocalBlockNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        var captured: RiftCheckedListNode^{region} = null
        captured =
          val value = 40
          new RiftCheckedListNode(value)
        val list = RiftRegion.regionList[RiftCheckedListNode]()
        RiftRegion.prependRegionList(region, list, captured)
        RiftRegion.regionListHead(region, list).value + 2
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 1L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLocalMethodReturnNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        def make(
            value: Int
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          new RiftCheckedListNode(value)
        val node = make(40)(using region)
        val list = RiftRegion.regionList[RiftCheckedListNode]()
        RiftRegion.prependRegionList(region, list, node)
        RiftRegion.regionListHead(region, list).value + 2
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 1L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineMethodArgumentNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def consume(node: RiftCheckedListNode^{region}): Int =
          node.value + (System.identityHashCode(node) & 0)
        consume(new RiftCheckedListNode(40)) + 2
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inline method argument value to be region allocated, observed $delta region objects",
        delta >= 1L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineRegionParamMethodArgumentNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def consume(
            using r: RiftRegion.ScopedRegion^
        )(
            node: RiftCheckedListNode^{r}
        ): Int =
          node.value + (System.identityHashCode(node) & 0)
        consume(using region)(new RiftCheckedListNode(40)) + 2
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inline region-param method argument value to be region allocated, observed $delta region objects",
        delta >= 1L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineRegionParamMethodArgumentArrayPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def consume(
            using r: RiftRegion.ScopedRegion^
        )(
            leaves: Array[RiftCheckedLeaf^{r}]^{r}
        ): Int =
          leaves(0) = new RiftCheckedLeaf(20)
          leaves(1) = new RiftCheckedLeaf(22)
          val identity =
            System.identityHashCode(leaves) ^
              System.identityHashCode(leaves(0)) ^
              System.identityHashCode(leaves(1))
          leaves(0).value + leaves(1).value + (identity & 0)

        consume(using region)(new Array[RiftCheckedLeaf^{region}](2))
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inline region-param array argument and stored values to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineRegionParamMethodArgumentClosurePlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def consume(
            using r: RiftRegion.ScopedRegion^
        )(
            add: Function1[Int, Int]^{r}
        ): Int =
          invokeRegionFunction(add, 2)

        consume(using region)((n: Int) => n + 40)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inline region-param closure argument to be region allocated, observed $delta region objects",
        delta >= 1L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineRegionParamMethodArgumentSomePlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def consume(
            using r: RiftRegion.ScopedRegion^
        )(
            option: Option[RiftCheckedListNode^{r}]^{r}
        ): Int =
          option.get.value + (System.identityHashCode(option) & 0)

        consume(using region)(Some(new RiftCheckedListNode(40))) + 2
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inline region-param Some argument and payload to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineRegionParamMethodArgumentOptionApplyPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def consume(
            using r: RiftRegion.ScopedRegion^
        )(
            option: Option[RiftCheckedListNode^{r}]^{r}
        ): Int =
          option.get.value + (System.identityHashCode(option) & 0)

        consume(using region)(Option(new RiftCheckedListNode(40))) + 2
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inline region-param Option.apply argument and payload to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineRegionParamMethodArgumentEitherPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def consume(
            using r: RiftRegion.ScopedRegion^
        )(
            either: Either[RiftCheckedListNode^{r}, RiftCheckedListNode^{r}]^{r}
        ): Int =
          val leaf = either match
            case Left(value)  => value
            case Right(value) => value
          leaf.value +
            (System.identityHashCode(either) & 0) +
            (System.identityHashCode(leaf) & 0)

        consume(using region)(Left(new RiftCheckedListNode(40))) + 2
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inline region-param Either argument and payload to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineRegionParamMethodArgumentTuple2Placement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def consume(
            using r: RiftRegion.ScopedRegion^
        )(
            pair: Tuple2[RiftCheckedListNode^{r}, RiftCheckedListNode^{r}]^{r}
        ): Int =
          pair._1.value + pair._2.value +
            (System.identityHashCode(pair) & 0)

        consume(using region)(
          Tuple2(new RiftCheckedListNode(40), new RiftCheckedListNode(2))
        )
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inline region-param Tuple2 argument and payloads to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineRegionParamMethodArgumentGenericCellPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class Cell[A](val value: A)
        def consume(
            using r: RiftRegion.ScopedRegion^
        )(
            cell: Cell[RiftCheckedListNode^{r}]^{r}
        ): Int =
          cell.value.value + (System.identityHashCode(cell) & 0)

        consume(using region)(new Cell(new RiftCheckedListNode(40))) + 2
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inline region-param generic Cell argument and payload to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlinePolymorphicMethodArgumentGenericCellPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class Cell[A](val value: A)
        def consume[A](
            using r: RiftRegion.ScopedRegion^
        )(
            cell: Cell[A^{r}]^{r}
        ): Cell[A^{r}]^{r} =
          cell

        val cell =
          consume[RiftCheckedListNode](using region)(
            new Cell(new RiftCheckedListNode(40))
          )
        cell.value.value + 2 +
          (System.identityHashCode(cell) & 0) +
          (System.identityHashCode(cell.value) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected polymorphic inline region-param Cell argument and payload to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersSelectedPolymorphicMethodArgumentGenericCellPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class Cell[A](val value: A)
        def consume[A](
            using r: RiftRegion.ScopedRegion^
        )(
            cell: Cell[A^{r}]^{r}
        ): Cell[A^{r}]^{r} =
          cell

        val chooseFirst = (System.identityHashCode(region) & 1) == 0
        val first: Cell[RiftCheckedListNode^{region}]^{region} =
          new Cell[RiftCheckedListNode^{region}](
            new RiftCheckedListNode(40)
          )
        val second: Cell[RiftCheckedListNode^{region}]^{region} =
          new Cell[RiftCheckedListNode^{region}](
            new RiftCheckedListNode(41)
          )
        val selected = if chooseFirst then first else second
        val cell = consume[RiftCheckedListNode](using region)(selected)
        val expected = if chooseFirst then 40 else 41
        cell.value.value - expected + 42 +
          (System.identityHashCode(cell) & 0) +
          (System.identityHashCode(cell.value) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected selected polymorphic method argument Cell candidates and payloads to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPolymorphicMethodReturnedGenericCellPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class Cell[A](val value: A)
        def make[A](
            using r: RiftRegion.ScopedRegion^
        )(
            value: A^{r}
        ): Cell[A^{r}]^{r} =
          new Cell[A^{r}](value)

        val cell =
          make[RiftCheckedListNode](using region)(
            new RiftCheckedListNode(40)
          )
        cell.value.value + 2 +
          (System.identityHashCode(cell) & 0) +
          (System.identityHashCode(cell.value) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected polymorphic method-returned Cell and payload to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedPolymorphicMethodReturnedGenericCellPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class Cell[A](val value: A)
        def make[A](
            using r: RiftRegion.ScopedRegion^
        )(
            value: A^{r}
        ): Cell[A^{r}]^{r} =
          new Cell[A^{r}](value)

        def wrap[A](
            using r: RiftRegion.ScopedRegion^
        )(
            value: A^{r}
        ): Cell[A^{r}]^{r} =
          make[A](using r)(value)

        val cell =
          wrap[RiftCheckedListNode](using region)(
            new RiftCheckedListNode(40)
          )
        cell.value.value + 2 +
          (System.identityHashCode(cell) & 0) +
          (System.identityHashCode(cell.value) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected forwarded polymorphic method-returned Cell and payload to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPolymorphicMethodReturnedOptionFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make[A](
            using r: RiftRegion.ScopedRegion^
        )(
            value: A^{r}
        ): Option[A^{r}]^{r} =
          Some(value)

        val option =
          make[RiftCheckedListNode](using region)(
            new RiftCheckedListNode(40)
          )
        option.get.value + 2 +
          (System.identityHashCode(option) & 0) +
          (System.identityHashCode(option.get) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected polymorphic method-returned Option/Some and payload to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPolymorphicMethodReturnedTuple2FactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make[A, B](
            using r: RiftRegion.ScopedRegion^
        )(
            left: A^{r},
            right: B^{r}
        ): Tuple2[A^{r}, B^{r}]^{r} =
          Tuple2(left, right)

        val pair =
          make[RiftCheckedListNode, RiftCheckedListNode](using region)(
            new RiftCheckedListNode(40),
            new RiftCheckedListNode(2)
          )
        pair._1.value + pair._2.value +
          (System.identityHashCode(pair) & 0) +
          (System.identityHashCode(pair._1) & 0) +
          (System.identityHashCode(pair._2) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected polymorphic method-returned Tuple2 and payloads to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPolymorphicMethodReturnedOptionApplyFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make[A](
            using r: RiftRegion.ScopedRegion^
        )(
            value: A^{r}
        ): Option[A^{r}]^{r} =
          Option(value)

        val option =
          make[RiftCheckedListNode](using region)(
            new RiftCheckedListNode(40)
          )
        option.get.value + 2 +
          (System.identityHashCode(option) & 0) +
          (System.identityHashCode(option.get) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected polymorphic method-returned Option.apply Some branch and payload to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersBranchForwardedPolymorphicOptionApplyFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make[A](
            using r: RiftRegion.ScopedRegion^
        )(
            value: A^{r}
        ): Option[A^{r}]^{r} =
          Option(value)

        def branch[A](flag: Boolean)(
            using r: RiftRegion.ScopedRegion^
        )(
            value: A^{r}
        ): Option[A^{r}]^{r} =
          if flag then make[A](using r)(value)
          else make[A](using r)(value)

        def matched[A](selector: Int)(
            using r: RiftRegion.ScopedRegion^
        )(
            value: A^{r}
        ): Option[A^{r}]^{r} =
          selector match
            case 0 => make[A](using r)(value)
            case _ => make[A](using r)(value)

        val left =
          branch[RiftCheckedListNode](true)(using region)(
            new RiftCheckedListNode(20)
          )
        val right =
          matched[RiftCheckedListNode](0)(using region)(
            new RiftCheckedListNode(22)
          )
        left.get.value + right.get.value +
          (System.identityHashCode(left) & 0) +
          (System.identityHashCode(right) & 0) +
          (System.identityHashCode(left.get) & 0) +
          (System.identityHashCode(right.get) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch/match forwarded polymorphic Option.apply results and payloads to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPolymorphicMethodReturnedEitherFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make[A, B](chooseLeft: Boolean)(
            using r: RiftRegion.ScopedRegion^
        )(
            left: A^{r},
            right: B^{r}
        ): Either[A^{r}, B^{r}]^{r} =
          if chooseLeft then Left(left) else Right(right)

        val either =
          make[RiftCheckedListNode, RiftCheckedListNode](true)(
            using region
          )(
            new RiftCheckedListNode(40),
            new RiftCheckedListNode(2)
          )
        val value = either match
          case Left(node)  => node.value
          case Right(node) => node.value
        value + 2 +
          (System.identityHashCode(either) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected polymorphic method-returned Either case and payloads to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersBranchForwardedPolymorphicEitherFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make[A, B](chooseLeft: Boolean)(
            using r: RiftRegion.ScopedRegion^
        )(
            left: A^{r},
            right: B^{r}
        ): Either[A^{r}, B^{r}]^{r} =
          if chooseLeft then Left(left) else Right(right)

        def branch[A, B](flag: Boolean)(
            using r: RiftRegion.ScopedRegion^
        )(
            left: A^{r},
            right: B^{r}
        ): Either[A^{r}, B^{r}]^{r} =
          if flag then make[A, B](true)(using r)(left, right)
          else make[A, B](false)(using r)(left, right)

        def matched[A, B](selector: Int)(
            using r: RiftRegion.ScopedRegion^
        )(
            left: A^{r},
            right: B^{r}
        ): Either[A^{r}, B^{r}]^{r} =
          selector match
            case 0 => make[A, B](false)(using r)(left, right)
            case _ => make[A, B](true)(using r)(left, right)

        val left =
          branch[RiftCheckedListNode, RiftCheckedListNode](true)(
            using region
          )(
            new RiftCheckedListNode(20),
            new RiftCheckedListNode(0)
          )
        val right =
          matched[RiftCheckedListNode, RiftCheckedListNode](0)(
            using region
          )(
            new RiftCheckedListNode(0),
            new RiftCheckedListNode(22)
          )
        val leftValue = left match
          case Left(node)  => node.value
          case Right(node) => node.value
        val rightValue = right match
          case Left(node)  => node.value
          case Right(node) => node.value
        leftValue + rightValue +
          (System.identityHashCode(left) & 0) +
          (System.identityHashCode(right) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch/match forwarded polymorphic Either cases and payloads to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersBranchForwardedPolymorphicTuple2FactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make[A, B](
            using r: RiftRegion.ScopedRegion^
        )(
            left: A^{r},
            right: B^{r}
        ): Tuple2[A^{r}, B^{r}]^{r} =
          Tuple2(left, right)

        def branch[A, B](flag: Boolean)(
            using r: RiftRegion.ScopedRegion^
        )(
            left: A^{r},
            right: B^{r}
        ): Tuple2[A^{r}, B^{r}]^{r} =
          if flag then make[A, B](using r)(left, right)
          else make[A, B](using r)(left, right)

        def matched[A, B](selector: Int)(
            using r: RiftRegion.ScopedRegion^
        )(
            left: A^{r},
            right: B^{r}
        ): Tuple2[A^{r}, B^{r}]^{r} =
          selector match
            case 0 => make[A, B](using r)(left, right)
            case _ => make[A, B](using r)(left, right)

        val first =
          branch[RiftCheckedListNode, RiftCheckedListNode](true)(
            using region
          )(new RiftCheckedListNode(20), new RiftCheckedListNode(1))
        val second =
          matched[RiftCheckedListNode, RiftCheckedListNode](0)(using region)(
            new RiftCheckedListNode(20),
            new RiftCheckedListNode(1)
          )
        first._1.value + first._2.value +
          second._1.value + second._2.value +
          (System.identityHashCode(first) & 0) +
          (System.identityHashCode(second) & 0) +
          (System.identityHashCode(first._1) & 0) +
          (System.identityHashCode(first._2) & 0) +
          (System.identityHashCode(second._1) & 0) +
          (System.identityHashCode(second._2) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch/match forwarded polymorphic Tuple2 results and payloads to be region allocated, observed $delta region objects",
        delta >= 6L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLocalMethodSomeFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make(
            value: Int
        )(using r: RiftRegion.ScopedRegion^)
            : Some[RiftCheckedListNode^{r}]^{r} =
          Some(new RiftCheckedListNode(value))
        val option = make(40)(using region)
        val identity = System.identityHashCode(option)
        option.value.value + 2 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned Some and value to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLocalMethodOptionSupertypeSomeFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make(
            value: Int
        )(using r: RiftRegion.ScopedRegion^)
            : Option[RiftCheckedListNode^{r}]^{r} =
          Some(new RiftCheckedListNode(value))
        val option = make(40)(using region)
        val identity = System.identityHashCode(option)
        option.get.value + 2 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned Option-supertype Some and value to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLocalMethodReturnedLocalOptionSomeFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make(
            value: Int
        )(using r: RiftRegion.ScopedRegion^)
            : Option[RiftCheckedListNode^{r}]^{r} =
          val option: Option[RiftCheckedListNode^{r}]^{r} =
            Some(new RiftCheckedListNode(value))
          option
        val option = make(40)(using region)
        val identity = System.identityHashCode(option)
        option.get.value + 2 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected returned-local Option-supertype Some and value to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedOptionSomeFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make(
            value: Int
        )(using r: RiftRegion.ScopedRegion^)
            : Option[RiftCheckedListNode^{r}]^{r} =
          Some(new RiftCheckedListNode(value))

        def branch(
            flag: Boolean
        )(using r: RiftRegion.ScopedRegion^)
            : Option[RiftCheckedListNode^{r}]^{r} =
          if flag then make(20)(using r) else make(0)(using r)

        def matched(
            selector: Int
        )(using r: RiftRegion.ScopedRegion^)
            : Option[RiftCheckedListNode^{r}]^{r} =
          selector match
            case 0 => make(22)(using r)
            case _ => make(0)(using r)

        val left = branch(true)(using region)
        val right = matched(0)(using region)
        val identity =
          System.identityHashCode(left) ^
            System.identityHashCode(right) ^
            System.identityHashCode(left.get) ^
            System.identityHashCode(right.get)
        left.get.value + right.get.value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch/match forwarded Option-supertype Some values to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLocalMethodEitherFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make(
            value: Int,
            leftBranch: Boolean
        )(using r: RiftRegion.ScopedRegion^)
            : Either[RiftCheckedListNode^{r}, RiftCheckedListNode^{r}]^{r} =
          if leftBranch then Left(new RiftCheckedListNode(value))
          else Right(new RiftCheckedListNode(value + 1))
        val either = make(40, true)(using region)
        val identity = System.identityHashCode(either)
        val value = either match
          case Left(node)  => node.value
          case Right(node) => node.value - 1
        value + 2 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned Either and value to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedEitherFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make(
            value: Int
        )(using r: RiftRegion.ScopedRegion^)
            : Either[RiftCheckedListNode^{r}, RiftCheckedListNode^{r}]^{r} =
          if value >= 0 then Left(new RiftCheckedListNode(value))
          else Right(new RiftCheckedListNode(0))

        def branch(
            flag: Boolean
        )(using r: RiftRegion.ScopedRegion^)
            : Either[RiftCheckedListNode^{r}, RiftCheckedListNode^{r}]^{r} =
          if flag then make(20)(using r) else make(-1)(using r)

        def matched(
            selector: Int
        )(using r: RiftRegion.ScopedRegion^)
            : Either[RiftCheckedListNode^{r}, RiftCheckedListNode^{r}]^{r} =
          selector match
            case 0 => make(22)(using r)
            case _ => make(-1)(using r)

        val left = branch(true)(using region)
        val right = matched(0)(using region)
        val leftValue = left match
          case Left(node)  => node.value
          case Right(node) => node.value
        val rightValue = right match
          case Left(node)  => node.value
          case Right(node) => node.value
        val identity =
          System.identityHashCode(left) ^
            System.identityHashCode(right)
        leftValue + rightValue + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch/match forwarded Either values to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLocalMethodTuple2FactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make(using r: RiftRegion.ScopedRegion^)
            : Tuple2[
              RiftCheckedListNode^{r},
              RiftCheckedListNode^{r}
            ]^{r} =
          Tuple2(
            new RiftCheckedListNode(40),
            new RiftCheckedListNode(2)
          )
        val pair = make(using region)
        val identity = System.identityHashCode(pair)
        pair._1.value + pair._2.value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned Tuple2 and values to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLocalMethodReturnedLocalTuple2FactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make(using r: RiftRegion.ScopedRegion^)
            : Tuple2[
              RiftCheckedListNode^{r},
              RiftCheckedListNode^{r}
            ]^{r} =
          val pair: Tuple2[
            RiftCheckedListNode^{r},
            RiftCheckedListNode^{r}
          ]^{r} =
            Tuple2(
              new RiftCheckedListNode(40),
              new RiftCheckedListNode(2)
            )
          pair
        val pair = make(using region)
        val identity = System.identityHashCode(pair)
        pair._1.value + pair._2.value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected returned-local Tuple2 and values to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedTuple2FactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make(
            left: Int,
            right: Int
        )(using r: RiftRegion.ScopedRegion^)
            : Tuple2[
              RiftCheckedListNode^{r},
              RiftCheckedListNode^{r}
            ]^{r} =
          Tuple2(
            new RiftCheckedListNode(left),
            new RiftCheckedListNode(right)
          )

        def branch(
            flag: Boolean
        )(using r: RiftRegion.ScopedRegion^)
            : Tuple2[
              RiftCheckedListNode^{r},
              RiftCheckedListNode^{r}
            ]^{r} =
          if flag then make(10, 11)(using r) else make(0, 0)(using r)

        def matched(
            selector: Int
        )(using r: RiftRegion.ScopedRegion^)
            : Tuple2[
              RiftCheckedListNode^{r},
              RiftCheckedListNode^{r}
            ]^{r} =
          selector match
            case 0 => make(9, 12)(using r)
            case _ => make(0, 0)(using r)

        val left = branch(true)(using region)
        val right = matched(0)(using region)
        val identity =
          System.identityHashCode(left) ^
            System.identityHashCode(right) ^
            System.identityHashCode(left._1) ^
            System.identityHashCode(left._2) ^
            System.identityHashCode(right._1) ^
            System.identityHashCode(right._2)
        left._1.value + left._2.value + right._1.value + right._2.value +
          (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch/match forwarded Tuple2 values to be region allocated, observed $delta region objects",
        delta >= 6L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLocalMethodTupleLiteralPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make(using r: RiftRegion.ScopedRegion^)
            : Tuple2[
              RiftCheckedListNode^{r},
              RiftCheckedListNode^{r}
            ]^{r} =
          (
            new RiftCheckedListNode(40),
            new RiftCheckedListNode(2)
          )
        val pair = make(using region)
        val identity = System.identityHashCode(pair)
        pair._1.value + pair._2.value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned tuple literal and values to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLocalMethodPolymorphicCellFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class Cell[A](val value: A)

        def make(
            value: Int
        )(using r: RiftRegion.ScopedRegion^)
            : Cell[RiftCheckedListNode^{r}]^{r} =
          val leaf: RiftCheckedListNode^{r} =
            new RiftCheckedListNode(value)
          new Cell[RiftCheckedListNode^{r}](leaf)

        val cell = make(40)(using region)
        val identity = System.identityHashCode(cell)
        cell.value.value + 2 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned polymorphic Cell and value to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLocalMethodReturnedLocalPolymorphicCellFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class Cell[A](val value: A)

        def make(
            value: Int
        )(using r: RiftRegion.ScopedRegion^)
            : Cell[RiftCheckedListNode^{r}]^{r} =
          val leaf: RiftCheckedListNode^{r} =
            new RiftCheckedListNode(value)
          val cell: Cell[RiftCheckedListNode^{r}]^{r} =
            new Cell[RiftCheckedListNode^{r}](leaf)
          cell

        val cell = make(40)(using region)
        val identity = System.identityHashCode(cell)
        cell.value.value + 2 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected returned-local polymorphic Cell and value to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedPolymorphicCellFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class Cell[A](val value: A)

        def make(
            value: Int
        )(using r: RiftRegion.ScopedRegion^)
            : Cell[RiftCheckedListNode^{r}]^{r} =
          val leaf: RiftCheckedListNode^{r} =
            new RiftCheckedListNode(value)
          new Cell[RiftCheckedListNode^{r}](leaf)

        def branch(
            flag: Boolean
        )(using r: RiftRegion.ScopedRegion^)
            : Cell[RiftCheckedListNode^{r}]^{r} =
          if flag then make(20)(using r) else make(0)(using r)

        def matched(
            selector: Int
        )(using r: RiftRegion.ScopedRegion^)
            : Cell[RiftCheckedListNode^{r}]^{r} =
          selector match
            case 0 => make(22)(using r)
            case _ => make(0)(using r)

        val left = branch(true)(using region)
        val right = matched(0)(using region)
        val identity =
          System.identityHashCode(left) ^
            System.identityHashCode(right) ^
            System.identityHashCode(left.value) ^
            System.identityHashCode(right.value)
        left.value.value + right.value.value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch/match forwarded polymorphic Cell values to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersObjectBufferLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val buffer = RiftRegion.objectBuffer[RiftCheckedLeaf](2)
        val left = new RiftCheckedLeaf(20)
        val right = new RiftCheckedLeaf(22)
        RiftRegion.append(region, buffer, left)
        region.append(buffer, right)
        RiftRegion.get(region, buffer, 0).value +
          region.get(buffer, 1).value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersObjectBufferSelectedLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val buffer = RiftRegion.objectBuffer[RiftCheckedLeaf](1)
        val first = new RiftCheckedLeaf(40)
        val second = new RiftCheckedLeaf(42)
        val keep =
          System.identityHashCode(first) + System.identityHashCode(second)
        val selected = if ((keep & 1) == 0) first else second
        val expected = if ((keep & 1) == 0) 40 else 42
        RiftRegion.append(region, buffer, selected)
        RiftRegion.get(region, buffer, 0).value - expected + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, sum)
      assertTrue(
        s"expected selected ObjectBuffer candidate allocations to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersObjectBufferInlineClosurePlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val buffer =
          RiftRegion.objectBuffer[Function1[Int, Int]^{region}](1)
        RiftRegion.append(region, buffer, (n: Int) => n + 40)

        val fn = RiftRegion.get(region, buffer, 0)
        val identity = System.identityHashCode(fn)
        invokeRegionFunction(fn, 2) + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected ObjectBuffer backing array plus inline closure value to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersObjectBufferInlineClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val owner = region
        val buffer =
          RiftRegion.objectBuffer[Function1[Int, RiftCheckedLeaf^{region}]^{region}](1)
        RiftRegion.append(
          region,
          buffer,
          (n: Int) => {
            val keepOwner = System.identityHashCode(owner) & 0
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(n + 40 + keepOwner)
            leaf
          }
        )

        val fn = RiftRegion.get(region, buffer, 0)
        val leaf = fn(2)
        leaf.value +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected ObjectBuffer backing array plus closure value and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersObjectBufferInlineArrayPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val buffer =
          RiftRegion.objectBuffer[Array[RiftCheckedLeaf^{region}]^{region}](1)
        RiftRegion.append(
          region,
          buffer,
          new Array[RiftCheckedLeaf^{region}](2)
        )
        val leaves = RiftRegion.get(region, buffer, 0)
        leaves(0) = new RiftCheckedLeaf(20)
        leaves(1) = new RiftCheckedLeaf(22)

        val identity =
          System.identityHashCode(buffer) ^
            System.identityHashCode(leaves) ^
            System.identityHashCode(leaves(0)) ^
            System.identityHashCode(leaves(1))
        leaves(0).value + leaves(1).value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected ObjectBuffer backing array plus inline array value and stored values to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersRegionBufferInlineArrayPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val buffer =
          RiftRegion.regionBuffer[Array[RiftCheckedLeaf^{region}]^{region}](1)
        region.append(buffer, new Array[RiftCheckedLeaf^{region}](2))
        val leaves = region.get(buffer, 0)
        leaves(0) = new RiftCheckedLeaf(20)
        leaves(1) = new RiftCheckedLeaf(22)

        val identity =
          System.identityHashCode(buffer) ^
            System.identityHashCode(leaves) ^
            System.identityHashCode(leaves(0)) ^
            System.identityHashCode(leaves(1))
        leaves(0).value + leaves(1).value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected RegionBuffer backing array plus inline array value and stored values to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersRegionBufferSelectedClosurePlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val buffer =
          RiftRegion.regionBuffer[Function1[Int, Int]^{region}](1)
        val first = (n: Int) => n + 40
        val second = (n: Int) => n + 41
        val keep =
          System.identityHashCode(first) + System.identityHashCode(second)
        val selected = if ((keep & 1) == 0) first else second
        val expected = if ((keep & 1) == 0) 42 else 43
        region.append(buffer, selected)

        val fn = region.get(buffer, 0)
        val identity =
          System.identityHashCode(first) ^
            System.identityHashCode(second) ^
            System.identityHashCode(fn)
        invokeRegionFunction(fn, 2) - expected + 42 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected RegionBuffer backing array plus selected closure values to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersRegionBufferSelectedClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val owner = region
        val buffer =
          RiftRegion.regionBuffer[Function1[Int, RiftCheckedLeaf^{region}]^{region}](1)
        val first = (n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 40 + keepOwner)
          leaf
        }
        val second = (n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 41 + keepOwner)
          leaf
        }
        val keep =
          System.identityHashCode(first) + System.identityHashCode(second)
        val selected = if ((keep & 1) == 0) first else second
        val expected = if ((keep & 1) == 0) 42 else 43
        region.append(buffer, selected)

        val fn = region.get(buffer, 0)
        val leaf = fn(2)
        val identity =
          System.identityHashCode(first) ^
            System.identityHashCode(second) ^
            System.identityHashCode(fn) ^
            System.identityHashCode(leaf)
        leaf.value - expected + 42 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected RegionBuffer backing array plus selected closure values and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersBufferSelectedLocalSyntheticFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val objectSomeBuffer =
          RiftRegion.objectBuffer[Option[RiftCheckedLeaf^{region}]^{region}](1)
        val objectSomeFirst = Some(new RiftCheckedLeaf(40))
        val objectSomeSecond = Some(new RiftCheckedLeaf(41))
        val objectSomeKeep =
          System.identityHashCode(objectSomeFirst) +
            System.identityHashCode(objectSomeSecond)
        val objectSomeSelected =
          if ((objectSomeKeep & 1) == 0) objectSomeFirst else objectSomeSecond
        RiftRegion.append(region, objectSomeBuffer, objectSomeSelected)

        val objectOptionBuffer =
          RiftRegion.objectBuffer[Option[RiftCheckedLeaf^{region}]^{region}](1)
        val objectOptionFirst = Option(new RiftCheckedLeaf(1))
        val objectOptionSecond = Option(new RiftCheckedLeaf(2))
        val objectOptionKeep =
          System.identityHashCode(objectOptionFirst) +
            System.identityHashCode(objectOptionSecond)
        val objectOptionSelected =
          if ((objectOptionKeep & 1) == 0) objectOptionFirst
          else objectOptionSecond
        RiftRegion.append(region, objectOptionBuffer, objectOptionSelected)

        val objectPairBuffer = RiftRegion.objectBuffer[
          Tuple2[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
        ](1)
        val objectPairFirst =
          Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(1))
        val objectPairSecond =
          Tuple2(new RiftCheckedLeaf(2), new RiftCheckedLeaf(2))
        val objectPairKeep =
          System.identityHashCode(objectPairFirst) +
            System.identityHashCode(objectPairSecond)
        val objectPairSelected =
          if ((objectPairKeep & 1) == 0) objectPairFirst else objectPairSecond
        RiftRegion.append(region, objectPairBuffer, objectPairSelected)

        val regionSomeBuffer =
          RiftRegion.regionBuffer[Option[RiftCheckedLeaf^{region}]^{region}](1)
        val regionSomeFirst = Some(new RiftCheckedLeaf(10))
        val regionSomeSecond = Some(new RiftCheckedLeaf(11))
        val regionSomeKeep =
          System.identityHashCode(regionSomeFirst) +
            System.identityHashCode(regionSomeSecond)
        val regionSomeSelected =
          if ((regionSomeKeep & 1) == 0) regionSomeFirst else regionSomeSecond
        region.append(regionSomeBuffer, regionSomeSelected)

        val regionOptionBuffer =
          RiftRegion.regionBuffer[Option[RiftCheckedLeaf^{region}]^{region}](1)
        val regionOptionFirst = Option(new RiftCheckedLeaf(3))
        val regionOptionSecond = Option(new RiftCheckedLeaf(4))
        val regionOptionKeep =
          System.identityHashCode(regionOptionFirst) +
            System.identityHashCode(regionOptionSecond)
        val regionOptionSelected =
          if ((regionOptionKeep & 1) == 0) regionOptionFirst
          else regionOptionSecond
        region.append(regionOptionBuffer, regionOptionSelected)

        val regionPairBuffer = RiftRegion.regionBuffer[
          Tuple2[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
        ](1)
        val regionPairFirst =
          Tuple2(new RiftCheckedLeaf(3), new RiftCheckedLeaf(3))
        val regionPairSecond =
          Tuple2(new RiftCheckedLeaf(4), new RiftCheckedLeaf(4))
        val regionPairKeep =
          System.identityHashCode(regionPairFirst) +
            System.identityHashCode(regionPairSecond)
        val regionPairSelected =
          if ((regionPairKeep & 1) == 0) regionPairFirst else regionPairSecond
        region.append(regionPairBuffer, regionPairSelected)

        val expectedObjectSome =
          if ((objectSomeKeep & 1) == 0) 40 else 41
        val expectedObjectOption =
          if ((objectOptionKeep & 1) == 0) 1 else 2
        val expectedObjectPair =
          if ((objectPairKeep & 1) == 0) 2 else 4
        val expectedRegionSome =
          if ((regionSomeKeep & 1) == 0) 10 else 11
        val expectedRegionOption =
          if ((regionOptionKeep & 1) == 0) 3 else 4
        val expectedRegionPair =
          if ((regionPairKeep & 1) == 0) 6 else 8

        RiftRegion.get(region, objectSomeBuffer, 0).get.value +
          RiftRegion.get(region, objectOptionBuffer, 0).get.value +
          RiftRegion.get(region, objectPairBuffer, 0)._1.value +
          RiftRegion.get(region, objectPairBuffer, 0)._2.value +
          region.get(regionSomeBuffer, 0).get.value +
          region.get(regionOptionBuffer, 0).get.value +
          region.get(regionPairBuffer, 0)._1.value +
          region.get(regionPairBuffer, 0)._2.value -
          expectedObjectSome - expectedObjectOption - expectedObjectPair -
          expectedRegionSome - expectedRegionOption - expectedRegionPair + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected selected buffer Some/Option/Tuple2 factories and payloads to be region allocated, observed $delta region objects",
        delta >= 28L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersBufferEitherFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val objectSelectedBuffer = RiftRegion.objectBuffer[
          Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
        ](1)
        val objectFirst: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} =
          Left(new RiftCheckedLeaf(40))
        val objectSecond: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} =
          Right(new RiftCheckedLeaf(41))
        val objectKeep =
          System.identityHashCode(objectFirst) +
            System.identityHashCode(objectSecond)
        val objectSelected =
          if ((objectKeep & 1) == 0) objectFirst else objectSecond
        RiftRegion.append(region, objectSelectedBuffer, objectSelected)

        val regionSelectedBuffer = RiftRegion.regionBuffer[
          Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
        ](1)
        val regionFirst: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} =
          Left(new RiftCheckedLeaf(10))
        val regionSecond: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} =
          Right(new RiftCheckedLeaf(11))
        val regionKeep =
          System.identityHashCode(regionFirst) +
            System.identityHashCode(regionSecond)
        val regionSelected =
          if ((regionKeep & 1) == 0) regionFirst else regionSecond
        region.append(regionSelectedBuffer, regionSelected)

        val chooseObject = (System.identityHashCode(region) & 1) == 0
        val objectBranchBuffer = RiftRegion.objectBuffer[
          Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
        ](1)
        RiftRegion.append(
          region,
          objectBranchBuffer,
          if chooseObject then Left(new RiftCheckedLeaf(1))
          else Right(new RiftCheckedLeaf(2))
        )

        val chooseRegion = (System.identityHashCode(objectBranchBuffer) & 1) == 0
        val regionBranchBuffer = RiftRegion.regionBuffer[
          Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
        ](1)
        region.append(
          regionBranchBuffer,
          if chooseRegion then Left(new RiftCheckedLeaf(3))
          else Right(new RiftCheckedLeaf(4))
        )

        val objectSelectedLeaf = RiftRegion.get(region, objectSelectedBuffer, 0) match
          case Left(value)  => value
          case Right(value) => value
        val regionSelectedLeaf = region.get(regionSelectedBuffer, 0) match
          case Left(value)  => value
          case Right(value) => value
        val objectBranchLeaf = RiftRegion.get(region, objectBranchBuffer, 0) match
          case Left(value)  => value
          case Right(value) => value
        val regionBranchLeaf = region.get(regionBranchBuffer, 0) match
          case Left(value)  => value
          case Right(value) => value

        val expectedObjectSelected = if ((objectKeep & 1) == 0) 40 else 41
        val expectedRegionSelected = if ((regionKeep & 1) == 0) 10 else 11
        val expectedObjectBranch = if chooseObject then 1 else 2
        val expectedRegionBranch = if chooseRegion then 3 else 4
        val identity =
          System.identityHashCode(objectSelectedBuffer) ^
            System.identityHashCode(objectSelected) ^
            System.identityHashCode(regionSelectedBuffer) ^
            System.identityHashCode(regionSelected) ^
            System.identityHashCode(objectBranchBuffer) ^
            System.identityHashCode(regionBranchBuffer)
        objectSelectedLeaf.value + regionSelectedLeaf.value +
          objectBranchLeaf.value + regionBranchLeaf.value -
          expectedObjectSelected - expectedRegionSelected -
          expectedObjectBranch - expectedRegionBranch + 42 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected checked-buffer selected and branch Either factories and payloads to be region allocated, observed $delta region objects",
        delta >= 12L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersBufferSelectedNestedSyntheticFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class SomeNode(
            val option: Option[RiftCheckedLeaf^{region}]^{region}
        )
        final class OptionNode(
            val option: Option[RiftCheckedLeaf^{region}]^{region}
        )
        final class PairNode(
            val pair: Tuple2[
              RiftCheckedLeaf^{region},
              RiftCheckedLeaf^{region}
            ]^{region}
        )

        val objectSomeBuffer = RiftRegion.objectBuffer[SomeNode](1)
        val someFirst = Some(new RiftCheckedLeaf(40))
        val someSecond = Some(new RiftCheckedLeaf(41))
        val someKeep =
          System.identityHashCode(someFirst) +
            System.identityHashCode(someSecond)
        val someSelected =
          if ((someKeep & 1) == 0) someFirst else someSecond
        RiftRegion.append(region, objectSomeBuffer, new SomeNode(someSelected))

        val regionOptionBuffer = RiftRegion.regionBuffer[OptionNode](1)
        val optionFirst = Option(new RiftCheckedLeaf(10))
        val optionSecond = Option(new RiftCheckedLeaf(11))
        val optionKeep =
          System.identityHashCode(optionFirst) +
            System.identityHashCode(optionSecond)
        val optionSelected =
          if ((optionKeep & 1) == 0) optionFirst else optionSecond
        region.append(regionOptionBuffer, new OptionNode(optionSelected))

        val objectPairBuffer = RiftRegion.objectBuffer[PairNode](1)
        val pairFirst = Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(2))
        val pairSecond = Tuple2(new RiftCheckedLeaf(3), new RiftCheckedLeaf(4))
        val pairKeep =
          System.identityHashCode(pairFirst) + System.identityHashCode(pairSecond)
        val pairSelected =
          if ((pairKeep & 1) == 0) pairFirst else pairSecond
        RiftRegion.append(region, objectPairBuffer, new PairNode(pairSelected))

        val expectedSome = if ((someKeep & 1) == 0) 40 else 41
        val expectedOption = if ((optionKeep & 1) == 0) 10 else 11
        val expectedPair = if ((pairKeep & 1) == 0) 3 else 7
        RiftRegion.get(region, objectSomeBuffer, 0).option.get.value +
          region.get(regionOptionBuffer, 0).option.get.value +
          RiftRegion.get(region, objectPairBuffer, 0).pair._1.value +
          RiftRegion.get(region, objectPairBuffer, 0).pair._2.value -
          expectedSome - expectedOption - expectedPair + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected selected nested buffer synthetic factories, payloads, and value objects to be region allocated, observed $delta region objects",
        delta >= 17L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersBufferBranchMatchSyntheticFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val objectSomeBuffer =
          RiftRegion.objectBuffer[Option[RiftCheckedLeaf^{region}]^{region}](1)
        val chooseSome = (System.identityHashCode(region) & 1) == 0
        RiftRegion.append(
          region,
          objectSomeBuffer,
          if chooseSome then Some(new RiftCheckedLeaf(40))
          else Some(new RiftCheckedLeaf(41))
        )

        val regionOptionBuffer =
          RiftRegion.regionBuffer[Option[RiftCheckedLeaf^{region}]^{region}](1)
        val optionSelector = System.identityHashCode(objectSomeBuffer) & 1
        region.append(
          regionOptionBuffer,
          (optionSelector match
            case 0 => Option(new RiftCheckedLeaf(10))
            case _ => Option(new RiftCheckedLeaf(11))
          )
        )

        val objectPairBuffer = RiftRegion.objectBuffer[
          Tuple2[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
        ](1)
        val choosePair = (System.identityHashCode(regionOptionBuffer) & 1) == 0
        RiftRegion.append(
          region,
          objectPairBuffer,
          if choosePair then
            Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(2))
          else Tuple2(new RiftCheckedLeaf(3), new RiftCheckedLeaf(4))
        )

        val expectedSome = if chooseSome then 40 else 41
        val expectedOption = if optionSelector == 0 then 10 else 11
        val expectedPair = if choosePair then 3 else 7
        RiftRegion.get(region, objectSomeBuffer, 0).get.value +
          region.get(regionOptionBuffer, 0).get.value +
          RiftRegion.get(region, objectPairBuffer, 0)._1.value +
          RiftRegion.get(region, objectPairBuffer, 0)._2.value -
          expectedSome - expectedOption - expectedPair + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch/match buffer Some/Option/Tuple2 factories and payloads to be region allocated, observed $delta region objects",
        delta >= 7L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersObjectBufferLocalBlockNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val buffer = RiftRegion.objectBuffer[RiftCheckedLeaf](2)
        val left =
          val value = 20
          new RiftCheckedLeaf(value)
        val right =
          val value = 22
          new RiftCheckedLeaf(value)
        RiftRegion.append(region, buffer, left)
        region.append(buffer, right)
        RiftRegion.get(region, buffer, 0).value +
          region.get(buffer, 1).value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersObjectBufferInlineNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val buffer = RiftRegion.objectBuffer[RiftCheckedLeaf](2)
        RiftRegion.append(region, buffer, new RiftCheckedLeaf(20))
        region.append(buffer, new RiftCheckedLeaf(22))
        RiftRegion.get(region, buffer, 0).value +
          region.get(buffer, 1).value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersObjectBufferInlineBlockNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val buffer = RiftRegion.objectBuffer[RiftCheckedLeaf](2)
        RiftRegion.append(
          region,
          buffer,
          {
            val value = 20
            new RiftCheckedLeaf(value)
          }
        )
        region.append(
          buffer,
          {
            val value = 22
            new RiftCheckedLeaf(value)
          }
        )
        RiftRegion.get(region, buffer, 0).value +
          region.get(buffer, 1).value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLocalMethodReturnedLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        def make(
            value: Int
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          val node = new RiftCheckedListNode(value)
          node
        val node = make(40)(using region)
        val list = RiftRegion.regionList[RiftCheckedListNode]()
        RiftRegion.prependRegionList(region, list, node)
        RiftRegion.regionListHead(region, list).value + 2
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 1L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLocalMethodReturnedLocalBlockNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        def make(
            value: Int
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          val node =
            val adjusted = value + 1
            new RiftCheckedListNode(adjusted)
          node
        val left = make(19)(using region)
        val right = make(21)(using region)
        left.value + right.value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLocalMethodBlockReturnedNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        def make(
            value: Int
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          val adjusted = value + 1
          new RiftCheckedListNode(adjusted)
        val left = make(19)(using region)
        val right = make(21)(using region)
        left.value + right.value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedLocalMethodReturnPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        def make(
            value: Int
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          new RiftCheckedListNode(value)
        def wrap(
            value: Int
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          make(value)(using r)
        val left = wrap(20)(using region)
        val right = wrap(22)(using region)
        left.value + right.value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedLocalAliasMethodReturnPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        def make(
            value: Int
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          new RiftCheckedListNode(value)
        def wrap(
            value: Int
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          val node = make(value)(using r)
          node
        val left = wrap(20)(using region)
        val right = wrap(22)(using region)
        left.value + right.value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedBranchAndMatchMethodReturnPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        def make(
            value: Int
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          new RiftCheckedListNode(value)
        def branchWrap(
            value: Int,
            flag: Boolean
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          if (flag) make(value)(using r)
          else make(value + 1000)(using r)
        def matchWrap(
            value: Int,
            selector: Int
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          selector match {
            case 0 => make(value)(using r)
            case _ => make(value + 1000)(using r)
          }
        val left = branchWrap(20, true)(using region)
        val right = matchWrap(22, 0)(using region)
        left.value + right.value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodReturnedSelectedLocalNewPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(flag: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): RiftCheckedLeaf^{r} = {
        val first = new RiftCheckedLeaf(40)
        val second = new RiftCheckedLeaf(41)
        val keep =
          System.identityHashCode(first) + System.identityHashCode(second)
        val selected = if (flag || keep != 0) first else second
        selected
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val leaf = make(true)(using region)
        leaf.value + 2 + (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned selected local allocations to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodReturnedSelectedLocalSyntheticFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      def makeOption(flag: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Option[RiftCheckedLeaf^{r}]^{r} = {
        val first = Some(new RiftCheckedLeaf(40))
        val second = Some(new RiftCheckedLeaf(41))
        val keep =
          System.identityHashCode(first) + System.identityHashCode(second)
        val selected = if (flag || keep != 0) first else second
        selected
      }

      def makeOptionApply(flag: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Option[RiftCheckedLeaf^{r}]^{r} = {
        val first = Option(new RiftCheckedLeaf(10))
        val second = Option(new RiftCheckedLeaf(11))
        val keep =
          System.identityHashCode(first) + System.identityHashCode(second)
        val selected = if (flag || keep != 0) first else second
        selected
      }

      def makePair(flag: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Tuple2[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r} = {
        val first = Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(1))
        val second = Tuple2(new RiftCheckedLeaf(2), new RiftCheckedLeaf(2))
        val keep =
          System.identityHashCode(first) + System.identityHashCode(second)
        val selected = if (flag || keep != 0) first else second
        selected
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val option = makeOption(true)(using region)
        val optionApply = makeOptionApply(true)(using region)
        val pair = makePair(true)(using region)
        val optionIdentity = System.identityHashCode(option)
        val optionApplyIdentity = System.identityHashCode(optionApply)
        val pairIdentity = System.identityHashCode(pair)
        option.get.value + optionApply.get.value + pair._1.value +
          pair._2.value - 10 +
          (optionIdentity & 0) + (optionApplyIdentity & 0) +
          (pairIdentity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected selected local Some/Option/Tuple2 factories and payloads to be region allocated, observed $delta region objects",
        delta >= 14L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodArgumentSelectedLocalSyntheticFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      def consumeOption(using r: RiftRegion.ScopedRegion^)(
          option: Option[RiftCheckedLeaf^{r}]^{r}
      ): Int = {
        val identity = System.identityHashCode(option)
        option.get.value + (identity & 0)
      }

      def consumeOptionApply(using r: RiftRegion.ScopedRegion^)(
          option: Option[RiftCheckedLeaf^{r}]^{r}
      ): Int = {
        val identity = System.identityHashCode(option)
        option.get.value + (identity & 0)
      }

      def consumePair(using r: RiftRegion.ScopedRegion^)(
          pair: Tuple2[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r}
      ): Int = {
        val identity = System.identityHashCode(pair)
        pair._1.value + pair._2.value + (identity & 0)
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val optionFirst = Some(new RiftCheckedLeaf(40))
        val optionSecond = Some(new RiftCheckedLeaf(41))
        val optionKeep =
          System.identityHashCode(optionFirst) +
            System.identityHashCode(optionSecond)
        val optionSelected =
          if ((optionKeep & 1) == 0) optionFirst else optionSecond

        val optionApplyFirst = Option(new RiftCheckedLeaf(10))
        val optionApplySecond = Option(new RiftCheckedLeaf(11))
        val optionApplyKeep =
          System.identityHashCode(optionApplyFirst) +
            System.identityHashCode(optionApplySecond)
        val optionApplySelected =
          if ((optionApplyKeep & 1) == 0) optionApplyFirst
          else optionApplySecond

        val pairFirst = Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(1))
        val pairSecond = Tuple2(new RiftCheckedLeaf(2), new RiftCheckedLeaf(2))
        val pairKeep =
          System.identityHashCode(pairFirst) + System.identityHashCode(pairSecond)
        val pairSelected = if ((pairKeep & 1) == 0) pairFirst else pairSecond

        val expectedOption = if ((optionKeep & 1) == 0) 40 else 41
        val expectedOptionApply =
          if ((optionApplyKeep & 1) == 0) 10 else 11
        val expectedPair = if ((pairKeep & 1) == 0) 2 else 4
        consumeOption(using region)(optionSelected) +
          consumeOptionApply(using region)(optionApplySelected) +
          consumePair(using region)(pairSelected) -
          expectedOption - expectedOptionApply - expectedPair + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected selected owner-token Some/Option/Tuple2 factories and payloads to be region allocated, observed $delta region objects",
        delta >= 14L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodArgumentSelectedLocalEitherFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      def consumeEither(using r: RiftRegion.ScopedRegion^)(
          either: Either[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r}
      ): Int = {
        val identity = System.identityHashCode(either)
        val leaf = either match
          case Left(value)  => value
          case Right(value) => value
        leaf.value + (identity & 0)
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val eitherFirst: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} =
          Left(new RiftCheckedLeaf(40))
        val eitherSecond: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} =
          Right(new RiftCheckedLeaf(41))
        val keep =
          System.identityHashCode(eitherFirst) +
            System.identityHashCode(eitherSecond)
        val eitherSelected =
          if ((keep & 1) == 0) eitherFirst else eitherSecond
        val expected = if ((keep & 1) == 0) 40 else 41
        consumeEither(using region)(eitherSelected) - expected + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected selected owner-token Either factories and payloads to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodArgumentBranchMatchSyntheticFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      def consumeOption(using r: RiftRegion.ScopedRegion^)(
          option: Option[RiftCheckedLeaf^{r}]^{r}
      ): Int = {
        val identity = System.identityHashCode(option)
        option.get.value + (identity & 0)
      }

      def consumePair(using r: RiftRegion.ScopedRegion^)(
          pair: Tuple2[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r}
      ): Int = {
        val identity = System.identityHashCode(pair)
        pair._1.value + pair._2.value + (identity & 0)
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val flag = (System.identityHashCode(region) & 1) == 0
        val selector = System.identityHashCode(region) & 1
        val expectedSome = if flag then 40 else 41
        val expectedOption = if selector == 0 then 10 else 11
        val expectedPair = if flag then 3 else 7

        consumeOption(using region)(
          if flag then Some(new RiftCheckedLeaf(40))
          else Some(new RiftCheckedLeaf(41))
        ) +
          consumeOption(using region)(
            (selector match
              case 0 => Option(new RiftCheckedLeaf(10))
              case _ => Option(new RiftCheckedLeaf(11))
            )
          ) +
          consumePair(using region)(
            if flag then Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(2))
            else Tuple2(new RiftCheckedLeaf(3), new RiftCheckedLeaf(4))
          ) -
          expectedSome - expectedOption - expectedPair + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch/match owner-token Some/Option/Tuple2 factories and payloads to be region allocated, observed $delta region objects",
        delta >= 7L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamingEpochInfersLocalMethodReturnedLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.streaming { stream ?=>
        RiftRegion.epoch { epoch ?=>
          def make(
              value: Int
          )(using r: RiftRegion.OpenStreamingRegion^): RiftCheckedListNode^{r} =
            val node = new RiftCheckedListNode(value)
            node
          val node = make(40)(using epoch)
          node.value + 2
        }
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 1L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersBranchedLocalMethodNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        def make(
            value: Int,
            flag: Boolean
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          if (flag) new RiftCheckedListNode(value)
          else new RiftCheckedListNode(value + 1000)
        val left = make(20, true)(using region)
        val right = make(22, true)(using region)
        left.value + right.value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersBranchReturnedLocalMethodNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        def make(
            value: Int,
            flag: Boolean
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          if (flag) {
            val left = new RiftCheckedListNode(value)
            left
          } else {
            val right = new RiftCheckedListNode(value + 1000)
            right
          }
        val left = make(20, true)(using region)
        val right = make(22, true)(using region)
        left.value + right.value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMatchedLocalMethodNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        def make(
            value: Int,
            selector: Int
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          selector match {
            case 0 => new RiftCheckedListNode(value)
            case _ => new RiftCheckedListNode(value + 1000)
          }
        val left = make(20, 0)(using region)
        val right = make(22, 0)(using region)
        left.value + right.value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMatchReturnedLocalMethodNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        def make(
            value: Int,
            selector: Int
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          selector match {
            case 0 =>
              val left = new RiftCheckedListNode(value)
              left
            case _ =>
              val right = new RiftCheckedListNode(value + 1000)
              right
          }
        val left = make(20, 0)(using region)
        val right = make(22, 0)(using region)
        left.value + right.value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersBranchAndMatchReturnedLocalBlockMethodNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        def branchMake(
            value: Int,
            flag: Boolean
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          if (flag) {
            val left = {
              val adjusted = value
              new RiftCheckedListNode(adjusted)
            }
            left
          } else {
            val right = {
              val adjusted = value + 1000
              new RiftCheckedListNode(adjusted)
            }
            right
          }
        def matchMake(
            value: Int,
            selector: Int
        )(using r: RiftRegion.ScopedRegion^): RiftCheckedListNode^{r} =
          selector match {
            case 0 =>
              val left = {
                val adjusted = value
                new RiftCheckedListNode(adjusted)
              }
              left
            case _ =>
              val right = {
                val adjusted = value + 1000
                new RiftCheckedListNode(adjusted)
              }
              right
          }
        val left = branchMake(20, true)(using region)
        val right = matchMake(22, 0)(using region)
        left.value + right.value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersRegionBufferLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val buffer = RiftRegion.regionBuffer[RiftCheckedLeaf](1)
        val left = new RiftCheckedLeaf(20)
        val right = new RiftCheckedLeaf(22)
        region.append(buffer, left)
        RiftRegion.append(region, buffer, right)
        region.get(buffer, 0).value +
          RiftRegion.get(region, buffer, 1).value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersRegionBufferLocalBlockNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val buffer = RiftRegion.regionBuffer[RiftCheckedLeaf](1)
        val left =
          val value = 20
          new RiftCheckedLeaf(value)
        val right =
          val value = 22
          new RiftCheckedLeaf(value)
        region.append(buffer, left)
        RiftRegion.append(region, buffer, right)
        region.get(buffer, 0).value +
          RiftRegion.get(region, buffer, 1).value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersRegionBufferInlineNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val buffer = RiftRegion.regionBuffer[RiftCheckedLeaf](1)
        region.append(buffer, new RiftCheckedLeaf(20))
        RiftRegion.append(region, buffer, new RiftCheckedLeaf(22))
        region.get(buffer, 0).value +
          RiftRegion.get(region, buffer, 1).value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersRegionBufferInlineBlockNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val sum = RiftRegion.scoped { region ?=>
        val buffer = RiftRegion.regionBuffer[RiftCheckedLeaf](1)
        region.append(
          buffer,
          {
            val value = 20
            new RiftCheckedLeaf(value)
          }
        )
        RiftRegion.append(
          region,
          buffer,
          {
            val value = 22
            new RiftCheckedLeaf(value)
          }
        )
        region.get(buffer, 0).value +
          RiftRegion.get(region, buffer, 1).value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, sum)
      assertTrue(after - before >= 2L)
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

  @Test def scopedRegionInfersLocalClosurePlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val leaf: RiftCheckedLeaf^{region} =
          new RiftCheckedLeaf(40)
        val addCaptured: Function1[Int, Int]^{region} =
          (n: Int) => leaf.value + n

        invokeRegionFunction(addCaptured, 2)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(
        s"expected region-local leaf and closure object to be region allocated, observed ${after - before} region objects",
        after - before >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersCaptureFreeLocalClosurePlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val add: Function1[Int, Int]^{region} =
          (n: Int) => n + 40

        invokeRegionFunction(add, 2)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(
        s"expected capture-free closure object to be region allocated, observed ${after - before} region objects",
        after - before >= 1L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodReturnedCaptureFreeClosurePlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} = {
        val add: Function1[Int, Int]^{r} =
          (n: Int) => n + 40
        add
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        invokeRegionFunction(make(using region), 2)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned capture-free closure object to be region allocated, observed ${after - before} region objects",
        after - before >= 1L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodReturnedClosureCapturingRegionValuePlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} = {
        val leaf: RiftCheckedLeaf^{r} =
          new RiftCheckedLeaf(40)
        val add: Function1[Int, Int]^{r} =
          (n: Int) => leaf.value + n
        add
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        invokeRegionFunction(make(using region), 2)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned closure and captured region value to be region allocated, observed ${after - before} region objects",
        after - before >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersOwnerAliasMethodReturnedClosureCapturingRegionValuePlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} = {
        val owner = r
        val leaf: RiftCheckedLeaf^{owner} =
          new RiftCheckedLeaf(40)
        val add: Function1[Int, Int]^{owner} =
          (n: Int) => leaf.value + n
        add
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        invokeRegionFunction(make(using region), 2)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(
        s"expected owner-alias method-returned closure and captured region value to be region allocated, observed ${after - before} region objects",
        after - before >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedMethodReturnedClosureCapturingRegionValuePlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} = {
        val leaf: RiftCheckedLeaf^{r} =
          new RiftCheckedLeaf(40)
        val add: Function1[Int, Int]^{r} =
          (n: Int) => leaf.value + n
        add
      }

      def wrap(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} =
        make(using r)

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        invokeRegionFunction(wrap(using region), 2)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(
        s"expected forwarded method-returned closure and captured region value to be region allocated, observed ${after - before} region objects",
        after - before >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedLocalAliasMethodReturnedClosureCapturingRegionValuePlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} = {
        val leaf: RiftCheckedLeaf^{r} =
          new RiftCheckedLeaf(40)
        val add: Function1[Int, Int]^{r} =
          (n: Int) => leaf.value + n
        add
      }

      def wrap(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} = {
        val forwarded = make(using r)
        forwarded
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        invokeRegionFunction(wrap(using region), 2)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(
        s"expected forwarded local-alias method-returned closure and captured region value to be region allocated, observed ${after - before} region objects",
        after - before >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedBranchMethodReturnedClosureCapturingRegionValuePlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} = {
        val leaf: RiftCheckedLeaf^{r} =
          new RiftCheckedLeaf(40)
        val add: Function1[Int, Int]^{r} =
          (n: Int) => leaf.value + n
        add
      }

      def wrap(flag: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Int]^{r} =
        if (flag) make(using r) else make(using r)

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        invokeRegionFunction(wrap(true)(using region), 2)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(
        s"expected branch-forwarded method-returned closure and captured region value to be region allocated, observed ${after - before} region objects",
        after - before >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedMatchMethodReturnedClosureCapturingRegionValuePlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using r: RiftRegion.ScopedRegion^): Function1[Int, Int]^{r} = {
        val leaf: RiftCheckedLeaf^{r} =
          new RiftCheckedLeaf(40)
        val add: Function1[Int, Int]^{r} =
          (n: Int) => leaf.value + n
        add
      }

      def wrap(selector: Int)(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Int]^{r} =
        selector match {
          case 0 => make(using r)
          case _ => make(using r)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        invokeRegionFunction(wrap(0)(using region), 2)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(
        s"expected match-forwarded method-returned closure and captured region value to be region allocated, observed ${after - before} region objects",
        after - before >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val make: Function1[Int, RiftCheckedLeaf^{region}]^{region} =
          (value: Int) => {
            val owner = region
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(value)
            leaf
          }

        val leaf = make(40)
        leaf.value + 2 +
          (System.identityHashCode(make) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected captured-owner closure object and body allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyDirectOptionFactoryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val make: Function1[
          Int,
          Option[RiftCheckedLeaf^{region}]^{region}
        ]^{region} =
          (value: Int) => {
            val owner = region
            val keepOwner = System.identityHashCode(owner) & 0
            Some(new RiftCheckedLeaf(value + keepOwner))
          }

        val maybe = make(40)
        val leaf = maybe.get
        leaf.value + 2 +
          (System.identityHashCode(make) & 0) +
          (System.identityHashCode(maybe) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected captured-owner closure object plus direct Option/Some body factory allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyOptionApplyFactoryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val make: Function1[
          Int,
          Option[RiftCheckedLeaf^{region}]^{region}
        ]^{region} =
          (value: Int) => {
            val owner = region
            val keepOwner = System.identityHashCode(owner) & 0
            Option(new RiftCheckedLeaf(value + keepOwner))
          }

        val maybe = make(40)
        val leaf = maybe.get
        leaf.value + 2 +
          (System.identityHashCode(make) & 0) +
          (System.identityHashCode(maybe) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected captured-owner closure object plus Option.apply body factory allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodySomeOrNoneAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val make: Function1[
          Boolean,
          Option[RiftCheckedLeaf^{region}]^{region}
        ]^{region} =
          (include: Boolean) => {
            val owner = region
            val keepOwner = System.identityHashCode(owner) & 0
            if (include) Some(new RiftCheckedLeaf(40 + keepOwner))
            else None
          }

        val some = make(true)
        val none = make(false)
        val leaf = some.get
        leaf.value + 2 + none.fold(0)(_.value) +
          (System.identityHashCode(make) & 0) +
          (System.identityHashCode(some) & 0) +
          (System.identityHashCode(none) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected captured-owner closure object plus Some branch payload to be region allocated while None allocates no region object, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodySelectedOptionFactoryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val make: Function1[
          Int,
          Option[RiftCheckedLeaf^{region}]^{region}
        ]^{region} =
          (value: Int) => {
            val owner = region
            val keepOwner = System.identityHashCode(owner) & 0
            val first = Some(new RiftCheckedLeaf(value + keepOwner))
            val second = Some(new RiftCheckedLeaf(value + 1 + keepOwner))
            val keepFactories =
              System.identityHashCode(first) + System.identityHashCode(second)
            val selected =
              if (value + (keepFactories & 0) >= 0) first else second
            selected
          }

        val maybe = make(40)
        val leaf = maybe.get
        leaf.value + 2 +
          (System.identityHashCode(make) & 0) +
          (System.identityHashCode(maybe) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected captured-owner closure object plus selected Option/Some body factory allocations to be region allocated, observed $delta region objects",
        delta >= 5L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyDirectTupleFactoryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val make: Function1[
          Int,
          Tuple2[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
        ]^{region} =
          (value: Int) => {
            val owner = region
            val keepOwner = System.identityHashCode(owner) & 0
            Tuple2(
              new RiftCheckedLeaf(value + keepOwner),
              new RiftCheckedLeaf(1 + keepOwner)
            )
          }

        val pair = make(39)
        val first = pair._1
        val second = pair._2
        first.value + second.value + 2 +
          (System.identityHashCode(make) & 0) +
          (System.identityHashCode(pair) & 0) +
          (System.identityHashCode(first) & 0) +
          (System.identityHashCode(second) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected captured-owner closure object plus direct Tuple2 body factory allocation to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodySelectedTupleFactoryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val make: Function1[
          Int,
          Tuple2[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
        ]^{region} =
          (value: Int) => {
            val owner = region
            val keepOwner = System.identityHashCode(owner) & 0
            val first = Tuple2(
              new RiftCheckedLeaf(value + keepOwner),
              new RiftCheckedLeaf(1 + keepOwner)
            )
            val second = Tuple2(
              new RiftCheckedLeaf(value + 1 + keepOwner),
              new RiftCheckedLeaf(0 + keepOwner)
            )
            val keepFactories =
              System.identityHashCode(first) +
                System.identityHashCode(second) +
                System.identityHashCode(first._1) +
                System.identityHashCode(first._2) +
                System.identityHashCode(second._1) +
                System.identityHashCode(second._2)
            val selected =
              if (value + (keepFactories & 0) >= 0) first else second
            selected
          }

        val pair = make(39)
        val first = pair._1
        val second = pair._2
        first.value + second.value + 2 +
          (System.identityHashCode(make) & 0) +
          (System.identityHashCode(pair) & 0) +
          (System.identityHashCode(first) & 0) +
          (System.identityHashCode(second) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected captured-owner closure object plus selected Tuple2 body factory allocations to be region allocated, observed $delta region objects",
        delta >= 7L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyOptionApplyInlineClosureAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val make: Function1[
          Int,
          Option[Function1[Int, RiftCheckedLeaf^{region}]^{region}]^{region}
        ]^{region} =
          (base: Int) => {
            val owner = region
            Option((value: Int) => {
              val keepOwner = System.identityHashCode(owner) & 0
              val leaf: RiftCheckedLeaf^{owner} =
                new RiftCheckedLeaf(base + value + keepOwner)
              leaf
            })
          }

        val maybe = make(40)
        val fn = maybe.get
        val leaf = fn(2)
        leaf.value +
          (System.identityHashCode(make) & 0) +
          (System.identityHashCode(maybe) & 0) +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected captured-owner closure body Option.apply wrapper plus inline closure/body allocation to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodySomeInlineClosureAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val make: Function1[
          Int,
          Option[Function1[Int, RiftCheckedLeaf^{region}]^{region}]^{region}
        ]^{region} =
          (base: Int) => {
            val owner = region
            Some((value: Int) => {
              val keepOwner = System.identityHashCode(owner) & 0
              val leaf: RiftCheckedLeaf^{owner} =
                new RiftCheckedLeaf(base + value + keepOwner)
              leaf
            })
          }

        val maybe = make(40)
        val fn = maybe.get
        val leaf = fn(2)
        leaf.value +
          (System.identityHashCode(make) & 0) +
          (System.identityHashCode(maybe) & 0) +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected captured-owner closure body Some wrapper plus inline closure/body allocation to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyOptionApplySelectedClosureAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val make: Function1[
          Boolean,
          Option[Function1[Int, RiftCheckedLeaf^{region}]^{region}]^{region}
        ]^{region} =
          (chooseFirst: Boolean) => {
            val owner = region
            val first = (value: Int) => {
              val keepOwner = System.identityHashCode(owner) & 0
              val leaf: RiftCheckedLeaf^{owner} =
                new RiftCheckedLeaf(value + 40 + keepOwner)
              leaf
            }
            val second = (value: Int) => {
              val keepOwner = System.identityHashCode(owner) & 0
              val leaf: RiftCheckedLeaf^{owner} =
                new RiftCheckedLeaf(value + 41 + keepOwner)
              leaf
            }
            val selected = if chooseFirst then first else second
            Option(selected)
          }

        val flag = System.identityHashCode(region) != 0
        val maybe = make(flag)
        val fn = maybe.get
        val leaf = fn(2)
        val expected = if flag then 42 else 43
        leaf.value - expected + 42 +
          (System.identityHashCode(make) & 0) +
          (System.identityHashCode(maybe) & 0) +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected captured-owner closure body Option.apply wrapper plus selected closure values/body allocation to be region allocated, observed $delta region objects",
        delta >= 5L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodySomeSelectedClosureAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val make: Function1[
          Boolean,
          Option[Function1[Int, RiftCheckedLeaf^{region}]^{region}]^{region}
        ]^{region} =
          (chooseFirst: Boolean) => {
            val owner = region
            val first = (value: Int) => {
              val keepOwner = System.identityHashCode(owner) & 0
              val leaf: RiftCheckedLeaf^{owner} =
                new RiftCheckedLeaf(value + 40 + keepOwner)
              leaf
            }
            val second = (value: Int) => {
              val keepOwner = System.identityHashCode(owner) & 0
              val leaf: RiftCheckedLeaf^{owner} =
                new RiftCheckedLeaf(value + 41 + keepOwner)
              leaf
            }
            val selected = if chooseFirst then first else second
            Some(selected)
          }

        val flag = System.identityHashCode(region) != 0
        val maybe = make(flag)
        val fn = maybe.get
        val leaf = fn(2)
        val expected = if flag then 42 else 43
        leaf.value - expected + 42 +
          (System.identityHashCode(make) & 0) +
          (System.identityHashCode(maybe) & 0) +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected captured-owner closure body Some wrapper plus selected closure values/body allocation to be region allocated, observed $delta region objects",
        delta >= 5L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodReturnedClosureBodyAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (value: Int) => {
          val owner = r
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(value)
          leaf
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned captured-owner closure object and body allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyMethodSummaryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def build(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): RiftCheckedLeaf^{r} =
        new RiftCheckedLeaf(value)

      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (value: Int) => {
          val owner = r
          val keepOwner = System.identityHashCode(owner) & 0
          build(value + keepOwner)(using owner)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned closure plus callee-summary body allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyForwardedMethodSummaryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def build(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): RiftCheckedLeaf^{r} =
        new RiftCheckedLeaf(value)

      def forward(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): RiftCheckedLeaf^{r} =
        build(value)(using r)

      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (value: Int) => {
          val owner = r
          val keepOwner = System.identityHashCode(owner) & 0
          forward(value + keepOwner)(using owner)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned closure plus forwarded callee-summary body allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyBranchForwardedMethodSummaryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def build(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): RiftCheckedLeaf^{r} =
        new RiftCheckedLeaf(value)

      def forward(flag: Boolean, value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): RiftCheckedLeaf^{r} =
        if flag then build(value)(using r)
        else build(value + 1)(using r)

      def make(flag: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (value: Int) => {
          val owner = r
          val keepOwner = System.identityHashCode(owner) & 0
          forward(flag, value + keepOwner)(using owner)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(true)(using region)
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned closure plus branch-forwarded callee-summary body allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyMatchForwardedMethodSummaryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def build(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): RiftCheckedLeaf^{r} =
        new RiftCheckedLeaf(value)

      def forward(selector: Int, value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): RiftCheckedLeaf^{r} =
        selector match
          case 0 => build(value)(using r)
          case _ => build(value + 1)(using r)

      def make(selector: Int)(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (value: Int) => {
          val owner = r
          val keepOwner = System.identityHashCode(owner) & 0
          forward(selector, value + keepOwner)(using owner)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(0)(using region)
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned closure plus match-forwarded callee-summary body allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyOptionMethodSummaryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def build(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): Option[RiftCheckedLeaf^{r}]^{r} =
        Some(new RiftCheckedLeaf(value))

      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Option[RiftCheckedLeaf^{r}]^{r}]^{r} =
        (value: Int) => {
          val owner = r
          val keepOwner = System.identityHashCode(owner) & 0
          build(value + keepOwner)(using owner)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val maybe = makeLeaf(40)
        val leaf = maybe.get
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(maybe) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned closure plus Option/Some callee-summary body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyOptionApplyMethodSummaryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def build(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): Option[RiftCheckedLeaf^{r}]^{r} =
        Option(new RiftCheckedLeaf(value))

      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Option[RiftCheckedLeaf^{r}]^{r}]^{r} =
        (value: Int) => {
          val owner = r
          val keepOwner = System.identityHashCode(owner) & 0
          build(value + keepOwner)(using owner)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val maybe = makeLeaf(40)
        val leaf = maybe.get
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(maybe) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned closure plus Option.apply callee-summary body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyTupleMethodSummaryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def build(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): Tuple2[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r} =
        Tuple2(new RiftCheckedLeaf(value), new RiftCheckedLeaf(1))

      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[
        Int,
        Tuple2[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r}
      ]^{r} =
        (value: Int) => {
          val owner = r
          val keepOwner = System.identityHashCode(owner) & 0
          build(value + keepOwner)(using owner)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makePair = make(using region)
        val pair = makePair(39)
        val first = pair._1
        val second = pair._2
        first.value + second.value + 2 +
          (System.identityHashCode(makePair) & 0) +
          (System.identityHashCode(pair) & 0) +
          (System.identityHashCode(first) & 0) +
          (System.identityHashCode(second) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned closure plus Tuple2 callee-summary body allocation to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodySelectedOptionApplyMethodSummaryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def build(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): Option[RiftCheckedLeaf^{r}]^{r} = {
        val first = Option(new RiftCheckedLeaf(value))
        val second = Option(new RiftCheckedLeaf(value + 1))
        val keepFactories =
          System.identityHashCode(first) + System.identityHashCode(second)
        if (value + (keepFactories & 0) >= 0) first else second
      }

      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Option[RiftCheckedLeaf^{r}]^{r}]^{r} =
        (value: Int) => {
          val owner = r
          val keepOwner = System.identityHashCode(owner) & 0
          build(value + keepOwner)(using owner)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val maybe = makeLeaf(40)
        val leaf = maybe.get
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(maybe) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned closure plus selected Option.apply callee-summary body allocations to be region allocated, observed $delta region objects",
        delta >= 5L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodySelectedTupleMethodSummaryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def build(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): Tuple2[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r} = {
        val first = Tuple2(
          new RiftCheckedLeaf(value),
          new RiftCheckedLeaf(1)
        )
        val second = Tuple2(
          new RiftCheckedLeaf(value + 1),
          new RiftCheckedLeaf(0)
        )
        val keepFactories =
          System.identityHashCode(first) +
            System.identityHashCode(second) +
            System.identityHashCode(first._1) +
            System.identityHashCode(first._2) +
            System.identityHashCode(second._1) +
            System.identityHashCode(second._2)
        if (value + (keepFactories & 0) >= 0) first else second
      }

      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[
        Int,
        Tuple2[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r}
      ]^{r} =
        (value: Int) => {
          val owner = r
          val keepOwner = System.identityHashCode(owner) & 0
          build(value + keepOwner)(using owner)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makePair = make(using region)
        val pair = makePair(39)
        val first = pair._1
        val second = pair._2
        first.value + second.value + 2 +
          (System.identityHashCode(makePair) & 0) +
          (System.identityHashCode(pair) & 0) +
          (System.identityHashCode(first) & 0) +
          (System.identityHashCode(second) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned closure plus selected Tuple2 callee-summary body allocations to be region allocated, observed $delta region objects",
        delta >= 7L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyEitherMethodSummaryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      def build(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): Either[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r} =
        if value >= 0 then Left(new RiftCheckedLeaf(value))
        else Right(new RiftCheckedLeaf(0))

      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[
        Int,
        Either[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r}
      ]^{r} =
        (value: Int) => {
          val owner = r
          val keepOwner = System.identityHashCode(owner) & 0
          build(value + keepOwner)(using owner)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeEither = make(using region)
        val either = makeEither(40)
        val leaf = either match
          case Left(value)  => value
          case Right(value) => value
        leaf.value + 2 +
          (System.identityHashCode(makeEither) & 0) +
          (System.identityHashCode(either) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned closure plus Either callee-summary body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodySelectedEitherMethodSummaryAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      def build(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): Either[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r} = {
        val first: Either[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r} =
          Left(new RiftCheckedLeaf(value))
        val second: Either[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r} =
          Right(new RiftCheckedLeaf(value + 1))
        val keepFactories =
          System.identityHashCode(first) +
            System.identityHashCode(second)
        if (value + (keepFactories & 0) >= 0) first else second
      }

      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[
        Int,
        Either[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r}
      ]^{r} =
        (value: Int) => {
          val owner = r
          val keepOwner = System.identityHashCode(owner) & 0
          build(value + keepOwner)(using owner)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeEither = make(using region)
        val either = makeEither(40)
        val leaf = either match
          case Left(value)  => value
          case Right(value) => value
        leaf.value + 2 +
          (System.identityHashCode(makeEither) & 0) +
          (System.identityHashCode(either) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned closure plus selected Either callee-summary body allocations to be region allocated, observed $delta region objects",
        delta >= 5L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedMethodReturnedClosureBodyAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (value: Int) => {
          val owner = r
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(value)
          leaf
        }

      def wrap(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        make(using r)

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = wrap(using region)
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected forwarded method-returned captured-owner closure object and body allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedBranchMethodReturnedClosureBodyAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (value: Int) => {
          val owner = r
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(value)
          leaf
        }

      def wrap(flag: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        if (flag) make(using r) else make(using r)

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = wrap(true)(using region)
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch-forwarded method-returned captured-owner closure object and body allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedMatchMethodReturnedClosureBodyAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (value: Int) => {
          val owner = r
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(value)
          leaf
        }

      def wrap(selector: Int)(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        selector match {
          case 0 => make(using r)
          case _ => make(using r)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = wrap(0)(using region)
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected match-forwarded method-returned captured-owner closure object and body allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodReturnedLocalClosureBodyAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} = {
        val makeLeaf =
          (value: Int) => {
            val owner = r
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(value)
            leaf
          }
        makeLeaf
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned local captured-owner closure object and body allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyReturnedClosureAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Function1[Int, RiftCheckedLeaf^{r}]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          ((value: Int) => {
            val keepOwner = System.identityHashCode(owner) & 0
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(base + value + keepOwner)
            leaf
          }): Function1[Int, RiftCheckedLeaf^{owner}]^{owner}
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val outer = make(using region)
        val inner = outer(40)
        val leaf = inner(2)
        leaf.value +
          (System.identityHashCode(outer) & 0) +
          (System.identityHashCode(inner) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body returned closure object and nested body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyReturnedUntypedLocalClosureAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Function1[Int, RiftCheckedLeaf^{r}]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          val inner =
            (value: Int) => {
              val keepOwner = System.identityHashCode(owner) & 0
              val leaf: RiftCheckedLeaf^{owner} =
                new RiftCheckedLeaf(base + value + keepOwner)
              leaf
            }
          inner
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val outer = make(using region)
        val inner = outer(40)
        val leaf = inner(2)
        leaf.value +
          (System.identityHashCode(outer) & 0) +
          (System.identityHashCode(inner) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body returned untyped local closure object and nested body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyReturnedTypedLocalClosureAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Function1[Int, RiftCheckedLeaf^{r}]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          val inner: Function1[Int, RiftCheckedLeaf^{owner}]^{owner} =
            (value: Int) => {
              val keepOwner = System.identityHashCode(owner) & 0
              val leaf: RiftCheckedLeaf^{owner} =
                new RiftCheckedLeaf(base + value + keepOwner)
              leaf
            }
          inner
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val outer = make(using region)
        val inner = outer(40)
        val leaf = inner(2)
        leaf.value +
          (System.identityHashCode(outer) & 0) +
          (System.identityHashCode(inner) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body returned local closure object and nested body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyLocalHelperReturnedLocalClosureAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Function1[Int, RiftCheckedLeaf^{r}]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int)
              : Function1[Int, RiftCheckedLeaf^{owner}]^{owner} = {
            val inner =
              (value: Int) => {
                val keepOwner = System.identityHashCode(owner) & 0
                val leaf: RiftCheckedLeaf^{owner} =
                  new RiftCheckedLeaf(base + offset + value + keepOwner)
                leaf
              }
            inner
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val outer = make(using region)
        val inner = outer(20)
        val leaf = inner(2)
        leaf.value +
          (System.identityHashCode(outer) & 0) +
          (System.identityHashCode(inner) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body local helper returned local closure object and nested body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionDoesNotInferClosureBodyLocalHelperTypeOnlyOwnerBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Function1[Int, RiftCheckedLeaf^{r}]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int)
              : Function1[Int, RiftCheckedLeaf^{owner}]^{owner} = {
            val inner =
              (value: Int) => {
                val leaf: RiftCheckedLeaf^{owner} =
                  new RiftCheckedLeaf(base + offset + value)
                leaf
              }
            inner
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val outer = make(using region)
        val inner = outer(20)
        val leaf = inner(2)
        leaf.value +
          (System.identityHashCode(outer) & 0) +
          (System.identityHashCode(inner) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected type-only lexical helper body allocation to stay on the heap without a runtime owner value, observed $delta region objects",
        delta < 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyLocalHelperReturnedLocalAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int): RiftCheckedLeaf^{owner} = {
            val keepOwner = System.identityHashCode(owner) & 0
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(base + offset + keepOwner)
            leaf
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21)
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body local helper returned local allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionDoesNotInferClosureBodyLocalHelperTypeOnlyReturnedLocalAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int): RiftCheckedLeaf^{owner} = {
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(base + offset)
            leaf
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21)
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected type-only lexical helper returned local allocation to stay on the heap without a runtime owner value, observed $delta region objects",
        delta < 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyLocalHelperDirectBranchAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int): RiftCheckedLeaf^{owner} = {
            val keepOwner = System.identityHashCode(owner) & 0
            if offset >= 0 then
              new RiftCheckedLeaf(base + offset + keepOwner)
            else new RiftCheckedLeaf(keepOwner)
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21)
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body local helper direct branch allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionDoesNotInferClosureBodyLocalHelperTypeOnlyDirectBranchAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int): RiftCheckedLeaf^{owner} = {
            if offset >= 0 then new RiftCheckedLeaf(base + offset)
            else new RiftCheckedLeaf(0)
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21)
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected type-only lexical helper direct branch allocation to stay on the heap without a runtime owner value, observed $delta region objects",
        delta < 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyLocalHelperDirectMatchAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int): RiftCheckedLeaf^{owner} = {
            val keepOwner = System.identityHashCode(owner) & 0
            offset match {
              case n if n >= 0 =>
                new RiftCheckedLeaf(base + n + keepOwner)
              case _ =>
                new RiftCheckedLeaf(keepOwner)
            }
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21)
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body local helper direct match allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionDoesNotInferClosureBodyLocalHelperTypeOnlyDirectMatchAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int): RiftCheckedLeaf^{owner} =
            offset match {
              case n if n >= 0 => new RiftCheckedLeaf(base + n)
              case _           => new RiftCheckedLeaf(0)
            }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21)
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected type-only lexical helper direct match allocation to stay on the heap without a runtime owner value, observed $delta region objects",
        delta < 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyLocalHelperBranchForwardedAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def allocate(offset: Int): RiftCheckedLeaf^{owner} = {
            val keepOwner = System.identityHashCode(owner) & 0
            new RiftCheckedLeaf(base + offset + keepOwner)
          }
          def build(offset: Int): RiftCheckedLeaf^{owner} = {
            val keepOwner = System.identityHashCode(owner) & 0
            if offset >= 0 then allocate(offset + keepOwner)
            else allocate(keepOwner)
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21)
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body local helper branch-forwarded allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionDoesNotInferClosureBodyLocalHelperTypeOnlyBranchForwardedAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def allocate(offset: Int): RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(base + offset)
          def build(offset: Int): RiftCheckedLeaf^{owner} =
            if offset >= 0 then allocate(offset)
            else allocate(0)
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21)
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected type-only lexical helper branch-forwarded allocation to stay on the heap without a runtime owner value, observed $delta region objects",
        delta < 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyLocalHelperMatchForwardedAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def allocate(offset: Int): RiftCheckedLeaf^{owner} = {
            val keepOwner = System.identityHashCode(owner) & 0
            new RiftCheckedLeaf(base + offset + keepOwner)
          }
          def build(offset: Int): RiftCheckedLeaf^{owner} = {
            val keepOwner = System.identityHashCode(owner) & 0
            offset match {
              case n if n >= 0 => allocate(n + keepOwner)
              case _           => allocate(keepOwner)
            }
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21)
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body local helper match-forwarded allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionDoesNotInferClosureBodyLocalHelperTypeOnlyMatchForwardedAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def allocate(offset: Int): RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(base + offset)
          def build(offset: Int): RiftCheckedLeaf^{owner} =
            offset match {
              case n if n >= 0 => allocate(n)
              case _           => allocate(0)
            }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21)
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected type-only lexical helper match-forwarded allocation to stay on the heap without a runtime owner value, observed $delta region objects",
        delta < 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyLocalHelperAliasForwardedAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def allocate(offset: Int): RiftCheckedLeaf^{owner} = {
            val keepOwner = System.identityHashCode(owner) & 0
            new RiftCheckedLeaf(base + offset + keepOwner)
          }
          def build(offset: Int): RiftCheckedLeaf^{owner} = {
            val keepOwner = System.identityHashCode(owner) & 0
            val forwarded: RiftCheckedLeaf^{owner} =
              allocate(offset + keepOwner)
            forwarded
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21)
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body local helper alias-forwarded allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionDoesNotInferClosureBodyLocalHelperTypeOnlyAliasForwardedAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def allocate(offset: Int): RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(base + offset)
          def build(offset: Int): RiftCheckedLeaf^{owner} = {
            val forwarded: RiftCheckedLeaf^{owner} =
              allocate(offset)
            forwarded
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21)
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected type-only lexical helper alias-forwarded allocation to stay on the heap without a runtime owner value, observed $delta region objects",
        delta < 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyLocalHelperReturnedArrayWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Array[RiftCheckedLeaf^{r}]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int): Array[RiftCheckedLeaf^{r}]^{r} = {
            val keepOwner = System.identityHashCode(owner) & 0
            val items: Array[RiftCheckedLeaf^{r}]^{r} =
              new Array[RiftCheckedLeaf^{r}](1)
            items(0) = new RiftCheckedLeaf(base + offset + keepOwner)
            items
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeArray = make(using region)
        val items = makeArray(21)
        val leaf = items(0)
        leaf.value +
          (System.identityHashCode(makeArray) & 0) +
          (System.identityHashCode(items) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body local helper returned array and element to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionDoesNotInferClosureBodyLocalHelperTypeOnlyReturnedArray()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Array[RiftCheckedLeaf^{r}]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int): Array[RiftCheckedLeaf^{r}]^{r} = {
            val items: Array[RiftCheckedLeaf^{r}]^{r} =
              new Array[RiftCheckedLeaf^{r}](1)
            items(0) = new RiftCheckedLeaf(base + offset)
            items
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeArray = make(using region)
        val items = makeArray(21)
        val leaf = items(0)
        leaf.value +
          (System.identityHashCode(makeArray) & 0) +
          (System.identityHashCode(items) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected type-only lexical helper returned array and element to stay on the heap without a runtime owner value, observed $delta region objects",
        delta < 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyLocalHelperReturnedPrimitiveArrayWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Array[Int]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int): Array[Int]^{r} = {
            val keepOwner = System.identityHashCode(owner) & 0
            val values: Array[Int]^{r} =
              new Array[Int](1)
            values(0) = base + offset + keepOwner
            values
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeArray = make(using region)
        val values = makeArray(21)
        values(0) +
          (System.identityHashCode(makeArray) & 0) +
          (System.identityHashCode(values) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body local helper returned primitive array to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionDoesNotInferClosureBodyLocalHelperTypeOnlyReturnedPrimitiveArray()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Array[Int]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int): Array[Int]^{r} = {
            val values: Array[Int]^{r} =
              new Array[Int](1)
            values(0) = base + offset
            values
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeArray = make(using region)
        val values = makeArray(21)
        values(0) +
          (System.identityHashCode(makeArray) & 0) +
          (System.identityHashCode(values) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected type-only lexical helper returned primitive array to stay on the heap without a runtime owner value, observed $delta region objects",
        delta < 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyLocalHelperReturnedSomeWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Option[RiftCheckedLeaf^{r}]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int): Option[RiftCheckedLeaf^{owner}]^{owner} = {
            val keepOwner = System.identityHashCode(owner) & 0
            val result: Option[RiftCheckedLeaf^{owner}]^{owner} =
              Some(new RiftCheckedLeaf(base + offset + keepOwner))
            result
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21).get
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body local helper returned Some and payload to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionDoesNotInferClosureBodyLocalHelperTypeOnlyReturnedSome()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Option[RiftCheckedLeaf^{r}]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int): Option[RiftCheckedLeaf^{owner}]^{owner} = {
            val result: Option[RiftCheckedLeaf^{owner}]^{owner} =
              Some(new RiftCheckedLeaf(base + offset))
            result
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21).get
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected type-only lexical helper returned Some to stay on the heap without a runtime owner value, observed $delta region objects",
        delta < 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyLocalHelperReturnedOptionApplyWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Option[RiftCheckedLeaf^{r}]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int): Option[RiftCheckedLeaf^{owner}]^{owner} = {
            val keepOwner = System.identityHashCode(owner) & 0
            val result: Option[RiftCheckedLeaf^{owner}]^{owner} =
              Option(new RiftCheckedLeaf(base + offset + keepOwner))
            result
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21).get
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body local helper returned Option.apply Some and payload to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionDoesNotInferClosureBodyLocalHelperTypeOnlyReturnedOptionApply()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Option[RiftCheckedLeaf^{r}]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int): Option[RiftCheckedLeaf^{owner}]^{owner} = {
            val result: Option[RiftCheckedLeaf^{owner}]^{owner} =
              Option(new RiftCheckedLeaf(base + offset))
            result
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(using region)
        val leaf = makeLeaf(21).get
        leaf.value +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected type-only lexical helper returned Option.apply to stay on the heap without a runtime owner value, observed $delta region objects",
        delta < 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyLocalHelperReturnedTupleWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Tuple2[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int)
              : Tuple2[RiftCheckedLeaf^{owner}, RiftCheckedLeaf^{owner}]^{owner} = {
            val keepOwner = System.identityHashCode(owner) & 0
            val result
                : Tuple2[RiftCheckedLeaf^{owner}, RiftCheckedLeaf^{owner}]^{owner} =
              Tuple2(
                new RiftCheckedLeaf(base + offset + keepOwner),
                new RiftCheckedLeaf(1 + keepOwner)
              )
            result
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makePair = make(using region)
        val pair = makePair(40)
        pair._1.value + pair._2.value +
          (System.identityHashCode(makePair) & 0) +
          (System.identityHashCode(pair) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(81, total)
      assertTrue(
        s"expected closure-body local helper returned Tuple2 and payloads to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionDoesNotInferClosureBodyLocalHelperTypeOnlyReturnedTuple()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Tuple2[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int)
              : Tuple2[RiftCheckedLeaf^{owner}, RiftCheckedLeaf^{owner}]^{owner} = {
            val result
                : Tuple2[RiftCheckedLeaf^{owner}, RiftCheckedLeaf^{owner}]^{owner} =
              Tuple2(
                new RiftCheckedLeaf(base + offset),
                new RiftCheckedLeaf(1)
              )
            result
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makePair = make(using region)
        val pair = makePair(40)
        pair._1.value + pair._2.value +
          (System.identityHashCode(makePair) & 0) +
          (System.identityHashCode(pair) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(81, total)
      assertTrue(
        s"expected type-only lexical helper returned Tuple2 to stay on the heap without a runtime owner value, observed $delta region objects",
        delta < 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyLocalHelperReturnedEitherWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Either[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int)
              : Either[RiftCheckedLeaf^{owner}, RiftCheckedLeaf^{owner}]^{owner} = {
            val keepOwner = System.identityHashCode(owner) & 0
            val result
                : Either[RiftCheckedLeaf^{owner}, RiftCheckedLeaf^{owner}]^{owner} =
              if offset >= 0 then
                Left(new RiftCheckedLeaf(base + offset + keepOwner))
              else Right(new RiftCheckedLeaf(base + keepOwner))
            result
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeEither = make(using region)
        val either = makeEither(21)
        val value =
          either match
            case Left(leaf)  => leaf.value
            case Right(leaf) => leaf.value
        value +
          (System.identityHashCode(makeEither) & 0) +
          (System.identityHashCode(either) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body local helper returned Either case and payload to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionDoesNotInferClosureBodyLocalHelperTypeOnlyReturnedEither()
      : Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      def make(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, Either[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r}]^{r} =
        (base: Int) => {
          val owner = r
          def build(offset: Int)
              : Either[RiftCheckedLeaf^{owner}, RiftCheckedLeaf^{owner}]^{owner} = {
            val result
                : Either[RiftCheckedLeaf^{owner}, RiftCheckedLeaf^{owner}]^{owner} =
              if offset >= 0 then Left(new RiftCheckedLeaf(base + offset))
              else Right(new RiftCheckedLeaf(base))
            result
          }
          build(base)
        }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeEither = make(using region)
        val either = makeEither(21)
        val value =
          either match
            case Left(leaf)  => leaf.value
            case Right(leaf) => leaf.value
        value +
          (System.identityHashCode(makeEither) & 0) +
          (System.identityHashCode(either) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected type-only lexical helper returned Either case to stay on the heap without a runtime owner value, observed $delta region objects",
        delta < 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodReturnedSelectedLocalClosureBodyAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(flag: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} = {
        val first =
          (value: Int) => {
            val owner = r
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(value)
            leaf
          }
        val second =
          (value: Int) => {
            val owner = r
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(value + 1)
            leaf
          }
        val keep =
          System.identityHashCode(first) + System.identityHashCode(second)
        val selected = if (flag || keep != 0) first else second
        selected
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(true)(using region)
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned selected local captured-owner closures and body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodReturnedForwardedSelectedLocalClosureBodyAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(flag: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Function1[Int, RiftCheckedLeaf^{r}]^{r} = {
        val first =
          (value: Int) => {
            val owner = r
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(value)
            leaf
          }
        val second =
          (value: Int) => {
            val owner = r
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(value + 1)
            leaf
          }
        val keep =
          System.identityHashCode(first) + System.identityHashCode(second)
        val selected = if (flag || keep != 0) first else second
        val forwarded = selected
        forwarded
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf = make(true)(using region)
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned forwarded selected local captured-owner closures and body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodArgumentLocalClosureBodyAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def consume(using r: RiftRegion.ScopedRegion^)(
          makeLeaf: Function1[Int, RiftCheckedLeaf^{r}]^{r}
      ): Int = {
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf =
          (value: Int) => {
            val owner = region
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(value)
            leaf
          }
        consume(using region)(makeLeaf)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-argument local captured-owner closure object and body allocation to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodArgumentBranchInlineClosureBodyAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def consume(using r: RiftRegion.ScopedRegion^)(
          makeLeaf: Function1[Int, RiftCheckedLeaf^{r}]^{r}
      ): Int = {
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        consume(using region)(
          if (System.identityHashCode(region) != 0)
            (value: Int) => {
              val owner = region
              val leaf: RiftCheckedLeaf^{owner} =
                new RiftCheckedLeaf(value)
              leaf
            }
          else
            (value: Int) => {
              val owner = region
              val leaf: RiftCheckedLeaf^{owner} =
                new RiftCheckedLeaf(value + 1)
              leaf
            }
        )
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch-selected inline captured-owner closure body allocation to be region allocated, observed $delta region objects",
        delta >= 1L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodArgumentMatchInlineClosureBodyAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def consume(using r: RiftRegion.ScopedRegion^)(
          makeLeaf: Function1[Int, RiftCheckedLeaf^{r}]^{r}
      ): Int = {
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val selector = System.identityHashCode(region) & 0
        consume(using region)(
          selector match {
            case 0 =>
              (value: Int) => {
                val owner = region
                val leaf: RiftCheckedLeaf^{owner} =
                  new RiftCheckedLeaf(value)
                leaf
              }
            case _ =>
              (value: Int) => {
                val owner = region
                val leaf: RiftCheckedLeaf^{owner} =
                  new RiftCheckedLeaf(value + 1)
                leaf
              }
          }
        )
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected match-selected inline captured-owner closure body allocation to be region allocated, observed $delta region objects",
        delta >= 1L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodArgumentBranchLocalClosureBodyAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def consume(using r: RiftRegion.ScopedRegion^)(
          makeLeaf: Function1[Int, RiftCheckedLeaf^{r}]^{r}
      ): Int = {
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val first =
          (value: Int) => {
            val owner = region
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(value)
            leaf
          }
        val second =
          (value: Int) => {
            val owner = region
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(value + 1)
            leaf
          }
        consume(using region)(
          if (System.identityHashCode(first) != 0) first else second
        ) +
          (System.identityHashCode(first) & 0) +
          (System.identityHashCode(second) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch-selected local captured-owner closures and body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodArgumentMatchLocalClosureBodyAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def consume(using r: RiftRegion.ScopedRegion^)(
          makeLeaf: Function1[Int, RiftCheckedLeaf^{r}]^{r}
      ): Int = {
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val first =
          (value: Int) => {
            val owner = region
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(value)
            leaf
          }
        val second =
          (value: Int) => {
            val owner = region
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(value + 1)
            leaf
          }
        val selector = System.identityHashCode(first) & 0
        consume(using region)(
          selector match {
            case 0 => first
            case _ => second
          }
        ) +
          (System.identityHashCode(first) & 0) +
          (System.identityHashCode(second) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected match-selected local captured-owner closures and body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodArgumentSelectedLocalClosureBodyAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      def consume(using r: RiftRegion.ScopedRegion^)(
          makeLeaf: Function1[Int, RiftCheckedLeaf^{r}]^{r}
      ): Int = {
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val first =
          (value: Int) => {
            val owner = region
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(value)
            leaf
          }
        val second =
          (value: Int) => {
            val owner = region
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(value + 1)
            leaf
          }
        val selected =
          if (System.identityHashCode(first) != 0) first else second
        consume(using region)(selected) +
          (System.identityHashCode(first) & 0) +
          (System.identityHashCode(second) & 0) +
          (System.identityHashCode(selected) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected selected local captured-owner closure wrappers and body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLocalSelectedInlineClosureBodyAllocationWithCapturedOwnerTerm()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val makeLeaf: Function1[Int, RiftCheckedLeaf^{region}]^{region} =
          if (System.identityHashCode(region) != 0)
            (value: Int) => {
              val owner = region
              val leaf: RiftCheckedLeaf^{owner} =
                new RiftCheckedLeaf(value)
              leaf
            }
          else
            (value: Int) => {
              val owner = region
              val leaf: RiftCheckedLeaf^{owner} =
                new RiftCheckedLeaf(value + 1)
              leaf
            }
        val leaf = makeLeaf(40)
        leaf.value + 2 +
          (System.identityHashCode(makeLeaf) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected local selected inline captured-owner closure body allocation to be region allocated, observed $delta region objects",
        delta >= 1L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersWrapperInlineClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class Wrapper(
            val make: Function1[Int, RiftCheckedLeaf^{region}]^{region}
        )
        val owner = region
        val wrapper: Wrapper^{region} =
          new Wrapper((n: Int) => {
            val keepOwner = System.identityHashCode(owner) & 0
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(n + 40 + keepOwner)
            leaf
          })
        val leaf = wrapper.make(2)
        leaf.value +
          (System.identityHashCode(wrapper) & 0) +
          (System.identityHashCode(wrapper.make) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected region wrapper plus inline closure value and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersWrapperSelectedClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class Wrapper(
            val make: Function1[Int, RiftCheckedLeaf^{region}]^{region}
        )
        val owner = region
        val first = (n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 40 + keepOwner)
          leaf
        }
        val second = (n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 41 + keepOwner)
          leaf
        }
        val keep =
          System.identityHashCode(first) + System.identityHashCode(second)
        val selected = if ((keep & 1) == 0) first else second
        val expected = if ((keep & 1) == 0) 42 else 43
        val wrapper: Wrapper^{region} = new Wrapper(selected)
        val leaf = wrapper.make(2)
        val identity =
          System.identityHashCode(first) ^
            System.identityHashCode(second) ^
            System.identityHashCode(selected) ^
            System.identityHashCode(wrapper) ^
            System.identityHashCode(leaf)
        leaf.value - expected + 42 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected region wrapper plus selected closure values and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodReturnedSomeInlineClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using
          r: RiftRegion.ScopedRegion^
      ): Option[Function1[Int, RiftCheckedLeaf^{r}]^{r}]^{r} = {
        val owner = r
        Some((n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 40 + keepOwner)
          leaf
        })
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val maybe = make(using region)
        val fn = maybe.get
        val leaf = fn(2)
        leaf.value +
          (System.identityHashCode(maybe) & 0) +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned Some plus inline closure value and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodReturnedSomeSelectedClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(flag: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Option[Function1[Int, RiftCheckedLeaf^{r}]^{r}]^{r} = {
        val owner = r
        val first = (n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 40 + keepOwner)
          leaf
        }
        val second = (n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 41 + keepOwner)
          leaf
        }
        val selected = if flag then first else second
        Some(selected)
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val flag = System.identityHashCode(region) != 0
        val maybe = make(flag)(using region)
        val fn = maybe.get
        val leaf = fn(2)
        val expected = if flag then 42 else 43
        leaf.value - expected + 42 +
          (System.identityHashCode(maybe) & 0) +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned Some plus selected closure values and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyEitherInlineClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val chooseLeft = (System.identityHashCode(region) & 1) == 0
        val make: Function1[
          Int,
          Either[
            Function1[Int, RiftCheckedLeaf^{region}]^{region},
            Function1[Int, RiftCheckedLeaf^{region}]^{region}
          ]^{region}
        ]^{region} =
          (base: Int) =>
            val owner = region
            if chooseLeft then
              Left(
                (n: Int) => {
                  val keepOwner = System.identityHashCode(owner) & 0
                  val leaf: RiftCheckedLeaf^{owner} =
                    new RiftCheckedLeaf(base + n + keepOwner)
                  leaf
                }
              )
            else
              Right(
                (n: Int) => {
                  val keepOwner = System.identityHashCode(owner) & 0
                  val leaf: RiftCheckedLeaf^{owner} =
                    new RiftCheckedLeaf(base + n + 1 + keepOwner)
                  leaf
                }
              )
        val either = make(40)
        val fn = either match {
          case Left(value)  => value
          case Right(value) => value
        }
        val leaf = fn(2)
        val expected = if chooseLeft then 42 else 43
        leaf.value - expected + 42 +
          (System.identityHashCode(make) & 0) +
          (System.identityHashCode(either) & 0) +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body returned Either plus inline closure value and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersClosureBodyEitherSelectedClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val chooseLeft = (System.identityHashCode(region) & 1) == 0
        val make: Function1[
          Boolean,
          Either[
            Function1[Int, RiftCheckedLeaf^{region}]^{region},
            Function1[Int, RiftCheckedLeaf^{region}]^{region}
          ]^{region}
        ]^{region} =
          (flag: Boolean) =>
            val owner = region
            val first = (n: Int) => {
              val keepOwner = System.identityHashCode(owner) & 0
              val leaf: RiftCheckedLeaf^{owner} =
                new RiftCheckedLeaf(n + 40 + keepOwner)
              leaf
            }
            val second = (n: Int) => {
              val keepOwner = System.identityHashCode(owner) & 0
              val leaf: RiftCheckedLeaf^{owner} =
                new RiftCheckedLeaf(n + 41 + keepOwner)
              leaf
            }
            if flag then Left(first) else Right(second)
        val either = make(chooseLeft)
        val fn = either match {
          case Left(value)  => value
          case Right(value) => value
        }
        val leaf = fn(2)
        val expected = if chooseLeft then 42 else 43
        leaf.value - expected + 42 +
          (System.identityHashCode(make) & 0) +
          (System.identityHashCode(either) & 0) +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected closure-body returned Either plus selected closure values and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 5L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodArgumentWrapperInlineClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      final class Wrapper[A <: Object^](val make: Function1[Int, A]^)
      def consume(using r: RiftRegion.ScopedRegion^)(
          wrapper: Wrapper[RiftCheckedLeaf^{r}]^{r}
      ): Int = {
        val fn = wrapper.make
        val leaf = fn(2)
        leaf.value +
          (System.identityHashCode(wrapper) & 0) +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val owner = region
        consume(using region)(
          new Wrapper[RiftCheckedLeaf^{region}]((n: Int) => {
            val keepOwner = System.identityHashCode(owner) & 0
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(n + 40 + keepOwner)
            leaf
          })
        )
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-argument wrapper plus inline closure value and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodArgumentWrapperSelectedClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      final class Wrapper[A <: Object^](val make: Function1[Int, A]^)
      def consume(using r: RiftRegion.ScopedRegion^)(
          wrapper: Wrapper[RiftCheckedLeaf^{r}]^{r}
      ): Int = {
        val fn = wrapper.make
        val leaf = fn(2)
        leaf.value +
          (System.identityHashCode(wrapper) & 0) +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val owner = region
        val first = (n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 40 + keepOwner)
          leaf
        }
        val second = (n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 41 + keepOwner)
          leaf
        }
        val keep =
          System.identityHashCode(first) + System.identityHashCode(second)
        val selected = if ((keep & 1) == 0) first else second
        val expected = if ((keep & 1) == 0) 42 else 43
        consume(using region)(new Wrapper[RiftCheckedLeaf^{region}](selected)) -
          expected + 42 +
          (System.identityHashCode(first) & 0) +
          (System.identityHashCode(second) & 0) +
          (System.identityHashCode(selected) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-argument wrapper plus selected closure values and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodReturnedWrapperInlineClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      final class Wrapper[A <: Object^](val make: Function1[Int, A]^)
      def makeWrapper(using
          r: RiftRegion.ScopedRegion^
      ): Wrapper[RiftCheckedLeaf^{r}]^{r} = {
        val owner = r
        new Wrapper[RiftCheckedLeaf^{r}]((n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 40 + keepOwner)
          leaf
        })
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val wrapper: Wrapper[RiftCheckedLeaf^{region}]^{region} =
          makeWrapper(using region)
        val fn = wrapper.make
        val leaf = fn(2)
        leaf.value +
          (System.identityHashCode(wrapper) & 0) +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned wrapper plus inline closure value and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodReturnedWrapperSelectedClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      final class Wrapper[A <: Object^](val make: Function1[Int, A]^)
      def makeWrapper(flag: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Wrapper[RiftCheckedLeaf^{r}]^{r} = {
        val owner = r
        val first = (n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 40 + keepOwner)
          leaf
        }
        val second = (n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 41 + keepOwner)
          leaf
        }
        val keep =
          System.identityHashCode(first) + System.identityHashCode(second)
        val selected = if flag then first else second
        new Wrapper[RiftCheckedLeaf^{r}](selected)
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val flag = (System.identityHashCode(region) & 1) == 0
        val wrapper: Wrapper[RiftCheckedLeaf^{region}]^{region} =
          makeWrapper(flag)(using region)
        val fn = wrapper.make
        val leaf = fn(2)
        val expected = if flag then 42 else 43
        leaf.value - expected + 42 +
          (System.identityHashCode(wrapper) & 0) +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned wrapper plus selected closure values and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedMethodReturnedWrapperInlineClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      final class Wrapper[A <: Object^](val make: Function1[Int, A]^)
      def makeWrapper(using
          r: RiftRegion.ScopedRegion^
      ): Wrapper[RiftCheckedLeaf^{r}]^{r} = {
        val owner = r
        new Wrapper[RiftCheckedLeaf^{r}]((n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 40 + keepOwner)
          leaf
        })
      }
      def forward(using
          r: RiftRegion.ScopedRegion^
      ): Wrapper[RiftCheckedLeaf^{r}]^{r} =
        makeWrapper(using r)

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val wrapper: Wrapper[RiftCheckedLeaf^{region}]^{region} =
          forward(using region)
        val fn = wrapper.make
        val leaf = fn(2)
        leaf.value +
          (System.identityHashCode(wrapper) & 0) +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected forwarded method-returned wrapper plus inline closure value and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersBranchForwardedMethodReturnedWrapperSelectedClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      final class Wrapper[A <: Object^](val make: Function1[Int, A]^)
      def makeWrapper(flag: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Wrapper[RiftCheckedLeaf^{r}]^{r} = {
        val owner = r
        val first = (n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 40 + keepOwner)
          leaf
        }
        val second = (n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 41 + keepOwner)
          leaf
        }
        val selected = if flag then first else second
        new Wrapper[RiftCheckedLeaf^{r}](selected)
      }
      def forward(flag: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Wrapper[RiftCheckedLeaf^{r}]^{r} =
        if flag then makeWrapper(true)(using r)
        else makeWrapper(false)(using r)

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val flag = (System.identityHashCode(region) & 1) == 0
        val wrapper: Wrapper[RiftCheckedLeaf^{region}]^{region} =
          forward(flag)(using region)
        val fn = wrapper.make
        val leaf = fn(2)
        val expected = if flag then 42 else 43
        leaf.value - expected + 42 +
          (System.identityHashCode(wrapper) & 0) +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch-forwarded method-returned wrapper plus selected closure values and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodReturnedSomeWrapperNestedPayloadAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      final class Wrapper[A <: Object^](val value: A)
      def makeWrapper(using
          r: RiftRegion.ScopedRegion^
      ): Option[Wrapper[RiftCheckedLeaf^{r}]^{r}]^{r} = {
        Some(new Wrapper[RiftCheckedLeaf^{r}](new RiftCheckedLeaf(40)))
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val maybe: Option[Wrapper[RiftCheckedLeaf^{region}]^{region}]^{region} =
          makeWrapper(using region)
        val wrapper: Wrapper[RiftCheckedLeaf^{region}]^{region} = maybe.get
        val leaf: RiftCheckedLeaf^{region} = wrapper.value
        leaf.value + 2 +
          (System.identityHashCode(maybe) & 0) +
          (System.identityHashCode(wrapper) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected method-returned Some plus wrapper and nested payload to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedMethodReturnedSomeWrapperNestedPayloadAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      final class Wrapper[A <: Object^](val value: A)

      def makeWrapper(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): Option[Wrapper[RiftCheckedLeaf^{r}]^{r}]^{r} =
        Some(new Wrapper[RiftCheckedLeaf^{r}](new RiftCheckedLeaf(value)))

      def forward(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): Option[Wrapper[RiftCheckedLeaf^{r}]^{r}]^{r} =
        makeWrapper(value)(using r)

      def branch(value: Int, flag: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Option[Wrapper[RiftCheckedLeaf^{r}]^{r}]^{r} =
        if flag then forward(value)(using r)
        else makeWrapper(value + 1)(using r)

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val direct
            : Option[Wrapper[RiftCheckedLeaf^{region}]^{region}]^{region} =
          forward(10)(using region)
        val forwardedTrue
            : Option[Wrapper[RiftCheckedLeaf^{region}]^{region}]^{region} =
          branch(20, true)(using region)
        val forwardedFalse
            : Option[Wrapper[RiftCheckedLeaf^{region}]^{region}]^{region} =
          branch(30, false)(using region)
        val directWrapper
            : Wrapper[RiftCheckedLeaf^{region}]^{region} =
          direct.get
        val forwardedTrueWrapper
            : Wrapper[RiftCheckedLeaf^{region}]^{region} =
          forwardedTrue.get
        val forwardedFalseWrapper
            : Wrapper[RiftCheckedLeaf^{region}]^{region} =
          forwardedFalse.get
        val directLeaf: RiftCheckedLeaf^{region} = directWrapper.value
        val forwardedTrueLeaf: RiftCheckedLeaf^{region} =
          forwardedTrueWrapper.value
        val forwardedFalseLeaf: RiftCheckedLeaf^{region} =
          forwardedFalseWrapper.value
        directLeaf.value + forwardedTrueLeaf.value + forwardedFalseLeaf.value -
          10 - 20 - 31 + 42 +
          (System.identityHashCode(direct) & 0) +
          (System.identityHashCode(forwardedTrue) & 0) +
          (System.identityHashCode(forwardedFalse) & 0) +
          (System.identityHashCode(directWrapper) & 0) +
          (System.identityHashCode(forwardedTrueWrapper) & 0) +
          (System.identityHashCode(forwardedFalseWrapper) & 0) +
          (System.identityHashCode(directLeaf) & 0) +
          (System.identityHashCode(forwardedTrueLeaf) & 0) +
          (System.identityHashCode(forwardedFalseLeaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected direct and branch-forwarded method-returned Some plus wrapper and nested payloads to be region allocated, observed $delta region objects",
        delta >= 9L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedMethodReturnedOptionWrapperNestedPayloadAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      final class Wrapper[A <: Object^](val value: A)

      def makeWrapper(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): Option[Wrapper[RiftCheckedLeaf^{r}]^{r}]^{r} =
        Option(new Wrapper[RiftCheckedLeaf^{r}](new RiftCheckedLeaf(value)))

      def forward(value: Int)(using
          r: RiftRegion.ScopedRegion^
      ): Option[Wrapper[RiftCheckedLeaf^{r}]^{r}]^{r} =
        makeWrapper(value)(using r)

      def branch(value: Int, flag: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Option[Wrapper[RiftCheckedLeaf^{r}]^{r}]^{r} =
        if flag then forward(value)(using r)
        else makeWrapper(value + 1)(using r)

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val direct
            : Option[Wrapper[RiftCheckedLeaf^{region}]^{region}]^{region} =
          forward(10)(using region)
        val forwardedTrue
            : Option[Wrapper[RiftCheckedLeaf^{region}]^{region}]^{region} =
          branch(20, true)(using region)
        val forwardedFalse
            : Option[Wrapper[RiftCheckedLeaf^{region}]^{region}]^{region} =
          branch(30, false)(using region)
        val directWrapper
            : Wrapper[RiftCheckedLeaf^{region}]^{region} =
          direct.get
        val forwardedTrueWrapper
            : Wrapper[RiftCheckedLeaf^{region}]^{region} =
          forwardedTrue.get
        val forwardedFalseWrapper
            : Wrapper[RiftCheckedLeaf^{region}]^{region} =
          forwardedFalse.get
        val directLeaf: RiftCheckedLeaf^{region} = directWrapper.value
        val forwardedTrueLeaf: RiftCheckedLeaf^{region} =
          forwardedTrueWrapper.value
        val forwardedFalseLeaf: RiftCheckedLeaf^{region} =
          forwardedFalseWrapper.value
        directLeaf.value + forwardedTrueLeaf.value + forwardedFalseLeaf.value -
          10 - 20 - 31 + 42 +
          (System.identityHashCode(direct) & 0) +
          (System.identityHashCode(forwardedTrue) & 0) +
          (System.identityHashCode(forwardedFalse) & 0) +
          (System.identityHashCode(directWrapper) & 0) +
          (System.identityHashCode(forwardedTrueWrapper) & 0) +
          (System.identityHashCode(forwardedFalseWrapper) & 0) +
          (System.identityHashCode(directLeaf) & 0) +
          (System.identityHashCode(forwardedTrueLeaf) & 0) +
          (System.identityHashCode(forwardedFalseLeaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected direct and branch-forwarded method-returned Option.apply plus wrapper and nested payloads to be region allocated, observed $delta region objects",
        delta >= 9L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedMethodReturnedEitherWrapperNestedPayloadAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      final class Wrapper[A <: Object^](val value: A)

      def makeWrapper(value: Int, left: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Either[Wrapper[RiftCheckedLeaf^{r}]^{r}, Wrapper[RiftCheckedLeaf^{r}]^{r}]^{r} =
        if left then
          Left(new Wrapper[RiftCheckedLeaf^{r}](new RiftCheckedLeaf(value)))
        else
          Right(new Wrapper[RiftCheckedLeaf^{r}](new RiftCheckedLeaf(value + 1)))

      def forward(value: Int, left: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Either[Wrapper[RiftCheckedLeaf^{r}]^{r}, Wrapper[RiftCheckedLeaf^{r}]^{r}]^{r} =
        makeWrapper(value, left)(using r)

      def branch(value: Int, flag: Boolean, left: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Either[Wrapper[RiftCheckedLeaf^{r}]^{r}, Wrapper[RiftCheckedLeaf^{r}]^{r}]^{r} =
        if flag then forward(value, left)(using r)
        else makeWrapper(value + 1, left)(using r)

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val direct
            : Either[
              Wrapper[RiftCheckedLeaf^{region}]^{region},
              Wrapper[RiftCheckedLeaf^{region}]^{region}
            ]^{region} =
          forward(10, true)(using region)
        val forwardedTrue
            : Either[
              Wrapper[RiftCheckedLeaf^{region}]^{region},
              Wrapper[RiftCheckedLeaf^{region}]^{region}
            ]^{region} =
          branch(20, true, false)(using region)
        val forwardedFalse
            : Either[
              Wrapper[RiftCheckedLeaf^{region}]^{region},
              Wrapper[RiftCheckedLeaf^{region}]^{region}
            ]^{region} =
          branch(30, false, true)(using region)

        42 + (System.identityHashCode(direct) & 0) +
          (System.identityHashCode(forwardedTrue) & 0) +
          (System.identityHashCode(forwardedFalse) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected direct and branch-forwarded method-returned Either plus wrapper and nested payloads to be region allocated, observed $delta region objects",
        delta >= 9L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedMethodReturnedOptionEitherNestedPayloadAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      def makeOption(value: Int, left: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Option[Either[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r}]^{r} =
        if left then Option(Left(new RiftCheckedLeaf(value)))
        else Option(Right(new RiftCheckedLeaf(value + 1)))

      def forward(value: Int, left: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Option[Either[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r}]^{r} =
        makeOption(value, left)(using r)

      def branch(value: Int, flag: Boolean, left: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Option[Either[RiftCheckedLeaf^{r}, RiftCheckedLeaf^{r}]^{r}]^{r} =
        if flag then forward(value, left)(using r)
        else makeOption(value + 1, left)(using r)

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val direct
            : Option[
              Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
            ]^{region} =
          forward(10, true)(using region)
        val forwardedTrue
            : Option[
              Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
            ]^{region} =
          branch(20, true, false)(using region)
        val forwardedFalse
            : Option[
              Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
            ]^{region} =
          branch(30, false, true)(using region)

        42 + (System.identityHashCode(direct) & 0) +
          (System.identityHashCode(forwardedTrue) & 0) +
          (System.identityHashCode(forwardedFalse) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected direct and branch-forwarded method-returned Option.apply plus Either and nested payloads to be region allocated, observed $delta region objects",
        delta >= 9L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedMethodReturnedEitherOptionNestedPayloadAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      def makeEither(value: Int, left: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Either[Option[RiftCheckedLeaf^{r}]^{r}, Option[RiftCheckedLeaf^{r}]^{r}]^{r} =
        if left then Left(Option(new RiftCheckedLeaf(value)))
        else Right(Option(new RiftCheckedLeaf(value + 1)))

      def forward(value: Int, left: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Either[Option[RiftCheckedLeaf^{r}]^{r}, Option[RiftCheckedLeaf^{r}]^{r}]^{r} =
        makeEither(value, left)(using r)

      def branch(value: Int, flag: Boolean, left: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Either[Option[RiftCheckedLeaf^{r}]^{r}, Option[RiftCheckedLeaf^{r}]^{r}]^{r} =
        if flag then forward(value, left)(using r)
        else makeEither(value + 1, left)(using r)

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val direct
            : Either[
              Option[RiftCheckedLeaf^{region}]^{region},
              Option[RiftCheckedLeaf^{region}]^{region}
            ]^{region} =
          forward(10, true)(using region)
        val forwardedTrue
            : Either[
              Option[RiftCheckedLeaf^{region}]^{region},
              Option[RiftCheckedLeaf^{region}]^{region}
            ]^{region} =
          branch(20, true, false)(using region)
        val forwardedFalse
            : Either[
              Option[RiftCheckedLeaf^{region}]^{region},
              Option[RiftCheckedLeaf^{region}]^{region}
            ]^{region} =
          branch(30, false, true)(using region)

        42 + (System.identityHashCode(direct) & 0) +
          (System.identityHashCode(forwardedTrue) & 0) +
          (System.identityHashCode(forwardedFalse) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected direct and branch-forwarded method-returned Either plus Option.apply and nested payloads to be region allocated, observed $delta region objects",
        delta >= 9L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersSelectedMethodReturnedEitherOptionNestedPayloadAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      def makeEither(value: Int, left: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Either[Option[RiftCheckedLeaf^{r}]^{r}, Option[RiftCheckedLeaf^{r}]^{r}]^{r} = {
        val first
            : Either[
              Option[RiftCheckedLeaf^{r}]^{r},
              Option[RiftCheckedLeaf^{r}]^{r}
            ]^{r} =
          Left(Option(new RiftCheckedLeaf(value)))
        val second
            : Either[
              Option[RiftCheckedLeaf^{r}]^{r},
              Option[RiftCheckedLeaf^{r}]^{r}
            ]^{r} =
          Right(Option(new RiftCheckedLeaf(value + 1)))
        val selected
            : Either[
              Option[RiftCheckedLeaf^{r}]^{r},
              Option[RiftCheckedLeaf^{r}]^{r}
            ]^{r} =
          if left then first else second
        selected
      }

      def forward(value: Int, left: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Either[Option[RiftCheckedLeaf^{r}]^{r}, Option[RiftCheckedLeaf^{r}]^{r}]^{r} =
        makeEither(value, left)(using r)

      def branch(value: Int, flag: Boolean, left: Boolean)(using
          r: RiftRegion.ScopedRegion^
      ): Either[Option[RiftCheckedLeaf^{r}]^{r}, Option[RiftCheckedLeaf^{r}]^{r}]^{r} =
        if flag then forward(value, left)(using r)
        else makeEither(value + 1, left)(using r)

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val direct
            : Either[
              Option[RiftCheckedLeaf^{region}]^{region},
              Option[RiftCheckedLeaf^{region}]^{region}
            ]^{region} =
          makeEither(10, true)(using region)
        val forwardedTrue
            : Either[
              Option[RiftCheckedLeaf^{region}]^{region},
              Option[RiftCheckedLeaf^{region}]^{region}
            ]^{region} =
          branch(20, true, false)(using region)
        val forwardedFalse
            : Either[
              Option[RiftCheckedLeaf^{region}]^{region},
              Option[RiftCheckedLeaf^{region}]^{region}
            ]^{region} =
          branch(30, false, true)(using region)

        42 + (System.identityHashCode(direct) & 0) +
          (System.identityHashCode(forwardedTrue) & 0) +
          (System.identityHashCode(forwardedFalse) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected selected direct and forwarded method-returned Either plus Option.apply and nested payloads to be region allocated, observed $delta region objects",
        delta >= 18L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersOwnerAliasLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val owner = region
        val leaf: RiftCheckedLeaf^{owner} =
          new RiftCheckedLeaf(42)
        val identity = System.identityHashCode(leaf)
        leaf.value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(
        s"expected owner-alias local allocation to be region allocated, observed ${after - before} region objects",
        after - before >= 1L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersOwnerAliasMethodReturnedLocalNewPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      def make(using r: RiftRegion.ScopedRegion^): RiftCheckedLeaf^{r} = {
        val owner = r
        val leaf: RiftCheckedLeaf^{owner} =
          new RiftCheckedLeaf(42)
        val identity = System.identityHashCode(leaf)
        val result: RiftCheckedLeaf^{r} = leaf
        if ((identity & 0) == 0) result else result
      }

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        make(using region).value
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(
        s"expected owner-alias method-returned allocation to be region allocated, observed ${after - before} region objects",
        after - before >= 1L
      )
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

  @Test def scopedRegionInfersRegionOwnedArrayPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val leaves: Array[RiftCheckedLeaf^{region}]^{region} =
          new Array[RiftCheckedLeaf^{region}](2)
        val first: RiftCheckedLeaf^{region} = new RiftCheckedLeaf(20)
        val second: RiftCheckedLeaf^{region} = new RiftCheckedLeaf(21)
        leaves(0) = first
        leaves(1) = second

        val roots:
          Array[RiftRegion.HeapRoot[RiftCheckedMetadata]^{region}]^{region} =
          new Array[RiftRegion.HeapRoot[RiftCheckedMetadata]^{region}](1)
        roots(0) = RiftRegion.root(new RiftCheckedMetadata(1))

        val identity =
          System.identityHashCode(leaves) ^
            System.identityHashCode(roots) ^
            System.identityHashCode(first) ^
            System.identityHashCode(second)
        leaves(0).value + leaves(1).value + roots(0).value.value +
          (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(
        s"expected inferred arrays and region values to be region allocated, observed ${after - before} region objects",
        after - before >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineArrayStoreRegionObjectPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val leaves: Array[RiftCheckedLeaf^{region}]^{region} =
          new Array[RiftCheckedLeaf^{region}](2)
        leaves(0) = new RiftCheckedLeaf(20)
        leaves(1) = new RiftCheckedLeaf(22)

        val identity =
          System.identityHashCode(leaves) ^
            System.identityHashCode(leaves(0)) ^
            System.identityHashCode(leaves(1))
        leaves(0).value + leaves(1).value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inferred array plus inline store values to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineArrayStoreSomeFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val options: Array[Option[RiftCheckedLeaf^{region}]^{region}]^{region} =
          new Array[Option[RiftCheckedLeaf^{region}]^{region}](1)
        options(0) = Some(new RiftCheckedLeaf(42))

        val option = options(0)
        val identity =
          System.identityHashCode(options) ^
            System.identityHashCode(option) ^
            System.identityHashCode(option.get)
        option.get.value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inferred array plus inline Some store value to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineArrayStoreOptionApplyFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val options: Array[Option[RiftCheckedLeaf^{region}]^{region}]^{region} =
          new Array[Option[RiftCheckedLeaf^{region}]^{region}](1)
        options(0) = Option(new RiftCheckedLeaf(42))

        val option = options(0)
        val identity =
          System.identityHashCode(options) ^
            System.identityHashCode(option) ^
            System.identityHashCode(option.get)
        option.get.value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inferred array plus inline Option.apply store value to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineArrayStoreEitherFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val eitherItems: Array[
          Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
        ]^{region} =
          new Array[
            Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
          ](1)
        eitherItems(0) = Left(new RiftCheckedLeaf(42))

        val either = eitherItems(0)
        val leaf = either match
          case Left(value)  => value
          case Right(value) => value
        val identity =
          System.identityHashCode(eitherItems) ^
            System.identityHashCode(either) ^
            System.identityHashCode(leaf)
        leaf.value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inferred array plus inline Either store value to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineArrayStoreTuple2FactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val pairs: Array[
          Tuple2[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
        ]^{region} =
          new Array[
            Tuple2[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
          ](1)
        pairs(0) = Tuple2(new RiftCheckedLeaf(40), new RiftCheckedLeaf(2))

        val pair = pairs(0)
        val identity =
          System.identityHashCode(pairs) ^
            System.identityHashCode(pair) ^
            System.identityHashCode(pair._1) ^
            System.identityHashCode(pair._2)
        pair._1.value + pair._2.value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inferred array plus inline Tuple2 store value to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersBranchMatchArrayStoreFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val flag = (System.identityHashCode(region) & 1) == 0
        val selector = System.identityHashCode(region) & 1

        val leaves: Array[RiftCheckedLeaf^{region}]^{region} =
          new Array[RiftCheckedLeaf^{region}](1)
        leaves(0) =
          if flag then new RiftCheckedLeaf(40) else new RiftCheckedLeaf(41)

        val options: Array[
          Option[RiftCheckedLeaf^{region}]^{region}
        ]^{region} =
          new Array[Option[RiftCheckedLeaf^{region}]^{region}](1)
        options(0) =
          selector match
            case 0 => Option(new RiftCheckedLeaf(1))
            case _ => Option(new RiftCheckedLeaf(2))

        val pairs: Array[
          Tuple2[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
        ]^{region} =
          new Array[
            Tuple2[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
          ](1)
        pairs(0) =
          if flag then Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(1))
          else Tuple2(new RiftCheckedLeaf(2), new RiftCheckedLeaf(2))

        val expectedLeaf = if flag then 40 else 41
        val expectedOption = if selector == 0 then 1 else 2
        val expectedPair = if flag then 2 else 4
        val identity =
          System.identityHashCode(leaves) ^
            System.identityHashCode(leaves(0)) ^
            System.identityHashCode(options) ^
            System.identityHashCode(options(0)) ^
            System.identityHashCode(pairs) ^
            System.identityHashCode(pairs(0))
        leaves(0).value + options(0).get.value +
          pairs(0)._1.value + pairs(0)._2.value -
          expectedLeaf - expectedOption - expectedPair + 42 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch/match array-store factories and payloads to be region allocated, observed $delta region objects",
        delta >= 9L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersSelectedArrayStoreEitherFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val flag = (System.identityHashCode(region) & 1) == 0
        val eitherItems: Array[
          Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
        ]^{region} =
          new Array[
            Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
          ](1)
        val first: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} =
          Left(new RiftCheckedLeaf(40))
        val second: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} =
          Right(new RiftCheckedLeaf(41))
        val selected = if flag then first else second
        eitherItems(0) = selected

        val either = eitherItems(0)
        val leaf = either match
          case Left(value)  => value
          case Right(value) => value
        val expected = if flag then 40 else 41
        val identity =
          System.identityHashCode(eitherItems) ^
            System.identityHashCode(first) ^
            System.identityHashCode(second) ^
            System.identityHashCode(either) ^
            System.identityHashCode(leaf)
        leaf.value - expected + 42 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected selected array-store Either factories and payloads to be region allocated, observed $delta region objects",
        delta >= 5L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineArrayStoreClosurePlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val functions: Array[Function1[Int, Int]^{region}]^{region} =
          new Array[Function1[Int, Int]^{region}](1)
        functions(0) = (n: Int) => n + 40

        val fn = functions(0)
        val identity =
          System.identityHashCode(functions) ^ System.identityHashCode(fn)
        invokeRegionFunction(fn, 2) + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inferred array plus inline closure store value to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersInlineArrayStoreClosureBodyAllocation(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val owner = region
        val functions: Array[Function1[Int, RiftCheckedLeaf^{region}]^{region}]^{region} =
          new Array[Function1[Int, RiftCheckedLeaf^{region}]^{region}](1)
        functions(0) = (n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 40 + keepOwner)
          leaf
        }

        val fn = functions(0)
        val leaf = fn(2)
        leaf.value +
          (System.identityHashCode(functions) & 0) +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inferred array plus inline closure store value and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersSelectedArrayStoreClosurePlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val functions: Array[Function1[Int, Int]^{region}]^{region} =
          new Array[Function1[Int, Int]^{region}](1)
        val first = (n: Int) => n + 40
        val second = (n: Int) => n + 41
        val keep =
          System.identityHashCode(first) + System.identityHashCode(second)
        val selected =
          if ((keep & 1) == 0) first else second
        functions(0) = selected

        val expected = if ((keep & 1) == 0) 42 else 43
        val fn = functions(0)
        val identity =
          System.identityHashCode(functions) ^
            System.identityHashCode(first) ^
            System.identityHashCode(second) ^
            System.identityHashCode(fn)
        invokeRegionFunction(fn, 2) - expected + 42 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected inferred array plus selected closure store values to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersSelectedArrayStoreSyntheticFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val someItems: Array[Option[RiftCheckedLeaf^{region}]^{region}]^{region} =
          new Array[Option[RiftCheckedLeaf^{region}]^{region}](1)
        val someFirst = Some(new RiftCheckedLeaf(40))
        val someSecond = Some(new RiftCheckedLeaf(41))
        val someKeep =
          System.identityHashCode(someFirst) +
            System.identityHashCode(someSecond)
        val someSelected =
          if ((someKeep & 1) == 0) someFirst else someSecond
        someItems(0) = someSelected

        val optionItems
            : Array[Option[RiftCheckedLeaf^{region}]^{region}]^{region} =
          new Array[Option[RiftCheckedLeaf^{region}]^{region}](1)
        val optionFirst = Option(new RiftCheckedLeaf(1))
        val optionSecond = Option(new RiftCheckedLeaf(2))
        val optionKeep =
          System.identityHashCode(optionFirst) +
            System.identityHashCode(optionSecond)
        val optionSelected =
          if ((optionKeep & 1) == 0) optionFirst else optionSecond
        optionItems(0) = optionSelected

        val pairItems: Array[
          Tuple2[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
        ]^{region} =
          new Array[
            Tuple2[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
          ](1)
        val pairFirst = Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(1))
        val pairSecond = Tuple2(new RiftCheckedLeaf(2), new RiftCheckedLeaf(2))
        val pairKeep =
          System.identityHashCode(pairFirst) + System.identityHashCode(pairSecond)
        val pairSelected =
          if ((pairKeep & 1) == 0) pairFirst else pairSecond
        pairItems(0) = pairSelected

        val expectedSome = if ((someKeep & 1) == 0) 40 else 41
        val expectedOption = if ((optionKeep & 1) == 0) 1 else 2
        val expectedPair = if ((pairKeep & 1) == 0) 2 else 4
        someItems(0).get.value +
          optionItems(0).get.value +
          pairItems(0)._1.value +
          pairItems(0)._2.value -
          expectedSome - expectedOption - expectedPair + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected selected array-store Some/Option/Tuple2 factories and payloads to be region allocated, observed $delta region objects",
        delta >= 14L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMethodReturnedRegionOwnedArrayPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make(using
            r: RiftRegion.ScopedRegion^
        ): Array[RiftCheckedLeaf^{r}]^{r} =
          val leaves: Array[RiftCheckedLeaf^{r}]^{r} =
            new Array[RiftCheckedLeaf^{r}](2)
          val first: RiftCheckedLeaf^{r} = new RiftCheckedLeaf(20)
          val second: RiftCheckedLeaf^{r} = new RiftCheckedLeaf(21)
          leaves(0) = first
          leaves(1) = second
          leaves

        val leaves = make(using region)
        val identity =
          System.identityHashCode(leaves) ^
            System.identityHashCode(leaves(0)) ^
            System.identityHashCode(leaves(1))
        leaves(0).value + leaves(1).value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(41, total)
      assertTrue(
        s"expected method-returned array and region values to be region allocated, observed ${after - before} region objects",
        after - before >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersForwardedMethodReturnedRegionOwnedArrayPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make(using
            r: RiftRegion.ScopedRegion^
        ): Array[RiftCheckedLeaf^{r}]^{r} =
          val leaves: Array[RiftCheckedLeaf^{r}]^{r} =
            new Array[RiftCheckedLeaf^{r}](2)
          val first: RiftCheckedLeaf^{r} = new RiftCheckedLeaf(20)
          val second: RiftCheckedLeaf^{r} = new RiftCheckedLeaf(21)
          leaves(0) = first
          leaves(1) = second
          leaves

        def wrap(using r: RiftRegion.ScopedRegion^): Array[RiftCheckedLeaf^{r}]^{r} =
          val leaves = make(using r)
          leaves

        val leaves = wrap(using region)
        val identity =
          System.identityHashCode(leaves) ^
            System.identityHashCode(leaves(0)) ^
            System.identityHashCode(leaves(1))
        leaves(0).value + leaves(1).value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(41, total)
      assertTrue(
        s"expected forwarded method-returned array and region values to be region allocated, observed ${after - before} region objects",
        after - before >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersBranchForwardedMethodReturnedRegionOwnedArrayPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make(using
            r: RiftRegion.ScopedRegion^
        ): Array[RiftCheckedLeaf^{r}]^{r} =
          val leaves: Array[RiftCheckedLeaf^{r}]^{r} =
            new Array[RiftCheckedLeaf^{r}](2)
          val first: RiftCheckedLeaf^{r} = new RiftCheckedLeaf(20)
          val second: RiftCheckedLeaf^{r} = new RiftCheckedLeaf(21)
          leaves(0) = first
          leaves(1) = second
          leaves

        def wrap(
            flag: Boolean
        )(using r: RiftRegion.ScopedRegion^): Array[RiftCheckedLeaf^{r}]^{r} =
          if flag then make(using r) else make(using r)

        val leaves = wrap(true)(using region)
        val identity =
          System.identityHashCode(leaves) ^
            System.identityHashCode(leaves(0)) ^
            System.identityHashCode(leaves(1))
        leaves(0).value + leaves(1).value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(41, total)
      assertTrue(
        s"expected branch-forwarded method-returned array and region values to be region allocated, observed ${after - before} region objects",
        after - before >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersMatchForwardedMethodReturnedRegionOwnedArrayPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        def make(using
            r: RiftRegion.ScopedRegion^
        ): Array[RiftCheckedLeaf^{r}]^{r} =
          val leaves: Array[RiftCheckedLeaf^{r}]^{r} =
            new Array[RiftCheckedLeaf^{r}](2)
          val first: RiftCheckedLeaf^{r} = new RiftCheckedLeaf(20)
          val second: RiftCheckedLeaf^{r} = new RiftCheckedLeaf(21)
          leaves(0) = first
          leaves(1) = second
          leaves

        def wrap(
            selector: Int
        )(using r: RiftRegion.ScopedRegion^): Array[RiftCheckedLeaf^{r}]^{r} =
          selector match {
            case 0 => make(using r)
            case _ => make(using r)
          }

        val leaves = wrap(0)(using region)
        val identity =
          System.identityHashCode(leaves) ^
            System.identityHashCode(leaves(0)) ^
            System.identityHashCode(leaves(1))
        leaves(0).value + leaves(1).value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(41, total)
      assertTrue(
        s"expected match-forwarded method-returned array and region values to be region allocated, observed ${after - before} region objects",
        after - before >= 3L
      )
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

  @Test def scopedRegionInfersPriorityQueueLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val plain = RiftRegion.regionPriorityQueue[RiftCheckedLeaf](1)
        val plainLow = new RiftCheckedLeaf(10)
        val plainHigh = new RiftCheckedLeaf(20)
        region.push(plain, plainLow, 1L)
        RiftRegion.push(region, plain, plainHigh, 2L)

        val indexed = RiftRegion.regionIndexedPriorityQueue[RiftCheckedLeaf](4, 1)
        val indexedLow = new RiftCheckedLeaf(3)
        val indexedHigh = new RiftCheckedLeaf(4)
        region.put(indexed, 1, indexedLow, 1L)
        RiftRegion.put(region, indexed, 2, indexedHigh, 2L)

        val longIndexed =
          RiftRegion.regionLongIndexedPriorityQueue[RiftCheckedLeaf](1, 4)
        val longLow = new RiftCheckedLeaf(0)
        val longHigh = new RiftCheckedLeaf(1)
        region.put(longIndexed, 10L, longLow, 1L)
        RiftRegion.put(region, longIndexed, 20L, longHigh, 2L)

        region.pop(plain).value +
          RiftRegion.pop(region, plain).value +
          region.peek(indexed).value +
          RiftRegion.get(region, indexed, 1).value +
          region.peek(longIndexed).value +
          RiftRegion.get(region, longIndexed, 10L).value +
          region.length(plain) +
          region.length(indexed) +
          region.length(longIndexed)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(after - before >= 6L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPriorityQueueSelectedLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val queue = RiftRegion.regionPriorityQueue[RiftCheckedLeaf](1)
        val low = new RiftCheckedLeaf(40)
        val high = new RiftCheckedLeaf(42)
        val keep = System.identityHashCode(low) + System.identityHashCode(high)
        val selected = if ((keep & 1) == 0) low else high
        val expected = if ((keep & 1) == 0) 40 else 42
        region.push(queue, selected, 1L)
        region.peek(queue).value - expected + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected selected priority-queue candidate allocations to be region allocated, observed $delta region objects",
        delta >= 2L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPriorityQueueInlineClosurePlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val queue =
          RiftRegion.regionPriorityQueue[Function1[Int, Int]^{region}](1)
        region.push(queue, (n: Int) => n + 40, 1L)

        val fn = region.peek(queue)
        val identity = System.identityHashCode(fn)
        invokeRegionFunction(fn, 2) + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected priority-queue backing arrays plus inline closure value to be region allocated, observed $delta region objects",
        delta >= 3L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPriorityQueueInlineClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val owner = region
        val queue =
          RiftRegion.regionPriorityQueue[
            Function1[Int, RiftCheckedLeaf^{region}]^{region}
          ](1)
        region.push(
          queue,
          (n: Int) => {
            val keepOwner = System.identityHashCode(owner) & 0
            val leaf: RiftCheckedLeaf^{owner} =
              new RiftCheckedLeaf(n + 40 + keepOwner)
            leaf
          },
          1L
        )

        val fn = region.peek(queue)
        val leaf = fn(2)
        leaf.value +
          (System.identityHashCode(fn) & 0) +
          (System.identityHashCode(leaf) & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected priority-queue backing arrays plus inline closure value and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPriorityQueueSelectedClosureBodyAllocation()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val owner = region
        val queue =
          RiftRegion.regionPriorityQueue[
            Function1[Int, RiftCheckedLeaf^{region}]^{region}
          ](1)
        val first = (n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 40 + keepOwner)
          leaf
        }
        val second = (n: Int) => {
          val keepOwner = System.identityHashCode(owner) & 0
          val leaf: RiftCheckedLeaf^{owner} =
            new RiftCheckedLeaf(n + 41 + keepOwner)
          leaf
        }
        val keep =
          System.identityHashCode(first) + System.identityHashCode(second)
        val selected = if ((keep & 1) == 0) first else second
        val expected = if ((keep & 1) == 0) 42 else 43
        region.push(queue, selected, 1L)

        val fn = region.peek(queue)
        val leaf = fn(2)
        val identity =
          System.identityHashCode(first) ^
            System.identityHashCode(second) ^
            System.identityHashCode(fn) ^
            System.identityHashCode(leaf)
        leaf.value - expected + 42 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected priority-queue backing arrays plus selected closure values and closure-body allocation to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPriorityQueueInlineArrayPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val queue =
          RiftRegion.regionPriorityQueue[
            Array[RiftCheckedLeaf^{region}]^{region}
          ](1)
        region.push(queue, new Array[RiftCheckedLeaf^{region}](2), 1L)
        val leaves = region.peek(queue)
        leaves(0) = new RiftCheckedLeaf(20)
        leaves(1) = new RiftCheckedLeaf(22)

        val identity =
          System.identityHashCode(queue) ^
            System.identityHashCode(leaves) ^
            System.identityHashCode(leaves(0)) ^
            System.identityHashCode(leaves(1))
        leaves(0).value + leaves(1).value + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected priority-queue backing arrays plus inline array value and stored values to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersIndexedPriorityQueueInlineArrayPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val indexed =
          RiftRegion.regionIndexedPriorityQueue[
            Array[RiftCheckedLeaf^{region}]^{region}
          ](4, 1)
        region.put(indexed, 1, new Array[RiftCheckedLeaf^{region}](1), 1L)
        val indexedLeaves = region.get(indexed, 1)
        indexedLeaves(0) = new RiftCheckedLeaf(10)

        val longIndexed =
          RiftRegion.regionLongIndexedPriorityQueue[
            Array[RiftCheckedLeaf^{region}]^{region}
          ](1, 4)
        RiftRegion.put(
          region,
          longIndexed,
          10L,
          new Array[RiftCheckedLeaf^{region}](1),
          2L
        )
        val longLeaves = RiftRegion.peek(region, longIndexed)
        longLeaves(0) = new RiftCheckedLeaf(30)

        val identity =
          System.identityHashCode(indexed) ^
            System.identityHashCode(indexedLeaves) ^
            System.identityHashCode(indexedLeaves(0)) ^
            System.identityHashCode(longIndexed) ^
            System.identityHashCode(longLeaves) ^
            System.identityHashCode(longLeaves(0))
        indexedLeaves(0).value + longLeaves(0).value + 2 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected indexed priority-queue backing arrays plus inline array values and stored values to be region allocated, observed $delta region objects",
        delta >= 6L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLexicographicPriorityQueueInlineArrayPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val indexed =
          RiftRegion.regionIndexedPriorityQueueLexicographic[
            Array[RiftCheckedLeaf^{region}]^{region}
          ](4, 1)
        region.put(
          indexed,
          1,
          new Array[RiftCheckedLeaf^{region}](1),
          1L,
          2L,
          3L,
          4L
        )
        val indexedLeaves = region.get(indexed, 1)
        indexedLeaves(0) = new RiftCheckedLeaf(10)

        val longIndexed =
          RiftRegion.regionLongIndexedPriorityQueueLexicographic[
            Array[RiftCheckedLeaf^{region}]^{region}
          ](1, 4)
        RiftRegion.put(
          region,
          longIndexed,
          10L,
          new Array[RiftCheckedLeaf^{region}](1),
          2L,
          3L,
          4L,
          5L
        )
        val longLeaves = RiftRegion.peek(region, longIndexed)
        longLeaves(0) = new RiftCheckedLeaf(30)

        val identity =
          System.identityHashCode(indexed) ^
            System.identityHashCode(indexedLeaves) ^
            System.identityHashCode(indexedLeaves(0)) ^
            System.identityHashCode(longIndexed) ^
            System.identityHashCode(longLeaves) ^
            System.identityHashCode(longLeaves(0))
        indexedLeaves(0).value + longLeaves(0).value + 2 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected lexicographic priority-queue backing arrays plus inline array values and stored values to be region allocated, observed $delta region objects",
        delta >= 6L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPriorityQueueSelectedLocalSyntheticFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val plain =
          RiftRegion.regionPriorityQueue[
            Option[RiftCheckedLeaf^{region}]^{region}
          ](1)
        val someFirst = Some(new RiftCheckedLeaf(40))
        val someSecond = Some(new RiftCheckedLeaf(41))
        val someKeep =
          System.identityHashCode(someFirst) +
            System.identityHashCode(someSecond)
        val someSelected =
          if ((someKeep & 1) == 0) someFirst else someSecond
        region.push(plain, someSelected, 1L)

        val indexed =
          RiftRegion.regionIndexedPriorityQueue[
            Option[RiftCheckedLeaf^{region}]^{region}
          ](4, 1)
        val optionFirst = Option(new RiftCheckedLeaf(10))
        val optionSecond = Option(new RiftCheckedLeaf(11))
        val optionKeep =
          System.identityHashCode(optionFirst) +
            System.identityHashCode(optionSecond)
        val optionSelected =
          if ((optionKeep & 1) == 0) optionFirst else optionSecond
        region.put(indexed, 1, optionSelected, 2L)

        val longIndexed =
          RiftRegion.regionLongIndexedPriorityQueue[
            Tuple2[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
          ](1, 4)
        val pairFirst = Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(1))
        val pairSecond = Tuple2(new RiftCheckedLeaf(2), new RiftCheckedLeaf(2))
        val pairKeep =
          System.identityHashCode(pairFirst) + System.identityHashCode(pairSecond)
        val pairSelected =
          if ((pairKeep & 1) == 0) pairFirst else pairSecond
        region.put(longIndexed, 10L, pairSelected, 3L)

        val expectedSome = if ((someKeep & 1) == 0) 40 else 41
        val expectedOption = if ((optionKeep & 1) == 0) 10 else 11
        val expectedPair = if ((pairKeep & 1) == 0) 2 else 4
        region.peek(plain).get.value +
          region.peek(indexed).get.value +
          region.peek(longIndexed)._1.value +
          region.peek(longIndexed)._2.value -
          expectedSome - expectedOption - expectedPair + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected selected priority-queue Some/Option/Tuple2 factories and payloads to be region allocated, observed $delta region objects",
        delta >= 14L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPriorityQueueSelectedNestedSyntheticFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        final class SomeNode(
            val option: Option[RiftCheckedLeaf^{region}]^{region}
        )
        final class OptionNode(
            val option: Option[RiftCheckedLeaf^{region}]^{region}
        )
        final class PairNode(
            val pair: Tuple2[
              RiftCheckedLeaf^{region},
              RiftCheckedLeaf^{region}
            ]^{region}
        )

        val plain = RiftRegion.regionPriorityQueue[SomeNode](1)
        val someFirst = Some(new RiftCheckedLeaf(40))
        val someSecond = Some(new RiftCheckedLeaf(41))
        val someKeep =
          System.identityHashCode(someFirst) +
            System.identityHashCode(someSecond)
        val someSelected =
          if ((someKeep & 1) == 0) someFirst else someSecond
        region.push(plain, new SomeNode(someSelected), 1L)

        val indexed = RiftRegion.regionIndexedPriorityQueue[OptionNode](4, 1)
        val optionFirst = Option(new RiftCheckedLeaf(10))
        val optionSecond = Option(new RiftCheckedLeaf(11))
        val optionKeep =
          System.identityHashCode(optionFirst) +
            System.identityHashCode(optionSecond)
        val optionSelected =
          if ((optionKeep & 1) == 0) optionFirst else optionSecond
        region.put(indexed, 1, new OptionNode(optionSelected), 2L)

        val longIndexed =
          RiftRegion.regionLongIndexedPriorityQueue[PairNode](1, 4)
        val pairFirst = Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(2))
        val pairSecond = Tuple2(new RiftCheckedLeaf(3), new RiftCheckedLeaf(4))
        val pairKeep =
          System.identityHashCode(pairFirst) + System.identityHashCode(pairSecond)
        val pairSelected =
          if ((pairKeep & 1) == 0) pairFirst else pairSecond
        region.put(longIndexed, 10L, new PairNode(pairSelected), 3L)

        val expectedSome = if ((someKeep & 1) == 0) 40 else 41
        val expectedOption = if ((optionKeep & 1) == 0) 10 else 11
        val expectedPair = if ((pairKeep & 1) == 0) 3 else 7
        region.peek(plain).option.get.value +
          region.peek(indexed).option.get.value +
          region.peek(longIndexed).pair._1.value +
          region.peek(longIndexed).pair._2.value -
          expectedSome - expectedOption - expectedPair + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected selected nested priority-queue synthetic factories, payloads, and value objects to be region allocated, observed $delta region objects",
        delta >= 17L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPriorityQueueBranchMatchSyntheticFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val plain =
          RiftRegion.regionPriorityQueue[
            Option[RiftCheckedLeaf^{region}]^{region}
          ](1)
        val chooseSome = (System.identityHashCode(region) & 1) == 0
        region.push(
          plain,
          if chooseSome then Some(new RiftCheckedLeaf(40))
          else Some(new RiftCheckedLeaf(41)),
          1L
        )

        val indexed =
          RiftRegion.regionIndexedPriorityQueue[
            Option[RiftCheckedLeaf^{region}]^{region}
          ](4, 1)
        val optionSelector = System.identityHashCode(plain) & 1
        region.put(
          indexed,
          1,
          (optionSelector match
            case 0 => Option(new RiftCheckedLeaf(10))
            case _ => Option(new RiftCheckedLeaf(11))
          ),
          2L
        )

        val longIndexed =
          RiftRegion.regionLongIndexedPriorityQueue[
            Tuple2[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
          ](1, 4)
        val choosePair = (System.identityHashCode(indexed) & 1) == 0
        region.put(
          longIndexed,
          10L,
          if choosePair then
            Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(2))
          else Tuple2(new RiftCheckedLeaf(3), new RiftCheckedLeaf(4)),
          3L
        )

        val expectedSome = if chooseSome then 40 else 41
        val expectedOption = if optionSelector == 0 then 10 else 11
        val expectedPair = if choosePair then 3 else 7
        region.peek(plain).get.value +
          region.peek(indexed).get.value +
          region.peek(longIndexed)._1.value +
          region.peek(longIndexed)._2.value -
          expectedSome - expectedOption - expectedPair + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected branch/match priority-queue Some/Option/Tuple2 factories and payloads to be region allocated, observed $delta region objects",
        delta >= 7L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPriorityQueueEitherFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val plain = RiftRegion.regionPriorityQueue[
          Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
        ](1)
        val plainFirst: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} =
          Left(new RiftCheckedLeaf(40))
        val plainSecond: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} =
          Right(new RiftCheckedLeaf(41))
        val plainKeep =
          System.identityHashCode(plainFirst) +
            System.identityHashCode(plainSecond)
        val plainSelected =
          if ((plainKeep & 1) == 0) plainFirst else plainSecond
        region.push(plain, plainSelected, 1L)

        val indexed =
          RiftRegion.regionIndexedPriorityQueue[
            Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
          ](4, 1)
        val chooseIndexed = (System.identityHashCode(plain) & 1) == 0
        region.put(
          indexed,
          1,
          if chooseIndexed then Left(new RiftCheckedLeaf(10))
          else Right(new RiftCheckedLeaf(11)),
          2L
        )

        val longIndexed =
          RiftRegion.regionLongIndexedPriorityQueue[
            Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
          ](1, 4)
        val longSelector = System.identityHashCode(indexed) & 1
        region.put(
          longIndexed,
          10L,
          (longSelector match
            case 0 => Left(new RiftCheckedLeaf(1))
            case _ => Right(new RiftCheckedLeaf(2))
          ),
          3L
        )

        val plainLeaf = region.peek(plain) match
          case Left(value)  => value
          case Right(value) => value
        val indexedLeaf = region.peek(indexed) match
          case Left(value)  => value
          case Right(value) => value
        val longLeaf = region.peek(longIndexed) match
          case Left(value)  => value
          case Right(value) => value

        val expectedPlain = if ((plainKeep & 1) == 0) 40 else 41
        val expectedIndexed = if chooseIndexed then 10 else 11
        val expectedLong = if longSelector == 0 then 1 else 2
        val identity =
          System.identityHashCode(plain) ^
            System.identityHashCode(plainSelected) ^
            System.identityHashCode(indexed) ^
            System.identityHashCode(longIndexed)
        plainLeaf.value + indexedLeaf.value + longLeaf.value -
          expectedPlain - expectedIndexed - expectedLong + 42 +
          (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected checked priority-queue selected and branch Either factories and payloads to be region allocated, observed $delta region objects",
        delta >= 8L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLexicographicPriorityQueueSelectedLocalSyntheticFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val indexed =
          RiftRegion.regionIndexedPriorityQueueLexicographic[
            Option[RiftCheckedLeaf^{region}]^{region}
          ](4, 1)
        val someFirst = Some(new RiftCheckedLeaf(40))
        val someSecond = Some(new RiftCheckedLeaf(41))
        val someKeep =
          System.identityHashCode(someFirst) +
            System.identityHashCode(someSecond)
        val someSelected =
          if ((someKeep & 1) == 0) someFirst else someSecond
        region.put(indexed, 1, someSelected, 1L, 2L, 3L, 4L)

        val optionFirst = Option(new RiftCheckedLeaf(10))
        val optionSecond = Option(new RiftCheckedLeaf(11))
        val optionKeep =
          System.identityHashCode(optionFirst) +
            System.identityHashCode(optionSecond)
        val optionSelected =
          if ((optionKeep & 1) == 0) optionFirst else optionSecond
        RiftRegion.put(region, indexed, 2, optionSelected, 2L, 3L, 4L, 5L)

        val longIndexed =
          RiftRegion.regionLongIndexedPriorityQueueLexicographic[
            Tuple2[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
          ](1, 4)
        val pairFirst = Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(1))
        val pairSecond = Tuple2(new RiftCheckedLeaf(2), new RiftCheckedLeaf(2))
        val pairKeep =
          System.identityHashCode(pairFirst) + System.identityHashCode(pairSecond)
        val pairSelected =
          if ((pairKeep & 1) == 0) pairFirst else pairSecond
        region.put(longIndexed, 10L, pairSelected, 3L, 4L, 5L, 6L)

        val expectedSome = if ((someKeep & 1) == 0) 40 else 41
        val expectedOption = if ((optionKeep & 1) == 0) 10 else 11
        val expectedPair = if ((pairKeep & 1) == 0) 2 else 4
        region.get(indexed, 1).get.value +
          region.get(indexed, 2).get.value +
          region.get(longIndexed, 10L)._1.value +
          region.get(longIndexed, 10L)._2.value -
          expectedSome - expectedOption - expectedPair + 42
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected selected lexicographic priority-queue Some/Option/Tuple2 factories and payloads to be region allocated, observed $delta region objects",
        delta >= 14L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersLexicographicPriorityQueueEitherFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      import scala.util.*

      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val indexed =
          RiftRegion.regionIndexedPriorityQueueLexicographic[
            Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
          ](4, 1)
        val indexedFirst: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} =
          Left(new RiftCheckedLeaf(40))
        val indexedSecond: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} =
          Right(new RiftCheckedLeaf(41))
        val indexedKeep =
          System.identityHashCode(indexedFirst) +
            System.identityHashCode(indexedSecond)
        val indexedSelected =
          if ((indexedKeep & 1) == 0) indexedFirst else indexedSecond
        region.put(indexed, 1, indexedSelected, 1L, 2L, 3L, 4L)

        val chooseIndexed = (System.identityHashCode(indexed) & 1) == 0
        RiftRegion.put(
          region,
          indexed,
          2,
          if chooseIndexed then Left(new RiftCheckedLeaf(10))
          else Right(new RiftCheckedLeaf(11)),
          2L,
          3L,
          4L,
          5L
        )

        val longIndexed =
          RiftRegion.regionLongIndexedPriorityQueueLexicographic[
            Either[RiftCheckedLeaf^{region}, RiftCheckedLeaf^{region}]^{region}
          ](1, 4)
        val longFirst: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} =
          Left(new RiftCheckedLeaf(1))
        val longSecond: Either[
          RiftCheckedLeaf^{region},
          RiftCheckedLeaf^{region}
        ]^{region} =
          Right(new RiftCheckedLeaf(2))
        val longKeep =
          System.identityHashCode(longFirst) +
            System.identityHashCode(longSecond)
        val longSelected =
          if ((longKeep & 1) == 0) longFirst else longSecond
        region.put(longIndexed, 10L, longSelected, 3L, 4L, 5L, 6L)

        val longSelector = System.identityHashCode(longIndexed) & 1
        RiftRegion.put(
          region,
          longIndexed,
          20L,
          (longSelector match
            case 0 => Left(new RiftCheckedLeaf(3))
            case _ => Right(new RiftCheckedLeaf(4))
          ),
          4L,
          5L,
          6L,
          7L
        )

        val indexedSelectedLeaf = region.get(indexed, 1) match
          case Left(value)  => value
          case Right(value) => value
        val indexedBranchLeaf = region.get(indexed, 2) match
          case Left(value)  => value
          case Right(value) => value
        val longSelectedLeaf = region.get(longIndexed, 10L) match
          case Left(value)  => value
          case Right(value) => value
        val longBranchLeaf = region.get(longIndexed, 20L) match
          case Left(value)  => value
          case Right(value) => value

        val expectedIndexed = if ((indexedKeep & 1) == 0) 40 else 41
        val expectedIndexedBranch = if chooseIndexed then 10 else 11
        val expectedLong = if ((longKeep & 1) == 0) 1 else 2
        val expectedLongBranch = if longSelector == 0 then 3 else 4
        val identity =
          System.identityHashCode(indexed) ^
            System.identityHashCode(indexedSelected) ^
            System.identityHashCode(longIndexed) ^
            System.identityHashCode(longSelected)
        indexedSelectedLeaf.value + indexedBranchLeaf.value +
          longSelectedLeaf.value + longBranchLeaf.value -
          expectedIndexed - expectedIndexedBranch -
          expectedLong - expectedLongBranch + 42 + (identity & 0)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected lexicographic checked priority-queue selected and branch Either factories and payloads to be region allocated, observed $delta region objects",
        delta >= 12L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPriorityQueueInlineNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val plain = RiftRegion.regionPriorityQueue[RiftCheckedLeaf](1)
        region.push(plain, new RiftCheckedLeaf(10), 1L)
        RiftRegion.push(region, plain, new RiftCheckedLeaf(20), 2L)

        val indexed = RiftRegion.regionIndexedPriorityQueue[RiftCheckedLeaf](4, 1)
        region.put(indexed, 1, new RiftCheckedLeaf(3), 1L)
        RiftRegion.put(region, indexed, 2, new RiftCheckedLeaf(4), 2L)

        val longIndexed =
          RiftRegion.regionLongIndexedPriorityQueue[RiftCheckedLeaf](1, 4)
        region.put(longIndexed, 10L, new RiftCheckedLeaf(0), 1L)
        RiftRegion.put(region, longIndexed, 20L, new RiftCheckedLeaf(1), 2L)

        region.pop(plain).value +
          RiftRegion.pop(region, plain).value +
          region.peek(indexed).value +
          RiftRegion.get(region, indexed, 1).value +
          region.peek(longIndexed).value +
          RiftRegion.get(region, longIndexed, 10L).value +
          region.length(plain) +
          region.length(indexed) +
          region.length(longIndexed)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(after - before >= 6L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def scopedRegionInfersPriorityQueueInlineBlockNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.scoped { region ?=>
        val plain = RiftRegion.regionPriorityQueue[RiftCheckedLeaf](1)
        region.push(plain, { val value = 10; new RiftCheckedLeaf(value) }, 1L)
        RiftRegion.push(
          region,
          plain,
          { val value = 20; new RiftCheckedLeaf(value) },
          2L
        )

        val indexed = RiftRegion.regionIndexedPriorityQueue[RiftCheckedLeaf](4, 1)
        region.put(indexed, 1, { val value = 3; new RiftCheckedLeaf(value) }, 1L)
        RiftRegion.put(
          region,
          indexed,
          2,
          { val value = 4; new RiftCheckedLeaf(value) },
          2L
        )

        val longIndexed =
          RiftRegion.regionLongIndexedPriorityQueue[RiftCheckedLeaf](1, 4)
        region.put(
          longIndexed,
          10L,
          { val value = 0; new RiftCheckedLeaf(value) },
          1L
        )
        RiftRegion.put(
          region,
          longIndexed,
          20L,
          { val value = 1; new RiftCheckedLeaf(value) },
          2L
        )

        region.pop(plain).value +
          RiftRegion.pop(region, plain).value +
          region.peek(indexed).value +
          RiftRegion.get(region, indexed, 1).value +
          region.peek(longIndexed).value +
          RiftRegion.get(region, longIndexed, 10L).value +
          region.length(plain) +
          region.length(indexed) +
          region.length(longIndexed)
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(after - before >= 6L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamingRegionInfersWindowRankBranchMatchLocalNewPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      def run(insertSelected: Boolean): Long = {
        RiftAllocator.Impl.statsReset()
        val total = RiftRegion.streaming { stream ?=>
          val indexed =
            RiftRegion.streamWindowIndexedRank[RiftCheckedLeaf](10, 8, 1)

          val longIndexed =
            RiftRegion.streamWindowLongIndexedRank[RiftCheckedLeaf](10, 1, 4)
          val longBucket =
            RiftRegion.streamWindowBucketFor(stream, longIndexed, 7L)

          val table =
            RiftRegion.streamWindowTableRank[RiftCheckedLeaf](10, 1, 4)
          val tableBucket =
            RiftRegion.streamWindowBucketFor(stream, table, 7L)

          if (insertSelected) {
            val low = new RiftCheckedLeaf(10)
            val high = new RiftCheckedLeaf(40)
            val keep =
              System.identityHashCode(low) + System.identityHashCode(high)
            val chooseLow = (keep & 1) == 0
            RiftRegion.putWindowRank(
              stream,
              indexed,
              1,
              if chooseLow then low else high,
              1L
            )

            val first = new RiftCheckedLeaf(1)
            val second = new RiftCheckedLeaf(2)
            val longKeep =
              System.identityHashCode(first) + System.identityHashCode(second)
            val longSelector = longKeep & 1
            RiftRegion.putWindowRankInBucket(
              stream,
              longIndexed,
              longBucket,
              10L,
              (longSelector match
                case 0 => first
                case _ => second
              ),
              2L
            )

            val left = new RiftCheckedLeaf(3)
            val right = new RiftCheckedLeaf(4)
            val tableKeep =
              System.identityHashCode(left) + System.identityHashCode(right)
            val tableSelector = tableKeep & 1
            RiftRegion.putTableRankInBucket(
              stream,
              table,
              tableBucket,
              20L,
              (tableSelector match
                case 0 => left
                case _ => right
              ),
              3L
            )

            val expectedIndexed = if chooseLow then 10 else 40
            val expectedLong = if longSelector == 0 then 1 else 2
            val expectedTable = if tableSelector == 0 then 3 else 4
            RiftRegion.peekWindowRank(stream, indexed).value +
              RiftRegion.peekWindowRank(stream, longIndexed).value +
              RiftRegion.peekTableRank(stream, table).value -
              expectedIndexed - expectedLong - expectedTable + 42
          } else 42
        }
        assertEquals(42, total)
        fromRawUSize(RiftAllocator.Impl.statsAllocObjectTotal()).toLong
      }
      val setupOnly = run(insertSelected = false)
      val withSelected = run(insertSelected = true)
      val delta = withSelected - setupOnly

      assertTrue(
        s"expected stream-window rank branch/match local Row candidates to be region allocated, observed $delta region objects",
        delta >= 6L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamingRegionInfersWindowRankSelectedLocalSyntheticFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      def run(insertSelected: Boolean): Long = {
        RiftAllocator.Impl.statsReset()
        val total = RiftRegion.streaming { stream ?=>
          val indexed =
            RiftRegion.streamWindowIndexedRank[
              Option[RiftCheckedLeaf^{stream}]^{stream}
            ](10, 8, 1)

          val longIndexed =
            RiftRegion.streamWindowLongIndexedRankLexicographic[
              Option[RiftCheckedLeaf^{stream}]^{stream}
            ](10, 1, 4)
          val longBucket =
            RiftRegion.streamWindowBucketFor(stream, longIndexed, 7L)

          val table =
            RiftRegion.streamWindowTableRankLexicographic[
              Tuple2[RiftCheckedLeaf^{stream}, RiftCheckedLeaf^{stream}]^{stream}
            ](10, 1, 4)
          val tableBucket =
            RiftRegion.streamWindowBucketFor(stream, table, 7L)

          if (insertSelected) {
            val someFirst = Some(new RiftCheckedLeaf(40))
            val someSecond = Some(new RiftCheckedLeaf(41))
            val someKeep =
              System.identityHashCode(someFirst) +
                System.identityHashCode(someSecond)
            val someSelected =
              if ((someKeep & 1) == 0) someFirst else someSecond
            RiftRegion.putWindowRank(stream, indexed, 1, someSelected, 1L)

            val optionFirst = Option(new RiftCheckedLeaf(10))
            val optionSecond = Option(new RiftCheckedLeaf(11))
            val optionKeep =
              System.identityHashCode(optionFirst) +
                System.identityHashCode(optionSecond)
            val optionSelected =
              if ((optionKeep & 1) == 0) optionFirst else optionSecond
            RiftRegion.putWindowRankInBucket(
              stream,
              longIndexed,
              longBucket,
              10L,
              optionSelected,
              2L,
              3L,
              4L,
              5L
            )

            val pairFirst =
              Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(1))
            val pairSecond =
              Tuple2(new RiftCheckedLeaf(2), new RiftCheckedLeaf(2))
            val pairKeep =
              System.identityHashCode(pairFirst) +
                System.identityHashCode(pairSecond)
            val pairSelected =
              if ((pairKeep & 1) == 0) pairFirst else pairSecond
            RiftRegion.putTableRankInBucket(
              stream,
              table,
              tableBucket,
              20L,
              pairSelected,
              3L,
              4L,
              5L,
              6L
            )

            val expectedSome = if ((someKeep & 1) == 0) 40 else 41
            val expectedOption = if ((optionKeep & 1) == 0) 10 else 11
            val expectedPair = if ((pairKeep & 1) == 0) 2 else 4
            RiftRegion.peekWindowRank(stream, indexed).get.value +
              RiftRegion.peekWindowRank(stream, longIndexed).get.value +
              RiftRegion.peekTableRank(stream, table)._1.value +
              RiftRegion.peekTableRank(stream, table)._2.value -
              expectedSome - expectedOption - expectedPair + 42
          } else 42
        }
        assertEquals(42, total)
        fromRawUSize(RiftAllocator.Impl.statsAllocObjectTotal()).toLong
      }
      val setupOnly = run(insertSelected = false)
      val withSelected = run(insertSelected = true)
      val delta = withSelected - setupOnly

      assertTrue(
        s"expected selected stream-window rank Some/Option/Tuple2 factories and payloads to be region allocated, observed $delta region objects",
        delta >= 14L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamingRegionInfersWindowRankClosurePlacement(): Unit = {
    RiftRegion.init(1)
    try {
      def run(insertValues: Boolean): Long = {
        RiftAllocator.Impl.statsReset()
        val total = RiftRegion.streaming { stream ?=>
          val indexed =
            RiftRegion.streamWindowIndexedRank[
              Function1[Int, Int]^{stream}
            ](10, 8, 1)

          val longIndexed =
            RiftRegion.streamWindowLongIndexedRank[
              Function1[Int, Int]^{stream}
            ](10, 1, 4)
          val longBucket =
            RiftRegion.streamWindowBucketFor(stream, longIndexed, 7L)

          val table =
            RiftRegion.streamWindowTableRank[
              Function1[Int, Int]^{stream}
            ](10, 1, 4)
          val tableBucket =
            RiftRegion.streamWindowBucketFor(stream, table, 7L)

          if (insertValues) {
            RiftRegion.putWindowRank(
              stream,
              indexed,
              1,
              (n: Int) => n + 40,
              1L
            )

            val first = (n: Int) => n + 10
            val second = (n: Int) => n + 20
            val keep =
              System.identityHashCode(first) + System.identityHashCode(second)
            val selected = if ((keep & 1) == 0) first else second
            RiftRegion.putWindowRankInBucket(
              stream,
              longIndexed,
              longBucket,
              10L,
              selected,
              2L
            )

            RiftRegion.putTableRankInBucket(
              stream,
              table,
              tableBucket,
              20L,
              (n: Int) => n + 1,
              3L
            )

            val expectedSelected = if ((keep & 1) == 0) 12 else 22
            invokeRegionFunction(RiftRegion.peekWindowRank(stream, indexed), 2) +
              invokeRegionFunction(
                RiftRegion.peekWindowRank(stream, longIndexed),
                2
              ) +
              invokeRegionFunction(RiftRegion.peekTableRank(stream, table), 2) -
              42 - expectedSelected - 3 + 42
          } else 42
        }
        assertEquals(42, total)
        fromRawUSize(RiftAllocator.Impl.statsAllocObjectTotal()).toLong
      }
      val setupOnly = run(insertValues = false)
      val withValues = run(insertValues = true)
      val delta = withValues - setupOnly

      assertTrue(
        s"expected stream-window rank closure values to be region allocated, observed $delta region objects",
        delta >= 4L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamingRegionInfersWindowRankBranchMatchSyntheticFactoryPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      def run(insertSelected: Boolean): Long = {
        RiftAllocator.Impl.statsReset()
        val total = RiftRegion.streaming { stream ?=>
          val indexed =
            RiftRegion.streamWindowIndexedRank[
              Option[RiftCheckedLeaf^{stream}]^{stream}
            ](10, 8, 1)

          val longIndexed =
            RiftRegion.streamWindowLongIndexedRank[
              Option[RiftCheckedLeaf^{stream}]^{stream}
            ](10, 1, 4)
          val longBucket =
            RiftRegion.streamWindowBucketFor(stream, longIndexed, 7L)

          val table =
            RiftRegion.streamWindowTableRank[
              Tuple2[RiftCheckedLeaf^{stream}, RiftCheckedLeaf^{stream}]^{stream}
            ](10, 1, 4)
          val tableBucket =
            RiftRegion.streamWindowBucketFor(stream, table, 7L)

          if (insertSelected) {
            val chooseSome = (System.identityHashCode(indexed) & 1) == 0
            RiftRegion.putWindowRank(
              stream,
              indexed,
              1,
              if chooseSome then Some(new RiftCheckedLeaf(40))
              else Some(new RiftCheckedLeaf(41)),
              1L
            )

            val optionSelector = System.identityHashCode(longIndexed) & 1
            RiftRegion.putWindowRankInBucket(
              stream,
              longIndexed,
              longBucket,
              10L,
              (optionSelector match
                case 0 => Option(new RiftCheckedLeaf(10))
                case _ => Option(new RiftCheckedLeaf(11))
              ),
              2L
            )

            val choosePair = (System.identityHashCode(table) & 1) == 0
            RiftRegion.putTableRankInBucket(
              stream,
              table,
              tableBucket,
              20L,
              if choosePair then
                Tuple2(new RiftCheckedLeaf(1), new RiftCheckedLeaf(2))
              else Tuple2(new RiftCheckedLeaf(3), new RiftCheckedLeaf(4)),
              3L
            )

            val expectedSome = if chooseSome then 40 else 41
            val expectedOption = if optionSelector == 0 then 10 else 11
            val expectedPair = if choosePair then 3 else 7
            RiftRegion.peekWindowRank(stream, indexed).get.value +
              RiftRegion.peekWindowRank(stream, longIndexed).get.value +
              RiftRegion.peekTableRank(stream, table)._1.value +
              RiftRegion.peekTableRank(stream, table)._2.value -
              expectedSome - expectedOption - expectedPair + 42
          } else 42
        }
        assertEquals(42, total)
        fromRawUSize(RiftAllocator.Impl.statsAllocObjectTotal()).toLong
      }
      val setupOnly = run(insertSelected = false)
      val withSelected = run(insertSelected = true)
      val delta = withSelected - setupOnly

      assertTrue(
        s"expected branch/match stream-window rank Some/Option/Tuple2 factories and payloads to be region allocated, observed $delta region objects",
        delta >= 7L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamingRegionInfersWindowRankEitherFactoryPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      def run(insertValues: Boolean): Long = {
        RiftAllocator.Impl.statsReset()
        val total = RiftRegion.streaming { stream ?=>
          val indexed =
            RiftRegion.streamWindowIndexedRank[
              Either[RiftCheckedLeaf^{stream}, RiftCheckedLeaf^{stream}]^{stream}
            ](10, 8, 1)

          val longIndexed =
            RiftRegion.streamWindowLongIndexedRankLexicographic[
              Either[RiftCheckedLeaf^{stream}, RiftCheckedLeaf^{stream}]^{stream}
            ](10, 1, 4)
          val longBucket =
            RiftRegion.streamWindowBucketFor(stream, longIndexed, 7L)

          val table =
            RiftRegion.streamWindowTableRank[
              Either[RiftCheckedLeaf^{stream}, RiftCheckedLeaf^{stream}]^{stream}
            ](10, 1, 4)
          val tableBucket =
            RiftRegion.streamWindowBucketFor(stream, table, 7L)

          if (insertValues) {
            val indexedFirst: Either[
              RiftCheckedLeaf^{stream},
              RiftCheckedLeaf^{stream}
            ]^{stream} = Left(new RiftCheckedLeaf(40))
            val indexedSecond: Either[
              RiftCheckedLeaf^{stream},
              RiftCheckedLeaf^{stream}
            ]^{stream} = Right(new RiftCheckedLeaf(41))
            val indexedKeep =
              System.identityHashCode(indexedFirst) +
                System.identityHashCode(indexedSecond)
            val indexedSelected =
              if ((indexedKeep & 1) == 0) indexedFirst else indexedSecond
            RiftRegion.putWindowRank(
              stream,
              indexed,
              1,
              indexedSelected,
              1L
            )

            val longSelector = System.identityHashCode(longIndexed) & 1
            RiftRegion.putWindowRankInBucket(
              stream,
              longIndexed,
              longBucket,
              10L,
              (longSelector match
                case 0 => Left(new RiftCheckedLeaf(10))
                case _ => Right(new RiftCheckedLeaf(11))
              ),
              2L,
              3L,
              4L,
              5L
            )

            val chooseTable = (System.identityHashCode(table) & 1) == 0
            RiftRegion.putTableRankInBucket(
              stream,
              table,
              tableBucket,
              20L,
              if chooseTable then Left(new RiftCheckedLeaf(1))
              else Right(new RiftCheckedLeaf(2)),
              3L
            )

            val indexedValue = RiftRegion.peekWindowRank(stream, indexed) match {
              case Left(value)  => value.value
              case Right(value) => value.value
            }
            val longValue =
              RiftRegion.peekWindowRank(stream, longIndexed) match {
                case Left(value)  => value.value
                case Right(value) => value.value
              }
            val tableValue = RiftRegion.peekTableRank(stream, table) match {
              case Left(value)  => value.value
              case Right(value) => value.value
            }
            val expectedIndexed = if ((indexedKeep & 1) == 0) 40 else 41
            val expectedLong = if longSelector == 0 then 10 else 11
            val expectedTable = if chooseTable then 1 else 2
            indexedValue + longValue + tableValue -
              expectedIndexed - expectedLong - expectedTable + 42
          } else 42
        }
        assertEquals(42, total)
        fromRawUSize(RiftAllocator.Impl.statsAllocObjectTotal()).toLong
      }
      val setupOnly = run(insertValues = false)
      val withValues = run(insertValues = true)
      val delta = withValues - setupOnly

      assertTrue(
        s"expected stream-window rank selected and branch/match Either factories and payloads to be region allocated, observed $delta region objects",
        delta >= 8L
      )
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamingRegionInfersWindowRankInlineArrayPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      def run(insertValues: Boolean): Long = {
        RiftAllocator.Impl.statsReset()
        val total = RiftRegion.streaming { stream ?=>
          val indexed =
            RiftRegion.streamWindowIndexedRank[
              Array[RiftCheckedLeaf^{stream}]^{stream}
            ](10, 8, 1)

          val longIndexed =
            RiftRegion.streamWindowLongIndexedRankLexicographic[
              Array[RiftCheckedLeaf^{stream}]^{stream}
            ](10, 1, 4)
          val longBucket =
            RiftRegion.streamWindowBucketFor(stream, longIndexed, 7L)

          val table =
            RiftRegion.streamWindowTableRank[
              Array[RiftCheckedLeaf^{stream}]^{stream}
            ](10, 1, 4)
          val tableBucket =
            RiftRegion.streamWindowBucketFor(stream, table, 7L)

          if (insertValues) {
            RiftRegion.putWindowRank(
              stream,
              indexed,
              1,
              new Array[RiftCheckedLeaf^{stream}](1),
              1L
            )
            val indexedLeaves = RiftRegion.peekWindowRank(stream, indexed)
            indexedLeaves(0) = new RiftCheckedLeaf(10)

            RiftRegion.putWindowRankInBucket(
              stream,
              longIndexed,
              longBucket,
              10L,
              new Array[RiftCheckedLeaf^{stream}](1),
              2L,
              3L,
              4L,
              5L
            )
            val longLeaves = RiftRegion.peekWindowRank(stream, longIndexed)
            longLeaves(0) = new RiftCheckedLeaf(20)

            RiftRegion.putTableRankInBucket(
              stream,
              table,
              tableBucket,
              20L,
              new Array[RiftCheckedLeaf^{stream}](1),
              3L
            )
            val tableLeaves = RiftRegion.peekTableRank(stream, table)
            tableLeaves(0) = new RiftCheckedLeaf(30)

            indexedLeaves(0).value + longLeaves(0).value +
              tableLeaves(0).value - 60 + 42
          } else 42
        }
        assertEquals(42, total)
        fromRawUSize(RiftAllocator.Impl.statsAllocObjectTotal()).toLong
      }
      val setupOnly = run(insertValues = false)
      val withValues = run(insertValues = true)
      val delta = withValues - setupOnly

      assertTrue(
        s"expected stream-window rank arrays and inline elements to be region allocated, observed $delta region objects",
        delta >= 6L
      )
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

  @Test def streamingEpochAllocatesAndResetsRecords(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int)

        var sum = 0
        var i = 0
        while (i < 2) {
          val epochSum = RiftRegion.epoch { epoch ?=>
            val event: Event^{epoch} =
              RiftRegion.allocOpen(new Event(20 + i))
            event.value
          }
          sum += epochSum
          i += 1
        }
        sum
      }

      assertEquals(41, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def safeZoneBackedEpochAllocatesAndClosesRecords(): Unit = {
    val total = RiftRegion.streamingSafeZone { stream ?=>
      final class Event(val value: Int)

      var sum = 0
      var i = 0
      while (i < 2) {
        val epochSum = RiftRegion.epoch { epoch ?=>
          val event: Event^{epoch} =
            RiftRegion.allocOpen(new Event(20 + i))
          event.value
        }
        sum += epochSum
        i += 1
      }
      sum
    }

    assertEquals(41, total)
  }

  @Test def streamingEpochAllocatesRegionOwnedArrays(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Cell(val value: Int)

        var sum = 0
        var epochIndex = 0
        while (epochIndex < 2) {
          sum += RiftRegion.epoch { epoch ?=>
            val cells: Array[Cell^{epoch}]^{epoch} =
              RiftRegion.allocOpen(new Array[Cell^{epoch}](3))
            var i = 0
            while (i < cells.length) {
              cells(i) =
                RiftRegion.allocOpen(new Cell(epochIndex * 10 + i))
              i += 1
            }

            var local = 0
            var j = 0
            while (j < cells.length) {
              local += cells(j).value
              j += 1
            }
            local
          }
          epochIndex += 1
        }
        sum
      }

      assertEquals(36, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def openHandleAllocatesRegionOwnedArrays(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streamingOpenHandle {
        final class Cell(val value: Int)

        var sum = 0
        var epochIndex = 0
        while (epochIndex < 2) {
          sum += RiftRegion.resetOpenHandle { region ?=>
            val cells: Array[Cell^{region}]^{region} =
              RiftAllocator.allocateOpenHandle(
                region,
                new Array[Cell^{region}](3)
              )
            var i = 0
            while (i < cells.length) {
              cells(i) =
                RiftAllocator.allocateOpenHandle(
                  region,
                  new Cell(epochIndex * 10 + i)
                )
              i += 1
            }

            var local = 0
            var j = 0
            while (j < cells.length) {
              local += cells(j).value
              j += 1
            }
            local
          }
          epochIndex += 1
        }
        sum
      }

      assertEquals(36, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def safeZoneBackedEpochAllocatesRegionOwnedArrays(): Unit = {
    val total = RiftRegion.streamingSafeZone { stream ?=>
      final class Cell(val value: Int)

      var sum = 0
      var epochIndex = 0
      while (epochIndex < 2) {
        sum += RiftRegion.epoch { epoch ?=>
          val cells: Array[Cell^{epoch}]^{epoch} =
            RiftRegion.allocOpen(new Array[Cell^{epoch}](3))
          var i = 0
          while (i < cells.length) {
            cells(i) =
              RiftRegion.allocOpen(new Cell(epochIndex * 10 + i))
            i += 1
          }

          var local = 0
          var j = 0
          while (j < cells.length) {
            local += cells(j).value
            j += 1
          }
          local
        }
        epochIndex += 1
      }
      sum
    }

    assertEquals(36, total)
  }

  @Test def closedChildStreamingRejectsLaterAllocation(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int)

        val child = RiftRegion.childStreaming
        child.close()
        assertThrows(
          classOf[IllegalStateException],
          () => {
            val event = RiftRegion.alloc(new Event(1))(using child)
            java.lang.System.identityHashCode(event)
            ()
          }
        )
        42
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def safeZoneBackedClosedChildStreamingRejectsLaterAllocation(): Unit = {
    val total = RiftRegion.streamingSafeZone { stream ?=>
      final class Event(val value: Int)

      val child = RiftRegion.childStreaming
      child.close()
      assertThrows(
        classOf[IllegalStateException],
        () => {
          val event = RiftRegion.alloc(new Event(1))(using child)
          java.lang.System.identityHashCode(event)
          ()
        }
      )
      42
    }

    assertEquals(42, total)
  }

  @Test def epochTopKByKeyRanksAndClearsEpochs(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val key: Int, val value: Int)

        val topK = RiftRegion.epochTopKByKey(8, 3)
        var checksum = 0
        var epochIndex = 0
        while (epochIndex < 2) {
          RiftRegion.beginEpochTopKByKey(stream, topK)
          RiftRegion.epoch { epoch ?=>
            val first: Event^{epoch} =
              RiftRegion.allocOpen(new Event(2, 10))
            val second: Event^{epoch} =
              RiftRegion.allocOpen(new Event(if (epochIndex == 0) 1 else 3, 20))
            RiftRegion.incrementEpochTopKByKey(stream, topK, first.key)
            RiftRegion.addEpochTopKByKey(stream, topK, second.key, 2)
            first.value + second.value
          }

          val length = RiftRegion.finishEpochTopKByKey(stream, topK)
          checksum += length
          var rank = 0
          while (rank < length) {
            checksum +=
              RiftRegion.epochTopKKey(stream, topK, rank) * (rank + 1) * 10
            checksum += RiftRegion.epochTopKCount(stream, topK, rank)
            rank += 1
          }
          epochIndex += 1
        }
        checksum
      }

      assertEquals(130, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def safeZoneBackedEpochTopKByKeyRanksAndClearsEpochs(): Unit = {
    val total = RiftRegion.streamingSafeZone { stream ?=>
      final class Event(val key: Int, val value: Int)

      val topK = RiftRegion.epochTopKByKey(8, 2)
      RiftRegion.beginEpochTopKByKey(stream, topK)
      RiftRegion.epoch { epoch ?=>
        val first: Event^{epoch} =
          RiftRegion.allocOpen(new Event(4, 40))
        val second: Event^{epoch} =
          RiftRegion.allocOpen(new Event(1, 2))
        RiftRegion.addEpochTopKByKey(stream, topK, first.key, 1)
        RiftRegion.addEpochTopKByKey(stream, topK, second.key, 2)
      }

      val length = RiftRegion.finishEpochTopKByKey(stream, topK)
      length +
        RiftRegion.epochTopKKey(stream, topK, 0) * 10 +
        RiftRegion.epochTopKCount(stream, topK, 0) +
        RiftRegion.epochTopKKey(stream, topK, 1) * 100 +
        RiftRegion.epochTopKCount(stream, topK, 1)
    }

    assertEquals(415, total)
  }

  @Test def epochTopKByKeyRejectsInvalidKeys(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        val topK = RiftRegion.epochTopKByKey(4, 2)
        RiftRegion.beginEpochTopKByKey(stream, topK)

        assertThrows(
          classOf[IndexOutOfBoundsException],
          () => RiftRegion.incrementEpochTopKByKey(stream, topK, -1)
        )
        assertThrows(
          classOf[IndexOutOfBoundsException],
          () => RiftRegion.addEpochTopKByKey(stream, topK, 4, 0)
        )

        42
      }

      assertEquals(42, total)
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
        ): Unit = {
          var current = cursor.nextOwnedOrNull()
          while (current != null) {
            val event = current.asInstanceOf[Event^{stream}]
            sum += event.value + bucket.startSeconds.toInt
            current = cursor.nextOwnedOrNull()
          }
        }

        val firstRegion =
          RiftRegion.pageTokenAppendOpenRegionFor(
            stream,
            window,
            7L,
            Long.MinValue
          )(consume)
        val first: Event^{stream} =
          RiftRegion.allocOpen(new Event(20))(using firstRegion)
        RiftRegion.appendPageToken(stream, window, first)

        val secondRegion =
          RiftRegion.pageTokenAppendOpenRegionFor(stream, window, 17L, 10L)(
            consume
          )
        val second: Event^{stream} =
          RiftRegion.allocOpen(new Event(12))(using secondRegion)
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

  @Test def streamPageTokenAppendWindowInfersChildRegionLocalNewPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val window = RiftRegion.streamPageTokenAppendWindow[Event](10)
        var sum = 0

        def consume(
            bucket: RiftRegion.StreamBucket^{stream},
            cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
        ): Unit = {
          var current = cursor.nextOwnedOrNull()
          while (current != null) {
            val event = current.asInstanceOf[Event^{stream}]
            sum += event.value + bucket.startSeconds.toInt
            current = cursor.nextOwnedOrNull()
          }
        }

        val region =
          RiftRegion.pageTokenAppendRegionFor(stream, window, 7L, Long.MinValue)(
            consume
          )
        val event: Event^{region} = new Event(41)
        val widened: Event^{stream} = event
        RiftRegion.appendPageToken(stream, window, widened)

        RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
          consume
        )
        sum + 1
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(after - before >= 1L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamPageTokenAppendWindowInfersOpenChildRegionLocalNewPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val window = RiftRegion.streamPageTokenAppendWindow[Event](10)
        var sum = 0

        def consume(
            bucket: RiftRegion.StreamBucket^{stream},
            cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
        ): Unit = {
          var current = cursor.nextOwnedOrNull()
          while (current != null) {
            val event = current.asInstanceOf[Event^{stream}]
            sum += event.value + bucket.startSeconds.toInt
            current = cursor.nextOwnedOrNull()
          }
        }

        val region =
          RiftRegion.pageTokenAppendOpenRegionFor(
            stream,
            window,
            7L,
            Long.MinValue
          )(consume)
        val event: Event^{region} = new Event(41)
        val widened: Event^{stream} = event
        RiftRegion.appendPageToken(stream, window, widened)

        RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
          consume
        )
        sum + 1
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(after - before >= 1L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamPageTokenAppendWindowInfersRiftOpenHandleLocalNewPlacement()
      : Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val window = RiftRegion.streamPageTokenAppendWindow[Event](10)
        var sum = 0

        def consume(
            bucket: RiftRegion.StreamBucket^{stream},
            cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
        ): Unit = {
          var current = cursor.nextOwnedOrNull()
          while (current != null) {
            val event = current.asInstanceOf[Event^{stream}]
            sum += event.value + bucket.startSeconds.toInt
            current = cursor.nextOwnedOrNull()
          }
        }

        val region =
          RiftRegion.pageTokenAppendRiftOpenHandleFor(
            stream,
            window,
            7L,
            Long.MinValue
          )(consume)
        val event: Event^{region} = new Event(41)
        val widened: Event^{stream} = event
        RiftRegion.appendPageToken(stream, window, widened)

        RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
          consume
        )
        sum + 1
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(after - before >= 1L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamPageTokenAppendWindowClosesAfterPartialCursorConsumption()
      : Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val window = RiftRegion.streamPageTokenAppendWindow[Event](10)
        var sum = 0

        def consumeOne(
            bucket: RiftRegion.StreamBucket^{stream},
            cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
        ): Unit = {
          val event = cursor.nextOrNull()
          if (event != null)
            sum += event.asInstanceOf[Event^{stream}].value +
              bucket.startSeconds.toInt
        }

        val firstRegion =
          RiftRegion.pageTokenAppendRegionFor(stream, window, 7L, Long.MinValue)(
            consumeOne
          )
        val first: Event^{stream} =
          RiftRegion.alloc(new Event(10))(using firstRegion)
        val second: Event^{stream} =
          RiftRegion.alloc(new Event(20))(using firstRegion)
        RiftRegion.appendPageToken(stream, window, first)
        RiftRegion.appendPageToken(stream, window, second)

        val nextRegion =
          RiftRegion.pageTokenAppendRegionFor(stream, window, 17L, 10L)(
            consumeOne
          )
        val third: Event^{stream} =
          RiftRegion.alloc(new Event(30))(using nextRegion)
        RiftRegion.appendPageToken(stream, window, third)

        RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
          consumeOne
        )
        sum
      }

      assertEquals(50, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def streamPageTokenAppendWindowNoDrainClosesZeroAndFinalBuckets()
      : Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val window = RiftRegion.streamPageTokenAppendWindow[Event](10)
        var sum = 0

        def recordBucket(bucket: RiftRegion.StreamBucket^{stream}): Unit =
          sum += bucket.startSeconds.toInt + 16

        val emptyRegion =
          RiftRegion.pageTokenAppendRegionFor(stream, window, 7L, Long.MinValue) {
            (_, _) => ()
          }
        java.lang.System.identityHashCode(emptyRegion)

        val nextRegion =
          RiftRegion.pageTokenAppendRegionFor(stream, window, 17L, Long.MinValue) {
            (_, _) => ()
          }
        val event: Event^{stream} =
          RiftRegion.alloc(new Event(32))(using nextRegion)
        RiftRegion.appendPageToken(stream, window, event)

        RiftRegion.closeAllPageTokenAppendBucketsNoDrain(stream, window)(
          recordBucket
        )
        sum
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def pageTokenAppendRegionRejectsAllocationAfterCloseAll(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val window = RiftRegion.streamPageTokenAppendWindow[Event](10)
        def consume(
            bucket: RiftRegion.StreamBucket^{stream},
            cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
        ): Unit = ()

        val region =
          RiftRegion.pageTokenAppendRegionFor(stream, window, 7L, Long.MinValue)(
            consume
          )
        val event: Event^{stream} =
          RiftRegion.alloc(new Event(41))(using region)
        RiftRegion.appendPageToken(stream, window, event)
        RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
          consume
        )

        assertThrows(
          classOf[IllegalStateException],
          () => {
            val afterClose = RiftRegion.alloc(new Event(1))(using region)
            java.lang.System.identityHashCode(afterClose)
            ()
          }
        )
        42
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def safeZoneBackedPageTokenAppendRegionRejectsAllocationAfterCloseAll()
      : Unit = {
    val total = RiftRegion.streamingSafeZone { stream ?=>
      final class Event(val value: Int) extends RiftRegion.StreamAppendNode

      val window = RiftRegion.streamPageTokenAppendWindow[Event](10)
      def consume(
          bucket: RiftRegion.StreamBucket^{stream},
          cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
      ): Unit = ()

      val region =
        RiftRegion.pageTokenAppendRegionFor(stream, window, 7L, Long.MinValue)(
          consume
        )
      val event: Event^{stream} =
        RiftRegion.alloc(new Event(41))(using region)
      RiftRegion.appendPageToken(stream, window, event)
      RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
        consume
      )

      assertThrows(
        classOf[IllegalStateException],
        () => {
          val afterClose = RiftRegion.alloc(new Event(1))(using region)
          java.lang.System.identityHashCode(afterClose)
          ()
        }
      )
      42
    }

    assertEquals(42, total)
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

  @Test def pageTokenMapFilterInfersChildRegionLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
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

        val region =
          RiftRegion.pageTokenMapFilterRegionFor(
            stream,
            operator,
            7L,
            Long.MinValue
          )(consume)
        val event: Event^{region} = new Event(41)
        val widened: Event^{stream} = event
        RiftRegion.emitPageTokenMapFilter(stream, operator, widened)

        RiftRegion.closeAllPageTokenMapFilterBucketsWithCursor(stream, operator)(
          consume
        )
        sum + 1
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(after - before >= 1L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def pageTokenMapFilterOpenRegionInfersLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
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

        val region =
          RiftRegion.pageTokenMapFilterOpenRegionFor(
            stream,
            operator,
            7L,
            Long.MinValue
          )(consume)
        val event: Event^{region} = new Event(41)
        val widened: Event^{stream} = event
        RiftRegion.emitPageTokenMapFilter(stream, operator, widened)

        RiftRegion.closeAllPageTokenMapFilterBucketsWithCursor(stream, operator)(
          consume
        )
        sum + 1
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(after - before >= 1L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def pageTokenCountByKeyAggregatesAndClosesNoDrain(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val key: Int, val value: Int)
            extends RiftRegion.StreamAppendNode

        val operator = RiftRegion.pageTokenCountByKey[Event](10, 8, 2)
        var sum = 0L

        def consume(
            bucket: RiftRegion.StreamBucket^{stream},
            key: Int,
            count: Int,
            valueSum: Long
        ): Unit =
          sum += bucket.startSeconds + key.toLong + count.toLong + valueSum

        val firstRegion =
          RiftRegion.pageTokenCountByKeyRegionFor(
            stream,
            operator,
            7L,
            Long.MinValue
          )(consume)
        val first: Event^{stream} =
          RiftRegion.alloc(new Event(2, 10))(using firstRegion)
        val second: Event^{stream} =
          RiftRegion.alloc(new Event(2, 20))(using firstRegion)
        RiftRegion.appendPageTokenCountByKey(
          stream,
          operator,
          first,
          first.key,
          first.value.toLong
        )
        RiftRegion.appendPageTokenCountByKey(
          stream,
          operator,
          second,
          second.key,
          second.value.toLong
        )

        val secondRegion =
          RiftRegion.pageTokenCountByKeyRegionFor(stream, operator, 17L, 10L)(
            consume
          )
        val third: Event^{stream} =
          RiftRegion.alloc(new Event(1, 2))(using secondRegion)
        RiftRegion.appendPageTokenCountByKey(
          stream,
          operator,
          third,
          third.key,
          third.value.toLong
        )

        RiftRegion.closeAllPageTokenCountByKeyBuckets(stream, operator)(
          consume
        )
        sum
      }

      assertEquals(48L, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def pageTokenCountByKeyInfersChildRegionLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val key: Int, val value: Int)
            extends RiftRegion.StreamAppendNode

        val operator = RiftRegion.pageTokenCountByKey[Event](10, 8, 2)
        var sum = 0L

        def consume(
            bucket: RiftRegion.StreamBucket^{stream},
            key: Int,
            count: Int,
            valueSum: Long
        ): Unit =
          sum += bucket.startSeconds + key.toLong + count.toLong + valueSum

        val region =
          RiftRegion.pageTokenCountByKeyRegionFor(
            stream,
            operator,
            7L,
            Long.MinValue
          )(consume)
        val event: Event^{region} = new Event(2, 40)
        val widened: Event^{stream} = event
        RiftRegion.appendPageTokenCountByKey(
          stream,
          operator,
          widened,
          widened.key,
          widened.value.toLong
        )

        RiftRegion.closeAllPageTokenCountByKeyBuckets(stream, operator)(
          consume
        )
        sum
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(43L, total)
      assertTrue(after - before >= 1L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def pageTokenCountByKeyOpenRegionInfersLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val key: Int, val value: Int)
            extends RiftRegion.StreamAppendNode

        val operator = RiftRegion.pageTokenCountByKey[Event](10, 8, 2)
        var sum = 0L

        def consume(
            bucket: RiftRegion.StreamBucket^{stream},
            key: Int,
            count: Int,
            valueSum: Long
        ): Unit =
          sum += bucket.startSeconds + key.toLong + count.toLong + valueSum

        val region =
          RiftRegion.pageTokenCountByKeyOpenRegionFor(
            stream,
            operator,
            7L,
            Long.MinValue
          )(consume)
        val event: Event^{region} = new Event(2, 40)
        val widened: Event^{stream} = event
        RiftRegion.appendPageTokenCountByKey(
          stream,
          operator,
          widened,
          widened.key,
          widened.value.toLong
        )

        RiftRegion.closeAllPageTokenCountByKeyBuckets(stream, operator)(
          consume
        )
        sum
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(43L, total)
      assertTrue(after - before >= 1L)
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

  @Test def epochBufferRegionInfersLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
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

        val region = RiftRegion.epochBufferRegionFor(stream, buffer)
        val first: Event^{region} = new Event(30)
        val second: Event^{region} = new Event(12)
        val widenedFirst: Event^{stream} = first
        val widenedSecond: Event^{stream} = second
        RiftRegion.appendEpochBuffer(stream, buffer, widenedFirst)
        RiftRegion.appendEpochBuffer(stream, buffer, widenedSecond)
        assertEquals(2, RiftRegion.epochBufferLength(stream, buffer))

        RiftRegion.closeEpochBufferWithCursor(stream, buffer)(consume)
        assertEquals(0, RiftRegion.epochBufferLength(stream, buffer))
        sum
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def epochBufferOpenRegionAllocatesAndDrainsRecords(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val buffer = RiftRegion.epochBuffer[Event]()
        var sum = 0

        def consume(
            bucket: RiftRegion.StreamBucket^{stream},
            cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
        ): Unit = {
          var current = cursor.nextOwnedOrNull()
          while (current != null) {
            val event = current.asInstanceOf[Event^{stream}]
            sum += event.value + bucket.startSeconds.toInt
            current = cursor.nextOwnedOrNull()
          }
        }

        val region = RiftRegion.epochBufferOpenRegionFor(stream, buffer)
        val first: Event^{stream} =
          RiftRegion.allocOpen(new Event(30))(using region)
        val second: Event^{stream} =
          RiftRegion.allocOpen(new Event(12))(using region)
        RiftRegion.appendEpochBuffer(stream, buffer, first)
        RiftRegion.appendEpochBuffer(stream, buffer, second)
        assertEquals(2, RiftRegion.epochBufferLength(stream, buffer))

        RiftRegion.closeEpochBufferWithCursor(stream, buffer)(consume)
        assertEquals(0, RiftRegion.epochBufferLength(stream, buffer))
        sum
      }

      assertEquals(42, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def epochBufferOpenRegionInfersLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val buffer = RiftRegion.epochBuffer[Event]()
        var sum = 0

        def consume(
            bucket: RiftRegion.StreamBucket^{stream},
            cursor: RiftRegion.StreamAppendCursor[Event]^{stream}
        ): Unit = {
          var current = cursor.nextOwnedOrNull()
          while (current != null) {
            val event = current.asInstanceOf[Event^{stream}]
            sum += event.value + bucket.startSeconds.toInt
            current = cursor.nextOwnedOrNull()
          }
        }

        val region = RiftRegion.epochBufferOpenRegionFor(stream, buffer)
        val first: Event^{region} = new Event(30)
        val second: Event^{region} = new Event(12)
        val widenedFirst: Event^{stream} = first
        val widenedSecond: Event^{stream} = second
        RiftRegion.appendEpochBuffer(stream, buffer, widenedFirst)
        RiftRegion.appendEpochBuffer(stream, buffer, widenedSecond)
        assertEquals(2, RiftRegion.epochBufferLength(stream, buffer))

        RiftRegion.closeEpochBufferWithCursor(stream, buffer)(consume)
        assertEquals(0, RiftRegion.epochBufferLength(stream, buffer))
        sum
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(after - before >= 2L)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def transactionRegionAllocatesAndDrainsMultipleLists(): Unit = {
    RiftRegion.init(1)
    try {
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val tx = RiftRegion.transactionRegion(2)
        val input = RiftRegion.transactionList[Event](stream, tx, 0)
        val output = RiftRegion.transactionList[Event](stream, tx, 1)
        val region = RiftRegion.transactionRegionFor(stream, tx)

        val first: Event^{stream} =
          RiftRegion.alloc(new Event(20))(using region)
        val second: Event^{stream} =
          RiftRegion.alloc(new Event(21))(using region)
        RiftRegion.appendTransactionList(stream, input, first)
        RiftRegion.appendTransactionList(stream, input, second)
        assertEquals(2, RiftRegion.transactionListLength(stream, input))
        assertEquals(0, RiftRegion.transactionListLength(stream, output))

        RiftRegion.drainTransactionListWithCursor(stream, input) { cursor =>
          while (cursor.hasNext) {
            val event = cursor.next()
            val projected: Event^{stream} =
              RiftRegion.alloc(new Event(event.value + 1))(using region)
            RiftRegion.appendTransactionList(stream, output, projected)
          }
        }
        assertEquals(0, RiftRegion.transactionListLength(stream, input))
        assertEquals(2, RiftRegion.transactionListLength(stream, output))

        var sum = 0
        RiftRegion.drainTransactionListWithCursor(stream, output) { cursor =>
          while (cursor.hasNext)
            sum += cursor.next().value
        }
        RiftRegion.closeTransactionRegion(stream, tx)

        val nextRegion = RiftRegion.transactionRegionFor(stream, tx)
        val third: Event^{stream} =
          RiftRegion.alloc(new Event(0))(using nextRegion)
        RiftRegion.appendTransactionList(stream, input, third)
        RiftRegion.drainTransactionListWithCursor(stream, input) { cursor =>
          while (cursor.hasNext)
            sum += cursor.next().value
        }
        RiftRegion.closeTransactionRegion(stream, tx)
        sum
      }

      assertEquals(43, total)
    } finally {
      RiftRegion.shutdown()
    }
  }

  @Test def transactionRegionInfersLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val value: Int) extends RiftRegion.StreamAppendNode

        val tx = RiftRegion.transactionRegion(1)
        val input = RiftRegion.transactionList[Event](stream, tx, 0)
        val region = RiftRegion.transactionRegionFor(stream, tx)
        val event: Event^{region} = new Event(42)
        val widened: Event^{stream} = event
        RiftRegion.appendTransactionList(stream, input, widened)

        var sum = 0
        RiftRegion.drainTransactionListWithCursor(stream, input) { cursor =>
          while (cursor.hasNext)
            sum += cursor.next().value
        }
        RiftRegion.closeTransactionRegion(stream, tx)
        sum
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(after - before >= 1L)
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

  @Test def streamChunkAppendWindowInfersLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
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

        val region =
          RiftRegion.chunkAppendRegionFor(stream, window, 7L, Long.MinValue)(
            consume
          )
        val event: Event^{region} = new Event(41)
        val widened: Event^{stream} = event
        RiftRegion.appendChunkToken(stream, window, widened)
        RiftRegion.closeAllChunkAppendBucketsWithCursor(stream, window)(
          consume
        )
        sum + 1
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())

      assertEquals(42, total)
      assertTrue(after - before >= 1L)
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

  @Test def epochFoldInfersChildRegionLocalNewPlacement(): Unit = {
    RiftRegion.init(1)
    try {
      RiftAllocator.Impl.statsReset()
      val before = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val total = RiftRegion.streaming { stream ?=>
        final class Event(val key: Int, val delta: Long, val value: Int)
            extends RiftRegion.StreamAppendNode

        val fold = RiftRegion.epochFold[Event](10, 16)
        val region = RiftRegion.epochFoldRegionFor(stream, fold, 7L)
        val first: Event^{region} = new Event(3, 20L, 20)
        val second: Event^{region} = new Event(3, 21L, 21)
        val widenedFirst: Event^{stream} = first
        val widenedSecond: Event^{stream} = second
        val firstSum =
          RiftRegion.putEpochFold(
            stream,
            fold,
            widenedFirst.key,
            widenedFirst.delta,
            widenedFirst
          )
        val secondSum =
          RiftRegion.putEpochFold(
            stream,
            fold,
            widenedSecond.key,
            widenedSecond.delta,
            widenedSecond
          )

        assertEquals(20L, firstSum)
        assertEquals(41L, secondSum)
        assertEquals(2, RiftRegion.epochFoldCount(stream, fold, 3))

        var closed = 0
        RiftRegion.closeEpochFoldCurrentBucketAndClear(stream, fold) {
          (_, cursor) =>
            while (cursor.hasNext)
              closed += cursor.next().value
        }

        closed + 1
      }
      val after = rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal())
      val delta = after - before

      assertEquals(42, total)
      assertTrue(
        s"expected epochFoldRegionFor local new records to be region allocated, observed $delta region objects",
        delta >= 2L
      )
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
