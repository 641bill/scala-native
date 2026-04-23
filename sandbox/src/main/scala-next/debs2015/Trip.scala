package debs2015

final class Trip(
    var taxiId: String,
    var pickupTimestamp: String,
    var dropoffTimestamp: String,
    var pickupSeconds: Long,
    var dropoffSeconds: Long,
    var pickupLongitude: Double,
    var pickupLatitude: Double,
    var dropoffLongitude: Double,
    var dropoffLatitude: Double,
    var fare: Double,
    var tip: Double
) {
  def profit: Double = fare + tip
  def hasValidProfit: Boolean = fare >= 0.0 && tip >= 0.0
}

object Trip {
  private val ExpectedFields = 17
  private val MonthStartsNormal =
    Array(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
  private val MonthStartsLeap =
    Array(0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335)

  def empty: Trip =
    new Trip("", "", "", 0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

  def parse(line: String): Option[Trip] = {
    val trip = parseOrNull(line)
    if (trip == null) None else Some(trip)
  }

  def parseOrNull(line: String): Trip = {
    val trip = empty
    if (parseInto(line, trip)) trip else null
  }

  def parseInto(line: String, trip: Trip): Boolean = {
    var taxiStart = -1
    var taxiEnd = -1
    var pickupTsStart = -1
    var pickupTsEnd = -1
    var dropoffTsStart = -1
    var dropoffTsEnd = -1
    var pickupLonStart = -1
    var pickupLonEnd = -1
    var pickupLatStart = -1
    var pickupLatEnd = -1
    var dropoffLonStart = -1
    var dropoffLonEnd = -1
    var dropoffLatStart = -1
    var dropoffLatEnd = -1
    var fareStart = -1
    var fareEnd = -1
    var tipStart = -1
    var tipEnd = -1

    var field = 0
    var start = 0
    var i = 0
    val len = line.length
    while (i <= len) {
      if (i == len || line.charAt(i) == ',') {
        field match {
          case 0 =>
            taxiStart = start
            taxiEnd = i
          case 2 =>
            pickupTsStart = start
            pickupTsEnd = i
          case 3 =>
            dropoffTsStart = start
            dropoffTsEnd = i
          case 6 =>
            pickupLonStart = start
            pickupLonEnd = i
          case 7 =>
            pickupLatStart = start
            pickupLatEnd = i
          case 8 =>
            dropoffLonStart = start
            dropoffLonEnd = i
          case 9 =>
            dropoffLatStart = start
            dropoffLatEnd = i
          case 11 =>
            fareStart = start
            fareEnd = i
          case 14 =>
            tipStart = start
            tipEnd = i
          case _ =>
        }
        field += 1
        start = i + 1
      }
      i += 1
    }

    if (
      field != ExpectedFields ||
      taxiStart < 0 ||
      pickupTsStart < 0 ||
      dropoffTsStart < 0 ||
      pickupLonStart < 0 ||
      pickupLatStart < 0 ||
      dropoffLonStart < 0 ||
      dropoffLatStart < 0 ||
      fareStart < 0 ||
      tipStart < 0
    ) false
    else {
      try {
        trip.taxiId = line.substring(taxiStart, taxiEnd)
        trip.pickupTimestamp = line.substring(pickupTsStart, pickupTsEnd)
        trip.dropoffTimestamp = line.substring(dropoffTsStart, dropoffTsEnd)
        trip.pickupSeconds = parseTimestampAt(line, pickupTsStart, pickupTsEnd)
        trip.dropoffSeconds =
          parseTimestampAt(line, dropoffTsStart, dropoffTsEnd)
        trip.pickupLongitude = parseDoubleAt(line, pickupLonStart, pickupLonEnd)
        trip.pickupLatitude = parseDoubleAt(line, pickupLatStart, pickupLatEnd)
        trip.dropoffLongitude =
          parseDoubleAt(line, dropoffLonStart, dropoffLonEnd)
        trip.dropoffLatitude =
          parseDoubleAt(line, dropoffLatStart, dropoffLatEnd)
        trip.fare = parseDoubleAt(line, fareStart, fareEnd)
        trip.tip = parseDoubleAt(line, tipStart, tipEnd)
        true
      } catch {
        case _: NumberFormatException     => false
        case _: IndexOutOfBoundsException => false
      }
    }
  }

  def parseTimestamp(value: String): Long = {
    if (value.length < 19)
      throw new NumberFormatException(s"bad timestamp: $value")

    parseTimestampAt(value, 0, value.length)
  }

  private def parseTimestampAt(value: String, from: Int, until: Int): Long = {
    if (until - from < 19)
      throw new NumberFormatException(s"bad timestamp: ${value.substring(from, until)}")

    val year = intAt(value, from, from + 4)
    val month = intAt(value, from + 5, from + 7)
    val day = intAt(value, from + 8, from + 10)
    val hour = intAt(value, from + 11, from + 13)
    val minute = intAt(value, from + 14, from + 16)
    val second = intAt(value, from + 17, from + 19)

    val daysBeforeYear =
      if (year >= 2013) {
        var y = 2013
        var days = 0
        while (y < year) {
          days += daysInYear(y)
          y += 1
        }
        days
      } else {
        var y = year
        var days = 0
        while (y < 2013) {
          days -= daysInYear(y)
          y += 1
        }
        days
      }

    val starts = if (isLeap(year)) MonthStartsLeap else MonthStartsNormal
    val days = daysBeforeYear + starts(month - 1) + day - 1
    (((days.toLong * 24L + hour.toLong) * 60L + minute.toLong) * 60L) +
      second.toLong
  }

  private def parseDoubleAt(value: String, from: Int, until: Int): Double = {
    if (from >= until)
      throw new NumberFormatException("empty decimal")

    var i = from
    var sign = 1.0
    val first = value.charAt(i)
    if (first == '-' || first == '+') {
      if (first == '-') sign = -1.0
      i += 1
      if (i >= until)
        throw new NumberFormatException(value.substring(from, until))
    }

    var whole = 0.0
    var fraction = 0.0
    var scale = 0.1
    var seenDigit = false

    while (i < until) {
      val ch = value.charAt(i)
      if (ch >= '0' && ch <= '9') {
        whole = whole * 10.0 + (ch - '0').toDouble
        seenDigit = true
        i += 1
      } else if (ch == '.') {
        i += 1
        while (i < until) {
          val digit = value.charAt(i)
          if (digit >= '0' && digit <= '9') {
            fraction += (digit - '0').toDouble * scale
            scale *= 0.1
            seenDigit = true
            i += 1
          } else {
            return java.lang.Double.parseDouble(value.substring(from, until))
          }
        }
      } else {
        return java.lang.Double.parseDouble(value.substring(from, until))
      }
    }

    if (!seenDigit)
      throw new NumberFormatException(value.substring(from, until))
    sign * (whole + fraction)
  }

  private def intAt(value: String, from: Int, until: Int): Int = {
    var i = from
    var result = 0
    while (i < until) {
      val digit = value.charAt(i) - '0'
      if (digit < 0 || digit > 9)
        throw new NumberFormatException(s"bad timestamp: $value")
      result = result * 10 + digit
      i += 1
    }
    result
  }

  private def isLeap(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

  private def daysInYear(year: Int): Int =
    if (isLeap(year)) 366 else 365
}
