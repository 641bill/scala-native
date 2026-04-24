package debs2015

import java.io.Writer

object Q1Output {
  val EmptySnapshot: Array[Long] = Array.emptyLongArray

  def changed(previous: Array[Long], current: Array[RankedRoute]): Boolean = {
    if (previous.length != current.length) true
    else {
      var i = 0
      var same = true
      while (i < current.length && same) {
        same = previous(i) == routeKey(current(i))
        i += 1
      }
      !same
    }
  }

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

  def snapshot(ranking: Array[RankedRoute]): Array[Long] = {
    Debs2015Counters.recordQ1Snapshot(ranking.length)
    val result = new Array[Long](ranking.length)
    var i = 0
    while (i < ranking.length) {
      result(i) = routeKey(ranking(i))
      i += 1
    }
    result
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

  def writeRow(writer: Writer, trip: Trip, ranking: Array[RankedRoute], delayMillis: Long): Unit = {
    trip.writePickupTimestamp(writer)
    OutputSupport.writeComma(writer)
    trip.writeDropoffTimestamp(writer)

    var i = 0
    while (i < 10) {
      OutputSupport.writeComma(writer)
      if (i < ranking.length) {
        val route = ranking(i).route
        OutputSupport.writeCellId(writer, route.start.east, route.start.south)
        OutputSupport.writeComma(writer)
        OutputSupport.writeCellId(writer, route.end.east, route.end.south)
      } else {
        writer.write("NULL,NULL")
      }
      i += 1
    }

    OutputSupport.writeComma(writer)
    OutputSupport.writeLong(writer, delayMillis)
  }

  private def routeKey(ranked: RankedRoute): Long =
    Q1Support.routeKey(ranked.route.start, ranked.route.end)
}
