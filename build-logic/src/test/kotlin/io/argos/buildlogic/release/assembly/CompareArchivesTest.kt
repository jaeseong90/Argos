// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.assembly

import io.argos.buildlogic.release.assembly.CompareArchives.Cause
import io.argos.buildlogic.release.assembly.CompareArchives.Kind
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Entry-level reproducibility comparison (T-BLD-009, 19 §3.7, 16 §10.4) behind
 * test/release/verify-reproducible.sh: fixtures for identical outputs, entry time, entry order,
 * entry content, mode and owner, compression, nested jars, image layers and Vite asset names.
 */
@Tag("req:SEC-73")
class CompareArchivesTest {

  @TempDir lateinit var work: File

  private val fixedTime = 315532800000L + 2000 // 1980-01-01T00:00:02Z, Gradle's fixed entry time

  private data class Z(val name: String, val content: ByteArray, val time: Long, val stored: Boolean = false)

  private fun zipBytes(entries: List<Z>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { out ->
      for (e in entries) {
        val entry = ZipEntry(e.name)
        entry.time = e.time
        if (e.stored) {
          entry.method = ZipEntry.STORED
          entry.size = e.content.size.toLong()
          entry.compressedSize = e.content.size.toLong()
          entry.crc = CRC32().apply { update(e.content) }.value
        }
        out.putNextEntry(entry)
        out.write(e.content)
        out.closeEntry()
      }
    }
    return bytes.toByteArray()
  }

  private data class T(val name: String, val content: ByteArray, val mtime: Long = 1, val mode: Int = 420, val uid: Int = 0)

  private fun tarBytes(entries: List<T>): ByteArray {
    val out = ByteArrayOutputStream()
    for (e in entries) {
      val header = ByteArray(512)
      fun put(at: Int, text: String) = text.toByteArray(Charsets.US_ASCII).copyInto(header, at)
      put(0, e.name)
      put(100, "%07o\u0000".format(e.mode))
      put(108, "%07o\u0000".format(e.uid))
      put(116, "%07o\u0000".format(e.uid))
      put(124, "%011o\u0000".format(e.content.size))
      put(136, "%011o\u0000".format(e.mtime))
      put(148, "        ")
      header[156] = '0'.code.toByte()
      put(257, "ustar\u000000")
      val sum = header.sumOf { it.toInt() and 0xff }
      put(148, "%06o\u0000 ".format(sum))
      out.write(header)
      out.write(e.content)
      out.write(ByteArray((512 - e.content.size % 512) % 512))
    }
    out.write(ByteArray(1024))
    return out.toByteArray()
  }

  private fun gzip(bytes: ByteArray): ByteArray =
    ByteArrayOutputStream().also { GZIPOutputStream(it).use { gz -> gz.write(bytes) } }.toByteArray()

  private fun dirs(name: String, a: ByteArray, b: ByteArray): Pair<File, File> {
    val da = File(work, "a").apply { mkdirs() }
    val db = File(work, "b").apply { mkdirs() }
    File(da, name).apply { parentFile.mkdirs() }.writeBytes(a)
    File(db, name).apply { parentFile.mkdirs() }.writeBytes(b)
    return da to db
  }

  private val agentEntries =
    listOf(
      Z("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n".toByteArray(), fixedTime),
      Z("io/argos/agent/bootstrap/A.class", byteArrayOf(-54, -2, -70, -66, 0, 0, 0, 52), fixedTime),
      Z("inst/io/argos/agent/core/B.classdata", byteArrayOf(1, 2, 3, 4), fixedTime),
    )

  @Test
  @DisplayName("T-BLD-009a identical outputs: same files and hashes, IDENTICAL with the per-file SHA-256")
  fun identical() {
    val jar = zipBytes(agentEntries)
    val (a, b) = dirs("argos-agent-0.1.0.jar", jar, jar)
    File(a, "SHA256SUMS").writeText("x  argos-agent-0.1.0.jar\n")
    File(b, "SHA256SUMS").writeText("x  argos-agent-0.1.0.jar\n")
    val result = CompareArchives.compareDirectories(a, b)
    assertThat(result.identical).isTrue()
    assertThat(result.files.map { it.name }).containsExactly("SHA256SUMS", "argos-agent-0.1.0.jar")
    assertThat(result.files).allSatisfy { assertThat(it.sha256A).isEqualTo(it.sha256B).hasSize(64) }
    assertThat(CompareArchives.render(result, a, b)).startsWith("compare: IDENTICAL: 2 file(s)")
  }

  @Test
  @DisplayName("T-BLD-009a entry time differs: TIME on that entry only, cause timestamps")
  fun entryTimeDiffers() {
    val other = agentEntries.toMutableList()
    other[1] = other[1].copy(time = fixedTime + 3_600_000)
    val (a, b) = dirs("argos-agent-0.1.0.jar", zipBytes(agentEntries), zipBytes(other))
    val result = CompareArchives.compareDirectories(a, b)
    assertThat(result.identical).isFalse()
    assertThat(result.differences).singleElement().satisfies({ d ->
      assertThat(d.kind).isEqualTo(Kind.TIME)
      assertThat(d.path).isEqualTo("argos-agent-0.1.0.jar!/io/argos/agent/bootstrap/A.class")
      assertThat(d.cause).isEqualTo(Cause.TIMESTAMPS)
    })
    val report = CompareArchives.render(result, a, b)
    assertThat(report).startsWith("compare: DIFFERENT: 1 difference(s)").contains("likely cause: timestamps")
  }

  @Test
  @DisplayName("T-BLD-009a entry order differs: ORDER with the first differing position, cause entry order")
  fun entryOrderDiffers() {
    val (a, b) = dirs("argos-agent-0.1.0.jar", zipBytes(agentEntries), zipBytes(agentEntries.reversed()))
    val result = CompareArchives.compareDirectories(a, b)
    assertThat(result.differences).singleElement().satisfies({ d ->
      assertThat(d.kind).isEqualTo(Kind.ORDER)
      assertThat(d.detail).contains("entry #1").contains("META-INF/MANIFEST.MF").contains("B.classdata")
      assertThat(d.cause).isEqualTo(Cause.ORDER)
    })
  }

  @Test
  @DisplayName("T-BLD-009a entry content differs: CONTENT on the class (compiled code) and the manifest (build metadata)")
  fun entryContentDiffers() {
    val other = agentEntries.toMutableList()
    other[0] = other[0].copy(content = "Manifest-Version: 1.0\r\nBuild-Jdk: 21\r\n".toByteArray())
    other[2] = other[2].copy(content = byteArrayOf(1, 2, 3, 5))
    val (a, b) = dirs("argos-agent-0.1.0.jar", zipBytes(agentEntries), zipBytes(other))
    val byPath = CompareArchives.compareDirectories(a, b).differences.associateBy { it.path }
    assertThat(byPath).hasSize(2)
    assertThat(byPath.getValue("argos-agent-0.1.0.jar!/META-INF/MANIFEST.MF").cause).isEqualTo(Cause.BUILD_METADATA)
    val classdata = byPath.getValue("argos-agent-0.1.0.jar!/inst/io/argos/agent/core/B.classdata")
    assertThat(classdata.kind).isEqualTo(Kind.CONTENT)
    assertThat(classdata.cause).isEqualTo(Cause.COMPILED_CODE)
  }

  @Test
  @DisplayName("T-BLD-009a same content stored vs deflated: STORAGE, cause compression")
  fun compressionDiffers() {
    val stored = agentEntries.map { it.copy(stored = true) }
    val (a, b) = dirs("argos-agent-0.1.0.jar", zipBytes(agentEntries), zipBytes(stored))
    val differences = CompareArchives.compareDirectories(a, b).differences
    assertThat(differences).hasSize(3).allSatisfy {
      assertThat(it.kind).isEqualTo(Kind.STORAGE)
      assertThat(it.cause).isEqualTo(Cause.COMPRESSION)
    }
  }

  @Test
  @DisplayName("T-BLD-009a nested jar in the boot jar: the difference is reported inside BOOT-INF/lib/*.jar")
  fun nestedJar() {
    val inner = zipBytes(listOf(Z("a/B.class", byteArrayOf(1), fixedTime)))
    val innerChanged = zipBytes(listOf(Z("a/B.class", byteArrayOf(2), fixedTime)))
    val outer = { lib: ByteArray ->
      zipBytes(
        listOf(
          Z("BOOT-INF/lib/argos-proto-0.1.0.jar", lib, fixedTime, stored = true),
          Z("BOOT-INF/classes/static/assets/index-AbCdEf12.js", "x".toByteArray(), fixedTime),
        )
      )
    }
    val (a, b) = dirs("argos-server-0.1.0.jar", outer(inner), outer(innerChanged))
    val result = CompareArchives.compareDirectories(a, b)
    assertThat(result.differences).singleElement().satisfies({ d ->
      assertThat(d.path).isEqualTo("argos-server-0.1.0.jar!/BOOT-INF/lib/argos-proto-0.1.0.jar!/a/B.class")
      assertThat(d.kind).isEqualTo(Kind.CONTENT)
      assertThat(d.cause).isEqualTo(Cause.COMPILED_CODE)
    })
  }

  @Test
  @DisplayName("T-BLD-009a Vite hashed asset names differ: entries only in A and only in B, cause Vite hashes")
  fun viteHashes() {
    val jar = { hash: String ->
      zipBytes(
        listOf(
          Z("BOOT-INF/classes/static/index.html", "<script src=\"/assets/index-$hash.js\">".toByteArray(), fixedTime),
          Z("BOOT-INF/classes/static/assets/index-$hash.js", "code".toByteArray(), fixedTime),
        )
      )
    }
    val (a, b) = dirs("argos-server-0.1.0.jar", jar("AbCdEf12"), jar("Zz9_Yx-8"))
    val differences = CompareArchives.compareDirectories(a, b).differences
    assertThat(differences.map { it.kind }).contains(Kind.ONLY_IN_A, Kind.ONLY_IN_B, Kind.CONTENT)
    assertThat(differences).allSatisfy { assertThat(it.cause).isEqualTo(Cause.VITE_HASHES) }
  }

  @Test
  @DisplayName("T-BLD-009a image tar: layer mtime, mode and owner differences inside a gzip layer; image digest file")
  fun imageTar() {
    val layer = { mtime: Long, mode: Int, uid: Int ->
      gzip(tarBytes(listOf(T("opt/argos/app/libs/x.jar", byteArrayOf(9), mtime = mtime, mode = mode, uid = uid))))
    }
    val image = { l: ByteArray ->
      tarBytes(
        listOf(
          T("config.json", "{\"architecture\":\"amd64\"}".toByteArray()),
          T("0123abcd.tar.gz", l),
          T("manifest.json", "[]".toByteArray()),
        )
      )
    }
    val (a, b) = dirs("images/argos-server.tar", image(layer(1, 420, 0)), image(layer(2, 493, 10001)))
    File(a, "images/argos-server.digest").writeText("sha256:aaaa")
    File(b, "images/argos-server.digest").writeText("sha256:bbbb")
    val result = CompareArchives.compareDirectories(a, b)
    val inLayer = "images/argos-server.tar!/0123abcd.tar.gz!/opt/argos/app/libs/x.jar"
    assertThat(result.differences.map { it.path to it.kind })
      .containsExactlyInAnyOrder(
        "images/argos-server.digest" to Kind.CONTENT,
        inLayer to Kind.TIME,
        inLayer to Kind.MODE,
        inLayer to Kind.OWNER,
      )
    assertThat(result.differences.single { it.path == "images/argos-server.digest" }.cause).isEqualTo(Cause.IMAGE_METADATA)
    assertThat(result.differences.single { it.kind == Kind.MODE }.cause).isEqualTo(Cause.PERMISSIONS)
    assertThat(result.differences.single { it.kind == Kind.MODE }.detail).contains("0644").contains("0755")
  }

  @Test
  @DisplayName("T-BLD-009a file set differs: a file only one output has; SBOM content difference names SBOM metadata")
  fun fileSetAndSbom() {
    val (a, b) = dirs("argos-agent-0.1.0.cdx.json", "{\"t\":1}".toByteArray(), "{\"t\":2}".toByteArray())
    File(a, "argos-server-0.1.0.jar").writeBytes(zipBytes(agentEntries))
    val byPath = CompareArchives.compareDirectories(a, b).differences.associateBy { it.path }
    assertThat(byPath.getValue("argos-server-0.1.0.jar").kind).isEqualTo(Kind.ONLY_IN_A)
    assertThat(byPath.getValue("argos-server-0.1.0.jar").cause).isEqualTo(Cause.FILE_SET)
    assertThat(byPath.getValue("argos-agent-0.1.0.cdx.json").cause).isEqualTo(Cause.SBOM_METADATA)
  }

  @Test
  @DisplayName("T-BLD-009a zip64 jar (more than 65,535 entries) is read entry by entry")
  fun zip64() {
    val entries = (0 until 70_000).map { Z("e/$it", byteArrayOf((it % 7).toByte()), fixedTime, stored = true) }
    val changed = entries.toMutableList().apply { this[69_999] = this[69_999].copy(content = byteArrayOf(99)) }
    val (a, b) = dirs("big.jar", zipBytes(entries), zipBytes(changed))
    assertThat(CompareArchives.compareDirectories(a, b).differences.map { it.path }).containsExactly("big.jar!/e/69999")
  }
}
