/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.attach;

import static io.argos.agent.testing.attach.AttachRuns.DONE;
import static io.argos.agent.testing.attach.AttachRuns.jar;
import static io.argos.agent.testing.attach.AttachRuns.jdks;
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
 * build ignores the test-only namespace {@code argos.test.*}, a snapshot build honours it; the
 * WO-02 PoC exporter keys are gone in both (ADR-0009 C-88).
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
                                + " honours it); argos.exporter has no effect in either (C-88)",
                            () -> releaseBuildIgnoresTestNamespace(jdk)))));
  }

  /**
   * The release-build marker is the jar's {@code Implementation-Version} without {@code -SNAPSHOT}
   * (C-89). The same probe jar with only that attribute changed ({@link AgentJars#withVersion})
   * ignores {@code argos.test.faults}, {@code argos.test.exporter} and {@code
   * argos.test.selfdiag-file} and says so in one WARN: no fault, no JSONL, no self-diagnostics
   * file, the default exporter {@code grpc}. The PoC keys {@code argos.exporter} and {@code
   * argos.exporter.file.path} are unknown keys in both builds (one WARN each) and write no file
   * (C-88: a release agent never writes collected data to a local file). The snapshot jar with the
   * same settings applies every test key.
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
            // Set so that the release run (no test exporter) reports no missing-address ERROR;
            // there is no transport before WO-11, nothing connects.
            "-Dargos.server.address=127.0.0.1:1",
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
        .contains("test-only settings ignored in a release build (T-BLD-010)")
        .contains("argos.test.exporter", "argos.test.faults", "argos.test.selfdiag-file")
        .contains("exporter=grpc")
        .contains("unknown configuration key 'exporter'")
        .contains("unknown configuration key 'exporter.file.path'")
        .doesNotContain("argos.test.faults: advice of module")
        .doesNotContain("advice error in module");
    assertThatAgentLog(releaseLog)
        .hasNoErrors()
        .hasWarningCount("test-only settings ignored", 1)
        .hasWarningCount("unknown configuration key 'exporter", 2);
    // No fault (three requests handled), no file of any exporter, no self-diagnostics file.
    assertThat(released.properties("results.properties").getProperty("handled")).isEqualTo("3");
    assertThat(released.out().resolve("poc.jsonl")).doesNotExist();
    assertThat(released.transactions().file()).doesNotExist();
    assertThat(released.selfDiagnostics().available()).isFalse();
    assertThat(released.premainClasses()).isEmpty();

    ExitedApp snapshotRun =
        AttachRuns.launch(
            work, jdk, "snapshot-gate", snapshot, withRun(settings, "snapshot-gate"), true);
    assertThat(snapshotRun.exitCode()).as(snapshotRun.describe()).isZero();
    String snapshotLog = snapshotRun.agentLog();
    assertThat(snapshotLog)
        .doesNotContain("test-only settings ignored")
        .contains("argos.test.faults: advice of module test-probe throws")
        .contains("advice error in module 60")
        .contains("exporter=file")
        .contains("unknown configuration key 'exporter'");
    assertThatAgentLog(snapshotLog).hasWarningCount("unknown configuration key 'exporter", 2);
    // argos.test.exporter=file applies (an empty JSONL: the fault stops every recording), the PoC
    // key writes nothing, the self-diagnostics file exists.
    assertThat(snapshotRun.out().resolve("poc.jsonl")).doesNotExist();
    assertThat(snapshotRun.transactions().file()).exists();
    assertThat(transactions(snapshotRun)).isEmpty();
    assertThat(snapshotRun.selfDiagnostics().available()).isTrue();
  }
}
