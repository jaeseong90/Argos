/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.matrix;

import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.baseline.TransformList;
import io.argos.agent.testing.baseline.TransformLog;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.export.TransactionRecord.StepRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.log.AppOutput;
import io.argos.agent.testing.matrix.MatrixRuns.Combo;
import io.argos.sample.extstub.ExtStub;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * Coexistence with other Java agents and devtools (18 §6.4, T-MX-110~113, proposed detailed IDs
 * T-MX-110a~e, WO-50). The case is the row's {@code argos.test.matrix.coexist}:
 *
 * <ul>
 *   <li>{@code jacoco-before} / {@code jacoco-after} (T-MX-110a/b): the JaCoCo agent ({@code
 *       jacocoagent-*.jar}) before or after {@code -javaagent:argos-agent.jar};
 *   <li>{@code otel-before} / {@code otel-after} (T-MX-110c/d): the OpenTelemetry Java agent
 *       ({@code opentelemetry-javaagent-*.jar}, exporters off, its own logging off);
 *   <li>{@code devtools} (T-MX-110e): shop-boot3 with {@code spring-boot-devtools} ({@code
 *       argos.test.sampleApp.shop-boot3-devtools}) and {@code
 *       -Dspring.devtools.restart.enabled=true}.
 * </ul>
 *
 * The agent jars come from {@code argos.test.matrix.agentsDir} ({@code
 * test/matrix/fetch-coexist-agents.sh}); without them the case is skipped with the reason. One run
 * with the Argos agent alone and one with the other agent (or devtools) do the same requests. The
 * verdict: the application starts; both runs make exactly one transaction per request (the same
 * count); the transformed classes match the coexistence baseline {@code
 * <app>-<jdk>-coexist-<kind>.txt}; the agent log has no ERROR and the console nothing from the
 * Argos agent; every outgoing call reaches ext-stub with one W3C {@code traceparent}: with
 * OpenTelemetry it is the one OpenTelemetry created (not the Argos trace id) and the Argos HTTP
 * step records it unchanged (F-A32, 10 §8: a {@code traceparent} already on the request is kept;
 * with OpenTelemetry attached first the step keeps the traceparent Argos wrote before OpenTelemetry
 * replaced it, a known limitation of ADR-0010 C-257 (3)), otherwise it carries the Argos
 * transaction's trace id.
 *
 * <p>Results: {@code [mx-coexist-<kind>]} and {@code <resultDir>/coexist-<kind>.properties}.
 */
@Tag("req:F-A32")
@Tag("req:F-A31")
@Tag("req:F-A01")
class CoexistenceTest {
  static final List<String> KINDS =
      List.of("jacoco-before", "jacoco-after", "otel-before", "otel-after", "devtools");

  static final Map<String, String> IDS =
      Map.of(
          "jacoco-before", "T-MX-110a",
          "jacoco-after", "T-MX-110b",
          "otel-before", "T-MX-110c",
          "otel-after", "T-MX-110d",
          "devtools", "T-MX-110e");

  static final String DEVTOOLS_APP = "shop-boot3-devtools";

  /** OpenTelemetry settings of the coexistence run: no exporter, no console output. */
  static final List<String> OTEL_OPTIONS =
      List.of(
          "-Dotel.traces.exporter=none",
          "-Dotel.metrics.exporter=none",
          "-Dotel.logs.exporter=none",
          "-Dotel.javaagent.logging=none");

  static final AtomicLong PAY_IDS = new AtomicLong(8_000);

  @TestFactory
  Stream<DynamicTest> coexistence() {
    String kind = System.getProperty("argos.test.matrix.coexist", "").strip();
    Assumptions.assumeFalse(kind.isEmpty(), "no coexistence case (argos.test.matrix.coexist)");
    assertThat(KINDS).as("argos.test.matrix.coexist").contains(kind);
    Combo combo = MatrixRuns.firstJarCombo();
    return Stream.of(
        DynamicTest.dynamicTest(
            MatrixRuns.comboId()
                + " "
                + IDS.get(kind)
                + " "
                + combo
                + " "
                + kind
                + ": starts, the same transaction count as Argos alone, coexistence baseline, no"
                + " agent ERROR, traceparent kept (F-A32)",
            () -> run(combo, kind)));
  }

  /** What one run did. */
  record Outcome(int requests, int transactions, long startupMs, int transformed) {}

  void run(Combo combo, String kind) throws Exception {
    Path agents = agentsDir();
    Path extra = null;
    SampleApp app = combo.sampleApp();
    if (kind.startsWith("jacoco")) {
      extra = find(agents, "jacocoagent-", ".jar");
    } else if (kind.startsWith("otel")) {
      extra = find(agents, "opentelemetry-javaagent-", ".jar");
    } else {
      Assumptions.assumeTrue(
          combo.app().equals("shop-boot3"), "devtools coexistence runs on shop-boot3: " + combo);
      String devtools = System.getProperty("argos.test.sampleApp." + DEVTOOLS_APP);
      Assumptions.assumeTrue(
          devtools != null && !devtools.isBlank(),
          "no devtools build of shop-boot3 (argos.test.sampleApp." + DEVTOOLS_APP + ")");
      app = SampleApp.fromArtifact(Path.of(devtools));
    }
    try (MatrixRuns.TempTree tree = MatrixRuns.TempTree.create("mx-coexist-");
        ExtStub stub = MatrixRuns.startExtStub()) {
      Path work = tree.root();
      Outcome alone =
          runOnce(combo, combo.sampleApp(), kind, null, stub, work.resolve("alone"), false);
      Outcome together = runOnce(combo, app, kind, extra, stub, work.resolve(kind), true);
      assertThat(together.transactions())
          .as("transactions with %s = Argos alone (%d requests)", kind, together.requests())
          .isEqualTo(alone.transactions())
          .isEqualTo(together.requests());
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("app", combo.app());
      values.put("jdk", combo.jdk());
      values.put("kind", kind);
      values.put("extra_agent", extra == null ? "-" : extra.getFileName().toString());
      values.put("startup_ms", together.startupMs());
      values.put("startup_ms_alone", alone.startupMs());
      values.put("transformed_classes", together.transformed());
      values.put("transactions", together.transactions());
      MatrixRuns.result("coexist-" + kind, values);
    }
  }

  Outcome runOnce(
      Combo combo,
      SampleApp sample,
      String kind,
      Path extra,
      ExtStub stub,
      Path dir,
      boolean together)
      throws Exception {
    AppLauncher launcher =
        AppLauncher.of(sample, Jdk.of(combo.jdk()))
            .withEnv("SAMPLE_EXT_URL", stub.baseUri().toString())
            .workDir(dir)
            .readyTimeout(Duration.ofMinutes(4))
            .withAgent(MatrixRuns.releaseJar(), Map.of("sampling.profile.normal-rate", "1.0"));
    if (together) {
      switch (kind) {
        case "jacoco-before" ->
            launcher.withExtraAgent(extra, AppLauncher.Order.BEFORE, jacocoOptions(dir));
        case "jacoco-after" ->
            launcher.withExtraAgent(extra, AppLauncher.Order.AFTER, jacocoOptions(dir));
        case "otel-before" -> {
          launcher.withExtraAgent(extra, AppLauncher.Order.BEFORE, null);
          launcher.withJvmOptions(OTEL_OPTIONS.toArray(String[]::new));
        }
        case "otel-after" -> {
          launcher.withExtraAgent(extra, AppLauncher.Order.AFTER, null);
          launcher.withJvmOptions(OTEL_OPTIONS.toArray(String[]::new));
        }
        case "devtools" -> launcher.withJvmOptions("-Dspring.devtools.restart.enabled=true");
        default -> throw new IllegalArgumentException(kind);
      }
    }
    String name = "coexist-" + combo.app() + "-" + combo.jdk() + "-" + (together ? kind : "alone");
    RunningApp app = launcher.start();
    try {
      int requests =
          workload(
              app,
              stub,
              kind.startsWith("otel") && together,
              together && "otel-before".equals(kind));
      Thread.sleep(1_000);
      List<TransactionRecord> txs = settle(app);
      TransformList list =
          app.awaitTransformList(l -> !l.classesOf("jdbc").isEmpty(), Duration.ofSeconds(15));
      TransformLog log =
          app.awaitTransformLog(
              l -> l.isComplete() && l.transformed().entries().size() >= list.entries().size(),
              Duration.ofSeconds(15));
      String agentLog = app.agentLog();
      assertThatAgentLog(agentLog).hasStartBanner().hasNoErrors();
      List<String> console = new ArrayList<>(app.stdoutLines());
      console.addAll(app.stderrLines());
      assertThat(AppOutput.agentMentions(console))
          .as("T-AG-060: the Argos agent prints nothing (%s)", name)
          .isEmpty();
      assertThat(agentLog + MatrixRuns.read(app.transformLogFile()))
          .doesNotContain("IllegalAccessError")
          .doesNotContain("advice error in module");
      if (together) {
        MatrixRuns.verifyBaseline(
            combo.app() + "-" + combo.jdk() + "-coexist-" + kind + ".txt",
            combo.app() + " on JDK " + combo.jdk() + " with " + kind + " (CoexistenceTest)",
            log);
      }
      System.out.println(
          "[mx-coexist] "
              + name
              + ": port "
              + app.spawnToPortMillis()
              + " ms, "
              + requests
              + " requests, "
              + txs.size()
              + " transactions, transformed "
              + list.entries().size());
      return new Outcome(requests, txs.size(), app.spawnToPortMillis(), list.entries().size());
    } finally {
      app.close();
      MatrixRuns.keepEvidence(name, app);
    }
  }

  /**
   * The requests of both runs: orders, pages, a 404, an error, async work, outgoing calls with
   * HttpURLConnection (each checked for its {@code traceparent}), and excluded noise. Returns how
   * many of them make a transaction.
   */
  static int workload(RunningApp app, ExtStub stub, boolean otel, boolean otelFirst)
      throws Exception {
    int transactions = 0;
    for (int i = 0; i < 10; i++) {
      assertThat(app.get("/api/orders/1").status()).isEqualTo(200);
      transactions++;
    }
    for (int i = 0; i < 5; i++) {
      assertThat(app.get("/api/orders?page=0").status()).isEqualTo(200);
      assertThat(app.get("/api/not-found-x").status()).isEqualTo(404);
      assertThat(app.get("/api/async/executor").status()).isEqualTo(200);
      assertThat(app.get("/api/error-500").status()).isEqualTo(500);
      transactions += 4;
      assertThat(app.get("/actuator/health").status()).isEqualTo(200);
    }
    for (int i = 0; i < 5; i++) {
      long id = PAY_IDS.incrementAndGet();
      HttpResult pay = app.get("/api/pay/" + id + "?client=hurl");
      assertThat(pay.status()).as(pay.body()).isEqualTo(200);
      transactions++;
      long payId = id;
      List<TransactionRecord> txs =
          app.transactions()
              .await(
                  tx -> ("/api/pay/" + payId).equals(MatrixRuns.bare(tx.path())),
                  1,
                  MatrixRuns.WAIT);
      assertThat(txs).hasSize(1);
      TransactionRecord tx = txs.get(0);
      assertThat(tx.steps("HTTP")).as("one HTTP step: %s", tx).hasSize(1);
      StepRecord step = tx.steps("HTTP").get(0);
      String traceparent = MatrixRuns.stubHeaders(stub, "/pay/" + id).get("traceparent");
      assertThat(traceparent)
          .as("one W3C traceparent at ext-stub")
          .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
      if (otel) {
        assertThat(traceparent)
            .as("the traceparent OpenTelemetry created, not the Argos trace id")
            .doesNotStartWith("00-" + tx.traceId() + "-");
        if (otelFirst) {
          // ADR-0010 C-257 (3), user decision 2026-11-26: with OpenTelemetry attached first the
          // Argos advice runs first (no traceparent on the request yet, so Argos writes its own,
          // as when it runs alone) and OpenTelemetry then replaces the header. The step is the
          // same as with Argos alone and does not claim the OpenTelemetry traceparent. Known
          // limitation (1.x backlog); the header that leaves is still the one OpenTelemetry
          // created (checked above).
          assertThat(step.detail())
              .as("C-257 (3): the Argos HTTP step is the plain Argos step: %s", step)
              .startsWith("GET ")
              .doesNotContain(traceparent);
        } else {
          assertThat(step.detail())
              .as("F-A32: the Argos HTTP step records the traceparent unchanged: %s", step)
              .contains(traceparent);
        }
      } else {
        assertThat(traceparent)
            .as("F-A32: the Argos transaction's trace id")
            .startsWith("00-" + tx.traceId() + "-");
      }
    }
    return transactions;
  }

  /** Waits until the JSONL has not grown for 1.5 s, then returns every transaction. */
  static List<TransactionRecord> settle(RunningApp app) throws InterruptedException {
    int last = app.transactions().read().size();
    long stableSince = System.nanoTime();
    long deadline = System.nanoTime() + MatrixRuns.WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      Thread.sleep(250);
      int now = app.transactions().read().size();
      if (now != last) {
        last = now;
        stableSince = System.nanoTime();
      } else if (System.nanoTime() - stableSince >= 1_500_000_000L) {
        break;
      }
    }
    return app.transactions().read();
  }

  static String jacocoOptions(Path dir) {
    return "destfile=" + dir.resolve("jacoco.exec").toAbsolutePath() + ",output=file";
  }

  /** {@code argos.test.matrix.agentsDir}, else {@code build/matrix-agents} of the project. */
  static Path agentsDir() {
    String dir = System.getProperty("argos.test.matrix.agentsDir");
    return dir == null || dir.isBlank()
        ? Path.of(System.getProperty("user.dir")).resolve("build").resolve("matrix-agents")
        : Path.of(dir);
  }

  /** The single {@code <prefix>*<suffix>} file of {@code dir}, or a skip with the reason. */
  static Path find(Path dir, String prefix, String suffix) throws IOException {
    List<Path> found = new ArrayList<>();
    if (Files.isDirectory(dir)) {
      try (Stream<Path> files = Files.list(dir)) {
        files
            .filter(
                f -> {
                  String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                  return n.startsWith(prefix) && n.endsWith(suffix);
                })
            .forEach(found::add);
      }
    }
    Assumptions.assumeTrue(
        found.size() == 1,
        "expected one "
            + prefix
            + "*"
            + suffix
            + " in "
            + dir
            + " (run test/matrix/fetch-coexist-agents.sh), found "
            + found);
    return found.get(0);
  }
}
