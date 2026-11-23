/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import io.argos.server.ops.migration.SchemaParameters;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The comparisons of the consistency check (12 §9.1, 13 §6.3 (13) and (14), D-03, D-12, D-14,
 * ADR-0007 C-57) and their ClickHouse queries. Every query covers one UTC day and every application
 * at once and returns one line per application that has a difference ({@code
 * <app_id>\t<buckets>\t<first buckets>}, at most {@link #MAX_APPS} lines), so the work is one scan
 * per comparison, not one per application.
 *
 * <ul>
 *   <li>{@link Spec}: a rollup against its source, per time bucket and per rollup key (all {@code
 *       GROUP BY} columns of the rollup except the time): the number of rows ({@code count()}) or
 *       the summed count column of the source against the summed count column of the rollup. A
 *       bucket differs when any key in it differs. {@code d03}: {@code xlog} against {@code
 *       app_stat_1m}, {@code service_stat_1m}, {@code instance_stat_1m} (all kinds); {@code
 *       rollup_1m}: the raw table against the other 1m rollups; {@code rollup_1h}: each 1h rollup
 *       against its 1m source, per hour of the day. These can be repaired by a rebuild.
 *   <li>D-12 ({@link #d12MinuteQuery}, {@link #d12DayQuery}): {@code Σ xlog.sql_count} against
 *       {@code Σ sql_stat_raw.count} (and {@code ext_count} against {@code ext_stat_raw}). The
 *       agent puts a statement into the 10 s window in which it ended (C-57), a transaction into
 *       the minute in which it started, so the two sides differ legitimately by the statements of
 *       transactions that cross a boundary. A minute differs when {@code |xlog - statistics|} is
 *       larger than the count of the transactions that touch the minute and cross one of its
 *       boundaries (the C-57 bound applied to each minute); the day differs when it is larger than
 *       the count of the transactions that cross one of its midnights (C-57 as written). Recorded
 *       only: the two sides are different streams, a rebuild cannot repair them (12 §9.1).
 * </ul>
 *
 * Names and literals come from this catalog, the server configuration and a {@link LocalDate},
 * never from request input.
 */
public final class ConsistencyChecks {

  /** Most applications one query reports. */
  public static final int MAX_APPS = 10_000;

  /** Most mismatching buckets listed per application and comparison. */
  public static final int SAMPLE_BUCKETS = 10;

  /** {@code check_name} of the xlog-to-statistics comparisons (D-03). */
  public static final String D03 = "d03";

  /** {@code check_name} of the other 1m rollups against their raw table. */
  public static final String ROLLUP_1M = "rollup_1m";

  /** {@code check_name} of the 1h rollups against their 1m source. */
  public static final String ROLLUP_1H = "rollup_1h";

  /** {@code check_name} of the SQL and outgoing-call statistics against xlog (D-12). */
  public static final String D12 = "d12";

  private ConsistencyChecks() {}

  /** Time bucket of a comparison. */
  public enum Bucket {
    /** One minute of a 1m rollup, from a raw table. */
    MINUTE(1),
    /** One hour of a 1h rollup, from a 1m rollup. */
    HOUR(60);

    private final int minutes;

    Bucket(int minutes) {
      this.minutes = minutes;
    }

    /** Minutes of one bucket. */
    public int minutes() {
      return minutes;
    }
  }

  /**
   * One rollup against its source.
   *
   * @param check {@link #D03}, {@link #ROLLUP_1M} or {@link #ROLLUP_1H}
   * @param table the logical rollup table
   * @param source the logical source table (raw table or 1m rollup)
   * @param bucket minute or hour
   * @param sourceFilter extra predicate on the source ({@code true} for none; the rollup's own
   *     {@code WHERE})
   * @param sourceMeasure the count on the source side
   * @param rollupMeasure the count on the rollup side
   * @param keys the rollup's {@code GROUP BY} columns without {@code app_id} and the time
   */
  public record Spec(
      String check,
      String table,
      String source,
      Bucket bucket,
      String sourceFilter,
      String sourceMeasure,
      String rollupMeasure,
      List<String> keys) {

    public Spec {
      keys = List.copyOf(keys);
    }

    /**
     * The retention key that must cover the day for the comparison to mean anything: the raw source
     * of a 1m rollup, {@code rollup-1m-days} for a 1h rollup.
     */
    public String retentionKey() {
      return bucket == Bucket.HOUR ? "rollup-1m-days" : RollupRebuilder.rawRetentionKey(source);
    }
  }

  private static Spec minute(
      String check, String table, String source, String filter, String measure, String... keys) {
    return new Spec(
        check, table, source, Bucket.MINUTE, filter, measure, "sum(count)", Arrays.asList(keys));
  }

  private static Spec hour(String table, String source, String measure, String... keys) {
    return new Spec(
        ROLLUP_1H, table, source, Bucket.HOUR, "true", measure, measure, Arrays.asList(keys));
  }

  /** The comparisons, in check order (D-03 first, then the other 1m rollups, then 1h). */
  public static final List<Spec> SPECS =
      List.of(
          minute(D03, "app_stat_1m", "xlog", "true", "count()", "env", "kind"),
          minute(
              D03,
              "service_stat_1m",
              "xlog",
              "true",
              "count()",
              "env",
              "kind",
              "service_id",
              "app_version_id"),
          minute(D03, "instance_stat_1m", "xlog", "true", "count()", "env", "instance_id", "kind"),
          minute(
              ROLLUP_1M,
              "error_stat_1m",
              "xlog",
              "error = 1 AND error_group_id != 0",
              "count()",
              "env",
              "kind",
              "error_group_id",
              "service_id",
              "app_version_id"),
          minute(
              ROLLUP_1M,
              "sql_stat_1m",
              "sql_stat_raw",
              "true",
              "sum(count)",
              "env",
              "kind",
              "sql_id",
              "db_id"),
          minute(
              ROLLUP_1M,
              "ext_stat_1m",
              "ext_stat_raw",
              "true",
              "sum(count)",
              "env",
              "kind",
              "host_id"),
          new Spec(
              ROLLUP_1M,
              "metric_1m",
              "metric",
              Bucket.MINUTE,
              "true",
              "count()",
              "sum(sample_count)",
              List.of("env", "instance_id", "name", "kind")),
          hour("app_stat_1h", "app_stat_1m", "sum(count)", "env", "kind"),
          hour(
              "service_stat_1h",
              "service_stat_1m",
              "sum(count)",
              "env",
              "kind",
              "service_id",
              "app_version_id"),
          hour("instance_stat_1h", "instance_stat_1m", "sum(count)", "env", "instance_id", "kind"),
          hour("sql_stat_1h", "sql_stat_1m", "sum(count)", "env", "kind", "sql_id", "db_id"),
          hour("ext_stat_1h", "ext_stat_1m", "sum(count)", "env", "kind", "host_id"),
          hour(
              "metric_1h", "metric_1m", "sum(sample_count)", "env", "instance_id", "name", "kind"));

  /**
   * One D-12 comparison.
   *
   * @param name {@code sql} or {@code ext}
   * @param xlogCount the per-transaction count column of {@code xlog}
   * @param rawTable the statistics table
   */
  public record D12Spec(String name, String xlogCount, String rawTable) {}

  /** The D-12 comparisons. */
  public static final List<D12Spec> D12_SPECS =
      List.of(
          new D12Spec("sql", "sql_count", "sql_stat_raw"),
          new D12Spec("ext", "ext_count", "ext_stat_raw"));

  /**
   * The mismatch query of {@code spec} for {@code day}: one line per application with a difference,
   * {@code <app_id>\t<mismatching buckets>\t<first buckets, comma separated>}.
   *
   * @param table the physical rollup table (alias resolved, without prefix)
   * @param source the physical source table (alias resolved, without prefix)
   */
  public static String mismatchQuery(
      Spec spec, String table, String source, SchemaParameters params, LocalDate day) {
    String keys = String.join(", ", spec.keys());
    String sourceBucket;
    String sourceDay;
    String rollupBucket;
    String rollupDay;
    if (spec.bucket() == Bucket.MINUTE) {
      sourceBucket = "toStartOfMinute(ts)";
      sourceDay = "toDate(ts) = '" + day + "'";
      rollupBucket = "minute";
      rollupDay = "toDate(minute) = '" + day + "'";
    } else {
      sourceBucket = "toStartOfHour(minute)";
      sourceDay = "toDate(minute) = '" + day + "'";
      rollupBucket = "hour";
      rollupDay = "toDate(hour) = '" + day + "'";
    }
    return "SELECT concat(toString(app_id), '\\t', toString(uniqExact(b)), '\\t',"
        + " arrayStringConcat(arraySlice(arraySort(groupUniqArray(formatDateTime(b, '%H:%i'))), 1, "
        + SAMPLE_BUCKETS
        + "), ','))"
        + " FROM (SELECT app_id, b, "
        + keys
        + ", sum(s0) AS s, sum(r0) AS r FROM ("
        + "SELECT app_id, toDateTime("
        + sourceBucket
        + ", 'UTC') AS b, "
        + keys
        + ", toInt64("
        + spec.sourceMeasure()
        + ") AS s0, toInt64(0) AS r0 FROM "
        + params.table(source)
        + " WHERE "
        + sourceDay
        + " AND ("
        + spec.sourceFilter()
        + ") GROUP BY app_id, b, "
        + keys
        + " UNION ALL SELECT app_id, toDateTime("
        + rollupBucket
        + ", 'UTC') AS b, "
        + keys
        + ", toInt64(0) AS s0, toInt64("
        + spec.rollupMeasure()
        + ") AS r0 FROM "
        + params.table(table)
        + " WHERE "
        + rollupDay
        + " GROUP BY app_id, b, "
        + keys
        + ") GROUP BY app_id, b, "
        + keys
        + " HAVING s != r) GROUP BY app_id ORDER BY app_id LIMIT "
        + MAX_APPS;
  }

  /**
   * The D-12 per-minute query for {@code day}: one line per application with minutes beyond the
   * bound, {@code <app_id>\t<minutes>\t<first minutes>}. The bound of a minute is the count of the
   * transactions whose span (start minute to end minute, at most two days) covers the minute and
   * crosses one of its boundaries; transactions of the previous day are read for the spans that
   * reach into the day.
   */
  public static String d12MinuteQuery(D12Spec spec, SchemaParameters params, LocalDate day) {
    String count = spec.xlogCount();
    String xlog = params.table("xlog");
    return "SELECT concat(toString(app_id), '\\t', toString(count()), '\\t',"
        + " arrayStringConcat(arraySlice(arraySort(groupArray(formatDateTime(b, '%H:%i'))), 1, "
        + SAMPLE_BUCKETS
        + "), ','))"
        + " FROM (SELECT app_id, b, sum(x0) AS x, sum(s0) AS s, sum(c0) AS c FROM ("
        + "SELECT app_id, toDateTime(toStartOfMinute(ts), 'UTC') AS b, toInt64(sum("
        + count
        + ")) AS x0, toInt64(0) AS s0, toInt64(0) AS c0 FROM "
        + xlog
        + " WHERE toDate(ts) = '"
        + day
        + "' GROUP BY app_id, b"
        + " UNION ALL SELECT app_id, toDateTime(toStartOfMinute(ts), 'UTC') AS b, toInt64(0) AS x0,"
        + " toInt64(sum(count)) AS s0, toInt64(0) AS c0 FROM "
        + params.table(spec.rawTable())
        + " WHERE toDate(ts) = '"
        + day
        + "' GROUP BY app_id, b"
        + " UNION ALL SELECT app_id, b, toInt64(0) AS x0, toInt64(0) AS s0, toInt64(sum(n)) AS c0"
        + " FROM (SELECT app_id, "
        + count
        + " AS n, arrayJoin(arrayMap(i -> addMinutes(sm, i), range(toUInt32(least(dateDiff("
        + "'minute', sm, em), 2880)) + 1))) AS b FROM (SELECT app_id, "
        + count
        + ", toDateTime(toStartOfMinute(ts), 'UTC') AS sm,"
        + " toDateTime(toStartOfMinute(addMilliseconds(ts, elapsed_ms)), 'UTC') AS em FROM "
        + xlog
        + " WHERE toDate(ts) >= '"
        + day.minusDays(1)
        + "' AND toDate(ts) <= '"
        + day
        + "' AND "
        + count
        + " > 0) WHERE em > sm) WHERE toDate(b) = '"
        + day
        + "' GROUP BY app_id, b"
        + ") GROUP BY app_id, b HAVING abs(x - s) > c) GROUP BY app_id ORDER BY app_id LIMIT "
        + MAX_APPS;
  }

  /**
   * The D-12 day totals for {@code day} (13 §6.3 (14) with the C-57 bound): one line per
   * application with data, {@code <app_id>\t<Σ xlog count>\t<Σ statistics count>\t<bound>}.
   */
  public static String d12DayQuery(D12Spec spec, SchemaParameters params, LocalDate day) {
    String count = spec.xlogCount();
    String start = "toDateTime64('" + day + " 00:00:00', 3, 'UTC')";
    String end = "toDateTime64('" + day.plusDays(1) + " 00:00:00', 3, 'UTC')";
    String crossing =
        "(ts < "
            + start
            + " AND addMilliseconds(ts, elapsed_ms) >= "
            + start
            + ") OR (ts < "
            + end
            + " AND addMilliseconds(ts, elapsed_ms) >= "
            + end
            + ")";
    return "SELECT concat(toString(app_id), '\\t', toString(sum(x0)), '\\t', toString(sum(s0)),"
        + " '\\t', toString(sum(c0))) FROM (SELECT app_id, toInt64(sumIf("
        + count
        + ", toDate(ts) = '"
        + day
        + "')) AS x0, toInt64(0) AS s0, toInt64(sumIf("
        + count
        + ", "
        + crossing
        + ")) AS c0 FROM "
        + params.table("xlog")
        + " WHERE toDate(ts) >= '"
        + day.minusDays(1)
        + "' AND toDate(ts) <= '"
        + day
        + "' GROUP BY app_id"
        + " UNION ALL SELECT app_id, toInt64(0) AS x0, toInt64(sum(count)) AS s0, toInt64(0) AS c0"
        + " FROM "
        + params.table(spec.rawTable())
        + " WHERE toDate(ts) = '"
        + day
        + "' GROUP BY app_id) GROUP BY app_id ORDER BY app_id LIMIT "
        + MAX_APPS;
  }

  /**
   * One line of a mismatch query.
   *
   * @param appId the application
   * @param buckets mismatching buckets (minutes or hours)
   * @param first the first mismatching buckets ({@code HH:mm}, UTC), at most {@link
   *     #SAMPLE_BUCKETS}
   */
  public record Mismatch(int appId, int buckets, List<String> first) {

    public Mismatch {
      first = List.copyOf(first);
    }
  }

  /**
   * Parses a line of {@link #mismatchQuery} or {@link #d12MinuteQuery}.
   *
   * @throws IllegalStateException for a line of another shape
   */
  public static Mismatch parseMismatch(String line) {
    String[] parts = line.split("\t", -1);
    if (parts.length != 3) {
      throw new IllegalStateException("Unexpected consistency query line: " + shorten(line));
    }
    try {
      List<String> first = new ArrayList<>();
      for (String bucket : parts[2].split(",", -1)) {
        if (!bucket.isEmpty()) {
          first.add(bucket);
        }
      }
      return new Mismatch(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), first);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Unexpected consistency query line: " + shorten(line), e);
    }
  }

  /**
   * One line of {@link #d12DayQuery}.
   *
   * @param appId the application
   * @param xlog {@code Σ xlog.<count>} of the day
   * @param statistics {@code Σ <raw>.count} of the day
   * @param bound the count of the transactions crossing one of the day's midnights (C-57)
   */
  public record DayTotals(int appId, long xlog, long statistics, long bound) {

    /** {@code |xlog - statistics| <= bound} (C-57). */
    public boolean withinBound() {
      return Math.abs(xlog - statistics) <= bound;
    }
  }

  /**
   * Parses a line of {@link #d12DayQuery}.
   *
   * @throws IllegalStateException for a line of another shape
   */
  public static DayTotals parseDayTotals(String line) {
    String[] parts = line.split("\t", -1);
    if (parts.length != 4) {
      throw new IllegalStateException("Unexpected D-12 query line: " + shorten(line));
    }
    try {
      return new DayTotals(
          Integer.parseInt(parts[0]),
          Long.parseLong(parts[1]),
          Long.parseLong(parts[2]),
          Long.parseLong(parts[3]));
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Unexpected D-12 query line: " + shorten(line), e);
    }
  }

  /** The physical name of a logical table ({@code settings.rollup_alias}). */
  static String physical(String logical, Map<String, String> aliases) {
    return aliases.getOrDefault(logical, logical);
  }

  private static String shorten(String text) {
    String oneLine = text.replaceAll("\\p{Cntrl}", "?");
    return oneLine.length() > 120 ? oneLine.substring(0, 117) + "..." : oneLine;
  }
}
