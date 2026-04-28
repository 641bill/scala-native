package debs2015

object Debs2015ProcessDiagnostics {
  final case class Snapshot(
      q1WindowEntriesCreated: Long,
      q1BucketOpens: Long,
      q1BucketCloses: Long,
      q1RouteTableProbeSteps: Long,
      q1RouteTableRehashes: Long,
      q1RouteTableRehashSlots: Long,
      q1RankRefreshes: Long,
      q1RankHeapCompares: Long,
      q1RankHeapSwaps: Long,
      q1TopCandidateCompares: Long,
      q2ProfitEntriesCreated: Long,
      q2EmptyEntriesCreated: Long,
      q2ProfitBucketOpens: Long,
      q2ProfitBucketCloses: Long,
      q2EmptyBucketOpens: Long,
      q2EmptyBucketCloses: Long
  ) {
    def since(start: Snapshot): Snapshot =
      Snapshot(
        q1WindowEntriesCreated - start.q1WindowEntriesCreated,
        q1BucketOpens - start.q1BucketOpens,
        q1BucketCloses - start.q1BucketCloses,
        q1RouteTableProbeSteps - start.q1RouteTableProbeSteps,
        q1RouteTableRehashes - start.q1RouteTableRehashes,
        q1RouteTableRehashSlots - start.q1RouteTableRehashSlots,
        q1RankRefreshes - start.q1RankRefreshes,
        q1RankHeapCompares - start.q1RankHeapCompares,
        q1RankHeapSwaps - start.q1RankHeapSwaps,
        q1TopCandidateCompares - start.q1TopCandidateCompares,
        q2ProfitEntriesCreated - start.q2ProfitEntriesCreated,
        q2EmptyEntriesCreated - start.q2EmptyEntriesCreated,
        q2ProfitBucketOpens - start.q2ProfitBucketOpens,
        q2ProfitBucketCloses - start.q2ProfitBucketCloses,
        q2EmptyBucketOpens - start.q2EmptyBucketOpens,
        q2EmptyBucketCloses - start.q2EmptyBucketCloses
      )
  }

  val zero: Snapshot =
    Snapshot(
      0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
      0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L
    )

  val enabled: Boolean = {
    val env = System.getenv("DEBS2015_PROCESS_DIAGNOSTICS")
    env != null && env.nonEmpty && env != "0"
  }

  private var q1WindowEntriesCreated = 0L
  private var q1BucketOpens = 0L
  private var q1BucketCloses = 0L
  private var q1RouteTableProbeSteps = 0L
  private var q1RouteTableRehashes = 0L
  private var q1RouteTableRehashSlots = 0L
  private var q1RankRefreshes = 0L
  private var q1RankHeapCompares = 0L
  private var q1RankHeapSwaps = 0L
  private var q1TopCandidateCompares = 0L
  private var q2ProfitEntriesCreated = 0L
  private var q2EmptyEntriesCreated = 0L
  private var q2ProfitBucketOpens = 0L
  private var q2ProfitBucketCloses = 0L
  private var q2EmptyBucketOpens = 0L
  private var q2EmptyBucketCloses = 0L

  def reset(): Unit = {
    q1WindowEntriesCreated = 0L
    q1BucketOpens = 0L
    q1BucketCloses = 0L
    q1RouteTableProbeSteps = 0L
    q1RouteTableRehashes = 0L
    q1RouteTableRehashSlots = 0L
    q1RankRefreshes = 0L
    q1RankHeapCompares = 0L
    q1RankHeapSwaps = 0L
    q1TopCandidateCompares = 0L
    q2ProfitEntriesCreated = 0L
    q2EmptyEntriesCreated = 0L
    q2ProfitBucketOpens = 0L
    q2ProfitBucketCloses = 0L
    q2EmptyBucketOpens = 0L
    q2EmptyBucketCloses = 0L
  }

  def snapshot(): Snapshot =
    Snapshot(
      q1WindowEntriesCreated,
      q1BucketOpens,
      q1BucketCloses,
      q1RouteTableProbeSteps,
      q1RouteTableRehashes,
      q1RouteTableRehashSlots,
      q1RankRefreshes,
      q1RankHeapCompares,
      q1RankHeapSwaps,
      q1TopCandidateCompares,
      q2ProfitEntriesCreated,
      q2EmptyEntriesCreated,
      q2ProfitBucketOpens,
      q2ProfitBucketCloses,
      q2EmptyBucketOpens,
      q2EmptyBucketCloses
    )

  def recordQ1WindowEntry(): Unit =
    if (enabled) q1WindowEntriesCreated += 1L

  def recordQ1BucketOpen(): Unit =
    if (enabled) q1BucketOpens += 1L

  def recordQ1BucketClose(): Unit =
    if (enabled) q1BucketCloses += 1L

  def recordQ1RouteTableProbe(): Unit =
    if (enabled) q1RouteTableProbeSteps += 1L

  def recordQ1RouteTableRehash(newCapacity: Int): Unit = {
    if (enabled) {
      q1RouteTableRehashes += 1L
      q1RouteTableRehashSlots += newCapacity.toLong
    }
  }

  def recordQ1RankRefresh(): Unit =
    if (enabled) q1RankRefreshes += 1L

  def recordQ1RankHeapCompare(): Unit =
    if (enabled) q1RankHeapCompares += 1L

  def recordQ1RankHeapSwap(): Unit =
    if (enabled) q1RankHeapSwaps += 1L

  def recordQ1TopCandidateCompare(): Unit =
    if (enabled) q1TopCandidateCompares += 1L

  def recordQ2ProfitEntry(): Unit =
    if (enabled) q2ProfitEntriesCreated += 1L

  def recordQ2EmptyEntry(): Unit =
    if (enabled) q2EmptyEntriesCreated += 1L

  def recordQ2ProfitBucketOpen(): Unit =
    if (enabled) q2ProfitBucketOpens += 1L

  def recordQ2ProfitBucketClose(): Unit =
    if (enabled) q2ProfitBucketCloses += 1L

  def recordQ2EmptyBucketOpen(): Unit =
    if (enabled) q2EmptyBucketOpens += 1L

  def recordQ2EmptyBucketClose(): Unit =
    if (enabled) q2EmptyBucketCloses += 1L
}
