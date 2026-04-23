import scala.language.experimental.captureChecking

object GCBenchConfig {
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

  val profile: String =
    sys.env.getOrElse("GCBENCH_PROFILE", "default").toLowerCase

  val stretchTreeDepth: Int = envInt("GCBENCH_STRETCH_DEPTH", 18)
  val longLivedTreeDepth: Int = envInt("GCBENCH_LONG_LIVED_DEPTH", 16)
  val arraySize: Int = envInt("GCBENCH_ARRAY_SIZE", 500000)
  val minTreeDepth: Int = envNonNegativeInt("GCBENCH_MIN_DEPTH", 4)
  val maxTreeDepth: Int = envInt("GCBENCH_MAX_DEPTH", 16)
  val treeDepthStep: Int = envInt("GCBENCH_DEPTH_STEP", 2)

  val safeZoneBatchSize: Int = envInt("SAFEZONE_BATCH_SIZE", 1)

  val benchmarkRuns: Int = {
    val defaultRuns = if (profile == "report") 20 else 5
    envInt("GCBENCH_BENCHMARK_RUNS", defaultRuns)
  }
}
