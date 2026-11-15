/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.e2e;

import static io.argos.agent.testing.assertion.TransactionAssert.assertThatTransaction;
import static io.argos.agent.testing.e2e.E2eRuns.EXPORT_WAIT;
import static io.argos.agent.testing.e2e.E2eRuns.releaseJar;
import static io.argos.agent.testing.e2e.E2eRuns.warCombos;
import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.e2e.E2eRuns.Combo;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.log.AgentLogAssert;
import io.argos.agent.testing.log.TomcatLogCheck;
import io.argos.sample.extstub.ExtStub;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * WAR apps in a standalone Tomcat with the release agent jar (AppLauncher WAR mode, 18 §5.2, 18
 * §4.1): every WAR combination of {@code -Pagent.e2e} ({@code shop-war-javax@<8|11|17|21>}, {@code
 * shop-war-jakarta@<17|21|25>}; none by default, so the PR gate does not start a Tomcat). The
 * launcher stages CATALINA_BASE in the run directory, deploys the WAR under each context path and
 * waits for every context's health endpoint.
 *
 * <p>One request to the first context's {@code /api/orders/1} answers 200 and is exactly one
 * transaction with status 200 named {@code <context> GET /api/orders/{id}} (F-A36); the agent log
 * has no ERROR and the console nothing but the applications' own output. Then the Tomcat is stopped
 * through its shutdown port (graceful: the web applications stop and Tomcat's leak detection runs)
 * and its own log ({@code RunningApp.tomcatLogLines()}, judged by {@link TomcatLogCheck}, WO-05
 * r1-new-1) has no WARNING or SEVERE line naming the agent, and no SEVERE line and no ThreadLocal
 * leak warning that the same run without the agent does not log as well (18 §5.9): the WAR of
 * shop-boot3 leaves Netty's {@code InternalThreadLocalMap} of its own WebClient in Tomcat's
 * threads, a leak of the application. The no-agent run starts only when the agent run has such
 * lines. The rest of the WO-12 WAR scenarios are in package {@code servlet} ({@code War*Test}).
 */
@Tag("req:F-A01")
@Tag("req:DSN-18-03")
class WarAppAgentTest {
  private static ExtStub extStub;

  @TempDir Path work;

  @BeforeAll
  static void startExtStub() throws IOException {
    if (!warCombos().isEmpty()) {
      extStub = E2eRuns.startExtStub();
    }
  }

  @AfterAll
  static void stopExtStub() {
    if (extStub != null) {
      extStub.close();
    }
  }

  @TestFactory
  Stream<DynamicTest> warAppsWithAgent() {
    // Without a WAR combination nothing runs: report the factory as skipped, not as passed.
    Assumptions.assumeFalse(
        warCombos().isEmpty(),
        "no WAR combination in -Pagent.e2e (for example shop-war-jakarta@21, shop-war-javax@8)");
    return warCombos().stream()
        .map(
            combo ->
                DynamicTest.dynamicTest(
                    "WAR "
                        + combo
                        + ": standalone Tomcat with the agent, one request is one transaction"
                        + " (status 200, context prefix), no agent ERROR, quiet console; after a"
                        + " graceful stop the Tomcat log names no agent and has no SEVERE line or"
                        + " ThreadLocal leak beyond a run without the agent",
                    () -> run(combo)));
  }

  void run(Combo combo) throws Exception {
    AppLauncher launcher = E2eRuns.launcher(work, extStub, combo, "-war").withAgent(releaseJar());
    String tomcat = launcher.tomcatKey();
    try (RunningApp running = launcher.start()) {
      List<String> contexts = running.app().contextPaths();
      assertThat(contexts).as("web contexts of %s", combo).isNotEmpty().doesNotContain("");
      String path = contexts.get(0) + "/api/orders/1";
      HttpResult orders = running.get(path);
      assertThat(orders.status()).as(running.describe()).isEqualTo(200);
      assertThat(orders.body()).contains("\"id\":1");

      // The recorded path may or may not carry the context (WO-12): match its end.
      Predicate<TransactionRecord> ordersPath =
          tx -> tx.path() != null && tx.path().endsWith("/api/orders/1");
      running.transactions().await(ordersPath, 1, EXPORT_WAIT);
      Thread.sleep(300);
      List<TransactionRecord> recorded = running.transactions().read(ordersPath);
      assertThat(recorded).as("transactions of GET %s%n%s", path, running.describe()).hasSize(1);
      assertThatTransaction(recorded.get(0))
          .hasStatus(200)
          .hasService(contexts.get(0) + " GET /api/orders/{id}");

      String log = running.agentLog();
      assertThatAgentLog(log).hasStartBanner().hasNoErrors();
      AgentLogAssert.assertQuietConsole(running.stdoutLines(), running.stderrLines());

      // WO-05 r1-new-1: Tomcat's own log after a graceful stop (the web applications are stopped,
      // so Tomcat's leak detection has run): no SEVERE, no WARNING naming the agent, no ThreadLocal
      // leak warning (18 §5.9, F-A36).
      long stopStart = System.nanoTime();
      running.gracefulStop();
      long stopMs = (System.nanoTime() - stopStart) / 1_000_000;
      assertThat(running.isAlive()).as("Tomcat stopped by its shutdown command").isFalse();
      List<String> tomcatLog = running.tomcatLogLines();
      assertThat(tomcatLog)
          .as("Tomcat log of %s", combo)
          .anyMatch(line -> line.contains("Stopping service [Catalina]"));
      TomcatLogCheck.Findings findings = TomcatLogCheck.judge(tomcatLog);
      assertThat(findings.agentWarnings()).as(findings.toString()).isEmpty();
      String baselineNote = "";
      if (!findings.clean()) {
        // The application's own SEVERE records and leak warnings (shop-war-jakarta: Netty's
        // InternalThreadLocalMap of the app's WebClient) are not the agent's: compare with the same
        // run without the agent and judge what the agent adds.
        TomcatLogCheck.Findings baseline = withoutAgent(combo, path);
        baselineNote = ", without the agent " + baseline;
        findings = findings.beyond(baseline);
      }
      assertThat(findings.severe()).as(findings + baselineNote).isEmpty();
      assertThat(findings.threadLocalLeaks()).as(findings + baselineNote).isEmpty();
      assertThatAgentLog(running.agentLog()).hasNoErrors();
      System.out.println(
          "[e2e-run] "
              + combo
              + " | WAR on "
              + tomcat
              + " "
              + running.app().tomcats().get(tomcat)
              + ", contexts "
              + contexts
              + " | port "
              + running.spawnToPortMillis()
              + " ms, ready "
              + running.spawnToReadyMillis()
              + " ms | GET "
              + path
              + " -> 1 transaction, recorded path "
              + recorded.get(0).path()
              + ", service "
              + recorded.get(0).service()
              + " | graceful stop "
              + stopMs
              + " ms, Tomcat log "
              + tomcatLog.size()
              + " lines clean"
              + (baselineNote.isEmpty() ? "" : " beyond the no-agent run")
              + " | PASS");
    }
  }

  /**
   * The Tomcat log findings of the same WAR app, Tomcat and request without the agent, after a
   * graceful stop: what the application logs on its own.
   */
  TomcatLogCheck.Findings withoutAgent(Combo combo, String path) throws Exception {
    try (RunningApp baseline =
        E2eRuns.launcher(work, extStub, combo, "-noag").withoutAgent().start()) {
      assertThat(baseline.get(path).status()).isEqualTo(200);
      baseline.gracefulStop();
      TomcatLogCheck.Findings findings = TomcatLogCheck.judge(baseline.tomcatLogLines());
      System.out.println("[e2e] " + combo + ": Tomcat log without the agent: " + findings);
      return findings;
    }
  }
}
