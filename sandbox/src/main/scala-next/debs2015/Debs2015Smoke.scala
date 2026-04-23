package debs2015

object Debs2015SmokeData {
  val sampleLine: String =
    "07290D3599E7A0D62097A346EFCC1FB5,E7750A37CAB07D0DFF0AF7E3573AC141,2013-01-01 00:00:00,2013-01-01 00:02:00,120,0.44,-73.956528,40.716976,-73.962440,40.715008,CSH,3.50,0.50,0.50,0.00,0.00,4.50"
}

@main def Debs2015Smoke(): Unit = {
  val line = sys.env.getOrElse("DEBS2015_SMOKE_LINE", Debs2015SmokeData.sampleLine)
  val trip = Trip.parse(line).getOrElse {
    throw new IllegalArgumentException("failed to parse DEBS 2015 smoke line")
  }

  val q1Pickup = Grid.Q1.cell(trip.pickupLongitude, trip.pickupLatitude)
  val q1Dropoff = Grid.Q1.cell(trip.dropoffLongitude, trip.dropoffLatitude)
  val q2Pickup = Grid.Q2.cell(trip.pickupLongitude, trip.pickupLatitude)
  val q2Dropoff = Grid.Q2.cell(trip.dropoffLongitude, trip.dropoffLatitude)

  println(
    "DEBS2015_SMOKE " +
      s"taxi=${trip.taxiId} " +
      s"pickup_seconds=${trip.pickupSeconds} " +
      s"dropoff_seconds=${trip.dropoffSeconds} " +
      s"profit=${trip.profit} " +
      s"q1_pickup=${q1Pickup.map(_.id).getOrElse("OUTLIER")} " +
      s"q1_dropoff=${q1Dropoff.map(_.id).getOrElse("OUTLIER")} " +
      s"q2_pickup=${q2Pickup.map(_.id).getOrElse("OUTLIER")} " +
      s"q2_dropoff=${q2Dropoff.map(_.id).getOrElse("OUTLIER")}"
  )
}
