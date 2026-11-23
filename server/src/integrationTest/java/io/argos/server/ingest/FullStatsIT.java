/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static io.argos.server.ingest.AgentServerStack.assertAckOk;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.Records;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * T-ACC-12 server side (D-12, F-S21): statistics are complete without profiles. A fake agent sends
 * 10,000 transactions with profiling at 0 % (no transaction captured, no profile sent), each with
 * its own number of SQL executions and outgoing calls, and the {@code StatBatch}es of the same 10 s
 * windows whose counts add up to those executions, as the agent's statistics do (10 §8). Then
 * {@code Σ xlog.sql_count = Σ sql_stat_raw.count = Σ sql_stat_1m.count = Σ sql_stat_1h.count} = the
 * SQL executions sent, the same for the outgoing calls, and no {@code profile_step} row exists.
 *
 * <p>The half of T-ACC-12 that compares with a counter inside a real application ("앱 측 카운터") needs
 * the agent's statistics (SendStats, WO-16) and is done there.
 */
@Tag("integration")
@Tag("req:D-12")
@Tag("req:F-S21")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullStatsIT {

  private static final int TRANSACTIONS = 10_000;
  private static final int PER_BATCH = 500;
  private static final String[] SQL = {
    "SELECT o.id, o.total FROM orders o WHERE o.id = ?",
    "SELECT i.sku FROM order_items i WHERE i.order_id = ?",
    "UPDATE orders SET status = ? WHERE id = ?"
  };
  private static final String DB = "jdbc:postgresql://db.shop.internal:5432/shop";
  private static final String[] HOSTS = {"https://pay.example.net:443", "http://stock:8080"};

  private AgentServerStack stack;

  @BeforeAll
  void start() {
    stack = AgentServerStack.start();
  }

  @AfterAll
  void stop() {
    if (stack != null) {
      stack.close();
    }
  }

  /** Executions of one 10 s window and kind. */
  private static final class Window {
    final long[] sql = new long[SQL.length];
    final long[] ext = new long[HOSTS.length];
  }

  @Test
  @DisplayName(
      "T-ACC-12 profiling 0 %, 10,000 transactions: Σ xlog.sql_count = Σ sql_stat_1m.count = SQL"
          + " executions sent (raw and 1h too), the same for outgoing calls; no profile rows")
  void fullStatisticsWithoutProfiles() throws Exception {
    String appName = "it-full-stats";
    AgentTokens.IssuedToken token = stack.app(appName);
    int appId = token.appId();
    long sqlSent = 0;
    long extSent = 0;
    try (FakeAgent agent = stack.connect(token, appName, appName + "-1:8080", 41)) {
      Records records = agent.records();
      for (String s : SQL) {
        records.id(DictionaryKind.SQL_TEXT, s);
      }
      records.id(DictionaryKind.DB_TARGET, DB);
      for (String h : HOSTS) {
        records.id(DictionaryKind.HOST, h);
      }
      // 10,000 transactions over the last 8 minutes and 20 s, one every 50 ms: 50 windows.
      long first = Records.window10s(System.currentTimeMillis()) - 510_000L;
      Map<String, Window> windows = new TreeMap<>();
      List<TransactionRecord> batch = new ArrayList<>();
      for (int i = 0; i < TRANSACTIONS; i++) {
        long start = first + i * 50L;
        boolean isBatch = i % 10 == 9;
        int sqlCount = (i * 7) % 5; // 0..4
        int extCount = i % 2; // 0..1
        TransactionRecord.Builder tx =
            isBatch
                ? records.batch("job.SettleOrders", start, 40_000, false)
                : records.http("GET /api/orders/{id}", start, 30_000);
        tx.setProfileCaptured(false)
            .clearSqlIds()
            .setSqlCount(sqlCount)
            .setSqlTimeMs(sqlCount * 2)
            .setExtCount(extCount)
            .setExtTimeMs(extCount * 3);
        batch.add(tx.build());
        sqlSent += sqlCount;
        extSent += extCount;
        Window w =
            windows.computeIfAbsent(
                Records.window10s(start) + "/" + (isBatch ? "BATCH" : "HTTP"), k -> new Window());
        // The agent counts each execution in the statistics of its statement and target.
        for (int e = 0; e < sqlCount; e++) {
          w.sql[(i + e) % SQL.length]++;
        }
        for (int e = 0; e < extCount; e++) {
          w.ext[(i + e) % HOSTS.length]++;
        }
        if (batch.size() == PER_BATCH) {
          agent.sendNewDictionaryEntries();
          assertAckOk(agent.sendTransactions(agent.batches().transactions(batch)));
          batch = new ArrayList<>();
          Thread.sleep(300); // below the 2,000 transactions/s quota (SEC-12)
        }
      }
      assertThat(batch).isEmpty();
      agent.sendNewDictionaryEntries();
      // One StatBatch per 10 s window with the rows of both kinds.
      Map<Long, List<SqlStat>> sqlByWindow = new TreeMap<>();
      Map<Long, List<ExtStat>> extByWindow = new TreeMap<>();
      for (Map.Entry<String, Window> e : windows.entrySet()) {
        long window = Long.parseLong(e.getKey().substring(0, e.getKey().indexOf('/')));
        TransactionKind kind =
            e.getKey().endsWith("BATCH") ? TransactionKind.BATCH : TransactionKind.HTTP;
        for (int s = 0; s < SQL.length; s++) {
          long n = e.getValue().sql[s];
          if (n > 0) {
            sqlByWindow
                .computeIfAbsent(window, k -> new ArrayList<>())
                .add(records.sqlStat(SQL[s], DB, kind, (int) n, n * 2_000, 5_000).build());
          }
        }
        for (int h = 0; h < HOSTS.length; h++) {
          long n = e.getValue().ext[h];
          if (n > 0) {
            extByWindow
                .computeIfAbsent(window, k -> new ArrayList<>())
                .add(records.extStat(HOSTS[h], kind, (int) n, n * 3_000, 9_000).build());
          }
        }
      }
      assertThat(sqlByWindow).hasSize(50);
      for (Long window : sqlByWindow.keySet()) {
        assertAckOk(
            agent.sendStats(
                agent
                    .batches()
                    .stats(
                        window,
                        sqlByWindow.get(window),
                        extByWindow.getOrDefault(window, List.of()),
                        List.of())));
      }
    }
    String where = " WHERE app_id = " + appId;
    assertThat(stack.awaitScalar("SELECT count() FROM argos.xlog" + where, TRANSACTIONS, 180))
        .isEqualTo(TRANSACTIONS);
    assertThat(stack.awaitScalar("SELECT sum(count) FROM argos.sql_stat_raw" + where, sqlSent, 180))
        .isEqualTo(sqlSent);
    assertThat(stack.awaitScalar("SELECT sum(count) FROM argos.ext_stat_raw" + where, extSent, 180))
        .isEqualTo(extSent);

    assertThat(sqlSent).isEqualTo(20_000);
    assertThat(stack.scalar("SELECT sum(sql_count) FROM argos.xlog" + where)).isEqualTo(sqlSent);
    assertThat(stack.scalar("SELECT sum(count) FROM argos.sql_stat_1m" + where)).isEqualTo(sqlSent);
    assertThat(stack.scalar("SELECT sum(count) FROM argos.sql_stat_1h" + where)).isEqualTo(sqlSent);
    assertThat(extSent).isEqualTo(5_000);
    assertThat(stack.scalar("SELECT sum(ext_count) FROM argos.xlog" + where)).isEqualTo(extSent);
    assertThat(stack.scalar("SELECT sum(count) FROM argos.ext_stat_1m" + where)).isEqualTo(extSent);
    assertThat(stack.scalar("SELECT sum(count) FROM argos.ext_stat_1h" + where)).isEqualTo(extSent);
    // Per kind as well (D-20): BATCH executions are not mixed into HTTP.
    for (String kind : List.of("HTTP", "BATCH")) {
      String k = where + " AND kind = '" + kind + "'";
      assertThat(stack.scalar("SELECT sum(count) FROM argos.sql_stat_1m" + k))
          .as("sql " + kind)
          .isEqualTo(stack.scalar("SELECT sum(sql_count) FROM argos.xlog" + k));
      assertThat(stack.scalar("SELECT sum(count) FROM argos.ext_stat_1m" + k))
          .as("ext " + kind)
          .isEqualTo(stack.scalar("SELECT sum(ext_count) FROM argos.xlog" + k));
    }
    assertThat(stack.scalar("SELECT count() FROM argos.xlog" + where + " AND profile_captured = 1"))
        .isZero();
    assertThat(stack.scalar("SELECT count() FROM argos.profile_step" + where)).isZero();
    System.out.printf(
        "T-ACC-12: %d transactions, %d SQL executions, %d outgoing calls; xlog = raw = 1m = 1h%n",
        TRANSACTIONS, sqlSent, extSent);
  }
}
