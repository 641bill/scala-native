package debs2015

import java.io.FileOutputStream
import java.io.Writer

import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion

private[debs2015] object OutputSupport {
  private val MinLongText = "-9223372036854775808"
  private val DefaultByteBufferSize = 64 * 1024

  final class ByteRowWriter private[debs2015] (
      out: FileOutputStream,
      buffer: Array[Byte]
  ) extends AutoCloseable {
    private var size = 0

    def writeByte(value: Int): Unit = {
      if (size == buffer.length) flushBuffer()
      buffer(size) = (value & 0xff).toByte
      size += 1
    }

    def writeAscii(value: String): Unit = {
      var i = 0
      while (i < value.length) {
        writeByte(value.charAt(i).toInt)
        i += 1
      }
    }

    def newLine(): Unit =
      writeByte('\n'.toInt)

    def flush(): Unit = {
      flushBuffer()
      out.flush()
    }

    override def close(): Unit =
      try flush()
      finally out.close()

    private def flushBuffer(): Unit =
      if (size > 0) {
        out.write(buffer, 0, size)
        size = 0
      }
  }

  object ByteRowWriter {
    def open(path: String, region: RiftRegion): ByteRowWriter = {
      val buffer =
        if (region == null) new Array[Byte](DefaultByteBufferSize)
        else region.alloc(new Array[Byte](DefaultByteBufferSize))
      new ByteRowWriter(new FileOutputStream(path), buffer)
    }
  }

  def writeComma(writer: Writer): Unit =
    writer.write(','.toInt)

  def writeComma(writer: ByteRowWriter): Unit =
    writer.writeByte(','.toInt)

  def writeInt(writer: Writer, value: Int): Unit =
    writeLong(writer, value.toLong)

  def writeInt(writer: ByteRowWriter, value: Int): Unit =
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

  def writeLong(writer: ByteRowWriter, value: Long): Unit = {
    if (value == Long.MinValue) {
      writer.writeAscii(MinLongText)
    } else {
      var current = value
      if (current < 0L) {
        writer.writeByte('-'.toInt)
        current = -current
      }

      var divisor = 1L
      while (current / divisor >= 10L)
        divisor *= 10L

      while (divisor > 0L) {
        val digit = ((current / divisor) % 10L).toInt
        writer.writeByte('0' + digit)
        divisor /= 10L
      }
    }
  }

  def writeCellId(writer: Writer, east: Int, south: Int): Unit = {
    writeInt(writer, east)
    writer.write('.'.toInt)
    writeInt(writer, south)
  }

  def writeCellId(writer: ByteRowWriter, east: Int, south: Int): Unit = {
    writeInt(writer, east)
    writer.writeByte('.'.toInt)
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

  def writeFixed(writer: ByteRowWriter, value: Double, digits: Int): Unit = {
    if (java.lang.Double.isNaN(value) || java.lang.Double.isInfinite(value)) {
      writer.writeAscii(value.toString)
    } else {
      val factor = powerOf10(digits)
      var scaled = java.lang.Math.round(value * factor.toDouble)
      if (scaled < 0L) {
        writer.writeByte('-'.toInt)
        scaled = -scaled
      }

      writeLong(writer, scaled / factor)
      writer.writeByte('.'.toInt)

      var divisor = factor / 10L
      val fraction = scaled % factor
      while (divisor > 0L) {
        val digit = ((fraction / divisor) % 10L).toInt
        writer.writeByte('0' + digit)
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
