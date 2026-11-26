// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The tasks on a real build (TestKit, no network): argos.test-layers registers checkTraceability
 * and gate1Report on the root project; a clean checkout passes with results ignored, an
 * uncatalogued ID fails the build with the diff, gate1Report writes its page.
 */
@Tag("req:DSN-18-01")
class TraceabilityCheckFunctionalTest {
  @TempDir lateinit var dir: File

  private fun runner(vararg arguments: String): GradleRunner =
    GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(arguments.toList() + "--stacktrace")

  @Test
  @DisplayName("T-BLD-014g checkTraceability and gate1Report on a real build: clean checkout passes, an uncatalogued ID fails with the diff")
  fun tasks() {
    CheckFixture.create(dir)
    File(dir, "settings.gradle").writeText("rootProject.name = 'traceability-fixture'\n")
    File(dir, "build.gradle").writeText("plugins { id 'base'; id 'argos.test-layers' }\n")

    val clean = runner("checkTraceability", "gate1Report", "-Ptraceability.results=none").build()
    assertThat(clean.task(":checkTraceability")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(clean.output).contains("Traceability check: R1 0, R2 0, R3 0, R4 0")
    assertThat(File(dir, "build/reports/traceability-check.md").readText()).contains("Results: ignored")
    assertThat(File(dir, "build/reports/traceability-check.json")).exists()
    assertThat(File(dir, "build/reports/gate1.md").readText()).contains("# Gate 1 NFR report").contains(Gate1Report.NO_EVIDENCE)

    CheckFixture.writeSource(dir, CheckFixture.tests + ("T-AG-099 not catalogued" to listOf("F-A01")))
    val failed = runner("checkTraceability").buildAndFail()
    assertThat(failed.task(":checkTraceability")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(failed.output).contains("Traceability check failed").contains("R1 T-AG-099").contains(CheckFixture.SOURCE)
  }
}
