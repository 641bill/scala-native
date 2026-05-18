package rift.portable

object PortableRiftMicrobench:
  private final case class Mode(label: String, runtime: () => RiftRuntime, usePool: Boolean)

  private val modes = List(
    Mode("heap-gc", () => Rift.heapFallback(), usePool = false),
    Mode("analysis-only", () => Rift.analysisOnly(), usePool = false),
    Mode("jvm-pool-arena", () => Rift.jvmPoolArena(), usePool = true)
  )

  def main(args: Array[String]): Unit =
    val workload = parseString(args, "--workload", "retained-epoch")
    val selectedMode = parseString(args, "--mode", "all")
    val records = parseInt(args, "--records", 1000000)
    val epochSize = parseInt(args, "--epoch-size", 10000)
    val activeTimestamps = parseInt(args, "--active-timestamps", 4)
    val warmups = parseInt(args, "--warmups", 1)
    val runs = parseInt(args, "--runs", 3)
    val forceGcBeforeRun = parseBoolean(args, "--force-gc-before-run", default = false)
    val selectedModes =
      if selectedMode == "all" then modes
      else modes.filter(_.label == selectedMode)
    if selectedModes.isEmpty then
      throw new IllegalArgumentException(
        s"unknown mode: $selectedMode; expected all, heap-gc, analysis-only, or jvm-pool-arena"
      )

    println(
      "mode\tworkload\trecords\tepoch_size\tactive_timestamps\twarmups\tforce_gc_before_run\trun\telapsed_ms\trecords_per_sec\theap_used_mb\tchecksum\toutput\tstats"
    )
    selectedModes.foreach { mode =>
      var warmup = 0
      while warmup < warmups do
        val runtime = mode.runtime()
        runWorkload(workload, runtime, records, epochSize, activeTimestamps, mode.usePool)
        warmup += 1
      var run = 1
      while run <= runs do
        if forceGcBeforeRun then System.gc()
        val beforeUsed = heapUsedBytes()
        val runtime = mode.runtime()
        val start = System.nanoTime()
        val result = runWorkload(workload, runtime, records, epochSize, activeTimestamps, mode.usePool)
        val elapsedMs = (System.nanoTime() - start).toDouble / 1000000.0
        val afterUsed = heapUsedBytes()
        val recordsPerSec = records.toDouble / (elapsedMs / 1000.0)
        val heapUsedMb = math.max(beforeUsed, afterUsed).toDouble / (1024.0 * 1024.0)
        println(
          s"${mode.label}\t$workload\t$records\t$epochSize\t$activeTimestamps\t$warmups\t$forceGcBeforeRun\t$run\t" +
            f"$elapsedMs%.3f\t$recordsPerSec%.3f\t$heapUsedMb%.3f\t" +
            s"${result.checksum}\t${result.output}\t${runtime.stats.snapshot}"
        )
        run += 1
    }

  private def heapUsedBytes(): Long =
    val runtime = Runtime.getRuntime
    runtime.totalMemory() - runtime.freeMemory()

  private def runWorkload(
      workload: String,
      runtime: RiftRuntime,
      records: Int,
      epochSize: Int,
      activeTimestamps: Int,
      usePool: Boolean
  ): PortableRiftWorkloads.WorkloadResult =
    workload match
      case "retained-epoch" =>
        PortableRiftWorkloads.retainedEpoch(runtime, records, epochSize, usePool)
      case "broom-aggregate" =>
        PortableRiftWorkloads.broomAggregate(
          runtime,
          records,
          epochSize,
          activeTimestamps,
          usePool
        )
      case other =>
        throw new IllegalArgumentException(s"unknown workload: $other")

  private def parseInt(args: Array[String], key: String, default: Int): Int =
    val idx = args.indexOf(key)
    if idx >= 0 && idx + 1 < args.length then args(idx + 1).toInt else default

  private def parseString(args: Array[String], key: String, default: String): String =
    val idx = args.indexOf(key)
    if idx >= 0 && idx + 1 < args.length then args(idx + 1) else default

  private def parseBoolean(args: Array[String], key: String, default: Boolean): Boolean =
    val idx = args.indexOf(key)
    if idx >= 0 && idx + 1 < args.length then args(idx + 1).toBoolean else default
