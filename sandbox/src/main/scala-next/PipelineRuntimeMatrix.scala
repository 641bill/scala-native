import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.runtime.SafeZoneAllocator.allocate

object PipelineRuntimeMatrixHelpers {
  private val size = PipelineConfig.size
  private val numKeys = PipelineConfig.numKeys

  /** Inlined to keep capture-checking happy when called inside a SafeZone block.
   *  A non-inline helper taking `Array[Int]` parameters cannot accept zone-allocated
   *  `Array[Int]^{sz}` arguments without leaking `any` into the lambda's result type.
   */
  private inline def computePipeline(
      sizeLocal: Int,
      numKeysLocal: Int,
      keys: Array[Int],
      values: Array[Int],
      mappedKeys: Array[Int],
      scores: Array[Long],
      buckets: Array[Long]
  ): Long = {
    var i = 0
    while (i < sizeLocal) {
      var x = i + 1
      x ^= x << 13; x ^= x >>> 17; x ^= x << 5
      keys(i) = math.abs(x) % numKeysLocal
      var y = i * 7 + 3
      y ^= y << 13; y ^= y >>> 17; y ^= y << 5
      values(i) = math.abs(y) % 1000
      i += 1
    }
    i = 0
    while (i < sizeLocal) {
      val key = keys(i)
      mappedKeys(i) = key
      scores(i) = (values(i) * (key + 1)).toLong
      i += 1
    }
    var b = 0
    while (b < numKeysLocal) { buckets(b) = 0L; b += 1 }
    i = 0
    while (i < sizeLocal) {
      buckets(mappedKeys(i)) += scores(i)
      i += 1
    }
    var total = 0L
    var k = 0
    while (k < numKeysLocal) { total += buckets(k); k += 1 }
    total
  }

  private lazy val expectedChecksum: Long = {
    val keys = new Array[Int](size)
    val values = new Array[Int](size)
    val mappedKeys = new Array[Int](size)
    val scores = new Array[Long](size)
    val buckets = new Array[Long](numKeys)
    computePipeline(size, numKeys, keys, values, mappedKeys, scores, buckets)
  }

  def runHeap(): Boolean = {
    val keys = new Array[Int](size)
    val values = new Array[Int](size)
    val mappedKeys = new Array[Int](size)
    val scores = new Array[Long](size)
    val buckets = new Array[Long](numKeys)
    computePipeline(size, numKeys, keys, values, mappedKeys, scores, buckets) == expectedChecksum
  }

  def runSafeZone(): Boolean = {
    val expected = expectedChecksum
    val sizeLocal = size
    val numKeysLocal = numKeys
    val buckets = new Array[Long](numKeysLocal)
    SafeZone { sz ?=>
      val keys = allocate(sz, new Array[Int](sizeLocal))
      val values = allocate(sz, new Array[Int](sizeLocal))
      val mappedKeys = allocate(sz, new Array[Int](sizeLocal))
      val scores = allocate(sz, new Array[Long](sizeLocal))
      computePipeline(sizeLocal, numKeysLocal, keys, values, mappedKeys, scores, buckets) == expected
    }
  }

  def runRift(kind: Int): Boolean = {
    val expected = expectedChecksum
    val sizeLocal = size
    val numKeysLocal = numKeys
    val buckets = new Array[Long](numKeysLocal)
    val region = RiftRegion.open(kind)
    try {
      val keys = region.alloc(new Array[Int](sizeLocal))
      val values = region.alloc(new Array[Int](sizeLocal))
      val mappedKeys = region.alloc(new Array[Int](sizeLocal))
      val scores = region.alloc(new Array[Long](sizeLocal))
      computePipeline(sizeLocal, numKeysLocal, keys, values, mappedKeys, scores, buckets) == expected
    } finally region.close()
  }

  def warmup(mode: String): Unit =
    mode match {
      case "heap" => runHeap()
      case "safezone" => runSafeZone()
      case "rift-hp" => runRift(RiftRegion.HPZone)
      case "rift-streaming" => runRift(RiftRegion.Streaming)
      case other =>
        throw new IllegalArgumentException(
          s"unknown mode '$other'; expected heap, safezone, rift-hp, or rift-streaming"
        )
    }

  def printConfig(mode: String): Unit = {
    val rootsMode = sys.env.getOrElse("SAFEZONE_ROOTS_MODE", "0")
    val pageSize = sys.env.getOrElse("SAFEZONE_PAGE_SIZE", "default")
    println(
      s"CONFIG mode=$mode runs=${PipelineConfig.benchmarkRuns} warmups=${PipelineConfig.warmupRuns} size=${PipelineConfig.size} keys=${PipelineConfig.numKeys} safezone_roots_mode=$rootsMode safezone_page_size=$pageSize"
    )
  }
}

@main def PipelineRuntimeMatrix(mode: String = "heap"): Unit = {
  PipelineRuntimeMatrixHelpers.printConfig(mode)

  var warmup = 0
  while (warmup < PipelineConfig.warmupRuns) {
    PipelineRuntimeMatrixHelpers.warmup(mode)
    warmup += 1
  }

  val stats = mode match {
    case "heap" =>
      BenchmarkRunner.runBenchmark(
        name = "pipeline-heap",
        numRuns = PipelineConfig.benchmarkRuns
      )(PipelineRuntimeMatrixHelpers.runHeap())
    case "safezone" =>
      BenchmarkRunner.runBenchmark(
        name = "pipeline-safezone",
        numRuns = PipelineConfig.benchmarkRuns
      )(PipelineRuntimeMatrixHelpers.runSafeZone())
    case "rift-hp" =>
      BenchmarkRunner.runBenchmark(
        name = "pipeline-rift-hp",
        numRuns = PipelineConfig.benchmarkRuns
      )(PipelineRuntimeMatrixHelpers.runRift(RiftRegion.HPZone))
    case "rift-streaming" =>
      BenchmarkRunner.runBenchmark(
        name = "pipeline-rift-streaming",
        numRuns = PipelineConfig.benchmarkRuns
      )(PipelineRuntimeMatrixHelpers.runRift(RiftRegion.Streaming))
    case other =>
      throw new IllegalArgumentException(
        s"unknown mode '$other'; expected heap, safezone, rift-hp, or rift-streaming"
      )
  }

  if (stats.medianMs.isNaN)
    throw new IllegalStateException("benchmark stats were not computed")
}
