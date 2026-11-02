/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.leak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.agent.testing.jvm.ClassHistogram;
import io.argos.agent.testing.jvm.JcmdClient;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** T-AG-053 leak checks: heap residency, not-available reporting, WAR redeploy driver. */
@Tag("req:N-02")
class LeakProbeTest {
  static String histogram(long argosBytes) {
    return " num     #instances         #bytes  class name (module)\n"
        + "   1:         1000      "
        + argosBytes
        + "  io.argos.agent.core.trace.Transaction\n"
        + "   2:            1             48  "
        + TomcatRedeployDriver.WEBAPP_LOADER
        + "\n";
  }

  @Test
  @DisplayName(
      "T-AG-053 heap residency: io.argos. bytes <= 64 MB passes, above fails; self-diagnostics"
          + " checks are NOT_AVAILABLE until WO-10, never passed")
  void probe() {
    List<List<String>> commands = new ArrayList<>();
    JcmdClient jcmd =
        new JcmdClient(
            42,
            arguments -> {
              commands.add(arguments);
              return arguments.contains("GC.class_histogram") ? histogram(1_000_000) : "";
            });
    LeakReport report = new LeakProbe(jcmd, SelfDiagnostics.notAvailable()).check();
    assertThat(commands)
        .containsExactly(
            List.of("42", "GC.run"), List.of("42", "GC.run"), List.of("42", "GC.class_histogram"));
    assertThat(report.check(LeakReport.HEAP_RESIDENCY).outcome())
        .isEqualTo(LeakReport.Outcome.PASS);
    assertThat(report.check(LeakReport.HEAP_RESIDENCY).value()).isEqualTo(1_000_000);
    for (String name :
        List.of(LeakReport.THREADLOCAL, LeakReport.ACTIVE_REGISTRY, LeakReport.WEAK_MAP)) {
      assertThat(report.check(name).outcome()).isEqualTo(LeakReport.Outcome.NOT_AVAILABLE);
      assertThat(report.check(name).detail()).contains("WO-10");
    }
    report.assertNoFailure();
    assertThatThrownBy(report::assertAllPassed)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("NOT_AVAILABLE");

    LeakReport.Check over =
        LeakProbe.heapResidency(ClassHistogram.parse(histogram(LeakProbe.HEAP_LIMIT_BYTES + 1)));
    assertThat(over.outcome()).isEqualTo(LeakReport.Outcome.FAIL);
    assertThatThrownBy(() -> new LeakReport(List.of(over)).assertNoFailure())
        .isInstanceOf(AssertionError.class);

    LeakReport reported =
        new LeakProbe(
                jcmd,
                SelfDiagnostics.of(
                    Map.of(
                        "threadlocal_contexts_live", 0L,
                        "active_registry_size", 2L,
                        "weakmap_size", 0L)))
            .check();
    assertThat(reported.check(LeakReport.ACTIVE_REGISTRY).outcome())
        .isEqualTo(LeakReport.Outcome.FAIL);
    assertThat(reported.check(LeakReport.WEAK_MAP).outcome()).isEqualTo(LeakReport.Outcome.PASS);
  }

  @Test
  @DisplayName(
      "T-AG-053 heap residency without a measured value is NOT_AVAILABLE, never PASS: empty or"
          + " unparsable histogram, attached agent without one io.argos.agent. instance")
  void heapResidencyNeedsData() {
    for (String output :
        List.of(
            "",
            "com.sun.tools.attach.AttachNotSupportedException: Unable to open socket file:"
                + " target process 1234 doesn't respond within 10500ms or HotSpot VM not loaded",
            """
             num     #instances         #bytes  class name (module)
            -------------------------------------------------------
            """)) {
      ClassHistogram histogram = ClassHistogram.parse(output);
      assertThat(histogram.rows()).as(output).isEmpty();
      for (boolean attached : new boolean[] {false, true}) {
        LeakReport.Check check = LeakProbe.heapResidency(histogram, attached);
        assertThat(check.outcome()).as(output).isEqualTo(LeakReport.Outcome.NOT_AVAILABLE);
        assertThat(check.detail()).contains("no rows");
      }
      assertThat(LeakProbe.heapResidency(histogram).outcome())
          .isEqualTo(LeakReport.Outcome.NOT_AVAILABLE);
    }
    String noAgent =
        """
         num     #instances         #bytes  class name (module)
           1:         1000          48000  [B (java.base@21)
           2:            3            144  io.argos.sample.boot3.App
        """;
    LeakReport.Check attached = LeakProbe.heapResidency(ClassHistogram.parse(noAgent), true);
    assertThat(attached.outcome()).isEqualTo(LeakReport.Outcome.NOT_AVAILABLE);
    assertThat(attached.detail()).contains("no io.argos.agent. instance");
    // Without the agent, an app's own io.argos. classes are measured as they are.
    assertThat(LeakProbe.heapResidency(ClassHistogram.parse(noAgent), false).outcome())
        .isEqualTo(LeakReport.Outcome.PASS);
    assertThat(LeakProbe.heapResidency(ClassHistogram.parse(histogram(1_000)), true).outcome())
        .isEqualTo(LeakReport.Outcome.PASS);

    // A probe of an attached run whose jcmd answered nothing never passes check 4.
    JcmdClient silent = new JcmdClient(42, arguments -> "");
    LeakReport report = new LeakProbe(silent, SelfDiagnostics.notAvailable(), true).check();
    assertThat(report.check(LeakReport.HEAP_RESIDENCY).outcome())
        .isEqualTo(LeakReport.Outcome.NOT_AVAILABLE);
    assertThatThrownBy(report::assertAllPassed).isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName(
      "WAR redeploy driver: undeploy and deploy through the manager text API, OK - checks,"
          + " webapp loaders and ThreadLocal leak warnings")
  void redeployDriver() throws Exception {
    List<String> calls = new ArrayList<>();
    boolean[] deployed = {true};
    TomcatRedeployDriver driver =
        new TomcatRedeployDriver(
            command -> {
              calls.add(command);
              if (command.equals("list")) {
                return "OK - Listed applications\n/:running:0:ROOT\n"
                    + (deployed[0] ? "/shop:running:0:shop\n" : "");
              }
              deployed[0] = command.startsWith("deploy");
              return command.startsWith("deploy")
                  ? "OK - Deployed application at context path [/shop]\n"
                  : "OK - Undeployed application at context path [/shop]\n";
            });
    Path war = Path.of("build", "shop.war").toAbsolutePath();
    TomcatRedeployDriver.Result result = driver.redeploy("/shop", war, 3);
    assertThat(result.deploys()).isEqualTo(3);
    assertThat(result.undeploys()).isEqualTo(3);
    assertThat(calls).hasSize(9);
    assertThat(calls.get(1)).isEqualTo("undeploy?path=%2Fshop");
    assertThat(calls.get(2))
        .startsWith("deploy?path=%2Fshop&war=file%3A")
        .endsWith("shop.war&update=true");

    TomcatRedeployDriver failing =
        new TomcatRedeployDriver(
            command -> command.equals("list") ? "OK - Listed applications\n" : "FAIL - no war");
    assertThatThrownBy(() -> failing.redeploy("/shop", war, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("FAIL - no war");

    assertThat(TomcatRedeployDriver.webappLoaders(ClassHistogram.parse(histogram(1)))).isEqualTo(1);
    assertThat(
            TomcatRedeployDriver.threadLocalLeakWarnings(
                List.of(
                    "INFO deployed",
                    "SEVERE [main] The web application [shop] created a ThreadLocal with key of"
                        + " type [x] and a value of type [y] but failed to remove it when the web"
                        + " application was stopped. Threads are going to be renewed over time to"
                        + " try and avoid a probable memory leak.")))
        .hasSize(1);
  }
}
