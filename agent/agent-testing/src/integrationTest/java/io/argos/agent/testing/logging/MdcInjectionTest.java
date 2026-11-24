/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.logging;

import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.logging.LoggingRuns.Combo;
import io.argos.agent.testing.logging.LoggingRuns.Run;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * T-AG-065 (F-A20, 10 §6.9), with the release agent jar and a TLS FakeCollector: the {@code
 * argos.trace_id} and {@code argos.tx_id} printed in an application log line are the trace id and
 * the transaction id the collector received for the request that logged it, also after the
 * application's {@code MDC.clear()} and from an application executor thread (propagated by the
 * agent); log lines written without a transaction (application start) carry no Argos keys.
 *
 * <ul>
 *   <li>Logback through SLF4J: the shop jar apps of {@code -Pagent.e2e} ({@code shop-boot2@8} is
 *       Logback 1.2, {@code shop-boot3@21} Logback 1.5), {@code GET /api/mdc-log} with the pattern
 *       {@code %5p [%X]}.
 *   <li>Log4j 1.2.17 and reload4j 1.2.26: the Spring-free logging fixture (embedded Tomcat 11, JDK
 *       21), {@code GET /log}.
 *   <li>Log4j 2.25.5 (the fixture): with the garbage-free thread context map the keys are printed
 *       like the others; with Log4j's default map of a web application the injector returns a
 *       frozen map, which the agent never changes (10 §18 U-13, logging module README), and the
 *       result is reported as {@code [wo18-log4j2]} without an assertion (PM decision pending).
 * </ul>
 */
@Tag("req:F-A20")
class MdcInjectionTest {

  @TestFactory
  Stream<DynamicTest> mdcInjection() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : LoggingRuns.shopCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-065 "
                  + combo
                  + " Logback: /api/mdc-log plain, after MDC.clear() and from the app executor print"
                  + " the collector's trace and tx id; start-up lines carry no Argos keys",
              () -> logback(combo)));
    }
    for (String flavor : List.of("log4j1", "reload4j")) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-065 "
                  + flavor
                  + " fixture on JDK 21: plain, after MDC.clear() and from an executor thread print"
                  + " the collector's trace and tx id; the start line carries none",
              () -> fixture(flavor, List.of(), true)));
    }
    tests.add(
        DynamicTest.dynamicTest(
            "T-AG-065 log4j2 fixture on JDK 21 with the garbage-free thread context map: the"
                + " collector's trace and tx id in every line",
            () ->
                fixture(
                    "log4j2",
                    List.of(
                        "-Dlog4j2.is.webapp=false", "-Dlog4j2.garbagefree.threadContextMap=true"),
                    true)));
    tests.add(
        DynamicTest.dynamicTest(
            "T-AG-065 log4j2 fixture on JDK 21 with Log4j's default (web application) context"
                + " map: frozen context data is never changed; reported as [wo18-log4j2] (10 U-13)",
            () -> fixture("log4j2", List.of(), false)));
    return tests.stream();
  }

  private static void logback(Combo combo) throws Exception {
    SampleApp app = SampleApp.named(combo.app());
    String name = "wo18-mdc-" + combo.app() + "-jdk" + combo.jdk();
    try (Run run = Run.start(name, app, combo.jdk(), Map.of(), true, List.of(), List.of())) {
      List<String> startup = new ArrayList<>(run.app.stdoutLines());
      assertThat(startup).as("start-up output of %s", name).isNotEmpty();
      assertThat(startup)
          .as("start-up log lines (no transaction) of %s", name)
          .noneMatch(line -> line.contains("argos.trace_id") || line.contains("argos.tx_id"));
      for (String query : List.of("", "&clear=true", "&async=true")) {
        String tag = LoggingRuns.tag("lb");
        TransactionRecord tx = run.request("/api/mdc-log?tag=" + tag + query, "/api/mdc-log");
        String line = run.awaitStdout("tag=" + tag);
        assertThat(LoggingRuns.group(LoggingRuns.LOGBACK_TRACE, line))
            .as("argos.trace_id in %s (%s)", line, query)
            .isEqualTo(tx.traceId());
        assertThat(LoggingRuns.group(LoggingRuns.LOGBACK_TX, line))
            .as("argos.tx_id in %s (%s)", line, query)
            .isEqualTo(LoggingRuns.txHex(tx));
        System.out.println(
            "[wo18-mdc] " + name + " mode " + (query.isEmpty() ? "plain" : query) + ": " + line);
      }
      assertThatAgentLog(run.app.agentLog()).hasStartBanner().hasNoErrors();
    }
  }

  private static void fixture(String flavor, List<String> jvmOptions, boolean expectKeys)
      throws Exception {
    String name = "wo18-mdc-" + flavor + (jvmOptions.isEmpty() ? "" : "-garbagefree");
    String framework = flavor.equals("log4j2") ? "log4j2" : "log4j1";
    try (Run run =
        Run.start(
            name,
            LoggingRuns.fixture(flavor),
            21,
            Map.of(),
            true,
            List.of(framework),
            jvmOptions)) {
      String start = run.awaitStdout("fixture-start");
      assertThat(start)
          .as("start line (no transaction): fixture <trace>|<tx>|<user>|message")
          .startsWith("fixture |||");
      for (String query : List.of("", "&clear=true", "&async=true")) {
        String tag = LoggingRuns.tag(flavor);
        TransactionRecord tx = run.request("/log?tag=" + tag + query, "/log");
        String line = run.awaitStdout("tag=" + tag);
        String[] fields = line.substring("fixture ".length()).split("\\|", -1);
        System.out.println(
            "[wo18-"
                + (flavor.equals("log4j2") ? "log4j2" : "mdc")
                + "] "
                + name
                + " mode "
                + (query.isEmpty() ? "plain" : query)
                + ": "
                + line
                + " (collector trace "
                + tx.traceId()
                + " tx "
                + LoggingRuns.txHex(tx)
                + ")");
        if (expectKeys) {
          assertThat(fields[0])
              .as("argos.trace_id in %s (%s)", line, query)
              .isEqualTo(tx.traceId());
          assertThat(fields[1])
              .as("argos.tx_id in %s (%s)", line, query)
              .isEqualTo(LoggingRuns.txHex(tx));
        } else {
          assertThat(fields[0].isEmpty() || fields[0].equals(tx.traceId()))
              .as("Log4j 2 default map: no key or the right one, never another id (%s)", line)
              .isTrue();
        }
      }
      assertThatAgentLog(run.app.agentLog()).hasStartBanner().hasNoErrors();
    }
  }
}
