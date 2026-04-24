package debs2015

import java.io.BufferedWriter
import java.io.FileWriter

import scala.collection.mutable
import scala.io.Source
import scala.scalanative.memory.RiftRegion

object Debs2015Q2Runner {
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

  def run(inputPath: String, outputPath: String, mode: String): Metrics = {
    val usesRift = mode.startsWith("rift-")
    if (usesRift) RiftRegion.init(0)

    val q2 = createEngine(mode)
    val source = Source.fromFile(inputPath)
    val writer = new BufferedWriter(new FileWriter(outputPath))
    val latencies = new mutable.ArrayBuffer[Long](1024)
    val trip = Trip.empty

    var previous = Array.empty[ProfitableArea]
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
            val current = q2.process(trip)
            if (current.isEmpty) {
              outliersOrInvalid += 1L
            } else if (Q2Output.changed(previous, current)) {
              val writeAt = System.nanoTime()
              val delayMillis = (writeAt - readAt) / 1000000L
              writer.write(Q2Output.formatRow(trip, current, delayMillis))
              writer.newLine()
              latencies += delayMillis
              outputs += 1L
              previous = current
            }
        } else {
          outliersOrInvalid += 1L
        }
      }
    } finally {
      writer.close()
      source.close()
      q2.close()
      if (usesRift) RiftRegion.shutdown()
    }

    Metrics(
      events = events,
      parsed = parsed,
      outliersOrInvalid = outliersOrInvalid,
      outputs = outputs,
      elapsedNanos = System.nanoTime() - started,
      latencyMillis = latencies.toArray
    )
  }

  private[debs2015] def createEngine(mode: String): Q2Engine =
    mode match {
      case "heap"           => new Q2Heap
      case "rift-hp"        => new Q2RiftWindows(RiftRegion.HPZone)
      case "rift-streaming" => new Q2RiftWindows(RiftRegion.Streaming)
      case other =>
        throw new IllegalArgumentException(
          s"unknown Q2 mode '$other'; expected heap, rift-hp, or rift-streaming"
        )
    }

  def percentile(sorted: Array[Long], fraction: Double): Long = {
    if (sorted.isEmpty) 0L
    else {
      val idx = math.ceil(fraction * sorted.length.toDouble).toInt - 1
      sorted(math.max(0, math.min(sorted.length - 1, idx)))
    }
  }

  def printMetrics(metrics: Metrics): Unit = {
    val sorted = metrics.latencyMillis.clone()
    scala.util.Sorting.quickSort(sorted)

    println(
      f"DEBS2015_Q2_RESULT events=${metrics.events}%d parsed=${metrics.parsed}%d " +
        f"outliers_or_invalid=${metrics.outliersOrInvalid}%d outputs=${metrics.outputs}%d " +
        f"elapsed_ms=${metrics.elapsedMillis}%.3f throughput_eps=${metrics.throughputEventsPerSecond}%.3f " +
        f"p50_ms=${percentile(sorted, 0.50)}%d p99_ms=${percentile(sorted, 0.99)}%d " +
        f"p999_ms=${percentile(sorted, 0.999)}%d max_ms=${if (sorted.isEmpty) 0L else sorted.last}%d"
    )
  }
}

@main def Debs2015Q2Run(
    inputPath: String,
    outputPath: String,
    mode: String = "heap"
): Unit = {
  val metrics = Debs2015Q2Runner.run(inputPath, outputPath, mode)
  Debs2015Q2Runner.printMetrics(metrics)
}
