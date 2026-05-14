import scala.language.experimental.captureChecking

import scala.scalanative.memory.{
  RiftNoZeroOpenStreamingHandle,
  RiftOpenStreamingHandle,
  RiftRegion
}
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object ObjectAllocationLoweringConfig {
  private def parsePositiveInt(value: String): Option[Int] =
    try {
      val parsed = value.toInt
      if (parsed > 0) Some(parsed) else None
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

  private def envNonNegativeInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(parseNonNegativeInt).getOrElse(default)

  val objects: Int = envInt("OBJECT_ALLOC_OBJECTS", 1000000)
  val dirtyPrepObjects: Int =
    envNonNegativeInt("OBJECT_ALLOC_DIRTY_PREP_OBJECTS", objects)
  val recordShape: String =
    sys.env.getOrElse("OBJECT_ALLOC_RECORD_SHAPE", "primitive").toLowerCase
  val sampleEvery: Int = envInt("OBJECT_ALLOC_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("OBJECT_ALLOC_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("OBJECT_ALLOC_BENCHMARK_RUNS", 3)
}

object ObjectAllocationLoweringMatrixHelpers {
  @volatile private var checksumSink = 0L

  private final class HeapRecord(
      val a: Int,
      val b: Int,
      val c: Long,
      var d: Int
  )

  private final class TrustedRecord(
      val a: Int,
      val b: Int,
      val c: Long,
      var d: Int
  )

  private final class DirtyPrepRecord(
      val a: Int,
      val b: Int,
      val c: Long,
      var d: Int
  )

  private final class HeapRefMetadata(val salt: Int)

  private final class HeapRefRecord(
      val a: Int,
      val prev: HeapRefRecord,
      val metadata: HeapRefMetadata,
      var d: Int
  )

  private final class TrustedRefMetadata(val salt: Int)

  private final class TrustedRefRecord(
      val a: Int,
      val prev: TrustedRefRecord,
      val metadata: TrustedRefMetadata,
      var d: Int
  )

  final case class RuntimeSample(
      gcCollections: Long,
      gcNanos: Long,
      riftRegionOpenTotal: Long,
      riftRegionCloseTotal: Long,
      riftRegionResetTotal: Long,
      riftAllocObjectTotal: Long,
      riftZeroObjectTotal: Long,
      riftZeroObjectBytesTotal: Long,
      riftZeroSkippedTotal: Long,
      riftZeroSkippedBytesTotal: Long,
      riftRegionOpNanos: Long,
      riftSlowAllocNanos: Long
  )

  private object RuntimeSample {
    val zero: RuntimeSample =
      RuntimeSample(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)

    private def rawSizeToLong(value: RawSize): Long =
      fromRawUSize(value).toLong

    private def nonNegative(value: Long): Long =
      if (value < 0L) 0L else value

    private def delta(end: Long, start: Long): Long =
      if (end >= 0L && start >= 0L && end >= start) end - start else 0L

    def capture(includeRift: Boolean): RuntimeSample = {
      val gcCollections = nonNegative(GC.getStatsCollectionTotal().toLong)
      val gcNanos = nonNegative(GC.getStatsCollectionDurationTotal().toLong)

      if (!includeRift) zero.copy(gcCollections = gcCollections, gcNanos = gcNanos)
      else
        RuntimeSample(
          gcCollections = gcCollections,
          gcNanos = gcNanos,
          riftRegionOpenTotal =
            rawSizeToLong(RiftAllocator.Impl.statsRegionOpenTotal()),
          riftRegionCloseTotal =
            rawSizeToLong(RiftAllocator.Impl.statsRegionCloseTotal()),
          riftRegionResetTotal =
            rawSizeToLong(RiftAllocator.Impl.statsRegionResetTotal()),
          riftAllocObjectTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal()),
          riftZeroObjectTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocZeroObjectTotal()),
          riftZeroObjectBytesTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocZeroObjectBytesTotal()),
          riftZeroSkippedTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocZeroSkippedTotal()),
          riftZeroSkippedBytesTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocZeroSkippedBytesTotal()),
          riftRegionOpNanos =
            rawSizeToLong(RiftAllocator.Impl.statsRegionOpNanos()),
          riftSlowAllocNanos =
            rawSizeToLong(RiftAllocator.Impl.statsSlowAllocNanos())
        )
    }

    def since(start: RuntimeSample, end: RuntimeSample): RuntimeSample =
      RuntimeSample(
        gcCollections = delta(end.gcCollections, start.gcCollections),
        gcNanos = delta(end.gcNanos, start.gcNanos),
        riftRegionOpenTotal =
          delta(end.riftRegionOpenTotal, start.riftRegionOpenTotal),
        riftRegionCloseTotal =
          delta(end.riftRegionCloseTotal, start.riftRegionCloseTotal),
        riftRegionResetTotal =
          delta(end.riftRegionResetTotal, start.riftRegionResetTotal),
        riftAllocObjectTotal =
          delta(end.riftAllocObjectTotal, start.riftAllocObjectTotal),
        riftZeroObjectTotal =
          delta(end.riftZeroObjectTotal, start.riftZeroObjectTotal),
        riftZeroObjectBytesTotal =
          delta(end.riftZeroObjectBytesTotal, start.riftZeroObjectBytesTotal),
        riftZeroSkippedTotal =
          delta(end.riftZeroSkippedTotal, start.riftZeroSkippedTotal),
        riftZeroSkippedBytesTotal =
          delta(end.riftZeroSkippedBytesTotal, start.riftZeroSkippedBytesTotal),
        riftRegionOpNanos =
          delta(end.riftRegionOpNanos, start.riftRegionOpNanos),
        riftSlowAllocNanos =
          delta(end.riftSlowAllocNanos, start.riftSlowAllocNanos)
      )
  }

  private def mix(value: Int): Int = {
    var x = value
    x ^= x << 13
    x ^= x >>> 17
    x ^= x << 5
    x & 0x7fffffff
  }

  private def fold(checksum: Long, a: Int, b: Int, c: Long, d: Int): Long =
    (((checksum ^ a.toLong) * 1099511628211L) ^ b.toLong ^ c ^ d.toLong)

  private def foldRef(checksum: Long, a: Int, prevA: Int, salt: Int, d: Int): Long =
    (((checksum ^ a.toLong) * 1099511628211L) ^ prevA.toLong ^ salt.toLong ^ d.toLong)

  private def medianDouble(values: Array[Double]): Double = {
    val sorted = values.clone()
    scala.util.Sorting.quickSort(sorted)
    if ((sorted.length & 1) == 1) sorted(sorted.length / 2)
    else (sorted(sorted.length / 2 - 1) + sorted(sorted.length / 2)) / 2.0
  }

  private def medianLong(values: Array[Long]): Long = {
    val sorted = values.clone()
    scala.util.Sorting.quickSort(sorted)
    if ((sorted.length & 1) == 1) sorted(sorted.length / 2)
    else (sorted(sorted.length / 2 - 1) + sorted(sorted.length / 2)) / 2L
  }

  private def runHeap(): Long = {
    val cfg = ObjectAllocationLoweringConfig
    val records = new Array[HeapRecord](cfg.objects)
    var checksum = 0L
    var i = 0
    while (i < cfg.objects) {
      val seed = mix(i * 1103515245 + 12345)
      val record =
        new HeapRecord(
          seed,
          seed >>> 3,
          seed.toLong * 1315423911L,
          seed & 255
        )
      record.d += record.a & 7
      records(i) = record
      i += 1
    }
    i = 0
    while (i < records.length) {
      val record = records(i)
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, record.a, record.b, record.c, record.d)
      i += 1
    }
    checksumSink = checksum
    checksum
  }

  private def runHeapReference(): Long = {
    val cfg = ObjectAllocationLoweringConfig
    val records = new Array[HeapRefRecord](cfg.objects)
    val metadata = new HeapRefMetadata(0x5eed)
    var prev: HeapRefRecord = null
    var checksum = 0L
    var i = 0
    while (i < cfg.objects) {
      val seed = mix(i * 1103515245 + 12345)
      val record =
        new HeapRefRecord(seed, prev, metadata, seed & 255)
      record.d += record.a & 7
      records(i) = record
      prev = record
      i += 1
    }
    i = 0
    while (i < records.length) {
      val record = records(i)
      if (i % cfg.sampleEvery == 0) {
        val prevA = if (record.prev == null) 0 else record.prev.a
        checksum = foldRef(checksum, record.a, prevA, record.metadata.salt, record.d)
      }
      i += 1
    }
    checksumSink = checksum
    checksum
  }

  private def runTrusted(kind: Int): Long = {
    val cfg = ObjectAllocationLoweringConfig
    val region = RiftRegion.trustedOpen(kind)
    val records = new Array[TrustedRecord](cfg.objects)
    var checksum = 0L
    var i = 0
    try {
      while (i < cfg.objects) {
        val seed = mix(i * 1103515245 + 12345)
        val record = region
          .alloc(new TrustedRecord(
            seed,
            seed >>> 3,
            seed.toLong * 1315423911L,
            seed & 255
          ))
          .asInstanceOf[TrustedRecord]
        record.d += record.a & 7
        records(i) = record
        i += 1
      }
      i = 0
      while (i < records.length) {
        val record = records(i)
        if (i % cfg.sampleEvery == 0)
          checksum = fold(checksum, record.a, record.b, record.c, record.d)
        i += 1
      }
    } finally region.close()
    checksumSink = checksum
    checksum
  }

  private def runTrustedReference(kind: Int): Long = {
    val cfg = ObjectAllocationLoweringConfig
    val region = RiftRegion.trustedOpen(kind)
    val records = new Array[TrustedRefRecord](cfg.objects)
    var checksum = 0L
    var i = 0
    try {
      val metadata = region
        .alloc(new TrustedRefMetadata(0x5eed))
        .asInstanceOf[TrustedRefMetadata]
      var prev: TrustedRefRecord = null
      while (i < cfg.objects) {
        val seed = mix(i * 1103515245 + 12345)
        val record = region
          .alloc(new TrustedRefRecord(seed, prev, metadata, seed & 255))
          .asInstanceOf[TrustedRefRecord]
        record.d += record.a & 7
        records(i) = record
        prev = record
        i += 1
      }
      i = 0
      while (i < records.length) {
        val record = records(i)
        if (i % cfg.sampleEvery == 0) {
          val prevA = if (record.prev == null) 0 else record.prev.a
          checksum =
            foldRef(checksum, record.a, prevA, record.metadata.salt, record.d)
        }
        i += 1
      }
    } finally region.close()
    checksumSink = checksum
    checksum
  }

  private def runCheckedBody()(using region: RiftRegion.StreamingRegion^): Long = {
    val cfg = ObjectAllocationLoweringConfig
    final class CheckedRecord(
        val a: Int,
        val b: Int,
        val c: Long,
        var d: Int
    )
    val records: Array[CheckedRecord^{region}]^{region} =
      RiftRegion.alloc(new Array[CheckedRecord^{region}](cfg.objects))
    var checksum = 0L
    var i = 0
    while (i < cfg.objects) {
      val seed = mix(i * 1103515245 + 12345)
      val record: CheckedRecord^{region} =
        RiftRegion.alloc(new CheckedRecord(
          seed,
          seed >>> 3,
          seed.toLong * 1315423911L,
          seed & 255
        ))
      record.d += record.a & 7
      records(i) = record
      i += 1
    }
    i = 0
    while (i < records.length) {
      val record = records(i)
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, record.a, record.b, record.c, record.d)
      i += 1
    }
    checksum
  }

  private def runCheckedReferenceBody()(using
      region: RiftRegion.StreamingRegion^
  ): Long = {
    val cfg = ObjectAllocationLoweringConfig
    final class CheckedRefMetadata(val salt: Int)
    final class CheckedRefRecord(
        val a: Int,
        val prev: CheckedRefRecord^{region},
        val metadata: CheckedRefMetadata^{region},
        var d: Int
    )
    val records: Array[CheckedRefRecord^{region}]^{region} =
      RiftRegion.alloc(new Array[CheckedRefRecord^{region}](cfg.objects))
    val metadata: CheckedRefMetadata^{region} =
      RiftRegion.alloc(new CheckedRefMetadata(0x5eed))
    var prev: CheckedRefRecord^{region} = null
    var checksum = 0L
    var i = 0
    while (i < cfg.objects) {
      val seed = mix(i * 1103515245 + 12345)
      val record: CheckedRefRecord^{region} =
        RiftRegion.alloc(new CheckedRefRecord(seed, prev, metadata, seed & 255))
      record.d += record.a & 7
      records(i) = record
      prev = record
      i += 1
    }
    i = 0
    while (i < records.length) {
      val record = records(i)
      if (i % cfg.sampleEvery == 0) {
        val prevA = if (record.prev == null) 0 else record.prev.a
        checksum = foldRef(checksum, record.a, prevA, record.metadata.salt, record.d)
      }
      i += 1
    }
    checksum
  }

  private def runCheckedOpenHandleBody()(using
      region: RiftOpenStreamingHandle^
  ): Long = {
    val cfg = ObjectAllocationLoweringConfig
    final class CheckedRecord(
        val a: Int,
        val b: Int,
        val c: Long,
        var d: Int
    )
    val records = new Array[CheckedRecord^{region}](cfg.objects)
    var checksum = 0L
    var i = 0
    while (i < cfg.objects) {
      val seed = mix(i * 1103515245 + 12345)
      val record: CheckedRecord^{region} =
        RiftAllocator.allocateOpenHandle(region, new CheckedRecord(
          seed,
          seed >>> 3,
          seed.toLong * 1315423911L,
          seed & 255
        ))
      record.d += record.a & 7
      records(i) = record
      i += 1
    }
    i = 0
    while (i < records.length) {
      val record = records(i)
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, record.a, record.b, record.c, record.d)
      i += 1
    }
    checksum
  }

  private def runCheckedOpenHandleReferenceBody()(using
      region: RiftOpenStreamingHandle^
  ): Long = {
    val cfg = ObjectAllocationLoweringConfig
    final class CheckedRefMetadata(val salt: Int)
    final class CheckedRefRecord(
        val a: Int,
        val prev: CheckedRefRecord^{region},
        val metadata: CheckedRefMetadata^{region},
        var d: Int
    )
    val records = new Array[CheckedRefRecord^{region}](cfg.objects)
    val metadata: CheckedRefMetadata^{region} =
      RiftAllocator.allocateOpenHandle(region, new CheckedRefMetadata(0x5eed))
    var prev: CheckedRefRecord^{region} = null
    var checksum = 0L
    var i = 0
    while (i < cfg.objects) {
      val seed = mix(i * 1103515245 + 12345)
      val record: CheckedRefRecord^{region} =
        RiftAllocator.allocateOpenHandle(
          region,
          new CheckedRefRecord(seed, prev, metadata, seed & 255)
        )
      record.d += record.a & 7
      records(i) = record
      prev = record
      i += 1
    }
    i = 0
    while (i < records.length) {
      val record = records(i)
      if (i % cfg.sampleEvery == 0) {
        val prevA = if (record.prev == null) 0 else record.prev.a
        checksum = foldRef(checksum, record.a, prevA, record.metadata.salt, record.d)
      }
      i += 1
    }
    checksum
  }

  private def runCheckedRift(): Long = {
    val checksum = RiftRegion.streaming { stream ?=>
      if (ObjectAllocationLoweringConfig.recordShape == "reference")
        runCheckedReferenceBody()
      else runCheckedBody()
    }
    checksumSink = checksum
    checksum
  }

  private def runCheckedNoZeroOpenHandleBody()(using
      region: RiftNoZeroOpenStreamingHandle^
  ): Long = {
    val cfg = ObjectAllocationLoweringConfig
    final class CheckedRecord(
        val a: Int,
        val b: Int,
        val c: Long,
        var d: Int
    )
    val records = new Array[CheckedRecord^{region}](cfg.objects)
    var checksum = 0L
    var i = 0
    while (i < cfg.objects) {
      val seed = mix(i * 1103515245 + 12345)
      val record: CheckedRecord^{region} =
        RiftAllocator.allocateOpenHandleNoZero(region, new CheckedRecord(
          seed,
          seed >>> 3,
          seed.toLong * 1315423911L,
          seed & 255
        ))
      record.d += record.a & 7
      records(i) = record
      i += 1
    }
    i = 0
    while (i < records.length) {
      val record = records(i)
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, record.a, record.b, record.c, record.d)
      i += 1
    }
    checksum
  }

  private def runCheckedNoZeroOpenHandleReferenceBody()(using
      region: RiftNoZeroOpenStreamingHandle^
  ): Long = {
    val cfg = ObjectAllocationLoweringConfig
    final class CheckedRefMetadata(val salt: Int)
    final class CheckedRefRecord(
        val a: Int,
        val prev: CheckedRefRecord^{region},
        val metadata: CheckedRefMetadata^{region},
        var d: Int
    )
    val records = new Array[CheckedRefRecord^{region}](cfg.objects)
    val metadata: CheckedRefMetadata^{region} =
      RiftAllocator.allocateOpenHandleNoZero(
        region,
        new CheckedRefMetadata(0x5eed)
      )
    var prev: CheckedRefRecord^{region} = null
    var checksum = 0L
    var i = 0
    while (i < cfg.objects) {
      val seed = mix(i * 1103515245 + 12345)
      val record: CheckedRefRecord^{region} =
        RiftAllocator.allocateOpenHandleNoZero(
          region,
          new CheckedRefRecord(seed, prev, metadata, seed & 255)
        )
      record.d += record.a & 7
      records(i) = record
      prev = record
      i += 1
    }
    i = 0
    while (i < records.length) {
      val record = records(i)
      if (i % cfg.sampleEvery == 0) {
        val prevA = if (record.prev == null) 0 else record.prev.a
        checksum = foldRef(checksum, record.a, prevA, record.metadata.salt, record.d)
      }
      i += 1
    }
    checksum
  }

  private def runCheckedRiftOpenHandle(): Long = {
    val checksum = RiftRegion.epochOpenHandle {
      if (ObjectAllocationLoweringConfig.recordShape == "reference")
        runCheckedOpenHandleReferenceBody()
      else runCheckedOpenHandleBody()
    }
    checksumSink = checksum
    checksum
  }

  private def runCheckedRiftNoZeroOpenHandle(): Long = {
    val checksum = RiftRegion.epochNoZeroOpenHandle {
      if (ObjectAllocationLoweringConfig.recordShape == "reference")
        runCheckedNoZeroOpenHandleReferenceBody()
      else runCheckedNoZeroOpenHandleBody()
    }
    checksumSink = checksum
    checksum
  }

  private def dirtyRiftSlabsWithOpenHandle(): Unit = {
    val cfg = ObjectAllocationLoweringConfig
    if (cfg.dirtyPrepObjects <= 0) return
    var sink = 0L
    RiftRegion.epochOpenHandle {
      val region = summon[RiftOpenStreamingHandle^]
      var i = 0
      while (i < cfg.dirtyPrepObjects) {
        val seed = mix(i * 1103515245 + 12345)
        val record: DirtyPrepRecord^{region} =
          RiftAllocator.allocateOpenHandle(
            region,
            new DirtyPrepRecord(
              seed,
              seed >>> 3,
              seed.toLong * 1315423911L,
              seed & 255
            )
          )
        if ((i & 4095) == 0)
          sink = fold(sink, record.a, record.b, record.c, record.d)
        i += 1
      }
    }
    checksumSink ^= sink
  }

  private def runCheckedSafeZone(): Long = {
    val checksum = RiftRegion.streamingSafeZone { stream ?=>
      if (ObjectAllocationLoweringConfig.recordShape == "reference")
        runCheckedReferenceBody()
      else runCheckedBody()
    }
    checksumSink = checksum
    checksum
  }

  private def canonicalMode(mode: String): String =
    mode match {
      case "heap" | "heap-immix" => "heap-immix"
      case "rift-hp" | "rift-trusted-hp" => "rift-trusted-hp"
      case "rift-streaming" | "rift-trusted-streaming" =>
        "rift-trusted-streaming"
      case "rift-checked" | "rift-checked-rift" => "rift-checked-rift"
      case "rift-checked-rift-open-handle" | "checked-rift-open-handle" =>
        "rift-checked-rift-open-handle"
      case "rift-checked-rift-open-handle-nozero-unsafe" |
          "checked-rift-open-handle-nozero-unsafe" =>
        "rift-checked-rift-open-handle-nozero-unsafe"
      case "rift-checked-rift-open-handle-dirty-slab" |
          "checked-rift-open-handle-dirty-slab" =>
        "rift-checked-rift-open-handle-dirty-slab"
      case "rift-checked-safezone-32k" | "rift-checked-safezone-improved-32k" =>
        "rift-checked-safezone-improved-32k"
      case other =>
        throw new IllegalArgumentException(s"unknown object allocation mode '$other'")
    }

  private def usesRiftStats(mode: String): Boolean =
      mode == "rift-trusted-hp" ||
      mode == "rift-trusted-streaming" ||
      mode == "rift-checked-rift" ||
      mode == "rift-checked-rift-open-handle" ||
      mode == "rift-checked-rift-open-handle-nozero-unsafe" ||
      mode == "rift-checked-rift-open-handle-dirty-slab"

  private def runMode(mode: String): Long =
    mode match {
      case "heap-immix" =>
        if (ObjectAllocationLoweringConfig.recordShape == "reference")
          runHeapReference()
        else runHeap()
      case "rift-trusted-hp" =>
        if (ObjectAllocationLoweringConfig.recordShape == "reference")
          runTrustedReference(RiftRegion.HPZone)
        else runTrusted(RiftRegion.HPZone)
      case "rift-trusted-streaming" =>
        if (ObjectAllocationLoweringConfig.recordShape == "reference")
          runTrustedReference(RiftRegion.Streaming)
        else runTrusted(RiftRegion.Streaming)
      case "rift-checked-rift" => runCheckedRift()
      case "rift-checked-rift-open-handle" => runCheckedRiftOpenHandle()
      case "rift-checked-rift-open-handle-nozero-unsafe" =>
        runCheckedRiftNoZeroOpenHandle()
      case "rift-checked-rift-open-handle-dirty-slab" =>
        runCheckedRiftOpenHandle()
      case "rift-checked-safezone-improved-32k" => runCheckedSafeZone()
    }

  private def prepareMode(mode: String): Unit =
    mode match {
      case "rift-checked-rift-open-handle-dirty-slab" =>
        dirtyRiftSlabsWithOpenHandle()
      case _ => ()
    }

  def run(modeArg: String): Unit = {
    val mode = canonicalMode(modeArg)
    val cfg = ObjectAllocationLoweringConfig
    if (cfg.recordShape != "primitive" && cfg.recordShape != "reference")
      throw new IllegalArgumentException(
        s"unknown OBJECT_ALLOC_RECORD_SHAPE '${cfg.recordShape}'"
      )
    val totalRuns = cfg.warmupRuns + cfg.benchmarkRuns
    val times = new Array[Double](cfg.benchmarkRuns)
    val gcTimes = new Array[Double](cfg.benchmarkRuns)
    val riftOpTimes = new Array[Double](cfg.benchmarkRuns)
    val slowAllocTimes = new Array[Double](cfg.benchmarkRuns)
    val gcCollections = new Array[Long](cfg.benchmarkRuns)
    val riftAllocObjects = new Array[Long](cfg.benchmarkRuns)
    val riftZeroObjects = new Array[Long](cfg.benchmarkRuns)
    val riftZeroBytes = new Array[Long](cfg.benchmarkRuns)
    val riftZeroSkipped = new Array[Long](cfg.benchmarkRuns)
    val riftZeroSkippedBytes = new Array[Long](cfg.benchmarkRuns)
    val riftOpenTotal = new Array[Long](cfg.benchmarkRuns)
    val riftCloseTotal = new Array[Long](cfg.benchmarkRuns)
    val riftResetTotal = new Array[Long](cfg.benchmarkRuns)
    var checksum = 0L
    var run = 0
    while (run < totalRuns) {
      val measured = run >= cfg.warmupRuns
      prepareMode(mode)
      if (usesRiftStats(mode)) RiftAllocator.Impl.statsReset()
      val before = RuntimeSample.capture(usesRiftStats(mode))
      val start = System.nanoTime()
      val result = runMode(mode)
      val elapsed = System.nanoTime() - start
      val after = RuntimeSample.capture(usesRiftStats(mode))
      val delta = RuntimeSample.since(before, after)
      if (measured) {
        val index = run - cfg.warmupRuns
        times(index) = elapsed.toDouble / 1000000.0
        gcTimes(index) = delta.gcNanos.toDouble / 1000000.0
        riftOpTimes(index) = delta.riftRegionOpNanos.toDouble / 1000000.0
        slowAllocTimes(index) = delta.riftSlowAllocNanos.toDouble / 1000000.0
        gcCollections(index) = delta.gcCollections
        riftAllocObjects(index) = delta.riftAllocObjectTotal
        riftZeroObjects(index) = delta.riftZeroObjectTotal
        riftZeroBytes(index) = delta.riftZeroObjectBytesTotal
        riftZeroSkipped(index) = delta.riftZeroSkippedTotal
        riftZeroSkippedBytes(index) = delta.riftZeroSkippedBytesTotal
        riftOpenTotal(index) = delta.riftRegionOpenTotal
        riftCloseTotal(index) = delta.riftRegionCloseTotal
        riftResetTotal(index) = delta.riftRegionResetTotal
        checksum = result
      }
      run += 1
    }

    val medianMs = medianDouble(times)
    val medianGcMs = medianDouble(gcTimes)
    val maxGcMs = gcTimes.max
    val runsWithGc = gcCollections.count(_ > 0L)
    val medianRiftOpMs = medianDouble(riftOpTimes)
    val medianSlowAllocMs = medianDouble(slowAllocTimes)
    val medianRiftObjects = medianLong(riftAllocObjects)
    val medianRiftZeroObjects = medianLong(riftZeroObjects)
    val medianRiftZeroBytes = medianLong(riftZeroBytes)
    val medianRiftZeroSkipped = medianLong(riftZeroSkipped)
    val medianRiftZeroSkippedBytes = medianLong(riftZeroSkippedBytes)
    val medianOpen = medianLong(riftOpenTotal)
    val medianClose = medianLong(riftCloseTotal)
    val medianReset = medianLong(riftResetTotal)

    println(
      f"RESULT name=object-allocation-lowering-$mode " +
        s"mode=$mode record_shape=${cfg.recordShape} objects=${cfg.objects} " +
        f"median_ms=$medianMs%.3f " +
        f"median_gc_ms=$medianGcMs%.3f " +
        f"max_gc_ms=$maxGcMs%.3f " +
        s"runs_with_gc=$runsWithGc " +
        f"median_rift_op_ms=$medianRiftOpMs%.3f " +
        f"median_rift_slow_alloc_ms=$medianSlowAllocMs%.3f " +
        s"median_rift_alloc_object_total=$medianRiftObjects " +
        s"median_rift_zero_object_total=$medianRiftZeroObjects " +
        s"median_rift_zero_object_bytes_total=$medianRiftZeroBytes " +
        s"median_rift_zero_skipped_total=$medianRiftZeroSkipped " +
        s"median_rift_zero_skipped_bytes_total=$medianRiftZeroSkippedBytes " +
        s"median_rift_open_total=$medianOpen " +
        s"median_rift_close_total=$medianClose " +
        s"median_rift_reset_total=$medianReset " +
        s"checksum=$checksum"
    )
  }
}

object ObjectAllocationLoweringMatrix {
  def main(args: Array[String]): Unit = {
    val mode = if (args.nonEmpty) args(0) else "heap-immix"
    ObjectAllocationLoweringMatrixHelpers.run(mode)
  }
}
