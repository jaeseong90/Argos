// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import java.io.File
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** test/lint/check-spdx.sh (19 §5.1, T-BLD-007): SPDX header in every source file. */
@Tag("req:O-L07")
class CheckSpdxScriptTest {
  private val script = File(repositoryRoot, "test/lint/check-spdx.sh")

  @TempDir lateinit var root: File

  private fun write(path: String, content: String) =
    File(root, path).apply {
      parentFile.mkdirs()
      writeText(content)
    }

  private fun writeCompliantSources() {
    write("src/main/java/a/A.java", "/* SPDX-License-Identifier: Apache-2.0 */\npackage a;\n")
    write("ui/src/app.tsx", "// SPDX-License-Identifier: Apache-2.0\nexport const a = 1;\n")
    write("ui/vite.config.mjs", "// SPDX-License-Identifier: Apache-2.0\nexport default {};\n")
    write("proto/argos/v1/a.proto", "// SPDX-License-Identifier: Apache-2.0\nsyntax = \"proto3\";\n")
    write("deploy/schema/001.sql", "-- SPDX-License-Identifier: Apache-2.0\nSELECT 1;\n")
    write("scripts/run.sh", "#!/usr/bin/env bash\n# SPDX-License-Identifier: Apache-2.0\necho\n")
    write("build.gradle.kts", "// SPDX-License-Identifier: Apache-2.0\n")
    write("README.md", "no header needed\n")
  }

  private fun run(): ScriptResult = runScript(script, listOf(root.invariantSeparatorsPath))

  @Test
  @DisplayName("T-BLD-007 accepts Java, TS, JS, proto, SQL, shell and Kotlin files with the header")
  fun acceptsCompliantTree() {
    writeCompliantSources()
    val result = run()
    assertThat(result.exitCode).`as`(result.output).isEqualTo(0)
    assertThat(result.output).contains("check-spdx: OK (7 files, find, 0 excluded)")
  }

  @Test
  @DisplayName("T-BLD-007 rejects missing, wrong, late (after line 5) and empty headers")
  fun rejectsMissingHeaders() {
    writeCompliantSources()
    write("src/main/java/a/NoHeader.java", "package a;\nclass NoHeader {}\n")
    write("deploy/schema/002.sql", "-- SPDX-License-Identifier: MIT\nSELECT 2;\n")
    write("ui/src/late.ts", "\n\n\n\n\n// SPDX-License-Identifier: Apache-2.0\n")
    write("scripts/empty.sh", "")
    val result = run()
    assertThat(result.exitCode).`as`(result.output).isEqualTo(1)
    assertThat(result.output)
      .contains("src/main/java/a/NoHeader.java  (no SPDX-License-Identifier in the first 5 lines)")
      .contains("deploy/schema/002.sql  (SPDX-License-Identifier: MIT, expected Apache-2.0)")
      .contains("ui/src/late.ts  (no SPDX-License-Identifier in the first 5 lines)")
      .contains("scripts/empty.sh  (empty file)")
      .contains("check-spdx: 4 of 11 files failed")
  }

  @Test
  @DisplayName("T-BLD-007 rejects compound expressions (AND, WITH, OR); accepts /* */, <!-- --> and CRLF")
  fun comparesTheWholeExpression() {
    write("scripts/and.sh", "#!/bin/sh\n# SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only\n")
    write("deploy/schema/with.sql", "-- SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception\n")
    write("build.gradle.kts", "// SPDX-License-Identifier: Apache-2.0 OR MIT\n")
    write("src/main/java/a/Block.java", "/* SPDX-License-Identifier: Apache-2.0 AND MIT */\npackage a;\n")
    write("ok/A.java", "/* SPDX-License-Identifier: Apache-2.0 */\npackage ok;\n")
    write("ok/index.html", "<!doctype html>\n<!-- SPDX-License-Identifier: Apache-2.0 -->\n<html></html>\n")
    write("ok/crlf.sh", "#!/bin/sh\r\n# SPDX-License-Identifier: Apache-2.0  \r\necho\r\n")
    val result = run()
    assertThat(result.exitCode).`as`(result.output).isEqualTo(1)
    assertThat(result.output)
      .contains("scripts/and.sh  (SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only, expected Apache-2.0)")
      .contains("deploy/schema/with.sql  (SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception, expected")
      .contains("build.gradle.kts  (SPDX-License-Identifier: Apache-2.0 OR MIT, expected")
      .contains("src/main/java/a/Block.java  (SPDX-License-Identifier: Apache-2.0 AND MIT, expected")
      .doesNotContain("ok/")
      .contains("check-spdx: 4 of 7 files failed")
  }

  @Test
  @DisplayName("T-BLD-007 accepts YAML, TOML, properties, CSS, HTML, Python, PowerShell and batch files with the header")
  fun acceptsConfigAndMarkupFormats() {
    write(".github/workflows/ci.yml", "# SPDX-License-Identifier: Apache-2.0\nname: ci\n")
    write("config/app.yaml", "# SPDX-License-Identifier: Apache-2.0\na: 1\n")
    write("gradle/libs.versions.toml", "# SPDX-License-Identifier: Apache-2.0\n[versions]\n")
    write("gradle.properties", "# SPDX-License-Identifier: Apache-2.0\na=1\n")
    write("ui/src/app.css", "/* SPDX-License-Identifier: Apache-2.0 */\nbody {}\n")
    write("ui/index.html", "<!doctype html>\n<!-- SPDX-License-Identifier: Apache-2.0 -->\n<html></html>\n")
    write("tools/a.py", "#!/usr/bin/env python3\n# SPDX-License-Identifier: Apache-2.0\n")
    write("tools/a.ps1", "# SPDX-License-Identifier: Apache-2.0\nWrite-Output 1\n")
    write("tools/a.bat", "@echo off\r\nREM SPDX-License-Identifier: Apache-2.0\r\n")
    write("tools/b.cmd", ":: SPDX-License-Identifier: Apache-2.0\r\n@echo off\r\n")
    val result = run()
    assertThat(result.exitCode).`as`(result.output).isEqualTo(0)
    assertThat(result.output).contains("check-spdx: OK (10 files, find, 0 excluded)")
  }

  @Test
  @DisplayName("T-BLD-007 rejects YAML, CSS, HTML and properties files without the header")
  fun rejectsHeaderlessConfigAndMarkup() {
    write(".github/workflows/ci.yml", "name: ci\n")
    write("ui/src/app.css", "body {}\n")
    write("ui/index.html", "<!doctype html>\n<html></html>\n")
    write("gradle.properties", "a=1\n")
    val result = run()
    assertThat(result.exitCode).`as`(result.output).isEqualTo(1)
    assertThat(result.output)
      .contains(".github/workflows/ci.yml  (no SPDX-License-Identifier in the first 5 lines)")
      .contains("ui/src/app.css  (no SPDX-License-Identifier in the first 5 lines)")
      .contains("ui/index.html  (no SPDX-License-Identifier in the first 5 lines)")
      .contains("gradle.properties  (no SPDX-License-Identifier in the first 5 lines)")
      .contains("check-spdx: 4 of 4 files failed")
  }

  @Test
  @DisplayName("T-BLD-007 skips build outputs and dependencies outside git (find mode)")
  fun skipsBuildOutputs() {
    writeCompliantSources()
    write("build/generated/G.java", "class G {}\n")
    write("ui/node_modules/x/index.js", "module.exports = 1;\n")
    val result = run()
    assertThat(result.exitCode).`as`(result.output).isEqualTo(0)
  }

  @Test
  @DisplayName("T-BLD-007 checks source packages named dist; only ui/dist is a build output (find mode)")
  fun checksDistPackages() {
    writeCompliantSources()
    write("ui/dist/assets/index.js", "var a = 1;\n")
    write(
      "agent/agent-dist/src/test/java/io/argos/agent/dist/NoHeader.java",
      "package io.argos.agent.dist;\n",
    )
    val result = run()
    assertThat(result.exitCode).`as`(result.output).isEqualTo(1)
    assertThat(result.output)
      .contains("agent/agent-dist/src/test/java/io/argos/agent/dist/NoHeader.java  (no SPDX")
      .doesNotContain("ui/dist/")
      .contains("check-spdx: 1 of 8 files failed (find")
  }

  @Test
  @DisplayName("T-BLD-007 honours exclusions with a reason and rejects exclusions without one")
  fun exclusions() {
    writeCompliantSources()
    write("third_party/lib.js", "/* MIT licensed upstream file */\n")
    write("test/lint/spdx-exclusions.txt", "# header\nthird_party/*  # copied MIT code, listed in NOTICE\n")
    val excluded = run()
    assertThat(excluded.exitCode).`as`(excluded.output).isEqualTo(0)
    assertThat(excluded.output).contains("1 excluded")

    write("test/lint/spdx-exclusions.txt", "third_party/*\n")
    val noReason = run()
    assertThat(noReason.exitCode).isEqualTo(1)
    assertThat(noReason.output).contains("exclusion without a reason")
  }

  @Test
  @DisplayName("T-BLD-007 in a git work tree checks tracked and untracked files, not ignored ones")
  fun gitMode() {
    assumeTrue(gitAvailable(), "git is not available")
    writeCompliantSources()
    write(".gitignore", "generated/\n")
    write("generated/Gen.java", "class Gen {}\n")
    write("src/main/java/a/Untracked.java", "class Untracked {}\n")
    git("init", "-q")
    val result = run()
    assertThat(result.exitCode).`as`(result.output).isEqualTo(1)
    assertThat(result.output)
      .contains("src/main/java/a/Untracked.java")
      .doesNotContain("generated/Gen.java")
      .contains("git ls-files")
  }

  private fun gitAvailable(): Boolean =
    runCatching { ProcessBuilder("git", "--version").start().waitFor(30, TimeUnit.SECONDS) }
      .getOrDefault(false)

  private fun git(vararg args: String) {
    val process =
      ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
    process.inputStream.readBytes()
    check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()}" }
  }
}
