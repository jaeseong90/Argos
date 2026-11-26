// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.assembly

import java.io.File
import java.security.MessageDigest

/**
 * The release assets of 19 §7.2 (WO-44) that the root task `releaseArtifacts` collects into
 * `build/release/`: the agent and server jars and the three CycloneDX SBOMs (agent, server, UI),
 * each under its versioned release name, plus `SHA256SUMS`.
 *
 * Parts (`-Pargos.release.parts`, default [ALL_PARTS]): `agent` (agent jar and SBOM), `server`
 * (server jar and SBOM) and `ui` (UI SBOM; the UI bundle itself ships inside the server jar). A
 * published release always has all three; the reduced sets exist for local reproducibility runs
 * (test/release/verify-reproducible.sh --agent-only, --ui-skip).
 */
object ReleaseAssets {

  const val AGENT: String = "agent"
  const val SERVER: String = "server"
  const val UI: String = "ui"

  /** Every part, in asset order. */
  val ALL_PARTS: List<String> = listOf(AGENT, SERVER, UI)

  /** Gradle property that selects the parts (comma-separated). */
  const val PARTS_PROPERTY: String = "argos.release.parts"

  /** Name of the checksum file (`sha256sum -c SHA256SUMS`, 16 §10.3). */
  const val SHA256SUMS: String = "SHA256SUMS"

  /** One asset: its release file name, its source file and the task that builds the source. */
  data class Asset(val name: String, val part: String, val sourcePath: String, val builtBy: String)

  /**
   * Parses `agent,server,ui` (any order, no duplicates, at least one) into parts in [ALL_PARTS]
   * order.
   */
  fun parseParts(spec: String?): List<String> {
    if (spec == null || spec.isBlank()) return ALL_PARTS
    val requested = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val unknown = requested.filterNot { it in ALL_PARTS }
    require(unknown.isEmpty()) {
      "$PARTS_PROPERTY: unknown part(s) ${unknown.joinToString(", ")}; expected a comma-separated " +
        "subset of ${ALL_PARTS.joinToString(",")}"
    }
    require(requested.size == requested.toSet().size) { "$PARTS_PROPERTY: duplicate part in '$spec'" }
    require(requested.isNotEmpty()) { "$PARTS_PROPERTY: no part selected" }
    return ALL_PARTS.filter { it in requested }
  }

  /**
   * The assets of [parts] for [version], with their sources relative to the repository root (the
   * contract names of the WO-44 plan, section 2).
   */
  fun assets(version: String, parts: List<String>): List<Asset> {
    require(version.isNotBlank()) { "version is empty" }
    val all =
      listOf(
        Asset("argos-agent-$version.jar", AGENT, "agent/agent-dist/build/libs/argos-agent-$version.jar", ":agent:agent-dist:agentJar"),
        Asset("argos-server-$version.jar", SERVER, "server/build/libs/argos-server-$version.jar", ":server:bootJar"),
        Asset("argos-agent-$version.cdx.json", AGENT, "agent/agent-dist/build/sbom/agent.cdx.json", ":agent:agent-dist:agentSbom"),
        Asset("argos-server-$version.cdx.json", SERVER, "server/build/sbom/server.cdx.json", ":server:serverSbom"),
        Asset("argos-ui-$version.cdx.json", UI, "ui/build/sbom/ui.cdx.json", ":ui:pnpmBuild"),
      )
    return all.filter { it.part in parts }
  }

  /** Lower-case hex SHA-256 of [file]. */
  fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /**
   * `SHA256SUMS` content for [files] (name to file): one `<sha256>  <name>` line per file (two
   * spaces, text mode, as `sha256sum` writes and `sha256sum -c` reads), sorted by name in byte
   * order, LF line ends.
   */
  fun sha256Sums(files: Map<String, File>): String {
    files.keys.forEach { name ->
      require(name.isNotEmpty() && '/' !in name && '\\' !in name && '\n' !in name) {
        "invalid asset name '$name'"
      }
    }
    return files.entries
      .sortedWith(compareBy(Comparators.BYTES) { it.key })
      .joinToString("") { (name, file) -> "${sha256(file)}  $name\n" }
  }

  /**
   * Replaces the content of [target] with the [assets] (release name to source file) and their
   * `SHA256SUMS`. Fails, naming every missing source and the task that builds it, before touching
   * [target].
   */
  fun assemble(target: File, assets: List<Pair<String, File>>, builtBy: Map<String, String> = emptyMap()) {
    require(assets.isNotEmpty()) { "no release asset selected" }
    val names = assets.map { it.first }
    require(names.size == names.toSet().size) { "duplicate release asset name in $names" }
    require(SHA256SUMS !in names) { "$SHA256SUMS is written by the assembly itself" }
    val missing = assets.filterNot { it.second.isFile }
    if (missing.isNotEmpty()) {
      throw IllegalStateException(
        "Missing release inputs:\n" +
          missing.joinToString("\n") { (name, file) ->
            "  $name <- $file" + (builtBy[name]?.let { " (built by $it)" } ?: "")
          }
      )
    }
    if (target.exists()) target.deleteRecursively()
    check(target.mkdirs() || target.isDirectory) { "cannot create $target" }
    val copied = LinkedHashMap<String, File>()
    for ((name, source) in assets) {
      val destination = File(target, name)
      source.copyTo(destination, overwrite = true)
      copied[name] = destination
    }
    File(target, SHA256SUMS).writeText(sha256Sums(copied), Charsets.UTF_8)
  }

  /** Byte-order (C locale) comparison of UTF-8 names, as `LC_ALL=C sort`. */
  internal object Comparators {
    val BYTES: Comparator<String> = Comparator { a, b ->
      val x = a.toByteArray(Charsets.UTF_8)
      val y = b.toByteArray(Charsets.UTF_8)
      val n = minOf(x.size, y.size)
      for (i in 0 until n) {
        val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
        if (c != 0) return@Comparator c
      }
      x.size - y.size
    }
  }
}
