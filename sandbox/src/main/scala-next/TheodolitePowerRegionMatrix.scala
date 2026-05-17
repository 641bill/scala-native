import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.memory.SafeZone._
import scala.scalanative.runtime.{
  fromRawUSize,
  GC,
  RawSize,
  RiftAllocator,
  SafeZoneAllocator
}

object TheodolitePowerRegionConfig {
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

  private def truthy(value: String): Boolean =
    value == "1" || value.equalsIgnoreCase("true") ||
      value.equalsIgnoreCase("yes")

  val input: String = BenchmarkInputSupport.envString("THEODOLITE_POWER_INPUT")
  val inputMode: String = {
    val raw = BenchmarkInputSupport.envString("THEODOLITE_POWER_INPUT_MODE")
    if (raw.isEmpty) "preloaded"
    else
      raw match {
        case "preloaded" | "streaming-file" => raw
        case other =>
          throw new IllegalArgumentException(
            s"unknown THEODOLITE_POWER_INPUT_MODE '$other'; expected preloaded or streaming-file"
          )
      }
  }
  val streamingInput: Boolean = inputMode == "streaming-file"
  val requestedRecords: Int = envInt("THEODOLITE_POWER_RECORDS", 1000000)
  val recordsPerEpoch: Int =
    envInt("THEODOLITE_POWER_RECORDS_PER_EPOCH", 25000)
  val groupCount: Int = envInt("THEODOLITE_POWER_GROUPS", 256)
  val benchmarkRuns: Int = envInt("THEODOLITE_POWER_BENCHMARK_RUNS", 3)
  val warmupRuns: Int = envNonNegativeInt("THEODOLITE_POWER_WARMUPS", 1)
  val finalClean: Boolean =
    sys.env.get("RIFT_FINAL_CLEAN").exists(truthy) ||
      sys.env
        .get("RIFT_EVAL_MEASUREMENT_LEVEL")
        .exists(_.equalsIgnoreCase("L1"))
}

object TheodolitePowerRegionMatrixHelpers {
  @volatile private var checksumSink = 0L
  @volatile private var retainedAnchorSink = 0L

  private final class InputData(
      val label: String,
      val records: Int,
      val totalMilliW: Array[Int],
      val sub1Watt: Array[Int],
      val sub2Watt: Array[Int],
      val sub3Watt: Array[Int],
      val voltageDeci: Array[Int]
  )

  private abstract class PowerConsumer {
    def apply(
        index: Int,
        totalMilliW: Int,
        sub1Watt: Int,
        sub2Watt: Int,
        sub3Watt: Int,
        voltageDeci: Int
    ): Unit
  }

  private final class HeapMeasurement(
      val minute: Int,
      val group: Int,
      val totalMilliW: Int,
      val voltageDeci: Int,
      val next: HeapMeasurement
  )

  private final class HeapContribution(
      val minute: Int,
      val group: Int,
      val circuit: Int,
      val watt: Int,
      val next: HeapContribution
  )

  private final class SafeMeasurement(
      val minute: Int,
      val group: Int,
      val totalMilliW: Int,
      val voltageDeci: Int,
      val next: SafeMeasurement
  )

  private final class SafeContribution(
      val minute: Int,
      val group: Int,
      val circuit: Int,
      val watt: Int,
      val next: SafeContribution
  )

  private final class TrustedMeasurement(
      val minute: Int,
      val group: Int,
      val totalMilliW: Int,
      val voltageDeci: Int,
      val next: TrustedMeasurement
  )

  private final class TrustedContribution(
      val minute: Int,
      val group: Int,
      val circuit: Int,
      val watt: Int,
      val next: TrustedContribution
  )

  final case class RunOutcome(checksum: Long, outputCount: Long)

  final case class RuntimeSample(
      gcCollections: Long,
      gcNanos: Long,
      riftRegionOpenTotal: Long,
      riftRegionCloseTotal: Long,
      riftRegionResetTotal: Long,
      riftAllocObjectTotal: Long,
      riftRegionOpNanos: Long,
      riftSlowAllocNanos: Long
  )

  private object RuntimeSample {
    val zero: RuntimeSample =
      RuntimeSample(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)

    private def rawSizeToLong(value: RawSize): Long =
      fromRawUSize(value).toLong

    private def nonNegative(value: Long): Long =
      if (value < 0L) 0L else value

    private def delta(end: Long, start: Long): Long =
      if (end >= 0L && start >= 0L && end >= start) end - start else 0L

    def capture(includeRift: Boolean): RuntimeSample = {
      val gcCollections = nonNegative(GC.getStatsCollectionTotal().toLong)
      val gcNanos = nonNegative(GC.getStatsCollectionDurationTotal().toLong)

      if (!includeRift) {
        zero.copy(gcCollections = gcCollections, gcNanos = gcNanos)
      } else {
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
          riftRegionOpNanos =
            rawSizeToLong(RiftAllocator.Impl.statsRegionOpNanos()),
          riftSlowAllocNanos =
            rawSizeToLong(RiftAllocator.Impl.statsSlowAllocNanos())
        )
      }
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
        riftRegionOpNanos =
          delta(end.riftRegionOpNanos, start.riftRegionOpNanos),
        riftSlowAllocNanos =
          delta(end.riftSlowAllocNanos, start.riftSlowAllocNanos)
      )
  }

  private lazy val loadedInput: InputData = loadInput()

  private var parsedTotalMilliW = 0
  private var parsedSub1Watt = 0
  private var parsedSub2Watt = 0
  private var parsedSub3Watt = 0
  private var parsedVoltageDeci = 0
  private val fieldCursor = new BenchmarkInputSupport.DelimitedByteFields(';')

  private def parseCurrentLine(
      bytes: Array[Byte],
      length: Int
  ): Boolean = {
    fieldCursor.reset(bytes, length)
    if (!fieldCursor.advanceTo(2)) return false
    val active = BenchmarkInputSupport.parseMilliDecimal(
      bytes,
      fieldCursor.offset,
      fieldCursor.length
    )
    if (!fieldCursor.advanceTo(4)) return false
    val volt = BenchmarkInputSupport.parseMilliDecimal(
      bytes,
      fieldCursor.offset,
      fieldCursor.length
    )
    if (!fieldCursor.advanceTo(6)) return false
    val s1 = BenchmarkInputSupport.parseIntDecimal(
      bytes,
      fieldCursor.offset,
      fieldCursor.length
    )
    if (!fieldCursor.advanceTo(7)) return false
    val s2 = BenchmarkInputSupport.parseIntDecimal(
      bytes,
      fieldCursor.offset,
      fieldCursor.length
    )
    if (!fieldCursor.advanceTo(8)) return false
    val s3 = BenchmarkInputSupport.parseIntDecimal(
      bytes,
      fieldCursor.offset,
      fieldCursor.length
    )
    if (active >= 0 && volt >= 0 && s1 >= 0 && s2 >= 0 && s3 >= 0) {
      parsedTotalMilliW = active
      parsedVoltageDeci = volt / 100
      parsedSub1Watt = s1
      parsedSub2Watt = s2
      parsedSub3Watt = s3
      true
    } else false
  }

  private def parseLine(
      bytes: Array[Byte],
      length: Int,
      index: Int,
      consumer: PowerConsumer
  ): Boolean = {
    if (parseCurrentLine(bytes, length)) {
      consumer(
        index,
        parsedTotalMilliW,
        parsedSub1Watt,
        parsedSub2Watt,
        parsedSub3Watt,
        parsedVoltageDeci
      )
      true
    } else {
      false
    }
  }

  private def foreachStreamingRecord(consumer: PowerConsumer): Int = {
    val cfg = TheodolitePowerRegionConfig
    val reader = BenchmarkInputSupport.openByteLines(cfg.input)
    var count = 0
    try {
      reader.readLine() // header
      var length = reader.readLine()
      while (length >= 0 && count < cfg.requestedRecords) {
        if (parseLine(reader.bytes, length, count, consumer))
          count += 1
        length = reader.readLine()
      }
    } finally {
      reader.close()
    }
    count
  }

  private def countStreamingRecords(): Int =
    foreachStreamingRecord(new PowerConsumer {
      def apply(
          index: Int,
          totalMilliW: Int,
          sub1Watt: Int,
          sub2Watt: Int,
          sub3Watt: Int,
          voltageDeci: Int
      ): Unit = ()
    })

  private def loadInput(): InputData = {
    val cfg = TheodolitePowerRegionConfig
    if (cfg.input.isEmpty)
      throw new IllegalArgumentException(
        "THEODOLITE_POWER_INPUT must point to household_power_consumption.txt"
      )

    if (cfg.streamingInput) {
      val count = countStreamingRecords()
      if (count == 0)
        throw new IllegalArgumentException(
          s"no usable power records counted from ${cfg.input}"
        )
      return new InputData(
        "real-uci-household-power-streaming-file",
        count,
        null,
        null,
        null,
        null,
        null
      )
    }

    val total = new Array[Int](cfg.requestedRecords)
    val sub1 = new Array[Int](cfg.requestedRecords)
    val sub2 = new Array[Int](cfg.requestedRecords)
    val sub3 = new Array[Int](cfg.requestedRecords)
    val voltage = new Array[Int](cfg.requestedRecords)

    val reader = BenchmarkInputSupport.openByteLines(cfg.input)
    var count = 0
    try {
      reader.readLine() // header
      var length = reader.readLine()
      while (length >= 0 && count < cfg.requestedRecords) {
        parseLine(
          reader.bytes,
          length,
          count,
          new PowerConsumer {
            def apply(
                index: Int,
                active: Int,
                s1: Int,
                s2: Int,
                s3: Int,
                volt: Int
            ): Unit = {
              total(index) = active
              voltage(index) = volt
              sub1(index) = s1
              sub2(index) = s2
              sub3(index) = s3
              count += 1
            }
          }
        )
        length = reader.readLine()
      }
    } finally {
      reader.close()
    }

    if (count == 0)
      throw new IllegalArgumentException(
        s"no usable power records loaded from ${cfg.input}"
      )

    def trim(values: Array[Int]): Array[Int] =
      if (values.length == count) values
      else {
        val out = new Array[Int](count)
        System.arraycopy(values, 0, out, 0, count)
        out
      }

    new InputData(
      "real-uci-household-power",
      count,
      trim(total),
      trim(sub1),
      trim(sub2),
      trim(sub3),
      trim(voltage)
    )
  }

  private def mix(value: Int): Int = {
    var x = value
    x ^= x << 13
    x ^= x >>> 17
    x ^= x << 5
    x & 0x7fffffff
  }

  private def groupFor(minute: Int): Int =
    mix(minute * 1103515245 + 12345) % TheodolitePowerRegionConfig.groupCount

  private def fold(acc: Long, a: Int, b: Int, c: Int, d: Long): Long =
    (((acc * 1099511628211L) ^ a.toLong) + (b.toLong << 7)) ^
      (c.toLong * 1315423911L) ^ d

  private def queryOutputMultiplier(query: String): Int =
    query match {
      case "q1-downsample"                      => 1
      case "q2-hierarchical"                   => 4
      case "q3-retained-uc4" | "uc4-retained" |
          "q3-uc4-retained" =>
        13
      case other =>
        throw new IllegalArgumentException(
          s"unknown Theodolite power query '$other'"
        )
    }

  private def isRetainedUc4(query: String): Boolean =
    query == "q3-retained-uc4" || query == "uc4-retained" ||
      query == "q3-uc4-retained"

  private def uc4ContributionCount: Int = 12

  private def uc4Node(index: Int, group: Int): Int =
    if (index < 3) group
    else if (index < 6) group >>> 1
    else if (index < 9) group >>> 3
    else if (index < 11) group >>> 4
    else group >>> 5

  private def uc4Offset(index: Int): Int =
    if (index < 3) 1 + index
    else if (index < 6) 4 + (index - 3)
    else if (index < 9) 7 + (index - 6)
    else if (index == 9) 10
    else if (index == 10) 11
    else 12

  private def uc4Circuit(index: Int): Int =
    if (index < 9) (index % 3) + 1
    else if (index == 9) 10
    else if (index == 10) 11
    else 12

  private def uc4Watt(
      index: Int,
      totalMilliW: Int,
      sub1Watt: Int,
      sub2Watt: Int,
      sub3Watt: Int
  ): Int =
    if (index == 0 || index == 3 || index == 6) sub1Watt
    else if (index == 1 || index == 4 || index == 7) sub2Watt
    else if (index == 2 || index == 5 || index == 8) sub3Watt
    else if (index == 10) sub1Watt + sub2Watt + sub3Watt
    else totalMilliW / 1000

  private def uc4Slot(index: Int, group: Int): Int =
    uc4Offset(index) * TheodolitePowerRegionConfig.groupCount +
      uc4Node(index, group)

  private def canonicalMode(mode: String): String =
    mode match {
      case "gc-heap" | "heap-immix" => "heap"
      case "region-scoped-rooted" | "safezone-improved-32k" |
          "safezone-improved" =>
        "safezone"
      case "region-stream-rootless" | "rift-trusted-streaming" =>
        "rift-streaming"
      case "checked-epoch-stream" | "checked-region-stream" =>
        "checked-epoch-stream"
      case "checked-epoch-stream-open-handle" |
          "checked-region-stream-open-handle" =>
        "checked-epoch-stream-open-handle"
      case "checked-epoch-stream-legacy" |
          "checked-region-stream-legacy" =>
        "checked-epoch-stream-legacy"
      case "checked-epoch-scoped" | "checked-region-scoped" =>
        "checked-epoch-scoped"
      case other => other
    }

  private def usesRiftRuntime(mode: String): Boolean =
    canonicalMode(mode) match {
      case "rift-streaming" | "checked-epoch-stream" |
          "checked-epoch-stream-open-handle" |
          "checked-epoch-stream-legacy" =>
        true
      case _                                        => false
    }

  def validateMode(mode: String): Unit =
    canonicalMode(mode) match {
      case "heap" | "safezone" | "rift-streaming" | "checked-epoch-stream" |
          "checked-epoch-stream-open-handle" |
          "checked-epoch-stream-legacy" |
          "checked-epoch-scoped" =>
        ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown Theodolite power mode '$other'"
        )
    }

  def validateQuery(query: String): Unit = {
    queryOutputMultiplier(query)
    ()
  }

  private def runHeapStreaming(query: String): RunOutcome = {
    val cfg = TheodolitePowerRegionConfig
    val input = loadedInput
    val sums = new Array[Long](cfg.groupCount * queryOutputMultiplier(query))
    val counts = new Array[Int](sums.length)
    val reader = BenchmarkInputSupport.openByteLines(cfg.input)
    var checksum = 0L
    var outputs = 0L
    var index = 0
    try {
      reader.readLine() // header
      var length = reader.readLine()
      while (length >= 0 && index < input.records) {
        var measurementHead: HeapMeasurement = null
        var contributionHead: HeapContribution = null
        var inEpoch = 0
        while (length >= 0 && index < input.records && inEpoch < cfg.recordsPerEpoch) {
          if (parseCurrentLine(reader.bytes, length)) {
            val group = groupFor(index)
            val measurement = new HeapMeasurement(
              index,
              group,
              parsedTotalMilliW,
              parsedVoltageDeci,
              measurementHead
            )
            measurementHead = measurement
            sums(group) += measurement.totalMilliW.toLong
            counts(group) += 1
            checksum = fold(
              checksum,
              measurement.minute,
              measurement.group,
              measurement.voltageDeci,
              measurement.totalMilliW.toLong
            )
            if (query == "q2-hierarchical") {
              val base = cfg.groupCount
              val c1 = new HeapContribution(
                index,
                group,
                1,
                parsedSub1Watt,
                contributionHead
              )
              contributionHead = c1
              sums(base + group) += c1.watt.toLong
              counts(base + group) += 1
              val c2 = new HeapContribution(
                index,
                group,
                2,
                parsedSub2Watt,
                contributionHead
              )
              contributionHead = c2
              sums(base * 2 + group) += c2.watt.toLong
              counts(base * 2 + group) += 1
              val c3 = new HeapContribution(
                index,
                group,
                3,
                parsedSub3Watt,
                contributionHead
              )
              contributionHead = c3
              sums(base * 3 + group) += c3.watt.toLong
              counts(base * 3 + group) += 1
              checksum = fold(checksum, c1.minute, c2.watt, c3.watt, c1.watt.toLong)
            } else if (isRetainedUc4(query)) {
              var k = 0
              while (k < uc4ContributionCount) {
                val node = uc4Node(k, group)
                val watt = uc4Watt(
                  k,
                  parsedTotalMilliW,
                  parsedSub1Watt,
                  parsedSub2Watt,
                  parsedSub3Watt
                )
                val contribution = new HeapContribution(
                  index,
                  node,
                  uc4Circuit(k),
                  watt,
                  contributionHead
                )
                contributionHead = contribution
                val slot = uc4Slot(k, group)
                sums(slot) += contribution.watt.toLong
                counts(slot) += 1
                checksum = fold(
                  checksum,
                  contribution.minute,
                  contribution.group,
                  contribution.circuit,
                  contribution.watt.toLong
                )
                k += 1
              }
            }
            index += 1
            inEpoch += 1
          }
          length = reader.readLine()
        }
        if (inEpoch > 0) {
          retainedAnchorSink ^= {
            val a = if (measurementHead == null) 0L else measurementHead.totalMilliW.toLong
            val b = if (contributionHead == null) 0L else contributionHead.watt.toLong
            a ^ (b << 5)
          }
          outputs += closeEpoch(sums, counts)
        }
      }
    } finally {
      reader.close()
    }
    checksumSink = checksum
    RunOutcome(checksum ^ checksumState(sums, counts), outputs)
  }

  private def runHeap(query: String): RunOutcome = {
    val cfg = TheodolitePowerRegionConfig
    if (cfg.streamingInput) return runHeapStreaming(query)
    val input = loadedInput
    val sums = new Array[Long](cfg.groupCount * queryOutputMultiplier(query))
    val counts = new Array[Int](sums.length)
    var checksum = 0L
    var outputs = 0L
    var index = 0
    while (index < input.records) {
      val end = math.min(input.records, index + cfg.recordsPerEpoch)
      var measurementHead: HeapMeasurement = null
      var contributionHead: HeapContribution = null
      var i = index
      while (i < end) {
        val group = groupFor(i)
        val measurement = new HeapMeasurement(
          i,
          group,
          input.totalMilliW(i),
          input.voltageDeci(i),
          measurementHead
        )
        measurementHead = measurement
        sums(group) += measurement.totalMilliW.toLong
        counts(group) += 1
        checksum = fold(
          checksum,
          measurement.minute,
          measurement.group,
          measurement.voltageDeci,
          measurement.totalMilliW.toLong
        )

        if (query == "q2-hierarchical") {
          val base = cfg.groupCount
          val c1 = new HeapContribution(i, group, 1, input.sub1Watt(i), contributionHead)
          contributionHead = c1
          sums(base + group) += c1.watt.toLong
          counts(base + group) += 1
          val c2 = new HeapContribution(i, group, 2, input.sub2Watt(i), contributionHead)
          contributionHead = c2
          sums(base * 2 + group) += c2.watt.toLong
          counts(base * 2 + group) += 1
          val c3 = new HeapContribution(i, group, 3, input.sub3Watt(i), contributionHead)
          contributionHead = c3
          sums(base * 3 + group) += c3.watt.toLong
          counts(base * 3 + group) += 1
          checksum = fold(checksum, c1.minute, c2.watt, c3.watt, c1.watt.toLong)
        } else if (isRetainedUc4(query)) {
          var k = 0
          while (k < uc4ContributionCount) {
            val node = uc4Node(k, group)
            val watt = uc4Watt(
              k,
              input.totalMilliW(i),
              input.sub1Watt(i),
              input.sub2Watt(i),
              input.sub3Watt(i)
            )
            val contribution =
              new HeapContribution(i, node, uc4Circuit(k), watt, contributionHead)
            contributionHead = contribution
            val slot = uc4Slot(k, group)
            sums(slot) += contribution.watt.toLong
            counts(slot) += 1
            checksum = fold(
              checksum,
              contribution.minute,
              contribution.group,
              contribution.circuit,
              contribution.watt.toLong
            )
            k += 1
          }
        }
        i += 1
      }
      retainedAnchorSink ^= {
        val a = if (measurementHead == null) 0L else measurementHead.totalMilliW.toLong
        val b = if (contributionHead == null) 0L else contributionHead.watt.toLong
        a ^ (b << 5)
      }
      outputs += closeEpoch(sums, counts)
      index = end
    }
    checksumSink = checksum
    RunOutcome(checksum ^ checksumState(sums, counts), outputs)
  }

  private def runSafeZoneStreaming(query: String): RunOutcome = {
    val cfg = TheodolitePowerRegionConfig
    val input = loadedInput
    val sums = new Array[Long](cfg.groupCount * queryOutputMultiplier(query))
    val counts = new Array[Int](sums.length)
    val reader = BenchmarkInputSupport.openByteLines(cfg.input)
    var checksum = 0L
    var outputs = 0L
    var index = 0
    try {
      reader.readLine() // header
      var length = reader.readLine()
      while (length >= 0 && index < input.records) {
        var anchor = 0L
        var inEpoch = 0
        SafeZone { sz ?=>
          final class SZMeasurement(
              val minute: Int,
              val group: Int,
              val totalMilliW: Int,
              val voltageDeci: Int,
              val next: SZMeasurement^{sz}
          )

          final class SZContribution(
              val minute: Int,
              val group: Int,
              val circuit: Int,
              val watt: Int,
              val next: SZContribution^{sz}
          )

          var measurementHead: SZMeasurement^{sz} = null
          var contributionHead: SZContribution^{sz} = null
          while (
            length >= 0 && index < input.records && inEpoch < cfg.recordsPerEpoch
          ) {
            if (parseCurrentLine(reader.bytes, length)) {
              val group = groupFor(index)
              val measurement = SafeZoneAllocator.allocate(
                sz,
                new SZMeasurement(
                  index,
                  group,
                  parsedTotalMilliW,
                  parsedVoltageDeci,
                  measurementHead
                )
              )
              measurementHead = measurement
              sums(group) += measurement.totalMilliW.toLong
              counts(group) += 1
              checksum = fold(
                checksum,
                measurement.minute,
                measurement.group,
                measurement.voltageDeci,
                measurement.totalMilliW.toLong
              )
              if (query == "q2-hierarchical") {
                val base = cfg.groupCount
                val c1 = SafeZoneAllocator.allocate(
                  sz,
                  new SZContribution(index, group, 1, parsedSub1Watt, contributionHead)
                )
                contributionHead = c1
                sums(base + group) += c1.watt.toLong
                counts(base + group) += 1
                val c2 = SafeZoneAllocator.allocate(
                  sz,
                  new SZContribution(index, group, 2, parsedSub2Watt, contributionHead)
                )
                contributionHead = c2
                sums(base * 2 + group) += c2.watt.toLong
                counts(base * 2 + group) += 1
                val c3 = SafeZoneAllocator.allocate(
                  sz,
                  new SZContribution(index, group, 3, parsedSub3Watt, contributionHead)
                )
                contributionHead = c3
                sums(base * 3 + group) += c3.watt.toLong
                counts(base * 3 + group) += 1
                checksum = fold(checksum, c1.minute, c2.watt, c3.watt, c1.watt.toLong)
              } else if (isRetainedUc4(query)) {
                var k = 0
                while (k < uc4ContributionCount) {
                  val node = uc4Node(k, group)
                  val watt = uc4Watt(
                    k,
                    parsedTotalMilliW,
                    parsedSub1Watt,
                    parsedSub2Watt,
                    parsedSub3Watt
                  )
                  val contribution = SafeZoneAllocator.allocate(
                    sz,
                    new SZContribution(
                      index,
                      node,
                      uc4Circuit(k),
                      watt,
                      contributionHead
                    )
                  )
                  contributionHead = contribution
                  val slot = uc4Slot(k, group)
                  sums(slot) += contribution.watt.toLong
                  counts(slot) += 1
                  checksum = fold(
                    checksum,
                    contribution.minute,
                    contribution.group,
                    contribution.circuit,
                    contribution.watt.toLong
                  )
                  k += 1
                }
              }
              index += 1
              inEpoch += 1
            }
            length = reader.readLine()
          }
          val a = if (measurementHead == null) 0L else measurementHead.totalMilliW.toLong
          val b = if (contributionHead == null) 0L else contributionHead.watt.toLong
          anchor = a ^ (b << 5)
        }
        if (inEpoch > 0) {
          retainedAnchorSink ^= anchor
          outputs += closeEpoch(sums, counts)
        }
      }
    } finally {
      reader.close()
    }
    checksumSink = checksum
    RunOutcome(checksum ^ checksumState(sums, counts), outputs)
  }

  private def runSafeZone(query: String): RunOutcome = {
    val cfg = TheodolitePowerRegionConfig
    if (cfg.streamingInput) return runSafeZoneStreaming(query)
    val input = loadedInput
    val sums = new Array[Long](cfg.groupCount * queryOutputMultiplier(query))
    val counts = new Array[Int](sums.length)
    var checksum = 0L
    var outputs = 0L
    var index = 0
    while (index < input.records) {
      val end = math.min(input.records, index + cfg.recordsPerEpoch)
      var anchor = 0L
      SafeZone { sz ?=>
        final class SZMeasurement(
            val minute: Int,
            val group: Int,
            val totalMilliW: Int,
            val voltageDeci: Int,
            val next: SZMeasurement^{sz}
        )

        final class SZContribution(
            val minute: Int,
            val group: Int,
            val circuit: Int,
            val watt: Int,
            val next: SZContribution^{sz}
        )

        var measurementHead: SZMeasurement^{sz} = null
        var contributionHead: SZContribution^{sz} = null
        var i = index
        while (i < end) {
          val group = groupFor(i)
          val measurement = SafeZoneAllocator.allocate(
            sz,
            new SZMeasurement(
              i,
              group,
              input.totalMilliW(i),
              input.voltageDeci(i),
              measurementHead
            )
          )
          measurementHead = measurement
          sums(group) += measurement.totalMilliW.toLong
          counts(group) += 1
          checksum = fold(
            checksum,
            measurement.minute,
            measurement.group,
            measurement.voltageDeci,
            measurement.totalMilliW.toLong
          )

          if (query == "q2-hierarchical") {
            val base = cfg.groupCount
            val c1 = SafeZoneAllocator.allocate(
              sz,
              new SZContribution(i, group, 1, input.sub1Watt(i), contributionHead)
            )
            contributionHead = c1
            sums(base + group) += c1.watt.toLong
            counts(base + group) += 1
            val c2 = SafeZoneAllocator.allocate(
              sz,
              new SZContribution(i, group, 2, input.sub2Watt(i), contributionHead)
            )
            contributionHead = c2
            sums(base * 2 + group) += c2.watt.toLong
            counts(base * 2 + group) += 1
            val c3 = SafeZoneAllocator.allocate(
              sz,
              new SZContribution(i, group, 3, input.sub3Watt(i), contributionHead)
            )
            contributionHead = c3
            sums(base * 3 + group) += c3.watt.toLong
            counts(base * 3 + group) += 1
            checksum = fold(checksum, c1.minute, c2.watt, c3.watt, c1.watt.toLong)
          } else if (isRetainedUc4(query)) {
            var k = 0
            while (k < uc4ContributionCount) {
              val node = uc4Node(k, group)
              val watt = uc4Watt(
                k,
                input.totalMilliW(i),
                input.sub1Watt(i),
                input.sub2Watt(i),
                input.sub3Watt(i)
              )
              val contribution = SafeZoneAllocator.allocate(
                sz,
                new SZContribution(i, node, uc4Circuit(k), watt, contributionHead)
              )
              contributionHead = contribution
              val slot = uc4Slot(k, group)
              sums(slot) += contribution.watt.toLong
              counts(slot) += 1
              checksum = fold(
                checksum,
                contribution.minute,
                contribution.group,
                contribution.circuit,
                contribution.watt.toLong
              )
              k += 1
            }
          }
          i += 1
        }
        val a = if (measurementHead == null) 0L else measurementHead.totalMilliW.toLong
        val b = if (contributionHead == null) 0L else contributionHead.watt.toLong
        anchor = a ^ (b << 5)
      }
      retainedAnchorSink ^= anchor
      outputs += closeEpoch(sums, counts)
      index = end
    }
    checksumSink = checksum
    RunOutcome(checksum ^ checksumState(sums, counts), outputs)
  }

  private def runRiftTrusted(query: String): RunOutcome = {
    val cfg = TheodolitePowerRegionConfig
    if (cfg.streamingInput)
      throw new UnsupportedOperationException(
        "region-stream-rootless is not wired for Theodolite streaming-file input; use checked epoch or scoped rows"
      )
    val input = loadedInput
    val sums = new Array[Long](cfg.groupCount * queryOutputMultiplier(query))
    val counts = new Array[Int](sums.length)
    val region = RiftRegion.open(RiftRegion.Streaming)
    var checksum = 0L
    var outputs = 0L
    var index = 0
    try {
      while (index < input.records) {
        if (index > 0) region.reset()
        val end = math.min(input.records, index + cfg.recordsPerEpoch)
        var measurementHead: TrustedMeasurement = null
        var contributionHead: TrustedContribution = null
        var i = index
        while (i < end) {
          val group = groupFor(i)
          val measurement = region.alloc(
            new TrustedMeasurement(
              i,
              group,
              input.totalMilliW(i),
              input.voltageDeci(i),
              measurementHead
            )
          )
          measurementHead = measurement
          sums(group) += measurement.totalMilliW.toLong
          counts(group) += 1
          checksum = fold(
            checksum,
            measurement.minute,
            measurement.group,
            measurement.voltageDeci,
            measurement.totalMilliW.toLong
          )
          if (query == "q2-hierarchical") {
            val base = cfg.groupCount
            val c1 = region.alloc(
              new TrustedContribution(i, group, 1, input.sub1Watt(i), contributionHead)
            )
            contributionHead = c1
            sums(base + group) += c1.watt.toLong
            counts(base + group) += 1
            val c2 = region.alloc(
              new TrustedContribution(i, group, 2, input.sub2Watt(i), contributionHead)
            )
            contributionHead = c2
            sums(base * 2 + group) += c2.watt.toLong
            counts(base * 2 + group) += 1
            val c3 = region.alloc(
              new TrustedContribution(i, group, 3, input.sub3Watt(i), contributionHead)
            )
            contributionHead = c3
            sums(base * 3 + group) += c3.watt.toLong
            counts(base * 3 + group) += 1
            checksum = fold(checksum, c1.minute, c2.watt, c3.watt, c1.watt.toLong)
          } else if (isRetainedUc4(query)) {
            var k = 0
            while (k < uc4ContributionCount) {
              val node = uc4Node(k, group)
              val watt = uc4Watt(
                k,
                input.totalMilliW(i),
                input.sub1Watt(i),
                input.sub2Watt(i),
                input.sub3Watt(i)
              )
              val contribution =
                region.alloc(new TrustedContribution(i, node, uc4Circuit(k), watt, contributionHead))
              contributionHead = contribution
              val slot = uc4Slot(k, group)
              sums(slot) += contribution.watt.toLong
              counts(slot) += 1
              checksum = fold(
                checksum,
                contribution.minute,
                contribution.group,
                contribution.circuit,
                contribution.watt.toLong
              )
              k += 1
            }
          }
          i += 1
        }
        retainedAnchorSink ^= {
          val a = if (measurementHead == null) 0L else measurementHead.totalMilliW.toLong
          val b = if (contributionHead == null) 0L else contributionHead.watt.toLong
          a ^ (b << 5)
        }
        outputs += closeEpoch(sums, counts)
        index = end
      }
    } finally {
      region.close()
    }
    checksumSink = checksum
    RunOutcome(checksum ^ checksumState(sums, counts), outputs)
  }

  private def runCheckedEpochStreamingHandle(query: String): RunOutcome = {
    val cfg = TheodolitePowerRegionConfig
    val input = loadedInput
    val sums = new Array[Long](cfg.groupCount * queryOutputMultiplier(query))
    val counts = new Array[Int](sums.length)
    var checksum = 0L
    var outputs = 0L

    RiftRegion.streamingOpenHandle {
      val reader = BenchmarkInputSupport.openByteLines(cfg.input)
      var index = 0
      try {
        reader.readLine() // header
        var length = reader.readLine()
        while (length >= 0 && index < input.records) {
          var anchor = 0L
          var inEpoch = 0
          RiftRegion.resetOpenHandle { region ?=>
            final class CheckedMeasurement(
                val minute: Int,
                val group: Int,
                val totalMilliW: Int,
                val voltageDeci: Int,
                val next: CheckedMeasurement^{region}
            )

            final class CheckedContribution(
                val minute: Int,
                val group: Int,
                val circuit: Int,
                val watt: Int,
                val next: CheckedContribution^{region}
            )

            var measurementHead: CheckedMeasurement^{region} = null
            var contributionHead: CheckedContribution^{region} = null
            while (
              length >= 0 && index < input.records && inEpoch < cfg.recordsPerEpoch
            ) {
              if (parseCurrentLine(reader.bytes, length)) {
                val group = groupFor(index)
                val measurement = RiftAllocator.allocateOpenHandle(
                  region,
                  new CheckedMeasurement(
                    index,
                    group,
                    parsedTotalMilliW,
                    parsedVoltageDeci,
                    measurementHead
                  )
                )
                measurementHead = measurement
                sums(group) += measurement.totalMilliW.toLong
                counts(group) += 1
                checksum = fold(
                  checksum,
                  measurement.minute,
                  measurement.group,
                  measurement.voltageDeci,
                  measurement.totalMilliW.toLong
                )
                if (query == "q2-hierarchical") {
                  val base = cfg.groupCount
                  val c1 = RiftAllocator.allocateOpenHandle(
                    region,
                    new CheckedContribution(
                      index,
                      group,
                      1,
                      parsedSub1Watt,
                      contributionHead
                    )
                  )
                  contributionHead = c1
                  sums(base + group) += c1.watt.toLong
                  counts(base + group) += 1
                  val c2 = RiftAllocator.allocateOpenHandle(
                    region,
                    new CheckedContribution(
                      index,
                      group,
                      2,
                      parsedSub2Watt,
                      contributionHead
                    )
                  )
                  contributionHead = c2
                  sums(base * 2 + group) += c2.watt.toLong
                  counts(base * 2 + group) += 1
                  val c3 = RiftAllocator.allocateOpenHandle(
                    region,
                    new CheckedContribution(
                      index,
                      group,
                      3,
                      parsedSub3Watt,
                      contributionHead
                    )
                  )
                  contributionHead = c3
                  sums(base * 3 + group) += c3.watt.toLong
                  counts(base * 3 + group) += 1
                  checksum = fold(
                    checksum,
                    c1.minute,
                    c2.watt,
                    c3.watt,
                    c1.watt.toLong
                  )
                } else if (isRetainedUc4(query)) {
                  var k = 0
                  while (k < uc4ContributionCount) {
                    val node = uc4Node(k, group)
                    val watt = uc4Watt(
                      k,
                      parsedTotalMilliW,
                      parsedSub1Watt,
                      parsedSub2Watt,
                      parsedSub3Watt
                    )
                    val contribution = RiftAllocator.allocateOpenHandle(
                      region,
                      new CheckedContribution(
                        index,
                        node,
                        uc4Circuit(k),
                        watt,
                        contributionHead
                      )
                    )
                    contributionHead = contribution
                    val slot = uc4Slot(k, group)
                    sums(slot) += contribution.watt.toLong
                    counts(slot) += 1
                    checksum = fold(
                      checksum,
                      contribution.minute,
                      contribution.group,
                      contribution.circuit,
                      contribution.watt.toLong
                    )
                    k += 1
                  }
                }
                index += 1
                inEpoch += 1
              }
              length = reader.readLine()
            }
            val a =
              if (measurementHead == null) 0L else measurementHead.totalMilliW.toLong
            val b =
              if (contributionHead == null) 0L else contributionHead.watt.toLong
            anchor = a ^ (b << 5)
          }
          if (inEpoch > 0) {
            retainedAnchorSink ^= anchor
            outputs += closeEpoch(sums, counts)
          }
        }
      } finally {
        reader.close()
      }
    }

    checksumSink = checksum
    RunOutcome(checksum ^ checksumState(sums, counts), outputs)
  }

  private def runCheckedEpochStreaming(
      query: String,
      safeZoneBackend: Boolean
  ): RunOutcome = {
    val cfg = TheodolitePowerRegionConfig
    val input = loadedInput
    val sums = new Array[Long](cfg.groupCount * queryOutputMultiplier(query))
    val counts = new Array[Int](sums.length)
    var checksum = 0L
    var outputs = 0L

    def run()(using stream: RiftRegion.StreamingRegion^): Unit = {
      val reader = BenchmarkInputSupport.openByteLines(cfg.input)
      var index = 0
      try {
        reader.readLine() // header
        var length = reader.readLine()
        while (length >= 0 && index < input.records) {
          var anchor = 0L
          var inEpoch = 0
          RiftRegion.epoch { region ?=>
            final class CheckedMeasurement(
                val minute: Int,
                val group: Int,
                val totalMilliW: Int,
                val voltageDeci: Int,
                val next: CheckedMeasurement^{region}
            )

            final class CheckedContribution(
                val minute: Int,
                val group: Int,
                val circuit: Int,
                val watt: Int,
                val next: CheckedContribution^{region}
            )

            var measurementHead: CheckedMeasurement^{region} = null
            var contributionHead: CheckedContribution^{region} = null
            while (
              length >= 0 && index < input.records && inEpoch < cfg.recordsPerEpoch
            ) {
              if (parseCurrentLine(reader.bytes, length)) {
                val group = groupFor(index)
                val measurement = RiftRegion.allocOpen(
                  new CheckedMeasurement(
                    index,
                    group,
                    parsedTotalMilliW,
                    parsedVoltageDeci,
                    measurementHead
                  )
                )
                measurementHead = measurement
                sums(group) += measurement.totalMilliW.toLong
                counts(group) += 1
                checksum = fold(
                  checksum,
                  measurement.minute,
                  measurement.group,
                  measurement.voltageDeci,
                  measurement.totalMilliW.toLong
                )
                if (query == "q2-hierarchical") {
                  val base = cfg.groupCount
                  val c1 = RiftRegion.allocOpen(
                    new CheckedContribution(
                      index,
                      group,
                      1,
                      parsedSub1Watt,
                      contributionHead
                    )
                  )
                  contributionHead = c1
                  sums(base + group) += c1.watt.toLong
                  counts(base + group) += 1
                  val c2 = RiftRegion.allocOpen(
                    new CheckedContribution(
                      index,
                      group,
                      2,
                      parsedSub2Watt,
                      contributionHead
                    )
                  )
                  contributionHead = c2
                  sums(base * 2 + group) += c2.watt.toLong
                  counts(base * 2 + group) += 1
                  val c3 = RiftRegion.allocOpen(
                    new CheckedContribution(
                      index,
                      group,
                      3,
                      parsedSub3Watt,
                      contributionHead
                    )
                  )
                  contributionHead = c3
                  sums(base * 3 + group) += c3.watt.toLong
                  counts(base * 3 + group) += 1
                  checksum = fold(
                    checksum,
                    c1.minute,
                    c2.watt,
                    c3.watt,
                    c1.watt.toLong
                  )
                } else if (isRetainedUc4(query)) {
                  var k = 0
                  while (k < uc4ContributionCount) {
                    val node = uc4Node(k, group)
                    val watt = uc4Watt(
                      k,
                      parsedTotalMilliW,
                      parsedSub1Watt,
                      parsedSub2Watt,
                      parsedSub3Watt
                    )
                    val contribution = RiftRegion.allocOpen(
                      new CheckedContribution(
                        index,
                        node,
                        uc4Circuit(k),
                        watt,
                        contributionHead
                      )
                    )
                    contributionHead = contribution
                    val slot = uc4Slot(k, group)
                    sums(slot) += contribution.watt.toLong
                    counts(slot) += 1
                    checksum = fold(
                      checksum,
                      contribution.minute,
                      contribution.group,
                      contribution.circuit,
                      contribution.watt.toLong
                    )
                    k += 1
                  }
                }
                index += 1
                inEpoch += 1
              }
              length = reader.readLine()
            }
            val a =
              if (measurementHead == null) 0L else measurementHead.totalMilliW.toLong
            val b =
              if (contributionHead == null) 0L else contributionHead.watt.toLong
            anchor = a ^ (b << 5)
          }
          if (inEpoch > 0) {
            retainedAnchorSink ^= anchor
            outputs += closeEpoch(sums, counts)
          }
        }
      } finally {
        reader.close()
      }
    }

    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> run() }
    else RiftRegion.streaming { stream ?=> run() }
    checksumSink = checksum
    RunOutcome(checksum ^ checksumState(sums, counts), outputs)
  }

  private def runCheckedEpochHandle(query: String): RunOutcome = {
    val cfg = TheodolitePowerRegionConfig
    if (cfg.streamingInput) return runCheckedEpochStreamingHandle(query)

    val input = loadedInput
    val sums = new Array[Long](cfg.groupCount * queryOutputMultiplier(query))
    val counts = new Array[Int](sums.length)
    var checksum = 0L
    var outputs = 0L

    RiftRegion.streamingOpenHandle {
      var index = 0
      while (index < input.records) {
        val end = math.min(input.records, index + cfg.recordsPerEpoch)
        var anchor = 0L
        RiftRegion.resetOpenHandle { region ?=>
          final class CheckedMeasurement(
              val minute: Int,
              val group: Int,
              val totalMilliW: Int,
              val voltageDeci: Int,
              val next: CheckedMeasurement^{region}
          )

          final class CheckedContribution(
              val minute: Int,
              val group: Int,
              val circuit: Int,
              val watt: Int,
              val next: CheckedContribution^{region}
          )

          var measurementHead: CheckedMeasurement^{region} = null
          var contributionHead: CheckedContribution^{region} = null
          var i = index
          while (i < end) {
            val group = groupFor(i)
            val measurement = RiftAllocator.allocateOpenHandle(
              region,
              new CheckedMeasurement(
                i,
                group,
                input.totalMilliW(i),
                input.voltageDeci(i),
                measurementHead
              )
            )
            measurementHead = measurement
            sums(group) += measurement.totalMilliW.toLong
            counts(group) += 1
            checksum = fold(
              checksum,
              measurement.minute,
              measurement.group,
              measurement.voltageDeci,
              measurement.totalMilliW.toLong
            )
            if (query == "q2-hierarchical") {
              val base = cfg.groupCount
              val c1 = RiftAllocator.allocateOpenHandle(
                region,
                new CheckedContribution(
                  i,
                  group,
                  1,
                  input.sub1Watt(i),
                  contributionHead
                )
              )
              contributionHead = c1
              sums(base + group) += c1.watt.toLong
              counts(base + group) += 1
              val c2 = RiftAllocator.allocateOpenHandle(
                region,
                new CheckedContribution(
                  i,
                  group,
                  2,
                  input.sub2Watt(i),
                  contributionHead
                )
              )
              contributionHead = c2
              sums(base * 2 + group) += c2.watt.toLong
              counts(base * 2 + group) += 1
              val c3 = RiftAllocator.allocateOpenHandle(
                region,
                new CheckedContribution(
                  i,
                  group,
                  3,
                  input.sub3Watt(i),
                  contributionHead
                )
              )
              contributionHead = c3
              sums(base * 3 + group) += c3.watt.toLong
              counts(base * 3 + group) += 1
              checksum = fold(checksum, c1.minute, c2.watt, c3.watt, c1.watt.toLong)
            } else if (isRetainedUc4(query)) {
              var k = 0
              while (k < uc4ContributionCount) {
                val node = uc4Node(k, group)
                val watt = uc4Watt(
                  k,
                  input.totalMilliW(i),
                  input.sub1Watt(i),
                  input.sub2Watt(i),
                  input.sub3Watt(i)
                )
                val contribution = RiftAllocator.allocateOpenHandle(
                  region,
                  new CheckedContribution(i, node, uc4Circuit(k), watt, contributionHead)
                )
                contributionHead = contribution
                val slot = uc4Slot(k, group)
                sums(slot) += contribution.watt.toLong
                counts(slot) += 1
                checksum = fold(
                  checksum,
                  contribution.minute,
                  contribution.group,
                  contribution.circuit,
                  contribution.watt.toLong
                )
                k += 1
              }
            }
            i += 1
          }
          val a = if (measurementHead == null) 0L else measurementHead.totalMilliW.toLong
          val b = if (contributionHead == null) 0L else contributionHead.watt.toLong
          anchor = a ^ (b << 5)
        }
        retainedAnchorSink ^= anchor
        outputs += closeEpoch(sums, counts)
        index = end
      }
    }

    checksumSink = checksum
    RunOutcome(checksum ^ checksumState(sums, counts), outputs)
  }

  private def runCheckedEpoch(
      query: String,
      safeZoneBackend: Boolean,
      useHandle: Boolean = true
  ): RunOutcome = {
    val cfg = TheodolitePowerRegionConfig
    if (!safeZoneBackend && useHandle) return runCheckedEpochHandle(query)
    if (cfg.streamingInput)
      return runCheckedEpochStreaming(query, safeZoneBackend)
    val input = loadedInput
    val sums = new Array[Long](cfg.groupCount * queryOutputMultiplier(query))
    val counts = new Array[Int](sums.length)
    var checksum = 0L
    var outputs = 0L

    def run()(using stream: RiftRegion.StreamingRegion^): Unit = {
      var index = 0
      while (index < input.records) {
        val end = math.min(input.records, index + cfg.recordsPerEpoch)
        var anchor = 0L
        RiftRegion.epoch { region ?=>
          final class CheckedMeasurement(
              val minute: Int,
              val group: Int,
              val totalMilliW: Int,
              val voltageDeci: Int,
              val next: CheckedMeasurement^{region}
          )

          final class CheckedContribution(
              val minute: Int,
              val group: Int,
              val circuit: Int,
              val watt: Int,
              val next: CheckedContribution^{region}
          )

          var measurementHead: CheckedMeasurement^{region} = null
          var contributionHead: CheckedContribution^{region} = null
          var i = index
          while (i < end) {
            val group = groupFor(i)
            val measurement = RiftRegion.allocOpen(
              new CheckedMeasurement(
                i,
                group,
                input.totalMilliW(i),
                input.voltageDeci(i),
                measurementHead
              )
            )
            measurementHead = measurement
            sums(group) += measurement.totalMilliW.toLong
            counts(group) += 1
            checksum = fold(
              checksum,
              measurement.minute,
              measurement.group,
              measurement.voltageDeci,
              measurement.totalMilliW.toLong
            )
            if (query == "q2-hierarchical") {
              val base = cfg.groupCount
              val c1 = RiftRegion.allocOpen(
                new CheckedContribution(
                  i,
                  group,
                  1,
                  input.sub1Watt(i),
                  contributionHead
                )
              )
              contributionHead = c1
              sums(base + group) += c1.watt.toLong
              counts(base + group) += 1
              val c2 = RiftRegion.allocOpen(
                new CheckedContribution(
                  i,
                  group,
                  2,
                  input.sub2Watt(i),
                  contributionHead
                )
              )
              contributionHead = c2
              sums(base * 2 + group) += c2.watt.toLong
              counts(base * 2 + group) += 1
              val c3 = RiftRegion.allocOpen(
                new CheckedContribution(
                  i,
                  group,
                  3,
                  input.sub3Watt(i),
                  contributionHead
                )
              )
              contributionHead = c3
              sums(base * 3 + group) += c3.watt.toLong
              counts(base * 3 + group) += 1
              checksum = fold(checksum, c1.minute, c2.watt, c3.watt, c1.watt.toLong)
            } else if (isRetainedUc4(query)) {
              var k = 0
              while (k < uc4ContributionCount) {
                val node = uc4Node(k, group)
                val watt = uc4Watt(
                  k,
                  input.totalMilliW(i),
                  input.sub1Watt(i),
                  input.sub2Watt(i),
                  input.sub3Watt(i)
                )
                val contribution = RiftRegion.allocOpen(
                  new CheckedContribution(i, node, uc4Circuit(k), watt, contributionHead)
                )
                contributionHead = contribution
                val slot = uc4Slot(k, group)
                sums(slot) += contribution.watt.toLong
                counts(slot) += 1
                checksum = fold(
                  checksum,
                  contribution.minute,
                  contribution.group,
                  contribution.circuit,
                  contribution.watt.toLong
                )
                k += 1
              }
            }
            i += 1
          }
          val a = if (measurementHead == null) 0L else measurementHead.totalMilliW.toLong
          val b = if (contributionHead == null) 0L else contributionHead.watt.toLong
          anchor = a ^ (b << 5)
        }
        retainedAnchorSink ^= anchor
        outputs += closeEpoch(sums, counts)
        index = end
      }
    }

    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> run() }
    else RiftRegion.streaming { stream ?=> run() }
    checksumSink = checksum
    RunOutcome(checksum ^ checksumState(sums, counts), outputs)
  }

  private def closeEpoch(sums: Array[Long], counts: Array[Int]): Long = {
    var outputs = 0L
    var i = 0
    while (i < sums.length) {
      if (counts(i) != 0) outputs += 1L
      i += 1
    }
    outputs
  }

  private def checksumState(sums: Array[Long], counts: Array[Int]): Long = {
    var checksum = 0L
    var i = 0
    while (i < sums.length) {
      checksum = fold(checksum, i, counts(i), (sums(i) & 0x7fffffffL).toInt, sums(i))
      i += 1
    }
    checksum
  }

  private def median(values: Array[Double]): Double = {
    val copy = values.clone()
    java.util.Arrays.sort(copy)
    copy(copy.length / 2)
  }

  private def medianLong(values: Array[Long]): Long = {
    val copy = values.clone()
    java.util.Arrays.sort(copy)
    copy(copy.length / 2)
  }

  private def maxLong(values: Array[Long]): Long = {
    var max = 0L
    var i = 0
    while (i < values.length) {
      if (values(i) > max) max = values(i)
      i += 1
    }
    max
  }

  private def runMode(mode: String, query: String): RunOutcome =
    canonicalMode(mode) match {
      case "heap"                 => runHeap(query)
      case "safezone"             => runSafeZone(query)
      case "rift-streaming"       => runRiftTrusted(query)
      case "checked-epoch-stream" =>
        runCheckedEpoch(query, safeZoneBackend = false, useHandle = true)
      case "checked-epoch-stream-open-handle" =>
        runCheckedEpoch(query, safeZoneBackend = false, useHandle = true)
      case "checked-epoch-stream-legacy" =>
        runCheckedEpoch(query, safeZoneBackend = false, useHandle = false)
      case "checked-epoch-scoped" => runCheckedEpoch(query, safeZoneBackend = true)
      case other =>
        throw new IllegalArgumentException(
          s"unknown Theodolite power mode '$other'"
        )
    }

  def runBenchmark(mode: String, query: String): Unit = {
    val cfg = TheodolitePowerRegionConfig
    val input = loadedInput
    val canonical = canonicalMode(mode)
    val usesRift = usesRiftRuntime(mode)

    if (cfg.finalClean) {
      var run = 0
      var expected: RunOutcome = null
      while (run < cfg.benchmarkRuns) {
        val outcome = runMode(mode, query)
        if (run == 0) expected = outcome
        else if (outcome != expected)
          throw new IllegalStateException(
            s"final-clean mismatch query=$query mode=$mode expected=$expected actual=$outcome"
          )
        run += 1
      }
      println(
        s"RESULT name=theodolite-power-$query-$canonical " +
          s"measurement_level=L1 final_clean=1 query=$query mode=$canonical " +
          s"input=${input.label} input_mode=${cfg.inputMode} records=${input.records} " +
          s"records_per_epoch=${cfg.recordsPerEpoch} groups=${cfg.groupCount} " +
          s"runs=${cfg.benchmarkRuns} checksum=${expected.checksum} " +
          s"output_count=${expected.outputCount}"
      )
      return
    }

    val expected = runHeap(query)
    System.gc()

    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val outcome = runMode(mode, query)
      if (outcome != expected)
        throw new IllegalStateException(
          s"warmup mismatch query=$query mode=$mode expected=$expected actual=$outcome"
        )
      System.gc()
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

    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val outcome = runMode(mode, query)
      val end = System.nanoTime()
      val endRuntime = RuntimeSample.capture(usesRift)
      val runtime = RuntimeSample.since(startRuntime, endRuntime)

      if (outcome != expected)
        throw new IllegalStateException(
          s"checksum mismatch query=$query mode=$mode expected=$expected actual=$outcome"
        )

      elapsedMs(run) = (end - start) / 1000000.0
      gcNanos(run) = runtime.gcNanos
      gcCollections(run) = runtime.gcCollections
      riftOpNanos(run) = runtime.riftRegionOpNanos
      riftObjects(run) = runtime.riftAllocObjectTotal
      riftOpens(run) = runtime.riftRegionOpenTotal
      riftCloses(run) = runtime.riftRegionCloseTotal
      riftResets(run) = runtime.riftRegionResetTotal
      System.gc()
      run += 1
    }

    val medianMs = median(elapsedMs)
    val medianGcMs = medianLong(gcNanos).toDouble / 1000000.0
    val maxGcMs = maxLong(gcNanos).toDouble / 1000000.0
    val runsWithGc = gcNanos.count(_ > 0L)
    val maxGcCollections = maxLong(gcCollections)
    val medianRiftOpMs = medianLong(riftOpNanos).toDouble / 1000000.0
    val medianRiftObjects = medianLong(riftObjects)
    val medianRiftOpens = medianLong(riftOpens)
    val medianRiftCloses = medianLong(riftCloses)
    val medianRiftResets = medianLong(riftResets)

    println(
      f"RESULT name=theodolite-power-$query-$canonical " +
        f"query=$query mode=$canonical input=${input.label} input_mode=${cfg.inputMode} " +
        f"records=${input.records}%d records_per_epoch=${cfg.recordsPerEpoch}%d " +
        f"groups=${cfg.groupCount}%d median_ms=$medianMs%.3f " +
        f"median_gc_ms=$medianGcMs%.3f max_gc_ms=$maxGcMs%.3f " +
        f"runs_with_gc=$runsWithGc%d max_gc_collections=$maxGcCollections%d " +
        f"median_rift_op_ms=$medianRiftOpMs%.3f " +
        f"median_rift_alloc_object_total=$medianRiftObjects%d " +
        f"median_rift_open_total=$medianRiftOpens%d " +
        f"median_rift_close_total=$medianRiftCloses%d " +
        f"median_rift_reset_total=$medianRiftResets%d " +
        f"checksum=${expected.checksum}%d output_count=${expected.outputCount}%d"
    )
  }

  def printConfig(mode: String, query: String): Unit = {
    val cfg = TheodolitePowerRegionConfig
    val input = loadedInput
    println(
      s"CONFIG mode=$mode canonical_mode=${canonicalMode(mode)} query=$query " +
        s"input=${cfg.input} input_label=${input.label} input_mode=${cfg.inputMode} records=${input.records} " +
        s"records_per_epoch=${cfg.recordsPerEpoch} groups=${cfg.groupCount} " +
        s"runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} " +
        s"final_clean=${cfg.finalClean}"
    )
  }
}

object TheodolitePowerRegionMatrix {
  def main(args: Array[String]): Unit = {
    val mode = if (args.length > 0) args(0) else "heap-immix"
    val query = if (args.length > 1) args(1) else "q1-downsample"

    TheodolitePowerRegionMatrixHelpers.validateMode(mode)
    TheodolitePowerRegionMatrixHelpers.validateQuery(query)
    TheodolitePowerRegionMatrixHelpers.printConfig(mode, query)
    TheodolitePowerRegionMatrixHelpers.runBenchmark(mode, query)
  }
}
