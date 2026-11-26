// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.assembly

import io.argos.buildlogic.BashLocator
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * releaseArtifacts (argos.release, 19 §7.2): release names of the assets, SHA256SUMS in the format
 * `sha256sum -c` and deploy/verify-release.sh read (16 §10.3).
 */
@Tag("req:SEC-72")
class ReleaseAssetsTest {

  @TempDir lateinit var work: File

  @Test
  @DisplayName("T-BLD-023 release assets: the five contract names for X.Y.Z and their source tasks")
  fun contractNames() {
    val assets = ReleaseAssets.assets("0.1.0", ReleaseAssets.ALL_PARTS)
    assertThat(assets.map { it.name })
      .containsExactly(
        "argos-agent-0.1.0.jar",
        "argos-server-0.1.0.jar",
        "argos-agent-0.1.0.cdx.json",
        "argos-server-0.1.0.cdx.json",
        "argos-ui-0.1.0.cdx.json",
      )
    assertThat(assets.associate { it.name to it.builtBy })
      .containsEntry("argos-agent-0.1.0.jar", ":agent:agent-dist:agentJar")
      .containsEntry("argos-server-0.1.0.jar", ":server:bootJar")
      .containsEntry("argos-agent-0.1.0.cdx.json", ":agent:agent-dist:agentSbom")
      .containsEntry("argos-server-0.1.0.cdx.json", ":server:serverSbom")
      .containsEntry("argos-ui-0.1.0.cdx.json", ":ui:pnpmBuild")
    assertThat(assets.single { it.name == "argos-ui-0.1.0.cdx.json" }.sourcePath).isEqualTo("ui/build/sbom/ui.cdx.json")
    assertThat(ReleaseAssets.assets("0.1.0", listOf("agent")).map { it.name })
      .containsExactly("argos-agent-0.1.0.jar", "argos-agent-0.1.0.cdx.json")
  }

  @Test
  @DisplayName("T-BLD-023 release parts: default all, subsets in fixed order, unknown and duplicate parts rejected")
  fun parts() {
    assertThat(ReleaseAssets.parseParts(null)).containsExactly("agent", "server", "ui")
    assertThat(ReleaseAssets.parseParts(" ")).containsExactly("agent", "server", "ui")
    assertThat(ReleaseAssets.parseParts("server, agent")).containsExactly("agent", "server")
    assertThatThrownBy { ReleaseAssets.parseParts("agent,docs") }.hasMessageContaining("docs")
    assertThatThrownBy { ReleaseAssets.parseParts("agent,agent") }.hasMessageContaining("duplicate")
    assertThatThrownBy { ReleaseAssets.parseParts(",") }.hasMessageContaining("no part")
  }

  @Test
  @DisplayName("T-BLD-023 SHA256SUMS: byte-ordered names, two spaces, LF, accepted by sha256sum -c")
  fun sha256SumsVerifies() {
    val sources = File(work, "src").apply { mkdirs() }
    val agent = File(sources, "agent.jar").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val sbom = File(sources, "agent.cdx.json").apply { writeText("{\"bomFormat\":\"CycloneDX\"}\n") }
    val server = File(sources, "server.jar").apply { writeBytes(ByteArray(70_000) { it.toByte() }) }
    val target = File(work, "release")
    File(target, "stale.txt").apply {
      parentFile.mkdirs()
      writeText("left over")
    }
    ReleaseAssets.assemble(
      target,
      listOf(
        "argos-server-0.1.0.jar" to server,
        "argos-agent-0.1.0.jar" to agent,
        "argos-agent-0.1.0.cdx.json" to sbom,
      ),
    )
    assertThat(target.list()!!.sorted())
      .containsExactly("SHA256SUMS", "argos-agent-0.1.0.cdx.json", "argos-agent-0.1.0.jar", "argos-server-0.1.0.jar")
    val sums = File(target, "SHA256SUMS").readBytes().toString(Charsets.UTF_8)
    assertThat(sums).doesNotContain("\r").endsWith("\n")
    val lines = sums.trimEnd('\n').split('\n')
    assertThat(lines.map { it.substringAfter("  ") })
      .containsExactly("argos-agent-0.1.0.cdx.json", "argos-agent-0.1.0.jar", "argos-server-0.1.0.jar")
    lines.forEach { assertThat(it).matches("[0-9a-f]{64}  [A-Za-z0-9.-]+") }
    assertThat(lines[1]).startsWith("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81  ")

    // The real tool: sha256sum -c in the release directory (Git Bash on Windows).
    val check = bash("cd '${target.invariantSeparatorsPath}' && sha256sum -c SHA256SUMS")
    assertThat(check.second).`as`(check.first).isEqualTo(0)
    assertThat(check.first).contains("argos-agent-0.1.0.jar: OK")

    // Tampering is detected by the same command.
    File(target, "argos-agent-0.1.0.jar").writeBytes(byteArrayOf(1, 2, 4))
    assertThat(bash("cd '${target.invariantSeparatorsPath}' && sha256sum -c SHA256SUMS").second).isNotEqualTo(0)
  }

  @Test
  @DisplayName("T-BLD-023 missing input: every missing source and the task that builds it, output untouched")
  fun missingInput() {
    val target = File(work, "release").apply { mkdirs() }
    val marker = File(target, "previous.txt").apply { writeText("previous") }
    val present = File(work, "a.jar").apply { writeText("a") }
    assertThatThrownBy {
        ReleaseAssets.assemble(
          target,
          listOf("argos-agent-1.0.0.jar" to present, "argos-ui-1.0.0.cdx.json" to File(work, "missing.json")),
          mapOf("argos-ui-1.0.0.cdx.json" to ":ui:pnpmBuild"),
        )
      }
      .hasMessageContaining("argos-ui-1.0.0.cdx.json")
      .hasMessageContaining(":ui:pnpmBuild")
    assertThat(marker).exists()
    assertThatThrownBy { ReleaseAssets.assemble(target, listOf("SHA256SUMS" to present)) }
      .hasMessageContaining("SHA256SUMS")
  }

  private fun bash(command: String): Pair<String, Int> {
    val process =
      ProcessBuilder(BashLocator.find(), "-c", command)
        .redirectErrorStream(true)
        .directory(Files.createTempDirectory(work.toPath(), "sh").toFile())
        .start()
    process.outputStream.close()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(60, TimeUnit.SECONDS))
    return output to process.exitValue()
  }
}
