object ListOfListsConfig {
  private def parsePositiveInt(value: String): Option[Int] =
    try {
      val parsed = value.toInt
      if (parsed > 0) Some(parsed) else None
    } catch {
      case _: NumberFormatException => None
    }

  private def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(parsePositiveInt).getOrElse(default)

  val profile: String =
    sys.env.getOrElse("LISTBENCH_PROFILE", "default").toLowerCase

  val n: Int = envInt("LISTBENCH_N", 3000)
  val structures: Int = envInt("LISTBENCH_STRUCTURES", 40)
  val chunkSize: Int = envInt("LISTBENCH_CHUNK_SIZE", 32)

  val benchmarkRuns: Int = {
    val defaultRuns = if (profile == "report") 5 else 3
    envInt("LISTBENCH_BENCHMARK_RUNS", defaultRuns)
  }
}
