import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

object BenchmarkInputSupport {
  def envString(name: String): String =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse("")

  def openText(path: String): BufferedReader = {
    val input = openBinary(path)
    new BufferedReader(new InputStreamReader(input, "UTF-8"), 1024 * 1024)
  }

  def openBinary(path: String): InputStream = {
    val raw: InputStream = new FileInputStream(path)
    if (path.endsWith(".gz")) new GZIPInputStream(raw, 1024 * 1024)
    else raw
  }

  final class ByteLineReader(path: String) {
    private val input = openBinary(path)
    private val readBuffer = new Array[Byte](1024 * 1024)
    private var readOffset = 0
    private var readLimit = 0
    private var closed = false

    private var lineBuffer = new Array[Byte](8192)
    private var currentLength = 0

    def bytes: Array[Byte] = lineBuffer

    def length: Int = currentLength

    private def nextByte(): Int = {
      if (readOffset >= readLimit) {
        readLimit = input.read(readBuffer)
        readOffset = 0
        if (readLimit <= 0) return -1
      }
      val value = readBuffer(readOffset) & 0xff
      readOffset += 1
      value
    }

    private def append(value: Int): Unit = {
      if (currentLength >= lineBuffer.length) {
        val grown = new Array[Byte](lineBuffer.length << 1)
        System.arraycopy(lineBuffer, 0, grown, 0, lineBuffer.length)
        lineBuffer = grown
      }
      lineBuffer(currentLength) = value.toByte
      currentLength += 1
    }

    def readLine(): Int = {
      currentLength = 0
      var value = nextByte()
      while (value >= 0) {
        if (value == '\n') {
          if (currentLength > 0 && lineBuffer(currentLength - 1) == '\r'.toByte)
            currentLength -= 1
          return currentLength
        }
        append(value)
        value = nextByte()
      }
      if (currentLength == 0) -1
      else {
        if (lineBuffer(currentLength - 1) == '\r'.toByte) currentLength -= 1
        currentLength
      }
    }

    def close(): Unit =
      if (!closed) {
        closed = true
        input.close()
      }
  }

  def openByteLines(path: String): ByteLineReader =
    new ByteLineReader(path)

  def stableHash(value: String): Int = {
    var h = 0x811c9dc5
    var i = 0
    while (i < value.length) {
      h ^= value.charAt(i).toInt
      h *= 0x01000193
      i += 1
    }
    h & 0x7fffffff
  }

  def stableHash(bytes: Array[Byte], offset: Int, length: Int): Int = {
    var h = 0x811c9dc5
    var i = offset
    val end = offset + length
    while (i < end) {
      h ^= bytes(i) & 0xff
      h *= 0x01000193
      i += 1
    }
    h & 0x7fffffff
  }

  def positiveModulo(value: Int, mod: Int): Int =
    if (mod <= 1) 0 else (value & 0x7fffffff) % mod

  def positiveModulo(value: Long, mod: Int): Int =
    if (mod <= 1) 0 else ((value & 0x7fffffffffffffffL) % mod.toLong).toInt

  def parseInt(value: String, default: Int): Int =
    try value.toInt
    catch {
      case _: NumberFormatException => default
    }

  def parseLong(value: String, default: Long): Long =
    try value.toLong
    catch {
      case _: NumberFormatException => default
    }

  def tokenCount(line: String, limit: Int): Int = {
    var count = 0
    var inToken = false
    var i = 0
    while (i < line.length && count < limit) {
      val c = line.charAt(i)
      val ws = c == ' ' || c == '\t' || c == '\r' || c == '\n'
      if (ws) inToken = false
      else if (!inToken) {
        count += 1
        inToken = true
      }
      i += 1
    }
    count
  }
}
