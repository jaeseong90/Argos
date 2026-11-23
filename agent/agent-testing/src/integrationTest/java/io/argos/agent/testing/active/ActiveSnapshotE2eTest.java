/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.active;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.Collected.ActiveItem;
import io.argos.agent.testing.collector.Collected.ActiveSnapshot;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.export.TransactionRecord;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The active transaction snapshot of a real agent (10 §11 {@code ActiveSnapshotter}, 11 §4.4,
 * ADR-0010 C-225 (1); WO-16). shop-boot3 of {@code -Pagent.e2e} (default {@code shop-boot3@21})
 * with the release agent jar over gRPC to a TLS {@link FakeCollector} (which asks for a snapshot
 * every 5 s). Two 8-second requests run at the same time:
 *
 * <ul>
 *   <li>{@code /api/slow?ms=8000&sql=1} sleeps inside H2 ({@code CALL ARGOS_SLEEP_MS(?)}): its item
 *       is {@code IN_SQL} and its {@code state_name_id} resolves to that statement in the
 *       collector's dictionary while the request still runs, i.e. before any transaction record or
 *       profile carried the text (the snapshot's dictionary pre-send, 11 §7);
 *   <li>{@code /api/slow?ms=8000} sleeps in the request thread: {@code IN_METHOD}, {@code
 *       state_name_id} 0.
 * </ul>
 *
 * The snapshot has {@code total_active} 2, the items longest first, the thread that runs each
 * request, and the items' {@code tx_id}s are those of the two transaction records sent at the end.
 * After both requests ended, a later snapshot has neither item and {@code total_active} 0.
 */
@Tag("req:F-U04")
@Tag("req:F-S03")
class ActiveSnapshotE2eTest {
  static final String SQL_PATH = "/api/slow?ms=8000&sql=1";
  static final String METHOD_PATH = "/api/slow?ms=8000";
  static final String SLEEP_SQL = "CALL ARGOS_SLEEP_MS(?)";

  /** One app on one JDK. */
  record Combo(String app, int jdk) {
    String name() {
      return app + "-jdk" + jdk;
    }

    @Override
    public String toString() {
      return app + "@" + jdk;
    }
  }

  static Stream<Combo> combos() {
    List<Combo> combos = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", "shop-boot3@21").split(",", -1)) {
      String[] parts = item.strip().split("@", -1);
      if (parts.length == 2 && parts[0].equals("shop-boot3")) {
        combos.add(new Combo(parts[0], Integer.parseInt(parts[1])));
      }
    }
    Assumptions.assumeFalse(combos.isEmpty(), "no shop-boot3 in -Pagent.e2e, e.g. shop-boot3@21");
    return combos.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("combos")
  @DisplayName(
      "T-E2E-active-31b a real agent's active snapshot: IN_SQL with its sql_id pre-sent in the"
          + " dictionary, IN_METHOD for a plain sleep, total_active right, gone after the end")
  void snapshotOfRunningRequests(Combo combo) throws Exception {
    String name = "active-" + combo.name();
    Path workRoot = Files.createTempDirectory("wo16-active-");
    String agentJar = System.getProperty("argos.test.releaseAgentJar");
    assertThat(agentJar).as("argos.test.releaseAgentJar").isNotBlank();
    try (FakeCollector collector = FakeCollector.builder().tls(true).start()) {
      AppLauncher launcher =
          AppLauncher.of(SampleApp.named(combo.app()), Jdk.of(combo.jdk()))
              .workDir(workRoot.resolve(name))
              .readyTimeout(Duration.ofMinutes(4))
              .withAgent(Path.of(agentJar), new LinkedHashMap<>());
      launcher.collector(collector);
      RunningApp app = launcher.start(Duration.ofMinutes(4));
      try {
        run(app, collector);
      } finally {
        app.close();
        keepEvidence(name, app);
      }
    } finally {
      deleteTree(workRoot);
    }
  }

  private static void run(RunningApp app, FakeCollector collector) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (collector.batches().isEmpty()
        && !app.agentLog().contains("collector session established")) {
      assertThat(System.nanoTime() < deadline && app.isAlive())
          .as("collector session within 60 s\n%s", app.describe())
          .isTrue();
      Thread.sleep(100);
    }
    Collected collected = app.collected();
    // A warm-up request that runs no SQL: the dispatcher servlet and the thread are ready.
    assertThat(app.get("/api/slow?ms=1").status()).isEqualTo(200);

    long startMs = System.currentTimeMillis();
    CompletableFuture<HttpResult> sql = async(app, SQL_PATH);
    CompletableFuture<HttpResult> method = async(app, METHOD_PATH);

    // A snapshot with both requests running: one IN_SQL, one IN_METHOD (within 7.5 s of the start,
    // the requests take 8 s; snapshots come every 5 s).
    ActiveSnapshot both = null;
    String sqlText = null;
    boolean recordsBefore = true;
    while (System.currentTimeMillis() - startMs < 7_500L) {
      for (ActiveSnapshot s : snapshots(collected)) {
        if (s.snapshotTsMs() >= startMs
            && state(s, "IN_SQL") != null
            && state(s, "IN_METHOD") != null) {
          both = s;
        }
      }
      if (both != null) {
        // Resolved now, while the requests still run: the text came with the snapshot.
        sqlText = state(both, "IN_SQL").stateName();
        recordsBefore = !slowRecords(collected).isEmpty() || sql.isDone() || method.isDone();
        break;
      }
      Thread.sleep(100);
    }
    assertThat(both)
        .as("a snapshot with IN_SQL and IN_METHOD within 7.5 s; got %s", snapshots(collected))
        .isNotNull();
    System.out.println("[wo16-active] running " + both);
    assertThat(recordsBefore)
        .as("checked while both requests still ran and before any /api/slow record arrived")
        .isFalse();
    assertThat(sqlText).as("state_name_id of IN_SQL, pre-sent (11 §7)").isEqualTo(SLEEP_SQL);
    assertThat(both.totalActive()).as("total_active").isEqualTo(2);
    assertThat(both.truncated()).isFalse();
    assertThat(both.items()).hasSize(2);
    assertThat(both.items())
        .extracting(ActiveItem::elapsedMs)
        .isSortedAccordingTo(Comparator.reverseOrder());
    ActiveItem inSql = state(both, "IN_SQL");
    ActiveItem inMethod = state(both, "IN_METHOD");
    for (ActiveItem item : both.items()) {
      assertThat(item.kind()).isEqualTo("HTTP");
      assertThat(item.service()).contains("/api/slow");
      assertThat(item.threadName()).as("thread_name_id resolved").isNotBlank();
      assertThat(item.threadId()).isPositive();
      assertThat(item.startTsMs()).isBetween(startMs - 1_000L, both.snapshotTsMs());
      assertThat(item.elapsedMs()).isBetween(0L, both.snapshotTsMs() - startMs + 1_000L);
    }
    assertThat(inMethod.stateNameId()).isZero();
    assertThat(inSql.stateNameId()).isNotZero();

    assertThat(sql.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
    assertThat(method.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
    long endMs = System.currentTimeMillis();

    // The items are the two transactions: the records sent at the end carry their tx_ids.
    List<TransactionRecord> records = List.of();
    long recordDeadline = System.currentTimeMillis() + 20_000L;
    while (System.currentTimeMillis() < recordDeadline) {
      records = slowRecords(collected);
      if (records.size() >= 2) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(records).as("two /api/slow?ms=8000 records").hasSize(2);
    String sqlTx = null;
    String methodTx = null;
    for (TransactionRecord record : records) {
      if (number(record, "sql_count") > 0) {
        sqlTx = record.txId();
      } else {
        methodTx = record.txId();
      }
    }
    assertThat(Long.toUnsignedString(inSql.txId())).isEqualTo(sqlTx);
    assertThat(Long.toUnsignedString(inMethod.txId())).isEqualTo(methodTx);

    // A snapshot taken after the end: neither transaction, nothing running.
    ActiveSnapshot after =
        await(
            () -> {
              for (ActiveSnapshot s : snapshots(collected)) {
                if (s.snapshotTsMs() > endMs) {
                  return s;
                }
              }
              return null;
            },
            15_000L);
    assertThat(after).as("a snapshot after the end within 15 s").isNotNull();
    System.out.println("[wo16-active] after " + after);
    assertThat(after.items())
        .extracting(ActiveItem::txId)
        .doesNotContain(inSql.txId(), inMethod.txId());
    assertThat(after.totalActive()).isZero();
    assertThat(after.items()).isEmpty();
  }

  private static CompletableFuture<HttpResult> async(RunningApp app, String path) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return app.get(path);
          } catch (IOException e) {
            throw new IllegalStateException(e);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        });
  }

  private static List<ActiveSnapshot> snapshots(Collected collected) {
    Optional<List<ActiveSnapshot>> snapshots = collected.activeSnapshots();
    assertThat(snapshots).as("active snapshots (gRPC collector)").isPresent();
    return snapshots.get();
  }

  private static ActiveItem state(ActiveSnapshot snapshot, String state) {
    for (ActiveItem item : snapshot.items()) {
      if (state.equals(item.state())) {
        return item;
      }
    }
    return null;
  }

  /** The records of the two 8-second requests (the warm-up has another query). */
  private static List<TransactionRecord> slowRecords(Collected collected) {
    List<TransactionRecord> out = new ArrayList<>();
    for (TransactionRecord record : collected.transactions()) {
      String path = record.path();
      Double elapsed = record.elapsedMs();
      if (path != null
          && path.startsWith("/api/slow")
          && elapsed != null
          && elapsed.doubleValue() >= 7_000.0) {
        out.add(record);
      }
    }
    return out;
  }

  private static long number(TransactionRecord record, String field) {
    Object value = record.field(field);
    return value instanceof Number n ? n.longValue() : 0L;
  }

  private static <T> T await(Supplier<T> probe, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (true) {
      T value = probe.get();
      if (value != null || System.currentTimeMillis() > deadline) {
        return value;
      }
      Thread.sleep(200);
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
          dir.resolve("wo16-" + name + "-agent.log"), app.agentLog(), StandardCharsets.UTF_8);
      Files.write(
          dir.resolve("wo16-" + name + "-stdout.log"), app.stdoutLines(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.out.println("[wo16-active] cannot keep the evidence of " + name + ": " + e);
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
