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

object SpecJbb2005PortConfig {
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

  val warehouses: Int = envInt("SPECJBB_WAREHOUSES", 4)
  val iterationsPerWarehouse: Int =
    envInt("SPECJBB_ITERATIONS_PER_WAREHOUSE", 100000)
  val itemsPerOrder: Int = envInt("SPECJBB_ITEMS_PER_ORDER", 8)
  val transactionsPerRegion: Int = envInt("SPECJBB_TX_PER_REGION", 64)
  val products: Int = envInt("SPECJBB_PRODUCTS", 4096)
  val customersPerWarehouse: Int =
    envInt("SPECJBB_CUSTOMERS_PER_WAREHOUSE", 4096)
  val districtsPerWarehouse: Int =
    envInt("SPECJBB_DISTRICTS_PER_WAREHOUSE", 10)
  val benchmarkRuns: Int = envInt("SPECJBB_BENCHMARK_RUNS", 3)
  val warmupRuns: Int = envNonNegativeInt("SPECJBB_WARMUPS", 1)

  val totalTransactions: Int = warehouses * iterationsPerWarehouse
}

object SpecJbb2005PortMatrixHelpers {
  @volatile private var checksumSink = 0L

  private final class TxLine(
      val product: Int,
      val quantity: Int,
      val priceCents: Int,
      val next: TxLine
  )

  private final class TxProbe(
      val product: Int,
      val quantity: Int,
      val belowThreshold: Boolean,
      val next: TxProbe
  )

  private final class TxRequest(
      val id: Int,
      val warehouse: Int,
      val customer: Int,
      val district: Int,
      val kind: Int,
      val amountCents: Long,
      val lines: TxLine,
      val probes: TxProbe
  )

  private final class TxReceipt(
      val id: Int,
      val warehouse: Int,
      val customer: Int,
      val kind: Int,
      val status: Int,
      val totalCents: Long,
      val lineCount: Int,
      val lines: TxLine,
      val probes: TxProbe
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

    def beginBatch(): RiftRegion =
      if (!usesRift) null
      else if (streaming) {
        if (streamRegion == null) streamRegion = RiftRegion.open(kind)
        else streamRegion.reset()
        streamRegion
      } else {
        RiftRegion.open(kind)
      }

    def endBatch(region: RiftRegion): Unit =
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

    def allocProbe(
        region: RiftRegion,
        product: Int,
        quantity: Int,
        belowThreshold: Boolean,
        next: TxProbe
    ): TxProbe =
      if (usesRift)
        region.alloc(new TxProbe(product, quantity, belowThreshold, next))
      else new TxProbe(product, quantity, belowThreshold, next)

    def allocRequest(
        region: RiftRegion,
        id: Int,
        warehouse: Int,
        customer: Int,
        district: Int,
        kind: Int,
        amountCents: Long,
        lines: TxLine,
        probes: TxProbe
    ): TxRequest =
      if (usesRift)
        region.alloc(
          new TxRequest(
            id,
            warehouse,
            customer,
            district,
            kind,
            amountCents,
            lines,
            probes
          )
        )
      else
        new TxRequest(
          id,
          warehouse,
          customer,
          district,
          kind,
          amountCents,
          lines,
          probes
        )

    def allocReceipt(
        region: RiftRegion,
        id: Int,
        warehouse: Int,
        customer: Int,
        kind: Int,
        status: Int,
        totalCents: Long,
        lineCount: Int,
        lines: TxLine,
        probes: TxProbe
    ): TxReceipt =
      if (usesRift)
        region.alloc(
          new TxReceipt(
            id,
            warehouse,
            customer,
            kind,
            status,
            totalCents,
            lineCount,
            lines,
            probes
          )
        )
      else
        new TxReceipt(
          id,
          warehouse,
          customer,
          kind,
          status,
          totalCents,
          lineCount,
          lines,
          probes
        )
  }

  private def mix(value: Int): Int = {
    var x = value
    x ^= x << 13
    x ^= x >>> 17
    x ^= x << 5
    x & 0x7fffffff
  }

  private def mixLong(value: Long): Long = {
    var x = value
    x ^= x << 21
    x ^= x >>> 35
    x ^= x << 4
    x & 0x7fffffffffffffffL
  }

  private def txKind(globalTx: Int, warehouse: Int): Int =
    mix(globalTx * 1103515245 + warehouse * 265443576) % 5

  private def warehouseFor(globalTx: Int): Int =
    globalTx / SpecJbb2005PortConfig.iterationsPerWarehouse

  private def txObjectCount(globalTx: Int): Long = {
    val cfg = SpecJbb2005PortConfig
    val wh = warehouseFor(globalTx)
    txKind(globalTx, wh) match {
      case 0 | 4 => cfg.itemsPerOrder.toLong + 2L
      case _     => 2L
    }
  }

  private def txByteProxy(globalTx: Int): Long = {
    val cfg = SpecJbb2005PortConfig
    val wh = warehouseFor(globalTx)
    txKind(globalTx, wh) match {
      case 0 | 4 => 112L + cfg.itemsPerOrder.toLong * 40L
      case _     => 112L
    }
  }

  private def initProducts(size: Int): Array[Int] = {
    val prices = new Array[Int](size)
    var i = 0
    while (i < prices.length) {
      prices(i) = 100 + (mix(i + 73) % 10000)
      i += 1
    }
    prices
  }

  private def initStock(size: Int): Array[Int] = {
    val stock = new Array[Int](size)
    var i = 0
    while (i < stock.length) {
      stock(i) = 100000 + (mix(i + 7) & 0x3ff)
      i += 1
    }
    stock
  }

  private def initCustomers(size: Int): Array[Long] = {
    val customers = new Array[Long](size)
    var i = 0
    while (i < customers.length) {
      customers(i) = mixLong(i.toLong * 1315423911L + 17L)
      i += 1
    }
    customers
  }

  private def checksumState(
      stock: Array[Int],
      prices: Array[Int],
      customers: Array[Long],
      revenue: Array[Long],
      orders: Array[Long],
      districts: Array[Long],
      transactionChecksum: Long
  ): Long = {
    var checksum = transactionChecksum ^ 0x6a09e667f3bcc909L
    var i = 0
    while (i < stock.length) {
      checksum = (checksum * 16777619L) ^ stock(i).toLong ^ i.toLong
      i += 1
    }
    i = 0
    while (i < prices.length) {
      checksum = (checksum * 1099511628211L) ^ prices(i).toLong ^ (i.toLong << 5)
      i += 1
    }
    i = 0
    while (i < customers.length) {
      checksum = (checksum * 1315423911L) ^ customers(i) ^ (i.toLong << 11)
      i += 1
    }
    i = 0
    while (i < revenue.length) {
      checksum = (checksum * 2654435761L) ^ revenue(i) ^ (i.toLong << 13)
      i += 1
    }
    i = 0
    while (i < orders.length) {
      checksum = (checksum * 889523592379L) ^ orders(i) ^ (i.toLong << 17)
      i += 1
    }
    i = 0
    while (i < districts.length) {
      checksum = (checksum * 6364136223846793005L) ^ districts(i)
      i += 1
    }
    checksum
  }

  private def newWarehouseState(): WarehouseState = {
    val cfg = SpecJbb2005PortConfig
    WarehouseState(
      stock = initStock(cfg.warehouses * cfg.products),
      prices = initProducts(cfg.products),
      customers = initCustomers(cfg.warehouses * cfg.customersPerWarehouse),
      revenue = new Array[Long](cfg.warehouses),
      orders = new Array[Long](cfg.warehouses),
      districts = new Array[Long](cfg.warehouses * cfg.districtsPerWarehouse)
    )
  }

  private final case class WarehouseState(
      stock: Array[Int],
      prices: Array[Int],
      customers: Array[Long],
      revenue: Array[Long],
      orders: Array[Long],
      districts: Array[Long]
  )

  private def customerIndex(warehouse: Int, customer: Int): Int =
    warehouse * SpecJbb2005PortConfig.customersPerWarehouse + customer

  private def districtIndex(warehouse: Int, district: Int): Int =
    warehouse * SpecJbb2005PortConfig.districtsPerWarehouse + district

  private def stockIndex(warehouse: Int, product: Int): Int =
    warehouse * SpecJbb2005PortConfig.products + product

  private def processTopLevelReceipt(
      receipt: TxReceipt,
      request: TxRequest,
      state: WarehouseState
  ): Long = {
    val cfg = SpecJbb2005PortConfig
    val wh = request.warehouse
    val customerSlot = customerIndex(wh, request.customer)
    val districtSlot = districtIndex(wh, request.district)
    var checksum =
      receipt.id.toLong ^ (receipt.kind.toLong << 8) ^ receipt.totalCents

    receipt.kind match {
      case 0 =>
        var line = receipt.lines
        while (line != null) {
          val slot = stockIndex(wh, line.product)
          state.stock(slot) -= line.quantity
          checksum += state.stock(slot).toLong ^ line.priceCents.toLong
          line = line.next
        }
        state.orders(wh) += 1L
        state.revenue(wh) += receipt.totalCents
        state.customers(customerSlot) -= receipt.totalCents / 100L
        state.districts(districtSlot) ^= state.orders(wh) + receipt.id.toLong

      case 1 =>
        state.revenue(wh) += request.amountCents
        state.customers(customerSlot) += request.amountCents
        checksum ^= state.customers(customerSlot)

      case 2 =>
        checksum ^= state.customers(customerSlot)
        checksum ^= state.orders(wh)

      case 3 =>
        state.orders(wh) += 1L
        state.customers(customerSlot) -= request.amountCents / 10L
        state.districts(districtSlot) += 1L
        checksum ^= state.districts(districtSlot)

      case _ =>
        var below = 0
        var probe = receipt.probes
        while (probe != null) {
          val slot = stockIndex(wh, probe.product)
          val current = state.stock(slot)
          if (current < 100010) below += 1
          checksum ^= current.toLong + probe.quantity.toLong
          probe = probe.next
        }
        state.districts(districtSlot) ^= below.toLong + cfg.itemsPerOrder.toLong
    }

    checksum ^ receipt.status.toLong ^ receipt.lineCount.toLong
  }

  def runHeapOrRift(modeName: String): Long = {
    val cfg = SpecJbb2005PortConfig
    val mode = new ModeState(modeName)
    val state = newWarehouseState()
    var transactionChecksum = 0L

    var tx = 0
    try {
      while (tx < cfg.totalTransactions) {
        val region = mode.beginBatch()
        val batchEnd =
          math.min(cfg.totalTransactions, tx + cfg.transactionsPerRegion)
        while (tx < batchEnd) {
          val warehouse = warehouseFor(tx)
          val district = mix(tx + 31) % cfg.districtsPerWarehouse
          val customer = mix(tx + 101) % cfg.customersPerWarehouse
          val kind = txKind(tx, warehouse)
          val amount = 1000L + (mixLong(tx.toLong * 65537L + 19L) % 100000L)
          var lines: TxLine = null
          var probes: TxProbe = null
          var total = 0L
          var item = 0

          if (kind == 0) {
            while (item < cfg.itemsPerOrder) {
              val seed = mix(tx * 104729 + item * 8191)
              val product = seed % cfg.products
              val quantity = (mix(seed + 19) & 3) + 1
              val price = state.prices(product)
              total += quantity.toLong * price.toLong
              lines = mode.allocLine(region, product, quantity, price, lines)
              item += 1
            }
          } else if (kind == 4) {
            while (item < cfg.itemsPerOrder) {
              val seed = mix(tx * 524287 + item * 4099)
              val product = seed % cfg.products
              val quantity = (mix(seed + 29) & 7) + 1
              val current = state.stock(stockIndex(warehouse, product))
              probes = mode.allocProbe(
                region,
                product,
                quantity,
                current < 100010,
                probes
              )
              item += 1
            }
            total = amount
          } else {
            total = amount
          }

          val request = mode.allocRequest(
            region,
            tx,
            warehouse,
            customer,
            district,
            kind,
            amount,
            lines,
            probes
          )
          val status = (kind << 16) ^ customer ^ district
          val receipt = mode.allocReceipt(
            region,
            tx,
            warehouse,
            customer,
            kind,
            status,
            total,
            if (kind == 0 || kind == 4) cfg.itemsPerOrder else 0,
            lines,
            probes
          )
          transactionChecksum =
            (transactionChecksum * 16777619L) ^
              processTopLevelReceipt(receipt, request, state)
          tx += 1
        }
        mode.endBatch(region)
      }
    } finally mode.finish()

    val checksum = checksumState(
      state.stock,
      state.prices,
      state.customers,
      state.revenue,
      state.orders,
      state.districts,
      transactionChecksum
    )
    checksumSink = checksum
    checksum
  }

  def runSafeZone(): Long = {
    val cfg = SpecJbb2005PortConfig
    val state = newWarehouseState()
    var transactionChecksum = 0L

    var tx = 0
    while (tx < cfg.totalTransactions) {
      val batchStart = tx
      val batchEnd =
        math.min(cfg.totalTransactions, tx + cfg.transactionsPerRegion)
      SafeZone { sz ?=>
        final class SZLine(
            val product: Int,
            val quantity: Int,
            val priceCents: Int,
            val next: SZLine^{sz}
        )

        final class SZProbe(
            val product: Int,
            val quantity: Int,
            val belowThreshold: Boolean,
            val next: SZProbe^{sz}
        )

        final class SZRequest(
            val id: Int,
            val warehouse: Int,
            val customer: Int,
            val district: Int,
            val kind: Int,
            val amountCents: Long,
            val lines: SZLine^{sz},
            val probes: SZProbe^{sz}
        )

        final class SZReceipt(
            val id: Int,
            val warehouse: Int,
            val customer: Int,
            val kind: Int,
            val status: Int,
            val totalCents: Long,
            val lineCount: Int,
            val lines: SZLine^{sz},
            val probes: SZProbe^{sz}
        )

        def processSZReceipt(
            receipt: SZReceipt^{sz},
            request: SZRequest^{sz}
        ): Long = {
          val wh = request.warehouse
          val customerSlot = customerIndex(wh, request.customer)
          val districtSlot = districtIndex(wh, request.district)
          var checksum =
            receipt.id.toLong ^ (receipt.kind.toLong << 8) ^ receipt.totalCents

          receipt.kind match {
            case 0 =>
              var line = receipt.lines
              while (line != null) {
                val slot = stockIndex(wh, line.product)
                state.stock(slot) -= line.quantity
                checksum += state.stock(slot).toLong ^ line.priceCents.toLong
                line = line.next
              }
              state.orders(wh) += 1L
              state.revenue(wh) += receipt.totalCents
              state.customers(customerSlot) -= receipt.totalCents / 100L
              state.districts(districtSlot) ^= state.orders(wh) + receipt.id.toLong

            case 1 =>
              state.revenue(wh) += request.amountCents
              state.customers(customerSlot) += request.amountCents
              checksum ^= state.customers(customerSlot)

            case 2 =>
              checksum ^= state.customers(customerSlot)
              checksum ^= state.orders(wh)

            case 3 =>
              state.orders(wh) += 1L
              state.customers(customerSlot) -= request.amountCents / 10L
              state.districts(districtSlot) += 1L
              checksum ^= state.districts(districtSlot)

            case _ =>
              var below = 0
              var probe = receipt.probes
              while (probe != null) {
                val slot = stockIndex(wh, probe.product)
                val current = state.stock(slot)
                if (current < 100010) below += 1
                checksum ^= current.toLong + probe.quantity.toLong
                probe = probe.next
              }
              state.districts(districtSlot) ^=
                below.toLong + cfg.itemsPerOrder.toLong
          }

          checksum ^ receipt.status.toLong ^ receipt.lineCount.toLong
        }

        var currentTx = batchStart
        while (currentTx < batchEnd) {
          val warehouse = warehouseFor(currentTx)
          val district = mix(currentTx + 31) % cfg.districtsPerWarehouse
          val customer = mix(currentTx + 101) % cfg.customersPerWarehouse
          val kind = txKind(currentTx, warehouse)
          val amount =
            1000L + (mixLong(currentTx.toLong * 65537L + 19L) % 100000L)
          var lines: SZLine^{sz} = null
          var probes: SZProbe^{sz} = null
          var total = 0L
          var item = 0

          if (kind == 0) {
            while (item < cfg.itemsPerOrder) {
              val seed = mix(currentTx * 104729 + item * 8191)
              val product = seed % cfg.products
              val quantity = (mix(seed + 19) & 3) + 1
              val price = state.prices(product)
              total += quantity.toLong * price.toLong
              lines = SafeZoneAllocator.allocate(
                sz,
                new SZLine(product, quantity, price, lines)
              )
              item += 1
            }
          } else if (kind == 4) {
            while (item < cfg.itemsPerOrder) {
              val seed = mix(currentTx * 524287 + item * 4099)
              val product = seed % cfg.products
              val quantity = (mix(seed + 29) & 7) + 1
              val current = state.stock(stockIndex(warehouse, product))
              probes = SafeZoneAllocator.allocate(
                sz,
                new SZProbe(product, quantity, current < 100010, probes)
              )
              item += 1
            }
            total = amount
          } else {
            total = amount
          }

          val request = SafeZoneAllocator.allocate(
            sz,
            new SZRequest(
              currentTx,
              warehouse,
              customer,
              district,
              kind,
              amount,
              lines,
              probes
            )
          )
          val status = (kind << 16) ^ customer ^ district
          val receipt = SafeZoneAllocator.allocate(
            sz,
            new SZReceipt(
              currentTx,
              warehouse,
              customer,
              kind,
              status,
              total,
              if (kind == 0 || kind == 4) cfg.itemsPerOrder else 0,
              lines,
              probes
            )
          )

          transactionChecksum =
            (transactionChecksum * 16777619L) ^
              processSZReceipt(receipt, request)
          currentTx += 1
        }
      }
      tx = batchEnd
    }

    val checksum = checksumState(
      state.stock,
      state.prices,
      state.customers,
      state.revenue,
      state.orders,
      state.districts,
      transactionChecksum
    )
    checksumSink = checksum
    checksum
  }

  def runCheckedDirectEpoch(safeZoneBackend: Boolean): Long = {
    val cfg = SpecJbb2005PortConfig
    val state = newWarehouseState()
    var transactionChecksum = 0L

    def run()(using stream: RiftRegion.StreamingRegion^): Unit = {
      var tx = 0
      while (tx < cfg.totalTransactions) {
        val batchStart = tx
        val batchEnd =
          math.min(cfg.totalTransactions, tx + cfg.transactionsPerRegion)
        RiftRegion.epoch { region ?=>
          final class CheckedLine(
              val product: Int,
              val quantity: Int,
              val priceCents: Int,
              val next: CheckedLine^{region}
          )

          final class CheckedProbe(
              val product: Int,
              val quantity: Int,
              val belowThreshold: Boolean,
              val next: CheckedProbe^{region}
          )

          final class CheckedRequest(
              val id: Int,
              val warehouse: Int,
              val customer: Int,
              val district: Int,
              val kind: Int,
              val amountCents: Long,
              val lines: CheckedLine^{region},
              val probes: CheckedProbe^{region}
          )

          final class CheckedReceipt(
              val id: Int,
              val warehouse: Int,
              val customer: Int,
              val kind: Int,
              val status: Int,
              val totalCents: Long,
              val lineCount: Int,
              val lines: CheckedLine^{region},
              val probes: CheckedProbe^{region}
          )

          def processCheckedReceipt(
              receipt: CheckedReceipt^{region},
              request: CheckedRequest^{region}
          ): Long = {
            val wh = request.warehouse
            val customerSlot = customerIndex(wh, request.customer)
            val districtSlot = districtIndex(wh, request.district)
            var checksum =
              receipt.id.toLong ^ (receipt.kind.toLong << 8) ^ receipt.totalCents

            receipt.kind match {
              case 0 =>
                var line = receipt.lines
                while (line != null) {
                  val slot = stockIndex(wh, line.product)
                  state.stock(slot) -= line.quantity
                  checksum += state.stock(slot).toLong ^ line.priceCents.toLong
                  line = line.next
                }
                state.orders(wh) += 1L
                state.revenue(wh) += receipt.totalCents
                state.customers(customerSlot) -= receipt.totalCents / 100L
                state.districts(districtSlot) ^=
                  state.orders(wh) + receipt.id.toLong

              case 1 =>
                state.revenue(wh) += request.amountCents
                state.customers(customerSlot) += request.amountCents
                checksum ^= state.customers(customerSlot)

              case 2 =>
                checksum ^= state.customers(customerSlot)
                checksum ^= state.orders(wh)

              case 3 =>
                state.orders(wh) += 1L
                state.customers(customerSlot) -= request.amountCents / 10L
                state.districts(districtSlot) += 1L
                checksum ^= state.districts(districtSlot)

              case _ =>
                var below = 0
                var probe = receipt.probes
                while (probe != null) {
                  val slot = stockIndex(wh, probe.product)
                  val current = state.stock(slot)
                  if (current < 100010) below += 1
                  checksum ^= current.toLong + probe.quantity.toLong
                  probe = probe.next
                }
                state.districts(districtSlot) ^=
                  below.toLong + cfg.itemsPerOrder.toLong
            }

            checksum ^ receipt.status.toLong ^ receipt.lineCount.toLong
          }

          var currentTx = batchStart
          while (currentTx < batchEnd) {
            val warehouse = warehouseFor(currentTx)
            val district = mix(currentTx + 31) % cfg.districtsPerWarehouse
            val customer = mix(currentTx + 101) % cfg.customersPerWarehouse
            val kind = txKind(currentTx, warehouse)
            val amount =
              1000L + (mixLong(currentTx.toLong * 65537L + 19L) % 100000L)
            var lines: CheckedLine^{region} = null
            var probes: CheckedProbe^{region} = null
            var total = 0L
            var item = 0

            if (kind == 0) {
              while (item < cfg.itemsPerOrder) {
                val seed = mix(currentTx * 104729 + item * 8191)
                val product = seed % cfg.products
                val quantity = (mix(seed + 19) & 3) + 1
                val price = state.prices(product)
                total += quantity.toLong * price.toLong
                lines = RiftRegion.allocOpen(
                  new CheckedLine(product, quantity, price, lines)
                )
                item += 1
              }
            } else if (kind == 4) {
              while (item < cfg.itemsPerOrder) {
                val seed = mix(currentTx * 524287 + item * 4099)
                val product = seed % cfg.products
                val quantity = (mix(seed + 29) & 7) + 1
                val current = state.stock(stockIndex(warehouse, product))
                probes = RiftRegion.allocOpen(
                  new CheckedProbe(product, quantity, current < 100010, probes)
                )
                item += 1
              }
              total = amount
            } else {
              total = amount
            }

            val request = RiftRegion.allocOpen(
              new CheckedRequest(
                currentTx,
                warehouse,
                customer,
                district,
                kind,
                amount,
                lines,
                probes
              )
            )
            val status = (kind << 16) ^ customer ^ district
            val receipt = RiftRegion.allocOpen(
              new CheckedReceipt(
                currentTx,
                warehouse,
                customer,
                kind,
                status,
                total,
                if (kind == 0 || kind == 4) cfg.itemsPerOrder else 0,
                lines,
                probes
              )
            )

            transactionChecksum =
              (transactionChecksum * 16777619L) ^
                processCheckedReceipt(receipt, request)
            currentTx += 1
          }
        }
        tx = batchEnd
      }
    }

    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> run() }
    else RiftRegion.streaming { stream ?=> run() }

    val checksum = checksumState(
      state.stock,
      state.prices,
      state.customers,
      state.revenue,
      state.orders,
      state.districts,
      transactionChecksum
    )
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
    if (mode == "safezone") runSafeZone()
    else if (mode == "rift-checked-direct-epoch")
      runCheckedDirectEpoch(safeZoneBackend = false)
    else if (mode == "rift-checked-safezone-direct-epoch")
      runCheckedDirectEpoch(safeZoneBackend = true)
    else runHeapOrRift(mode)

  private def logicalRegionObjects(): Long = {
    val cfg = SpecJbb2005PortConfig
    var count = 0L
    var tx = 0
    while (tx < cfg.totalTransactions) {
      count += txObjectCount(tx)
      tx += 1
    }
    count
  }

  private def logicalRegionBytesProxy(): Long = {
    val cfg = SpecJbb2005PortConfig
    var bytes = 0L
    var tx = 0
    while (tx < cfg.totalTransactions) {
      bytes += txByteProxy(tx)
      tx += 1
    }
    bytes
  }

  private def maxLiveRegionObjectsProxy(): Long = {
    val cfg = SpecJbb2005PortConfig
    var maxLive = 0L
    var tx = 0
    while (tx < cfg.totalTransactions) {
      val batchEnd = math.min(cfg.totalTransactions, tx + cfg.transactionsPerRegion)
      var live = 0L
      var current = tx
      while (current < batchEnd) {
        live += txObjectCount(current)
        current += 1
      }
      if (live > maxLive) maxLive = live
      tx = batchEnd
    }
    maxLive
  }

  private def maxLiveRegionBytesProxy(): Long = {
    val cfg = SpecJbb2005PortConfig
    var maxLive = 0L
    var tx = 0
    while (tx < cfg.totalTransactions) {
      val batchEnd = math.min(cfg.totalTransactions, tx + cfg.transactionsPerRegion)
      var live = 0L
      var current = tx
      while (current < batchEnd) {
        live += txByteProxy(current)
        current += 1
      }
      if (live > maxLive) maxLive = live
      tx = batchEnd
    }
    maxLive
  }

  private def durableControlSlots(): Long = {
    val cfg = SpecJbb2005PortConfig
    cfg.warehouses.toLong * cfg.products.toLong +
      cfg.products.toLong +
      cfg.warehouses.toLong * cfg.customersPerWarehouse.toLong +
      cfg.warehouses.toLong +
      cfg.warehouses.toLong +
      cfg.warehouses.toLong * cfg.districtsPerWarehouse.toLong
  }

  private def candidateBasisPoints(): Long = {
    val regionObjects = logicalRegionObjects()
    val total = regionObjects + durableControlSlots()
    if (total == 0L) 0L else (regionObjects * 10000L) / total
  }

  def runBenchmark(mode: String): Unit = {
    val cfg = SpecJbb2005PortConfig
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
    val gcCollections = new Array[Long](cfg.benchmarkRuns)
    val riftOpNanos = new Array[Long](cfg.benchmarkRuns)
    val riftSlowNanos = new Array[Long](cfg.benchmarkRuns)
    val riftObjects = new Array[Long](cfg.benchmarkRuns)
    val riftOpens = new Array[Long](cfg.benchmarkRuns)
    val riftCloses = new Array[Long](cfg.benchmarkRuns)
    val riftResets = new Array[Long](cfg.benchmarkRuns)

    println(
      s"Running specjbb2005-port-$mode for ${cfg.benchmarkRuns} timed runs"
    )
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
      gcCollections(run) = runtime.gcCollections
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
      f"RESULT name=specjbb2005-port-$mode " +
        f"median_ms=${medianDouble(elapsedMs)}%.3f " +
        f"min_ms=${elapsedMs.min}%.3f " +
        f"max_ms=${elapsedMs.max}%.3f " +
        f"median_gc_ms=${medianLong(gcNanos) / 1000000.0}%.3f " +
        f"max_gc_ms=${gcNanos.max / 1000000.0}%.3f " +
        f"median_gc_collections=${medianLong(gcCollections)}%d " +
        f"median_rift_op_ms=${medianLong(riftOpNanos) / 1000000.0}%.3f " +
        f"median_rift_slow_alloc_ms=${medianLong(riftSlowNanos) / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=${medianLong(riftObjects)}%d " +
        f"median_rift_open_total=${medianLong(riftOpens)}%d " +
        f"median_rift_close_total=${medianLong(riftCloses)}%d " +
        f"median_rift_reset_total=${medianLong(riftResets)}%d " +
        f"logical_region_objects=${logicalRegionObjects()}%d " +
        f"region_freed_object_proxy=${logicalRegionObjects()}%d " +
        f"logical_region_byte_proxy=${logicalRegionBytesProxy()}%d " +
        f"region_freed_byte_proxy=${logicalRegionBytesProxy()}%d " +
        f"max_live_region_object_proxy=${maxLiveRegionObjectsProxy()}%d " +
        f"max_live_region_byte_proxy=${maxLiveRegionBytesProxy()}%d " +
        f"durable_control_slots=${durableControlSlots()}%d " +
        f"candidate_region_object_bp=${candidateBasisPoints()}%d " +
        f"transactions=${cfg.totalTransactions}%d " +
        f"warehouses=${cfg.warehouses}%d " +
        f"iterations_per_warehouse=${cfg.iterationsPerWarehouse}%d " +
        f"items_per_order=${cfg.itemsPerOrder}%d " +
        f"transactions_per_region=${cfg.transactionsPerRegion}%d " +
        f"annotation_api_boundaries=1 " +
        f"explicit_region_boundaries=1 " +
        f"escaped_region_objects=0 " +
        f"official_specjbb2005=0 " +
        f"checksum=$expected%d"
    )
  }

  def printConfig(mode: String): Unit = {
    val cfg = SpecJbb2005PortConfig
    val rootsMode = sys.env.getOrElse("SAFEZONE_ROOTS_MODE", "0")
    val pageSize = sys.env.getOrElse("SAFEZONE_PAGE_SIZE", "")
    println(
      s"CONFIG benchmark=SPECjbb2005-workload-Scala-Native-port official_specjbb2005=0 mode=$mode runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} warehouses=${cfg.warehouses} iterations_per_warehouse=${cfg.iterationsPerWarehouse} total_transactions=${cfg.totalTransactions} items_per_order=${cfg.itemsPerOrder} tx_per_region=${cfg.transactionsPerRegion} products=${cfg.products} customers_per_warehouse=${cfg.customersPerWarehouse} districts_per_warehouse=${cfg.districtsPerWarehouse} safezone_roots_mode=$rootsMode safezone_page_size=$pageSize"
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
          s"unknown SpecJbb2005Port mode '$other'; expected heap, safezone, rift-hp, rift-streaming, rift-checked-direct-epoch, or rift-checked-safezone-direct-epoch"
        )
    }
}

@main def SpecJbb2005PortMatrix(mode: String = "heap"): Unit = {
  SpecJbb2005PortMatrixHelpers.validateMode(mode)
  SpecJbb2005PortMatrixHelpers.printConfig(mode)

  val usesRift =
    mode == "rift-hp" ||
      mode == "rift-streaming" ||
      mode == "rift-checked-direct-epoch"
  if (usesRift) RiftRegion.init(0)
  try {
    SpecJbb2005PortMatrixHelpers.runBenchmark(mode)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
