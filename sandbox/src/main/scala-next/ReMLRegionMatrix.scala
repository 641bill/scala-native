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

object ReMLRegionConfig {
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

  val fibN: Int = envInt("REML_FIB_N", 37)
  val takX: Int = envInt("REML_TAK_X", 18)
  val takY: Int = envInt("REML_TAK_Y", 12)
  val takZ: Int = envInt("REML_TAK_Z", 6)
  val mandelSize: Int = envInt("REML_MANDEL_SIZE", 160)
  val mandelIterations: Int = envInt("REML_MANDEL_ITERATIONS", 64)
  val listSize: Int = envInt("REML_LIST_SIZE", 100000)
  val lifeSize: Int = envInt("REML_LIFE_SIZE", 128)
  val lifeSteps: Int = envInt("REML_LIFE_STEPS", 32)
  val fftSize: Int = envInt("REML_FFT_SIZE", 16384)
  val ratioCount: Int = envInt("REML_RATIO_COUNT", 500000)
  val warmupRuns: Int = envNonNegativeInt("REML_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("REML_BENCHMARK_RUNS", 3)

  private def truthy(value: String): Boolean =
    value == "1" || value.equalsIgnoreCase("true") ||
      value.equalsIgnoreCase("yes")

  val finalClean: Boolean =
    sys.env.get("RIFT_FINAL_CLEAN").exists(truthy) ||
      sys.env.get("RIFT_EVAL_MEASUREMENT_LEVEL").exists(_.equalsIgnoreCase("L1"))
}

object ReMLRegionMatrixHelpers {
  @volatile private var checksumSink = 0L

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
        riftRegionOpNanos =
          delta(end.riftRegionOpNanos, start.riftRegionOpNanos),
        riftSlowAllocNanos =
          delta(end.riftSlowAllocNanos, start.riftSlowAllocNanos)
      )
  }

  private final class HeapNode(val value: Int, val next: HeapNode)
  private final class TrustedNode(val value: Int, val next: TrustedNode)
  private final class HeapComplex(val re: Double, val im: Double)
  private final class TrustedComplex(val re: Double, val im: Double)
  private final class HeapRatio(val n: Int, val d: Int)
  private final class TrustedRatio(val n: Int, val d: Int)

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

  private def mix(value: Int): Int = {
    var x = value
    x ^= x << 13
    x ^= x >>> 17
    x ^= x << 5
    x & 0x7fffffff
  }

  private def fold(checksum: Long, value: Long): Long =
    (checksum ^ value) * 1099511628211L

  private def fib(n: Int): Int =
    if (n < 2) n else fib(n - 1) + fib(n - 2)

  private def tak(x: Int, y: Int, z: Int): Int =
    if (x <= y) z
    else tak(tak(x - 1, y, z), tak(y - 1, z, x), tak(z - 1, x, y))

  private def runFib(): Long =
    fib(ReMLRegionConfig.fibN).toLong

  private def runTak(): Long =
    tak(ReMLRegionConfig.takX, ReMLRegionConfig.takY, ReMLRegionConfig.takZ).toLong

  private def runMandel(): Long = {
    val cfg = ReMLRegionConfig
    var checksum = 0L
    var y = 0
    while (y < cfg.mandelSize) {
      val ci = (y.toDouble / cfg.mandelSize.toDouble) * 2.0 - 1.0
      var x = 0
      while (x < cfg.mandelSize) {
        val cr = (x.toDouble / cfg.mandelSize.toDouble) * 3.0 - 2.0
        var zr = 0.0
        var zi = 0.0
        var iter = 0
        while (iter < cfg.mandelIterations && zr * zr + zi * zi <= 4.0) {
          val nextR = zr * zr - zi * zi + cr
          zi = 2.0 * zr * zi + ci
          zr = nextR
          iter += 1
        }
        checksum = fold(checksum, iter.toLong + x.toLong + y.toLong)
        x += 1
      }
      y += 1
    }
    checksum
  }

  private def heapListChecksum(head: HeapNode): Long = {
    var cursor = head
    var count = 0
    val values = new Array[Int](ReMLRegionConfig.listSize)
    while (cursor != null) {
      values(count) = cursor.value
      cursor = cursor.next
      count += 1
    }
    scala.util.Sorting.quickSort(values)
    var checksum = 0L
    var i = 0
    while (i < values.length) {
      checksum = fold(checksum, values(i).toLong)
      i += 1
    }
    checksum
  }

  private def trustedListChecksum(head: TrustedNode): Long = {
    var cursor = head
    var count = 0
    val values = new Array[Int](ReMLRegionConfig.listSize)
    while (cursor != null) {
      values(count) = cursor.value
      cursor = cursor.next
      count += 1
    }
    scala.util.Sorting.quickSort(values)
    var checksum = 0L
    var i = 0
    while (i < values.length) {
      checksum = fold(checksum, values(i).toLong)
      i += 1
    }
    checksum
  }

  private def runHeapMsort(reverse: Boolean): Long = {
    val cfg = ReMLRegionConfig
    var head: HeapNode = null
    var i = 0
    while (i < cfg.listSize) {
      val index = if (reverse) cfg.listSize - i else i
      head = new HeapNode(mix(index * 1103515245 + 12345), head)
      i += 1
    }
    heapListChecksum(head)
  }

  private def runTrustedMsort(kind: Int, reverse: Boolean): Long = {
    val cfg = ReMLRegionConfig
    val region = RiftRegion.open(kind)
    var head: TrustedNode = null
    var i = 0
    try {
      while (i < cfg.listSize) {
        val index = if (reverse) cfg.listSize - i else i
        head = region.alloc(
          new TrustedNode(mix(index * 1103515245 + 12345), head)
        )
        i += 1
      }
      trustedListChecksum(head)
    } finally region.close()
  }

  private def runSafeZoneMsort(reverse: Boolean): Long =
    SafeZone { sz ?=>
      final class SZNode(val value: Int, val next: SZNode^{sz})
      val cfg = ReMLRegionConfig
      var head: SZNode^{sz} = null
      var i = 0
      while (i < cfg.listSize) {
        val index = if (reverse) cfg.listSize - i else i
        head = SafeZoneAllocator.allocate(
          sz,
          new SZNode(mix(index * 1103515245 + 12345), head)
        )
        i += 1
      }
      var cursor = head
      var count = 0
      val values = new Array[Int](cfg.listSize)
      while (cursor != null) {
        values(count) = cursor.value
        cursor = cursor.next
        count += 1
      }
      scala.util.Sorting.quickSort(values)
      var checksum = 0L
      i = 0
      while (i < values.length) {
        checksum = fold(checksum, values(i).toLong)
        i += 1
      }
      checksum
    }

  private def runCheckedMsort(reverse: Boolean, safeZoneBackend: Boolean): Long = {
    def body()(using region: RiftRegion.StreamingRegion^): Long = {
      final class Node(val value: Int, val next: Node^{region})
      val cfg = ReMLRegionConfig
      var head: Node^{region} = null
      var i = 0
      while (i < cfg.listSize) {
        val index = if (reverse) cfg.listSize - i else i
        head = RiftRegion.alloc(new Node(mix(index * 1103515245 + 12345), head))
        i += 1
      }
      var cursor = head
      var count = 0
      val values = new Array[Int](cfg.listSize)
      while (cursor != null) {
        values(count) = cursor.value
        cursor = cursor.next
        count += 1
      }
      scala.util.Sorting.quickSort(values)
      var checksum = 0L
      i = 0
      while (i < values.length) {
        checksum = fold(checksum, values(i).toLong)
        i += 1
      }
      checksum
    }
    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> body() }
    else RiftRegion.streaming { stream ?=> body() }
  }

  private def runHeapLife(): Long = {
    val cfg = ReMLRegionConfig
    var current = new Array[Byte](cfg.lifeSize * cfg.lifeSize)
    var next = new Array[Byte](cfg.lifeSize * cfg.lifeSize)
    var i = 0
    while (i < current.length) {
      current(i) = (mix(i) & 1).toByte
      i += 1
    }
    var step = 0
    while (step < cfg.lifeSteps) {
      var y = 0
      while (y < cfg.lifeSize) {
        var x = 0
        while (x < cfg.lifeSize) {
          var neighbors = 0
          var dy = -1
          while (dy <= 1) {
            var dx = -1
            while (dx <= 1) {
              if (dx != 0 || dy != 0) {
                val nx = (x + dx + cfg.lifeSize) % cfg.lifeSize
                val ny = (y + dy + cfg.lifeSize) % cfg.lifeSize
                neighbors += current(ny * cfg.lifeSize + nx)
              }
              dx += 1
            }
            dy += 1
          }
          val alive = current(y * cfg.lifeSize + x) != 0
          next(y * cfg.lifeSize + x) =
            (if (neighbors == 3 || (alive && neighbors == 2)) 1 else 0).toByte
          x += 1
        }
        y += 1
      }
      val tmp = current
      current = next
      next = tmp
      step += 1
    }
    var checksum = 0L
    i = 0
    while (i < current.length) {
      checksum = fold(checksum, current(i).toLong)
      i += 1
    }
    checksum
  }

  private def runHeapFft(): Long = {
    val cfg = ReMLRegionConfig
    val values = new Array[HeapComplex](cfg.fftSize)
    var i = 0
    while (i < values.length) {
      values(i) = new HeapComplex(math.sin(i.toDouble), math.cos(i.toDouble))
      i += 1
    }
    var span = 1
    while (span < values.length) {
      i = 0
      while (i + span < values.length) {
        val a = values(i)
        val b = values(i + span)
        values(i) = new HeapComplex(a.re + b.re, a.im + b.im)
        values(i + span) = new HeapComplex(a.re - b.re, a.im - b.im)
        i += span << 1
      }
      span <<= 1
    }
    var checksum = 0L
    i = 0
    while (i < values.length) {
      val c = values(i)
      checksum = fold(checksum, java.lang.Double.doubleToLongBits(c.re + c.im))
      i += math.max(1, values.length / 256)
    }
    checksum
  }

  private def runTrustedFft(kind: Int): Long = {
    val cfg = ReMLRegionConfig
    val region = RiftRegion.open(kind)
    val values = new Array[TrustedComplex](cfg.fftSize)
    var i = 0
    try {
      while (i < values.length) {
        values(i) = region.alloc(
          new TrustedComplex(math.sin(i.toDouble), math.cos(i.toDouble))
        )
        i += 1
      }
      var span = 1
      while (span < values.length) {
        i = 0
        while (i + span < values.length) {
          val a = values(i)
          val b = values(i + span)
          values(i) = region.alloc(new TrustedComplex(a.re + b.re, a.im + b.im))
          values(i + span) =
            region.alloc(new TrustedComplex(a.re - b.re, a.im - b.im))
          i += span << 1
        }
        span <<= 1
      }
      var checksum = 0L
      i = 0
      while (i < values.length) {
        val c = values(i)
        checksum =
          fold(checksum, java.lang.Double.doubleToLongBits(c.re + c.im))
        i += math.max(1, values.length / 256)
      }
      checksum
    } finally region.close()
  }

  private def runSafeZoneFft(): Long =
    SafeZone { sz ?=>
      final class SZComplex(val re: Double, val im: Double)
      val cfg = ReMLRegionConfig
      val values = new Array[SZComplex^{sz}](cfg.fftSize)
      var i = 0
      while (i < values.length) {
        values(i) = SafeZoneAllocator.allocate(
          sz,
          new SZComplex(math.sin(i.toDouble), math.cos(i.toDouble))
        )
        i += 1
      }
      var span = 1
      while (span < values.length) {
        i = 0
        while (i + span < values.length) {
          val a = values(i)
          val b = values(i + span)
          values(i) =
            SafeZoneAllocator.allocate(sz, new SZComplex(a.re + b.re, a.im + b.im))
          values(i + span) =
            SafeZoneAllocator.allocate(sz, new SZComplex(a.re - b.re, a.im - b.im))
          i += span << 1
        }
        span <<= 1
      }
      var checksum = 0L
      i = 0
      while (i < values.length) {
        val c = values(i)
        checksum =
          fold(checksum, java.lang.Double.doubleToLongBits(c.re + c.im))
        i += math.max(1, values.length / 256)
      }
      checksum
    }

  private def runCheckedFft(safeZoneBackend: Boolean): Long = {
    def body()(using region: RiftRegion.StreamingRegion^): Long = {
      final class Complex(val re: Double, val im: Double)
      val cfg = ReMLRegionConfig
      val values: Array[Complex^{region}]^{region} =
        RiftRegion.alloc(new Array[Complex^{region}](cfg.fftSize))
      var i = 0
      while (i < values.length) {
        values(i) =
          RiftRegion.alloc(new Complex(math.sin(i.toDouble), math.cos(i.toDouble)))
        i += 1
      }
      var span = 1
      while (span < values.length) {
        i = 0
        while (i + span < values.length) {
          val a = values(i)
          val b = values(i + span)
          values(i) = RiftRegion.alloc(new Complex(a.re + b.re, a.im + b.im))
          values(i + span) =
            RiftRegion.alloc(new Complex(a.re - b.re, a.im - b.im))
          i += span << 1
        }
        span <<= 1
      }
      var checksum = 0L
      i = 0
      while (i < values.length) {
        val c = values(i)
        checksum =
          fold(checksum, java.lang.Double.doubleToLongBits(c.re + c.im))
        i += math.max(1, values.length / 256)
      }
      checksum
    }
    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> body() }
    else RiftRegion.streaming { stream ?=> body() }
  }

  private def gcd(a0: Int, b0: Int): Int = {
    var a = math.abs(a0)
    var b = math.abs(b0)
    while (b != 0) {
      val t = a % b
      a = b
      b = t
    }
    if (a == 0) 1 else a
  }

  private def runHeapRatio(): Long = {
    val cfg = ReMLRegionConfig
    val values = new Array[HeapRatio](cfg.ratioCount)
    var i = 1
    while (i <= cfg.ratioCount) {
      val n = mix(i) % 100000 + 1
      val d = mix(i + 17) % 99999 + 1
      val g = gcd(n, d)
      values(i - 1) = new HeapRatio(n / g, d / g)
      i += 1
    }
    var checksum = 0L
    i = 0
    while (i < values.length) {
      val r = values(i)
      checksum = fold(checksum, r.n.toLong * 65537L + r.d.toLong)
      i += 1
    }
    checksum
  }

  private def runTrustedRatio(kind: Int): Long = {
    val cfg = ReMLRegionConfig
    val region = RiftRegion.open(kind)
    val values = new Array[TrustedRatio](cfg.ratioCount)
    var checksum = 0L
    var i = 1
    try {
      while (i <= cfg.ratioCount) {
        val n = mix(i) % 100000 + 1
        val d = mix(i + 17) % 99999 + 1
        val g = gcd(n, d)
        values(i - 1) = region.alloc(new TrustedRatio(n / g, d / g))
        i += 1
      }
      i = 0
      while (i < values.length) {
        val r = values(i)
        checksum = fold(checksum, r.n.toLong * 65537L + r.d.toLong)
        i += 1
      }
      checksum
    } finally region.close()
  }

  private def runSafeZoneRatio(): Long =
    SafeZone { sz ?=>
      final class SZRatio(val n: Int, val d: Int)
      val cfg = ReMLRegionConfig
      val values = new Array[SZRatio^{sz}](cfg.ratioCount)
      var i = 1
      while (i <= cfg.ratioCount) {
        val n = mix(i) % 100000 + 1
        val d = mix(i + 17) % 99999 + 1
        val g = gcd(n, d)
        values(i - 1) = SafeZoneAllocator.allocate(sz, new SZRatio(n / g, d / g))
        i += 1
      }
      var checksum = 0L
      i = 0
      while (i < values.length) {
        val r = values(i)
        checksum = fold(checksum, r.n.toLong * 65537L + r.d.toLong)
        i += 1
      }
      checksum
    }

  private def runCheckedRatio(safeZoneBackend: Boolean): Long = {
    def body()(using region: RiftRegion.StreamingRegion^): Long = {
      final class Ratio(val n: Int, val d: Int)
      val cfg = ReMLRegionConfig
      val values: Array[Ratio^{region}]^{region} =
        RiftRegion.alloc(new Array[Ratio^{region}](cfg.ratioCount))
      var i = 1
      while (i <= cfg.ratioCount) {
        val n = mix(i) % 100000 + 1
        val d = mix(i + 17) % 99999 + 1
        val g = gcd(n, d)
        values(i - 1) = RiftRegion.alloc(new Ratio(n / g, d / g))
        i += 1
      }
      var checksum = 0L
      i = 0
      while (i < values.length) {
        val r = values(i)
        checksum = fold(checksum, r.n.toLong * 65537L + r.d.toLong)
        i += 1
      }
      checksum
    }
    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> body() }
    else RiftRegion.streaming { stream ?=> body() }
  }

  private def canonicalMode(mode: String): String =
    mode match {
      case "heap" | "heap-immix" | "gc-heap" => "gc-heap"
      case "safezone-improved" | "safezone-improved-32k" |
          "region-scoped-rooted" =>
        "region-scoped-rooted"
      case "safezone-rootless-32k" | "unsafezone-hp" |
          "region-scoped-rootless" =>
        "region-scoped-rootless"
      case "rift-hp" | "rift-trusted-hp" | "region-hp-rootless" =>
        "region-hp-rootless"
      case "rift-streaming" | "rift-trusted-streaming" |
          "region-stream-rootless" =>
        "region-stream-rootless"
      case "rift-checked" | "rift-checked-rift" | "checked-region-stream" =>
        "checked-region-stream"
      case "rift-checked-safezone-32k" |
          "rift-checked-safezone-improved-32k" | "checked-region-scoped" =>
        "checked-region-scoped"
      case other => throw new IllegalArgumentException(s"unknown ReML mode '$other'")
    }

  private def usesRiftStats(mode: String): Boolean =
    mode == "region-hp-rootless" ||
      mode == "region-stream-rootless" ||
      mode == "checked-region-stream"

  private def runWorkload(workload: String, mode: String): Long =
    workload match {
      case "fib37" => runFib()
      case "tak" => runTak()
      case "mandel" => runMandel()
      case "life" => runHeapLife()
      case "msort" =>
        mode match {
          case "gc-heap" => runHeapMsort(reverse = false)
          case "region-scoped-rooted" | "region-scoped-rootless" =>
            runSafeZoneMsort(reverse = false)
          case "region-hp-rootless" => runTrustedMsort(RiftRegion.HPZone, false)
          case "region-stream-rootless" =>
            runTrustedMsort(RiftRegion.Streaming, false)
          case "checked-region-stream" => runCheckedMsort(false, false)
          case "checked-region-scoped" => runCheckedMsort(false, true)
        }
      case "msort-r" =>
        mode match {
          case "gc-heap" => runHeapMsort(reverse = true)
          case "region-scoped-rooted" | "region-scoped-rootless" =>
            runSafeZoneMsort(reverse = true)
          case "region-hp-rootless" => runTrustedMsort(RiftRegion.HPZone, true)
          case "region-stream-rootless" =>
            runTrustedMsort(RiftRegion.Streaming, true)
          case "checked-region-stream" => runCheckedMsort(true, false)
          case "checked-region-scoped" => runCheckedMsort(true, true)
        }
      case "fft" =>
        mode match {
          case "gc-heap" => runHeapFft()
          case "region-scoped-rooted" | "region-scoped-rootless" =>
            runSafeZoneFft()
          case "region-hp-rootless" => runTrustedFft(RiftRegion.HPZone)
          case "region-stream-rootless" => runTrustedFft(RiftRegion.Streaming)
          case "checked-region-stream" => runCheckedFft(false)
          case "checked-region-scoped" => runCheckedFft(true)
        }
      case "ratio" =>
        mode match {
          case "gc-heap" => runHeapRatio()
          case "region-scoped-rooted" | "region-scoped-rootless" =>
            runSafeZoneRatio()
          case "region-hp-rootless" => runTrustedRatio(RiftRegion.HPZone)
          case "region-stream-rootless" => runTrustedRatio(RiftRegion.Streaming)
          case "checked-region-stream" => runCheckedRatio(false)
          case "checked-region-scoped" => runCheckedRatio(true)
        }
      case other =>
        throw new IllegalArgumentException(s"unknown ReML workload '$other'")
    }

  def run(workloadArg: String, modeArg: String): Unit = {
    val workload = workloadArg
    val mode = canonicalMode(modeArg)
    val cfg = ReMLRegionConfig
    if (cfg.finalClean) {
      var run = 0
      var checksum = 0L
      while (run < cfg.benchmarkRuns) {
        val result = runWorkload(workload, mode)
        if (run == 0) checksum = result
        else if (result != checksum)
          throw new IllegalStateException(
            s"final-clean ReML mismatch workload=$workload mode=$mode first_checksum=$checksum actual=$result"
          )
        run += 1
      }
      checksumSink = checksum
      println(
        s"RESULT name=reml-region-$workload-$mode " +
          s"measurement_level=L1 final_clean=1 workload=$workload " +
          s"mode=$mode runs=${cfg.benchmarkRuns} checksum=$checksum"
      )
      return
    }

    val totalRuns = cfg.warmupRuns + cfg.benchmarkRuns
    val times = new Array[Double](cfg.benchmarkRuns)
    val gcTimes = new Array[Double](cfg.benchmarkRuns)
    val riftOpTimes = new Array[Double](cfg.benchmarkRuns)
    val slowAllocTimes = new Array[Double](cfg.benchmarkRuns)
    val gcCollections = new Array[Long](cfg.benchmarkRuns)
    val riftAllocObjects = new Array[Long](cfg.benchmarkRuns)
    val riftOpenTotal = new Array[Long](cfg.benchmarkRuns)
    val riftCloseTotal = new Array[Long](cfg.benchmarkRuns)
    val riftResetTotal = new Array[Long](cfg.benchmarkRuns)
    var checksum = 0L

    var run = 0
    while (run < totalRuns) {
      val measured = run >= cfg.warmupRuns
      if (usesRiftStats(mode)) RiftAllocator.Impl.statsReset()
      val before = RuntimeSample.capture(usesRiftStats(mode))
      val start = System.nanoTime()
      val result = runWorkload(workload, mode)
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
    val medianOpen = medianLong(riftOpenTotal)
    val medianClose = medianLong(riftCloseTotal)
    val medianReset = medianLong(riftResetTotal)

    checksumSink = checksum
    println(
      f"RESULT name=reml-region-$workload-$mode " +
        s"workload=$workload mode=$mode " +
        f"median_ms=$medianMs%.3f " +
        f"median_gc_ms=$medianGcMs%.3f " +
        f"max_gc_ms=$maxGcMs%.3f " +
        s"runs_with_gc=$runsWithGc " +
        f"median_rift_op_ms=$medianRiftOpMs%.3f " +
        f"median_rift_slow_alloc_ms=$medianSlowAllocMs%.3f " +
        s"median_rift_alloc_object_total=$medianRiftObjects " +
        s"median_rift_open_total=$medianOpen " +
        s"median_rift_close_total=$medianClose " +
        s"median_rift_reset_total=$medianReset " +
        s"checksum=$checksum"
    )
  }
}

object ReMLRegionMatrix {
  def main(args: Array[String]): Unit = {
    val workload = if (args.nonEmpty) args(0) else "fib37"
    val mode = if (args.length >= 2) args(1) else "gc-heap"
    ReMLRegionMatrixHelpers.run(workload, mode)
  }
}
