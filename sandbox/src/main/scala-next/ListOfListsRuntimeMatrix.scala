import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.memory.SafeZone._
import scala.scalanative.runtime.SafeZoneAllocator.allocate

object ListOfListsRuntimeMatrixHelpers {
  @volatile private var checksumSink = 0L

  private final class HeapBasicObject(val value: Int)
  private final class HeapInnerNode(
      val obj: HeapBasicObject,
      val next: HeapInnerNode
  )
  private final class HeapOuterNode(
      val inner: HeapInnerNode,
      val next: HeapOuterNode
  )

  private def expectedChecksum(n: Int, structures: Int): Long =
    structures.toLong * n.toLong * n.toLong * (n.toLong - 1L)

  private def runHeapStructure(n: Int): Long = {
    def buildOneStructure(): HeapOuterNode = {
      var outerHead: HeapOuterNode = null
      var outer = 0
      while (outer < n) {
        var innerHead: HeapInnerNode = null
        var inner = 0
        while (inner < n) {
          val obj = new HeapBasicObject((outer + inner) & 0x7fffffff)
          innerHead = new HeapInnerNode(obj, innerHead)
          inner += 1
        }
        outerHead = new HeapOuterNode(innerHead, outerHead)
        outer += 1
      }
      outerHead
    }

    var checksum = 0L
    var root = buildOneStructure()
    var outerCursor = root
    while (outerCursor != null) {
      var innerCursor = outerCursor.inner
      while (innerCursor != null) {
        checksum += innerCursor.obj.value.toLong
        innerCursor = innerCursor.next
      }
      outerCursor = outerCursor.next
    }
    root = null
    checksum
  }

  private def runSafeZoneStructure(n: Int): Long =
    SafeZone { sz ?=>
      final class BasicObject(val value: Int)
      final class InnerNode(val obj: BasicObject^{sz}, val next: InnerNode^{sz})
      final class OuterNode(val inner: InnerNode^{sz}, val next: OuterNode^{sz})

      def buildOneStructure(): OuterNode^{sz} = {
        var outerHead: OuterNode^{sz} = null
        var outer = 0
        while (outer < n) {
          var innerHead: InnerNode^{sz} = null
          var inner = 0
          while (inner < n) {
            val obj = allocate(sz, new BasicObject((outer + inner) & 0x7fffffff))
            innerHead = allocate(sz, new InnerNode(obj, innerHead))
            inner += 1
          }
          outerHead = allocate(sz, new OuterNode(innerHead, outerHead))
          outer += 1
        }
        outerHead
      }

      var checksum = 0L
      var root = buildOneStructure()
      var outerCursor = root
      while (outerCursor != null) {
        var innerCursor = outerCursor.inner
        while (innerCursor != null) {
          checksum += innerCursor.obj.value.toLong
          innerCursor = innerCursor.next
        }
        outerCursor = outerCursor.next
      }
      root = null
      checksum
    }

  private def runRiftStructure(kind: Int, n: Int): Long = {
    final class BasicObject(val value: Int)
    final class InnerNode(val obj: BasicObject, val next: InnerNode)
    final class OuterNode(val inner: InnerNode, val next: OuterNode)

    val region = RiftRegion.open(kind)
    try {
      def buildOneStructure(): OuterNode = {
        var outerHead: OuterNode = null
        var outer = 0
        while (outer < n) {
          var innerHead: InnerNode = null
          var inner = 0
          while (inner < n) {
            val obj = region.alloc(new BasicObject((outer + inner) & 0x7fffffff))
            innerHead = region.alloc(new InnerNode(obj, innerHead))
            inner += 1
          }
          outerHead = region.alloc(new OuterNode(innerHead, outerHead))
          outer += 1
        }
        outerHead
      }

      var checksum = 0L
      var root = buildOneStructure()
      var outerCursor = root
      while (outerCursor != null) {
        var innerCursor = outerCursor.inner
        while (innerCursor != null) {
          checksum += innerCursor.obj.value.toLong
          innerCursor = innerCursor.next
        }
        outerCursor = outerCursor.next
      }
      root = null
      checksum
    } finally region.close()
  }

  private def runCheckedListBuilderStructure(n: Int): Long =
    RiftRegion.scoped { region ?=>
      final class BasicObject(val value: Int) extends RiftRegion.RegionListNode
      final class InnerList(
          val values: RiftRegion.RegionList[BasicObject]^{region}
      ) extends RiftRegion.RegionListNode

      def buildOneStructure(): RiftRegion.RegionList[InnerList]^{region} = {
        val outerList = RiftRegion.regionList[InnerList]()
        var outer = 0
        while (outer < n) {
          val innerList = RiftRegion.regionList[BasicObject]()
          var inner = 0
          while (inner < n) {
            val obj: BasicObject^{region} =
              RiftRegion.alloc(
                new BasicObject((outer + inner) & 0x7fffffff)
              )
            RiftRegion.prependRegionList(region, innerList, obj)
            inner += 1
          }
          val row: InnerList^{region} =
            RiftRegion.alloc(new InnerList(innerList))
          RiftRegion.prependRegionList(region, outerList, row)
          outer += 1
        }
        outerList
      }

      var checksum = 0L
      var root = buildOneStructure()
      var outerCursor = RiftRegion.regionListHead(region, root)
      while (outerCursor != null) {
        var innerCursor = RiftRegion.regionListHead(region, outerCursor.values)
        while (innerCursor != null) {
          checksum += innerCursor.value.toLong
          innerCursor = RiftRegion.regionListNext(region, innerCursor)
        }
        outerCursor = RiftRegion.regionListNext(region, outerCursor)
      }
      root = null
      checksum
    }

  def runHeap(): Boolean = {
    val cfg = ListOfListsConfig
    var checksum = 0L
    var structures = 0
    while (structures < cfg.structures) {
      checksum += runHeapStructure(cfg.n)
      structures += 1
    }
    checksumSink = checksum
    checksum == expectedChecksum(cfg.n, cfg.structures)
  }

  def runSafeZone(): Boolean = {
    val cfg = ListOfListsConfig
    var checksum = 0L
    var structures = 0
    while (structures < cfg.structures) {
      checksum += runSafeZoneStructure(cfg.n)
      structures += 1
    }
    checksumSink = checksum
    checksum == expectedChecksum(cfg.n, cfg.structures)
  }

  def runRift(kind: Int): Boolean = {
    val cfg = ListOfListsConfig
    var checksum = 0L
    var structures = 0
    while (structures < cfg.structures) {
      checksum += runRiftStructure(kind, cfg.n)
      structures += 1
    }
    checksumSink = checksum
    checksum == expectedChecksum(cfg.n, cfg.structures)
  }

  def runCheckedListBuilder(): Boolean = {
    val cfg = ListOfListsConfig
    var checksum = 0L
    var structures = 0
    while (structures < cfg.structures) {
      checksum += runCheckedListBuilderStructure(cfg.n)
      structures += 1
    }
    checksumSink = checksum
    checksum == expectedChecksum(cfg.n, cfg.structures)
  }

  def canonicalMode(mode: String): String =
    mode match {
      case "gc-heap" => "heap"
      case "region-scoped-rooted" | "region-scoped-rootless" => "safezone"
      case "checked-listoflists-builder" | "checked-region-listoflists-builder" =>
        "rift-checked-list-builder"
      case other => other
    }

  def printConfig(mode: String): Unit = {
    val cfg = ListOfListsConfig
    val rootsMode = sys.env.getOrElse("SAFEZONE_ROOTS_MODE", "0")
    val pageSize = sys.env.getOrElse("SAFEZONE_PAGE_SIZE", "default")
    println(
      s"CONFIG mode=$mode runs=${cfg.benchmarkRuns} n=${cfg.n} structures=${cfg.structures} safezone_roots_mode=$rootsMode safezone_page_size=$pageSize"
    )
  }
}

@main def ListOfListsRuntimeMatrix(mode: String = "heap"): Unit = {
  ListOfListsRuntimeMatrixHelpers.printConfig(mode)
  val internalMode = ListOfListsRuntimeMatrixHelpers.canonicalMode(mode)
  val usesRift = internalMode == "rift-hp" || internalMode == "rift-checked-list-builder"
  if (usesRift) RiftRegion.init(0)

  val stats = try internalMode match {
    case "heap" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-heap",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsRuntimeMatrixHelpers.runHeap())
    case "safezone" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-safezone",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsRuntimeMatrixHelpers.runSafeZone())
    case "rift-hp" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-rift-hp",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsRuntimeMatrixHelpers.runRift(RiftRegion.HPZone))
    case "rift-checked-list-builder" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-rift-checked-list-builder",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsRuntimeMatrixHelpers.runCheckedListBuilder())
    case other =>
      throw new IllegalArgumentException(
        s"unknown mode '$other'; expected heap, safezone, rift-hp, or rift-checked-list-builder"
      )
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }

  if (stats.medianMs.isNaN)
    throw new IllegalStateException("benchmark stats were not computed")
}
