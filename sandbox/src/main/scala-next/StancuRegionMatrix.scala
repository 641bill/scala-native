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

object StancuRegionConfig {
  private def positiveInt(value: String): Option[Int] =
    try {
      val parsed = value.toInt
      if (parsed > 0) Some(parsed) else None
    } catch {
      case _: NumberFormatException => None
    }

  private def nonNegativeInt(value: String): Option[Int] =
    try {
      val parsed = value.toInt
      if (parsed >= 0) Some(parsed) else None
    } catch {
      case _: NumberFormatException => None
    }

  private def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(positiveInt).getOrElse(default)

  private def envNonNegativeInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(nonNegativeInt).getOrElse(default)

  val transactions: Int = envInt("STANCU_TRANSACTIONS", 200000)
  val itemsPerTransaction: Int = envInt("STANCU_ITEMS_PER_TX", 8)
  val transactionsPerRegion: Int = envInt("STANCU_TX_PER_REGION", 64)
  val warehouses: Int = envInt("STANCU_WAREHOUSES", 64)
  val products: Int = envInt("STANCU_PRODUCTS", 4096)
  val benchmarkRuns: Int = envInt("STANCU_BENCHMARK_RUNS", 3)
  val warmupRuns: Int = envNonNegativeInt("STANCU_WARMUPS", 1)
}

object StancuRegionMatrixHelpers {
  @volatile private var checksumSink = 0L

  private final class TxLine(
      val product: Int,
      val quantity: Int,
      val priceCents: Int,
      val next: TxLine
  )

  private final class TxOrder(
      val id: Int,
      val warehouse: Int,
      val customer: Int,
      val totalCents: Long,
      val lines: TxLine
  )

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

  private final class ModeState(mode: String) {
    val usesRift: Boolean = mode == "rift-hp" || mode == "rift-streaming"
    private val streaming: Boolean = mode == "rift-streaming"
    private val kind: Int =
      if (streaming) RiftRegion.Streaming else RiftRegion.HPZone
    private var streamRegion: RiftRegion = null

    def beginTransactionBatch(): RiftRegion =
      if (!usesRift) null
      else if (streaming) {
        if (streamRegion == null) streamRegion = RiftRegion.open(kind)
        else streamRegion.reset()
        streamRegion
      } else {
        RiftRegion.open(kind)
      }

    def endTransactionBatch(region: RiftRegion): Unit =
      if (usesRift && !streaming) region.close()

    def finish(): Unit =
      if (streamRegion != null) {
        streamRegion.close()
        streamRegion = null
      }

    def allocLine(
        region: RiftRegion,
        product: Int,
        quantity: Int,
        priceCents: Int,
        next: TxLine
    ): TxLine =
      if (usesRift) region.alloc(new TxLine(product, quantity, priceCents, next))
      else new TxLine(product, quantity, priceCents, next)

    def allocOrder(
        region: RiftRegion,
        id: Int,
        warehouse: Int,
        customer: Int,
        totalCents: Long,
        lines: TxLine
    ): TxOrder =
      if (usesRift)
        region.alloc(new TxOrder(id, warehouse, customer, totalCents, lines))
      else new TxOrder(id, warehouse, customer, totalCents, lines)
  }

  private def mix(value: Int): Int = {
    var x = value
    x ^= x << 13
    x ^= x >>> 17
    x ^= x << 5
    x & 0x7fffffff
  }

  private def checksumState(
      stock: Array[Int],
      revenue: Array[Long],
      customers: Array[Long]
  ): Long = {
    var checksum = 0L
    var i = 0
    while (i < stock.length) {
      checksum = (checksum * 16777619L) ^ stock(i).toLong ^ i.toLong
      i += 1
    }
    i = 0
    while (i < revenue.length) {
      checksum = (checksum * 1099511628211L) ^ revenue(i) ^ (i.toLong << 7)
      i += 1
    }
    i = 0
    while (i < customers.length) {
      checksum = (checksum * 1315423911L) ^ customers(i) ^ (i.toLong << 11)
      i += 1
    }
    checksum
  }

  private def initStock(size: Int): Array[Int] = {
    val stock = new Array[Int](size)
    var i = 0
    while (i < stock.length) {
      stock(i) = 100000 + (mix(i + 7) & 0xff)
      i += 1
    }
    stock
  }

  def runHeapOrRiftTransactions(modeName: String): Long = {
    val cfg = StancuRegionConfig
    val mode = new ModeState(modeName)
    val stock = initStock(cfg.products)
    val revenue = new Array[Long](cfg.warehouses)
    val customers = new Array[Long](cfg.warehouses)

    var tx = 0
    try {
      while (tx < cfg.transactions) {
        val region = mode.beginTransactionBatch()
        val batchEnd = math.min(cfg.transactions, tx + cfg.transactionsPerRegion)
        while (tx < batchEnd) {
          val warehouse = mix(tx + 13) % cfg.warehouses
          val customer = mix(tx + 101) & 0xffff
          var lines: TxLine = null
          var total = 0L
          var item = 0
          while (item < cfg.itemsPerTransaction) {
            val seed = mix(tx * 104729 + item * 8191)
            val product = seed % cfg.products
            val quantity = (mix(seed + 19) & 3) + 1
            val price = 100 + (mix(seed + 23) % 10000)
            total += quantity.toLong * price.toLong
            lines = mode.allocLine(region, product, quantity, price, lines)
            item += 1
          }
          val order =
            mode.allocOrder(region, tx, warehouse, customer, total, lines)

          var line = order.lines
          while (line != null) {
            stock(line.product) -= line.quantity
            line = line.next
          }
          revenue(order.warehouse) += order.totalCents
          customers(order.warehouse) ^= order.customer.toLong + order.id.toLong
          tx += 1
        }
        mode.endTransactionBatch(region)
      }
    } finally mode.finish()

    val checksum = checksumState(stock, revenue, customers)
    checksumSink = checksum
    checksum
  }

  def runSafeZoneTransactions(): Long = {
    val cfg = StancuRegionConfig
    val stock = initStock(cfg.products)
    val revenue = new Array[Long](cfg.warehouses)
    val customers = new Array[Long](cfg.warehouses)

    var tx = 0
    while (tx < cfg.transactions) {
      val batchStart = tx
      val batchEnd = math.min(cfg.transactions, tx + cfg.transactionsPerRegion)
      SafeZone { sz ?=>
        final class SZLine(
            val product: Int,
            val quantity: Int,
            val priceCents: Int,
            val next: SZLine^{sz}
        )
        final class SZOrder(
            val id: Int,
            val warehouse: Int,
            val customer: Int,
            val totalCents: Long,
            val lines: SZLine^{sz}
        )

        var currentTx = batchStart
        while (currentTx < batchEnd) {
          val warehouse = mix(currentTx + 13) % cfg.warehouses
          val customer = mix(currentTx + 101) & 0xffff
          var lines: SZLine^{sz} = null
          var total = 0L
          var item = 0
          while (item < cfg.itemsPerTransaction) {
            val seed = mix(currentTx * 104729 + item * 8191)
            val product = seed % cfg.products
            val quantity = (mix(seed + 19) & 3) + 1
            val price = 100 + (mix(seed + 23) % 10000)
            total += quantity.toLong * price.toLong
            lines = SafeZoneAllocator.allocate(
              sz,
              new SZLine(product, quantity, price, lines)
            )
            item += 1
          }
          val order = SafeZoneAllocator.allocate(
            sz,
            new SZOrder(currentTx, warehouse, customer, total, lines)
          )

          var line = order.lines
          while (line != null) {
            stock(line.product) -= line.quantity
            line = line.next
          }
          revenue(order.warehouse) += order.totalCents
          customers(order.warehouse) ^= order.customer.toLong + order.id.toLong
          currentTx += 1
        }
      }
      tx = batchEnd
    }

    val checksum = checksumState(stock, revenue, customers)
    checksumSink = checksum
    checksum
  }

  def runCheckedDirectEpochTransactions(safeZoneBackend: Boolean): Long = {
    val cfg = StancuRegionConfig
    val stock = initStock(cfg.products)
    val revenue = new Array[Long](cfg.warehouses)
    val customers = new Array[Long](cfg.warehouses)

    def run()(using stream: RiftRegion.StreamingRegion^): Unit = {
      var tx = 0
      while (tx < cfg.transactions) {
        val batchStart = tx
        val batchEnd = math.min(cfg.transactions, tx + cfg.transactionsPerRegion)
        RiftRegion.epoch { region ?=>
          final class CheckedTxLine(
              val product: Int,
              val quantity: Int,
              val priceCents: Int,
              val next: CheckedTxLine^{region}
          )

          final class CheckedTxOrder(
              val id: Int,
              val warehouse: Int,
              val customer: Int,
              val totalCents: Long,
              val lines: CheckedTxLine^{region}
          )

          var currentTx = batchStart
          while (currentTx < batchEnd) {
            val warehouse = mix(currentTx + 13) % cfg.warehouses
            val customer = mix(currentTx + 101) & 0xffff
            var lines: CheckedTxLine^{region} = null
            var total = 0L
            var item = 0
            while (item < cfg.itemsPerTransaction) {
              val seed = mix(currentTx * 104729 + item * 8191)
              val product = seed % cfg.products
              val quantity = (mix(seed + 19) & 3) + 1
              val price = 100 + (mix(seed + 23) % 10000)
              total += quantity.toLong * price.toLong
              lines = RiftRegion.allocOpen(
                new CheckedTxLine(product, quantity, price, lines)
              )
              item += 1
            }

            val order = RiftRegion.allocOpen(
              new CheckedTxOrder(currentTx, warehouse, customer, total, lines)
            )
            var line = order.lines
            while (line != null) {
              stock(line.product) -= line.quantity
              line = line.next
            }
            revenue(order.warehouse) += order.totalCents
            customers(order.warehouse) ^=
              order.customer.toLong + order.id.toLong
            currentTx += 1
          }
        }
        tx = batchEnd
      }
    }

    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> run() }
    else RiftRegion.streaming { stream ?=> run() }

    val checksum = checksumState(stock, revenue, customers)
    checksumSink = checksum
    checksum
  }

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

  private def runWorkload(mode: String): Long =
    if (mode == "safezone") runSafeZoneTransactions()
    else if (mode == "rift-checked-direct-epoch")
      runCheckedDirectEpochTransactions(safeZoneBackend = false)
    else if (mode == "rift-checked-safezone-direct-epoch")
      runCheckedDirectEpochTransactions(safeZoneBackend = true)
    else runHeapOrRiftTransactions(mode)

  private def logicalRegionObjects(): Long = {
    val cfg = StancuRegionConfig
    cfg.transactions.toLong * (cfg.itemsPerTransaction.toLong + 1L)
  }

  private def durableControlSlots(): Long = {
    val cfg = StancuRegionConfig
    cfg.products.toLong + cfg.warehouses.toLong * 2L
  }

  private def candidateBasisPoints(): Long = {
    val regionObjects = logicalRegionObjects()
    val total = regionObjects + durableControlSlots()
    if (total == 0L) 0L else (regionObjects * 10000L) / total
  }

  def runBenchmark(mode: String): Unit = {
    val cfg = StancuRegionConfig
    val usesRift =
      mode == "rift-hp" ||
        mode == "rift-streaming" ||
        mode == "rift-checked-direct-epoch"
    val expected = runWorkload("heap")

    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val checksum = runWorkload(mode)
      if (checksum != expected)
        throw new IllegalStateException(
          s"warmup checksum mismatch mode=$mode expected=$expected actual=$checksum"
        )
      warmup += 1
    }

    if (usesRift) RiftAllocator.Impl.statsReset()

    val elapsedMs = new Array[Double](cfg.benchmarkRuns)
    val gcNanos = new Array[Long](cfg.benchmarkRuns)
    val riftOpNanos = new Array[Long](cfg.benchmarkRuns)
    val riftSlowNanos = new Array[Long](cfg.benchmarkRuns)
    val riftObjects = new Array[Long](cfg.benchmarkRuns)
    val riftOpens = new Array[Long](cfg.benchmarkRuns)
    val riftCloses = new Array[Long](cfg.benchmarkRuns)
    val riftResets = new Array[Long](cfg.benchmarkRuns)

    println(s"Running stancu-transactions-$mode for ${cfg.benchmarkRuns} timed runs")
    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val checksum = runWorkload(mode)
      val end = System.nanoTime()
      val endRuntime = RuntimeSample.capture(usesRift)
      val runtime = RuntimeSample.since(startRuntime, endRuntime)

      if (checksum != expected)
        throw new IllegalStateException(
          s"checksum mismatch mode=$mode expected=$expected actual=$checksum"
        )

      elapsedMs(run) = (end - start) / 1000000.0
      gcNanos(run) = runtime.gcNanos
      riftOpNanos(run) = runtime.riftRegionOpNanos
      riftSlowNanos(run) = runtime.riftSlowAllocNanos
      riftObjects(run) = runtime.riftAllocObjectTotal
      riftOpens(run) = runtime.riftRegionOpenTotal
      riftCloses(run) = runtime.riftRegionCloseTotal
      riftResets(run) = runtime.riftRegionResetTotal
      println(
        f"  run=${run + 1}%d elapsed_ms=${elapsedMs(run)}%.3f " +
          f"gc_collections=${runtime.gcCollections}%d " +
          f"gc_ms=${runtime.gcNanos / 1000000.0}%.3f " +
          f"rift_op_ms=${runtime.riftRegionOpNanos / 1000000.0}%.3f " +
          f"rift_slow_alloc_ms=${runtime.riftSlowAllocNanos / 1000000.0}%.3f " +
          f"rift_alloc_object_total=${runtime.riftAllocObjectTotal}%d"
      )
      run += 1
    }

    println(
      f"RESULT name=stancu-transactions-$mode " +
        f"median_ms=${medianDouble(elapsedMs)}%.3f " +
        f"median_gc_ms=${medianLong(gcNanos) / 1000000.0}%.3f " +
        f"median_rift_op_ms=${medianLong(riftOpNanos) / 1000000.0}%.3f " +
        f"median_rift_slow_alloc_ms=${medianLong(riftSlowNanos) / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=${medianLong(riftObjects)}%d " +
        f"median_rift_open_total=${medianLong(riftOpens)}%d " +
        f"median_rift_close_total=${medianLong(riftCloses)}%d " +
        f"median_rift_reset_total=${medianLong(riftResets)}%d " +
        f"logical_region_objects=${logicalRegionObjects()}%d " +
        f"durable_control_slots=${durableControlSlots()}%d " +
        f"candidate_region_object_bp=${candidateBasisPoints()}%d " +
        f"transactions_per_region=${cfg.transactionsPerRegion}%d " +
        f"explicit_region_boundaries=1 " +
        f"escaped_region_objects=0 " +
        f"checksum=$expected%d"
    )
  }

  def printConfig(mode: String): Unit = {
    val cfg = StancuRegionConfig
    val rootsMode = sys.env.getOrElse("SAFEZONE_ROOTS_MODE", "0")
    println(
      s"CONFIG mode=$mode runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} transactions=${cfg.transactions} items_per_tx=${cfg.itemsPerTransaction} tx_per_region=${cfg.transactionsPerRegion} warehouses=${cfg.warehouses} products=${cfg.products} safezone_roots_mode=$rootsMode"
    )
  }

  def validateMode(mode: String): Unit =
    mode match {
      case "heap" | "safezone" | "rift-hp" | "rift-streaming" |
          "rift-checked-direct-epoch" |
          "rift-checked-safezone-direct-epoch" =>
        ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown Stancu mode '$other'; expected heap, safezone, rift-hp, rift-streaming, rift-checked-direct-epoch, or rift-checked-safezone-direct-epoch"
        )
    }
}

@main def StancuRegionMatrix(mode: String = "heap"): Unit = {
  StancuRegionMatrixHelpers.validateMode(mode)
  StancuRegionMatrixHelpers.printConfig(mode)

  val usesRift =
    mode == "rift-hp" ||
      mode == "rift-streaming" ||
      mode == "rift-checked-direct-epoch"
  if (usesRift) RiftRegion.init(0)
  try {
    StancuRegionMatrixHelpers.runBenchmark(mode)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
