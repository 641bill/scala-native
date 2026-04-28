package debs2015

object Debs2015Q2CpuDiagnostics {
  final case class Snapshot(
      evictProfitNanos: Long,
      evictEmptyNanos: Long,
      taxiLookupNanos: Long,
      previousEmptyNanos: Long,
      profitPathNanos: Long,
      profitRankNanos: Long,
      emptyPathNanos: Long,
      emptyRankNanos: Long,
      top10Nanos: Long
  ) {
    def since(start: Snapshot): Snapshot =
      Snapshot(
        evictProfitNanos - start.evictProfitNanos,
        evictEmptyNanos - start.evictEmptyNanos,
        taxiLookupNanos - start.taxiLookupNanos,
        previousEmptyNanos - start.previousEmptyNanos,
        profitPathNanos - start.profitPathNanos,
        profitRankNanos - start.profitRankNanos,
        emptyPathNanos - start.emptyPathNanos,
        emptyRankNanos - start.emptyRankNanos,
        top10Nanos - start.top10Nanos
      )

    def recordedNanos: Long =
      evictProfitNanos +
        evictEmptyNanos +
        taxiLookupNanos +
        previousEmptyNanos +
        profitPathNanos +
        profitRankNanos +
        emptyPathNanos +
        emptyRankNanos +
        top10Nanos
  }

  val zero: Snapshot =
    Snapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)

  val enabled: Boolean = {
    val env = System.getenv("DEBS2015_Q2_CPU_DIAGNOSTICS")
    env != null && env.nonEmpty && env != "0"
  }

  private var evictProfitNanos = 0L
  private var evictEmptyNanos = 0L
  private var taxiLookupNanos = 0L
  private var previousEmptyNanos = 0L
  private var profitPathNanos = 0L
  private var profitRankNanos = 0L
  private var emptyPathNanos = 0L
  private var emptyRankNanos = 0L
  private var top10Nanos = 0L

  def reset(): Unit = {
    evictProfitNanos = 0L
    evictEmptyNanos = 0L
    taxiLookupNanos = 0L
    previousEmptyNanos = 0L
    profitPathNanos = 0L
    profitRankNanos = 0L
    emptyPathNanos = 0L
    emptyRankNanos = 0L
    top10Nanos = 0L
  }

  def snapshot(): Snapshot =
    Snapshot(
      evictProfitNanos,
      evictEmptyNanos,
      taxiLookupNanos,
      previousEmptyNanos,
      profitPathNanos,
      profitRankNanos,
      emptyPathNanos,
      emptyRankNanos,
      top10Nanos
    )

  def recordEvictProfit(nanos: Long): Unit =
    if (enabled) evictProfitNanos += nanos

  def recordEvictEmpty(nanos: Long): Unit =
    if (enabled) evictEmptyNanos += nanos

  def recordTaxiLookup(nanos: Long): Unit =
    if (enabled) taxiLookupNanos += nanos

  def recordPreviousEmpty(nanos: Long): Unit =
    if (enabled) previousEmptyNanos += nanos

  def recordProfitPath(nanos: Long): Unit =
    if (enabled) profitPathNanos += nanos

  def recordProfitRank(nanos: Long): Unit =
    if (enabled) profitRankNanos += nanos

  def recordEmptyPath(nanos: Long): Unit =
    if (enabled) emptyPathNanos += nanos

  def recordEmptyRank(nanos: Long): Unit =
    if (enabled) emptyRankNanos += nanos

  def recordTop10(nanos: Long): Unit =
    if (enabled) top10Nanos += nanos
}
