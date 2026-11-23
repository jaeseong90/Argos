/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.ops.migration.MigrationSource;
import io.argos.server.ops.migration.RollupSelects;
import io.argos.server.ops.migration.SchemaParameters;
import io.argos.server.ops.rollup.ConsistencyChecks.Bucket;
import io.argos.server.ops.rollup.ConsistencyChecks.DayTotals;
import io.argos.server.ops.rollup.ConsistencyChecks.Mismatch;
import io.argos.server.ops.rollup.ConsistencyChecks.Spec;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The comparison catalog and the queries of the consistency check (12 §9.1, D-03, D-12, C-57). */
@Tag("req:D-14")
@Tag("req:F-S22")
@Tag("req:D-03")
@Tag("req:D-12")
class ConsistencyChecksTest {

  private static final RollupSelects SELECTS =
      RollupSelects.of(MigrationSource.fromClasspath(ConsistencyChecksTest.class.getClassLoader()));
  private static final Pattern GROUP_BY = Pattern.compile("(?is)GROUP BY\\s+(.+?)\\s*$");
  private static final LocalDate DAY = LocalDate.of(2026, 11, 22);

  @Test
  @DisplayName(
      "T-SV-019 every bundled rollup has exactly one comparison against its own source, keyed by"
          + " the rollup's GROUP BY columns (without app_id and the time)")
  void catalogMatchesRollupFiles() {
    List<String> tables = new ArrayList<>();
    for (Spec spec : ConsistencyChecks.SPECS) {
      tables.add(spec.table());
      assertThat(SELECTS.sourceTable(spec.table())).as(spec.table()).contains(spec.source());
      String select = SELECTS.select(spec.table(), SchemaParameters.defaults(), "");
      Matcher m = GROUP_BY.matcher(select.strip());
      assertThat(m.find()).as(spec.table()).isTrue();
      List<String> keys = new ArrayList<>();
      for (String column : m.group(1).split(",", -1)) {
        String c = column.strip().toLowerCase(Locale.ROOT);
        if (!c.equals("app_id") && !c.equals("minute") && !c.equals("hour")) {
          keys.add(c);
        }
      }
      assertThat(spec.keys()).as(spec.table()).containsExactlyElementsOf(keys);
      assertThat(spec.bucket())
          .isEqualTo(spec.table().endsWith("_1h") ? Bucket.HOUR : Bucket.MINUTE);
    }
    assertThat(tables).containsExactlyInAnyOrderElementsOf(SELECTS.tables());
    assertThat(ConsistencyChecks.SPECS.stream().filter(s -> s.check().equals("d03")))
        .extracting(Spec::table)
        .containsExactly("app_stat_1m", "service_stat_1m", "instance_stat_1m");
  }

  @Test
  @DisplayName(
      "T-SV-019 the retention that must hold the day: the raw source of a 1m rollup,"
          + " rollup-1m-days for a 1h rollup")
  void retentionKeys() {
    for (Spec spec : ConsistencyChecks.SPECS) {
      String expected =
          switch (spec.source()) {
            case "xlog" -> "xlog-days";
            case "sql_stat_raw", "ext_stat_raw", "metric" -> "raw-days";
            default -> "rollup-1m-days";
          };
      assertThat(spec.retentionKey()).as(spec.table()).isEqualTo(expected);
    }
  }

  @Test
  @DisplayName(
      "T-SV-019 D-03 query: xlog count() per minute and key against sum(count) of the rollup, one"
          + " day, every app, bounded result")
  void d03Query() {
    Spec service = spec("service_stat_1m");
    SchemaParameters params =
        new SchemaParameters("argos", "t1_", SchemaParameters.defaults().retentionDays());
    String sql = ConsistencyChecks.mismatchQuery(service, "service_stat_1m", "xlog", params, DAY);
    assertThat(sql)
        .contains("FROM argos.t1_xlog WHERE toDate(ts) = '2026-11-22' AND (true)")
        .contains("FROM argos.t1_service_stat_1m WHERE toDate(minute) = '2026-11-22'")
        .contains("toInt64(count()) AS s0")
        .contains("toInt64(sum(count)) AS r0")
        .contains("GROUP BY app_id, b, env, kind, service_id, app_version_id")
        .contains("HAVING s != r")
        .endsWith("LIMIT 10000");
    String hour =
        ConsistencyChecks.mismatchQuery(
            spec("metric_1h"), "metric_1h", "metric_1m", SchemaParameters.defaults(), DAY);
    assertThat(hour)
        .contains("toDateTime(toStartOfHour(minute), 'UTC') AS b")
        .contains("FROM argos.metric_1m WHERE toDate(minute) = '2026-11-22'")
        .contains("FROM argos.metric_1h WHERE toDate(hour) = '2026-11-22'")
        .contains("toInt64(sum(sample_count)) AS s0");
    String errors =
        ConsistencyChecks.mismatchQuery(
            spec("error_stat_1m"), "error_stat_1m", "xlog", SchemaParameters.defaults(), DAY);
    assertThat(errors).contains("AND (error = 1 AND error_group_id != 0)");
  }

  @Test
  @DisplayName(
      "T-SV-019 D-12 queries: per minute against the crossing-transaction bound (C-57 per"
          + " minute), per day against the midnight bound (C-57), previous day read for spans")
  void d12Queries() {
    ConsistencyChecks.D12Spec sql = ConsistencyChecks.D12_SPECS.get(0);
    String minute = ConsistencyChecks.d12MinuteQuery(sql, SchemaParameters.defaults(), DAY);
    assertThat(minute)
        .contains("toInt64(sum(sql_count)) AS x0")
        .contains("FROM argos.sql_stat_raw WHERE toDate(ts) = '2026-11-22'")
        .contains("toDate(ts) >= '2026-11-21' AND toDate(ts) <= '2026-11-22' AND sql_count > 0")
        .contains("WHERE em > sm")
        .contains("HAVING abs(x - s) > c");
    String day =
        ConsistencyChecks.d12DayQuery(
            ConsistencyChecks.D12_SPECS.get(1), SchemaParameters.defaults(), DAY);
    assertThat(day)
        .contains("sumIf(ext_count, toDate(ts) = '2026-11-22')")
        .contains("toDateTime64('2026-11-22 00:00:00', 3, 'UTC')")
        .contains("toDateTime64('2026-11-23 00:00:00', 3, 'UTC')")
        .contains("FROM argos.ext_stat_raw WHERE toDate(ts) = '2026-11-22'");
  }

  @Test
  @DisplayName("T-SV-019 result lines are parsed; other shapes are refused")
  void parse() {
    assertThat(ConsistencyChecks.parseMismatch("12\t3\t10:00,10:01,10:05"))
        .isEqualTo(new Mismatch(12, 3, List.of("10:00", "10:01", "10:05")));
    assertThat(ConsistencyChecks.parseMismatch("4\t0\t").first()).isEmpty();
    assertThatThrownBy(() -> ConsistencyChecks.parseMismatch("4\t1"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ConsistencyChecks.parseMismatch("x\t1\t"))
        .isInstanceOf(IllegalStateException.class);
    DayTotals totals = ConsistencyChecks.parseDayTotals("3\t1000\t990\t12");
    assertThat(totals).isEqualTo(new DayTotals(3, 1000, 990, 12));
    assertThat(totals.withinBound()).isTrue();
    assertThat(new DayTotals(3, 1000, 980, 12).withinBound()).isFalse();
    assertThat(new DayTotals(3, 980, 1000, 20).withinBound()).isTrue();
    assertThatThrownBy(() -> ConsistencyChecks.parseDayTotals("3\t1\t2"))
        .isInstanceOf(IllegalStateException.class);
  }

  private static Spec spec(String table) {
    return ConsistencyChecks.SPECS.stream()
        .filter(s -> s.table().equals(table))
        .findFirst()
        .orElseThrow();
  }
}
