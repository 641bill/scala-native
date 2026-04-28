package debs2015

import java.io.FileInputStream

import scala.language.experimental.captureChecking
import scala.scalanative.memory.RiftRegion

final class CsvLineReader(path: String, mode: String) {
  private val BufferSize = 1024 * 1024
  private val Newline = '\n'.toByte
  private val CarriageReturn = '\r'.toByte
  private val useRegions = mode.startsWith("rift-")
  private val region =
    if (useRegions) RiftRegion.open(regionKind(mode)) else null
  if (useRegions)
    DebsRegionFamilies.set(region, DebsRegionFamilies.Input)

  private val input = new FileInputStream(path)
  private val storage =
    if (useRegions) region.alloc(new Array[Byte](BufferSize))
    else new Array[Byte](BufferSize)

  private var scanStart = 0
  private var filled = 0
  private var eof = false

  var lineStart: Int = 0
  var lineEnd: Int = 0

  def bytes: Array[Byte] = storage

  def nextLine(): Boolean = {
    while (true) {
      var i = scanStart
      while (i < filled) {
        if (storage(i) == Newline) {
          lineStart = scanStart
          lineEnd =
            if (i > lineStart && storage(i - 1) == CarriageReturn) i - 1 else i
          scanStart = i + 1
          return true
        }
        i += 1
      }

      if (eof) {
        if (scanStart < filled) {
          lineStart = scanStart
          lineEnd =
            if (filled > lineStart && storage(filled - 1) == CarriageReturn)
              filled - 1
            else filled
          scanStart = filled
          return true
        }
        return false
      }

      compactIfNeeded()
      val read = input.read(storage, filled, storage.length - filled)
      if (read < 0) eof = true
      else filled += read
    }

    false
  }

  def close(): Unit = {
    input.close()
    if (useRegions) region.close()
  }

  private def compactIfNeeded(): Unit = {
    if (scanStart > 0) {
      val remaining = filled - scanStart
      java.lang.System.arraycopy(storage, scanStart, storage, 0, remaining)
      scanStart = 0
      filled = remaining
    } else if (filled == storage.length) {
      throw new IllegalArgumentException(
        s"CSV row exceeds ${storage.length} byte input buffer"
      )
    }
  }

  private def regionKind(mode: String): Int =
    mode match {
      case "rift-streaming" => RiftRegion.Streaming
      case _                => RiftRegion.HPZone
    }
}
