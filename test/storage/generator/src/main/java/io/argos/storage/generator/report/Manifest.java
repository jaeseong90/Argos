/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.report;

import io.argos.storage.generator.model.Hash;
import io.argos.storage.generator.model.MetricRow;
import io.argos.storage.generator.model.ProfileStep;
import io.argos.storage.generator.model.StatRow;
import io.argos.storage.generator.model.Transaction;
import io.argos.storage.generator.rows.Row;
import io.argos.storage.generator.rows.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ground truth of a run: every row handed to the loader is counted here (the generator counters of
 * 18 §8, "sent = count(xlog) = sum(service_stat_1m.count)"). Per UTC day it keeps the xlog row
 * count and its sql_count / ext_count sums (by xlog ts) and the statistics counts (by window ts),
 * so a D-12 check can compare exact expected values per day, and it keeps deterministic samples of
 * transaction, trace, user and request ids for query parameters. Used by the generator thread only.
 */
public final class Manifest {

  private static final int XLOG_ROWS = 0;
  private static final int XLOG_SQL = 1;
  private static final int XLOG_EXT = 2;
  private static final int XLOG_ERRORS = 3;
  private static final int XLOG_LATE = 4;
  private static final int XLOG_SQL_MS = 5;
  private static final int STAT_SQL_ROWS = 0;
  private static final int STAT_SQL_COUNT = 1;
  private static final int STAT_EXT_ROWS = 2;
  private static final int STAT_EXT_COUNT = 3;
  private static final int STAT_SQL_MS = 4;

  private final long[] rows = new long[Table.ALL.size()];
  private final long[] clamped = new long[Table.ALL.size()];
  private final long[] lateRows = new long[Table.ALL.size()];
  private final long[] minTs = new long[Table.ALL.size()];
  private final long[] maxTs = new long[Table.ALL.size()];
  private final TreeMap<Long, long[]> xlogByDay = new TreeMap<>();
  private final TreeMap<Long, long[]> statsByDay = new TreeMap<>();
  private final TreeMap<Long, long[]> profileByDay = new TreeMap<>();
  private final TreeMap<Long, long[]> metricByDay = new TreeMap<>();
  private long rootTransactions;
  private long childTransactions;
  private long batchTransactions;
  private long capturedTransactions;
  private long summarizedTransactions;
  private long timedOutCount;
  private long lateBatches;
  private final long sampleThreshold;
  private final List<Map<String, Object>> txSamples = new ArrayList<>();
  private final List<Map<String, Object>> skewedSamples = new ArrayList<>();
  private final List<Map<String, Object>> traceSamples = new ArrayList<>();
  private final List<Map<String, Object>> userSamples = new ArrayList<>();
  private final List<Map<String, Object>> requestSamples = new ArrayList<>();
  private final List<Map<String, Object>> lateSamples = new ArrayList<>();

  /**
   * @param expectedXlogRows expected number of xlog rows (sets the sampling probability)
   */
  public Manifest(long expectedXlogRows) {
    for (int i = 0; i < minTs.length; i++) {
      minTs[i] = Long.MAX_VALUE;
      maxTs[i] = Long.MIN_VALUE;
    }
    double p = Math.min(1.0, 400.0 / Math.max(1, expectedXlogRows));
    this.sampleThreshold = (long) (p * (1L << 53));
  }

  /** Counts one row handed to the loader. */
  public void count(Table table, Row row, long receivedMs) {
    int t = table.index();
    rows[t]++;
    long ts = row.tsMs();
    minTs[t] = Math.min(minTs[t], ts);
    maxTs[t] = Math.max(maxTs[t], ts);
    if (receivedMs - ts > 60_000L) {
      lateRows[t]++;
    }
    long day = Math.floorDiv(ts, 86_400_000L);
    if (row instanceof Transaction tx) {
      long[] d = xlogByDay.computeIfAbsent(day, k -> new long[6]);
      d[XLOG_ROWS]++;
      d[XLOG_SQL] += tx.sqlCount;
      d[XLOG_EXT] += tx.extCount();
      d[XLOG_ERRORS] += tx.error ? 1 : 0;
      d[XLOG_LATE] += tx.late ? 1 : 0;
      d[XLOG_SQL_MS] += tx.sqlTimeMs;
      if (tx.clamped) {
        clamped[t]++;
      }
      if (tx.callerAppId != 0) {
        childTransactions++;
      } else {
        rootTransactions++;
      }
      if (tx.kind == Transaction.BATCH) {
        batchTransactions++;
      }
      if (tx.profileCaptured) {
        capturedTransactions++;
      }
      if ((tx.flags & Transaction.FLAG_SUMMARIZED) != 0) {
        summarizedTransactions++;
      }
      if (tx.timedOut) {
        timedOutCount++;
      }
      sample(tx);
    } else if (row instanceof StatRow stat) {
      long[] d = statsByDay.computeIfAbsent(day, k -> new long[5]);
      if (stat.sql()) {
        d[STAT_SQL_ROWS]++;
        d[STAT_SQL_COUNT] += stat.count;
        d[STAT_SQL_MS] += stat.sumMs;
      } else {
        d[STAT_EXT_ROWS]++;
        d[STAT_EXT_COUNT] += stat.count;
      }
      if (stat.clamped) {
        clamped[t]++;
      }
    } else if (row instanceof ProfileStep) {
      profileByDay.computeIfAbsent(day, k -> new long[1])[0]++;
    } else if (row instanceof MetricRow metric) {
      metricByDay.computeIfAbsent(day, k -> new long[1])[0]++;
      if (metric.clamped) {
        clamped[t]++;
      }
    }
  }

  /** Counts an agent batch delayed by a late arrival. */
  public void countLateBatch() {
    lateBatches++;
  }

  /** Rows counted for a table. */
  public long rows(Table table) {
    return rows[table.index()];
  }

  /** Sum of xlog.sql_count over all rows. */
  public long xlogSqlCount() {
    return xlogByDay.values().stream().mapToLong(d -> d[XLOG_SQL]).sum();
  }

  /** Sum of xlog.ext_count over all rows. */
  public long xlogExtCount() {
    return xlogByDay.values().stream().mapToLong(d -> d[XLOG_EXT]).sum();
  }

  /** Sum of xlog.sql_time_ms over all rows. */
  public long xlogSqlTimeMs() {
    return xlogByDay.values().stream().mapToLong(d -> d[XLOG_SQL_MS]).sum();
  }

  /** Sum of sql_stat_raw.count. */
  public long statSqlCount() {
    return statsByDay.values().stream().mapToLong(d -> d[STAT_SQL_COUNT]).sum();
  }

  /** Sum of sql_stat_raw.sum_ms. */
  public long statSqlMs() {
    return statsByDay.values().stream().mapToLong(d -> d[STAT_SQL_MS]).sum();
  }

  /** Sum of ext_stat_raw.count. */
  public long statExtCount() {
    return statsByDay.values().stream().mapToLong(d -> d[STAT_EXT_COUNT]).sum();
  }

  /** xlog rows with late = 1. */
  public long xlogLateRows() {
    return xlogByDay.values().stream().mapToLong(d -> d[XLOG_LATE]).sum();
  }

  /** Rows whose ts was clamped by the timestamp guard. */
  public long clampedRows(Table table) {
    return clamped[table.index()];
  }

  /** Rows that arrived more than 60 s after their ts. */
  public long lateRows(Table table) {
    return lateRows[table.index()];
  }

  /** Captured (profile_captured = 1) transactions. */
  public long capturedTransactions() {
    return capturedTransactions;
  }

  /** Child transactions (internal calls). */
  public long childTransactions() {
    return childTransactions;
  }

  /** Batch transactions. */
  public long batchTransactions() {
    return batchTransactions;
  }

  /** Summarized transactions (profile summary mode). */
  public long summarizedTransactions() {
    return summarizedTransactions;
  }

  /** Delayed agent batches. */
  public long lateBatches() {
    return lateBatches;
  }

  /** xlog rows by UTC day. */
  public Map<LocalDate, Long> xlogRowsByDay() {
    Map<LocalDate, Long> m = new LinkedHashMap<>();
    xlogByDay.forEach((d, v) -> m.put(LocalDate.ofEpochDay(d), v[XLOG_ROWS]));
    return m;
  }

  /** Sum of xlog.sql_count by UTC day of xlog.ts. */
  public Map<LocalDate, Long> xlogSqlCountByDay() {
    Map<LocalDate, Long> m = new LinkedHashMap<>();
    xlogByDay.forEach((d, v) -> m.put(LocalDate.ofEpochDay(d), v[XLOG_SQL]));
    return m;
  }

  /** Sum of sql_stat_raw.count by UTC day of the window ts. */
  public Map<LocalDate, Long> statSqlCountByDay() {
    Map<LocalDate, Long> m = new LinkedHashMap<>();
    statsByDay.forEach((d, v) -> m.put(LocalDate.ofEpochDay(d), v[STAT_SQL_COUNT]));
    return m;
  }

  /** Sum of xlog.ext_count by UTC day of xlog.ts. */
  public Map<LocalDate, Long> xlogExtCountByDay() {
    Map<LocalDate, Long> m = new LinkedHashMap<>();
    xlogByDay.forEach((d, v) -> m.put(LocalDate.ofEpochDay(d), v[XLOG_EXT]));
    return m;
  }

  /** Sum of ext_stat_raw.count by UTC day of the window ts. */
  public Map<LocalDate, Long> statExtCountByDay() {
    Map<LocalDate, Long> m = new LinkedHashMap<>();
    statsByDay.forEach((d, v) -> m.put(LocalDate.ofEpochDay(d), v[STAT_EXT_COUNT]));
    return m;
  }

  private void sample(Transaction tx) {
    long h = Hash.mix(tx.txId) >>> 11;
    if (h >= sampleThreshold
        && !(tx.instance.skewed && skewedSamples.size() < 20 && (h & 0xFF) == 0)) {
      return;
    }
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("app_id", tx.instance.app.id);
    s.put("instance_id", tx.instance.id);
    s.put("tx_id", Long.toUnsignedString(tx.txId));
    s.put("ts", Instant.ofEpochMilli(tx.tsMs).toString());
    s.put("service_id", Long.toUnsignedString(tx.service.id));
    s.put("kind", tx.kind == Transaction.BATCH ? "BATCH" : "HTTP");
    s.put("profile_captured", tx.profileCaptured);
    s.put("clamped", tx.clamped);
    s.put("late", tx.late);
    if (tx.instance.skewed) {
      if (skewedSamples.size() < 20) {
        skewedSamples.add(s);
      }
    } else if (tx.late) {
      if (lateSamples.size() < 20) {
        lateSamples.add(s);
      }
    } else if (txSamples.size() < 200) {
      txSamples.add(s);
    }
    if (traceSamples.size() < 100) {
      Map<String, Object> t = new LinkedHashMap<>();
      t.put("trace_id", HexFormat.of().formatHex(tx.traceId));
      t.put("app_id", tx.instance.app.id);
      t.put("ts", Instant.ofEpochMilli(tx.tsMs).toString());
      t.put("argos_generated", tx.argosTrace);
      t.put("transactions", 1 + tx.children.size());
      traceSamples.add(t);
    }
    if (!tx.userId.isEmpty() && userSamples.size() < 50) {
      userSamples.add(
          Map.of(
              "app_id",
              tx.instance.app.id,
              "user_id",
              tx.userId,
              "ts",
              Instant.ofEpochMilli(tx.tsMs).toString()));
    }
    if (!tx.requestId.isEmpty() && requestSamples.size() < 50) {
      requestSamples.add(
          Map.of(
              "app_id",
              tx.instance.app.id,
              "request_id",
              tx.requestId,
              "ts",
              Instant.ofEpochMilli(tx.tsMs).toString()));
    }
  }

  /** Samples for query parameters (Q4-Q6, Q11). */
  public Map<String, Object> samples() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("transactions", txSamples);
    m.put("skewedInstanceTransactions", skewedSamples);
    m.put("lateTransactions", lateSamples);
    m.put("traces", traceSamples);
    m.put("users", userSamples);
    m.put("requests", requestSamples);
    return m;
  }

  /** Counters as a JSON-ready map. */
  public Map<String, Object> toMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    Map<String, Object> tables = new LinkedHashMap<>();
    for (Table table : Table.ALL) {
      int t = table.index();
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("rows", rows[t]);
      e.put("minTs", rows[t] == 0 ? null : Instant.ofEpochMilli(minTs[t]).toString());
      e.put("maxTs", rows[t] == 0 ? null : Instant.ofEpochMilli(maxTs[t]).toString());
      e.put("clampedRows", clamped[t]);
      e.put("arrivedLaterThan60s", lateRows[t]);
      tables.put(table.name(), e);
    }
    m.put("tables", tables);
    Map<String, Object> tx = new LinkedHashMap<>();
    tx.put("root", rootTransactions);
    tx.put("child", childTransactions);
    tx.put("batch", batchTransactions);
    tx.put("profileCaptured", capturedTransactions);
    tx.put("summarized", summarizedTransactions);
    tx.put("timedOut", timedOutCount);
    tx.put("lateFlag", xlogLateRows());
    tx.put("lateAgentBatches", lateBatches);
    m.put("transactions", tx);
    Map<String, Object> d12 = new LinkedHashMap<>();
    d12.put("xlogSqlCount", xlogSqlCount());
    d12.put("sqlStatCount", statSqlCount());
    d12.put("xlogSqlTimeMs", xlogSqlTimeMs());
    d12.put("sqlStatSumMs", statSqlMs());
    d12.put("xlogExtCount", xlogExtCount());
    d12.put("extStatCount", statExtCount());
    m.put("d12Totals", d12);
    List<Map<String, Object>> days = new ArrayList<>();
    TreeMap<Long, Boolean> allDays = new TreeMap<>();
    xlogByDay.keySet().forEach(d -> allDays.put(d, true));
    statsByDay.keySet().forEach(d -> allDays.put(d, true));
    profileByDay.keySet().forEach(d -> allDays.put(d, true));
    metricByDay.keySet().forEach(d -> allDays.put(d, true));
    for (long day : allDays.keySet()) {
      long[] x = xlogByDay.getOrDefault(day, new long[6]);
      long[] s = statsByDay.getOrDefault(day, new long[5]);
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("day", LocalDate.ofEpochDay(day).toString());
      e.put("xlogRows", x[XLOG_ROWS]);
      e.put("xlogErrors", x[XLOG_ERRORS]);
      e.put("xlogLate", x[XLOG_LATE]);
      e.put("xlogSqlCount", x[XLOG_SQL]);
      e.put("sqlStatCount", s[STAT_SQL_COUNT]);
      e.put("xlogExtCount", x[XLOG_EXT]);
      e.put("extStatCount", s[STAT_EXT_COUNT]);
      e.put("sqlStatRows", s[STAT_SQL_ROWS]);
      e.put("extStatRows", s[STAT_EXT_ROWS]);
      e.put("profileRows", profileByDay.getOrDefault(day, new long[1])[0]);
      e.put("metricRows", metricByDay.getOrDefault(day, new long[1])[0]);
      days.add(e);
    }
    m.put("days", days);
    return m;
  }
}
