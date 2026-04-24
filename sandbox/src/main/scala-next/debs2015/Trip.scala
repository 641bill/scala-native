package debs2015

import java.io.Writer

final class Trip(
    private var sourceLine: String,
    private var sourceBytes: Array[Byte],
    private var taxiStart: Int,
    private var taxiEnd: Int,
    private var pickupTimestampStart: Int,
    private var pickupTimestampEnd: Int,
    private var dropoffTimestampStart: Int,
    private var dropoffTimestampEnd: Int,
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

  def taxiId: String =
    sliceToString(taxiStart, taxiEnd)

  def pickupTimestamp: String =
    sliceToString(pickupTimestampStart, pickupTimestampEnd)

  def dropoffTimestamp: String =
    sliceToString(dropoffTimestampStart, dropoffTimestampEnd)

  private[debs2015] def setSourceSlices(
      line: String,
      taxiStart: Int,
      taxiEnd: Int,
      pickupTimestampStart: Int,
      pickupTimestampEnd: Int,
      dropoffTimestampStart: Int,
      dropoffTimestampEnd: Int
  ): Unit = {
    sourceLine = line
    sourceBytes = null
    this.taxiStart = taxiStart
    this.taxiEnd = taxiEnd
    this.pickupTimestampStart = pickupTimestampStart
    this.pickupTimestampEnd = pickupTimestampEnd
    this.dropoffTimestampStart = dropoffTimestampStart
    this.dropoffTimestampEnd = dropoffTimestampEnd
  }

  private[debs2015] def setSourceSlices(
      bytes: Array[Byte],
      taxiStart: Int,
      taxiEnd: Int,
      pickupTimestampStart: Int,
      pickupTimestampEnd: Int,
      dropoffTimestampStart: Int,
      dropoffTimestampEnd: Int
  ): Unit = {
    sourceLine = null
    sourceBytes = bytes
    this.taxiStart = taxiStart
    this.taxiEnd = taxiEnd
    this.pickupTimestampStart = pickupTimestampStart
    this.pickupTimestampEnd = pickupTimestampEnd
    this.dropoffTimestampStart = dropoffTimestampStart
    this.dropoffTimestampEnd = dropoffTimestampEnd
  }

  private[debs2015] def appendPickupTimestamp(builder: StringBuilder): Unit =
    appendSlice(builder, pickupTimestampStart, pickupTimestampEnd)

  private[debs2015] def appendDropoffTimestamp(builder: StringBuilder): Unit =
    appendSlice(builder, dropoffTimestampStart, dropoffTimestampEnd)

  private[debs2015] def writePickupTimestamp(writer: Writer): Unit =
    writeSlice(writer, pickupTimestampStart, pickupTimestampEnd)

  private[debs2015] def writeDropoffTimestamp(writer: Writer): Unit =
    writeSlice(writer, dropoffTimestampStart, dropoffTimestampEnd)

  private[debs2015] def taxiIdHash: Int = {
    var hash = 0
    var i = taxiStart
    while (i < taxiEnd) {
      hash = 31 * hash + charAt(i)
      i += 1
    }
    hash
  }

  private[debs2015] def taxiIdEquals(value: String): Boolean = {
    val len = taxiEnd - taxiStart
    if (value.length != len) false
    else {
      var i = 0
      var same = true
      while (i < len && same) {
        same = charAt(taxiStart + i) == value.charAt(i)
        i += 1
      }
      same
    }
  }

  private def appendSlice(builder: StringBuilder, from: Int, until: Int): Unit = {
    var i = from
    while (i < until) {
      builder.append(charAt(i))
      i += 1
    }
  }

  private def writeSlice(writer: Writer, from: Int, until: Int): Unit = {
    var i = from
    while (i < until) {
      writer.write(charAt(i).toInt)
      i += 1
    }
  }

  private def sliceToString(from: Int, until: Int): String = {
    if (sourceLine != null) sourceLine.substring(from, until)
    else {
      val builder = new StringBuilder(until - from)
      appendSlice(builder, from, until)
      builder.toString()
    }
  }

  private def charAt(index: Int): Char =
    if (sourceLine != null) sourceLine.charAt(index)
    else (sourceBytes(index) & 0xff).toChar
}

object Trip {
  private val ExpectedFields = 17
  private val Comma = ','.toByte
  private val MonthStartsNormal =
    Array(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
  private val MonthStartsLeap =
    Array(0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335)

  def empty: Trip =
    new Trip("", null, 0, 0, 0, 0, 0, 0, 0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

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
        trip.setSourceSlices(
          line,
          taxiStart,
          taxiEnd,
          pickupTsStart,
          pickupTsEnd,
          dropoffTsStart,
          dropoffTsEnd
        )
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

  def parseInto(bytes: Array[Byte], from: Int, until: Int, trip: Trip): Boolean = {
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
    var start = from
    var i = from
    while (i <= until) {
      if (i == until || bytes(i) == Comma) {
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
        trip.setSourceSlices(
          bytes,
          taxiStart,
          taxiEnd,
          pickupTsStart,
          pickupTsEnd,
          dropoffTsStart,
          dropoffTsEnd
        )
        trip.pickupSeconds = parseTimestampAt(bytes, pickupTsStart, pickupTsEnd)
        trip.dropoffSeconds =
          parseTimestampAt(bytes, dropoffTsStart, dropoffTsEnd)
        trip.pickupLongitude = parseDoubleAt(bytes, pickupLonStart, pickupLonEnd)
        trip.pickupLatitude = parseDoubleAt(bytes, pickupLatStart, pickupLatEnd)
        trip.dropoffLongitude =
          parseDoubleAt(bytes, dropoffLonStart, dropoffLonEnd)
        trip.dropoffLatitude =
          parseDoubleAt(bytes, dropoffLatStart, dropoffLatEnd)
        trip.fare = parseDoubleAt(bytes, fareStart, fareEnd)
        trip.tip = parseDoubleAt(bytes, tipStart, tipEnd)
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

  private def parseTimestampAt(value: Array[Byte], from: Int, until: Int): Long = {
    if (until - from < 19)
      throw new NumberFormatException("bad timestamp")

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

  private def parseDoubleAt(value: Array[Byte], from: Int, until: Int): Double = {
    if (from >= until)
      throw new NumberFormatException("empty decimal")

    var i = from
    var sign = 1.0
    val first = byteChar(value(i))
    if (first == '-' || first == '+') {
      if (first == '-') sign = -1.0
      i += 1
      if (i >= until)
        throw new NumberFormatException("bad decimal")
    }

    var whole = 0.0
    var fraction = 0.0
    var scale = 0.1
    var seenDigit = false

    while (i < until) {
      val ch = byteChar(value(i))
      if (ch >= '0' && ch <= '9') {
        whole = whole * 10.0 + (ch - '0').toDouble
        seenDigit = true
        i += 1
      } else if (ch == '.') {
        i += 1
        var readingFraction = true
        while (i < until && readingFraction) {
          val digit = byteChar(value(i))
          if (digit >= '0' && digit <= '9') {
            fraction += (digit - '0').toDouble * scale
            scale *= 0.1
            seenDigit = true
            i += 1
          } else {
            readingFraction = false
          }
        }
      } else if (ch == 'e' || ch == 'E') {
        if (!seenDigit)
          throw new NumberFormatException("bad decimal")
        val base = sign * (whole + fraction)
        return base * java.lang.Math.pow(
          10.0,
          parseExponent(value, i + 1, until).toDouble
        )
      } else {
        return parseDoubleSlow(value, from, until)
      }
    }

    if (!seenDigit)
      throw new NumberFormatException("bad decimal")
    sign * (whole + fraction)
  }

  private def parseExponent(value: Array[Byte], from: Int, until: Int): Int = {
    if (from >= until)
      throw new NumberFormatException("bad decimal")

    var i = from
    var sign = 1
    val first = byteChar(value(i))
    if (first == '-' || first == '+') {
      if (first == '-') sign = -1
      i += 1
      if (i >= until)
        throw new NumberFormatException("bad decimal")
    }

    var exponent = 0
    var seenDigit = false
    while (i < until) {
      val digit = byteChar(value(i)) - '0'
      if (digit < 0 || digit > 9)
        throw new NumberFormatException("bad decimal")
      exponent = exponent * 10 + digit
      seenDigit = true
      i += 1
    }

    if (!seenDigit)
      throw new NumberFormatException("bad decimal")
    sign * exponent
  }

  private def parseDoubleSlow(value: Array[Byte], from: Int, until: Int): Double = {
    val builder = new StringBuilder(until - from)
    var i = from
    while (i < until) {
      builder.append(byteChar(value(i)))
      i += 1
    }
    java.lang.Double.parseDouble(builder.toString())
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

  private def intAt(value: Array[Byte], from: Int, until: Int): Int = {
    var i = from
    var result = 0
    while (i < until) {
      val digit = byteChar(value(i)) - '0'
      if (digit < 0 || digit > 9)
        throw new NumberFormatException("bad timestamp")
      result = result * 10 + digit
      i += 1
    }
    result
  }

  private def byteChar(value: Byte): Char =
    (value & 0xff).toChar

  private def isLeap(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

  private def daysInYear(year: Int): Int =
    if (isLeap(year)) 366 else 365
}
