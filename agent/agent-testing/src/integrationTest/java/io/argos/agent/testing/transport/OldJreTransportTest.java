/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.log.AgentLogAssert;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Java 8 floor of the transport (ADR-0005 decision 6, 11 §15 #4, 10 §10.3) on the old JREs that
 * a run names: 8u252 has ALPN in the JDK and connects with TLS 1.2 (T-AG-071 "8u252 TLS"); 8u242
 * has none, so the agent logs one ERROR, turns the transport off and keeps instrumenting.
 *
 * <p>The JREs are not Gradle toolchains: system property {@code argos.test.jdk8u252} / {@code
 * argos.test.jdk8u242} (from {@code -Pagent.jdk8u252=<JRE home>}, {@code -Pagent.jdk8u242=<JRE
 * home>}) or the environment variable {@code ARGOS_TEST_JDK8U252} / {@code ARGOS_TEST_JDK8U242}.
 * Without them the test is skipped and says so. Download: AdoptOpenJDK {@code jdk8u242-b08} and
 * {@code jdk8u252-b09.1} (docs/poc/agent-poc.md 9.8); on Windows unpack them outside {@code
 * %TEMP%}.
 */
@Tag("req:DSN-10-11")
class OldJreTransportTest {
  @TempDir(cleanup = CleanupMode.NEVER)
  Path work;

  @AfterEach
  void deleteRun() {
    TransportRuns.deleteRun(work);
  }

  private static Path jre(String name) {
    String home = System.getProperty("argos.test." + name);
    if (home == null || home.isBlank()) {
      home = System.getenv("ARGOS_TEST_" + name.toUpperCase(java.util.Locale.ROOT));
    }
    Assumptions.assumeTrue(
        home != null && !home.isBlank(),
        "-Pagent."
            + name
            + "=<JRE home> (or ARGOS_TEST_"
            + name.toUpperCase(java.util.Locale.ROOT)
            + ") is not set");
    Path bin = Path.of(home).resolve("bin");
    Path java = bin.resolve(isWindows() ? "java.exe" : "java");
    Assumptions.assumeTrue(Files.isRegularFile(java), "no JRE at " + home);
    return java;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
  }

  @Test
  @Tag("req:DSN-02-11")
  @DisplayName("T-AG-071 8u252: the agent connects with TLS 1.2 and delivers batches")
  void tls12On8u252() throws Exception {
    Path java = jre("jdk8u252");
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start()) {
      ExitedApp run =
          TransportRuns.runToExit(
              TransportRuns.trivial(
                  work.resolve("8u252"),
                  new Jdk(8, java),
                  TransportRuns.probeJar(),
                  collector,
                  TransportRuns.STAY_ALIVE_MILLIS));
      assertThat(collector.sessions()).as(run.agentLog()).hasSize(1);
      assertThat(collector.sessions().get(0).accepted()).isTrue();
      assertThat(collector.seqs(BatchKind.TRANSACTIONS)).as(run.agentLog()).isNotEmpty();
      assertThat(run.agentLog()).containsPattern("collector session established .*TLSv1\\.2");
      AgentLogAssert.assertThatAgentLog(run.agentLog()).hasNoErrors().hasNoWarnings();
    }
  }

  @Test
  @DisplayName(
      "ADR-0005 decision 6, 8u242 (no ALPN): one ERROR line, transport off, instrumentation keeps"
          + " running, console clean, nothing reaches the collector")
  void errorAndContinueOn8u242() throws Exception {
    Path java = jre("jdk8u242");
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start()) {
      ExitedApp run =
          TransportRuns.runToExit(
              TransportRuns.trivial(
                  work.resolve("8u242"),
                  new Jdk(8, java),
                  TransportRuns.probeJar(),
                  collector,
                  "3000"));
      List<AgentLogAssert.LogLine> errors = AgentLogAssert.linesAt(run.agentLog(), "ERROR");
      assertThat(errors).as(run.agentLog()).hasSize(1);
      assertThat(errors.get(0).message())
          .contains("agent transport disabled")
          .contains("8u252")
          .contains("1.8.0_242");
      assertThat(collector.sessions()).as("no connection").isEmpty();
      assertThat(run.transformListLines())
          .as("the instrumentation keeps running")
          .anyMatch(line -> line.contains("example.trivial.TrivialApp$Handler"));
      assertThat(run.properties("results.properties").getProperty("handled")).isEqualTo("3");
      assertThat(run.stdout().strip()).isEqualTo("trivial-app done");
      assertThat(run.stderr().strip()).isEmpty();
    }
  }
}
