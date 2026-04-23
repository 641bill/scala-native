object BenchmarkRunner {
  final case class Stats(
      samplesMs: Array[Double],
      medianMs: Double,
      averageMs: Double,
      minMs: Double,
      maxMs: Double
  )

  private def median(sorted: Array[Double]): Double = {
    val n = sorted.length
    if ((n & 1) == 1) sorted(n / 2)
    else (sorted(n / 2 - 1) + sorted(n / 2)) / 2.0
  }

  def runBenchmark(name: String, numRuns: Int)(runFn: => Boolean): Stats = {
    require(numRuns > 0, "benchmark run count must be positive")

    val samplesMs = new Array[Double](numRuns)
    println(s"Running $name for $numRuns timed runs")

    var i = 0
    while (i < numRuns) {
      val start = System.nanoTime()
      val ok = runFn
      val end = System.nanoTime()
      if (!ok)
        throw new IllegalStateException(s"benchmark $name returned false")

      val elapsedMs = (end - start) / 1000000.0
      samplesMs(i) = elapsedMs
      println(f"  run=${i + 1}%d elapsed_ms=$elapsedMs%.3f")
      i += 1
    }

    val sorted = samplesMs.clone()
    scala.util.Sorting.quickSort(sorted)

    var sum = 0.0
    var min = sorted(0)
    var max = sorted(sorted.length - 1)
    i = 0
    while (i < samplesMs.length) {
      sum += samplesMs(i)
      i += 1
    }

    val stats = Stats(
      samplesMs = samplesMs,
      medianMs = median(sorted),
      averageMs = sum / samplesMs.length.toDouble,
      minMs = min,
      maxMs = max
    )

    println(
      f"RESULT name=$name median_ms=${stats.medianMs}%.3f avg_ms=${stats.averageMs}%.3f min_ms=${stats.minMs}%.3f max_ms=${stats.maxMs}%.3f"
    )
    println(
      "SAMPLES_MS " + sorted.map(v => f"$v%.3f").mkString(",")
    )

    stats
  }
}
