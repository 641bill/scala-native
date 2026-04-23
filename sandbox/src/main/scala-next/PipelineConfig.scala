object PipelineConfig {
  private def parsePositiveInt(value: String): Option[Int] =
    try {
      val parsed = value.toInt
      if (parsed > 0) Some(parsed) else None
    } catch {
      case _: NumberFormatException => None
    }

  private def parseNonNegativeInt(value: String): Option[Int] =
    try {
      val parsed = value.toInt
      if (parsed >= 0) Some(parsed) else None
    } catch {
      case _: NumberFormatException => None
    }

  private def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(parsePositiveInt).getOrElse(default)

  private def envNonNegativeInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(parseNonNegativeInt).getOrElse(default)

  val size: Int = envInt("PIPELINE_SIZE", 100000)
  val numKeys: Int = envInt("PIPELINE_KEYS", 64)
  val workers: Int = envInt("PIPELINE_WORKERS", 4)
  val warmupRuns: Int = envNonNegativeInt("PIPELINE_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("PIPELINE_BENCHMARK_RUNS", 5)
}
