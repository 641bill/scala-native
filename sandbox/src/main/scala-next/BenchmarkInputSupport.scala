import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.Collections
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object BenchmarkInputSupport {
  def envString(name: String): String =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse("")

  def openText(path: String): BufferedReader = {
    val input = openBinary(path)
    new BufferedReader(new InputStreamReader(input, "UTF-8"), 1024 * 1024)
  }

  def openBinary(path: String): InputStream = {
    if (path.startsWith("tar.gz:")) {
      val (archive, member) = splitArchiveSpec(path, "tar.gz:")
      openTarGzMember(archive, member)
    } else if (path.startsWith("zip:")) {
      val (archive, member) = splitArchiveSpec(path, "zip:")
      openZipMember(archive, member)
    } else if (path.startsWith("7z:")) {
      val (archive, member) = splitArchiveSpec(path, "7z:")
      openBsdtarMember(archive, member)
    } else {
      val raw: InputStream = new FileInputStream(path)
      if (path.endsWith(".gz")) new GZIPInputStream(raw, 1024 * 1024)
      else raw
    }
  }

  private def splitArchiveSpec(spec: String, prefix: String): (String, String) = {
    val body = spec.substring(prefix.length)
    val bang = body.indexOf('!')
    if (bang <= 0 || bang == body.length - 1)
      throw new IllegalArgumentException(
        s"archive input '$spec' must use ${prefix}/path/archive!member"
      )
    (body.substring(0, bang), body.substring(bang + 1))
  }

  private final class ProcessInput(
      process: Process,
      input: InputStream,
      description: String
  ) extends FilterInputStream(input) {
    override def close(): Unit = {
      var closeError: Throwable = null
      try super.close()
      catch {
        case t: Throwable => closeError = t
      }
      if (process.isAlive()) process.destroy()
      val exit =
        try process.waitFor()
        catch {
          case _: InterruptedException =>
            Thread.currentThread().interrupt()
            process.destroy()
            -1
        }
      if (closeError != null) throw closeError
      // Archive readers are often closed early after a benchmark reaches its
      // record limit. bsdtar reports that as exit 1 on macOS instead of SIGPIPE.
      if (exit != 0 && exit != 1 && exit != 141 && exit != 143)
        throw new IOException(s"$description exited with status $exit")
    }
  }

  private def openTarGzMember(archive: String, member: String): InputStream = {
    val process =
      new ProcessBuilder("tar", "-xOzf", archive, member)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    new ProcessInput(process, process.getInputStream, s"tar member $member")
  }

  private def openBsdtarMember(archive: String, member: String): InputStream = {
    val process =
      new ProcessBuilder("bsdtar", "-xOf", archive, member)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    new ProcessInput(process, process.getInputStream, s"archive member $member")
  }

  private final class ZipEntryInput(
      zip: ZipInputStream,
      archive: String,
      member: String
  ) extends FilterInputStream(zip) {
    override def close(): Unit = {
      try super.close()
      catch {
        case e: IOException =>
          throw new IOException(s"failed closing zip member $member in $archive", e)
      }
    }
  }

  private def openZipMember(archive: String, member: String): InputStream = {
    val zip = new ZipInputStream(new FileInputStream(archive))
    var entry = zip.getNextEntry()
    while (entry != null) {
      if (!entry.isDirectory && entry.getName == member)
        return new ZipEntryInput(zip, archive, member)
      zip.closeEntry()
      entry = zip.getNextEntry()
    }
    zip.close()
    throw new IOException(s"zip member $member not found in $archive")
  }

  def zipDirectoryMembers(archive: String, prefix: String): Array[String] = {
    val normalizedPrefix =
      if (prefix.endsWith("/")) prefix else prefix + "/"
    val zip = new ZipInputStream(new FileInputStream(archive))
    val members = new ArrayList[String]()
    try {
      var entry = zip.getNextEntry()
      while (entry != null) {
        val name = entry.getName
        if (!entry.isDirectory && name.startsWith(normalizedPrefix))
          members.add(name)
        zip.closeEntry()
        entry = zip.getNextEntry()
      }
    } finally zip.close()
    Collections.sort(members)
    val out = new Array[String](members.size())
    var i = 0
    while (i < out.length) {
      out(i) = members.get(i)
      i += 1
    }
    out
  }

  def zipDirectorySpecs(spec: String): Array[String] = {
    val (archive, prefix) = splitArchiveSpec(spec, "zipdir:")
    val members = zipDirectoryMembers(archive, prefix)
    val specs = new Array[String](members.length)
    var i = 0
    while (i < members.length) {
      specs(i) = s"zip:$archive!${members(i)}"
      i += 1
    }
    specs
  }

  final class ByteLineReader(path: String) {
    private val input = openBinary(path)
    private val readBuffer = new Array[Byte](1024 * 1024)
    private var readOffset = 0
    private var readLimit = 0
    private var closed = false
    private var bytesReadTotal = 0L
    private var linesReadTotal = 0L

    private var lineBuffer = new Array[Byte](8192)
    private var currentLength = 0

    def bytes: Array[Byte] = lineBuffer

    def length: Int = currentLength

    def bytesRead: Long = bytesReadTotal

    def linesRead: Long = linesReadTotal

    private def nextByte(): Int = {
      if (readOffset >= readLimit) {
        readLimit = input.read(readBuffer)
        readOffset = 0
        if (readLimit <= 0) return -1
        bytesReadTotal += readLimit.toLong
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
          linesReadTotal += 1L
          return currentLength
        }
        append(value)
        value = nextByte()
      }
      if (currentLength == 0) -1
      else {
        if (lineBuffer(currentLength - 1) == '\r'.toByte) currentLength -= 1
        linesReadTotal += 1L
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

  final class StreamingByteLineSource(paths: Array[String]) {
    if (paths.isEmpty)
      throw new IllegalArgumentException("streaming byte-line source needs at least one path")

    private var pathIndex = 0
    private var current: ByteLineReader = null
    private var closed = false
    private var bytesReadTotal = 0L
    private var linesReadTotal = 0L

    private def openCurrent(): Unit =
      if (current == null && pathIndex < paths.length)
        current = openByteLines(paths(pathIndex))

    private def closeCurrent(): Unit =
      if (current != null) {
        bytesReadTotal += current.bytesRead
        linesReadTotal += current.linesRead
        current.close()
        current = null
      }

    def bytes: Array[Byte] = current.bytes

    def length: Int = current.length

    def inputFiles: Int = paths.length

    def bytesRead: Long =
      bytesReadTotal + (if (current == null) 0L else current.bytesRead)

    def linesRead: Long =
      linesReadTotal + (if (current == null) 0L else current.linesRead)

    def readLine(): Int = {
      openCurrent()
      while (current != null) {
        val length = current.readLine()
        if (length >= 0) return length
        closeCurrent()
        pathIndex += 1
        openCurrent()
      }
      -1
    }

    def close(): Unit =
      if (!closed) {
        closed = true
        closeCurrent()
      }
  }

  def openStreamingByteLines(paths: Array[String]): StreamingByteLineSource =
    new StreamingByteLineSource(paths)

  final class DelimitedByteFields(delimiter: Int) {
    private var bytesRef: Array[Byte] = _
    private var lineLength = 0
    private var currentIndex = 0
    private var currentStart = 0
    private var currentEnd = 0
    private var nextOffset = 0
    private var valid = false

    def reset(bytes: Array[Byte], length: Int): Unit = {
      bytesRef = bytes
      lineLength = length
      currentIndex = 0
      currentStart = 0
      currentEnd = 0
      nextOffset = 0
      valid = length >= 0
      if (valid) readCurrent()
    }

    private def readCurrent(): Unit = {
      currentStart = nextOffset
      var i = nextOffset
      while (i < lineLength && (bytesRef(i) & 0xff) != delimiter)
        i += 1
      currentEnd = i
      nextOffset = if (i < lineLength) i + 1 else lineLength + 1
    }

    private def advanceOne(): Unit =
      if (valid && nextOffset <= lineLength) {
        currentIndex += 1
        readCurrent()
      } else {
        valid = false
      }

    def advanceTo(index: Int): Boolean = {
      while (valid && currentIndex < index) advanceOne()
      valid && currentIndex == index
    }

    def offset: Int = currentStart

    def length: Int = currentEnd - currentStart
  }

  def parseMilliDecimal(bytes: Array[Byte], offset: Int, length: Int): Int =
    if (bytes == null || length <= 0) -1
    else if (length == 1 && bytes(offset) == '?'.toByte) -1
    else {
      var whole = 0
      var frac = 0
      var fracDigits = 0
      var seenDot = false
      var i = offset
      val end = offset + length
      while (i < end) {
        val c = bytes(i) & 0xff
        if (c == '.') seenDot = true
        else if (c >= '0' && c <= '9') {
          val digit = c - '0'
          if (seenDot && fracDigits < 3) {
            frac = frac * 10 + digit
            fracDigits += 1
          } else if (!seenDot) {
            whole = whole * 10 + digit
          }
        } else return -1
        i += 1
      }
      while (fracDigits < 3) {
        frac *= 10
        fracDigits += 1
      }
      whole * 1000 + frac
    }

  def parseIntDecimal(bytes: Array[Byte], offset: Int, length: Int): Int = {
    val milli = parseMilliDecimal(bytes, offset, length)
    if (milli < 0) -1 else milli / 1000
  }

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
