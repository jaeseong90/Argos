/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.export.TransactionRecord.StepRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0010 C-233 (6): the DB target ({@code DB_TARGET}, C-143 (4)) of the DBC steps of a request
 * whose SQL runs on virtual threads ({@code SAMPLE_VT=true}) and of the same request on platform
 * threads. shop-boot3 on JDK 21 (H2), release agent jar over gRPC to a TLS {@link FakeCollector},
 * {@code GET /api/async/executor?work=sql} (one connection and one statement per async branch,
 * WO-15) and {@code GET /api/orders/1} (one connection on the request thread).
 *
 * <p>The DBC step reads the target its data source learnt from an earlier connection (DbInfo, C-143
 * (4) "when known", without a JDBC call at {@code getConnection}); a connection learns its target
 * at its first statement inside a transaction. So the only DBC steps without a target are the ones
 * that ended before any connection of that data source had resolved its target: those of the first
 * database request after start, acquired before its first SQL step began. The test checks that rule
 * on the cold first request and that every DBC step of later requests has the target, in both
 * thread modes. Console lines {@code [c233]} list every DBC step.
 */
@Tag("req:F-A03")
class VirtualThreadDbTargetTest {
  static final String EXECUTOR = "/api/async/executor";
  static final String ORDERS = "/api/orders/1";
  static final Duration WAIT = Duration.ofSeconds(30);
  static final String APP = "shop-boot3";
  static final String TARGET = "h2:mem:" + APP;

  @TempDir Path work;

  @Test
  @DisplayName(
      "T-AG-032 C-233 (6) SAMPLE_VT=true: DBC steps of virtual-thread requests carry the DB target"
          + " once the data source knows it; only DBC steps of the first request that ended before"
          + " its first SQL began lack it")
  void virtualThreads() throws Exception {
    check(true);
  }

  @Test
  @DisplayName(
      "T-AG-032 C-233 (6) platform threads: the same DBC target rule as SAMPLE_VT=true (the empty"
          + " first DBC target is not specific to virtual threads)")
  void platformThreads() throws Exception {
    check(false);
  }

  private void check(boolean vt) throws Exception {
    int jdk = jdk();
    Assumptions.assumeTrue(
        jdk >= 21, "SAMPLE_VT needs JDK 21+, -Pagent.e2e has " + APP + "@" + jdk);
    String name = APP + "-jdk" + jdk + (vt ? "-vt" : "-platform");
    try (FakeCollector collector = FakeCollector.builder().tls(true).start()) {
      AppLauncher launcher =
          AppLauncher.of(SampleApp.named(APP), Jdk.of(jdk))
              .workDir(work.resolve(name))
              .readyTimeout(Duration.ofMinutes(4))
              .virtualThreads(vt)
              .withAgent(Path.of(required("argos.test.releaseAgentJar")), agentSettings());
      launcher.collector(collector);
      try (RunningApp app = launcher.start(Duration.ofMinutes(4))) {
        try {
          awaitEstablished(app, collector);
          Set<String> seen = new HashSet<>();
          // Cold: the first database request of the JVM (startup and readiness run no SQL inside a
          // transaction, so no connection has resolved its target yet).
          TransactionRecord cold = request(app, collector, EXECUTOR + "?work=sql", seen);
          List<StepRecord> coldDbc = cold.steps("DBC");
          print(name, "cold " + EXECUTOR, cold);
          assertThat(coldDbc).as("DBC steps of the cold request: %s", cold).isNotEmpty();
          assertThat(cold.virtualThread()).as("virtual request thread").isEqualTo(vt);
          double firstSqlStart =
              cold.steps("SQL").stream()
                  .map(VirtualThreadDbTargetTest::startMs)
                  .min(Double::compare)
                  .orElseThrow();
          for (StepRecord dbc : coldDbc) {
            if (dbc.target() == null || dbc.target().isEmpty()) {
              assertThat(startMs(dbc) + dbc.elapsedMs())
                  .as(
                      "a DBC step without target must have ended before the first SQL step began"
                          + " (no connection had resolved the target yet): %s, first SQL at %s ms",
                      dbc, firstSqlStart)
                  .isLessThanOrEqualTo(firstSqlStart + 1.0);
            } else {
              assertThat(dbc.target()).isEqualTo(TARGET);
            }
          }
          // Warm: every DBC step has the target, on both request kinds.
          for (int i = 0; i < 3; i++) {
            for (String path : List.of(EXECUTOR + "?work=sql", ORDERS)) {
              TransactionRecord warm = request(app, collector, path, seen);
              print(name, "warm " + path, warm);
              List<StepRecord> dbc = warm.steps("DBC");
              assertThat(dbc).as("DBC steps of %s: %s", path, warm).isNotEmpty();
              assertThat(dbc)
                  .as("every DBC step of a warm %s carries the DB target: %s", path, warm)
                  .allSatisfy(step -> assertThat(step.target()).isEqualTo(TARGET));
              assertThat(warm.steps("SQL"))
                  .as("every SQL step of %s carries the DB target", path)
                  .allSatisfy(step -> assertThat(step.target()).isEqualTo(TARGET));
            }
          }
        } finally {
          keepEvidence(name, app);
        }
      }
    }
  }

  private static double startMs(StepRecord step) {
    Object value = step.field("start_offset_ms");
    assertThat(value).as("start_offset_ms of %s", step).isInstanceOf(Number.class);
    return ((Number) value).doubleValue();
  }

  private static void print(String name, String label, TransactionRecord tx) {
    List<String> lines = new ArrayList<>();
    for (StepRecord step : tx.steps()) {
      if ("DBC".equals(step.type()) || "SQL".equals(step.type())) {
        lines.add(
            step.type()
                + " seq="
                + step.seq()
                + " start="
                + step.field("start_offset_ms")
                + " ms elapsed="
                + step.elapsedMs()
                + " target="
                + (step.target() == null || step.target().isEmpty() ? "<none>" : step.target())
                + " thread="
                + step.thread());
      }
    }
    System.out.println(
        "[c233] "
            + name
            + " "
            + label
            + " vt="
            + tx.virtualThread()
            + " tx="
            + tx.txId()
            + ": "
            + String.join("; ", lines));
  }

  private static TransactionRecord request(
      RunningApp app, FakeCollector collector, String path, Set<String> seen)
      throws IOException, InterruptedException {
    HttpResult result = app.get(path);
    assertThat(result.status()).as("GET %s: %s", path, result.body()).isEqualTo(200);
    String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
    List<TransactionRecord> found =
        collector
            .collected()
            .awaitTransactions(tx -> bare.equals(tx.path()) && !seen.contains(tx.txId()), 1, WAIT);
    assertThat(found).as("transaction of %s", path).hasSize(1);
    TransactionRecord tx = found.get(0);
    seen.add(tx.txId());
    return tx;
  }

  private static void awaitEstablished(RunningApp app, FakeCollector collector)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (collector.acceptedSessionCount() == 0
        && !app.agentLog().contains("collector session established")) {
      assertThat(System.nanoTime() < deadline && app.isAlive())
          .as("collector session within 60 s\n%s", app.describe())
          .isTrue();
      Thread.sleep(100);
    }
  }

  private static Map<String, String> agentSettings() {
    Map<String, String> settings = new LinkedHashMap<>();
    settings.put("sampling.profile.normal-rate", "1.0");
    settings.put("async.step", "true");
    return settings;
  }

  /** The JDK of the first {@code shop-boot3@N} of {@code -Pagent.e2e} (skips without one). */
  private static int jdk() {
    for (String item : System.getProperty("argos.test.e2e", APP + "@21").split(",", -1)) {
      String[] parts = item.strip().split("@", -1);
      if (parts.length == 2 && parts[0].equals(APP)) {
        return Integer.parseInt(parts[1]);
      }
    }
    Assumptions.abort("no " + APP + "@<jdk> in -Pagent.e2e");
    return 0;
  }

  private static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  private static void keepEvidence(String name, RunningApp app) {
    String reportDir = System.getProperty("argos.test.reportDir");
    if (reportDir == null || reportDir.isBlank()) {
      return;
    }
    try {
      Path dir = Files.createDirectories(Path.of(reportDir));
      Files.writeString(
          dir.resolve("c233-" + name + "-agent.log"), app.agentLog(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.out.println("[c233] cannot keep the agent log of " + name + ": " + e);
    }
  }
}
