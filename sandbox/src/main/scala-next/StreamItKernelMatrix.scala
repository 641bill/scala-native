import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object StreamItKernelConfig {
  private def parsePositiveInt(value: String): Option[Int] =
    try {
      val parsed = value.toInt
      if (parsed > 0) Some(parsed) else None
    } catch {
      case _: NumberFormatException => None
    }

  private def parsePositiveLong(value: String): Option[Long] =
    try {
      val parsed = value.toLong
      if (parsed > 0L) Some(parsed) else None
    } catch {
      case _: NumberFormatException => None
    }

  private def parseNonNegativeInt(value: String): Option[Int] =
    try {
      val parsed = value.toInt
      if (parsed >= 0) Some(parsed) else None
    } catch {
      case _: NumberFormatException => None
    }

  private def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(parsePositiveInt).getOrElse(default)

  private def envLong(name: String, default: Long): Long =
    sys.env.get(name).flatMap(parsePositiveLong).getOrElse(default)

  private def envNonNegativeInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(parseNonNegativeInt).getOrElse(default)

  private def truthy(value: String): Boolean =
    value == "1" || value.equalsIgnoreCase("true") ||
      value.equalsIgnoreCase("yes")

  val benchmark: String =
    sys.env.getOrElse("STREAMIT_KERNEL_BENCHMARK", "both").toLowerCase
  val workload: String =
    sys.env.getOrElse("STREAMIT_KERNEL_WORKLOAD", "all").toLowerCase
  val filterbankIterations: Int =
    envInt("STREAMIT_FILTERBANK_ITERATIONS", 64)
  val beamformerFrames: Int =
    envInt("STREAMIT_BEAMFORMER_FRAMES", 16)
  val latencyIterations: Int =
    envInt("STREAMIT_LATENCY_ITERATIONS", 128)
  val periodNs: Long =
    envLong("STREAMIT_PERIOD_NS", 1000000L)
  val benchmarkRuns: Int =
    envInt("STREAMIT_BENCHMARK_RUNS", 3)
  val warmupRuns: Int =
    envNonNegativeInt("STREAMIT_WARMUPS", 1)
  val finalClean: Boolean =
    sys.env.get("RIFT_FINAL_CLEAN").exists(truthy) ||
      sys.env.get("RIFT_EVAL_MEASUREMENT_LEVEL").exists(_.equalsIgnoreCase("L1"))
}

object StreamItKernelMatrixHelpers {
  @volatile private var checksumSink = 0L

  final case class RuntimeSample(
      gcNanos: Long,
      gcCollections: Long,
      riftOpNanos: Long,
      riftAllocObjectTotal: Long,
      riftOpenTotal: Long,
      riftCloseTotal: Long,
      riftResetTotal: Long
  )

  object RuntimeSample {
    private def rawSizeToLong(value: RawSize): Long =
      fromRawUSize(value).toLong

    private def nonNegative(value: Long): Long =
      if (value < 0L) 0L else value

    private def delta(end: Long, start: Long): Long =
      if (end >= 0L && start >= 0L && end >= start) end - start else 0L

    def capture(usesRift: Boolean): RuntimeSample = {
      val gcCollections = nonNegative(GC.getStatsCollectionTotal().toLong)
      val gcNanos = nonNegative(GC.getStatsCollectionDurationTotal().toLong)
      if (usesRift) {
        RuntimeSample(
          gcNanos = gcNanos,
          gcCollections = gcCollections,
          riftOpNanos =
            rawSizeToLong(RiftAllocator.Impl.statsRegionOpNanos()),
          riftAllocObjectTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal()),
          riftOpenTotal =
            rawSizeToLong(RiftAllocator.Impl.statsRegionOpenTotal()),
          riftCloseTotal =
            rawSizeToLong(RiftAllocator.Impl.statsRegionCloseTotal()),
          riftResetTotal =
            rawSizeToLong(RiftAllocator.Impl.statsRegionResetTotal())
        )
      } else {
        RuntimeSample(
          gcNanos = gcNanos,
          gcCollections = gcCollections,
          riftOpNanos = 0L,
          riftAllocObjectTotal = 0L,
          riftOpenTotal = 0L,
          riftCloseTotal = 0L,
          riftResetTotal = 0L
        )
      }
    }

    def since(start: RuntimeSample, end: RuntimeSample): RuntimeSample =
      RuntimeSample(
        gcNanos = delta(end.gcNanos, start.gcNanos),
        gcCollections = delta(end.gcCollections, start.gcCollections),
        riftOpNanos = delta(end.riftOpNanos, start.riftOpNanos),
        riftAllocObjectTotal =
          delta(end.riftAllocObjectTotal, start.riftAllocObjectTotal),
        riftOpenTotal = delta(end.riftOpenTotal, start.riftOpenTotal),
        riftCloseTotal = delta(end.riftCloseTotal, start.riftCloseTotal),
        riftResetTotal = delta(end.riftResetTotal, start.riftResetTotal)
      )
  }

  final case class KernelResult(
      checksum: Long,
      outputCount: Long
  )

  final case class LatencyResult(
      checksum: Long,
      outputCount: Long,
      p50Ns: Long,
      p95Ns: Long,
      p99Ns: Long,
      p999Ns: Long,
      maxNs: Long,
      deadlineMisses: Int
  )

  private def mix(checksum: Long, value: Long): Long = {
    var x = checksum ^ (value + 0x9e3779b97f4a7c15L + (checksum << 6) + (checksum >>> 2))
    x ^= x >>> 33
    x *= 0xff51afd7ed558ccdL
    x ^= x >>> 33
    x *= 0xc4ceb9fe1a85ec53L
    x ^ (x >>> 33)
  }

  private def bits(value: Double): Long =
    java.lang.Float.floatToIntBits(value.toFloat).toLong & 0xffffffffL

  private def medianDouble(values: Array[Double]): Double = {
    val copy = values.clone()
    scala.util.Sorting.quickSort(copy)
    val mid = copy.length / 2
    if ((copy.length & 1) == 1) copy(mid)
    else (copy(mid - 1) + copy(mid)) / 2.0
  }

  private def medianLong(values: Array[Long]): Long = {
    val copy = values.clone()
    scala.util.Sorting.quickSort(copy)
    copy(copy.length / 2)
  }

  private def medianInt(values: Array[Int]): Int = {
    val copy = values.clone()
    scala.util.Sorting.quickSort(copy)
    copy(copy.length / 2)
  }

  private def percentile(sorted: Array[Long], pct: Double): Long = {
    if (sorted.isEmpty) 0L
    else {
      val idx = math.ceil((pct / 100.0) * sorted.length.toDouble).toInt - 1
      sorted(math.max(0, math.min(sorted.length - 1, idx)))
    }
  }

  private final class FilterBankKernel {
    private val nSim = 2048
    private val nSamp = 8
    private val nCh = 8
    private val nCol = 32
    private val r = new Array[Float](nSim)
    private val y = new Array[Float](nSim)
    private val h = Array.ofDim[Float](nCh, nCol)
    private val f = Array.ofDim[Float](nCh, nCol)
    private val vectH = new Array[Float](nSim)
    private val vectDn = new Array[Float](nSim / nSamp)
    private val vectUp = new Array[Float](nSim)
    private val vectF = new Array[Float](nSim)

    private var i = 0
    while (i < nSim) {
      r(i) = (i + 1).toFloat
      i += 1
    }

    i = 0
    while (i < nCol) {
      var j = 0
      while (j < nCh) {
        h(j)(i) = (i * nCol + j * nCh + j + i + j + 1).toFloat
        f(j)(i) = (i * j + j * j + j + i).toFloat
        j += 1
      }
      i += 1
    }

    def runOnce(): KernelResult = {
      java.util.Arrays.fill(y, 0.0f)
      var ch = 0
      while (ch < nCh) {
        var j = 0
        while (j < nSim) {
          var sum = 0.0f
          var k = 0
          while (k < nCol && j - k >= 0) {
            sum = (sum + h(ch)(k) * r(j - k)).toFloat
            k += 1
          }
          vectH(j) = sum
          j += 1
        }

        j = 0
        while (j < nSim / nSamp) {
          vectDn(j) = vectH(j * nSamp)
          j += 1
        }

        java.util.Arrays.fill(vectUp, 0.0f)
        j = 0
        while (j < nSim / nSamp) {
          vectUp(j * nSamp) = vectDn(j)
          j += 1
        }

        j = 0
        while (j < nSim) {
          var sum = 0.0f
          var k = 0
          while (k < nCol && j - k >= 0) {
            sum = (sum + f(ch)(k) * vectUp(j - k)).toFloat
            k += 1
          }
          vectF(j) = sum
          j += 1
        }

        j = 0
        while (j < nSim) {
          y(j) = (y(j) + vectF(j)).toFloat
          j += 1
        }
        ch += 1
      }

      var checksum = 0L
      var outputs = 0L
      i = 0
      while (i < nSim) {
        checksum = mix(checksum, bits(y(i)))
        outputs += 1L
        i += 1
      }
      KernelResult(checksum, outputs)
    }
  }

  private final class BeamFirFilterState(
      numTaps: Int,
      inputLength: Int,
      decimationRatio: Int
  ) {
    private val realWeight = new Array[Float](numTaps)
    private val imagWeight = new Array[Float](numTaps)
    private val realBuffer = new Array[Float](numTaps)
    private val imagBuffer = new Array[Float](numTaps)
    private val mask = numTaps - 1
    private var pos = 0
    private var count = 0

    private var i = 0
    while (i < numTaps) {
      val idx = i + 1
      realWeight(i) = (math.sin(idx.toDouble) / idx.toDouble).toFloat
      imagWeight(i) = (math.cos(idx.toDouble) / idx.toDouble).toFloat
      i += 1
    }

    def step(realIn: Float, imagIn: Float): (Float, Float) = {
      val write = mask - pos
      realBuffer(write) = realIn
      imagBuffer(write) = imagIn
      var realCurr = 0.0f
      var imagCurr = 0.0f
      var modPos = write
      var i = 0
      while (i < numTaps) {
        val rb = realBuffer(modPos)
        val ib = imagBuffer(modPos)
        realCurr = (realCurr + rb * realWeight(i) + ib * imagWeight(i)).toFloat
        imagCurr = (imagCurr + ib * realWeight(i) + rb * imagWeight(i)).toFloat
        modPos = (modPos + 1) & mask
        i += 1
      }
      pos = (pos + 1) & mask
      count += decimationRatio
      if (count == inputLength) {
        count = 0
        pos = 0
        java.util.Arrays.fill(realBuffer, 0.0f)
        java.util.Arrays.fill(imagBuffer, 0.0f)
      }
      (realCurr, imagCurr)
    }
  }

  private final class BeamFormerKernel {
    private val numChannels = 12
    private val numSamples = 1024
    private val numBeams = 4
    private val numCoarseFilterTaps = 64
    private val numFineFilterTaps = 64
    private val coarseDecimationRatio = 1
    private val fineDecimationRatio = 2
    private val numPostDec1 = numSamples / coarseDecimationRatio
    private val numPostDec2 = numPostDec1 / fineDecimationRatio
    private val mfSize = numPostDec2
    private val pulseSize = numPostDec2 / 2
    private val targetBeam = numBeams / 4
    private val targetSample = numSamples / 4
    private val dOverLambda = 0.5f
    private val cfarThreshold =
      (0.95f * dOverLambda * numChannels.toFloat * (0.5f * pulseSize.toFloat)).toFloat

    private val coarse = Array.tabulate(numChannels) { _ =>
      new BeamFirFilterState(numCoarseFilterTaps, numSamples, coarseDecimationRatio)
    }
    private val fine = Array.tabulate(numChannels) { _ =>
      new BeamFirFilterState(numFineFilterTaps, numPostDec1, fineDecimationRatio)
    }
    private val matched = Array.tabulate(numBeams) { _ =>
      new BeamFirFilterState(mfSize, numPostDec2, 1)
    }
    private val beamReal = Array.ofDim[Float](numBeams, numChannels)
    private val beamImag = Array.ofDim[Float](numBeams, numChannels)

    private var b = 0
    while (b < numBeams) {
      var ch = 0
      while (ch < numChannels) {
        val idx = ch + 1
        val scale = (b + idx).toDouble
        beamReal(b)(ch) = (math.sin(idx.toDouble) / scale).toFloat
        beamImag(b)(ch) = (math.cos(idx.toDouble) / scale).toFloat
        ch += 1
      }
      b += 1
    }

    private val channelReal = new Array[Float](numChannels)
    private val channelImag = new Array[Float](numChannels)

    private def input(channel: Int, sample: Int): (Float, Float) =
      if (targetBeam == channel && targetSample == sample)
        (math.sqrt(cfarThreshold.toDouble).toFloat, 0.0f)
      else (0.0f, 0.0f)

    def runFrame(): KernelResult = {
      var checksum = 0L
      var outputs = 0L
      var sample = 0
      while (sample < numSamples) {
        var channel = 0
        while (channel < numChannels) {
          val in = input(channel, sample)
          val coarseOut = coarse(channel).step(in._1, in._2)
          if ((sample & 1) == 0) {
            val fineOut = fine(channel).step(coarseOut._1, coarseOut._2)
            channelReal(channel) = fineOut._1
            channelImag(channel) = fineOut._2
          }
          channel += 1
        }

        if ((sample & 1) == 0) {
          var beam = 0
          while (beam < numBeams) {
            var realCurr = 0.0f
            var imagCurr = 0.0f
            channel = 0
            while (channel < numChannels) {
              val realPop = channelReal(channel)
              val imagPop = channelImag(channel)
              realCurr =
                (realCurr + beamReal(beam)(channel) * realPop -
                  beamImag(beam)(channel) * imagPop).toFloat
              imagCurr =
                (imagCurr + beamReal(beam)(channel) * imagPop +
                  beamImag(beam)(channel) * realPop).toFloat
              channel += 1
            }
            val matchedOut = matched(beam).step(realCurr, imagCurr)
            val magnitude =
              math.sqrt(
                matchedOut._1.toDouble * matchedOut._1.toDouble +
                  matchedOut._2.toDouble * matchedOut._2.toDouble
              ).toFloat
            checksum = mix(checksum, bits(magnitude) ^ beam.toLong)
            outputs += 1L
            beam += 1
          }
        }
        sample += 1
      }
      KernelResult(checksum, outputs)
    }
  }

  private def runFilterBankIterations(iterations: Int): KernelResult = {
    val kernel = new FilterBankKernel
    var checksum = 0L
    var outputs = 0L
    var i = 0
    while (i < iterations) {
      val result = kernel.runOnce()
      checksum = mix(checksum, result.checksum ^ i.toLong)
      outputs += result.outputCount
      i += 1
    }
    KernelResult(checksum, outputs)
  }

  private def runBeamFormerFrames(frames: Int): KernelResult = {
    val kernel = new BeamFormerKernel
    var checksum = 0L
    var outputs = 0L
    var i = 0
    while (i < frames) {
      val result = kernel.runFrame()
      checksum = mix(checksum, result.checksum ^ i.toLong)
      outputs += result.outputCount
      i += 1
    }
    KernelResult(checksum, outputs)
  }

  private def runPrimitiveKernel(benchmark: String, units: Int): KernelResult =
    benchmark match {
      case "filterbank" => runFilterBankIterations(units)
      case "beamformer" => runBeamFormerFrames(units)
      case other =>
        throw new IllegalArgumentException(
          s"unknown StreamIt benchmark '$other'; expected filterbank or beamformer"
        )
    }

  private def runMode(mode: String, benchmark: String, units: Int): KernelResult =
    mode match {
      case "heap" =>
        runPrimitiveKernel(benchmark, units)
      case "checked-epoch-scoped" =>
        RiftRegion.streamingSafeZone { stream ?=>
          RiftRegion.epoch {
            runPrimitiveKernel(benchmark, units)
          }
        }
      case "checked-epoch-stream" =>
        RiftRegion.streaming { stream ?=>
          RiftRegion.epoch {
            runPrimitiveKernel(benchmark, units)
          }
        }
      case other =>
        throw new IllegalArgumentException(
          s"unknown StreamIt mode '$other'; expected heap, checked-epoch-scoped, or checked-epoch-stream"
        )
    }

  private def unitsForThroughput(benchmark: String): Int =
    benchmark match {
      case "filterbank" => StreamItKernelConfig.filterbankIterations
      case "beamformer" => StreamItKernelConfig.beamformerFrames
      case other =>
        throw new IllegalArgumentException(s"unknown StreamIt benchmark '$other'")
    }

  def runThroughputBenchmark(mode: String, benchmark: String): Unit = {
    val cfg = StreamItKernelConfig
    val units = unitsForThroughput(benchmark)
    val usesRift = mode.startsWith("checked-")
    if (cfg.finalClean) {
      var run = 0
      var result = runMode(mode, benchmark, units)
      val checksum = result.checksum
      val outputs = result.outputCount
      run = 1
      while (run < cfg.benchmarkRuns) {
        result = runMode(mode, benchmark, units)
        if (result.checksum != checksum || result.outputCount != outputs)
          throw new IllegalStateException(
            s"final-clean StreamIt throughput mismatch benchmark=$benchmark mode=$mode"
          )
        run += 1
      }
      println(
        s"RESULT name=streamit-${benchmark}-throughput-${mode} " +
          s"measurement_level=L1 final_clean=1 benchmark=$benchmark workload=throughput " +
          s"mode=$mode runs=${cfg.benchmarkRuns} units=$units " +
          s"checksum=$checksum output_count=$outputs"
      )
      return
    }

    val expected = runMode("heap", benchmark, units)
    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val result = runMode(mode, benchmark, units)
      if (result != expected)
        throw new IllegalStateException(
          s"StreamIt throughput warmup mismatch benchmark=$benchmark mode=$mode"
        )
      warmup += 1
    }

    if (usesRift) RiftAllocator.Impl.statsReset()

    val elapsedMs = new Array[Double](cfg.benchmarkRuns)
    val gcNanos = new Array[Long](cfg.benchmarkRuns)
    val gcCollections = new Array[Long](cfg.benchmarkRuns)
    val riftOpNanos = new Array[Long](cfg.benchmarkRuns)
    val riftObjects = new Array[Long](cfg.benchmarkRuns)
    val riftOpens = new Array[Long](cfg.benchmarkRuns)
    val riftCloses = new Array[Long](cfg.benchmarkRuns)
    val riftResets = new Array[Long](cfg.benchmarkRuns)

    println(s"Running streamit-$benchmark-throughput-$mode for ${cfg.benchmarkRuns} timed runs")
    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val result = runMode(mode, benchmark, units)
      val end = System.nanoTime()
      val endRuntime = RuntimeSample.capture(usesRift)
      val runtime = RuntimeSample.since(startRuntime, endRuntime)
      if (result != expected)
        throw new IllegalStateException(
          s"StreamIt throughput checksum mismatch benchmark=$benchmark mode=$mode expected=$expected actual=$result"
        )

      elapsedMs(run) = (end - start) / 1000000.0
      gcNanos(run) = runtime.gcNanos
      gcCollections(run) = runtime.gcCollections
      riftOpNanos(run) = runtime.riftOpNanos
      riftObjects(run) = runtime.riftAllocObjectTotal
      riftOpens(run) = runtime.riftOpenTotal
      riftCloses(run) = runtime.riftCloseTotal
      riftResets(run) = runtime.riftResetTotal
      println(
        f"  run=${run + 1}%d elapsed_ms=${elapsedMs(run)}%.3f " +
          f"gc_collections=${runtime.gcCollections}%d " +
          f"gc_ms=${runtime.gcNanos / 1000000.0}%.3f"
      )
      run += 1
    }

    println(
      f"RESULT name=streamit-${benchmark}-throughput-${mode} " +
        f"benchmark=$benchmark workload=throughput mode=$mode units=$units " +
        f"median_ms=${medianDouble(elapsedMs)}%.3f " +
        f"median_gc_ms=${medianLong(gcNanos) / 1000000.0}%.3f " +
        f"max_gc_ms=${gcNanos.max / 1000000.0}%.3f " +
        f"runs_with_gc=${gcNanos.count(_ > 0L)}%d " +
        f"max_gc_collections=${gcCollections.max}%d " +
        f"median_rift_op_ms=${medianLong(riftOpNanos) / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=${medianLong(riftObjects)}%d " +
        f"median_rift_open_total=${medianLong(riftOpens)}%d " +
        f"median_rift_close_total=${medianLong(riftCloses)}%d " +
        f"median_rift_reset_total=${medianLong(riftResets)}%d " +
        f"checksum=${expected.checksum}%d output_count=${expected.outputCount}%d"
    )
  }

  private def runLatencySamples(
      mode: String,
      benchmark: String
  ): LatencyResult = {
    val cfg = StreamItKernelConfig
    val samples = new Array[Long](cfg.latencyIterations)
    var checksum = 0L
    var outputs = 0L
    var i = 0
    while (i < cfg.latencyIterations) {
      val start = System.nanoTime()
      val result = runMode(mode, benchmark, 1)
      val end = System.nanoTime()
      samples(i) = end - start
      checksum = mix(checksum, result.checksum ^ i.toLong)
      outputs += result.outputCount
      i += 1
    }
    val sorted = samples.clone()
    scala.util.Sorting.quickSort(sorted)
    var misses = 0
    i = 0
    while (i < samples.length) {
      if (samples(i) > cfg.periodNs) misses += 1
      i += 1
    }
    LatencyResult(
      checksum = checksum,
      outputCount = outputs,
      p50Ns = percentile(sorted, 50.0),
      p95Ns = percentile(sorted, 95.0),
      p99Ns = percentile(sorted, 99.0),
      p999Ns = percentile(sorted, 99.9),
      maxNs = sorted(sorted.length - 1),
      deadlineMisses = misses
    )
  }

  def runLatencyBenchmark(mode: String, benchmark: String): Unit = {
    val cfg = StreamItKernelConfig
    val usesRift = mode.startsWith("checked-")
    if (cfg.finalClean) {
      var run = 0
      var result = runLatencySamples(mode, benchmark)
      val checksum = result.checksum
      val outputs = result.outputCount
      run = 1
      while (run < cfg.benchmarkRuns) {
        result = runLatencySamples(mode, benchmark)
        if (result.checksum != checksum || result.outputCount != outputs)
          throw new IllegalStateException(
            s"final-clean StreamIt latency mismatch benchmark=$benchmark mode=$mode"
          )
        run += 1
      }
      println(
        s"RESULT name=streamit-${benchmark}-latency-${mode} " +
          s"measurement_level=L1 final_clean=1 benchmark=$benchmark workload=latency " +
          s"mode=$mode runs=${cfg.benchmarkRuns} latency_iterations=${cfg.latencyIterations} " +
          s"period_ns=${cfg.periodNs} p50_ns=${result.p50Ns} p95_ns=${result.p95Ns} " +
          s"p99_ns=${result.p99Ns} p999_ns=${result.p999Ns} " +
          s"max_ns=${result.maxNs} deadline_misses=${result.deadlineMisses} " +
          s"checksum=$checksum output_count=$outputs"
      )
      return
    }

    val expected = runLatencySamples("heap", benchmark)
    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val result = runLatencySamples(mode, benchmark)
      if (result.checksum != expected.checksum ||
          result.outputCount != expected.outputCount)
        throw new IllegalStateException(
          s"StreamIt latency warmup mismatch benchmark=$benchmark mode=$mode"
        )
      warmup += 1
    }

    if (usesRift) RiftAllocator.Impl.statsReset()

    val elapsedMs = new Array[Double](cfg.benchmarkRuns)
    val gcNanos = new Array[Long](cfg.benchmarkRuns)
    val gcCollections = new Array[Long](cfg.benchmarkRuns)
    val riftOpNanos = new Array[Long](cfg.benchmarkRuns)
    val riftObjects = new Array[Long](cfg.benchmarkRuns)
    val p50 = new Array[Long](cfg.benchmarkRuns)
    val p95 = new Array[Long](cfg.benchmarkRuns)
    val p99 = new Array[Long](cfg.benchmarkRuns)
    val p999 = new Array[Long](cfg.benchmarkRuns)
    val max = new Array[Long](cfg.benchmarkRuns)
    val misses = new Array[Int](cfg.benchmarkRuns)

    println(s"Running streamit-$benchmark-latency-$mode for ${cfg.benchmarkRuns} timed runs")
    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val result = runLatencySamples(mode, benchmark)
      val end = System.nanoTime()
      val endRuntime = RuntimeSample.capture(usesRift)
      val runtime = RuntimeSample.since(startRuntime, endRuntime)
      if (result.checksum != expected.checksum ||
          result.outputCount != expected.outputCount)
        throw new IllegalStateException(
          s"StreamIt latency checksum mismatch benchmark=$benchmark mode=$mode"
        )

      elapsedMs(run) = (end - start) / 1000000.0
      gcNanos(run) = runtime.gcNanos
      gcCollections(run) = runtime.gcCollections
      riftOpNanos(run) = runtime.riftOpNanos
      riftObjects(run) = runtime.riftAllocObjectTotal
      p50(run) = result.p50Ns
      p95(run) = result.p95Ns
      p99(run) = result.p99Ns
      p999(run) = result.p999Ns
      max(run) = result.maxNs
      misses(run) = result.deadlineMisses
      println(
        f"  run=${run + 1}%d elapsed_ms=${elapsedMs(run)}%.3f " +
          f"gc_collections=${runtime.gcCollections}%d " +
          f"gc_ms=${runtime.gcNanos / 1000000.0}%.3f " +
          f"p95_us=${result.p95Ns / 1000.0}%.3f " +
          f"p99_us=${result.p99Ns / 1000.0}%.3f " +
          f"max_us=${result.maxNs / 1000.0}%.3f " +
          f"deadline_misses=${result.deadlineMisses}%d"
      )
      run += 1
    }

    println(
      f"RESULT name=streamit-${benchmark}-latency-${mode} " +
        f"benchmark=$benchmark workload=latency mode=$mode " +
        f"latency_iterations=${cfg.latencyIterations}%d " +
        f"median_ms=${medianDouble(elapsedMs)}%.3f " +
        f"median_gc_ms=${medianLong(gcNanos) / 1000000.0}%.3f " +
        f"max_gc_ms=${gcNanos.max / 1000000.0}%.3f " +
        f"runs_with_gc=${gcNanos.count(_ > 0L)}%d " +
        f"max_gc_collections=${gcCollections.max}%d " +
        f"median_rift_op_ms=${medianLong(riftOpNanos) / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=${medianLong(riftObjects)}%d " +
        f"median_p50_ns=${medianLong(p50)}%d " +
        f"median_p95_ns=${medianLong(p95)}%d " +
        f"median_p99_ns=${medianLong(p99)}%d " +
        f"median_p999_ns=${medianLong(p999)}%d " +
        f"median_max_ns=${medianLong(max)}%d " +
        f"median_deadline_misses=${medianInt(misses)}%d " +
        f"period_ns=${cfg.periodNs}%d " +
        f"checksum=${expected.checksum}%d output_count=${expected.outputCount}%d"
    )
  }

  def validateMode(mode: String): Unit =
    mode match {
      case "heap" | "checked-epoch-scoped" | "checked-epoch-stream" => ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown StreamIt mode '$other'; expected heap, checked-epoch-scoped, or checked-epoch-stream"
        )
    }

  def validateBenchmark(benchmark: String): Unit =
    benchmark match {
      case "filterbank" | "beamformer" => ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown StreamIt benchmark '$other'; expected filterbank or beamformer"
        )
    }

  def validateWorkload(workload: String): Unit =
    workload match {
      case "throughput" | "latency" | "all" => ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown StreamIt workload '$other'; expected throughput, latency, or all"
        )
    }

  def printConfig(mode: String, benchmark: String, workload: String): Unit = {
    val cfg = StreamItKernelConfig
    println(
      s"CONFIG mode=$mode benchmark=$benchmark workload=$workload " +
        s"filterbank_iterations=${cfg.filterbankIterations} " +
        s"beamformer_frames=${cfg.beamformerFrames} " +
        s"latency_iterations=${cfg.latencyIterations} " +
        s"period_ns=${cfg.periodNs} runs=${cfg.benchmarkRuns} " +
        s"warmups=${cfg.warmupRuns} final_clean=${cfg.finalClean}"
    )
  }
}

@main def StreamItKernelMatrix(
    mode: String,
    benchmarkArg: String = StreamItKernelConfig.benchmark,
    workloadArg: String = StreamItKernelConfig.workload
): Unit = {
  val benchmark = benchmarkArg.toLowerCase
  val workload = workloadArg.toLowerCase
  StreamItKernelMatrixHelpers.validateMode(mode)
  StreamItKernelMatrixHelpers.validateWorkload(workload)
  val benchmarks =
    if (benchmark == "both") Array("filterbank", "beamformer")
    else {
      StreamItKernelMatrixHelpers.validateBenchmark(benchmark)
      Array(benchmark)
    }
  var i = 0
  while (i < benchmarks.length) {
    val bench = benchmarks(i)
    StreamItKernelMatrixHelpers.printConfig(mode, bench, workload)
    workload match {
      case "all" =>
        StreamItKernelMatrixHelpers.runThroughputBenchmark(mode, bench)
        StreamItKernelMatrixHelpers.runLatencyBenchmark(mode, bench)
      case "throughput" =>
        StreamItKernelMatrixHelpers.runThroughputBenchmark(mode, bench)
      case "latency" =>
        StreamItKernelMatrixHelpers.runLatencyBenchmark(mode, bench)
    }
    i += 1
  }
}
