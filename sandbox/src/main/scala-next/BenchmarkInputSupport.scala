import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

object BenchmarkInputSupport {
  def envString(name: String): String =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse("")

  def openText(path: String): BufferedReader = {
    val raw: InputStream = new FileInputStream(path)
    val input =
      if (path.endsWith(".gz")) new GZIPInputStream(raw, 1024 * 1024)
      else raw
    new BufferedReader(new InputStreamReader(input, "UTF-8"), 1024 * 1024)
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
