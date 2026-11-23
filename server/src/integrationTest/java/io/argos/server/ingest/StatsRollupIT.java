/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static io.argos.server.ingest.AgentServerStack.assertAckOk;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionKind;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.Records;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
 * T-SV-018 (F-S21, D-12, D-20, 12 §3.6, 13 §4.5): full statistics through the whole server. A fake
 * agent sends {@code StatBatch}es of 10 s windows over two minutes of one hour and one minute of
 * the next, with two statements and one outgoing-call host, each for HTTP and BATCH transactions;
 * {@code sql_stat_raw} and {@code ext_stat_raw} hold exactly what was sent, {@code sql_stat_1m},
 * {@code ext_stat_1m} (materialized views on the raw tables) and {@code sql_stat_1h}, {@code
 * ext_stat_1h} (cascaded from 1m) hold the sums, maxima and element-wise bucket sums ({@code
 * sumForEachMerge}) of the windows of each minute and hour, per kind (D-20). A resent batch (same
 * seq) is acknowledged as a duplicate and adds nothing.
 */
@Tag("integration")
@Tag("req:F-S21")
@Tag("req:D-12")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatsRollupIT {

  private static final DateTimeFormatter CH_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
  private static final int BUCKETS = 15;
  private static final String SQL_A = "SELECT o.id FROM orders o WHERE o.id = ?";
  private static final String SQL_B = "UPDATE stock SET qty = qty - ? WHERE item_id = ?";
  private static final String DB = "jdbc:postgresql://db.shop.internal:5432/shop";
  private static final String HOST = "https://pay.example.net:443";

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

  /** One sent SQL row as stored: key and measures in the stored units (ms). */
  private record SqlRow(
      long ts,
      String kind,
      long sqlId,
      long count,
      long errors,
      long sumMs,
      long execMs,
      long fetchMs,
      long maxMs,
      long rows,
      List<Long> buckets) {}

  /** One sent outgoing-call row as stored. */
  private record ExtRow(
      long ts,
      String kind,
      long count,
      long errors,
      long timeouts,
      long connectFails,
      long sumMs,
      long maxMs,
      List<Long> buckets) {}

  @Test
  @Tag("req:D-20")
  @DisplayName(
      "T-SV-018 StatBatch -> sql_stat_raw/ext_stat_raw = sent; sql/ext_stat_1m and 1h: sums, max"
          + " and sumForEachMerge buckets of their windows, HTTP and BATCH apart (D-20); a resent"
          + " batch adds 0")
  void statisticsAndRollups() throws Exception {
    String appName = "it-stats-rollup";
    AgentTokens.IssuedToken token = stack.app(appName);
    int appId = token.appId();
    // Windows of the previous complete hour (minutes 0 and 1) and the first minute of the
    // current hour: two 1h rows, three 1m rows per key.
    long hour = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L - 3_600_000L;
    long[] windows = {
      hour, hour + 10_000, hour + 50_000, hour + 60_000, hour + 70_000, hour + 3_600_000L
    };
    List<SqlRow> sentSql = new ArrayList<>();
    List<ExtRow> sentExt = new ArrayList<>();
    StatBatch resend = null;
    try (FakeAgent agent = stack.connect(token, appName, appName + "-1:8080", 31)) {
      Records records = agent.records();
      long sqlA = records.id(io.argos.proto.v1.DictionaryKind.SQL_TEXT, SQL_A);
      long sqlB = records.id(io.argos.proto.v1.DictionaryKind.SQL_TEXT, SQL_B);
      records.id(io.argos.proto.v1.DictionaryKind.DB_TARGET, DB);
      records.id(io.argos.proto.v1.DictionaryKind.HOST, HOST);
      agent.sendNewDictionaryEntries();
      int w = 0;
      for (long window : windows) {
        w++;
        List<SqlStat> sql = new ArrayList<>();
        List<ExtStat> ext = new ArrayList<>();
        for (TransactionKind kind : List.of(TransactionKind.HTTP, TransactionKind.BATCH)) {
          int k = kind == TransactionKind.HTTP ? 1 : 2;
          for (long sqlId : List.of(sqlA, sqlB)) {
            int s = sqlId == sqlA ? 1 : 3;
            long[] buckets = buckets(w * s + k);
            long count = sum(buckets);
            long sumUs = count * 1_000L * (1 + w * s * k);
            long fetchUs = (sumUs / 10 / 1_000) * 1_000;
            int maxUs = (int) (2_000L + 1_000L * w * s * k + 1_000L * (w % 3) * 7);
            SqlStat.Builder b =
                records
                    .sqlStat(sqlId == sqlA ? SQL_A : SQL_B, DB, kind, (int) count, sumUs, maxUs)
                    .setErrorCount(w % 2 == 0 ? 1 : 0)
                    .setSumFetchUs(fetchUs)
                    .setSumExecUs(sumUs - fetchUs)
                    .setFetchRows(count * 3)
                    .clearBuckets();
            for (long v : buckets) {
              b.addBuckets((int) v);
            }
            sql.add(b.build());
            sentSql.add(
                new SqlRow(
                    window / 1_000,
                    kind.name(),
                    sqlId,
                    count,
                    w % 2 == 0 ? 1 : 0,
                    sumUs / 1_000,
                    (sumUs - fetchUs) / 1_000,
                    fetchUs / 1_000,
                    maxUs / 1_000,
                    count * 3,
                    boxed(buckets)));
          }
          long[] buckets = buckets(w + 5 * k);
          long count = sum(buckets);
          long sumUs = count * (40_000L + 1_000L * w * k);
          int maxUs = (int) (90_000L + 3_000L * w * k);
          ExtStat.Builder e =
              records
                  .extStat(HOST, kind, (int) count, sumUs, maxUs)
                  .setErrorCount(w % 3 == 0 ? 2 : 0)
                  .setTimeoutCount(w == 2 ? 1 : 0)
                  .setConnectFailCount(w == 4 ? 1 : 0)
                  .clearBuckets();
          for (long v : buckets) {
            e.addBuckets((int) v);
          }
          ext.add(e.build());
          sentExt.add(
              new ExtRow(
                  window / 1_000,
                  kind.name(),
                  count,
                  w % 3 == 0 ? 2 : 0,
                  w == 2 ? 1 : 0,
                  w == 4 ? 1 : 0,
                  sumUs / 1_000,
                  maxUs / 1_000,
                  boxed(buckets)));
        }
        StatBatch batch = agent.batches().stats(window, sql, ext, List.of());
        assertAckOk(agent.sendStats(batch));
        if (w == 3) {
          resend = batch;
        }
      }
      String rawCount = "SELECT count() FROM argos.sql_stat_raw WHERE app_id = " + appId;
      assertThat(stack.awaitScalar(rawCount, sentSql.size(), 120)).isEqualTo(sentSql.size());
      String extCount = "SELECT count() FROM argos.ext_stat_raw WHERE app_id = " + appId;
      assertThat(stack.awaitScalar(extCount, sentExt.size(), 120)).isEqualTo(sentExt.size());

      // --- raw = sent -------------------------------------------------------------------------
      assertThat(
              stack
                  .reader()
                  .rows(
                      "SELECT toUnixTimestamp(ts), kind, sql_id, count, error_count, sum_ms,"
                          + " sum_exec_ms, sum_fetch_ms, max_ms, fetch_rows, buckets, db_id,"
                          + " instance_id FROM argos.sql_stat_raw WHERE app_id = "
                          + appId
                          + " ORDER BY ts, kind, sql_id"))
          .containsExactlyElementsOf(
              sentSql.stream()
                  .sorted(
                      (a, b) ->
                          a.ts() != b.ts()
                              ? Long.compare(a.ts(), b.ts())
                              : !a.kind().equals(b.kind())
                                  ? Integer.compare(kindOrder(a.kind()), kindOrder(b.kind()))
                                  : Long.compareUnsigned(a.sqlId(), b.sqlId()))
                  .map(
                      r ->
                          List.of(
                              Long.toString(r.ts()),
                              r.kind(),
                              Long.toUnsignedString(r.sqlId()),
                              Long.toString(r.count()),
                              Long.toString(r.errors()),
                              Long.toString(r.sumMs()),
                              Long.toString(r.execMs()),
                              Long.toString(r.fetchMs()),
                              Long.toString(r.maxMs()),
                              Long.toString(r.rows()),
                              array(r.buckets().stream().mapToLong(Long::longValue).toArray()),
                              Long.toUnsignedString(
                                  records.id(io.argos.proto.v1.DictionaryKind.DB_TARGET, DB)),
                              Integer.toString(agent.instanceId())))
                  .toList());
      assertThat(
              stack
                  .reader()
                  .rows(
                      "SELECT toUnixTimestamp(ts), kind, count, error_count, timeout_count,"
                          + " connect_fail_count, sum_ms, max_ms, buckets FROM argos.ext_stat_raw"
                          + " WHERE app_id = "
                          + appId
                          + " ORDER BY ts, kind"))
          .containsExactlyElementsOf(
              sentExt.stream()
                  .sorted(
                      (a, b) ->
                          a.ts() != b.ts()
                              ? Long.compare(a.ts(), b.ts())
                              : Integer.compare(kindOrder(a.kind()), kindOrder(b.kind())))
                  .map(
                      r ->
                          List.of(
                              Long.toString(r.ts()),
                              r.kind(),
                              Long.toString(r.count()),
                              Long.toString(r.errors()),
                              Long.toString(r.timeouts()),
                              Long.toString(r.connectFails()),
                              Long.toString(r.sumMs()),
                              Long.toString(r.maxMs()),
                              array(r.buckets().stream().mapToLong(Long::longValue).toArray())))
                  .toList());

      // --- 1m and 1h = the windows of each minute and hour, per kind ------------------------
      for (int bucketSeconds : new int[] {60, 3_600}) {
        String table = bucketSeconds == 60 ? "sql_stat_1m" : "sql_stat_1h";
        String time = bucketSeconds == 60 ? "minute" : "hour";
        List<List<String>> rows =
            stack
                .reader()
                .rows(
                    "SELECT toUnixTimestamp("
                        + time
                        + "), kind, sql_id, sum(count), sum(error_count), sum(sum_ms),"
                        + " sum(sum_exec_ms), sum(sum_fetch_ms), max(max_ms), sum(fetch_rows),"
                        + " sumForEachMerge(buckets) FROM argos."
                        + table
                        + " WHERE app_id = "
                        + appId
                        + " GROUP BY "
                        + time
                        + ", kind, sql_id ORDER BY "
                        + time
                        + ", kind, sql_id");
        assertThat(rows).as(table).containsExactlyElementsOf(expectedSql(sentSql, bucketSeconds));
        assertThat(rows)
            .as(table + ": HTTP and BATCH apart (D-20)")
            .extracting(r -> r.get(1))
            .containsOnly("HTTP", "BATCH")
            .contains("HTTP", "BATCH");
        String extTable = bucketSeconds == 60 ? "ext_stat_1m" : "ext_stat_1h";
        List<List<String>> extRows =
            stack
                .reader()
                .rows(
                    "SELECT toUnixTimestamp("
                        + time
                        + "), kind, sum(count), sum(error_count), sum(timeout_count),"
                        + " sum(connect_fail_count), sum(sum_ms), max(max_ms),"
                        + " sumForEachMerge(buckets) FROM argos."
                        + extTable
                        + " WHERE app_id = "
                        + appId
                        + " GROUP BY "
                        + time
                        + ", kind ORDER BY "
                        + time
                        + ", kind");
        assertThat(extRows)
            .as(extTable)
            .containsExactlyElementsOf(expectedExt(sentExt, bucketSeconds));
      }
      assertThat(
              stack.scalar(
                  "SELECT count(DISTINCT minute) FROM argos.sql_stat_1m WHERE app_id = " + appId))
          .isEqualTo(3);
      assertThat(
              stack.scalar(
                  "SELECT count(DISTINCT hour) FROM argos.sql_stat_1h WHERE app_id = " + appId))
          .isEqualTo(2);

      // --- a resent batch adds nothing ------------------------------------------------------
      String totals =
          "SELECT (SELECT sum(count) FROM argos.sql_stat_raw WHERE app_id = "
              + appId
              + ") * 1000000 + (SELECT sum(count) FROM argos.ext_stat_1h WHERE app_id = "
              + appId
              + ")";
      long before = stack.scalar(totals);
      CallResult duplicate = agent.sendStats(resend);
      assertThat(duplicate.ackStatus()).as(duplicate.toString()).isEqualTo(AckStatus.ACK_DUPLICATE);
      // A later batch of another window passes the same WAL after the resend: once it is in
      // ClickHouse, a duplicate would be too.
      long marker = hour + 3_600_000L + 50_000;
      assertAckOk(
          agent.sendStats(
              agent
                  .batches()
                  .stats(
                      marker,
                      List.of(
                          records
                              .sqlStat(SQL_A, DB, TransactionKind.HTTP, 1, 1_000, 1_000)
                              .build()),
                      List.of(),
                      List.of())));
      assertThat(stack.awaitScalar(rawCount, sentSql.size() + 1L, 120))
          .isEqualTo(sentSql.size() + 1L);
      assertThat(stack.scalar(totals)).isEqualTo(before + 1_000_000L);
      assertThat(
              stack.scalar(
                  "SELECT sum(count) FROM argos.sql_stat_1m WHERE app_id = "
                      + appId
                      + " AND minute = toDateTime('"
                      + CH_TIME.format(Instant.ofEpochMilli(hour))
                      + "', 'UTC')"))
          .as("the minute of the resent window counts it once")
          .isEqualTo(expectedCount(sentSql, hour / 1_000, 60));
    }
  }

  private static int kindOrder(String kind) {
    return kind.equals("HTTP") ? 1 : kind.equals("BATCH") ? 2 : 3;
  }

  /** 15 bucket counts that differ per {@code seed} (some zero). */
  private static long[] buckets(int seed) {
    long[] b = new long[BUCKETS];
    for (int i = 0; i < BUCKETS; i++) {
      b[i] = (i + seed) % 4 == 0 ? 0 : (seed * (i + 1)) % 9 + 1;
    }
    return b;
  }

  private static List<Long> boxed(long[] values) {
    return java.util.Arrays.stream(values).boxed().toList();
  }

  private static long sum(long[] values) {
    long s = 0;
    for (long v : values) {
      s += v;
    }
    return s;
  }

  private static String array(long[] values) {
    StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append(values[i]);
    }
    return out.append(']').toString();
  }

  private static long expectedCount(List<SqlRow> sent, long bucketStart, int bucketSeconds) {
    return sent.stream()
        .filter(r -> r.ts() - Math.floorMod(r.ts(), bucketSeconds) == bucketStart)
        .mapToLong(SqlRow::count)
        .sum();
  }

  /** The rows of a 1m or 1h query computed from the sent rows. */
  private static List<List<String>> expectedSql(List<SqlRow> sent, int bucketSeconds) {
    Map<String, long[]> acc = new TreeMap<>();
    Map<String, long[]> buckets = new TreeMap<>();
    Map<String, List<String>> keys = new TreeMap<>();
    for (SqlRow r : sent) {
      long t = r.ts() - Math.floorMod(r.ts(), bucketSeconds);
      String key =
          // Zero-padded hex sorts as ClickHouse orders UInt64 (unsigned).
          String.format("%012d/%d/%016x", t, kindOrder(r.kind()), r.sqlId());
      keys.putIfAbsent(key, List.of(Long.toString(t), r.kind(), Long.toUnsignedString(r.sqlId())));
      long[] a = acc.computeIfAbsent(key, k -> new long[7]);
      a[0] += r.count();
      a[1] += r.errors();
      a[2] += r.sumMs();
      a[3] += r.execMs();
      a[4] += r.fetchMs();
      a[5] = Math.max(a[5], r.maxMs());
      a[6] += r.rows();
      long[] b = buckets.computeIfAbsent(key, k -> new long[BUCKETS]);
      for (int i = 0; i < BUCKETS; i++) {
        b[i] += r.buckets().get(i);
      }
    }
    List<List<String>> out = new ArrayList<>();
    for (Map.Entry<String, long[]> e : acc.entrySet()) {
      List<String> row = new ArrayList<>(keys.get(e.getKey()));
      for (long v : e.getValue()) {
        row.add(Long.toString(v));
      }
      row.add(array(buckets.get(e.getKey())));
      out.add(row);
    }
    return out;
  }

  /** The rows of an ext 1m or 1h query computed from the sent rows. */
  private static List<List<String>> expectedExt(List<ExtRow> sent, int bucketSeconds) {
    Map<String, long[]> acc = new TreeMap<>();
    Map<String, long[]> buckets = new TreeMap<>();
    Map<String, List<String>> keys = new TreeMap<>();
    for (ExtRow r : sent) {
      long t = r.ts() - Math.floorMod(r.ts(), bucketSeconds);
      String key = String.format("%012d/%d", t, kindOrder(r.kind()));
      keys.putIfAbsent(key, List.of(Long.toString(t), r.kind()));
      long[] a = acc.computeIfAbsent(key, k -> new long[6]);
      a[0] += r.count();
      a[1] += r.errors();
      a[2] += r.timeouts();
      a[3] += r.connectFails();
      a[4] += r.sumMs();
      a[5] = Math.max(a[5], r.maxMs());
      long[] b = buckets.computeIfAbsent(key, k -> new long[BUCKETS]);
      for (int i = 0; i < BUCKETS; i++) {
        b[i] += r.buckets().get(i);
      }
    }
    List<List<String>> out = new ArrayList<>();
    for (Map.Entry<String, long[]> e : acc.entrySet()) {
      List<String> row = new ArrayList<>(keys.get(e.getKey()));
      for (long v : e.getValue()) {
        row.add(Long.toString(v));
      }
      row.add(array(buckets.get(e.getKey())));
      out.add(row);
    }
    return out;
  }
}
