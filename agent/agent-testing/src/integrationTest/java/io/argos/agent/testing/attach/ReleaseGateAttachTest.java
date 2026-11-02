/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.attach;

import static io.argos.agent.testing.attach.AttachRuns.DONE;
import static io.argos.agent.testing.attach.AttachRuns.jar;
import static io.argos.agent.testing.attach.AttachRuns.jdks;
import static io.argos.agent.testing.attach.AttachRuns.lines;
import static io.argos.agent.testing.attach.AttachRuns.transactions;
import static io.argos.agent.testing.attach.AttachRuns.withRun;
import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.jvm.AgentJars;
import io.argos.agent.testing.launcher.ExitedApp;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-BLD-010 (DSN-18-15) on each JDK of {@code -Pagent.attach.jdks} ({@link AttachRuns}): a release
 * build ignores the test-only namespace {@code argos.test.*}, a snapshot build honours it.
 */
@Tag("req:DSN-18-15")
class ReleaseGateAttachTest {
  @TempDir Path work;

  @TestFactory
  Stream<DynamicNode> releaseGateOnEveryJdk() {
    return jdks().stream()
        .map(
            jdk ->
                DynamicContainer.dynamicContainer(
                    "JDK " + jdk,
                    Stream.of(
                        DynamicTest.dynamicTest(
                            "T-BLD-010 a release build ignores argos.test.* (a snapshot build"
                                + " honours it)",
                            () -> releaseBuildIgnoresTestNamespace(jdk)))));
  }

  /**
   * The release-build marker is the jar's {@code Implementation-Version} without {@code -SNAPSHOT}.
   * The same probe jar with only that attribute changed ({@link AgentJars#withVersion}) ignores
   * {@code argos.test.faults} and {@code argos.test.exporter} (transactions are recorded and go to
   * the PoC {@code exporter=file} key instead) and says so in one WARN; the snapshot jar with the
   * same settings applies both.
   */
  void releaseBuildIgnoresTestNamespace(int jdk) throws Exception {
    Path snapshot = jar("probeAgentJar");
    assertThat(AgentJars.version(snapshot)).endsWith("-SNAPSHOT");
    Path release =
        AgentJars.withVersion(
            snapshot, work.resolve("release-flavour-" + jdk).resolve("argos-agent.jar"), "0.0.0");
    List<String> settings =
        List.of(
            "-Dargos.test.faults=advice-throw:test-probe",
            "-Dargos.exporter=file",
            "-Dargos.exporter.file.path=" + work.resolve("jdk" + jdk + "-%s/out/poc.jsonl"));

    ExitedApp released =
        AttachRuns.launch(
            work, jdk, "release-gate", release, withRun(settings, "release-gate"), true);
    assertThat(released.exitCode()).as(released.describe()).isZero();
    assertThat(released.stdout().strip()).isEqualTo(DONE);
    String releaseLog = released.agentLog();
    assertThat(releaseLog)
        .contains("Argos agent 0.0.0 ")
        .contains(
            "configuration: test-only settings ignored in a release build (T-BLD-010):"
                + " [argos.test.exporter, argos.test.faults]")
        .contains("exporter=file")
        .doesNotContain("argos.test.faults: advice of module")
        .doesNotContain("advice error in module");
    assertThatAgentLog(releaseLog).hasNoErrors().hasWarningCount("test-only settings ignored", 1);
    // The fault had no effect: three transactions; the test exporter had no effect: no JSONL in
    // out/.
    assertThat(lines(released.out().resolve("poc.jsonl"))).as(released.describe()).hasSize(3);
    assertThat(released.transactions().file()).doesNotExist();
    assertThat(released.properties("results.properties").getProperty("handled")).isEqualTo("3");

    ExitedApp snapshotRun =
        AttachRuns.launch(
            work, jdk, "snapshot-gate", snapshot, withRun(settings, "snapshot-gate"), true);
    assertThat(snapshotRun.exitCode()).as(snapshotRun.describe()).isZero();
    assertThat(snapshotRun.agentLog())
        .doesNotContain("test-only settings ignored")
        .contains("argos.test.faults: advice of module test-probe throws")
        .contains("advice error in module 60");
    // argos.test.exporter wins over the PoC key in a snapshot build: no poc.jsonl, an empty JSONL.
    assertThat(snapshotRun.out().resolve("poc.jsonl")).doesNotExist();
    assertThat(snapshotRun.transactions().file()).exists();
    assertThat(transactions(snapshotRun)).isEmpty();
  }
}
