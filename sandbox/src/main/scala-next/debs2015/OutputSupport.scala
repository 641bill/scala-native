package debs2015

import java.io.Writer

private[debs2015] object OutputSupport {
  private val MinLongText = "-9223372036854775808"

  def writeComma(writer: Writer): Unit =
    writer.write(','.toInt)

  def writeInt(writer: Writer, value: Int): Unit =
    writeLong(writer, value.toLong)

  def writeLong(writer: Writer, value: Long): Unit = {
    if (value == Long.MinValue) {
      writer.write(MinLongText)
    } else {
      var current = value
      if (current < 0L) {
        writer.write('-'.toInt)
        current = -current
      }

      var divisor = 1L
      while (current / divisor >= 10L)
        divisor *= 10L

      while (divisor > 0L) {
        val digit = ((current / divisor) % 10L).toInt
        writer.write(('0' + digit).toInt)
        divisor /= 10L
      }
    }
  }

  def writeCellId(writer: Writer, east: Int, south: Int): Unit = {
    writeInt(writer, east)
    writer.write('.'.toInt)
    writeInt(writer, south)
  }

  def writeFixed(writer: Writer, value: Double, digits: Int): Unit = {
    if (java.lang.Double.isNaN(value) || java.lang.Double.isInfinite(value)) {
      writer.write(value.toString)
    } else {
      val factor = powerOf10(digits)
      var scaled = java.lang.Math.round(value * factor.toDouble)
      if (scaled < 0L) {
        writer.write('-'.toInt)
        scaled = -scaled
      }

      writeLong(writer, scaled / factor)
      writer.write('.'.toInt)

      var divisor = factor / 10L
      val fraction = scaled % factor
      while (divisor > 0L) {
        val digit = ((fraction / divisor) % 10L).toInt
        writer.write(('0' + digit).toInt)
        divisor /= 10L
      }
    }
  }

  def appendFixed(builder: StringBuilder, value: Double, digits: Int): Unit = {
    if (java.lang.Double.isNaN(value) || java.lang.Double.isInfinite(value)) {
      builder.append(value)
    } else {
      val factor = powerOf10(digits)
      var scaled = java.lang.Math.round(value * factor.toDouble)
      if (scaled < 0L) {
        builder.append('-')
        scaled = -scaled
      }

      builder.append(scaled / factor)
      builder.append('.')

      var divisor = factor / 10L
      val fraction = scaled % factor
      while (divisor > 0L) {
        val digit = ((fraction / divisor) % 10L).toInt
        builder.append(('0' + digit).toChar)
        divisor /= 10L
      }
    }
  }

  private def powerOf10(digits: Int): Long = {
    var result = 1L
    var i = 0
    while (i < digits) {
      result *= 10L
      i += 1
    }
    result
  }
}
