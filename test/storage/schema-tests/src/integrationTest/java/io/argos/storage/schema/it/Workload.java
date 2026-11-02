/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import io.argos.storage.schema.SchemaParameters;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * Small deterministic workload for the schema tests: transactions (xlog) with the SQL and outgoing
 * calls they made, the complete 10 s statistics windows derived from them (sql_stat_raw,
 * ext_stat_raw, so that D-12 holds by construction) and 5 s metric samples. Rows are TabSeparated
 * in the column order of the {@code *_COLUMNS} constants. Not the PoC generator (13 §15): that one
 * reproduces the full distributions at scale.
 *
 * <p>Row times are relative to the {@link #anchorDay() anchor day}, never fixed dates: the tests
 * render V001 with the production retention (SchemaParameters.defaults()), and ClickHouse drops
 * rows older than a table's TTL at insert time while the materialized views still receive them, so
 * a fixed date breaks every raw-versus-rollup assertion once it leaves the retention. {@link
 * #generate} and {@link #requireRetained} refuse row times that a table would not keep for the
 * whole run.
 */
final class Workload {

  /**
   * System property: the anchor day lies this many days before today (UTC). Default 2, at least 2
   * (the latest rows are at 00:10 of the day after the anchor day and must not be in the future).
   * Gradle: {@code -Pschema.anchorOffsetDays=<n>}. The largest offset the retention guard accepts
   * is 12: raw rows start at 00:00 of the anchor day and the raw tables keep 14 days, less one day
   * of margin.
   */
  static final String ANCHOR_OFFSET_PROPERTY = "argos.test.anchorOffsetDays";

  private static final int DEFAULT_ANCHOR_OFFSET_DAYS = 2;
  private static final int MIN_ANCHOR_OFFSET_DAYS = 2;

  /** Rows must stay in their table at least this long after the check (the length of a run). */
  private static final Duration RETENTION_MARGIN = Duration.ofDays(1);

  private static LocalDate anchor;

  /** 13 §2 latency bucket upper bounds (ms); the 15th bucket is above 30 s. */
  static final int[] BUCKET_BOUNDS = {
    1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000
  };

  static final String XLOG_COLUMNS =
      "app_id, env, instance_id, ts, tx_id, kind, service_id, app_version_id, http_method,"
          + " http_status, elapsed_ms, error, error_class_id, error_group_id, apdex_t_ms, remote_ip,"
          + " ip_mode, user_id, user_id_mode, request_id, sql_count, sql_time_ms, dbc_wait_ms, ext_count,"
          + " ext_time_ms, cpu_time_ms, alloc_bytes, thread_name_id, sql_ids";
  static final String SQL_STAT_COLUMNS =
      "app_id, env, instance_id, ts, kind, sql_id, db_id, count, error_count, sum_ms, sum_exec_ms,"
          + " sum_fetch_ms, max_ms, fetch_rows, buckets";
  static final String EXT_STAT_COLUMNS =
      "app_id, env, instance_id, ts, kind, host_id, count, error_count, timeout_count,"
          + " connect_fail_count, sum_ms, max_ms, buckets";
  static final String METRIC_COLUMNS = "app_id, env, instance_id, name, kind, ts, value, delta";

  // tx_id epoch 2026-01-01T00:00:00Z (13 §2). fixed-date-ok: a format constant, not a row time.
  private static final long TX_EPOCH_MS = 1_767_225_600_000L;
  private static final DateTimeFormatter MS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter SECONDS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

  final List<String> xlog = new ArrayList<>();
  final List<String> sqlStat = new ArrayList<>();
  final List<String> extStat = new ArrayList<>();
  final List<String> metric = new ArrayList<>();

  private Workload() {}

  /**
   * A deduplication token in the form of ADR-0007 C-61, {@code <wal_instance_id>:<epoch>:<first>-
   * <last>} (positions in lower-case hex), for blocks of this workload: {@code instance} names the
   * test (letters, digits, dash), the positions are row numbers.
   */
  static String token(String instance, long first, long last) {
    return instance + ":1:" + Long.toHexString(first) + "-" + Long.toHexString(last);
  }

  /** Base position of a raw table: the rows of different tables never share a position. */
  static long tableBase(String table) {
    return (SchemaParameters.RAW_TABLES.indexOf(table) + 1L) << 40;
  }

  /**
   * The day the tests write their rows around (UTC): today minus {@link #ANCHOR_OFFSET_PROPERTY}
   * days, computed once per JVM so that every class of a run uses the same day.
   */
  static synchronized LocalDate anchorDay() {
    if (anchor == null) {
      String value =
          System.getProperty(ANCHOR_OFFSET_PROPERTY, Integer.toString(DEFAULT_ANCHOR_OFFSET_DAYS));
      int offset;
      try {
        offset = Integer.parseInt(value.strip());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(ANCHOR_OFFSET_PROPERTY + "=" + value, e);
      }
      if (offset < MIN_ANCHOR_OFFSET_DAYS) {
        throw new IllegalArgumentException(
            ANCHOR_OFFSET_PROPERTY
                + "="
                + offset
                + ": at least "
                + MIN_ANCHOR_OFFSET_DAYS
                + " (rows reach 00:10 of the day after the anchor day)");
      }
      anchor = LocalDate.now(ZoneOffset.UTC).minusDays(offset);
      Evidence.record("workload", "anchor day (today - " + offset + " days, UTC)", anchor);
    }
    return anchor;
  }

  /** The anchor day plus {@code days} (may be negative) as a Date literal {@code yyyy-MM-dd}. */
  static String day(int days) {
    return anchorDay().plusDays(days).toString();
  }

  /** {@code time} (UTC) on the anchor day. */
  static Instant at(LocalTime time) {
    return at(0, time);
  }

  /** {@code time} (UTC) on the anchor day plus {@code days} (may be negative). */
  static Instant at(int days, LocalTime time) {
    return anchorDay().plusDays(days).atTime(time).toInstant(ZoneOffset.UTC);
  }

  /** A DateTime literal (UTC, seconds) for TabSeparated rows. */
  static String seconds(Instant instant) {
    return SECONDS.format(instant);
  }

  /** A DateTime64(3) literal (UTC, milliseconds) for TabSeparated rows and SQL. */
  static String millis(Instant instant) {
    return MS.format(instant);
  }

  /**
   * Refuses rows from {@code first} to {@code last} for a raw {@code table} unless the table keeps
   * them for the whole run: not in the future, and inside the table's retention of
   * SchemaParameters.defaults() (the schema the tests apply) with one day of margin. A row older
   * than the TTL would be dropped at insert time while the views still count it.
   *
   * @throws IllegalArgumentException if the table would not keep the rows
   */
  static void requireRetained(String table, Instant first, Instant last) {
    String key =
        switch (table) {
          case "xlog" -> "xlog-days";
          case "profile_step" -> "profile-days";
          case "metric", "sql_stat_raw", "ext_stat_raw" -> "raw-days";
          default -> throw new IllegalArgumentException("Not a raw table: " + table);
        };
    int days = SchemaParameters.defaults().retentionDays(key);
    Instant now = Instant.now();
    Instant oldest = now.minus(Duration.ofDays(days)).plus(RETENTION_MARGIN);
    if (last.isBefore(first) || first.isBefore(oldest) || last.isAfter(now)) {
      throw new IllegalArgumentException(
          table
              + " rows "
              + first
              + " .. "
              + last
              + " are outside ["
              + oldest
              + ", "
              + now
              + "] ("
              + key
              + " = "
              + days
              + ", one day of margin): derive row times from Workload.anchorDay()");
    }
  }

  static String insert(SchemaParameters params, String table, String columns) {
    return "INSERT INTO " + params.table(table) + " (" + columns + ") FORMAT TabSeparated";
  }

  /**
   * @param seed random seed (same seed, same rows)
   * @param start first minute (UTC), derived from {@link #anchorDay()}
   * @param minutes number of minutes covered
   * @param txPerMinute transactions per minute over all apps
   * @throws IllegalArgumentException if a table of the workload would not keep the rows ({@link
   *     #requireRetained})
   */
  static Workload generate(long seed, Instant start, int minutes, int txPerMinute) {
    Instant end = start.plus(Duration.ofMinutes(minutes));
    for (String table : List.of("xlog", "sql_stat_raw", "ext_stat_raw", "metric")) {
      requireRetained(table, start, end);
    }
    Random random = new Random(seed);
    Workload w = new Workload();
    Map<String, long[]> sqlWindows = new TreeMap<>();
    Map<String, long[]> extWindows = new TreeMap<>();
    long sequence = 0;
    long startMs = start.toEpochMilli();
    for (int m = 0; m < minutes; m++) {
      for (int t = 0; t < txPerMinute; t++) {
        int app = 1 + random.nextInt(3);
        String env = app == 3 ? "stg" : "prod";
        int instance = app * 10 + 1 + random.nextInt(2);
        long tsMs = startMs + m * 60_000L + random.nextInt(60_000);
        long txId =
            ((tsMs - TX_EPOCH_MS) << 23) | ((instance & 0xFFFL) << 11) | (sequence++ & 0x7FFL);
        boolean batch = random.nextInt(100) < 5;
        String kind = batch ? "BATCH" : "HTTP";
        // Batch jobs and HTTP endpoints are different services: kind is a function of service_id,
        // as V001 assumes for error_stat_1m (kind outside its sorting key).
        long service = app * 1000L + (batch ? 900 : 0) + random.nextInt(5);
        long version = 900L + (m < minutes / 2 ? 1 : 2);
        int elapsed =
            random.nextInt(100) == 0
                ? 2000 + random.nextInt(28_000)
                : (int) Math.max(1, Math.round(Math.exp(Math.log(80) + random.nextGaussian())));
        boolean error = random.nextInt(100) < 3;
        long errorGroup = error ? app * 100L + 1 + random.nextInt(4) : 0;
        int sqlCount = random.nextInt(5);
        int sqlTime = 0;
        Set<Long> sqlIds = new LinkedHashSet<>();
        for (int i = 0; i < sqlCount; i++) {
          long sqlId = app * 10_000L + random.nextInt(20);
          long dbId = app * 100L + 1;
          int ms = 1 + random.nextInt(60);
          int exec = ms * 3 / 4;
          boolean sqlError = random.nextInt(100) == 0;
          int rows = random.nextInt(100);
          sqlTime += ms;
          sqlIds.add(sqlId);
          String key =
              String.join(
                  "\t",
                  Integer.toString(app),
                  env,
                  Integer.toString(instance),
                  SECONDS.format(Instant.ofEpochMilli(tsMs / 10_000 * 10_000)),
                  kind,
                  Long.toString(sqlId),
                  Long.toString(dbId));
          long[] agg = sqlWindows.computeIfAbsent(key, k -> new long[7 + 15]);
          agg[0]++;
          agg[1] += sqlError ? 1 : 0;
          agg[2] += ms;
          agg[3] += exec;
          agg[4] += ms - exec;
          agg[5] = Math.max(agg[5], ms);
          agg[6] += rows;
          agg[7 + bucket(ms)]++;
        }
        int extCount = random.nextInt(3);
        int extTime = 0;
        for (int i = 0; i < extCount; i++) {
          long host = 500L + random.nextInt(3);
          int ms = 5 + random.nextInt(200);
          int failure = random.nextInt(200);
          extTime += ms;
          String key =
              String.join(
                  "\t",
                  Integer.toString(app),
                  env,
                  Integer.toString(instance),
                  SECONDS.format(Instant.ofEpochMilli(tsMs / 10_000 * 10_000)),
                  kind,
                  Long.toString(host));
          long[] agg = extWindows.computeIfAbsent(key, k -> new long[6 + 15]);
          agg[0]++;
          agg[1] += failure < 4 ? 1 : 0;
          agg[2] += failure == 4 ? 1 : 0;
          agg[3] += failure == 5 ? 1 : 0;
          agg[4] += ms;
          agg[5] = Math.max(agg[5], ms);
          agg[6 + bucket(ms)]++;
        }
        boolean measured = random.nextInt(10) != 0;
        boolean hasUser = random.nextInt(10) < 3;
        w.xlog.add(
            String.join(
                "\t",
                Integer.toString(app),
                env,
                Integer.toString(instance),
                MS.format(Instant.ofEpochMilli(tsMs)),
                Long.toUnsignedString(txId),
                kind,
                Long.toString(service),
                Long.toString(version),
                batch ? "" : (random.nextBoolean() ? "GET" : "POST"),
                batch ? (error ? "1" : "0") : (error ? "500" : "200"),
                Integer.toString(elapsed),
                error ? "1" : "0",
                error ? Long.toString(7000L + errorGroup % 7) : "0",
                Long.toString(errorGroup),
                "500",
                "::ffff:10.0." + random.nextInt(4) + "." + random.nextInt(256),
                "masked",
                hasUser ? String.format(Locale.ROOT, "%016x", random.nextLong()) : "",
                hasUser ? "hashed" : "off",
                random.nextBoolean() ? "req-" + sequence : "",
                Integer.toString(sqlCount),
                Integer.toString(sqlTime),
                Integer.toString(random.nextInt(10)),
                Integer.toString(extCount),
                Integer.toString(extTime),
                measured ? Integer.toString(elapsed / 2) : "-1",
                measured ? Integer.toString(random.nextInt(1 << 20)) : "-1",
                "77",
                array(sqlIds)));
      }
    }
    sqlWindows.forEach(
        (key, a) -> w.sqlStat.add(key + "\t" + join(a, 0, 7) + "\t" + buckets(a, 7)));
    extWindows.forEach(
        (key, a) -> w.extStat.add(key + "\t" + join(a, 0, 6) + "\t" + buckets(a, 6)));
    w.addMetrics(random, start, minutes);
    return w;
  }

  private void addMetrics(Random random, Instant start, int minutes) {
    for (int app = 1; app <= 3; app++) {
      String env = app == 3 ? "stg" : "prod";
      for (int instance = app * 10 + 1; instance <= app * 10 + 2; instance++) {
        double cumulative = 1000;
        for (int s = 0; s < minutes * 12; s++) {
          String ts = SECONDS.format(start.plusSeconds(5L * s));
          double heap = 100_000_000 + random.nextInt(50_000_000) + 0.5;
          double delta = random.nextInt(50);
          cumulative += delta;
          metric.add(
              String.join(
                  "\t",
                  Integer.toString(app),
                  env,
                  Integer.toString(instance),
                  "jvm_memory_used_bytes{area=\"heap\"}",
                  "gauge",
                  ts,
                  Double.toString(heap),
                  "0"));
          metric.add(
              String.join(
                  "\t",
                  Integer.toString(app),
                  env,
                  Integer.toString(instance),
                  "http_server_requests_total",
                  "counter",
                  ts,
                  Double.toString(cumulative),
                  Double.toString(delta)));
        }
      }
    }
  }

  /** The rows split into blocks of at most {@code size} rows, joined as TabSeparated bodies. */
  static List<String> blocks(List<String> rows, int size) {
    List<String> blocks = new ArrayList<>();
    for (int i = 0; i < rows.size(); i += size) {
      blocks.add(String.join("\n", rows.subList(i, Math.min(rows.size(), i + size))) + "\n");
    }
    return blocks;
  }

  static int bucket(long ms) {
    for (int i = 0; i < BUCKET_BOUNDS.length; i++) {
      if (ms <= BUCKET_BOUNDS[i]) {
        return i;
      }
    }
    return BUCKET_BOUNDS.length;
  }

  private static String join(long[] values, int from, int to) {
    StringBuilder sb = new StringBuilder();
    for (int i = from; i < to; i++) {
      if (i > from) {
        sb.append('\t');
      }
      sb.append(values[i]);
    }
    return sb.toString();
  }

  private static String buckets(long[] values, int from) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < 15; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(values[from + i]);
    }
    return sb.append(']').toString();
  }

  private static String array(Set<Long> values) {
    StringBuilder sb = new StringBuilder("[");
    for (Long v : values) {
      if (sb.length() > 1) {
        sb.append(',');
      }
      sb.append(v);
    }
    return sb.append(']').toString();
  }
}
