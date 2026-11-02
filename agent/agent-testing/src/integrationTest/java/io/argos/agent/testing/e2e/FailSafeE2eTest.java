/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.e2e;

import static io.argos.agent.testing.assertion.TransactionAssert.assertThatTransaction;
import static io.argos.agent.testing.e2e.E2eRuns.EXPORT_WAIT;
import static io.argos.agent.testing.e2e.E2eRuns.jarCombos;
import static io.argos.agent.testing.e2e.E2eRuns.releaseJar;
import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.baseline.TransformLog;
import io.argos.agent.testing.e2e.E2eRuns.Combo;
import io.argos.agent.testing.export.FileExporterReader;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.log.AgentLogAssert;
import io.argos.agent.testing.log.AppOutput;
import io.argos.sample.extstub.ExtStub;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fail-safe scenarios (18 §5.7) on the first jar combination of {@code -Pagent.e2e} (default {@code
 * shop-boot3@21}) with the release agent jar ({@link E2eRuns}): advice fault injection (T-AG-040)
 * for the servlet module and for the jdbc, executor and httpclient-jdk modules, injected
 * transformation errors of jdbc (T-AG-041) and missing jdbc helpers ({@code helper-nocdef},
 * T-AG-040). The app answers as without the agent in every case.
 */
@Tag("req:F-A15")
class FailSafeE2eTest {
  private static ExtStub extStub;

  @TempDir Path work;

  @BeforeAll
  static void startExtStub() throws IOException {
    extStub = E2eRuns.startExtStub();
  }

  @AfterAll
  static void stopExtStub() {
    if (extStub != null) {
      extStub.close();
    }
  }

  @TestFactory
  Stream<DynamicTest> failSafeOnFirstCombination() {
    return jarCombos().stream()
        .limit(1)
        .flatMap(
            combo ->
                Stream.of(
                    DynamicTest.dynamicTest(
                        "T-AG-040 (PoC) " + combo + ": servlet advice throws, the app answers 200",
                        () -> adviceFault(combo)),
                    DynamicTest.dynamicTest(
                        "T-AG-040 (PoC) "
                            + combo
                            + ": jdbc, executor and httpclient-jdk advice throw, the app answers"
                            + " 2xx",
                        () -> dataModuleFaults(combo)),
                    DynamicTest.dynamicTest(
                        "T-AG-041 "
                            + combo
                            + ": jdbc transformations fail, jdbc disabled once, servlet still"
                            + " records",
                        () -> jdbcTransformErrors(combo)),
                    DynamicTest.dynamicTest(
                        "T-AG-040 "
                            + combo
                            + ": jdbc helpers missing (helper-nocdef), the app"
                            + " answers 200",
                        () -> jdbcHelpersMissing(combo))));
  }

  AppLauncher launcher(Combo combo, String runName) {
    return E2eRuns.launcher(work, extStub, combo, runName);
  }

  /**
   * T-AG-040 (PoC) with real advice: {@code argos.test.faults=advice-throw:servlet} makes the enter
   * advice of every servlet/filter call throw; the inlined exception handler counts it and the
   * request completes normally (no transaction is recorded).
   */
  void adviceFault(Combo combo) throws Exception {
    try (RunningApp running =
        launcher(combo, "-fault")
            .withAgent(releaseJar())
            .agentConfig("test.faults", "advice-throw:servlet")
            .start()) {
      for (int i = 0; i < 3; i++) {
        HttpResult result = running.get("/api/orders/1");
        assertThat(result.status()).as(running.describe()).isEqualTo(200);
        assertThat(result.body()).contains("\"id\":1");
      }
      Thread.sleep(1000);
      assertThat(running.transactions().read()).isEmpty();
      assertThat(running.agentLog())
          .contains("argos.test.faults: advice of module servlet throws")
          .contains("advice error in module 1");
      assertThatAgentLog(running.agentLog()).hasInjectedFault("advice-throw", "servlet");
      assertThat(AppOutput.unexpectedStderrLines(running.stderrLines()))
          .as(running.describe())
          .isEmpty();
    }
  }

  /**
   * T-AG-040 (PoC) for the data modules: every advice of jdbc, executor (JDK class) and
   * httpclient-jdk (JDK class) throws; the requests that go through them answer 2xx with the same
   * bodies, the servlet module still records the transactions, and they hold no SQL, HTTP or ASYNC
   * step.
   */
  void dataModuleFaults(Combo combo) throws Exception {
    try (RunningApp running =
        launcher(combo, "-fault-data")
            .withAgent(releaseJar())
            .agentConfig(
                "test.faults",
                "advice-throw:jdbc,advice-throw:executor,advice-throw:httpclient-jdk")
            .agentConfig("async.step", "true")
            .start()) {
      HttpResult orders = running.get("/api/orders/1");
      assertThat(orders.status()).as(running.describe()).isEqualTo(200);
      assertThat(orders.body()).contains("\"id\":1");
      HttpResult pay = running.get("/api/pay/1?client=hurl");
      assertThat(pay.status()).isEqualTo(200);
      assertThat(pay.body()).contains("\"outcome\":\"OK\"");
      HttpResult executor = running.get("/api/async/executor");
      assertThat(executor.status()).isEqualTo(200);
      assertThat(executor.body()).contains("\"removed\":true");

      FileExporterReader transactions = running.transactions();
      transactions.await(FileExporterReader.path("/api/async/executor"), 1, EXPORT_WAIT);
      List<TransactionRecord> all = transactions.read();
      assertThat(all).hasSize(3);
      for (TransactionRecord tx : all) {
        assertThat(tx.steps()).as(tx.toString()).isEmpty();
        assertThat(tx.status()).isEqualTo(200);
      }
      assertThat(running.agentLog())
          .contains("argos.test.faults: advice of module jdbc throws")
          .contains("advice error in module 3")
          .contains("advice error in module 6")
          .contains("advice error in module 11");
      assertThatAgentLog(running.agentLog())
          .hasInjectedFault("advice-throw", "jdbc")
          .hasInjectedFault("advice-throw", "executor")
          .hasInjectedFault("advice-throw", "httpclient-jdk");
      assertThat(AppOutput.unexpectedStderrLines(running.stderrLines()))
          .as(running.describe())
          .isEmpty();
      System.out.println(
          "[e2e-run] " + combo + " | fault jdbc,executor,httpclient-jdk | 3 requests 2xx, 0 steps");
    }
  }

  /**
   * T-AG-041 on a real app: {@code argos.test.faults=transform-error:jdbc} fails every jdbc
   * transformation (Hikari and H2 classes stay unchanged); at {@code
   * instrumentation.transform-error-threshold=5} jdbc is disabled with exactly one WARN; servlet
   * and Spring MVC still record the request (200, service name), with no SQL step.
   */
  void jdbcTransformErrors(Combo combo) throws Exception {
    try (RunningApp running =
        launcher(combo, "-transform-error")
            .withAgent(releaseJar())
            .agentConfig("test.faults", "transform-error:jdbc")
            .agentConfig("instrumentation.transform-error-threshold", "5")
            .start()) {
      HttpResult orders = running.get("/api/orders/1");
      assertThat(orders.status()).as(running.describe()).isEqualTo(200);
      assertThat(orders.body()).contains("\"id\":1");
      List<TransactionRecord> recorded =
          running.transactions().await(FileExporterReader.path("/api/orders/1"), 1, EXPORT_WAIT);
      assertThat(recorded).hasSize(1);
      assertThatTransaction(recorded.get(0))
          .hasService("GET /api/orders/{id}")
          .hasStatus(200)
          .hasStepCount("SQL", 0);
      TransformLog log =
          running.awaitTransformLog(
              l -> l.isComplete() && !l.transformed().classesOf("spring-webmvc").isEmpty(),
              Duration.ofSeconds(10));
      assertThat(log.onErrorCount()).as(log.failures().toString()).isGreaterThanOrEqualTo(5);
      assertThat(log.classCircularityErrorCount()).isZero();
      assertThat(log.failures())
          .isNotEmpty()
          .allSatisfy(failure -> assertThat(failure.modules()).containsExactly("jdbc"));
      assertThat(log.transformed().classesOf("jdbc")).isEmpty();
      assertThat(log.transformed().classesOf("servlet")).isNotEmpty();
      assertThatAgentLog(running.agentLog())
          .hasNoErrors()
          .contains("argos.test.faults: transformations of module jdbc fail")
          .hasInjectedFault("transform-error", "jdbc")
          .hasWarningCount("module jdbc disabled after 5 transformation errors", 1);
      AgentLogAssert.assertQuietConsole(running.stdoutLines(), running.stderrLines());
    }
  }

  /**
   * {@code argos.test.faults=helper-nocdef:jdbc}: the jdbc advice is inlined but its helper never
   * reaches the application's class loader; every jdbc advice call meets a {@code
   * NoClassDefFoundError}, which is suppressed and counted; the app answers 200 with the same body
   * and the servlet module still records the transaction (no SQL step).
   */
  void jdbcHelpersMissing(Combo combo) throws Exception {
    try (RunningApp running =
        launcher(combo, "-helper-nocdef")
            .withAgent(releaseJar())
            .agentConfig("test.faults", "helper-nocdef:jdbc")
            .start()) {
      for (int i = 0; i < 3; i++) {
        HttpResult orders = running.get("/api/orders/1");
        assertThat(orders.status()).as(running.describe()).isEqualTo(200);
        assertThat(orders.body()).contains("\"id\":1");
      }
      List<TransactionRecord> recorded =
          running.transactions().await(FileExporterReader.path("/api/orders/1"), 3, EXPORT_WAIT);
      assertThat(recorded).hasSize(3);
      for (TransactionRecord tx : recorded) {
        assertThatTransaction(tx).hasStatus(200).hasStepCount("SQL", 0);
      }
      assertThat(running.agentLog())
          .contains("argos.test.faults: helper classes of module jdbc are not injected")
          .contains("advice error in module 3");
      TransformLog log =
          running.awaitTransformLog(
              l -> l.isComplete() && !l.transformed().classesOf("jdbc").isEmpty(),
              Duration.ofSeconds(10));
      assertThat(log.onErrorCount()).isZero();
      assertThatAgentLog(running.agentLog())
          .hasNoErrors()
          .hasInjectedFault("helper-nocdef", "jdbc");
      AgentLogAssert.assertQuietConsole(running.stdoutLines(), running.stderrLines());
    }
  }
}
