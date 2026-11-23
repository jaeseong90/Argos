/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static io.argos.server.ingest.IngestFixtures.eventually;
import static io.argos.server.ingest.IngestFixtures.session;
import static io.argos.server.ingest.IngestFixtures.tx;
import static io.argos.server.ingest.IngestFixtures.txId;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.TransactionRecord;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.wal.api.WalRecordType;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Zero duplicates, zero loss (D-13) of the durable ingest path against real PostgreSQL and
 * ClickHouse: a materialized view that fails and is repaired (T-ACC-13 (d), the Q15 break), and a
 * real process kill of the ingest process during inserts (T-SV-010 and T-ACC-13 (b) at the
 * durable-ingest level; the gRPC end-to-end variant is the collector's).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("req:D-13")
class DurabilityIT {

  private ServerTestEnvironment env;

  @TempDir Path temp;

  @BeforeAll
  void start() throws Exception {
    env = ServerTestEnvironment.start();
    IngestStack.ensureMigrated(env);
  }

  @AfterAll
  void stop() {
    if (env != null) {
      env.close();
    }
  }

  private int app(String name) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP)) {
      return AgentTokens.ensureApp(c, name, "prod");
    }
  }

  private ClickHouseHttp reader() {
    return IngestFixtures.reader(env);
  }

  private long count(String sql) {
    String value = reader().scalar(sql);
    return value.isBlank() || value.equals("\\N") ? 0 : Long.parseLong(value);
  }

  private Map<String, Long> totals(int appId) {
    Map<String, Long> t = new LinkedHashMap<>();
    t.put("xlog", count("SELECT count() FROM argos.xlog WHERE app_id = " + appId));
    for (String rollup :
        List.of(
            "app_stat_1m",
            "app_stat_1h",
            "service_stat_1m",
            "service_stat_1h",
            "instance_stat_1m",
            "instance_stat_1h")) {
      t.put(rollup, count("SELECT sum(count) FROM argos." + rollup + " WHERE app_id = " + appId));
    }
    return t;
  }

  @Test
  @Tag("req:T-ACC-13")
  @Tag("req:C-61")
  @DisplayName(
      "T-ACC-13 (d) one materialized view broken (Q15: its target refuses rows), insert, repair,"
          + " retry with the same token: only the broken view gains the rows, nothing doubles")
  void brokenViewRepaired() throws Exception {
    int appId = app("it-broken-view");
    int n = 25;
    ClickHouseHttp migrator = IngestFixtures.migrator(env);
    Path dir = temp.resolve("broken-view");
    Files.createDirectories(dir);
    try (IngestStack stack = IngestStack.start(env, dir)) {
      migrator.execute(
          "ALTER TABLE argos.service_stat_1m ADD CONSTRAINT IF NOT EXISTS it_t_acc_13_broken CHECK 0");
      try {
        long now = System.currentTimeMillis();
        List<TransactionRecord> records = new ArrayList<>();
        for (int i = 0; i < n; i++) {
          records.add(tx(txId(now, i), now - 1_000 + i, 3_000, 1, 0).build());
        }
        stack.append(
            WalRecordType.TX,
            session(appId, 901, now, 0, 500, ""),
            IngestFixtures.txBatch(records),
            1);
        assertThat(
                eventually(
                    60,
                    () ->
                        stack
                                .registry
                                .get("argos.writer.inserts")
                                .tag("table", "xlog")
                                .tag("result", "error")
                                .counter()
                                .count()
                            >= 2))
            .as("the insert fails while the view is broken and is retried")
            .isTrue();
        Map<String, Long> broken = totals(appId);
        // The source block and the healthy views took the rows; the broken view did not (Q15).
        assertThat(broken.get("xlog")).isEqualTo(n);
        assertThat(broken.get("service_stat_1m")).isZero();
      } finally {
        migrator.execute(
            "ALTER TABLE argos.service_stat_1m DROP CONSTRAINT IF EXISTS it_t_acc_13_broken");
      }
      stack.awaitLoaded();
      assertThat(stack.writer.pendingIntents()).isEmpty();
    }
    Map<String, Long> repaired = totals(appId);
    assertThat(repaired)
        .as("raw and every rollup = rows sent, after the retry")
        .containsEntry("xlog", (long) n)
        .containsEntry("app_stat_1m", (long) n)
        .containsEntry("app_stat_1h", (long) n)
        .containsEntry("service_stat_1m", (long) n)
        .containsEntry("service_stat_1h", (long) n)
        .containsEntry("instance_stat_1m", (long) n)
        .containsEntry("instance_stat_1h", (long) n);
  }

  @Test
  @Tag("req:T-SV-010")
  @Tag("req:T-ACC-13")
  @DisplayName(
      "T-SV-010a 1,000 batches acked, the ingest process killed during inserts, restart and WAL"
          + " recovery: count = sent, duplicates 0")
  void killDuringInserts() throws Exception {
    int appId = app("it-kill");
    int batches = 1_000;
    int records = 10;
    Path dir = temp.resolve("kill-wal");
    Files.createDirectories(dir);
    Map<String, String> props = IngestCrashChild.baseProperties(env);
    props.put("wal.dir", dir.toString());
    props.put("app.id", String.valueOf(appId));
    props.put("instance.id", "902");
    props.put("batches", String.valueOf(batches));
    props.put("records", String.valueOf(records));
    props.put("insert.delay.ms", "600");
    Path childErr = temp.resolve("child-stderr.txt");
    Process child = IngestCrashChild.launch(temp, props);
    int acked = 0;
    try (BufferedReader out =
        new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
      while ((line = out.readLine()) != null && System.nanoTime() < deadline) {
        if (line.startsWith("ACK ")) {
          acked = Integer.parseInt(line.substring(4).strip());
        }
        if (line.equals("DONE")) {
          break;
        }
      }
      // Inserts of the slowed-down writer (600 ms before and after each insert, 20 blocks) are
      // still running: kill now (TerminateProcess / SIGKILL).
      child.destroyForcibly();
      assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
      }
    }
    assertThat(acked)
        .as("acked batches; child stderr: " + Files.readString(childErr))
        .isEqualTo(batches);
    long beforeRecovery = count("SELECT count() FROM argos.xlog WHERE app_id = " + appId);
    long sent = (long) batches * records;
    long loaded;
    int uncommitted;
    try (IngestStack recovered = IngestStack.start(env, dir)) {
      uncommitted = recovered.scan.uncommitted().size();
      // The WAL GC of the child freed the segments whose records were committed; every remaining
      // data record restores its key into the deduplication window (C-3).
      assertThat(recovered.scan.dataRecords()).isPositive().isLessThanOrEqualTo(batches);
      assertThat(recovered.restoredKeys).hasSize((int) recovered.scan.dataRecords());
      recovered.awaitLoaded();
      loaded = count("SELECT count() FROM argos.xlog WHERE app_id = " + appId);
    }
    long distinct = count("SELECT uniqExact(tx_id) FROM argos.xlog WHERE app_id = " + appId);
    System.out.printf(
        "T-SV-010a: sent %d rows, %d in ClickHouse at the kill, %d intents without commit, %d after"
            + " recovery%n",
        sent, beforeRecovery, uncommitted, loaded);
    assertThat(beforeRecovery).as("rows in ClickHouse at the kill").isLessThan(sent);
    assertThat(loaded).isEqualTo(sent);
    assertThat(distinct).isEqualTo(sent);
    assertThat(count("SELECT sum(count) FROM argos.app_stat_1m WHERE app_id = " + appId))
        .isEqualTo(sent);
    assertThat(count("SELECT sum(count) FROM argos.instance_stat_1m WHERE app_id = " + appId))
        .isEqualTo(sent);
  }

  @Test
  @Tag("req:D-13")
  @DisplayName(
      "T-SV-010b after a clean restart nothing is loaded twice and the WAL GC frees committed segments")
  void restartAndGc() throws Exception {
    int appId = app("it-restart");
    Path dir = temp.resolve("restart-wal");
    Files.createDirectories(dir);
    IngestStack.Options small = IngestStack.Options.defaults().withSegmentBytes(64 * 1024);
    int sent = 0;
    try (IngestStack stack = IngestStack.start(env, dir, small)) {
      for (int seq = 1; seq <= 100; seq++) {
        long now = System.currentTimeMillis();
        List<TransactionRecord> records = new ArrayList<>();
        for (int j = 0; j < 20; j++) {
          records.add(tx(txId(now, seq * 100 + j), now - 10, 1_000, 0, 0).build());
        }
        stack.append(
            WalRecordType.TX,
            session(appId, 903, now, 0, 500, ""),
            IngestFixtures.txBatch(records),
            seq);
        sent += 20;
      }
      stack.awaitLoaded();
      assertThat(stack.wal.activeSegmentId()).as("segments written").isGreaterThanOrEqualTo(3);
      // The argos-wal-gc thread (1 s period in the tests, 1 min in the server) frees every closed
      // segment once its records are committed: only the active segment stays.
      assertThat(eventually(30, () -> stack.wal.segments().size() == 1))
          .as("segments left after GC: " + stack.wal.segments())
          .isTrue();
      assertThat(stack.wal.bytesInUse()).isEqualTo(stack.wal.segments().get(0).size());
    }
    try (IngestStack again = IngestStack.start(env, dir, small)) {
      again.awaitLoaded();
    }
    assertThat(count("SELECT count() FROM argos.xlog WHERE app_id = " + appId)).isEqualTo(sent);
  }
}
