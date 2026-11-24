/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.remoteconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.ConfigApplyOutcome;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.ReceivedBatch;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Shared code of the remote configuration suite (WO-17, 10 §12.2, 11 §8.1, 16 §5.5, §5.6): a jar
 * shop app of {@code -Pagent.e2e} ({@code shop-boot3@N}, {@code shop-boot2@N}) with the release
 * agent jar over gRPC to a TLS {@link FakeCollector}, transactions by path, the self-diagnostics
 * effective configuration through the {@code SELF_DIAGNOSTICS} command (C-95), and the evidence
 * files ({@code wo17-*} logs in {@code build/reports/agent-e2e}). Public harness API only.
 */
final class RemoteConfigRuns {
  static final String BOOT3 = "shop-boot3";
  static final String BOOT2 = "shop-boot2";

  /** The default allow list (10 §15, C-240 (4)) as a {@code -Dargos.remote-config.allow} value. */
  static final String DEFAULT_ALLOW =
      "sampling.**, instrumentation.disabled, instrumentation.ignore-*, log.level, enabled,"
          + " transaction.slow-stack.*, capture.error-message-max-length, capture.stack-depth,"
          + " instrumentation.app-packages, async.step, active.snapshot-interval-seconds,"
          + " metrics.interval-seconds, transaction.max-duration-ms";

  static final Duration APPLY_WAIT = Duration.ofSeconds(30);

  private RemoteConfigRuns() {}

  /** One {@code app@jdk} item of {@code -Pagent.e2e}. */
  record Combo(String app, int jdk) {
    @Override
    public String toString() {
      return app + "@" + jdk;
    }
  }

  /** The {@code shop-boot3} and {@code shop-boot2} items of {@code -Pagent.e2e}, in order. */
  static List<Combo> shopCombos() {
    List<Combo> combos = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", BOOT3 + "@21").split(",", -1)) {
      String[] parts = item.strip().split("@", -1);
      if (parts.length == 2 && (parts[0].equals(BOOT3) || parts[0].equals(BOOT2))) {
        combos.add(new Combo(parts[0], Integer.parseInt(parts[1])));
      }
    }
    return combos;
  }

  /** The first {@code shop-boot3} item of {@code -Pagent.e2e}, if any. */
  static Optional<Combo> firstBoot3() {
    return shopCombos().stream().filter(c -> c.app().equals(BOOT3)).findFirst();
  }

  static Path releaseJar() {
    String value = System.getProperty("argos.test.releaseAgentJar");
    assertThat(value)
        .as("system property argos.test.releaseAgentJar (set by agent-testing integrationTest)")
        .isNotBlank();
    return Path.of(value);
  }

  /** {@code combo} with the release agent sending to {@code collector}. */
  static AppLauncher shop(Path dir, Combo combo, FakeCollector collector) {
    AppLauncher launcher =
        AppLauncher.of(SampleApp.named(combo.app()), Jdk.of(combo.jdk()))
            .workDir(dir)
            .readyTimeout(Duration.ofMinutes(4))
            .withAgent(releaseJar());
    launcher.collector(collector);
    return launcher;
  }

  /** Waits for the agent's session with {@code collector} (60 s). */
  static void awaitEstablished(RunningApp app, FakeCollector collector)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (collector.openSessionCount() == 0) {
      assertThat(System.nanoTime() < deadline && app.isAlive())
          .as("collector session within 60 s\n%s", app.describe())
          .isTrue();
      Thread.sleep(100);
    }
  }

  /** The agent's answer to a push of {@code values} within {@link #APPLY_WAIT}. */
  static ConfigApplyOutcome push(FakeCollector collector, Map<String, String> values)
      throws Exception {
    return collector.pushConfig(values).get(APPLY_WAIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Every transaction record the collector received, in arrival order. */
  static List<TransactionRecord> transactions(FakeCollector collector) {
    List<TransactionRecord> out = new ArrayList<>();
    for (ReceivedBatch batch : collector.batches(BatchKind.TRANSACTIONS)) {
      out.addAll(((TransactionBatch) batch.body()).getRecordsList());
    }
    return out;
  }

  /** Transactions whose {@code url_path} is {@code path}. */
  static long transactionsAt(FakeCollector collector, String path) {
    return transactions(collector).stream().filter(t -> t.getUrlPath().equals(path)).count();
  }

  /** Waits until {@code count} transactions of {@code path} arrived; true when they did. */
  static boolean awaitTransactions(FakeCollector collector, String path, long count, Duration wait)
      throws InterruptedException {
    return await(() -> transactionsAt(collector, path) >= count, wait);
  }

  /** {@code PROFILES} batches received after {@code instant}. */
  static long profileBatchesAfter(FakeCollector collector, Instant instant) {
    return collector.batches(BatchKind.PROFILES).stream()
        .filter(b -> b.receivedAt().isAfter(instant))
        .count();
  }

  /** {@code GET path} {@code times} times, each answered 200. */
  static void get(RunningApp app, String path, int times) throws Exception {
    for (int i = 0; i < times; i++) {
      assertThat(app.get(path).status()).as("GET %s", path).isEqualTo(200);
    }
  }

  /**
   * The {@code SELF_DIAGNOSTICS} command result (C-95), asked again after its 1 s cooldown when the
   * agent answers {@code COOLDOWN}.
   */
  static SelfDiagnostics selfDiagnostics(RunningApp app, FakeCollector collector)
      throws InterruptedException {
    SelfDiagnostics result = app.selfDiagnostics(collector);
    for (int i = 0; i < 5 && !result.available(); i++) {
      Thread.sleep(1_200L);
      result = app.selfDiagnostics(collector);
    }
    assertThat(result.available()).as("SELF_DIAGNOSTICS: %s", result.reason()).isTrue();
    return result;
  }

  /** {@code effective_config.<key>.value} of {@code diagnostics}. */
  static String effectiveValue(SelfDiagnostics diagnostics, String key) {
    return String.valueOf(
        diagnostics.member("effective_config." + key + ".value").orElse("<absent>"));
  }

  /** {@code effective_config.<key>.source} of {@code diagnostics}. */
  static String effectiveSource(SelfDiagnostics diagnostics, String key) {
    return String.valueOf(
        diagnostics.member("effective_config." + key + ".source").orElse("<absent>"));
  }

  /** {@code extras.<name>} of {@code diagnostics} as a number (-1 when absent). */
  static long extra(SelfDiagnostics diagnostics, String name) {
    Object value = diagnostics.member("extras." + name).orElse(null);
    return value instanceof Number number ? number.longValue() : -1L;
  }

  static boolean await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.sleep(100);
    }
    return condition.getAsBoolean();
  }

  /** Writes the agent log and stdout of {@code app} to {@code build/reports/agent-e2e/wo17-*}. */
  static void keepEvidence(String name, RunningApp app) {
    String reportDir = System.getProperty("argos.test.reportDir");
    if (reportDir == null || reportDir.isBlank() || app == null) {
      return;
    }
    try {
      Path dir = Files.createDirectories(Path.of(reportDir));
      Files.writeString(
          dir.resolve("wo17-" + name + "-agent.log"), app.agentLog(), StandardCharsets.UTF_8);
      Files.write(
          dir.resolve("wo17-" + name + "-stdout.log"), app.stdoutLines(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.out.println("[wo17] cannot keep the logs of " + name + ": " + e);
    }
  }
}
