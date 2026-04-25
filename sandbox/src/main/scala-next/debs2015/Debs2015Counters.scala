package debs2015

object Debs2015Counters {
  final case class Snapshot(
      gridQ1Calls: Long,
      gridQ1Hits: Long,
      gridQ2Calls: Long,
      gridQ2Hits: Long,
      q1RankAdds: Long,
      q1RankRemoves: Long,
      q1RankCreated: Long,
      q1Top10Calls: Long,
      q1ResultArrayAllocs: Long,
      q1ResultArraySlots: Long,
      q2RankAdds: Long,
      q2RankRemoves: Long,
      q2RankFixes: Long,
      q2RankCreated: Long,
      q2Top10Calls: Long,
      q2ResultArrayAllocs: Long,
      q2ResultArraySlots: Long,
      q2MedianComputes: Long,
      q2MedianValuesSorted: Long,
      q2MedianReads: Long,
      q2MedianHeapAdds: Long,
      q2MedianHeapRemoves: Long,
      q2MedianRebalances: Long,
      q1SnapshotAllocs: Long,
      q1SnapshotSlots: Long,
      q2SnapshotAllocs: Long,
      q2SnapshotArrayAllocs: Long,
      q2SnapshotSlots: Long,
      q1LatencyAppends: Long,
      q2LatencyAppends: Long,
      taxiLookups: Long,
      taxiHits: Long,
      taxiMisses: Long,
      taxiEntriesScanned: Long,
      taxiEntriesCreated: Long
  ) {
    def since(start: Snapshot): Snapshot =
      Snapshot(
        gridQ1Calls - start.gridQ1Calls,
        gridQ1Hits - start.gridQ1Hits,
        gridQ2Calls - start.gridQ2Calls,
        gridQ2Hits - start.gridQ2Hits,
        q1RankAdds - start.q1RankAdds,
        q1RankRemoves - start.q1RankRemoves,
        q1RankCreated - start.q1RankCreated,
        q1Top10Calls - start.q1Top10Calls,
        q1ResultArrayAllocs - start.q1ResultArrayAllocs,
        q1ResultArraySlots - start.q1ResultArraySlots,
        q2RankAdds - start.q2RankAdds,
        q2RankRemoves - start.q2RankRemoves,
        q2RankFixes - start.q2RankFixes,
        q2RankCreated - start.q2RankCreated,
        q2Top10Calls - start.q2Top10Calls,
        q2ResultArrayAllocs - start.q2ResultArrayAllocs,
        q2ResultArraySlots - start.q2ResultArraySlots,
        q2MedianComputes - start.q2MedianComputes,
        q2MedianValuesSorted - start.q2MedianValuesSorted,
        q2MedianReads - start.q2MedianReads,
        q2MedianHeapAdds - start.q2MedianHeapAdds,
        q2MedianHeapRemoves - start.q2MedianHeapRemoves,
        q2MedianRebalances - start.q2MedianRebalances,
        q1SnapshotAllocs - start.q1SnapshotAllocs,
        q1SnapshotSlots - start.q1SnapshotSlots,
        q2SnapshotAllocs - start.q2SnapshotAllocs,
        q2SnapshotArrayAllocs - start.q2SnapshotArrayAllocs,
        q2SnapshotSlots - start.q2SnapshotSlots,
        q1LatencyAppends - start.q1LatencyAppends,
        q2LatencyAppends - start.q2LatencyAppends,
        taxiLookups - start.taxiLookups,
        taxiHits - start.taxiHits,
        taxiMisses - start.taxiMisses,
        taxiEntriesScanned - start.taxiEntriesScanned,
        taxiEntriesCreated - start.taxiEntriesCreated
      )
  }

  val zero: Snapshot =
    Snapshot(
      0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
      0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
      0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
      0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
      0L, 0L, 0L
    )

  private var gridQ1Calls = 0L
  private var gridQ1Hits = 0L
  private var gridQ2Calls = 0L
  private var gridQ2Hits = 0L
  private var q1RankAdds = 0L
  private var q1RankRemoves = 0L
  private var q1RankCreated = 0L
  private var q1Top10Calls = 0L
  private var q1ResultArrayAllocs = 0L
  private var q1ResultArraySlots = 0L
  private var q2RankAdds = 0L
  private var q2RankRemoves = 0L
  private var q2RankFixes = 0L
  private var q2RankCreated = 0L
  private var q2Top10Calls = 0L
  private var q2ResultArrayAllocs = 0L
  private var q2ResultArraySlots = 0L
  private var q2MedianComputes = 0L
  private var q2MedianValuesSorted = 0L
  private var q2MedianReads = 0L
  private var q2MedianHeapAdds = 0L
  private var q2MedianHeapRemoves = 0L
  private var q2MedianRebalances = 0L
  private var q1SnapshotAllocs = 0L
  private var q1SnapshotSlots = 0L
  private var q2SnapshotAllocs = 0L
  private var q2SnapshotArrayAllocs = 0L
  private var q2SnapshotSlots = 0L
  private var q1LatencyAppends = 0L
  private var q2LatencyAppends = 0L
  private var taxiLookups = 0L
  private var taxiHits = 0L
  private var taxiMisses = 0L
  private var taxiEntriesScanned = 0L
  private var taxiEntriesCreated = 0L

  def reset(): Unit = {
    gridQ1Calls = 0L
    gridQ1Hits = 0L
    gridQ2Calls = 0L
    gridQ2Hits = 0L
    q1RankAdds = 0L
    q1RankRemoves = 0L
    q1RankCreated = 0L
    q1Top10Calls = 0L
    q1ResultArrayAllocs = 0L
    q1ResultArraySlots = 0L
    q2RankAdds = 0L
    q2RankRemoves = 0L
    q2RankFixes = 0L
    q2RankCreated = 0L
    q2Top10Calls = 0L
    q2ResultArrayAllocs = 0L
    q2ResultArraySlots = 0L
    q2MedianComputes = 0L
    q2MedianValuesSorted = 0L
    q2MedianReads = 0L
    q2MedianHeapAdds = 0L
    q2MedianHeapRemoves = 0L
    q2MedianRebalances = 0L
    q1SnapshotAllocs = 0L
    q1SnapshotSlots = 0L
    q2SnapshotAllocs = 0L
    q2SnapshotArrayAllocs = 0L
    q2SnapshotSlots = 0L
    q1LatencyAppends = 0L
    q2LatencyAppends = 0L
    taxiLookups = 0L
    taxiHits = 0L
    taxiMisses = 0L
    taxiEntriesScanned = 0L
    taxiEntriesCreated = 0L
  }

  def snapshot(): Snapshot =
    Snapshot(
      gridQ1Calls,
      gridQ1Hits,
      gridQ2Calls,
      gridQ2Hits,
      q1RankAdds,
      q1RankRemoves,
      q1RankCreated,
      q1Top10Calls,
      q1ResultArrayAllocs,
      q1ResultArraySlots,
      q2RankAdds,
      q2RankRemoves,
      q2RankFixes,
      q2RankCreated,
      q2Top10Calls,
      q2ResultArrayAllocs,
      q2ResultArraySlots,
      q2MedianComputes,
      q2MedianValuesSorted,
      q2MedianReads,
      q2MedianHeapAdds,
      q2MedianHeapRemoves,
      q2MedianRebalances,
      q1SnapshotAllocs,
      q1SnapshotSlots,
      q2SnapshotAllocs,
      q2SnapshotArrayAllocs,
      q2SnapshotSlots,
      q1LatencyAppends,
      q2LatencyAppends,
      taxiLookups,
      taxiHits,
      taxiMisses,
      taxiEntriesScanned,
      taxiEntriesCreated
    )

  def recordGridCell(gridName: String, hit: Boolean): Unit =
    if (gridName == "q1") {
      gridQ1Calls += 1L
      if (hit) gridQ1Hits += 1L
    } else {
      gridQ2Calls += 1L
      if (hit) gridQ2Hits += 1L
    }

  def recordQ1RankAdd(): Unit =
    q1RankAdds += 1L

  def recordQ1RankRemove(): Unit =
    q1RankRemoves += 1L

  def recordQ1RankCreated(): Unit =
    q1RankCreated += 1L

  def recordQ1Top10(): Unit =
    q1Top10Calls += 1L

  def recordQ1ResultArrayAlloc(size: Int): Unit = {
    q1ResultArrayAllocs += 1L
    q1ResultArraySlots += size.toLong
  }

  def recordQ2RankAdd(): Unit =
    q2RankAdds += 1L

  def recordQ2RankRemove(): Unit =
    q2RankRemoves += 1L

  def recordQ2RankFix(): Unit =
    q2RankFixes += 1L

  def recordQ2RankCreated(): Unit =
    q2RankCreated += 1L

  def recordQ2Top10(): Unit =
    q2Top10Calls += 1L

  def recordQ2ResultArrayAlloc(size: Int): Unit = {
    q2ResultArrayAllocs += 1L
    q2ResultArraySlots += size.toLong
  }

  def recordQ2MedianCompute(size: Int): Unit = {
    q2MedianComputes += 1L
    q2MedianValuesSorted += size.toLong
  }

  def recordQ2MedianRead(): Unit =
    q2MedianReads += 1L

  def recordQ2MedianHeapAdd(): Unit =
    q2MedianHeapAdds += 1L

  def recordQ2MedianHeapRemove(): Unit =
    q2MedianHeapRemoves += 1L

  def recordQ2MedianRebalance(): Unit =
    q2MedianRebalances += 1L

  def recordQ1Snapshot(size: Int): Unit = {
    q1SnapshotAllocs += 1L
    q1SnapshotSlots += size.toLong
  }

  def recordQ2Snapshot(size: Int): Unit = {
    q2SnapshotAllocs += 1L
    q2SnapshotArrayAllocs += 4L
    q2SnapshotSlots += size.toLong
  }

  def recordQ1LatencyAppend(): Unit =
    q1LatencyAppends += 1L

  def recordQ2LatencyAppend(): Unit =
    q2LatencyAppends += 1L

  def recordTaxiLookup(): Unit =
    taxiLookups += 1L

  def recordTaxiHit(): Unit =
    taxiHits += 1L

  def recordTaxiMiss(): Unit =
    taxiMisses += 1L

  def recordTaxiEntryScan(): Unit =
    taxiEntriesScanned += 1L

  def recordTaxiEntryCreated(): Unit =
    taxiEntriesCreated += 1L
}
