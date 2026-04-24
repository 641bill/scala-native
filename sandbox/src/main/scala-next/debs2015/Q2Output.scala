package debs2015

object Q2Output {
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
        builder.append(f"${area.medianProfit}%.2f")
        builder.append(',')
        builder.append(f"${area.profitability}%.6f")
      } else {
        builder.append("NULL,NULL,NULL,NULL")
      }
      i += 1
    }

    builder.append(',')
    builder.append(delayMillis)
    builder.toString()
  }
}
