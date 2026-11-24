/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.slowstack;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.Collected.MetricPoint;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.export.TransactionRecord.StepRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Slow-stack samples of a real agent (10 §13.1, F-A19, T-AG-064; WO-19). shop-boot3 of {@code
 * -Pagent.e2e} (default {@code shop-boot3@21}) with the agent jar of the build over gRPC to a TLS
 * {@link FakeCollector}, default {@code transaction.slow-stack.*} settings (slow threshold 1,000
 * ms, 10 threads per cycle, 5 samples per transaction 3,000 ms apart, depth 64). Fifteen {@code GET
 * /api/slow?ms=5000} (a {@code Thread.sleep} in the controller) run at the same time; each of their
 * profiles must carry STACK steps:
 *
 * <ul>
 *   <li>at most 10 samples per 1 s cycle over all transactions (captures grouped by their time);
 *   <li>1 to 5 STACK steps per transaction, at least 3,000 ms apart;
 *   <li>at most 64 frames each, {@code class.method(file:line)}, with {@code Thread.sleep} and the
 *       controller method;
 * </ul>
 *
 * and the self-diagnostics ({@code stack_capture_seconds_total}, {@code stack_captures_total}) and
 * the self metric {@code argos_agent_stack_capture_seconds_total} show a cost above 0. On JDK 21+
 * the run is repeated with {@code SAMPLE_VT=true} (Tomcat on virtual threads): the same holds
 * through the virtual-thread fallback ({@code Thread.getStackTrace}, T-AG-064d). One {@code
 * [wo19-slowstack]} line per run.
 */
@Tag("req:F-A19")
class SlowStackE2eTest {
  static final String APP = "shop-boot3";
  static final int REQUESTS = 15;
  static final long SLEEP_MS = 5_000L;
  static final String PATH = "/api/slow?ms=" + SLEEP_MS;

  /** One app run: JDK and whether Tomcat runs on virtual threads. */
  record Combo(int jdk, boolean virtualThreads) {
    String name() {
      return APP + "-jdk" + jdk + (virtualThreads ? "-vt" : "");
    }

    @Override
    public String toString() {
      return APP + "@" + jdk + (virtualThreads ? " SAMPLE_VT" : "");
    }
  }

  static Stream<Combo> combos() {
    List<Combo> combos = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", APP + "@21").split(",", -1)) {
      String[] parts = item.strip().split("@", -1);
      if (parts.length == 2 && parts[0].equals(APP)) {
        int jdk = Integer.parseInt(parts[1]);
        combos.add(new Combo(jdk, false));
        if (jdk >= 21) {
          combos.add(new Combo(jdk, true));
        }
      }
    }
    Assumptions.assumeFalse(combos.isEmpty(), "no shop-boot3 in -Pagent.e2e, e.g. shop-boot3@21");
    return combos.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("combos")
  @DisplayName(
      "T-AG-064 slow stacks of 15 concurrent 5 s requests: <= 10 per cycle, 1..5 per transaction"
          + " >= 3 s apart, <= 64 frames with Thread.sleep, VT fallback, cost in the"
          + " self-diagnostics and the self metric")
  void slowStacks(Combo combo) throws Exception {
    Path workRoot = Files.createTempDirectory("wo19-slowstack-");
    String agentJar = System.getProperty("argos.test.releaseAgentJar");
    assertThat(agentJar).as("argos.test.releaseAgentJar").isNotBlank();
    try (FakeCollector collector = FakeCollector.builder().tls(true).start()) {
      AppLauncher launcher =
          AppLauncher.of(SampleApp.named(APP), Jdk.of(combo.jdk()))
              .workDir(workRoot.resolve(combo.name()))
              .readyTimeout(Duration.ofMinutes(4))
              .withAgent(Path.of(agentJar), new LinkedHashMap<>())
              .virtualThreads(combo.virtualThreads());
      launcher.collector(collector);
      RunningApp app = launcher.start(Duration.ofMinutes(4));
      try {
        run(combo, app, collector);
      } finally {
        app.close();
        keepEvidence(combo.name(), app);
      }
    } finally {
      deleteTree(workRoot);
    }
  }

  private static void run(Combo combo, RunningApp app, FakeCollector collector) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (collector.openSessionCount() == 0) {
      assertThat(System.nanoTime() < deadline && app.isAlive())
          .as("collector session within 60 s\n%s", app.describe())
          .isTrue();
      Thread.sleep(100);
    }
    Collected collected = app.collected();
    assertThat(app.get("/api/slow?ms=1").status()).isEqualTo(200);

    ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
    Instant started = Instant.now();
    try {
      List<CompletableFuture<HttpResult>> calls = new ArrayList<>();
      for (int i = 0; i < REQUESTS; i++) {
        calls.add(
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return app.get(PATH);
                  } catch (IOException e) {
                    throw new IllegalStateException(e);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                  }
                },
                pool));
      }
      for (CompletableFuture<HttpResult> call : calls) {
        assertThat(call.get(60, TimeUnit.SECONDS).status()).isEqualTo(200);
      }
    } finally {
      pool.shutdownNow();
    }

    List<TransactionRecord> records =
        collected.awaitTransactions(
            tx ->
                tx.path() != null
                    && tx.path().startsWith("/api/slow")
                    && tx.elapsedMs() != null
                    && tx.elapsedMs() >= SLEEP_MS - 500.0,
            REQUESTS,
            Duration.ofSeconds(30));
    assertThat(records).as("the %d slow transactions", REQUESTS).hasSize(REQUESTS);

    List<Long> captureTimes = new ArrayList<>();
    int maxFrames = 0;
    double minGapMs = Double.MAX_VALUE;
    int[] perTx = new int[6];
    for (TransactionRecord tx : records) {
      assertThat(tx.profileCaptured()).as("profile of %s (slow)", tx.txId()).isTrue();
      if (combo.virtualThreads()) {
        assertThat(tx.virtualThread()).as("SAMPLE_VT: %s on a virtual thread", tx.txId()).isTrue();
      }
      List<StepRecord> stacks = tx.steps("STACK");
      assertThat(stacks)
          .as("STACK steps of %s (1 to max-captures 5)\n%s", tx.txId(), tx)
          .hasSizeBetween(1, 5);
      perTx[stacks.size()]++;
      double previous = Double.NaN;
      for (StepRecord step : stacks) {
        List<String> frames = frames(step);
        assertThat(frames).as("frames of a STACK step of %s", tx.txId()).isNotEmpty();
        assertThat(frames.size()).isLessThanOrEqualTo(64);
        maxFrames = Math.max(maxFrames, frames.size());
        assertThat(frames)
            .as("Thread.sleep in the sample of %s: %s", tx.txId(), frames)
            .anyMatch(f -> f.startsWith("java.lang.Thread.sleep"));
        assertThat(frames)
            .as("the controller frame as class.method(file:line): %s", frames)
            .anyMatch(
                f ->
                    f.matches(
                        ".*DiagnosticsController\\.slow\\(DiagnosticsController\\.java:\\d+\\)"));
        assertThat(step.parent()).as("top level").isIn(-1, null);
        double offset = ((Number) step.field("start_offset_ms")).doubleValue();
        assertThat(offset).as("not before the slow threshold").isGreaterThanOrEqualTo(999.0);
        if (!Double.isNaN(previous)) {
          minGapMs = Math.min(minGapMs, offset - previous);
          assertThat(offset - previous)
              .as("interval-ms 3,000 between samples")
              .isGreaterThanOrEqualTo(2_999.0);
        }
        previous = offset;
        captureTimes.add(tx.startEpochMs() + Math.round(offset));
      }
    }
    // Samples of one cycle are taken within milliseconds; cycles are 1 s apart.
    captureTimes.sort(Comparator.naturalOrder());
    List<Integer> cycles = new ArrayList<>();
    long last = Long.MIN_VALUE;
    for (long time : captureTimes) {
      if (cycles.isEmpty() || time - last > 400L) {
        cycles.add(0);
      }
      cycles.set(cycles.size() - 1, cycles.get(cycles.size() - 1) + 1);
      last = time;
    }
    int maxPerCycle = cycles.stream().mapToInt(Integer::intValue).max().orElse(0);
    assertThat(maxPerCycle).as("samples per cycle %s", cycles).isLessThanOrEqualTo(10);

    SelfDiagnostics diagnostics = app.awaitSelfDiagnostics(Instant.now(), Duration.ofSeconds(10));
    assertThat(diagnostics.available()).as(diagnostics.reason()).isTrue();
    double seconds = diagnostics.decimal("stack_capture_seconds_total");
    long stacks = diagnostics.value("stack_captures_total");
    assertThat(seconds).as("stack_capture_seconds_total").isGreaterThan(0.0);
    assertThat(stacks).as("stack_captures_total").isGreaterThanOrEqualTo(captureTimes.size());

    double metric = awaitMetric(collected, Duration.ofSeconds(20));
    assertThat(metric).as("argos_agent_stack_capture_seconds_total").isGreaterThan(0.0);
    assertThat(app.agentLog()).doesNotContain("slow stack sampling disabled");

    System.out.printf(
        "[wo19-slowstack] %s: %d transactions, STACK steps per tx %s (index = count), samples %d,"
            + " cycles %s (max %d), min gap %.1f ms, max frames %d, self-diag %.6f s / %d"
            + " stacks (%.3f ms per stack), metric %.6f s, run %d s%n",
        combo,
        records.size(),
        java.util.Arrays.toString(perTx),
        captureTimes.size(),
        cycles,
        maxPerCycle,
        minGapMs == Double.MAX_VALUE ? -1.0 : minGapMs,
        maxFrames,
        seconds,
        stacks,
        stacks == 0 ? 0.0 : seconds * 1000.0 / stacks,
        metric,
        Duration.between(started, Instant.now()).toSeconds());
  }

  @SuppressWarnings("unchecked")
  private static List<String> frames(StepRecord step) {
    Object stack = step.field("stack");
    List<String> out = new ArrayList<>();
    if (stack instanceof List<?> list) {
      for (Object frame : list) {
        out.add(String.valueOf(frame));
      }
    }
    return out;
  }

  /** The latest {@code argos_agent_stack_capture_seconds_total} above 0, or 0. */
  private static double awaitMetric(Collected collected, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      Optional<List<MetricPoint>> metrics = collected.metrics();
      assertThat(metrics).as("metrics (gRPC collector)").isPresent();
      double best = 0.0;
      for (MetricPoint point : metrics.get()) {
        if (point.name().equals("argos_agent_stack_capture_seconds_total")) {
          best = Math.max(best, point.value());
        }
      }
      if (best > 0.0 || System.nanoTime() > deadline) {
        return best;
      }
      Thread.sleep(250);
    }
  }

  private static void keepEvidence(String name, RunningApp app) {
    String reportDir = System.getProperty("argos.test.reportDir");
    if (reportDir == null || reportDir.isBlank()) {
      return;
    }
    try {
      Path dir = Files.createDirectories(Path.of(reportDir));
      Files.writeString(
          dir.resolve("wo19-slowstack-" + name + "-agent.log"),
          app.agentLog(),
          StandardCharsets.UTF_8);
      Files.write(
          dir.resolve("wo19-slowstack-" + name + "-stdout.log"),
          app.stdoutLines(),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.out.println("[wo19-slowstack] cannot keep the evidence of " + name + ": " + e);
    }
  }

  private static void deleteTree(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException e) {
          // left behind; the next clean of the temp directory removes it
        }
      }
    } catch (IOException e) {
      // nothing more to do
    }
  }
}
