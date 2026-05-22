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
  val logicDepth: Int = envInt("REML_LOGIC_DEPTH", 11)
  val logicIterations: Int = envInt("REML_LOGIC_ITERATIONS", 256)
  val raySpheres: Int = envInt("REML_RAY_SPHERES", 128)
  val rayRays: Int = envInt("REML_RAY_RAYS", 10000)
  val tspPoints: Int = envInt("REML_TSP_POINTS", 384)
  val tspStarts: Int = envInt("REML_TSP_STARTS", 192)
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
  private final class HeapLogicNode(
      val kind: Int,
      val value: Int,
      val left: HeapLogicNode,
      val right: HeapLogicNode
  )
  private final class TrustedLogicNode(
      val kind: Int,
      val value: Int,
      val left: TrustedLogicNode,
      val right: TrustedLogicNode
  )
  private final class HeapSphere(
      val x: Double,
      val y: Double,
      val z: Double,
      val radius: Double
  )
  private final class TrustedSphere(
      val x: Double,
      val y: Double,
      val z: Double,
      val radius: Double
  )
  private final class HeapRay(
      val ox: Double,
      val oy: Double,
      val oz: Double,
      val dx: Double,
      val dy: Double,
      val dz: Double
  )
  private final class TrustedRay(
      val ox: Double,
      val oy: Double,
      val oz: Double,
      val dx: Double,
      val dy: Double,
      val dz: Double
  )
  private final class HeapHit(val sphere: Int, val t: Double)
  private final class TrustedHit(val sphere: Int, val t: Double)
  private final class HeapPoint(val x: Double, val y: Double)
  private final class TrustedPoint(val x: Double, val y: Double)
  private final class HeapTourNode(val point: Int, val next: HeapTourNode)
  private final class TrustedTourNode(val point: Int, val next: TrustedTourNode)

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

  private def runCheckedMsortInferred(reverse: Boolean): Long =
    RiftRegion.streaming { stream ?=>
      RiftRegion.epoch { region ?=>
        final class Node(val value: Int, val next: Node^{region})
        val cfg = ReMLRegionConfig
        var head: Node^{region} = null
        var i = 0
        while (i < cfg.listSize) {
          val index = if (reverse) cfg.listSize - i else i
          head = new Node(mix(index * 1103515245 + 12345), head)
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

  private def runCheckedFftInferred(): Long =
    RiftRegion.streaming { stream ?=>
      RiftRegion.epoch { region ?=>
        final class Complex(val re: Double, val im: Double)
        val cfg = ReMLRegionConfig
        val values: Array[Complex^{region}]^{region} =
          new Array[Complex^{region}](cfg.fftSize)
        var i = 0
        while (i < values.length) {
          values(i) = new Complex(math.sin(i.toDouble), math.cos(i.toDouble))
          i += 1
        }
        var span = 1
        while (span < values.length) {
          i = 0
          while (i + span < values.length) {
            val a = values(i)
            val b = values(i + span)
            values(i) = new Complex(a.re + b.re, a.im + b.im)
            values(i + span) = new Complex(a.re - b.re, a.im - b.im)
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

  private def runCheckedRatioInferred(): Long =
    RiftRegion.streaming { stream ?=>
      RiftRegion.epoch { region ?=>
        final class Ratio(val n: Int, val d: Int)
        val cfg = ReMLRegionConfig
        val values: Array[Ratio^{region}]^{region} =
          new Array[Ratio^{region}](cfg.ratioCount)
        var i = 1
        while (i <= cfg.ratioCount) {
          val n = mix(i) % 100000 + 1
          val d = mix(i + 17) % 99999 + 1
          val g = gcd(n, d)
          values(i - 1) = new Ratio(n / g, d / g)
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
    }

  private def evalHeapLogic(node: HeapLogicNode): Long =
    if (node.kind == 0) node.value.toLong
    else {
      val child = evalHeapLogic(node.left)
      node.kind match {
        case 1 => child ^ node.value.toLong
        case 2 => (child & 0xffffL) + node.value.toLong
        case 3 => (child | node.value.toLong) ^ (node.value.toLong << 1)
        case _ => child + node.value.toLong
      }
    }

  private def evalTrustedLogic(node: TrustedLogicNode): Long =
    if (node.kind == 0) node.value.toLong
    else {
      val child = evalTrustedLogic(node.left)
      node.kind match {
        case 1 => child ^ node.value.toLong
        case 2 => (child & 0xffffL) + node.value.toLong
        case 3 => (child | node.value.toLong) ^ (node.value.toLong << 1)
        case _ => child + node.value.toLong
      }
    }

  private def buildHeapLogic(depth: Int, seed: Int): HeapLogicNode =
    {
      val seeds = new Array[Int](depth + 1)
      seeds(0) = seed
      var d = 1
      while (d <= depth) {
        seeds(d) = seeds(d - 1) * 1664525 + 1013904223
        d += 1
      }
      var node = new HeapLogicNode(0, mix(seeds(depth)) & 0x7fff, null, null)
      d = depth
      while (d > 0) {
        val currentSeed = seeds(d - 1)
        node = new HeapLogicNode(
          1 + (mix(currentSeed) & 3),
          mix(currentSeed ^ d),
          node,
          null
        )
        d -= 1
      }
      node
    }

  private def buildTrustedLogic(
      region: RiftRegion,
      depth: Int,
      seed: Int
  ): TrustedLogicNode =
    {
      val seeds = new Array[Int](depth + 1)
      seeds(0) = seed
      var d = 1
      while (d <= depth) {
        seeds(d) = seeds(d - 1) * 1664525 + 1013904223
        d += 1
      }
      var node =
        region.alloc(new TrustedLogicNode(0, mix(seeds(depth)) & 0x7fff, null, null))
      d = depth
      while (d > 0) {
        val currentSeed = seeds(d - 1)
        node = region.alloc(
          new TrustedLogicNode(
            1 + (mix(currentSeed) & 3),
            mix(currentSeed ^ d),
            node,
            null
          )
        )
        d -= 1
      }
      node
    }

  private def runHeapLogic(): Long = {
    val cfg = ReMLRegionConfig
    var checksum = 0L
    var i = 0
    while (i < cfg.logicIterations) {
      val root = buildHeapLogic(cfg.logicDepth, i + 17)
      checksum = fold(checksum, evalHeapLogic(root))
      i += 1
    }
    checksum
  }

  private def runTrustedLogic(kind: Int): Long = {
    val cfg = ReMLRegionConfig
    val region = RiftRegion.open(kind)
    var checksum = 0L
    var i = 0
    try {
      while (i < cfg.logicIterations) {
        val root = buildTrustedLogic(region, cfg.logicDepth, i + 17)
        checksum = fold(checksum, evalTrustedLogic(root))
        i += 1
      }
      checksum
    } finally region.close()
  }

  private def runSafeZoneLogic(): Long =
    SafeZone { sz ?=>
      final class SZLogicNode(
          val kind: Int,
          val value: Int,
          val left: SZLogicNode^{sz},
          val right: SZLogicNode^{sz}
      )
      def build(depth: Int, seed: Int): SZLogicNode^{sz} =
        {
          val seeds = new Array[Int](depth + 1)
          seeds(0) = seed
          var d = 1
          while (d <= depth) {
            seeds(d) = seeds(d - 1) * 1664525 + 1013904223
            d += 1
          }
          var node: SZLogicNode^{sz} = SafeZoneAllocator.allocate(
            sz,
            new SZLogicNode(0, mix(seeds(depth)) & 0x7fff, null, null)
          )
          d = depth
          while (d > 0) {
            val currentSeed = seeds(d - 1)
            node = SafeZoneAllocator.allocate(
              sz,
              new SZLogicNode(
                1 + (mix(currentSeed) & 3),
                mix(currentSeed ^ d),
                node,
                null
              )
            )
            d -= 1
          }
          node
        }
      def eval(node: SZLogicNode^{sz}): Long =
        if (node.kind == 0) node.value.toLong
        else {
          val child = eval(node.left)
          node.kind match {
            case 1 => child ^ node.value.toLong
            case 2 => (child & 0xffffL) + node.value.toLong
            case 3 => (child | node.value.toLong) ^ (node.value.toLong << 1)
            case _ => child + node.value.toLong
          }
        }
      val cfg = ReMLRegionConfig
      var checksum = 0L
      var i = 0
      while (i < cfg.logicIterations) {
        checksum = fold(checksum, eval(build(cfg.logicDepth, i + 17)))
        i += 1
      }
      checksum
    }

  private def runCheckedLogic(safeZoneBackend: Boolean): Long = {
    def body()(using region: RiftRegion.StreamingRegion^): Long = {
      final class LogicNode(
          val kind: Int,
          val value: Int,
          val left: LogicNode^{region},
          val right: LogicNode^{region}
      )
      def build(depth: Int, seed: Int): LogicNode^{region} = {
        val seeds = new Array[Int](depth + 1)
        seeds(0) = seed
        var d = 1
        while (d <= depth) {
          seeds(d) = seeds(d - 1) * 1664525 + 1013904223
          d += 1
        }
        var node: LogicNode^{region} =
          RiftRegion.alloc(new LogicNode(0, mix(seeds(depth)) & 0x7fff, null, null))
        d = depth
        while (d > 0) {
          val currentSeed = seeds(d - 1)
          node = RiftRegion.alloc(
            new LogicNode(
              1 + (mix(currentSeed) & 3),
              mix(currentSeed ^ d),
              node,
              null
            )
          )
          d -= 1
        }
        node
      }
      def eval(node: LogicNode^{region}): Long =
        if (node.kind == 0) node.value.toLong
        else {
          val child = eval(node.left)
          node.kind match {
            case 1 => child ^ node.value.toLong
            case 2 => (child & 0xffffL) + node.value.toLong
            case 3 => (child | node.value.toLong) ^ (node.value.toLong << 1)
            case _ => child + node.value.toLong
          }
        }
      val cfg = ReMLRegionConfig
      var checksum = 0L
      var i = 0
      while (i < cfg.logicIterations) {
        checksum = fold(checksum, eval(build(cfg.logicDepth, i + 17)))
        i += 1
      }
      checksum
    }
    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> body() }
    else RiftRegion.streaming { stream ?=> body() }
  }

  private def runCheckedLogicInferred(): Long =
    RiftRegion.streaming { stream ?=>
      RiftRegion.epoch { region ?=>
        final class LogicNode(
            val kind: Int,
            val value: Int,
            val left: LogicNode^{region},
            val right: LogicNode^{region}
        )
        def eval(node: LogicNode^{region}): Long =
          if (node.kind == 0) node.value.toLong
          else {
            val child = eval(node.left)
            node.kind match {
              case 1 => child ^ node.value.toLong
              case 2 => (child & 0xffffL) + node.value.toLong
              case 3 => (child | node.value.toLong) ^ (node.value.toLong << 1)
              case _ => child + node.value.toLong
            }
          }
        val cfg = ReMLRegionConfig
        var checksum = 0L
        var i = 0
        while (i < cfg.logicIterations) {
          val seed = i + 17
          val seeds = new Array[Int](cfg.logicDepth + 1)
          seeds(0) = seed
          var d = 1
          while (d <= cfg.logicDepth) {
            seeds(d) = seeds(d - 1) * 1664525 + 1013904223
            d += 1
          }
          var node: LogicNode^{region} =
            new LogicNode(0, mix(seeds(cfg.logicDepth)) & 0x7fff, null, null)
          d = cfg.logicDepth
          while (d > 0) {
            val currentSeed = seeds(d - 1)
            node = new LogicNode(
              1 + (mix(currentSeed) & 3),
              mix(currentSeed ^ d),
              node,
              null
            )
            d -= 1
          }
          checksum = fold(checksum, eval(node))
          i += 1
        }
        checksum
      }
    }

  private def runHeapRay(): Long = {
    val cfg = ReMLRegionConfig
    val spheres = new Array[HeapSphere](cfg.raySpheres)
    var i = 0
    while (i < spheres.length) {
      spheres(i) = new HeapSphere(
        ((mix(i) % 2000).toDouble - 1000.0) / 100.0,
        ((mix(i + 7) % 2000).toDouble - 1000.0) / 100.0,
        4.0 + (mix(i + 13) % 900).toDouble / 100.0,
        0.25 + (mix(i + 19) % 75).toDouble / 100.0
      )
      i += 1
    }
    var checksum = 0L
    i = 0
    while (i < cfg.rayRays) {
      val ray = new HeapRay(
        0.0,
        0.0,
        -2.0,
        ((mix(i) % 2000).toDouble - 1000.0) / 2000.0,
        ((mix(i + 3) % 2000).toDouble - 1000.0) / 2000.0,
        1.0
      )
      var bestT = Double.PositiveInfinity
      var bestSphere = -1
      var j = 0
      while (j < spheres.length) {
        val s = spheres(j)
        val ox = ray.ox - s.x
        val oy = ray.oy - s.y
        val oz = ray.oz - s.z
        val b = ox * ray.dx + oy * ray.dy + oz * ray.dz
        val c = ox * ox + oy * oy + oz * oz - s.radius * s.radius
        val disc = b * b - c
        if (disc > 0.0) {
          val t = -b - math.sqrt(disc)
          if (t > 0.0 && t < bestT) {
            bestT = t
            bestSphere = j
          }
        }
        j += 1
      }
      if (bestSphere >= 0) {
        val hit = new HeapHit(bestSphere, bestT)
        checksum =
          fold(checksum, hit.sphere.toLong * 65537L + hit.t.toLong)
      } else checksum = fold(checksum, i.toLong)
      i += 1
    }
    checksum
  }

  private def runTrustedRay(kind: Int): Long = {
    val cfg = ReMLRegionConfig
    val region = RiftRegion.open(kind)
    val spheres = new Array[TrustedSphere](cfg.raySpheres)
    var i = 0
    try {
      while (i < spheres.length) {
        spheres(i) = region.alloc(
          new TrustedSphere(
            ((mix(i) % 2000).toDouble - 1000.0) / 100.0,
            ((mix(i + 7) % 2000).toDouble - 1000.0) / 100.0,
            4.0 + (mix(i + 13) % 900).toDouble / 100.0,
            0.25 + (mix(i + 19) % 75).toDouble / 100.0
          )
        )
        i += 1
      }
      var checksum = 0L
      i = 0
      while (i < cfg.rayRays) {
        val ray = region.alloc(
          new TrustedRay(
            0.0,
            0.0,
            -2.0,
            ((mix(i) % 2000).toDouble - 1000.0) / 2000.0,
            ((mix(i + 3) % 2000).toDouble - 1000.0) / 2000.0,
            1.0
          )
        )
        var bestT = Double.PositiveInfinity
        var bestSphere = -1
        var j = 0
        while (j < spheres.length) {
          val s = spheres(j)
          val ox = ray.ox - s.x
          val oy = ray.oy - s.y
          val oz = ray.oz - s.z
          val b = ox * ray.dx + oy * ray.dy + oz * ray.dz
          val c = ox * ox + oy * oy + oz * oz - s.radius * s.radius
          val disc = b * b - c
          if (disc > 0.0) {
            val t = -b - math.sqrt(disc)
            if (t > 0.0 && t < bestT) {
              bestT = t
              bestSphere = j
            }
          }
          j += 1
        }
        if (bestSphere >= 0) {
          val hit = region.alloc(new TrustedHit(bestSphere, bestT))
          checksum =
            fold(checksum, hit.sphere.toLong * 65537L + hit.t.toLong)
        } else checksum = fold(checksum, i.toLong)
        i += 1
      }
      checksum
    } finally region.close()
  }

  private def runSafeZoneRay(): Long =
    SafeZone { sz ?=>
      final class SZSphere(
          val x: Double,
          val y: Double,
          val z: Double,
          val radius: Double
      )
      final class SZRay(
          val ox: Double,
          val oy: Double,
          val oz: Double,
          val dx: Double,
          val dy: Double,
          val dz: Double
      )
      final class SZHit(val sphere: Int, val t: Double)
      val cfg = ReMLRegionConfig
      val spheres = new Array[SZSphere^{sz}](cfg.raySpheres)
      var i = 0
      while (i < spheres.length) {
        spheres(i) = SafeZoneAllocator.allocate(
          sz,
          new SZSphere(
            ((mix(i) % 2000).toDouble - 1000.0) / 100.0,
            ((mix(i + 7) % 2000).toDouble - 1000.0) / 100.0,
            4.0 + (mix(i + 13) % 900).toDouble / 100.0,
            0.25 + (mix(i + 19) % 75).toDouble / 100.0
          )
        )
        i += 1
      }
      var checksum = 0L
      i = 0
      while (i < cfg.rayRays) {
        val ray = SafeZoneAllocator.allocate(
          sz,
          new SZRay(
            0.0,
            0.0,
            -2.0,
            ((mix(i) % 2000).toDouble - 1000.0) / 2000.0,
            ((mix(i + 3) % 2000).toDouble - 1000.0) / 2000.0,
            1.0
          )
        )
        var bestT = Double.PositiveInfinity
        var bestSphere = -1
        var j = 0
        while (j < spheres.length) {
          val s = spheres(j)
          val ox = ray.ox - s.x
          val oy = ray.oy - s.y
          val oz = ray.oz - s.z
          val b = ox * ray.dx + oy * ray.dy + oz * ray.dz
          val c = ox * ox + oy * oy + oz * oz - s.radius * s.radius
          val disc = b * b - c
          if (disc > 0.0) {
            val t = -b - math.sqrt(disc)
            if (t > 0.0 && t < bestT) {
              bestT = t
              bestSphere = j
            }
          }
          j += 1
        }
        if (bestSphere >= 0) {
          val hit = SafeZoneAllocator.allocate(sz, new SZHit(bestSphere, bestT))
          checksum =
            fold(checksum, hit.sphere.toLong * 65537L + hit.t.toLong)
        } else checksum = fold(checksum, i.toLong)
        i += 1
      }
      checksum
    }

  private def runCheckedRay(safeZoneBackend: Boolean): Long = {
    def body()(using region: RiftRegion.StreamingRegion^): Long = {
      final class Sphere(
          val x: Double,
          val y: Double,
          val z: Double,
          val radius: Double
      )
      final class Ray(
          val ox: Double,
          val oy: Double,
          val oz: Double,
          val dx: Double,
          val dy: Double,
          val dz: Double
      )
      final class Hit(val sphere: Int, val t: Double)
      val cfg = ReMLRegionConfig
      val spheres: Array[Sphere^{region}]^{region} =
        RiftRegion.alloc(new Array[Sphere^{region}](cfg.raySpheres))
      var i = 0
      while (i < spheres.length) {
        spheres(i) = RiftRegion.alloc(
          new Sphere(
            ((mix(i) % 2000).toDouble - 1000.0) / 100.0,
            ((mix(i + 7) % 2000).toDouble - 1000.0) / 100.0,
            4.0 + (mix(i + 13) % 900).toDouble / 100.0,
            0.25 + (mix(i + 19) % 75).toDouble / 100.0
          )
        )
        i += 1
      }
      var checksum = 0L
      i = 0
      while (i < cfg.rayRays) {
        val ray = RiftRegion.alloc(
          new Ray(
            0.0,
            0.0,
            -2.0,
            ((mix(i) % 2000).toDouble - 1000.0) / 2000.0,
            ((mix(i + 3) % 2000).toDouble - 1000.0) / 2000.0,
            1.0
          )
        )
        var bestT = Double.PositiveInfinity
        var bestSphere = -1
        var j = 0
        while (j < spheres.length) {
          val s = spheres(j)
          val ox = ray.ox - s.x
          val oy = ray.oy - s.y
          val oz = ray.oz - s.z
          val b = ox * ray.dx + oy * ray.dy + oz * ray.dz
          val c = ox * ox + oy * oy + oz * oz - s.radius * s.radius
          val disc = b * b - c
          if (disc > 0.0) {
            val t = -b - math.sqrt(disc)
            if (t > 0.0 && t < bestT) {
              bestT = t
              bestSphere = j
            }
          }
          j += 1
        }
        if (bestSphere >= 0) {
          val hit = RiftRegion.alloc(new Hit(bestSphere, bestT))
          checksum =
            fold(checksum, hit.sphere.toLong * 65537L + hit.t.toLong)
        } else checksum = fold(checksum, i.toLong)
        i += 1
      }
      checksum
    }
    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> body() }
    else RiftRegion.streaming { stream ?=> body() }
  }

  private def runCheckedRayInferred(): Long =
    RiftRegion.streaming { stream ?=>
      RiftRegion.epoch { region ?=>
        final class Sphere(
            val x: Double,
            val y: Double,
            val z: Double,
            val radius: Double
        )
        final class Ray(
            val ox: Double,
            val oy: Double,
            val oz: Double,
            val dx: Double,
            val dy: Double,
            val dz: Double
        )
        final class Hit(val sphere: Int, val t: Double)
        val cfg = ReMLRegionConfig
        val spheres: Array[Sphere^{region}]^{region} =
          new Array[Sphere^{region}](cfg.raySpheres)
        var i = 0
        while (i < spheres.length) {
          spheres(i) = new Sphere(
            ((mix(i) % 2000).toDouble - 1000.0) / 100.0,
            ((mix(i + 7) % 2000).toDouble - 1000.0) / 100.0,
            4.0 + (mix(i + 13) % 900).toDouble / 100.0,
            0.25 + (mix(i + 19) % 75).toDouble / 100.0
          )
          i += 1
        }
        var checksum = 0L
        i = 0
        while (i < cfg.rayRays) {
          val ray: Ray^{region} = new Ray(
            0.0,
            0.0,
            -2.0,
            ((mix(i) % 2000).toDouble - 1000.0) / 2000.0,
            ((mix(i + 3) % 2000).toDouble - 1000.0) / 2000.0,
            1.0
          )
          var bestT = Double.PositiveInfinity
          var bestSphere = -1
          var j = 0
          while (j < spheres.length) {
            val s = spheres(j)
            val ox = ray.ox - s.x
            val oy = ray.oy - s.y
            val oz = ray.oz - s.z
            val b = ox * ray.dx + oy * ray.dy + oz * ray.dz
            val c = ox * ox + oy * oy + oz * oz - s.radius * s.radius
            val disc = b * b - c
            if (disc > 0.0) {
              val t = -b - math.sqrt(disc)
              if (t > 0.0 && t < bestT) {
                bestT = t
                bestSphere = j
              }
            }
            j += 1
          }
          if (bestSphere >= 0) {
            val hit: Hit^{region} = new Hit(bestSphere, bestT)
            checksum =
              fold(checksum, hit.sphere.toLong * 65537L + hit.t.toLong)
          } else checksum = fold(checksum, i.toLong)
          i += 1
        }
        checksum
      }
    }

  private def pointDistance2Heap(a: HeapPoint, b: HeapPoint): Double = {
    val dx = a.x - b.x
    val dy = a.y - b.y
    dx * dx + dy * dy
  }

  private def pointDistance2Trusted(a: TrustedPoint, b: TrustedPoint): Double = {
    val dx = a.x - b.x
    val dy = a.y - b.y
    dx * dx + dy * dy
  }

  private def runHeapTsp(): Long = {
    val cfg = ReMLRegionConfig
    val points = new Array[HeapPoint](cfg.tspPoints)
    var i = 0
    while (i < points.length) {
      points(i) = new HeapPoint(
        (mix(i) % 10000).toDouble / 100.0,
        (mix(i + 11) % 10000).toDouble / 100.0
      )
      i += 1
    }
    val visited = new Array[Boolean](cfg.tspPoints)
    var checksum = 0L
    var start = 0
    while (start < cfg.tspStarts) {
      java.util.Arrays.fill(visited, false)
      var current = start % cfg.tspPoints
      var tour: HeapTourNode = null
      var step = 0
      var total = 0.0
      while (step < cfg.tspPoints) {
        visited(current) = true
        tour = new HeapTourNode(current, tour)
        var best = -1
        var bestD = Double.PositiveInfinity
        var j = 0
        while (j < cfg.tspPoints) {
          if (!visited(j)) {
            val distance = pointDistance2Heap(points(current), points(j))
            if (distance < bestD) {
              bestD = distance
              best = j
            }
          }
          j += 1
        }
        if (best >= 0) {
          total += math.sqrt(bestD)
          current = best
        }
        step += 1
      }
      var cursor = tour
      var tourChecksum = 0L
      while (cursor != null) {
        tourChecksum = fold(tourChecksum, cursor.point.toLong)
        cursor = cursor.next
      }
      checksum = fold(checksum, tourChecksum ^ total.toLong)
      start += 1
    }
    checksum
  }

  private def runTrustedTsp(kind: Int): Long = {
    val cfg = ReMLRegionConfig
    val region = RiftRegion.open(kind)
    val points = new Array[TrustedPoint](cfg.tspPoints)
    var i = 0
    try {
      while (i < points.length) {
        points(i) = region.alloc(
          new TrustedPoint(
            (mix(i) % 10000).toDouble / 100.0,
            (mix(i + 11) % 10000).toDouble / 100.0
          )
        )
        i += 1
      }
      val visited = new Array[Boolean](cfg.tspPoints)
      var checksum = 0L
      var start = 0
      while (start < cfg.tspStarts) {
        java.util.Arrays.fill(visited, false)
        var current = start % cfg.tspPoints
        var tour: TrustedTourNode = null
        var step = 0
        var total = 0.0
        while (step < cfg.tspPoints) {
          visited(current) = true
          tour = region.alloc(new TrustedTourNode(current, tour))
          var best = -1
          var bestD = Double.PositiveInfinity
          var j = 0
          while (j < cfg.tspPoints) {
            if (!visited(j)) {
              val distance = pointDistance2Trusted(points(current), points(j))
              if (distance < bestD) {
                bestD = distance
                best = j
              }
            }
            j += 1
          }
          if (best >= 0) {
            total += math.sqrt(bestD)
            current = best
          }
          step += 1
        }
        var cursor = tour
        var tourChecksum = 0L
        while (cursor != null) {
          tourChecksum = fold(tourChecksum, cursor.point.toLong)
          cursor = cursor.next
        }
        checksum = fold(checksum, tourChecksum ^ total.toLong)
        start += 1
      }
      checksum
    } finally region.close()
  }

  private def runSafeZoneTsp(): Long =
    SafeZone { sz ?=>
      final class SZPoint(val x: Double, val y: Double)
      final class SZTourNode(val point: Int, val next: SZTourNode^{sz})
      def distance2(a: SZPoint^{sz}, b: SZPoint^{sz}): Double = {
        val dx = a.x - b.x
        val dy = a.y - b.y
        dx * dx + dy * dy
      }
      val cfg = ReMLRegionConfig
      val points = new Array[SZPoint^{sz}](cfg.tspPoints)
      var i = 0
      while (i < points.length) {
        points(i) = SafeZoneAllocator.allocate(
          sz,
          new SZPoint(
            (mix(i) % 10000).toDouble / 100.0,
            (mix(i + 11) % 10000).toDouble / 100.0
          )
        )
        i += 1
      }
      val visited = new Array[Boolean](cfg.tspPoints)
      var checksum = 0L
      var start = 0
      while (start < cfg.tspStarts) {
        java.util.Arrays.fill(visited, false)
        var current = start % cfg.tspPoints
        var tour: SZTourNode^{sz} = null
        var step = 0
        var total = 0.0
        while (step < cfg.tspPoints) {
          visited(current) = true
          tour = SafeZoneAllocator.allocate(sz, new SZTourNode(current, tour))
          var best = -1
          var bestD = Double.PositiveInfinity
          var j = 0
          while (j < cfg.tspPoints) {
            if (!visited(j)) {
              val d = distance2(points(current), points(j))
              if (d < bestD) {
                bestD = d
                best = j
              }
            }
            j += 1
          }
          if (best >= 0) {
            total += math.sqrt(bestD)
            current = best
          }
          step += 1
        }
        var cursor = tour
        var tourChecksum = 0L
        while (cursor != null) {
          tourChecksum = fold(tourChecksum, cursor.point.toLong)
          cursor = cursor.next
        }
        checksum = fold(checksum, tourChecksum ^ total.toLong)
        start += 1
      }
      checksum
    }

  private def runCheckedTsp(safeZoneBackend: Boolean): Long = {
    def body()(using region: RiftRegion.StreamingRegion^): Long = {
      final class Point(val x: Double, val y: Double)
      final class TourNode(val point: Int, val next: TourNode^{region})
      def distance2(a: Point^{region}, b: Point^{region}): Double = {
        val dx = a.x - b.x
        val dy = a.y - b.y
        dx * dx + dy * dy
      }
      val cfg = ReMLRegionConfig
      val points: Array[Point^{region}]^{region} =
        RiftRegion.alloc(new Array[Point^{region}](cfg.tspPoints))
      var i = 0
      while (i < points.length) {
        points(i) = RiftRegion.alloc(
          new Point(
            (mix(i) % 10000).toDouble / 100.0,
            (mix(i + 11) % 10000).toDouble / 100.0
          )
        )
        i += 1
      }
      val visited = new Array[Boolean](cfg.tspPoints)
      var checksum = 0L
      var start = 0
      while (start < cfg.tspStarts) {
        java.util.Arrays.fill(visited, false)
        var current = start % cfg.tspPoints
        var tour: TourNode^{region} = null
        var step = 0
        var total = 0.0
        while (step < cfg.tspPoints) {
          visited(current) = true
          tour = RiftRegion.alloc(new TourNode(current, tour))
          var best = -1
          var bestD = Double.PositiveInfinity
          var j = 0
          while (j < cfg.tspPoints) {
            if (!visited(j)) {
              val d = distance2(points(current), points(j))
              if (d < bestD) {
                bestD = d
                best = j
              }
            }
            j += 1
          }
          if (best >= 0) {
            total += math.sqrt(bestD)
            current = best
          }
          step += 1
        }
        var cursor = tour
        var tourChecksum = 0L
        while (cursor != null) {
          tourChecksum = fold(tourChecksum, cursor.point.toLong)
          cursor = cursor.next
        }
        checksum = fold(checksum, tourChecksum ^ total.toLong)
        start += 1
      }
      checksum
    }
    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> body() }
    else RiftRegion.streaming { stream ?=> body() }
  }

  private def runCheckedTspInferred(): Long =
    RiftRegion.streaming { stream ?=>
      RiftRegion.epoch { region ?=>
        final class Point(val x: Double, val y: Double)
        final class TourNode(val point: Int, val next: TourNode^{region})
        def distance2(a: Point^{region}, b: Point^{region}): Double = {
          val dx = a.x - b.x
          val dy = a.y - b.y
          dx * dx + dy * dy
        }
        val cfg = ReMLRegionConfig
        val points: Array[Point^{region}]^{region} =
          new Array[Point^{region}](cfg.tspPoints)
        var i = 0
        while (i < points.length) {
          points(i) = new Point(
            (mix(i) % 10000).toDouble / 100.0,
            (mix(i + 11) % 10000).toDouble / 100.0
          )
          i += 1
        }
        val visited = new Array[Boolean](cfg.tspPoints)
        var checksum = 0L
        var start = 0
        while (start < cfg.tspStarts) {
          java.util.Arrays.fill(visited, false)
          var current = start % cfg.tspPoints
          var tour: TourNode^{region} = null
          var step = 0
          var total = 0.0
          while (step < cfg.tspPoints) {
            visited(current) = true
            tour = new TourNode(current, tour)
            var best = -1
            var bestD = Double.PositiveInfinity
            var j = 0
            while (j < cfg.tspPoints) {
              if (!visited(j)) {
                val d = distance2(points(current), points(j))
                if (d < bestD) {
                  bestD = d
                  best = j
                }
              }
              j += 1
            }
            if (best >= 0) {
              total += math.sqrt(bestD)
              current = best
            }
            step += 1
          }
          var cursor = tour
          var tourChecksum = 0L
          while (cursor != null) {
            tourChecksum = fold(tourChecksum, cursor.point.toLong)
            cursor = cursor.next
          }
          checksum = fold(checksum, tourChecksum ^ total.toLong)
          start += 1
        }
        checksum
      }
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
      case "rift-checked-inferred" | "checked-region-stream-inferred" =>
        "checked-region-stream-inferred"
      case "rift-checked-safezone-32k" |
          "rift-checked-safezone-improved-32k" | "checked-region-scoped" =>
        "checked-region-scoped"
      case other => throw new IllegalArgumentException(s"unknown ReML mode '$other'")
    }

  private def usesRiftStats(mode: String): Boolean =
    mode == "region-hp-rootless" ||
      mode == "region-stream-rootless" ||
      mode == "checked-region-stream" ||
      mode == "checked-region-stream-inferred"

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
          case "checked-region-stream-inferred" =>
            runCheckedMsortInferred(false)
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
          case "checked-region-stream-inferred" =>
            runCheckedMsortInferred(true)
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
          case "checked-region-stream-inferred" => runCheckedFftInferred()
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
          case "checked-region-stream-inferred" => runCheckedRatioInferred()
          case "checked-region-scoped" => runCheckedRatio(true)
        }
      case "logic" =>
        mode match {
          case "gc-heap" => runHeapLogic()
          case "region-scoped-rooted" | "region-scoped-rootless" =>
            runSafeZoneLogic()
          case "region-hp-rootless" => runTrustedLogic(RiftRegion.HPZone)
          case "region-stream-rootless" => runTrustedLogic(RiftRegion.Streaming)
          case "checked-region-stream" => runCheckedLogic(false)
          case "checked-region-stream-inferred" => runCheckedLogicInferred()
          case "checked-region-scoped" => runCheckedLogic(true)
        }
      case "ray" =>
        mode match {
          case "gc-heap" => runHeapRay()
          case "region-scoped-rooted" | "region-scoped-rootless" =>
            runSafeZoneRay()
          case "region-hp-rootless" => runTrustedRay(RiftRegion.HPZone)
          case "region-stream-rootless" => runTrustedRay(RiftRegion.Streaming)
          case "checked-region-stream" => runCheckedRay(false)
          case "checked-region-stream-inferred" => runCheckedRayInferred()
          case "checked-region-scoped" => runCheckedRay(true)
        }
      case "tsp" =>
        mode match {
          case "gc-heap" => runHeapTsp()
          case "region-scoped-rooted" | "region-scoped-rootless" =>
            runSafeZoneTsp()
          case "region-hp-rootless" => runTrustedTsp(RiftRegion.HPZone)
          case "region-stream-rootless" => runTrustedTsp(RiftRegion.Streaming)
          case "checked-region-stream" => runCheckedTsp(false)
          case "checked-region-stream-inferred" => runCheckedTspInferred()
          case "checked-region-scoped" => runCheckedTsp(true)
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
