import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.runtime.SafeZoneAllocator.allocate

object ListOfListsChunkedMatrixHelpers {
  @volatile private var checksumSink = 0L

  private final class HeapChunk(
      val values: Array[Int],
      val used: Int,
      val next: HeapChunk
  )
  private final class HeapOuterNode(
      val inner: HeapChunk,
      val next: HeapOuterNode
  )

  private def expectedChecksum(n: Int, structures: Int): Long =
    structures.toLong * n.toLong * n.toLong * (n.toLong - 1L)

  private def runHeapStructure(n: Int, chunkSize: Int): Long = {
    def buildOneStructure(): HeapOuterNode = {
      var outerHead: HeapOuterNode = null
      var outer = 0
      while (outer < n) {
        var innerHead: HeapChunk = null
        var inner = 0
        while (inner < n) {
          val used = math.min(chunkSize, n - inner)
          val values = new Array[Int](used)
          var i = 0
          while (i < used) {
            values(i) = (outer + inner + i) & 0x7fffffff
            i += 1
          }
          innerHead = new HeapChunk(values, used, innerHead)
          inner += used
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
      var chunkCursor = outerCursor.inner
      while (chunkCursor != null) {
        var i = 0
        while (i < chunkCursor.used) {
          checksum += chunkCursor.values(i).toLong
          i += 1
        }
        chunkCursor = chunkCursor.next
      }
      outerCursor = outerCursor.next
    }
    root = null
    checksum
  }

  private def runSafeZoneStructure(n: Int, chunkSize: Int): Long =
    SafeZone { sz ?=>
      final class Chunk(
          val values: Array[Int]^{sz},
          val used: Int,
          val next: Chunk^{sz}
      )
      final class OuterNode(val inner: Chunk^{sz}, val next: OuterNode^{sz})

      def buildOneStructure(): OuterNode^{sz} = {
        var outerHead: OuterNode^{sz} = null
        var outer = 0
        while (outer < n) {
          var innerHead: Chunk^{sz} = null
          var inner = 0
          while (inner < n) {
            val used = math.min(chunkSize, n - inner)
            val values = allocate(sz, new Array[Int](used))
            var i = 0
            while (i < used) {
              values(i) = (outer + inner + i) & 0x7fffffff
              i += 1
            }
            innerHead = allocate(sz, new Chunk(values, used, innerHead))
            inner += used
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
        var chunkCursor = outerCursor.inner
        while (chunkCursor != null) {
          var i = 0
          while (i < chunkCursor.used) {
            checksum += chunkCursor.values(i).toLong
            i += 1
          }
          chunkCursor = chunkCursor.next
        }
        outerCursor = outerCursor.next
      }
      root = null
      checksum
    }

  private def runRiftStructure(kind: Int, n: Int, chunkSize: Int): Long = {
    final class Chunk(val values: Array[Int], val used: Int, val next: Chunk)
    final class OuterNode(val inner: Chunk, val next: OuterNode)

    val region = RiftRegion.open(kind)
    try {
      def buildOneStructure(): OuterNode = {
        var outerHead: OuterNode = null
        var outer = 0
        while (outer < n) {
          var innerHead: Chunk = null
          var inner = 0
          while (inner < n) {
            val used = math.min(chunkSize, n - inner)
            val values = region.alloc(new Array[Int](used))
            var i = 0
            while (i < used) {
              values(i) = (outer + inner + i) & 0x7fffffff
              i += 1
            }
            innerHead = region.alloc(new Chunk(values, used, innerHead))
            inner += used
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
        var chunkCursor = outerCursor.inner
        while (chunkCursor != null) {
          var i = 0
          while (i < chunkCursor.used) {
            checksum += chunkCursor.values(i).toLong
            i += 1
          }
          chunkCursor = chunkCursor.next
        }
        outerCursor = outerCursor.next
      }
      root = null
      checksum
    } finally region.close()
  }

  def runHeap(): Boolean = {
    val cfg = ListOfListsConfig
    var checksum = 0L
    var structures = 0
    while (structures < cfg.structures) {
      checksum += runHeapStructure(cfg.n, cfg.chunkSize)
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
      checksum += runSafeZoneStructure(cfg.n, cfg.chunkSize)
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
      checksum += runRiftStructure(kind, cfg.n, cfg.chunkSize)
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
      s"CONFIG mode=$mode runs=${cfg.benchmarkRuns} n=${cfg.n} structures=${cfg.structures} chunk_size=${cfg.chunkSize} safezone_roots_mode=$rootsMode safezone_page_size=$pageSize"
    )
  }
}

@main def ListOfListsChunkedMatrix(mode: String = "heap"): Unit = {
  ListOfListsChunkedMatrixHelpers.printConfig(mode)

  val stats = mode match {
    case "heap" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-chunked-heap",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsChunkedMatrixHelpers.runHeap())
    case "safezone" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-chunked-safezone",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsChunkedMatrixHelpers.runSafeZone())
    case "rift-hp" =>
      BenchmarkRunner.runBenchmark(
        name = "listoflists-chunked-rift-hp",
        numRuns = ListOfListsConfig.benchmarkRuns
      )(ListOfListsChunkedMatrixHelpers.runRift(RiftRegion.HPZone))
    case other =>
      throw new IllegalArgumentException(
        s"unknown mode '$other'; expected heap, safezone, or rift-hp"
      )
  }

  if (stats.medianMs.isNaN)
    throw new IllegalStateException("benchmark stats were not computed")
}
