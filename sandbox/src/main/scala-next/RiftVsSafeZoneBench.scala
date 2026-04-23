import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.runtime.SafeZoneAllocator.allocate

final class ExperimentalBenchNode(
    val value: Int,
    val next: ExperimentalBenchNode^
)

object RiftVsSafeZoneBench {
  private final val DefaultWarmups = 2
  private final val DefaultRuns = 5
  private final val DefaultCount = 200000

  private def buildHeapList(count: Int): ExperimentalBenchNode = {
    var i = 0
    var head: ExperimentalBenchNode = null
    while (i < count) {
      head = new ExperimentalBenchNode(i, head)
      i += 1
    }
    head
  }

  private def buildSafeZoneList(count: Int)(using
      sz: SafeZone^
  ): ExperimentalBenchNode^{sz} = {
    var i = 0
    var head: ExperimentalBenchNode^{sz} = null
    while (i < count) {
      head = allocate(sz, new ExperimentalBenchNode(i, head))
      i += 1
    }
    head
  }

  private def buildRiftList(
      region: RiftRegion,
      count: Int
  ): ExperimentalBenchNode = {
    var i = 0
    var head: ExperimentalBenchNode = null
    while (i < count) {
      head = region.alloc(new ExperimentalBenchNode(i, head))
      i += 1
    }
    head
  }

  private def checksum(head: ExperimentalBenchNode^): Long = {
    var sum = 0L
    var node: ExperimentalBenchNode^ = head
    while (node != null) {
      sum += node.value.toLong
      node = node.next
    }
    sum
  }

  private def timeNanos[A](body: => A): (Long, A) = {
    val start = System.nanoTime()
    val result = body
    val end = System.nanoTime()
    (end - start, result)
  }

  private def parseKind(mode: String): Int =
    mode match {
      case "rift" | "rift-hp"        => RiftRegion.HPZone
      case "rift-scoped"             => RiftRegion.Scoped
      case "rift-streaming"          => RiftRegion.Streaming
      case other =>
        throw new IllegalArgumentException(
          s"unknown Rift mode: $other"
        )
    }

  private def runOnce(mode: String, count: Int): Long =
    mode match {
      case "heap" =>
        checksum(buildHeapList(count))
      case "safezone" =>
        SafeZone { sz ?=>
          checksum(buildSafeZoneList(count)(using sz))
        }
      case riftMode =>
        val region = RiftRegion.open(parseKind(riftMode))
        try checksum(buildRiftList(region, count))
        finally region.close()
    }

  private def report(
      mode: String,
      count: Int,
      warmups: Int,
      runs: Int,
      checksumValue: Long,
      samplesNanos: Array[Long]
  ): Unit = {
    var total = 0L
    var best = Long.MaxValue
    var i = 0
    while (i < samplesNanos.length) {
      val sample = samplesNanos(i)
      total += sample
      if (sample < best) best = sample
      i += 1
    }

    val avgMs = total.toDouble / samplesNanos.length.toDouble / 1000000.0
    val bestMs = best.toDouble / 1000000.0

    println(
      s"mode=$mode count=$count warmups=$warmups runs=$runs checksum=$checksumValue avg_ms=$avgMs best_ms=$bestMs"
    )
  }

  def main(args: Array[String]): Unit = {
    val mode =
      if (args.length >= 1) args(0)
      else "heap"
    val count =
      if (args.length >= 2) args(1).toInt
      else DefaultCount
    val warmups =
      if (args.length >= 3) args(2).toInt
      else DefaultWarmups
    val runs =
      if (args.length >= 4) args(3).toInt
      else DefaultRuns

    if (warmups < 0 || runs <= 0 || count < 0)
      throw new IllegalArgumentException(
        s"expected count >= 0, warmups >= 0, runs > 0 but got count=$count warmups=$warmups runs=$runs"
      )

    val useRift = mode.startsWith("rift")
    if (useRift) RiftRegion.init(0)
    try {
      var i = 0
      while (i < warmups) {
        runOnce(mode, count)
        i += 1
      }

      val samples = new Array[Long](runs)
      var checksumValue = 0L
      i = 0
      while (i < runs) {
        val (elapsed, currentChecksum) = timeNanos(runOnce(mode, count))
        samples(i) = elapsed
        checksumValue = currentChecksum
        i += 1
      }

      report(mode, count, warmups, runs, checksumValue, samples)
    } finally {
      if (useRift) RiftRegion.shutdown()
    }
  }
}
