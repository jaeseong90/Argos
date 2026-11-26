/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.SessionInfo;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.log.AgentLogAssert;
import io.argos.proto.v1.AgentHello;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proposed T-SEC-08d (ADR-0010 C-250 (8), SEC-07, WO-50): on a JDK 8 from update 261 on (TLS 1.3 in
 * the JDK's own provider), the agent negotiates TLS 1.3 with a TLS collector. The trivial app runs
 * on the toolchain JDK 8 ({@code argos.test.java8}) with the release agent (plus the test-only
 * probe module, so that batches flow) against a {@link FakeCollector} that offers TLS 1.2 and 1.3
 * (JDK provider of the harness JVM): one accepted session, {@code AgentHello.plaintext} false,
 * transactions delivered, the agent's session line names {@code TLSv1.3}, no ERROR or WARN, nothing
 * on the console but the app's own line. An older JDK 8 update is skipped with the reason (8u242
 * and 8u252 are the old-JRE rows of {@code transport.OldJreTransportTest}).
 *
 * <p>Results: {@code [mx-tls13]} and {@code <resultDir>/tls13.properties}.
 */
@Tag("req:SEC-07")
@Tag("req:DSN-10-11")
class Tls13Jdk8Test {
  static final String TRIVIAL_MAIN = "example.trivial.TrivialApp";
  static final Pattern UPDATE = Pattern.compile("version \"1\\.8\\.0_(\\d+)");

  @Test
  @DisplayName(
      "T-SEC-08d JDK 8u261+: the agent negotiates TLSv1.3 with a TLS collector and delivers"
          + " batches")
  void tls13OnJdk8() throws Exception {
    Jdk jdk8;
    try {
      jdk8 = Jdk.of(8);
    } catch (IllegalStateException e) {
      Assumptions.abort("no JDK 8 for this run (argos.test.java8): " + e.getMessage());
      return;
    }
    String version = javaVersion(jdk8);
    Matcher matcher = UPDATE.matcher(version);
    assertThat(matcher.find()).as("a JDK 8 version line: %s", version).isTrue();
    int update = Integer.parseInt(matcher.group(1));
    Assumptions.assumeTrue(
        update >= 261, "JDK 8 update " + update + " has no TLS 1.3 in its provider (8u261+)");

    try (MatrixRuns.TempTree tree = MatrixRuns.TempTree.create("mx-tls13-");
        FakeCollector collector =
            FakeCollector.builder().tls(true).tlsDirectory(tree.root().resolve("tls")).start()) {
      Path work = tree.root();
      Path dir = work.resolve("jdk8u" + update);
      String agentJar = System.getProperty("argos.test.probeAgentJar");
      AppLauncher launcher =
          AppLauncher.of(
                  SampleApp.mainClass(
                      "trivial", TRIVIAL_MAIN, MatrixRuns.required("argos.test.trivialAppClasses")),
                  jdk8)
              .withAgent(
                  agentJar == null || agentJar.isBlank()
                      ? MatrixRuns.releaseJar()
                      : Path.of(agentJar))
              .collector(collector)
              .args(dir.resolve("out").resolve("results.properties").toString(), "12000")
              .workDir(dir);
      ExitedApp run = launcher.runToExit(Duration.ofMinutes(2));
      assertThat(run.timedOut()).as(run.describe()).isFalse();
      assertThat(run.exitCode()).as(run.describe()).isZero();
      List<SessionInfo> sessions = collector.sessions();
      assertThat(sessions).as(run.agentLog()).hasSize(1);
      assertThat(sessions.get(0).accepted()).isTrue();
      assertThat(((AgentHello) sessions.get(0).hello()).getPlaintext()).isFalse();
      assertThat(run.agentLog())
          .as("T-SEC-08d: the session line names the negotiated protocol")
          .containsPattern("collector session established .*TLSv1\\.3");
      if (agentJar != null && !agentJar.isBlank()) {
        assertThat(collector.seqs(BatchKind.TRANSACTIONS)).as(run.agentLog()).isNotEmpty();
      }
      AgentLogAssert.assertThatAgentLog(run.agentLog()).hasNoErrors().hasNoWarnings();
      assertThat(run.stderr().strip()).as("stderr").isEmpty();
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("jdk", "1.8.0_" + update);
      values.put("protocol", "TLSv1.3");
      values.put("sessions", sessions.size());
      values.put("transaction_batches", collector.seqs(BatchKind.TRANSACTIONS).size());
      MatrixRuns.result("tls13", values);
    }
  }

  /** {@code java -version} of {@code jdk} (stderr). */
  static String javaVersion(Jdk jdk) throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder(jdk.java().toString(), "-version").redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    process.waitFor(30, TimeUnit.SECONDS);
    return output;
  }
}
