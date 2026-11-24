/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.log;

import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-AG-060 log rules: ERROR 0, WARN only from the allow-list fixture, no seed sentinel. */
@Tag("req:F-A35")
class AgentLogAssertTest {
  static final String BANNER =
      "2026-11-03T03:00:00.000+09:00 INFO  [main] Argos agent 0.0.0-SNAPSHOT (commit x, protocol 1)"
          + " started in 300.1 ms: app=- env=default exporter=file modules=[servlet]";

  @Test
  @DisplayName("log lines parse into level, thread and message; stack lines are continuations")
  void parses() {
    String log =
        BANNER
            + "\n2026-11-03T03:00:01.000+09:00 WARN  [argos-sender] something odd\n"
            + "2026-11-03T03:00:02.000+09:00 ERROR [main] boom\n\tat x.Y.z(Y.java:1)\n";
    List<AgentLogAssert.LogLine> lines = AgentLogAssert.parse(log);
    assertThat(lines).hasSize(3);
    assertThat(lines.get(1).level()).isEqualTo("WARN");
    assertThat(lines.get(1).thread()).isEqualTo("argos-sender");
    assertThat(lines.get(1).message()).isEqualTo("something odd");
    assertThat(AgentLogAssert.linesAt(log, "ERROR")).hasSize(1);
  }

  @Test
  @DisplayName(
      "T-AG-060 the fixture allows only the listed WARN lines; ERROR and other WARN lines fail")
  void allowlist(@TempDir Path dir) throws Exception {
    // Since WO-11 the jar has the transport: the C-5 line left the fixture and fails the rule now.
    String grpcMissing =
        "2026-11-03T03:00:01.000+09:00 WARN  [argos-sender] exporter 'grpc' is not available in"
            + " this agent build (io.argos.agent.core.transport.GrpcExporterFactory missing);"
            + " transactions are recorded and dropped (noop)";
    assertThatThrownBy(() -> assertThatAgentLog(BANNER + "\n" + grpcMissing).isNormalScenarioLog())
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("exporter 'grpc'");
    // The mechanism on a list of its own: comments are skipped, a pattern takes the whole message.
    Path file = dir.resolve("allow.txt");
    Files.writeString(file, "# a comment\n\nknown warning \\d+\n", StandardCharsets.UTF_8);
    List<Pattern> listed = AgentLogAssert.readAllowlist(file);
    assertThat(listed).hasSize(1);
    String known = "2026-11-03T03:00:01.000+09:00 WARN  [argos-sender] known warning 7";
    assertThatAgentLog(BANNER + "\n" + known + "\n")
        .hasStartBanner()
        .hasNoErrors()
        .hasOnlyAllowedWarnings(listed)
        .hasWarningCount("known warning", 1);
    assertThatThrownBy(() -> assertThatAgentLog(BANNER + "\n" + known).hasNoWarnings())
        .isInstanceOf(AssertionError.class);
    assertThatThrownBy(
            () ->
                assertThatAgentLog(BANNER + "\n" + known + " more").hasOnlyAllowedWarnings(listed))
        .isInstanceOf(AssertionError.class);
    String other = "2026-11-03T03:00:01.000+09:00 WARN  [main] configuration: unknown key 'x'";
    assertThatThrownBy(() -> assertThatAgentLog(BANNER + "\n" + other).isNormalScenarioLog())
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("unknown key 'x'");
    String error = "2026-11-03T03:00:01.000+09:00 ERROR [main] Argos agent failed to start";
    assertThatThrownBy(() -> assertThatAgentLog(BANNER + "\n" + error).isNormalScenarioLog())
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("failed to start");
    assertThatThrownBy(() -> assertThatAgentLog("x ARGOS_SEED y").doesNotContainSeedSentinel())
        .isInstanceOf(AssertionError.class);
    // A secret is never echoed by the failure (SEC-06): only its length and line number.
    String secret = "s3cr3t-value-not-a-token";
    assertThatThrownBy(() -> assertThatAgentLog("a\nb " + secret + " c").doesNotContainText(secret))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("line 2")
        .hasMessageNotContaining(secret);
    assertThatThrownBy(() -> assertThatAgentLog("").hasStartBanner())
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("T-AG-060 console: application lines and the CDS notice pass, agent output fails")
  void console() {
    String cds =
        "OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes"
            + " because bootstrap classpath has been appended";
    String boot = "2026-10-24T16:47:30.409+09:00  INFO 1234 --- [main] i.a.App : Started";
    AgentLogAssert.assertQuietConsole(List.of(boot), List.of(cds, ""));
    assertThatThrownBy(
            () -> AgentLogAssert.assertQuietConsole(List.of(boot, "[argos] hi"), List.of()))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("[argos] hi");
    assertThatThrownBy(() -> AgentLogAssert.assertQuietConsole(List.of(boot), List.of("oops")))
        .hasMessageContaining("oops");
  }

  @Test
  @DisplayName(
      "T-AG-060 console: the MDC keys argos.trace_id/argos.tx_id printed by the application's"
          + " pattern (10 §6.9) are not agent output; other [argos lines still fail")
  void consoleMdcKeys() {
    String mdc =
        "2026-11-24 12:34:45.758  WARN [argos.trace_id=6b0505d52cfc8df6aea72b0500057a43,"
            + " argos.tx_id=034a62117edd5000] 9996 --- [exec-10] o.s.w.s.PageNotFound : No mapping";
    String txOnly = "2026-11-24 12:34:45.758  INFO [argos.tx_id=034a62117edd5000] 1 --- [x] a : b";
    assertThat(AppOutput.agentMentions(List.of(mdc, txOnly))).isEmpty();
    AgentLogAssert.assertQuietConsole(List.of(mdc, txOnly), List.of());
    assertThat(
            AppOutput.agentMentions(
                List.of(
                    "2026-11-24 12:34:45.758  WARN [argos-logger] 1 --- [x] a : b",
                    "2026-11-24 12:34:45.758  WARN [argos.agent] 1 --- [x] a : b",
                    "\tat io.argos.agent.core.Foo.bar(Foo.java:1)")))
        .hasSize(3);
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName(
      "18 §5.7 hasInjectedFault: exactly one announce WARN of the fault and module; a spec that"
          + " injected nothing, another module or another kind fails")
  void injectedFault() {
    String announced =
        "2026-11-03T03:00:01.000+09:00 WARN  [main] argos.test.faults: advice of module jdbc"
            + " throws (test fault)";
    String jdkModule =
        "2026-11-03T03:00:01.000+09:00 WARN  [main] argos.test.faults: transformations of module"
            + " test-probe-jdk fail (test fault)";
    String unmatched =
        "2026-11-03T03:00:01.000+09:00 WARN  [main] argos.test.faults: 'advice-throw:servlet'"
            + " names no installed module (installed: [jdbc])";
    String log = BANNER + "\n" + announced + "\n" + jdkModule + "\n" + unmatched + "\n";
    assertThatAgentLog(log)
        .hasInjectedFault("advice-throw", "jdbc")
        .hasInjectedFault("transform-error", "test-probe-jdk");
    assertThatThrownBy(() -> assertThatAgentLog(log).hasInjectedFault("advice-throw", "servlet"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("advice of module servlet throws");
    assertThatThrownBy(
            () -> assertThatAgentLog(log).hasInjectedFault("transform-error", "test-probe"))
        .isInstanceOf(AssertionError.class);
    assertThatThrownBy(() -> assertThatAgentLog(log).hasInjectedFault("helper-nocdef", "jdbc"))
        .isInstanceOf(AssertionError.class);
    assertThatThrownBy(
            () ->
                assertThatAgentLog(log + announced + "\n").hasInjectedFault("advice-throw", "jdbc"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("but found 2");
    assertThatThrownBy(() -> assertThatAgentLog(log).hasInjectedFault("advice-throw.enter", "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
