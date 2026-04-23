package debs2015

object Debs2015Q1SmokeData {
  private val routeA =
    "07290D3599E7A0D62097A346EFCC1FB5,E7750A37CAB07D0DFF0AF7E3573AC141,%s,%s,120,0.44,-73.956528,40.716976,-73.962440,40.715008,CSH,3.50,0.50,0.50,0.00,0.00,4.50"

  private val routeB =
    "0EC22AAF491A8BD91F279350C2B010FD,778C92B26AE78A9EBDF96B49C67E4007,%s,%s,120,0.71,-73.973145,40.752827,-73.965897,40.760445,CSH,4.00,0.50,0.50,0.00,0.00,5.00"

  val lines: Array[String] = Array(
    routeA.format("2013-01-01 00:00:00", "2013-01-01 00:02:00"),
    routeB.format("2013-01-01 00:01:00", "2013-01-01 00:03:00"),
    routeA.format("2013-01-01 00:02:00", "2013-01-01 00:04:00")
  )
}

@main def Debs2015Q1Smoke(): Unit = {
  val q1 = new Q1Heap
  var latest = Array.empty[RankedRoute]

  Debs2015Q1SmokeData.lines.foreach { line =>
    val trip = Trip.parse(line).getOrElse {
      throw new IllegalArgumentException(s"failed to parse smoke line: $line")
    }
    latest = q1.process(trip)
  }

  if (latest.isEmpty)
    throw new IllegalStateException("Q1 smoke produced no routes")
  if (latest(0).count != 2)
    throw new IllegalStateException(s"expected top route count 2, got ${latest(0).count}")

  println(
    "DEBS2015_Q1_SMOKE " +
      s"top=${latest(0).route.id} " +
      s"count=${latest(0).count} " +
      s"routes=${latest.map(r => s"${r.route.id}:${r.count}").mkString("|")}"
  )
}
