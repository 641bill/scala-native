package debs2015

import java.util.Comparator
import java.util.TreeSet

import scala.collection.mutable

final case class ProfitableArea(
    cell: Cell,
    emptyTaxis: Int,
    medianProfit: Double,
    profitability: Double,
    latestSeq: Long
)

trait Q2Engine {
  def process(trip: Trip): Array[ProfitableArea]
  def close(): Unit = ()
}

final class Q2Heap extends Q2Engine {
  import Q2Heap._

  private val profitWindow = mutable.Queue.empty[ProfitEntry]
  private val emptyWindow = mutable.Queue.empty[EmptyEntry]
  private val profitsByCell = mutable.HashMap.empty[Cell, ProfitStats]
  private val emptyCounts = mutable.HashMap.empty[Cell, Int]
  private val latestByCell = mutable.HashMap.empty[Cell, Long]
  private val latestEmptyByTaxi = mutable.HashMap.empty[String, EmptyEntry]
  private val rankedAreas = new TreeSet[ProfitableArea](AreaOrdering)
  private val rankByCell = mutable.HashMap.empty[Cell, ProfitableArea]
  private var nextSeq = 0L

  override def process(trip: Trip): Array[ProfitableArea] = {
    evictProfitBefore(trip.dropoffSeconds - ProfitWindowSeconds)
    evictEmptyBefore(trip.dropoffSeconds - EmptyWindowSeconds)

    val seq = nextSeq
    nextSeq += 1L

    // The current pickup means this taxi is no longer empty at its previous dropoff.
    latestEmptyByTaxi.remove(trip.taxiId).foreach(removeEmpty)

    if (trip.hasValidProfit) {
      Grid.Q2.cell(trip.pickupLongitude, trip.pickupLatitude).foreach { pickupCell =>
        val profit = trip.profit
        profitWindow.enqueue(ProfitEntry(trip.dropoffSeconds, pickupCell, profit))
        profitsByCell
          .getOrElseUpdate(pickupCell, new ProfitStats)
          .add(profit)
        latestByCell.update(pickupCell, seq)
        updateRank(pickupCell)
      }
    }

    Grid.Q2.cell(trip.dropoffLongitude, trip.dropoffLatitude).foreach { dropoffCell =>
      val entry = EmptyEntry(trip.dropoffSeconds, seq, trip.taxiId, dropoffCell)
      emptyWindow.enqueue(entry)
      latestEmptyByTaxi.update(trip.taxiId, entry)
      emptyCounts.update(dropoffCell, emptyCounts.getOrElse(dropoffCell, 0) + 1)
      latestByCell.update(dropoffCell, seq)
      updateRank(dropoffCell)
    }

    top10()
  }

  private def evictProfitBefore(cutoffSeconds: Long): Unit = {
    while (profitWindow.nonEmpty && profitWindow.front.dropoffSeconds < cutoffSeconds) {
      val expired = profitWindow.dequeue()
      profitsByCell.get(expired.cell).foreach { stats =>
        stats.remove(expired.profit)
        if (stats.isEmpty) profitsByCell.remove(expired.cell)
        updateRank(expired.cell)
      }
    }
  }

  private def evictEmptyBefore(cutoffSeconds: Long): Unit = {
    while (emptyWindow.nonEmpty && emptyWindow.front.dropoffSeconds < cutoffSeconds) {
      val expired = emptyWindow.dequeue()
      latestEmptyByTaxi.get(expired.taxiId).foreach { latest =>
        if (latest.seq == expired.seq) {
          latestEmptyByTaxi.remove(expired.taxiId)
          removeEmpty(expired)
        }
      }
    }
  }

  private def removeEmpty(entry: EmptyEntry): Unit = {
    val previous = emptyCounts.getOrElse(entry.cell, 0)
    if (previous <= 1) emptyCounts.remove(entry.cell)
    else emptyCounts.update(entry.cell, previous - 1)
    updateRank(entry.cell)
  }

  private def updateRank(cell: Cell): Unit = {
    rankByCell.remove(cell).foreach(rankedAreas.remove)

    profitsByCell.get(cell).foreach { profits =>
      val empty = emptyCounts.getOrElse(cell, 0)
      if (empty > 0 && profits.nonEmpty) {
        val median = profits.medianProfit
        val area = ProfitableArea(
          cell = cell,
          emptyTaxis = empty,
          medianProfit = median,
          profitability = median / empty.toDouble,
          latestSeq = latestByCell.getOrElse(cell, 0L)
        )
        rankedAreas.add(area)
        rankByCell.update(cell, area)
      }
    }
  }

  private def top10(): Array[ProfitableArea] = {
    val ranked = new mutable.ArrayBuffer[ProfitableArea](10)
    val it = rankedAreas.iterator()
    while (ranked.length < 10 && it.hasNext)
      ranked += it.next()
    ranked.toArray
  }
}

object Q2Heap {
  private val ProfitWindowSeconds = 15L * 60L
  private val EmptyWindowSeconds = 30L * 60L

  private val AreaOrdering: Comparator[ProfitableArea] =
    new Comparator[ProfitableArea] {
      override def compare(left: ProfitableArea, right: ProfitableArea): Int = {
        if (left eq right) 0
        else {
          val byProfitability =
            java.lang.Double.compare(right.profitability, left.profitability)
          if (byProfitability != 0) byProfitability
          else if (left.latestSeq != right.latestSeq)
            java.lang.Long.compare(right.latestSeq, left.latestSeq)
          else left.cell.id.compareTo(right.cell.id)
        }
      }
    }

  private final case class ProfitEntry(
      dropoffSeconds: Long,
      cell: Cell,
      profit: Double
  )

  private final case class EmptyEntry(
      dropoffSeconds: Long,
      seq: Long,
      taxiId: String,
      cell: Cell
  )

  private final class ProfitStats {
    private val values = mutable.ArrayBuffer.empty[Double]
    private var dirty = true
    private var cachedMedian = 0.0

    def nonEmpty: Boolean = values.nonEmpty
    def isEmpty: Boolean = values.isEmpty

    def add(value: Double): Unit = {
      values += value
      dirty = true
    }

    def remove(value: Double): Unit = {
      val idx = values.indexOf(value)
      if (idx >= 0) {
        values.remove(idx)
        dirty = true
      }
    }

    def medianProfit: Double = {
      if (dirty) {
        val sorted = values.toArray
        scala.util.Sorting.quickSort(sorted)
        val n = sorted.length
        cachedMedian =
          if (n == 0) 0.0
          else if ((n & 1) == 1) sorted(n / 2)
          else (sorted(n / 2 - 1) + sorted(n / 2)) / 2.0
        dirty = false
      }
      cachedMedian
    }
  }
}
