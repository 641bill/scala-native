import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.runtime.SafeZoneAllocator.allocate

object ListOfListsFlatMatrixHelpers {
  @volatile private var checksumSink = 0L

  private def expectedChecksum(n: Int, structures: Int): Long =
    structures.toLong * n.toLong * n.toLong * (n.toLong - 1L)

  private def cellCount(n: Int): Int =
    Math.multiplyExact(n, n)

  private def runHeapStructure(n: Int): Long = {
    val values = new Array[Int](cellCount(n))

    var outer = 0
    while (outer < n) {
      val rowBase = outer * n
      var inner = 0
      while (inner < n) {
        values(rowBase + inner) = (outer + inner) & 0x7fffffff
        inner += 1
      }
      outer += 1
    }

    var checksum = 0L
    var i = 0
    while (i < values.length) {
      checksum += values(i).toLong
      i += 1
    }
    checksum
  }

  private def runSafeZoneStructure(n: Int): Long =
    SafeZone { sz ?=>
      val values = allocate(sz, new Array[Int](cellCount(n)))

      var outer = 0
      while (outer < n) {
        val rowBase = outer * n
        var inner = 0
        while (inner < n) {
          values(rowBase + inner) = (outer + inner) & 0x7fffffff
          inner += 1
        }
        outer += 1
      }

      var checksum = 0L
      var i = 0
      while (i < values.length) {
        checksum += values(i).toLong
        i += 1
      }
      checksum
    }

  private def runRiftStructure(kind: Int, n: Int): Long = {
    val region = RiftRegion.open(kind)
    try {
      val values = region.alloc(new Array[Int](cellCount(n)))

      var outer = 0
      while (outer < n) {
        val rowBase = outer * n
        var inner = 0
        while (inner < n) {
          values(rowBase + inner) = (outer + inner) & 0x7fffffff
          inner += 1
        }
        outer += 1
      }

      var checksum = 0L
      var i = 0
      while (i < values.length) {
        checksum += values(i).toLong
        i += 1
      }
      checksum
    } finally region.close()
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

  def printConfig(mode: String): Unit = {
    val cfg = ListOfListsConfig
    val rootsMode = sys.env.getOrElse("SAFEZONE_ROOTS_MODE", "0")
    val pageSize = sys.env.getOrElse("SAFEZONE_PAGE_SIZE", "default")
    println(
      s"CONFIG mode=$mode runs=${cfg.benchmarkRuns} n=${cfg.n} structures=${cfg.structures} layout=flat safezone_roots_mode=$rootsMode safezone_page_size=$pageSize"
    )
  }
}

@main def ListOfListsFlatMatrix(mode: String = "heap"): Unit = {
  ListOfListsFlatMatrixHelpers.printConfig(mode)

  val stats = mode match {
    case "heap" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-flat-heap",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsFlatMatrixHelpers.runHeap())
    case "safezone" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-flat-safezone",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsFlatMatrixHelpers.runSafeZone())
    case "rift-hp" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-flat-rift-hp",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsFlatMatrixHelpers.runRift(RiftRegion.HPZone))
    case other =>
      throw new IllegalArgumentException(
        s"unknown mode '$other'; expected heap, safezone, or rift-hp"
      )
  }

  if (stats.medianMs.isNaN)
    throw new IllegalStateException("benchmark stats were not computed")
}
