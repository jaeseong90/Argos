// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.assembly

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Reproducibility comparison of two release outputs (T-BLD-009, 19 §3.7, 16 §10.4,
 * test/release/verify-reproducible.sh): the same file set with the same SHA-256 per file. When a
 * file differs and is an archive (jar, zip, war, tar, tar.gz, and nested jars and image layers
 * inside them, up to [MAX_DEPTH] levels), the comparison goes down to the entries and reports
 * which ones differ in name set, order, time, mode, owner, storage or content, each with its
 * likely cause.
 */
object CompareArchives {

  /** Nesting depth below the top-level files (jar in a boot jar, layer in an image tar). */
  const val MAX_DEPTH: Int = 3

  enum class Kind(val label: String) {
    ONLY_IN_A("only in A"),
    ONLY_IN_B("only in B"),
    ORDER("order"),
    TIME("time"),
    MODE("mode"),
    OWNER("owner"),
    STORAGE("storage"),
    CONTENT("content"),
    ARCHIVE_BYTES("archive bytes"),
  }

  enum class Cause(val text: String) {
    TIMESTAMPS("timestamps: entry times differ (isPreserveFileTimestamps=false, SOURCE_DATE_EPOCH, Jib filesModificationTime)"),
    ORDER("entry order differs (isReproducibleFileOrder=true, sorted inputs)"),
    PERMISSIONS("permissions or owner differ (fixed file/dir permissions, umask, Jib extraDirectories permissions)"),
    COMPRESSION("compression differs (method, level or zip/gzip tool version)"),
    VITE_HASHES("Vite hashes: bundled asset names or contents differ (non-deterministic UI build input)"),
    BUILD_METADATA("embedded build metadata differs (build time, JDK, host, commit)"),
    SBOM_METADATA("SBOM metadata differs (timestamp, serial number, tool or component versions)"),
    IMAGE_METADATA("image config or digest differs (follows a layer, label or creation time difference)"),
    COMPILED_CODE("compiled classes differ (compiler or JDK version, generated sources, annotation processing order)"),
    FILE_SET("file set differs (an output is produced by only one build)"),
    CONTENT("content differs"),
  }

  data class Difference(val path: String, val kind: Kind, val detail: String, val cause: Cause)

  /** One top-level file: its SHA-256 in A and B (null when missing). */
  data class FileResult(val name: String, val sha256A: String?, val sha256B: String?)

  data class Result(val files: List<FileResult>, val differences: List<Difference>) {
    val identical: Boolean
      get() = differences.isEmpty()
  }

  private val VITE_ASSET = Regex("(^|/)assets/[^/]+[-.][A-Za-z0-9_-]{8,}\\.[A-Za-z0-9]+$")

  /** Likely cause of a [kind] difference at entry [path]. */
  fun likelyCause(kind: Kind, path: String): Cause {
    val leaf = path.substringAfterLast("!/").substringAfterLast('/')
    val inner = path.substringAfterLast("!/")
    return when (kind) {
      Kind.TIME -> Cause.TIMESTAMPS
      Kind.ORDER -> Cause.ORDER
      Kind.MODE,
      Kind.OWNER -> Cause.PERMISSIONS
      Kind.STORAGE,
      Kind.ARCHIVE_BYTES -> Cause.COMPRESSION
      Kind.ONLY_IN_A,
      Kind.ONLY_IN_B -> if (VITE_ASSET.containsMatchIn(inner)) Cause.VITE_HASHES else Cause.FILE_SET
      Kind.CONTENT ->
        when {
          VITE_ASSET.containsMatchIn(inner) -> Cause.VITE_HASHES
          (inner.startsWith("static/") || inner.contains("/static/") || inner.startsWith("BOOT-INF/classes/static/")) &&
            Regex("\\.(js|mjs|css|html|map)$").containsMatchIn(leaf) -> Cause.VITE_HASHES
          leaf == "MANIFEST.MF" || leaf.endsWith(".properties") -> Cause.BUILD_METADATA
          leaf.endsWith(".cdx.json") || leaf == "bom.json" -> Cause.SBOM_METADATA
          leaf.endsWith(".digest") || leaf.endsWith(".id") || leaf == "config.json" || leaf == "manifest.json" ||
            leaf == "index.json" -> Cause.IMAGE_METADATA
          leaf.endsWith(".class") || leaf.endsWith(".classdata") -> Cause.COMPILED_CODE
          else -> Cause.CONTENT
        }
    }
  }

  /** Regular files below [dir], relative with '/', in byte order. */
  private fun listFiles(dir: File): List<String> =
    dir
      .walkTopDown()
      .filter { it.isFile }
      .map { it.relativeTo(dir).invariantSeparatorsPath }
      .sortedWith(ReleaseAssets.Comparators.BYTES)
      .toList()

  /** Compares the release outputs in [a] and [b]. */
  fun compareDirectories(a: File, b: File): Result {
    require(a.isDirectory) { "not a directory: $a" }
    require(b.isDirectory) { "not a directory: $b" }
    val namesA = listFiles(a)
    val namesB = listFiles(b)
    val all = (namesA + namesB).toSortedSet(ReleaseAssets.Comparators.BYTES)
    val files = ArrayList<FileResult>()
    val differences = ArrayList<Difference>()
    for (name in all) {
      val fileA = File(a, name).takeIf { name in namesA }
      val fileB = File(b, name).takeIf { name in namesB }
      val shaA = fileA?.let { ReleaseAssets.sha256(it) }
      val shaB = fileB?.let { ReleaseAssets.sha256(it) }
      files += FileResult(name, shaA, shaB)
      when {
        fileA == null -> differences += diff(name, Kind.ONLY_IN_B, "file present only in B")
        fileB == null -> differences += diff(name, Kind.ONLY_IN_A, "file present only in A")
        shaA != shaB -> differences += compareFiles(name, fileA, fileB)
      }
    }
    return Result(files, differences)
  }

  private fun diff(path: String, kind: Kind, detail: String) = Difference(path, kind, detail, likelyCause(kind, path))

  private fun compareFiles(name: String, a: File, b: File): List<Difference> =
    try {
      when (ArchiveKind.of(name)) {
        ArchiveKind.ZIP ->
          ByteSource.OfFile(a).use { sa ->
            ByteSource.OfFile(b).use { sb ->
              val za = ZipArchive(sa)
              val zb = ZipArchive(sb)
              compareEntries(name, za.entries, zb.entries, { za.content(it) }, { zb.content(it) }, 1)
            }
          }
        ArchiveKind.TAR -> compareTars(name, { a.inputStream() }, { b.inputStream() }, 1)
        ArchiveKind.GZIP_TAR ->
          compareTars(name, { GZIPInputStream(a.inputStream(), 64 * 1024) }, { GZIPInputStream(b.inputStream(), 64 * 1024) }, 1)
        ArchiveKind.NONE -> listOf(diff(name, Kind.CONTENT, "sha256 ${short(a)} vs ${short(b)}"))
      }
    } catch (e: Exception) {
      listOf(diff(name, Kind.CONTENT, "sha256 differs; entries not readable: ${e.message}"))
    }

  private fun short(file: File) = ReleaseAssets.sha256(file).take(12)

  private fun compareTars(path: String, openA: () -> InputStream, openB: () -> InputStream, depth: Int): List<Difference> {
    val ea = TarArchive.entries(openA)
    val eb = TarArchive.entries(openB)
    return compareEntries(path, ea, eb, { TarArchive.content(openA, it) }, { TarArchive.content(openB, it) }, depth)
  }

  /** Name keys that stay unique when an archive holds the same name twice ("name", "name#2"). */
  private fun keyed(entries: List<ArchiveEntry>): LinkedHashMap<String, ArchiveEntry> {
    val counts = HashMap<String, Int>()
    val result = LinkedHashMap<String, ArchiveEntry>()
    for (entry in entries) {
      val n = (counts[entry.name] ?: 0) + 1
      counts[entry.name] = n
      result[if (n == 1) entry.name else "${entry.name}#$n"] = entry
    }
    return result
  }

  private fun compareEntries(
    path: String,
    entriesA: List<ArchiveEntry>,
    entriesB: List<ArchiveEntry>,
    contentA: (Int) -> ByteArray,
    contentB: (Int) -> ByteArray,
    depth: Int,
  ): List<Difference> {
    val result = ArrayList<Difference>()
    val a = keyed(entriesA)
    val b = keyed(entriesB)
    for (key in a.keys) if (key !in b) result += diff("$path!/$key", Kind.ONLY_IN_A, "entry present only in A")
    for (key in b.keys) if (key !in a) result += diff("$path!/$key", Kind.ONLY_IN_B, "entry present only in B")
    val orderA = a.keys.filter { it in b }
    val orderB = b.keys.filter { it in a }
    if (orderA != orderB) {
      val at = orderA.indices.first { orderA[it] != orderB[it] }
      result += diff("$path!/", Kind.ORDER, "entry #${at + 1} of the common entries: A '${orderA[at]}', B '${orderB[at]}'")
    }
    for (key in orderA) {
      val x = a.getValue(key)
      val y = b.getValue(key)
      val entryPath = "$path!/$key"
      if (x.time != y.time) result += diff(entryPath, Kind.TIME, "A ${x.time}, B ${y.time}")
      if (x.mode != y.mode) result += diff(entryPath, Kind.MODE, "A ${x.mode}, B ${y.mode}")
      if (x.owner != y.owner) result += diff(entryPath, Kind.OWNER, "A ${x.owner}, B ${y.owner}")
      if (x.storage != y.storage) result += diff(entryPath, Kind.STORAGE, "A ${x.storage}, B ${y.storage}")
      if (x.sha256 != y.sha256) {
        result += nested(entryPath, x, y, contentA, contentB, depth)
      }
    }
    if (result.isEmpty()) {
      // Same entries, same metadata and content, different archive bytes: compression, comments,
      // extra fields or padding.
      result += diff(path, Kind.ARCHIVE_BYTES, "entries identical; archive bytes differ (compression, extra fields, comment)")
    }
    return result
  }

  private fun nested(
    entryPath: String,
    x: ArchiveEntry,
    y: ArchiveEntry,
    contentA: (Int) -> ByteArray,
    contentB: (Int) -> ByteArray,
    depth: Int,
  ): List<Difference> {
    val plain = diff(entryPath, Kind.CONTENT, "sha256 ${x.sha256.take(12)} (${x.size} B) vs ${y.sha256.take(12)} (${y.size} B)")
    if (depth >= MAX_DEPTH) return listOf(plain)
    val kind = ArchiveKind.of(x.name)
    return try {
      when {
        kind == ArchiveKind.ZIP -> {
          val za = ZipArchive(ByteSource.OfBytes(contentA(x.index)))
          val zb = ZipArchive(ByteSource.OfBytes(contentB(y.index)))
          compareEntries(entryPath, za.entries, zb.entries, { za.content(it) }, { zb.content(it) }, depth + 1)
        }
        kind == ArchiveKind.TAR -> {
          val ba = contentA(x.index)
          val bb = contentB(y.index)
          compareTars(entryPath, { ByteArrayInputStream(ba) }, { ByteArrayInputStream(bb) }, depth + 1)
        }
        // Image layers: <digest>.tar.gz in a Jib tar, extension-less blobs in an OCI layout.
        kind == ArchiveKind.GZIP_TAR || x.name.endsWith(".gz") || !x.name.substringAfterLast('/').contains('.') -> {
          val ba = contentA(x.index)
          val bb = contentB(y.index)
          if (kind == ArchiveKind.GZIP_TAR || (ArchiveKind.gunzipIsTar(ba) && ArchiveKind.gunzipIsTar(bb))) {
            compareTars(entryPath, { GZIPInputStream(ByteArrayInputStream(ba)) }, { GZIPInputStream(ByteArrayInputStream(bb)) }, depth + 1)
          } else listOf(plain)
        }
        else -> listOf(plain)
      }
    } catch (e: Exception) {
      listOf(plain)
    }
  }

  /**
   * Text report: verdict, the per-file hashes, then up to [limit] differences with their likely
   * cause and a count per cause.
   */
  fun render(result: Result, a: File, b: File, limit: Int = 200): String = buildString {
    if (result.identical) {
      appendLine("compare: IDENTICAL: ${result.files.size} file(s) with equal SHA-256 in A and B")
    } else {
      appendLine("compare: DIFFERENT: ${result.differences.size} difference(s) between A and B")
    }
    appendLine("  A = $a")
    appendLine("  B = $b")
    appendLine("files:")
    for (file in result.files) {
      val state =
        when {
          file.sha256A == null -> "only in B"
          file.sha256B == null -> "only in A"
          file.sha256A == file.sha256B -> "same"
          else -> "DIFFERENT"
        }
      appendLine("  ${(file.sha256A ?: file.sha256B ?: "").padEnd(64)}  ${state.padEnd(9)}  ${file.name}")
      if (state == "DIFFERENT") appendLine("  ${file.sha256B!!.padEnd(64)}  (B)        ${file.name}")
    }
    if (!result.identical) {
      appendLine("differences:")
      for (d in result.differences.take(limit)) {
        appendLine("  ${d.kind.label.padEnd(13)}  ${d.path}")
        appendLine("      ${d.detail}")
        appendLine("      likely cause: ${d.cause.text}")
      }
      if (result.differences.size > limit) appendLine("  ... and ${result.differences.size - limit} more")
      appendLine("by likely cause:")
      result.differences.groupingBy { it.cause }.eachCount().entries.sortedByDescending { it.value }.forEach {
        appendLine("  ${it.value.toString().padStart(6)}  ${it.key.text}")
      }
    }
  }
}
