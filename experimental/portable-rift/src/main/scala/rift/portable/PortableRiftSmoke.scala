package rift.portable

object PortableRiftSmoke:
  private val backends: List[(String, () => RiftRuntime, Boolean)] =
    List(
      ("heap-gc", () => Rift.heapFallback(), false),
      ("analysis-only", () => Rift.analysisOnly(), false),
      ("jvm-pool-arena", () => Rift.jvmPoolArena(), true),
      ("scala-js-pool", () => Rift.scalaJsPool(), true),
      ("wasm-linear-memory", () => Rift.wasmLinearMemory(), false),
      ("scala-native-real-regions", () => Rift.scalaNativeModel(), false)
    )

  def main(args: Array[String]): Unit =
    val records = parseInt(args, "--records", 20000)
    val epochSize = parseInt(args, "--epoch-size", 10000)
    backends.foreach { case (label, mkRuntime, usePool) =>
      val runtime = mkRuntime()
      val result =
        if runtime.kind == BackendKind.WasmLinearMemory then wasmSmoke(runtime, records, epochSize)
        else PortableRiftWorkloads.retainedEpoch(runtime, records, epochSize, usePool)
      val staleRejected = staleScopeRejected(runtime)
      println(
        s"$label checksum=${result.checksum} output=${result.output} " +
          s"staleRejected=$staleRejected ${runtime.stats.snapshot}"
      )
    }

  private def wasmSmoke(runtime: RiftRuntime, records: Int, epochSize: Int): PortableRiftWorkloads.WorkloadResult =
    var checksum = 0L
    var output = 0L
    var processed = 0
    while processed < records do
      val limit = math.min(epochSize, records - processed)
      runtime.epoch { scope =>
        var i = 0
        while i < limit do
          val slice = scope.allocBytes(24, align = 8)
          checksum += slice.offset.toLong * 31L + slice.size
          output += 1L
          i += 1
      }
      processed += limit
    PortableRiftWorkloads.WorkloadResult(checksum, output)

  private def staleScopeRejected(runtime: RiftRuntime): Boolean =
    var stale: RiftScope | Null = null
    runtime.epoch { scope =>
      stale = scope
      scope.alloc(new Object)
    }
    try
      stale.nn.alloc(new Object)
      false
    catch case _: IllegalStateException => true

  private def parseInt(args: Array[String], key: String, default: Int): Int =
    val idx = args.indexOf(key)
    if idx >= 0 && idx + 1 < args.length then args(idx + 1).toInt else default
