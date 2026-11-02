// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.testing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Rules of the test layers (18 §2.2, DSN-18-02): composition of check, machine profile, matrix
 * skip rule, dry-run parsing. The functional test runs them on a real task graph.
 */
@Tag("req:DSN-18-02")
class CheckCompositionTest {

  /** Task names of the projects the anchors live in, as in the real build. */
  private val projects =
    mapOf(
      ":" to setOf("check", "checkSpdx", "traceabilityReport"),
      ":agent:agent-core" to setOf("check", "test", "archTest", "checkUnitTestSources"),
      ":agent:agent-testing" to setOf("check", "test", "archTest", "integrationTest", "securityTest", "matrixTest", "benchTest"),
      ":agent:agent-dist" to setOf("check", "test", "archTest", "verifyAgentJar"),
      ":server" to setOf("check", "test", "archTest", "contractTest", "integrationTest", "securityTest"),
      ":test:storage:generator" to setOf("check", "test", "archTest", "integrationTest", "storageBench", "storageGenerate"),
    )

  private fun closure(check: String, vararg deps: String) = CheckClosure(check, deps.toSet())

  private val goodClosures =
    listOf(
      closure(":check", ":checkSpdx"),
      closure(":agent:agent-core:check", ":agent:agent-core:test", ":agent:agent-core:archTest", ":agent:agent-core:compileJava"),
      closure(":agent:agent-testing:check", ":agent:agent-testing:test", ":agent:agent-testing:archTest"),
      closure(":agent:agent-dist:check", ":agent:agent-dist:test", ":agent:agent-dist:archTest", ":agent:agent-dist:verifyAgentJar"),
      closure(":server:check", ":server:test", ":server:archTest", ":server:contractTest"),
      closure(":test:storage:generator:check", ":test:storage:generator:test", ":test:storage:generator:archTest"),
    )

  @Test
  @DisplayName("T-BLD-013 check with test, archTest, contractTest and verifyAgentJar and nothing else passes")
  fun goodComposition() {
    assertThat(CheckComposition.verify(goodClosures, projects)).isEmpty()
  }

  @Test
  @Tag("req:O-Q02")
  @DisplayName("T-BLD-013 integrationTest, securityTest, matrixTest and the performance tasks in check fail")
  fun forbiddenTasks() {
    val closures =
      goodClosures.map {
        when (it.checkPath) {
          ":server:check" -> it.copy(dependencies = it.dependencies + ":server:integrationTest" + ":server:securityTest")
          ":agent:agent-testing:check" ->
            it.copy(dependencies = it.dependencies + ":agent:agent-testing:matrixTest" + ":agent:agent-testing:benchTest")
          ":test:storage:generator:check" ->
            it.copy(dependencies = it.dependencies + ":test:storage:generator:storageBench" + ":sample-apps:shop-boot3:sampleSmokeDefault")
          else -> it
        }
      }
    val violations = CheckComposition.verify(closures, projects)
    assertThat(violations)
      .hasSize(6)
      .anySatisfy { assertThat(it).startsWith(":server:check depends on :server:integrationTest, which is not part of check") }
      .anySatisfy { assertThat(it).startsWith(":server:check depends on :server:securityTest") }
      .anySatisfy { assertThat(it).startsWith(":agent:agent-testing:check depends on :agent:agent-testing:matrixTest") }
      .anySatisfy { assertThat(it).startsWith(":agent:agent-testing:check depends on :agent:agent-testing:benchTest") }
      .anySatisfy { assertThat(it).startsWith(":test:storage:generator:check depends on :test:storage:generator:storageBench") }
      .anySatisfy { assertThat(it).contains("depends on :sample-apps:shop-boot3:sampleSmokeDefault") }
    assertThat(CheckComposition.failureMessage(violations)).startsWith("The composition of check changed (18 §2.2, DSN-18-02):\n  - ")
  }

  @Test
  @Tag("req:O-Q02")
  @DisplayName("T-BLD-013 a check that drops test, archTest, contractTest or verifyAgentJar of its project fails")
  fun requiredTasks() {
    val closures =
      goodClosures.map {
        when (it.checkPath) {
          ":server:check" -> closure(":server:check", ":server:test", ":server:archTest")
          ":agent:agent-dist:check" -> closure(":agent:agent-dist:check", ":agent:agent-dist:archTest", ":agent:agent-dist:verifyAgentJar")
          ":agent:agent-core:check" -> closure(":agent:agent-core:check", ":agent:agent-core:test")
          ":agent:agent-testing:check" -> closure(":agent:agent-testing:check", ":agent:agent-testing:archTest")
          else -> it
        }
      }
    assertThat(CheckComposition.verify(closures, projects))
      .containsExactlyInAnyOrder(
        ":agent:agent-core:check does not run :agent:agent-core:archTest (18 §2.2: check includes test, archTest, contractTest, verifyAgentJar)",
        ":agent:agent-dist:check does not run :agent:agent-dist:test (18 §2.2: check includes test, archTest, contractTest, verifyAgentJar)",
        ":agent:agent-testing:check does not run :agent:agent-testing:test (18 §2.2: check includes test, archTest, contractTest, verifyAgentJar)",
        ":server:check does not run :server:contractTest (18 §2.2: check includes test, archTest, contractTest, verifyAgentJar)",
      )
  }

  @Test
  @DisplayName("T-BLD-013 tasks excluded with -x (by name or path) are not required")
  fun excludedTasks() {
    val closures = goodClosures.map { if (it.checkPath == ":server:check") closure(":server:check", ":server:archTest") else it }
    assertThat(CheckComposition.verify(closures, projects, excluded = setOf("test", ":server:contractTest"))).isEmpty()
    assertThat(CheckComposition.verify(closures, projects, excluded = setOf("test"))).hasSize(1)
  }

  @Test
  @DisplayName("T-BLD-013 the full check fails when an anchor (server contractTest, agent-dist verifyAgentJar) no longer exists")
  fun anchors() {
    val withoutContract = projects + (":server" to setOf("check", "test", "archTest"))
    val withoutDist = projects - ":agent:agent-dist"
    assertThat(CheckComposition.verify(goodClosures.filter { it.checkPath != ":agent:agent-dist:check" }, withoutDist))
      .containsExactly(":agent:agent-dist:verifyAgentJar no longer exists; check must include it (18 §2.2, TestLayers.CHECK_ANCHORS)")
    assertThat(CheckComposition.verify(goodClosures, withoutContract))
      .containsExactly(":server:contractTest no longer exists; check must include it (18 §2.2, TestLayers.CHECK_ANCHORS)")
    // A single project's check (no root :check) does not need the anchors of other projects.
    assertThat(CheckComposition.verify(goodClosures.filter { it.checkPath == ":agent:agent-core:check" }, withoutDist)).isEmpty()
  }

  @Test
  @DisplayName("T-BLD-013 dry-run output parsing keeps task paths in order and ignores other lines")
  fun dryRunParsing() {
    val output =
      """
      > Configure project :
      :agent:agent-core:compileJava SKIPPED
      :agent:agent-core:test SKIPPED
      :check SKIPPED

      BUILD SUCCESSFUL in 2s
      """
        .trimIndent()
        .replace("\n", "\r\n")
    assertThat(DryRunOutput.taskPaths(output)).containsExactly(":agent:agent-core:compileJava", ":agent:agent-core:test", ":check")
  }

  @Test
  @Tag("req:O-S05")
  @DisplayName("T-BLD-013 machine profile: required, validated, and the message names every requested task")
  fun machineProfile() {
    assertThat(MachineProfile.isValid(null)).isFalse()
    assertThat(MachineProfile.isValid("")).isFalse()
    assertThat(MachineProfile.isValid("argos-bench-app")).isTrue()
    assertThat(MachineProfile.isValid("dev.laptop_1")).isTrue()
    assertThat(MachineProfile.isValid("bad/id")).isFalse()
    assertThat(MachineProfile.isValid("-starts-with-dash")).isFalse()
    assertThat(MachineProfile.missingMessage(listOf(":test:soak:soakTest", ":test:bench:benchTest"), null))
      .startsWith("benchTest, soakTest require -Pbench.machine-profile=<id> (18 §2.2).")
      .endsWith("Requested: :test:bench:benchTest, :test:soak:soakTest")
    assertThat(MachineProfile.missingMessage(listOf(":test:soak:soakTest"), null)).startsWith("soakTest requires -Pbench.machine-profile=<id>")
    assertThat(MachineProfile.missingMessage(listOf(":x:chaosTest"), "a b")).startsWith("chaosTest got an invalid -Pbench.machine-profile='a b'")
  }

  @Test
  @Tag("req:O-S05")
  @DisplayName("T-BLD-013 matrixTest runs on an argos-matrix runner or with -Pmatrix.combo/-Pmatrix.group, otherwise skips")
  fun matrixGate() {
    assertThat(MatrixGate.shouldRun(null, null, null)).isFalse()
    assertThat(MatrixGate.shouldRun("self-hosted, linux", null, null)).isFalse()
    assertThat(MatrixGate.shouldRun("argos-matrix-x", null, null)).isFalse()
    assertThat(MatrixGate.shouldRun("self-hosted, argos-matrix", null, null)).isTrue()
    assertThat(MatrixGate.shouldRun(null, "T-MX-021", null)).isTrue()
    assertThat(MatrixGate.shouldRun(null, null, "boot3")).isTrue()
    assertThat(MatrixGate.shouldRun(null, " ", "")).isFalse()
    assertThat(MatrixGate.skipWarning(":agent:agent-testing:matrixTest"))
      .startsWith("WARNING: :agent:agent-testing:matrixTest SKIPPED: not on a self-hosted 'argos-matrix' runner")
  }

  @Test
  @DisplayName("T-BLD-013 the layer lists match 18 §2.2")
  fun layerLists() {
    assertThat(TestLayers.CHECK_TASKS).containsExactly("test", "archTest", "contractTest", "verifyAgentJar")
    assertThat(TestLayers.MACHINE_PROFILE_TASKS).containsExactly("benchTest", "serverLoadTest", "storageBench", "soakTest", "chaosTest")
    assertThat(TestLayers.NOT_IN_CHECK).contains("integrationTest", "securityTest", "matrixTest").containsAll(TestLayers.MACHINE_PROFILE_TASKS)
    assertThat(TestLayers.isNotInCheck("transportPocBench")).isTrue()
    assertThat(TestLayers.isNotInCheck("test")).isFalse()
    assertThat(TestLayers.projectPathOf(":check")).isEqualTo(":")
    assertThat(TestLayers.projectPathOf(":a:b:test")).isEqualTo(":a:b")
    assertThat(TestLayers.taskPath(":", "check")).isEqualTo(":check")
  }
}
