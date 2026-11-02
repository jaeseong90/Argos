// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.testing

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * argos.test-layers on a real task graph (TestKit, no network): a throwaway build with the project
 * layout of the anchors (agent-core, agent-testing, agent-dist, server) whose tasks are plain
 * lifecycle tasks named like the real suites, and the real skeleton tasks (registerPendingRunner).
 * `./gradlew check --dry-run` is parsed and must contain test, archTest, contractTest and
 * verifyAgentJar and none of integrationTest, securityTest, matrixTest and the performance tasks;
 * wiring one of those into check, or dropping a required one, fails the build (18 §2.2).
 */
@Tag("req:DSN-18-02")
class TestLayersFunctionalTest {
  @TempDir lateinit var dir: File

  @BeforeEach
  fun setUp() {
    write(
      "settings.gradle",
      """
      rootProject.name = 'test-layers-fixture'
      include ':agent:agent-core', ':agent:agent-testing', ':agent:agent-dist', ':server'
      include ':test:bench', ':test:soak', ':test:storage:generator'
      """,
    )
    write("build.gradle", "plugins { id 'base'; id 'argos.test-layers' }\n")
    write(
      "agent/agent-core/build.gradle",
      """
      plugins { id 'base' }
      ['test', 'archTest'].each { n -> tasks.register(n) { doLast { println "ran ${'$'}n" } } }
      tasks.named('check') { dependsOn 'test', 'archTest' }
      """,
    )
    write(
      "agent/agent-testing/build.gradle",
      """
      plugins { id 'base' }
      ['test', 'archTest', 'integrationTest', 'securityTest', 'matrixTest'].each { n -> tasks.register(n) }
      tasks.named('check') { dependsOn 'test', 'archTest' }
      """,
    )
    write(
      "agent/agent-dist/build.gradle",
      """
      plugins { id 'base' }
      tasks.register('verifyAgentJar')
      tasks.named('check') { if (!providers.gradleProperty('fixture.dropVerify').isPresent()) dependsOn 'verifyAgentJar' }
      """,
    )
    write(
      "server/build.gradle",
      """
      plugins { id 'base' }
      def names = ['test', 'integrationTest', 'securityTest']
      if (!providers.gradleProperty('fixture.noContractTest').isPresent()) names += 'contractTest'
      names.each { n -> tasks.register(n) }
      tasks.named('check') {
        dependsOn 'test'
        if (names.contains('contractTest')) dependsOn 'contractTest'
        if (providers.gradleProperty('fixture.integrationInCheck').isPresent()) dependsOn 'integrationTest'
      }
      """,
    )
    write(
      "test/bench/build.gradle",
      """
      plugins { id 'base' }
      tasks.register('benchTest') { doLast { println 'bench ran' } }
      """,
    )
    write(
      "test/soak/README.md",
      "# test/soak\n",
    )
    write(
      "test/soak/build.gradle",
      """
      plugins { id 'base' }
      io.argos.buildlogic.testing.PendingRunnerKt.registerPendingRunner(
        project, 'soakTest', 'WO-52', 'docs/18-test-design.md §11.2', '72-hour soak test.')
      """,
    )
    write(
      "test/storage/generator/build.gradle",
      """
      plugins { id 'base' }
      tasks.register('storageBench') { doLast { println 'storage bench ran' } }
      """,
    )
  }

  private fun write(path: String, text: String) {
    File(dir, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent() + "\n")
  }

  private fun runner(vararg arguments: String): GradleRunner =
    GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(arguments.toList() + "--stacktrace")

  @Test
  @Tag("req:O-Q02")
  @DisplayName("T-BLD-013 check --dry-run: test, archTest, contractTest, verifyAgentJar in; integration, security, matrix, bench out")
  fun checkComposition() {
    val result = runner("check", "--dry-run", "--configuration-cache").build()
    val tasks = DryRunOutput.taskPaths(result.output)
    assertThat(tasks)
      .contains(":agent:agent-core:test", ":agent:agent-core:archTest", ":agent:agent-testing:test")
      .contains(":agent:agent-testing:archTest", ":server:contractTest", ":agent:agent-dist:verifyAgentJar", ":check")
    assertThat(tasks.map { TestLayers.nameOf(it) })
      .doesNotContain("integrationTest", "securityTest", "matrixTest", "benchTest", "soakTest", "storageBench")
  }

  @Test
  @Tag("req:O-Q02")
  @DisplayName("T-BLD-013 wiring integrationTest into check fails the build before any task runs")
  fun integrationTestInCheckFails() {
    val result = runner("check", "-Pfixture.integrationInCheck=true").buildAndFail()
    assertThat(result.output)
      .contains("The composition of check changed (18 §2.2, DSN-18-02):")
      .contains(":server:check depends on :server:integrationTest, which is not part of check")
      .doesNotContain("ran test")
  }

  @Test
  @Tag("req:O-Q02")
  @DisplayName("T-BLD-013 dropping verifyAgentJar or the contractTest suite from check fails; -x verifyAgentJar is allowed")
  fun requiredTasksAndAnchors() {
    assertThat(runner("check", "--dry-run", "-Pfixture.dropVerify=true").buildAndFail().output)
      .contains(":agent:agent-dist:check does not run :agent:agent-dist:verifyAgentJar")
    assertThat(runner("check", "--dry-run", "-Pfixture.noContractTest=true").buildAndFail().output)
      .contains(":server:contractTest no longer exists; check must include it")
    runner("check", "--dry-run", "-Pfixture.dropVerify=true", "-x", "verifyAgentJar").build()
  }

  @Test
  @Tag("req:O-S05")
  @DisplayName("T-BLD-013 benchTest, soakTest and storageBench fail fast without -Pbench.machine-profile; soakTest names WO-52")
  fun machineProfileGuard() {
    val missing = runner("benchTest", "soakTest", "storageBench").buildAndFail()
    assertThat(missing.output)
      .contains("benchTest, soakTest, storageBench require -Pbench.machine-profile=<id> (18 §2.2)")
      .contains("Requested: :test:bench:benchTest, :test:soak:soakTest, :test:storage:generator:storageBench")
      .doesNotContain("bench ran")
    assertThat(runner("benchTest", "-Pbench.machine-profile=bad/id").buildAndFail().output)
      .contains("benchTest got an invalid -Pbench.machine-profile='bad/id'")
    assertThat(runner("benchTest", "-Pbench.machine-profile=dev-laptop").build().output).contains("bench ran")
    val pending = runner("soakTest", "-Pbench.machine-profile=dev-laptop").buildAndFail()
    assertThat(pending.output)
      .contains("soakTest has no runner yet: it is implemented by WO-52 (docs/18-test-design.md §11.2). Nothing was measured.")
      .contains("See test/soak/README.md.")
  }

  @Test
  @Tag("req:DSN-18-01")
  @DisplayName("T-BLD-014 traceabilityReport writes the requirement table and its JSON twin from sources and results")
  fun traceabilityReport() {
    write(
      "agent/agent-core/src/test/java/io/example/ClockTest.java",
      """
      package io.example;

      import org.junit.jupiter.api.DisplayName;
      import org.junit.jupiter.api.Tag;
      import org.junit.jupiter.api.Test;

      class ClockTest {
        @Test
        @Tag("req:D-01")
        @DisplayName("T-ACC-01 monotonic clock")
        void monotonic() {}
      }
      """,
    )
    write(
      "agent/agent-core/build/test-results/test/TEST-io.example.ClockTest.xml",
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="io.example.ClockTest" tests="1" timestamp="2026-11-03T01:02:03.456Z">
        <testcase name="T-ACC-01 monotonic clock" classname="io.example.ClockTest" time="0.01"/>
      </testsuite>
      """,
    )
    val result = runner("traceabilityReport", "--configuration-cache").build()
    assertThat(result.output).contains("Traceability: 1 requirements, 1 test IDs, 1 tagged tests (0 failed")
    val md = File(dir, "build/reports/traceability.md").readText()
    assertThat(md)
      .contains("| D-01 | T-ACC-01 | `ClockTest` T-ACC-01 monotonic clock ([agent/agent-core/src/test/java/io/example/ClockTest.java:11]")
      .contains("| test | PASSED (2026-11-03T01:02:03.456Z) |")
    assertThat(File(dir, "build/reports/traceability.json").readText()).contains("\"id\": \"D-01\"").contains("\"status\": \"PASSED\"")
  }
}
