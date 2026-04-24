package debs2015

object Debs2015Q2SmokeData {
  private val trip =
    "%s,%s,%s,%s,120,0.44,-73.956528,40.716976,-73.956528,40.716976,CSH,%s,0.50,0.50,%s,0.00,%s"

  val lines: Array[String] = Array(
    trip.format(
      "taxi-a",
      "license-a",
      "2013-01-01 00:00:00",
      "2013-01-01 00:02:00",
      "10.00",
      "2.00",
      "13.00"
    ),
    trip.format(
      "taxi-b",
      "license-b",
      "2013-01-01 00:01:00",
      "2013-01-01 00:03:00",
      "14.00",
      "4.00",
      "19.00"
    )
  )
}

@main def Debs2015Q2Smoke(): Unit = {
  smoke("heap")
  smoke("rift-hp")
  smoke("rift-streaming")
}

private def smoke(mode: String): Unit = {
  val usesRift = mode.startsWith("rift-")
  if (usesRift) scala.scalanative.memory.RiftRegion.init(0)

  val q2 = Debs2015Q2Runner.createEngine(mode)
  var latest = Array.empty[ProfitableArea]

  try {
    Debs2015Q2SmokeData.lines.foreach { line =>
      val trip = Trip.parse(line).getOrElse {
        throw new IllegalArgumentException(s"failed to parse smoke line: $line")
      }
      latest = q2.process(trip)
    }
    validateAndPrint(mode, latest)
  } finally {
    q2.close()
    if (usesRift) scala.scalanative.memory.RiftRegion.shutdown()
  }
}

private def validateAndPrint(mode: String, latest: Array[ProfitableArea]): Unit = {
  if (latest.isEmpty)
    throw new IllegalStateException("Q2 smoke produced no profitable areas")

  val top = latest(0)
  if (top.emptyTaxis != 2)
    throw new IllegalStateException(s"expected 2 empty taxis, got ${top.emptyTaxis}")
  if (math.abs(top.medianProfit - 15.0) > 0.0001)
    throw new IllegalStateException(s"expected median profit 15.0, got ${top.medianProfit}")

  println(
    "DEBS2015_Q2_SMOKE " +
      s"mode=$mode " +
      s"top=${top.cell.id} " +
      s"empty=${top.emptyTaxis} " +
      f"median=${top.medianProfit}%.2f " +
      f"profitability=${top.profitability}%.6f"
  )
}
