/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.matrix;

import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.jvm.JcmdClient;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.leak.TomcatRedeployDriver;
import io.argos.agent.testing.log.TomcatLogCheck;
import io.argos.agent.testing.matrix.MatrixRuns.Combo;
import io.argos.sample.extstub.ExtStub;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * T-AG-053 at the matrix level, WAR redeploy (18 §5.9, proposed detailed ID T-AG-053w, WO-50) on
 * the row's WAR app ({@code shop-war-javax} on Tomcat 9, {@code shop-war-jakarta} on Tomcat 10.1 or
 * 11 by {@code argos.test.matrix.tomcat}) with the release agent: the staged Tomcat runs its
 * manager app ({@code AppLauncher.tomcatManager(true)}), and {@link TomcatRedeployDriver} undeploys
 * and deploys the first web application {@value #CYCLES} times through the manager's text
 * interface. Then:
 *
 * <ul>
 *   <li>the redeployed application still answers and is still measured (one transaction named
 *       {@code <context> GET /api/orders/{id}}, F-A36);
 *   <li>after {@code GC.run} twice, {@code ParallelWebappClassLoader} instances are as many as
 *       before the cycles (one per deployed web application: the redeployed one, the other contexts
 *       and the manager), so no undeployed application's class loader is kept; when the application
 *       itself keeps some (its own leaks), the agent run keeps no more than the same cycles without
 *       the agent (C-162 (1), as for the log);
 *   <li>after a graceful stop, Tomcat's log names no agent, and has no SEVERE record and no
 *       ThreadLocal leak warning beyond the same run without the agent (ADR-0010 C-162 (1): the
 *       sample apps' own leaks, such as Netty's {@code InternalThreadLocalMap} of shop-war-jakarta,
 *       are the baseline, judged by {@link TomcatLogCheck.Findings#beyond}); the no-agent run does
 *       the same cycles and starts only when the agent run is not clean;
 *   <li>the agent log has no ERROR.
 * </ul>
 *
 * Results: {@code [mx-redeploy]} and {@code <resultDir>/redeploy.properties}.
 */
@Tag("req:F-A15")
@Tag("req:F-A36")
@Tag("req:N-02")
class WarRedeployTest {
  static final int CYCLES = 50;

  @TestFactory
  Stream<DynamicTest> redeploy() {
    Combo combo = MatrixRuns.firstWarCombo();
    return Stream.of(
        DynamicTest.dynamicTest(
            MatrixRuns.comboId()
                + " T-AG-053/T-AG-053w "
                + combo
                + ": "
                + CYCLES
                + " deploy/undeploy cycles through the Tomcat manager keep one web application"
                + " class loader per deployment, the agent measures the new deployment, Tomcat's"
                + " log names no agent and adds no SEVERE or ThreadLocal leak beyond the no-agent"
                + " run",
            () -> run(combo)));
  }

  /** What one run (with or without the agent) found. */
  record Outcome(
      TomcatRedeployDriver.Result redeploy,
      long loadersBefore,
      long loadersAfter,
      TomcatLogCheck.Findings findings,
      long redeployMs,
      String tomcat) {}

  void run(Combo combo) throws Exception {
    try (MatrixRuns.TempTree tree = MatrixRuns.TempTree.create("mx-redeploy-");
        ExtStub stub = MatrixRuns.startExtStub()) {
      Path work = tree.root();
      Outcome agent = cycles(combo, stub, work.resolve("agent"), true);
      TomcatLogCheck.Findings findings = agent.findings();
      assertThat(findings.agentWarnings()).as(findings.toString()).isEmpty();
      long kept = agent.loadersAfter() - agent.loadersBefore();
      long keptWithoutAgent = 0;
      String baselineNote = "";
      if (!findings.clean() || kept > 0) {
        Outcome noAgent = cycles(combo, stub, work.resolve("noagent"), false);
        keptWithoutAgent = Math.max(0, noAgent.loadersAfter() - noAgent.loadersBefore());
        baselineNote =
            ", without the agent "
                + noAgent.findings()
                + ", loaders "
                + noAgent.loadersBefore()
                + " -> "
                + noAgent.loadersAfter();
        findings = findings.beyond(noAgent.findings());
      }
      assertThat(findings.severe())
          .as("C-162 (1) SEVERE beyond the no-agent run: %s%s", findings, baselineNote)
          .isEmpty();
      assertThat(findings.threadLocalLeaks())
          .as("ThreadLocal leak warnings beyond the no-agent run: %s%s", findings, baselineNote)
          .isEmpty();
      assertThat(kept)
          .as(
              "%s kept after %d cycles and GC.run x2 (before %d, after %d)%s",
              TomcatRedeployDriver.WEBAPP_LOADER,
              CYCLES,
              agent.loadersBefore(),
              agent.loadersAfter(),
              baselineNote)
          .isLessThanOrEqualTo(keptWithoutAgent);
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("app", combo.app());
      values.put("jdk", combo.jdk());
      values.put("tomcat", agent.tomcat());
      values.put("cycles", CYCLES);
      values.put("deploys", agent.redeploy().deploys());
      values.put("undeploys", agent.redeploy().undeploys());
      values.put("webapp_loaders_before", agent.loadersBefore());
      values.put("webapp_loaders_after", agent.loadersAfter());
      values.put("webapp_loaders_kept_without_agent", keptWithoutAgent);
      values.put("redeploy_ms", agent.redeployMs());
      values.put("judged_against_no_agent_run", !baselineNote.isEmpty());
      MatrixRuns.result("redeploy", values);
    }
  }

  Outcome cycles(Combo combo, ExtStub stub, Path dir, boolean withAgent) throws Exception {
    AppLauncher launcher =
        AppLauncher.of(combo.sampleApp(), Jdk.of(combo.jdk()))
            .withEnv("SAMPLE_EXT_URL", stub.baseUri().toString())
            .workDir(dir)
            .readyTimeout(Duration.ofMinutes(4))
            .tomcatManager(true);
    if (withAgent) {
      launcher.withAgent(MatrixRuns.releaseJar());
    } else {
      launcher.withoutAgent();
    }
    String tomcat = launcher.tomcatKey();
    String name = "redeploy-" + combo.app() + "-" + combo.jdk() + (withAgent ? "" : "-noagent");
    RunningApp app = launcher.start();
    try {
      String context = app.app().contextPaths().get(0);
      String orders = context + "/api/orders/1";
      assertThat(app.get(orders).status()).as(app.describe()).isEqualTo(200);
      JcmdClient jcmd = app.jcmd();
      long before = loadersAfterGc(jcmd, null);
      long start = System.nanoTime();
      TomcatRedeployDriver.Result result =
          new TomcatRedeployDriver(TomcatRedeployDriver.of(app))
              .redeploy(context, app.app().artifact(), CYCLES);
      long redeployMs = (System.nanoTime() - start) / 1_000_000;
      assertThat(result.deploys()).isEqualTo(CYCLES);
      assertThat(result.undeploys()).isEqualTo(CYCLES);
      awaitHealthy(app, context);
      HttpResult answer = app.get(orders);
      assertThat(answer.status()).as(answer.body()).isEqualTo(200);
      if (withAgent) {
        Predicate<TransactionRecord> ordersTx =
            tx -> tx.path() != null && MatrixRuns.bare(tx.path()).endsWith("/api/orders/1");
        List<TransactionRecord> txs = app.transactions().await(ordersTx, 2, MatrixRuns.WAIT);
        assertThat(txs).as("the redeployed application is measured%n%s", app.describe()).hasSize(2);
        assertThat(txs.get(1).service()).isEqualTo(context + " GET /api/orders/{id}");
        assertThat(txs.get(1).status()).isEqualTo(200);
      }
      long after = loadersAfterGc(jcmd, before);
      app.gracefulStop();
      assertThat(app.isAlive()).as("Tomcat stopped by its shutdown command").isFalse();
      List<String> tomcatLog = app.tomcatLogLines();
      TomcatLogCheck.Findings findings = TomcatLogCheck.judge(tomcatLog);
      if (withAgent) {
        assertThatAgentLog(app.agentLog()).hasStartBanner().hasNoErrors();
      }
      System.out.println(
          "[mx-redeploy] "
              + name
              + " on "
              + tomcat
              + ": "
              + CYCLES
              + " cycles in "
              + redeployMs
              + " ms, "
              + TomcatRedeployDriver.WEBAPP_LOADER
              + " "
              + before
              + " -> "
              + after
              + ", "
              + findings);
      return new Outcome(result, before, after, findings, redeployMs, tomcat);
    } finally {
      app.close();
      MatrixRuns.keepEvidence(name, app);
    }
  }

  /**
   * {@code GC.run} twice, then the web application class loaders of a histogram; at least one
   * round, up to five (1 s apart) while the count is above {@code target} (a stopped loader becomes
   * unreachable only after Tomcat's own clean-up threads have ended). Without a target ({@code
   * null}) one round.
   */
  static long loadersAfterGc(JcmdClient jcmd, Long target) throws InterruptedException {
    long count = 0;
    for (int round = 0; round < (target == null ? 1 : 5); round++) {
      if (round > 0) {
        Thread.sleep(1_000);
      }
      jcmd.gcRun();
      jcmd.gcRun();
      count = TomcatRedeployDriver.webappLoaders(jcmd.classHistogram());
      if (target != null && count <= target) {
        break;
      }
    }
    return count;
  }

  /** Waits until the redeployed context answers its health endpoint. */
  static void awaitHealthy(RunningApp app, String context) throws Exception {
    long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
    while (true) {
      HttpResult health = app.get(context + "/actuator/health");
      if (health.status() == 200 || System.nanoTime() > deadline) {
        assertThat(health.status())
            .as("%s/actuator/health after the cycles", context)
            .isEqualTo(200);
        return;
      }
      Thread.sleep(250);
    }
  }
}
