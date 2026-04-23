import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.memory.SafeZone._
import scala.scalanative.runtime.SafeZoneAllocator.allocate

object ListOfListsTopologyMatrixHelpers {
  @volatile private var checksumSink = 0L

  private def expectedChecksum(n: Int, structures: Int): Long =
    structures.toLong * n.toLong * n.toLong * (n.toLong - 1L)

  private def runHeapStructure(n: Int): Long = {
    final class BasicObject(val value: Int)
    final class InnerNode(val obj: BasicObject, val next: InnerNode)
    final class OuterNode(val inner: InnerNode, val next: OuterNode)

    var outerHead: OuterNode = null
    var outer = 0
    while (outer < n) {
      var innerHead: InnerNode = null
      var inner = 0
      while (inner < n) {
        val obj = new BasicObject((outer + inner) & 0x7fffffff)
        innerHead = new InnerNode(obj, innerHead)
        inner += 1
      }
      outerHead = new OuterNode(innerHead, outerHead)
      outer += 1
    }

    var checksum = 0L
    var outerCursor = outerHead
    while (outerCursor != null) {
      var innerCursor = outerCursor.inner
      while (innerCursor != null) {
        checksum += innerCursor.obj.value.toLong
        innerCursor = innerCursor.next
      }
      outerCursor = outerCursor.next
    }
    outerHead = null
    checksum
  }

  private def runSafeZoneOneRegionStructure(n: Int): Long =
    SafeZone { sz ?=>
      final class BasicObject(val value: Int)
      final class InnerNode(val obj: BasicObject^{sz}, val next: InnerNode^{sz})
      final class OuterNode(val inner: InnerNode^{sz}, val next: OuterNode^{sz})

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

      var checksum = 0L
      var outerCursor = outerHead
      while (outerCursor != null) {
        var innerCursor = outerCursor.inner
        while (innerCursor != null) {
          checksum += innerCursor.obj.value.toLong
          innerCursor = innerCursor.next
        }
        outerCursor = outerCursor.next
      }
      outerHead = null
      checksum
    }

  private def runSafeZoneNestedStructure(n: Int): Long =
    SafeZone { outerZone ?=>
      final class OuterNode(val rowChecksum: Long, val next: OuterNode^{outerZone})

      var outerHead: OuterNode^{outerZone} = null
      var outer = 0
      while (outer < n) {
        val rowChecksum = SafeZone { innerZone ?=>
          final class BasicObject(val value: Int)
          final class InnerNode(
              val obj: BasicObject^{innerZone},
              val next: InnerNode^{innerZone}
          )

          var innerHead: InnerNode^{innerZone} = null
          var inner = 0
          while (inner < n) {
            val obj =
              allocate(innerZone, new BasicObject((outer + inner) & 0x7fffffff))
            innerHead = allocate(innerZone, new InnerNode(obj, innerHead))
            inner += 1
          }

          var checksum = 0L
          var innerCursor = innerHead
          while (innerCursor != null) {
            checksum += innerCursor.obj.value.toLong
            innerCursor = innerCursor.next
          }
          innerHead = null
          checksum
        }

        outerHead = allocate(outerZone, new OuterNode(rowChecksum, outerHead))
        outer += 1
      }

      var checksum = 0L
      var outerCursor = outerHead
      while (outerCursor != null) {
        checksum += outerCursor.rowChecksum
        outerCursor = outerCursor.next
      }
      outerHead = null
      checksum
    }

  private def runSafeZoneMixedStructure(n: Int): Long =
    SafeZone { sz ?=>
      final class BasicObject(val value: Int)
      final class InnerNode(val obj: BasicObject, val next: InnerNode^{sz})
      final class OuterNode(val inner: InnerNode^{sz}, val next: OuterNode^{sz})

      val valueRoots = new Array[BasicObject](n * n)
      var valueIndex = 0
      var outerHead: OuterNode^{sz} = null
      var outer = 0
      while (outer < n) {
        var innerHead: InnerNode^{sz} = null
        var inner = 0
        while (inner < n) {
          val obj = new BasicObject((outer + inner) & 0x7fffffff)
          valueRoots(valueIndex) = obj
          valueIndex += 1
          innerHead = allocate(sz, new InnerNode(obj, innerHead))
          inner += 1
        }
        outerHead = allocate(sz, new OuterNode(innerHead, outerHead))
        outer += 1
      }

      var checksum = 0L
      var outerCursor = outerHead
      while (outerCursor != null) {
        var innerCursor = outerCursor.inner
        while (innerCursor != null) {
          checksum += innerCursor.obj.value.toLong
          innerCursor = innerCursor.next
        }
        outerCursor = outerCursor.next
      }
      outerHead = null
      if (valueRoots(0) == null)
        throw new IllegalStateException("heap value root table was corrupted")
      checksum
    }

  private def runRiftOneRegionStructure(kind: Int, n: Int): Long = {
    final class BasicObject(val value: Int)
    final class InnerNode(val obj: BasicObject, val next: InnerNode)
    final class OuterNode(val inner: InnerNode, val next: OuterNode)

    val region = RiftRegion.open(kind)
    try {
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

      var checksum = 0L
      var outerCursor = outerHead
      while (outerCursor != null) {
        var innerCursor = outerCursor.inner
        while (innerCursor != null) {
          checksum += innerCursor.obj.value.toLong
          innerCursor = innerCursor.next
        }
        outerCursor = outerCursor.next
      }
      outerHead = null
      checksum
    } finally region.close()
  }

  private def runRiftNestedStructure(kind: Int, n: Int): Long = {
    final class OuterNode(val rowChecksum: Long, val next: OuterNode)
    final class BasicObject(val value: Int)
    final class InnerNode(val obj: BasicObject, val next: InnerNode)

    val outerRegion = RiftRegion.open(kind)
    try {
      var outerHead: OuterNode = null
      var outer = 0
      while (outer < n) {
        val innerRegion = RiftRegion.open(kind)
        val rowChecksum =
          try {
            var innerHead: InnerNode = null
            var inner = 0
            while (inner < n) {
              val obj =
                innerRegion.alloc(new BasicObject((outer + inner) & 0x7fffffff))
              innerHead = innerRegion.alloc(new InnerNode(obj, innerHead))
              inner += 1
            }

            var checksum = 0L
            var innerCursor = innerHead
            while (innerCursor != null) {
              checksum += innerCursor.obj.value.toLong
              innerCursor = innerCursor.next
            }
            innerHead = null
            checksum
          } finally innerRegion.close()

        outerHead = outerRegion.alloc(new OuterNode(rowChecksum, outerHead))
        outer += 1
      }

      var checksum = 0L
      var outerCursor = outerHead
      while (outerCursor != null) {
        checksum += outerCursor.rowChecksum
        outerCursor = outerCursor.next
      }
      outerHead = null
      checksum
    } finally outerRegion.close()
  }

  private def runRiftMixedStructure(kind: Int, n: Int): Long = {
    final class BasicObject(val value: Int)
    final class InnerNode(val obj: BasicObject, val next: InnerNode)
    final class OuterNode(val inner: InnerNode, val next: OuterNode)

    val valueRoots = new Array[BasicObject](n * n)
    val region = RiftRegion.open(kind)
    try {
      var valueIndex = 0
      var outerHead: OuterNode = null
      var outer = 0
      while (outer < n) {
        var innerHead: InnerNode = null
        var inner = 0
        while (inner < n) {
          val obj = new BasicObject((outer + inner) & 0x7fffffff)
          valueRoots(valueIndex) = obj
          valueIndex += 1
          innerHead = region.alloc(new InnerNode(obj, innerHead))
          inner += 1
        }
        outerHead = region.alloc(new OuterNode(innerHead, outerHead))
        outer += 1
      }

      var checksum = 0L
      var outerCursor = outerHead
      while (outerCursor != null) {
        var innerCursor = outerCursor.inner
        while (innerCursor != null) {
          checksum += innerCursor.obj.value.toLong
          innerCursor = innerCursor.next
        }
        outerCursor = outerCursor.next
      }
      outerHead = null
      if (valueRoots(0) == null)
        throw new IllegalStateException("heap value root table was corrupted")
      checksum
    } finally region.close()
  }

  private def runStructures(name: String)(runOne: Int => Long): Boolean = {
    val cfg = ListOfListsConfig
    var checksum = 0L
    var structures = 0
    while (structures < cfg.structures) {
      checksum += runOne(cfg.n)
      structures += 1
    }
    checksumSink = checksum
    val expected = expectedChecksum(cfg.n, cfg.structures)
    if (checksum != expected)
      println(s"CHECKSUM_MISMATCH name=$name actual=$checksum expected=$expected")
    checksum == expected
  }

  def runHeap(): Boolean =
    runStructures("heap")(runHeapStructure)

  def runSafeZoneOneRegion(): Boolean =
    runStructures("safezone-one")(runSafeZoneOneRegionStructure)

  def runSafeZoneNested(): Boolean =
    runStructures("safezone-nested")(runSafeZoneNestedStructure)

  def runSafeZoneMixed(): Boolean =
    runStructures("safezone-mixed")(runSafeZoneMixedStructure)

  def runRiftOneRegion(kind: Int): Boolean =
    runStructures("rift-one")(runRiftOneRegionStructure(kind, _))

  def runRiftNested(kind: Int): Boolean =
    runStructures("rift-nested")(runRiftNestedStructure(kind, _))

  def runRiftMixed(kind: Int): Boolean =
    runStructures("rift-mixed")(runRiftMixedStructure(kind, _))

  def printConfig(mode: String): Unit = {
    val cfg = ListOfListsConfig
    val rootsMode = sys.env.getOrElse("SAFEZONE_ROOTS_MODE", "0")
    val pageSize = sys.env.getOrElse("SAFEZONE_PAGE_SIZE", "default")
    println(
      s"CONFIG mode=$mode runs=${cfg.benchmarkRuns} n=${cfg.n} structures=${cfg.structures} safezone_roots_mode=$rootsMode safezone_page_size=$pageSize"
    )
  }
}

@main def ListOfListsTopologyMatrix(mode: String = "heap"): Unit = {
  ListOfListsTopologyMatrixHelpers.printConfig(mode)

  val stats = mode match {
    case "heap" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-topology-heap",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsTopologyMatrixHelpers.runHeap())
    case "safezone-one" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-topology-safezone-one",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsTopologyMatrixHelpers.runSafeZoneOneRegion())
    case "safezone-nested" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-topology-safezone-nested",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsTopologyMatrixHelpers.runSafeZoneNested())
    case "safezone-mixed" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-topology-safezone-mixed",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsTopologyMatrixHelpers.runSafeZoneMixed())
    case "rift-one" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-topology-rift-one",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsTopologyMatrixHelpers.runRiftOneRegion(RiftRegion.HPZone))
    case "rift-nested" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-topology-rift-nested",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsTopologyMatrixHelpers.runRiftNested(RiftRegion.HPZone))
    case "rift-mixed" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-topology-rift-mixed",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsTopologyMatrixHelpers.runRiftMixed(RiftRegion.HPZone))
    case other =>
      throw new IllegalArgumentException(
        s"unknown mode '$other'; expected heap, safezone-one, safezone-nested, safezone-mixed, rift-one, rift-nested, or rift-mixed"
      )
  }

  if (stats.medianMs.isNaN)
    throw new IllegalStateException("benchmark stats were not computed")
}
