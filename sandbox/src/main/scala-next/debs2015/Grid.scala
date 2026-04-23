package debs2015

final case class Cell(east: Int, south: Int) {
  def id: String = s"$east.$south"
}

final class Grid private (
    val name: String,
    val size: Int,
    val latitudeStep: Double,
    val longitudeStep: Double
) {
  import Grid._

  private val halfLatitudeStep = latitudeStep / 2.0
  private val halfLongitudeStep = longitudeStep / 2.0

  def cell(longitude: Double, latitude: Double): Option[Cell] = {
    val east =
      math.floor((longitude - (OriginLongitude - halfLongitudeStep)) / longitudeStep).toInt + 1
    val south =
      math.floor(((OriginLatitude + halfLatitudeStep) - latitude) / latitudeStep).toInt + 1

    if (east >= 1 && east <= size && south >= 1 && south <= size)
      Some(Cell(east, south))
    else None
  }
}

object Grid {
  val OriginLatitude = 41.474937
  val OriginLongitude = -74.913585

  val Q1LatitudeStep = 0.004491556
  val Q1LongitudeStep = 0.005986

  val Q1: Grid =
    new Grid("q1", size = 300, Q1LatitudeStep, Q1LongitudeStep)

  val Q2: Grid =
    new Grid(
      "q2",
      size = 600,
      latitudeStep = Q1LatitudeStep / 2.0,
      longitudeStep = Q1LongitudeStep / 2.0
    )
}
