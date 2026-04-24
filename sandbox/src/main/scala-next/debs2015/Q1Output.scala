package debs2015

object Q1Output {
  def changed(previous: Array[RankedRoute], current: Array[RankedRoute]): Boolean = {
    if (previous.length != current.length) true
    else {
      var i = 0
      var same = true
      while (i < current.length && same) {
        same = previous(i).route == current(i).route
        i += 1
      }
      !same
    }
  }

  def formatRow(trip: Trip, ranking: Array[RankedRoute], delayMillis: Long): String = {
    val builder = new StringBuilder(256)
    trip.appendPickupTimestamp(builder)
    builder.append(',')
    trip.appendDropoffTimestamp(builder)

    var i = 0
    while (i < 10) {
      builder.append(',')
      if (i < ranking.length) {
        builder.append(ranking(i).route.start.id)
        builder.append(',')
        builder.append(ranking(i).route.end.id)
      } else {
        builder.append("NULL,NULL")
      }
      i += 1
    }

    builder.append(',')
    builder.append(delayMillis)
    builder.toString()
  }
}
