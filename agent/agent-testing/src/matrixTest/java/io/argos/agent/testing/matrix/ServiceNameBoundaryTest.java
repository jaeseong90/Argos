/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.matrix;

import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.matrix.MatrixRuns.Combo;
import io.argos.sample.extstub.ExtStub;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * Service-name boundaries of ADR-0010 C-164 end to end on the row's jar app (10 §6.2, D-04, C-48,
 * WO-50), with Spring Boot's default {@code spring.web.resources.add-mappings=true} (the sample
 * apps turn it off; the run sets {@code SPRING_WEB_RESOURCES_ADD_MAPPINGS=true}):
 *
 * <ul>
 *   <li>proposed T-AG-010s, C-164 (1): requests that no controller maps reach the static resource
 *       handler of {@code /**} ({@code ResourceHttpRequestHandler}); their 404s are named by that
 *       pattern, {@code GET /**}, one service name for every such path (cardinality bound), never
 *       {@code /error};
 *   <li>proposed T-AG-010e, C-164 (2): a client that calls {@code GET /error} itself (a REQUEST
 *       dispatch) gets a transaction named after its own path, {@code GET /error}, with the status
 *       it was answered; ERROR dispatches still never rename a transaction (C-48).
 * </ul>
 */
@Tag("req:D-04")
@Tag("req:F-A29")
@Tag("req:F-A01")
class ServiceNameBoundaryTest {
  static final List<String> UNMAPPED =
      List.of("/api/not-found-x", "/no/such/page", "/api/not-found-y", "/deep/a/b/c/d");

  private static RunningApp app;
  private static ExtStub stub;
  private static Path work;

  @AfterAll
  static void stop() {
    if (app != null) {
      app.close();
      MatrixRuns.keepEvidence("names", app);
      app = null;
    }
    if (stub != null) {
      stub.close();
      stub = null;
    }
    MatrixRuns.deleteTree(work);
    work = null;
  }

  @TestFactory
  Stream<DynamicTest> serviceNames() {
    Combo combo = MatrixRuns.firstJarCombo();
    String prefix = MatrixRuns.comboId() + " ";
    return Stream.of(
        DynamicTest.dynamicTest(
            prefix
                + "T-AG-010s C-164 (1) "
                + combo
                + ": with add-mappings=true, 404s of the /** resource handler are one service GET"
                + " /**",
            () -> staticHandler(combo)),
        DynamicTest.dynamicTest(
            prefix
                + "T-AG-010e C-164 (2) "
                + combo
                + ": a direct GET /error is named after its own path",
            () -> directError(combo)));
  }

  static RunningApp app(Combo combo) throws Exception {
    if (app == null) {
      stub = MatrixRuns.startExtStub();
      work = Files.createTempDirectory("mx-names-");
      app =
          AppLauncher.of(combo.sampleApp(), Jdk.of(combo.jdk()))
              .withEnv("SAMPLE_EXT_URL", stub.baseUri().toString())
              .withEnv("SPRING_WEB_RESOURCES_ADD_MAPPINGS", "true")
              .workDir(work.resolve("names-" + combo.app() + "-" + combo.jdk()))
              .readyTimeout(Duration.ofMinutes(4))
              .withAgent(MatrixRuns.releaseJar())
              .start();
    }
    assertThat(app.isAlive()).as(app.describe()).isTrue();
    return app;
  }

  static void staticHandler(Combo combo) throws Exception {
    RunningApp running = app(combo);
    Set<String> paths = new TreeSet<>(UNMAPPED);
    for (String path : UNMAPPED) {
      HttpResult answer = running.get(path);
      assertThat(answer.status()).as("GET %s: %s", path, answer.body()).isEqualTo(404);
    }
    Predicate<TransactionRecord> unmapped = tx -> paths.contains(MatrixRuns.bare(tx.path()));
    running.transactions().await(unmapped, UNMAPPED.size(), MatrixRuns.WAIT);
    Thread.sleep(1_000);
    List<TransactionRecord> txs = running.transactions().read(unmapped);
    assertThat(txs)
        .as("one transaction per request%n%s", running.describe())
        .hasSize(UNMAPPED.size());
    for (TransactionRecord tx : txs) {
      assertThat(tx.service()).as("C-164 (1): %s", tx).isEqualTo("GET /**");
      assertThat(tx.status()).isEqualTo(404);
      assertThat(tx.error()).as("a 404 is not an error (D-21)").isFalse();
    }
    assertThat(running.transactions().read()).noneMatch(MatrixRuns::isErrorTransaction);
    assertThatAgentLog(running.agentLog()).hasNoErrors();
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("app", combo.app());
    values.put("jdk", combo.jdk());
    values.put("unmapped_paths", UNMAPPED.size());
    values.put("service", txs.get(0).service());
    MatrixRuns.result("names-static", values);
  }

  static void directError(Combo combo) throws Exception {
    RunningApp running = app(combo);
    HttpResult answer = running.get("/error");
    Predicate<TransactionRecord> error = tx -> "/error".equals(MatrixRuns.bare(tx.path()));
    running.transactions().await(error, 1, MatrixRuns.WAIT);
    Thread.sleep(1_000);
    List<TransactionRecord> txs = running.transactions().read(error);
    assertThat(txs).as("one transaction for GET /error%n%s", running.describe()).hasSize(1);
    TransactionRecord tx = txs.get(0);
    assertThat(tx.service()).as("C-164 (2): %s", tx).isEqualTo("GET /error");
    assertThat(tx.status()).as("the status the client got").isEqualTo(answer.status());
    assertThatAgentLog(running.agentLog()).hasNoErrors();
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("app", combo.app());
    values.put("jdk", combo.jdk());
    values.put("status", answer.status());
    values.put("service", tx.service());
    MatrixRuns.result("names-error", values);
  }
}
