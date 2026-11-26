// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.assembly

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * One archive entry as the reproducibility comparison sees it ([CompareArchives]): everything that
 * can make two archives with the same files differ byte for byte.
 *
 * @property name entry path inside the archive
 * @property time entry time as stored (zip: DOS date-time and the extended timestamp field when
 *   present; tar: mtime seconds)
 * @property mode permission bits and type (zip: Unix mode when the entry was made on Unix, else the
 *   raw external attributes; tar: type flag and mode)
 * @property owner tar uid:gid and user/group names; empty for zip
 * @property storage how the bytes are stored (zip compression method), empty for tar
 * @property sha256 SHA-256 of the uncompressed content
 * @property size uncompressed size
 * @property index position in the archive (0-based)
 */
data class ArchiveEntry(
  val name: String,
  val time: String,
  val mode: String,
  val owner: String,
  val storage: String,
  val sha256: String,
  val size: Long,
  val index: Int,
)

/** Random access to archive bytes: a file or a byte array (nested archives). */
internal interface ByteSource : AutoCloseable {
  val length: Long

  fun read(position: Long, length: Int): ByteArray

  override fun close() {}

  class OfBytes(private val bytes: ByteArray) : ByteSource {
    override val length: Long
      get() = bytes.size.toLong()

    override fun read(position: Long, length: Int): ByteArray {
      if (position < 0 || position + length > bytes.size) throw EOFException("read beyond end")
      return bytes.copyOfRange(position.toInt(), position.toInt() + length)
    }
  }

  class OfFile(file: File) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val length: Long = raf.length()

    override fun read(position: Long, length: Int): ByteArray {
      if (position < 0 || position + length > this.length) throw EOFException("read beyond end")
      val out = ByteArray(length)
      raf.seek(position)
      raf.readFully(out)
      return out
    }

    override fun close() = raf.close()
  }
}

private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xff) or ((this[at + 1].toInt() and 0xff) shl 8)

private fun ByteArray.u32(at: Int): Long = (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)

private fun ByteArray.u64(at: Int): Long = u32(at) or (u32(at + 4) shl 32)

internal fun sha256Hex(input: InputStream): Pair<String, Long> {
  val digest = MessageDigest.getInstance("SHA-256")
  val buffer = ByteArray(64 * 1024)
  var total = 0L
  while (true) {
    val read = input.read(buffer)
    if (read < 0) break
    digest.update(buffer, 0, read)
    total += read
  }
  return digest.digest().joinToString("") { "%02x".format(it) } to total
}

/**
 * Reads a zip (jar) from its central directory, including zip64 archives. Entry content is read
 * through the local headers with the central directory's sizes (data descriptors are supported).
 */
internal class ZipArchive(private val source: ByteSource) {

  private class Raw(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val localOffset: Long,
    val entry: ArchiveEntry,
  )

  private val raw: List<Raw> = readCentralDirectory()

  val entries: List<ArchiveEntry>
    get() = raw.map { it.entry }

  /** Uncompressed content of the entry at [index]. */
  fun content(index: Int): ByteArray = open(raw[index]).use { it.readBytes() }

  private fun readCentralDirectory(): List<Raw> {
    val size = source.length
    if (size < 22) throw IllegalArgumentException("not a zip archive (too short)")
    val tailLength = minOf(size, 22L + 65535L).toInt()
    val tailStart = size - tailLength
    val tail = source.read(tailStart, tailLength)
    var eocd = -1
    for (i in tail.size - 22 downTo 0) {
      if (tail.u32(i) == 0x06054b50L) {
        eocd = i
        break
      }
    }
    if (eocd < 0) throw IllegalArgumentException("not a zip archive (no end of central directory)")
    var count = tail.u16(eocd + 10).toLong()
    var cdSize = tail.u32(eocd + 12)
    var cdOffset = tail.u32(eocd + 16)
    if (count == 0xffffL || cdSize == 0xffffffffL || cdOffset == 0xffffffffL) {
      val locatorAt = tailStart + eocd - 20
      val locator = source.read(locatorAt, 20)
      if (locator.u32(0) != 0x07064b50L) throw IllegalArgumentException("zip64 locator missing")
      val record = source.read(locator.u64(8), 56)
      if (record.u32(0) != 0x06064b50L) throw IllegalArgumentException("zip64 end record missing")
      count = record.u64(32)
      cdSize = record.u64(40)
      cdOffset = record.u64(48)
    }
    if (cdSize > Int.MAX_VALUE) throw IllegalArgumentException("central directory too large")
    val cd = source.read(cdOffset, cdSize.toInt())
    val result = ArrayList<Raw>()
    var at = 0
    for (index in 0 until count.toInt()) {
      if (cd.u32(at) != 0x02014b50L) throw IllegalArgumentException("bad central directory entry #$index")
      val madeBy = cd.u16(at + 4)
      val flags = cd.u16(at + 8)
      val method = cd.u16(at + 10)
      val dosTime = cd.u16(at + 12)
      val dosDate = cd.u16(at + 14)
      var compressed = cd.u32(at + 20)
      var uncompressed = cd.u32(at + 24)
      val nameLength = cd.u16(at + 28)
      val extraLength = cd.u16(at + 30)
      val commentLength = cd.u16(at + 32)
      val external = cd.u32(at + 38)
      var localOffset = cd.u32(at + 42)
      val nameBytes = cd.copyOfRange(at + 46, at + 46 + nameLength)
      val name = String(nameBytes, if (flags and 0x800 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1)
      val extra = cd.copyOfRange(at + 46 + nameLength, at + 46 + nameLength + extraLength)
      var extendedTime: Long? = null
      var e = 0
      while (e + 4 <= extra.size) {
        val id = extra.u16(e)
        val length = extra.u16(e + 2)
        val body = e + 4
        if (body + length > extra.size) break
        if (id == 0x0001) {
          var p = body
          if (uncompressed == 0xffffffffL && p + 8 <= body + length) uncompressed = extra.u64(p).also { p += 8 }
          if (compressed == 0xffffffffL && p + 8 <= body + length) compressed = extra.u64(p).also { p += 8 }
          if (localOffset == 0xffffffffL && p + 8 <= body + length) localOffset = extra.u64(p).also { p += 8 }
        } else if (id == 0x5455 && length >= 5 && (extra[body].toInt() and 1) != 0) {
          extendedTime = extra.u32(body + 1)
        }
        e = body + length
      }
      val time =
        "%04d-%02d-%02d %02d:%02d:%02d".format(
          1980 + (dosDate shr 9),
          (dosDate shr 5) and 0x0f,
          dosDate and 0x1f,
          dosTime shr 11,
          (dosTime shr 5) and 0x3f,
          (dosTime and 0x1f) * 2,
        ) + (extendedTime?.let { " ut=$it" } ?: "")
      val unixMode = (external ushr 16) and 0xffff
      val mode =
        if ((madeBy shr 8) == 3 && unixMode != 0L) "0" + java.lang.Long.toOctalString(unixMode)
        else "ext=0x" + java.lang.Long.toHexString(external)
      val provisional =
        ArchiveEntry(name, time, mode, "", "method=$method", "", uncompressed, index)
      val rawEntry = Raw(name, method, compressed, localOffset, provisional)
      val (hash, length) = open(rawEntry).use { sha256Hex(it) }
      result += Raw(name, method, compressed, localOffset, provisional.copy(sha256 = hash, size = length))
      at += 46 + nameLength + extraLength + commentLength
    }
    return result
  }

  private fun open(entry: Raw): InputStream {
    val header = source.read(entry.localOffset, 30)
    if (header.u32(0) != 0x04034b50L) throw IllegalArgumentException("bad local header of ${entry.name}")
    val dataStart = entry.localOffset + 30 + header.u16(26) + header.u16(28)
    if (entry.compressedSize > Int.MAX_VALUE) throw IllegalArgumentException("entry too large: ${entry.name}")
    val data = ByteArrayInputStream(source.read(dataStart, entry.compressedSize.toInt()))
    return when (entry.method) {
      0 -> data
      8 -> InflaterInputStream(data, Inflater(true), 64 * 1024)
      // Other methods are not used by Gradle, Jib or the JDK: compare the stored bytes.
      else -> data
    }
  }
}

/**
 * Reads a tar stream (ustar, GNU long names, pax `path`/`mtime` records): entries with their
 * mode, owner and mtime, content hashed while streaming.
 */
internal object TarArchive {

  private fun field(block: ByteArray, from: Int, length: Int): String {
    var end = from
    while (end < from + length && block[end].toInt() != 0) end++
    return String(block, from, end - from, Charsets.UTF_8)
  }

  private fun number(block: ByteArray, from: Int, length: Int): Long {
    if ((block[from].toInt() and 0x80) != 0) {
      var value = (block[from].toLong() and 0x7f)
      for (i in from + 1 until from + length) value = (value shl 8) or (block[i].toLong() and 0xff)
      return value
    }
    val text = field(block, from, length).trim { it == ' ' || it == '\u0000' }
    return if (text.isEmpty()) 0 else text.toLong(8)
  }

  private fun InputStream.readBlock(): ByteArray? {
    val block = ByteArray(512)
    var read = 0
    while (read < 512) {
      val n = read(block, read, 512 - read)
      if (n < 0) {
        if (read == 0) return null
        throw EOFException("truncated tar header")
      }
      read += n
    }
    return block
  }

  private fun InputStream.skipFully(count: Long) {
    var left = count
    val buffer = ByteArray(8192)
    while (left > 0) {
      val n = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
      if (n < 0) throw EOFException("truncated tar entry")
      left -= n
    }
  }

  /** A bounded view of the next [size] bytes of [input] (does not close [input]). */
  private class Bounded(private val input: InputStream, size: Long) : InputStream() {
    private var left = size

    override fun read(): Int {
      if (left <= 0) return -1
      val b = input.read()
      if (b < 0) throw EOFException("truncated tar entry")
      left--
      return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (left <= 0) return -1
      val n = input.read(b, off, minOf(len.toLong(), left).toInt())
      if (n < 0) throw EOFException("truncated tar entry")
      left -= n
      return n
    }
  }

  private fun paxRecords(data: ByteArray): Map<String, String> {
    val result = HashMap<String, String>()
    var at = 0
    while (at < data.size) {
      val space = (at until data.size).firstOrNull { data[it] == ' '.code.toByte() } ?: break
      val length = String(data, at, space - at, Charsets.US_ASCII).toIntOrNull() ?: break
      if (length <= 0 || at + length > data.size) break
      val record = String(data, space + 1, at + length - space - 2, Charsets.UTF_8)
      val eq = record.indexOf('=')
      if (eq > 0) result[record.substring(0, eq)] = record.substring(eq + 1)
      at += length
    }
    return result
  }

  /**
   * Walks [input]; for every regular entry, directory or link calls [visit] with the entry and a
   * stream of its content (to be consumed or ignored; the rest is skipped).
   */
  fun walk(input: InputStream, visit: (ArchiveEntry, InputStream) -> ArchiveEntry) {
    var longName: String? = null
    var pax: Map<String, String> = emptyMap()
    var index = 0
    while (true) {
      val header = input.readBlock() ?: return
      if (header.all { it.toInt() == 0 }) return
      val type = header[156].toInt().toChar()
      val size = number(header, 124, 12)
      val padded = (size + 511) / 512 * 512
      when (type) {
        'L' -> {
          val data = Bounded(input, size).readBytes()
          input.skipFully(padded - size)
          longName = String(data, Charsets.UTF_8).trimEnd('\u0000')
          continue
        }
        'x' -> {
          val data = Bounded(input, size).readBytes()
          input.skipFully(padded - size)
          pax = paxRecords(data)
          continue
        }
        'g' -> {
          input.skipFully(padded)
          continue
        }
      }
      val prefix = if (field(header, 257, 5) == "ustar") field(header, 345, 155) else ""
      val baseName = field(header, 0, 100)
      val name =
        pax["path"] ?: longName ?: if (prefix.isNotEmpty()) "$prefix/$baseName" else baseName
      val mtime = pax["mtime"] ?: number(header, 136, 12).toString()
      val mode = number(header, 100, 8)
      val typeName = if (type == '\u0000') '0' else type
      val owner =
        "${number(header, 108, 8)}:${number(header, 116, 8)} " +
          "${field(header, 265, 32)}/${field(header, 297, 32)}"
      val link = field(header, 157, 100)
      val entry =
        ArchiveEntry(
          name,
          mtime,
          "type=$typeName 0" + java.lang.Long.toOctalString(mode and 0xfff) +
            (if (link.isNotEmpty()) " -> $link" else ""),
          owner,
          "",
          "",
          size,
          index++,
        )
      val body = Bounded(input, size)
      visit(entry, body)
      drain(body)
      input.skipFully(padded - size)
      longName = null
      pax = emptyMap()
    }
  }

  private fun drain(input: InputStream) {
    val buffer = ByteArray(8192)
    while (input.read(buffer) >= 0) {
      // the rest of an entry the visitor did not read
    }
  }

  /** Lists the entries of the tar in [open] with content hashes. */
  fun entries(open: () -> InputStream): List<ArchiveEntry> {
    val result = ArrayList<ArchiveEntry>()
    open().use { input ->
      walk(input.buffered(256 * 1024)) { entry, body ->
        val (hash, length) = sha256Hex(body)
        entry.copy(sha256 = hash, size = length).also { result += it }
      }
    }
    return result
  }

  /** Content of the entry at [index] of the tar in [open]. */
  fun content(open: () -> InputStream, index: Int): ByteArray {
    var found: ByteArray? = null
    open().use { input ->
      walk(input.buffered(256 * 1024)) { entry, body ->
        if (entry.index == index) found = body.readBytes()
        entry
      }
    }
    return found ?: throw IllegalArgumentException("tar entry #$index not found")
  }
}

/** The archive kinds the comparison opens, by file name. */
internal enum class ArchiveKind {
  ZIP,
  TAR,
  GZIP_TAR,
  NONE;

  companion object {
    fun of(name: String): ArchiveKind {
      val lower = name.lowercase()
      return when {
        lower.endsWith(".jar") || lower.endsWith(".zip") || lower.endsWith(".war") -> ZIP
        lower.endsWith(".tar") -> TAR
        lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> GZIP_TAR
        else -> NONE
      }
    }

    /** Whether [bytes] look like a tar (ustar magic) after gunzip, for layer blobs without a suffix. */
    fun gunzipIsTar(bytes: ByteArray): Boolean =
      try {
        val head = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readNBytes(263) }
        head.size >= 262 && String(head, 257, 5, Charsets.US_ASCII) == "ustar"
      } catch (e: java.io.IOException) {
        false
      }
  }
}
