// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.notices

import io.argos.buildlogic.ScriptResult
import io.argos.buildlogic.repositoryRoot
import io.argos.buildlogic.runScript
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * test/lint/check-notice.sh (T-BLD-012, O-L05, WO-44): third-party source code in the repository
 * (a foreign copyright header or SPDX identifier, a borrowed-code marker) is listed with its
 * source in NOTICE, every listed path exists and still carries its original header.
 *
 * The markers are assembled at run time so that this file itself is not third-party code for the
 * lint.
 */
@Tag("req:O-L05")
class CheckNoticeScriptTest {
  private val script = File(repositoryRoot, "test/lint/check-notice.sh")

  @TempDir lateinit var root: File

  private val copyright = "Copy" + "right"
  private val adapted = "Adapt" + "ed from"
  private val ported = "Port" + "ed from"
  private val heading = "Third-party source code adapted in this repository"

  private fun write(path: String, content: String) =
    File(root, path).apply {
      parentFile.mkdirs()
      writeText(content)
    }

  private fun notice(section: String) =
    write(
      "NOTICE",
      "Argos APM\n$copyright 2026 Argos APM contributors\n\n$heading\n${"-".repeat(50)}\n\n$section\n\n" +
        "Third-party data derived in this repository\n${"-".repeat(43)}\n\nlisted/elsewhere.txt is data.\n",
    )

  private fun argosSources() {
    write("src/main/java/a/A.java", "/* SPDX-License-Identifier: Apache-2.0 */\npackage a;\n")
    write("ui/src/app.ts", "// SPDX-License-Identifier: Apache-2.0\n// $copyright 2026 Argos APM contributors\n")
    // Prose that is not a marker: an internal port and a copyright phrase without a year.
    write(
      "server/src/main/java/s/Port.java",
      "/* SPDX-License-Identifier: Apache-2.0 */\n/** Ported from {@code io.argos.storage.Foo}; the $copyright line rule. */\n",
    )
    // A string literal with another identifier is not a header.
    write("ui/src/spdx.test.ts", "// SPDX-License-Identifier: Apache-2.0\nconst code = '// SPDX-License-Identifier: MIT';\n")
    write("README.md", "$copyright 2015 Example Authors (not a source file)\n")
  }

  private fun run(): ScriptResult = runScript(script, listOf(root.invariantSeparatorsPath))

  @Test
  @DisplayName("T-BLD-012 passes when no file is third-party and NOTICE says None.")
  fun noneAndNoThirdPartyCode() {
    argosSources()
    notice("None.")
    val result = run()
    assertThat(result.exitCode).`as`(result.output).isEqualTo(0)
    assertThat(result.output).contains("check-notice: OK (4 files, find, 0 third-party)")
  }

  @Test
  @DisplayName(
    "T-BLD-012 fails for a foreign copyright header, another SPDX identifier and borrowed-code markers missing from NOTICE"
  )
  fun unlistedThirdPartyCodeFails() {
    argosSources()
    notice("None.")
    write("ui/src/lib/foo.ts", "// $copyright (c) 2019 Foo Authors\n// SPDX-License-Identifier: MIT\nexport {};\n")
    write("deploy/x.sh", "#!/bin/sh\n# SPDX-License-Identifier: BSD-3-Clause\necho\n")
    write(
      "server/src/main/java/s/Lru.java",
      "/* SPDX-License-Identifier: Apache-2.0 */\npackage s;\n" + "\n".repeat(40) + "// $adapted the LRU of project X\n",
    )
    write(
      "agent/src/main/java/b/B.java",
      "/* SPDX-License-Identifier: Apache-2.0 */\n// $ported https://github.com/example/b/blob/v1/B.java\n",
    )
    val result = run()
    assertThat(result.exitCode).`as`(result.output).isEqualTo(1)
    assertThat(result.output)
      .contains("ui/src/lib/foo.ts: third-party code (copyright header: $copyright (c) 2019 Foo Authors)")
      .contains("deploy/x.sh: third-party code (SPDX-License-Identifier: BSD-3-Clause)")
      .contains("server/src/main/java/s/Lru.java: third-party code (marker: $adapted the LRU of project X)")
      .contains("agent/src/main/java/b/B.java: third-party code (marker: $ported https://github.com/example/b")
      .contains("check-notice: 4 problem(s) (8 files, find, 4 third-party)")
  }

  @Test
  @DisplayName("T-BLD-012 passes when every third-party file is listed, as a file or below a listed directory")
  fun listedThirdPartyCodePasses() {
    argosSources()
    write("ui/src/lib/foo.ts", "// $copyright (c) 2019 Foo Authors\n// SPDX-License-Identifier: MIT\nexport {};\n")
    write("third_party/bar/a.js", "// $copyright 2020 Bar Authors\n")
    write("third_party/bar/b.js", "// SPDX-License-Identifier: ISC\n")
    notice(
      "ui/src/lib/foo.ts  $adapted https://github.com/example/foo v1.2 (MIT)\n" +
        "  continued description of the entry\n" +
        "third_party/bar/  $ported https://github.com/example/bar v3 (ISC)"
    )
    val result = run()
    assertThat(result.exitCode).`as`(result.output).isEqualTo(0)
    assertThat(result.output).contains("check-notice: OK (7 files, find, 3 third-party)")
  }

  @Test
  @DisplayName(
    "T-BLD-012 fails for a listed path that does not exist, a listed file without its original header, and None. with entries"
  )
  fun staleOrInconsistentNoticeFails() {
    argosSources()
    notice("None.\nui/src/gone.ts  $adapted https://example.org/gone (MIT)\nsrc/main/java/a/A.java  from somewhere (MIT)\nlib/  x")
    val result = run()
    assertThat(result.exitCode).`as`(result.output).isEqualTo(1)
    assertThat(result.output)
      .contains("NOTICE: \"$heading\" says \"None.\" and lists paths")
      .contains("NOTICE: listed file ui/src/gone.ts does not exist")
      .contains("NOTICE: listed directory lib/ does not exist")
      .contains(
        "src/main/java/a/A.java: listed in NOTICE but carries no original copyright header or borrowed-code marker (O-L05)"
      )
  }

  @Test
  @DisplayName("T-BLD-012 fails without the NOTICE section and exits 2 on a usage error")
  fun missingSectionAndUsage() {
    argosSources()
    write("NOTICE", "Argos APM\n")
    val result = run()
    assertThat(result.exitCode).`as`(result.output).isEqualTo(1)
    assertThat(result.output).contains("NOTICE: no section \"$heading\"")
    val usage = runScript(script, listOf(root.invariantSeparatorsPath, "extra"))
    assertThat(usage.exitCode).`as`(usage.output).isEqualTo(2)
  }

  @Test
  @DisplayName("T-BLD-012 the repository itself passes (NOTICE: None.)")
  fun repositoryPasses() {
    val result = runScript(script, listOf(repositoryRoot.invariantSeparatorsPath))
    assertThat(result.exitCode).`as`(result.output).isEqualTo(0)
    assertThat(result.output).contains("check-notice: OK (").contains(", 0 third-party)")
  }
}
