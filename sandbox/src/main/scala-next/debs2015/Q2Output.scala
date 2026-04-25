package debs2015

import java.io.Writer

import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion

object Q2Output {
  final class Snapshot(
      val cellKeys: Array[Int],
      val emptyTaxis: Array[Int],
      val medianProfits: Array[Double],
      val profitabilities: Array[Double]
  )

  val EmptySnapshot: Snapshot =
    new Snapshot(Array.emptyIntArray, Array.emptyIntArray, Array.emptyDoubleArray, Array.emptyDoubleArray)

  def changed(previous: Snapshot, current: Array[ProfitableArea]): Boolean = {
    if (previous.cellKeys.length != current.length) true
    else {
      var i = 0
      var same = true
      while (i < current.length && same) {
        same =
          previous.cellKeys(i) == current(i).cellKey &&
            previous.emptyTaxis(i) == current(i).emptyTaxis &&
            previous.medianProfits(i) == current(i).medianProfit &&
            previous.profitabilities(i) == current(i).profitability
        i += 1
      }
      !same
    }
  }

  def changed(previous: Array[ProfitableArea], current: Array[ProfitableArea]): Boolean = {
    if (previous.length != current.length) true
    else {
      var i = 0
      var same = true
      while (i < current.length && same) {
        same =
          previous(i).cellKey == current(i).cellKey &&
            previous(i).emptyTaxis == current(i).emptyTaxis &&
            previous(i).medianProfit == current(i).medianProfit &&
            previous(i).profitability == current(i).profitability
        i += 1
      }
      !same
    }
  }

  def snapshot(ranking: Array[ProfitableArea]): Snapshot =
    snapshot(ranking, null)

  def snapshot(ranking: Array[ProfitableArea], region: RiftRegion): Snapshot = {
    Debs2015Counters.recordQ2Snapshot(ranking.length)
    val cellKeys = allocateIntArray(ranking.length, region)
    val emptyTaxis = allocateIntArray(ranking.length, region)
    val medianProfits = allocateDoubleArray(ranking.length, region)
    val profitabilities = allocateDoubleArray(ranking.length, region)
    var i = 0
    while (i < ranking.length) {
      val area = ranking(i)
      cellKeys(i) = area.cellKey
      emptyTaxis(i) = area.emptyTaxis
      medianProfits(i) = area.medianProfit
      profitabilities(i) = area.profitability
      i += 1
    }
    if (region == null)
      new Snapshot(cellKeys, emptyTaxis, medianProfits, profitabilities)
    else
      region.alloc(new Snapshot(cellKeys, emptyTaxis, medianProfits, profitabilities))
  }

  private def allocateIntArray(size: Int, region: RiftRegion): Array[Int] =
    if (region == null) new Array[Int](size)
    else region.alloc(new Array[Int](size))

  private def allocateDoubleArray(size: Int, region: RiftRegion): Array[Double] =
    if (region == null) new Array[Double](size)
    else region.alloc(new Array[Double](size))

  def formatRow(trip: Trip, ranking: Array[ProfitableArea], delayMillis: Long): String = {
    val builder = new StringBuilder(384)
    trip.appendPickupTimestamp(builder)
    builder.append(',')
    trip.appendDropoffTimestamp(builder)

    var i = 0
    while (i < 10) {
      builder.append(',')
      if (i < ranking.length) {
        val area = ranking(i)
        Q2Support.appendCellId(builder, area.cellKey)
        builder.append(',')
        builder.append(area.emptyTaxis)
        builder.append(',')
        OutputSupport.appendFixed(builder, area.medianProfit, 2)
        builder.append(',')
        OutputSupport.appendFixed(builder, area.profitability, 6)
      } else {
        builder.append("NULL,NULL,NULL,NULL")
      }
      i += 1
    }

    builder.append(',')
    builder.append(delayMillis)
    builder.toString()
  }

  def writeRow(writer: Writer, trip: Trip, ranking: Array[ProfitableArea], delayMillis: Long): Unit = {
    trip.writePickupTimestamp(writer)
    OutputSupport.writeComma(writer)
    trip.writeDropoffTimestamp(writer)

    var i = 0
    while (i < 10) {
      OutputSupport.writeComma(writer)
      if (i < ranking.length) {
        val area = ranking(i)
        Q2Support.writeCellId(writer, area.cellKey)
        OutputSupport.writeComma(writer)
        OutputSupport.writeInt(writer, area.emptyTaxis)
        OutputSupport.writeComma(writer)
        OutputSupport.writeFixed(writer, area.medianProfit, 2)
        OutputSupport.writeComma(writer)
        OutputSupport.writeFixed(writer, area.profitability, 6)
      } else {
        writer.write("NULL,NULL,NULL,NULL")
      }
      i += 1
    }

    OutputSupport.writeComma(writer)
    OutputSupport.writeLong(writer, delayMillis)
  }
}
