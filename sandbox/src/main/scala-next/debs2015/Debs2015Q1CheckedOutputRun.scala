package debs2015

import java.io.BufferedWriter
import java.io.FileWriter
import java.io.Writer

import scala.collection.mutable
import scala.io.Source
import scala.language.experimental.captureChecking
import scala.scalanative.memory.RiftRegion

object Debs2015Q1CheckedOutputRunner {
  final case class Metrics(
      events: Long,
      parsed: Long,
      outliersOrInvalid: Long,
      outputs: Long,
      elapsedNanos: Long,
      latencyMillis: Array[Long]
  ) {
    def elapsedMillis: Double = elapsedNanos.toDouble / 1000000.0

    def throughputEventsPerSecond: Double =
      if (elapsedNanos <= 0L) 0.0
      else events.toDouble * 1000000000.0 / elapsedNanos.toDouble
  }

  def run(inputPath: String, outputPath: String, mode: String): Metrics =
    mode match {
      case "heap-output" =>
        fromQ1Metrics(Debs2015Q1Runner.run(inputPath, outputPath, "heap"))
      case "checked-output" =>
        RiftRegion.init(0)
        try
          RiftRegion.streaming { stream ?=>
            runCheckedOutput(inputPath, outputPath)
          }
        finally RiftRegion.shutdown()
      case other =>
        throw new IllegalArgumentException(
          s"unknown Q1 checked-output mode '$other'; expected heap-output or checked-output"
        )
    }

  private def fromQ1Metrics(metrics: Debs2015Q1Runner.Metrics): Metrics =
    Metrics(
      events = metrics.events,
      parsed = metrics.parsed,
      outliersOrInvalid = metrics.outliersOrInvalid,
      outputs = metrics.outputs,
      elapsedNanos = metrics.elapsedNanos,
      latencyMillis = metrics.latencyMillis
    )

  private def runCheckedOutput(
      inputPath: String,
      outputPath: String
  )(using stream: RiftRegion.StreamingRegion^): Metrics = {
    val q1 = new Q1Heap
    val source = Source.fromFile(inputPath)
    val writer = new BufferedWriter(new FileWriter(outputPath))
    val latencies = new mutable.ArrayBuffer[Long](1024)
    val trip = Trip.empty

    var previous = Q1Output.EmptySnapshot
    var events = 0L
    var parsed = 0L
    var outliersOrInvalid = 0L
    var outputs = 0L
    val started = System.nanoTime()

    try {
      val lines = source.getLines()
      while (lines.hasNext) {
        val readAt = System.nanoTime()
        val line = lines.next()
        events += 1L

        if (Trip.parseInto(line, trip)) {
          parsed += 1L
          val current = q1.process(trip)
          if (current.isEmpty) {
            outliersOrInvalid += 1L
          } else if (Q1Output.changed(previous, current)) {
            val writeAt = System.nanoTime()
            val delayMillis = (writeAt - readAt) / 1000000L
            previous =
              writeCheckedOutputSnapshot(writer, trip, current, delayMillis)
            writer.newLine()
            latencies += delayMillis
            outputs += 1L
          }
        } else {
          outliersOrInvalid += 1L
        }
      }
    } finally {
      writer.close()
      source.close()
      q1.close()
    }

    val elapsedNanos = System.nanoTime() - started
    Metrics(
      events = events,
      parsed = parsed,
      outliersOrInvalid = outliersOrInvalid,
      outputs = outputs,
      elapsedNanos = elapsedNanos,
      latencyMillis = latencies.toArray
    )
  }

  private def writeCheckedOutputSnapshot(
      writer: Writer,
      trip: Trip,
      ranking: Array[RankedRoute],
      delayMillis: Long
  )(using stream: RiftRegion.StreamingRegion^): Array[Long] = {
    val snapshot = new Array[Long](ranking.length)

    RiftRegion.reset { region ?=>
      final class CheckedCell(val east: Int, val south: Int)
      final class CheckedRoute(
          val start: CheckedCell^{region},
          val end: CheckedCell^{region}
      )
      final class CheckedRankedRoute(
          val route: CheckedRoute^{region},
          val count: Int
      )

      val checkedRanking =
        RiftRegion.regionBuffer[CheckedRankedRoute](ranking.length)

      var i = 0
      while (i < ranking.length) {
        val ranked = ranking(i)
        val startCell = ranked.route.start
        val endCell = ranked.route.end
        val checkedStart: CheckedCell^{region} =
          RiftRegion.alloc(new CheckedCell(startCell.east, startCell.south))
        val checkedEnd: CheckedCell^{region} =
          RiftRegion.alloc(new CheckedCell(endCell.east, endCell.south))
        val checkedRoute: CheckedRoute^{region} =
          RiftRegion.alloc(new CheckedRoute(checkedStart, checkedEnd))
        val checkedRanked: CheckedRankedRoute^{region} =
          RiftRegion.alloc(new CheckedRankedRoute(checkedRoute, ranked.count))

        region.append(checkedRanking, checkedRanked)
        snapshot(i) = Q1Support.routeKey(startCell, endCell)
        i += 1
      }

      Debs2015Counters.recordQ1Snapshot(ranking.length)

      trip.writePickupTimestamp(writer)
      OutputSupport.writeComma(writer)
      trip.writeDropoffTimestamp(writer)

      i = 0
      while (i < 10) {
        OutputSupport.writeComma(writer)
        if (i < region.length(checkedRanking)) {
          val checked = region.get(checkedRanking, i)
          val route = checked.route
          OutputSupport.writeCellId(
            writer,
            route.start.east,
            route.start.south
          )
          OutputSupport.writeComma(writer)
          OutputSupport.writeCellId(
            writer,
            route.end.east,
            route.end.south
          )
        } else {
          writer.write("NULL,NULL")
        }
        i += 1
      }

      OutputSupport.writeComma(writer)
      OutputSupport.writeLong(writer, delayMillis)
    }

    snapshot
  }

  def percentile(sorted: Array[Long], fraction: Double): Long =
    Debs2015Q1Runner.percentile(sorted, fraction)

  def printMetrics(metrics: Metrics): Unit =
    Debs2015Q1Runner.printMetrics(
      Debs2015Q1Runner.Metrics(
        events = metrics.events,
        parsed = metrics.parsed,
        outliersOrInvalid = metrics.outliersOrInvalid,
        outputs = metrics.outputs,
        elapsedNanos = metrics.elapsedNanos,
        latencyMillis = metrics.latencyMillis
      )
    )
}

@main def Debs2015Q1CheckedOutputRun(
    inputPath: String,
    outputPath: String,
    mode: String = "checked-output"
): Unit = {
  val metrics = Debs2015Q1CheckedOutputRunner.run(inputPath, outputPath, mode)
  Debs2015Q1CheckedOutputRunner.printMetrics(metrics)
}
