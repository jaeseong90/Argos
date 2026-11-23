/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.ops.migration.MigrationSource;
import io.argos.server.ops.migration.RollupSelects;
import io.argos.server.ops.migration.SchemaParameters;
import io.argos.server.storage.clickhouse.RollupPartitions;
import io.argos.server.storage.clickhouse.RollupPartitions.StatementException;
import io.argos.server.storage.postgres.ops.ReconcileResultRepository.Row;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The flow of {@link Reconciler} against a ClickHouse double: check, rebuild the mismatching tables
 * of the day, check them again, a second rebuild for rows that raced the first, D-12 recorded only,
 * and the {@code reconcile_results} rows (12 §9.1, D-14, F-S22).
 */
@Tag("req:D-14")
@Tag("req:F-S22")
class ReconcilerTest {

  private static final RollupSelects SELECTS =
      RollupSelects.of(MigrationSource.fromClasspath(ReconcilerTest.class.getClassLoader()));
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-23T03:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate DAY = LocalDate.of(2026, 11, 22);
  private static final Pattern ROLLUP_SIDE = Pattern.compile("AS r0 FROM argos\\.(\\w+) WHERE");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** ClickHouse double: scripted answers per checked table; rebuild statements succeed. */
  private static final class FakeClickHouse
      implements RollupPartitions.Statements<StatementException> {
    final Map<String, Deque<List<String>>> answers = new HashMap<>();
    final List<String> d12Minute = new ArrayList<>();
    final List<String> d12Day = new ArrayList<>();
    final List<String> replaced = new ArrayList<>();
    final List<String> executed = new ArrayList<>();
    String failOn;

    void answer(String table, List<String> first, List<String> after) {
      Deque<List<String>> q = new ArrayDeque<>();
      q.add(first);
      q.add(after);
      answers.put(table, q);
    }

    @Override
    public void execute(String statement) throws StatementException {
      executed.add(statement);
      if (failOn != null && statement.contains(failOn)) {
        throw new StatementException("ClickHouse: Code: 241. MEMORY_LIMIT_EXCEEDED", null);
      }
      if (statement.contains(" REPLACE PARTITION ")) {
        replaced.add(statement.split(" ", -1)[2]);
      }
    }

    @Override
    public List<String> queryColumn(String query) {
      if (query.startsWith("SELECT name FROM system.tables")) {
        List<String> tables =
            new ArrayList<>(
                List.of("xlog", "profile_step", "metric", "sql_stat_raw", "ext_stat_raw"));
        tables.addAll(SELECTS.tables());
        return tables;
      }
      if (query.startsWith("SELECT concat(name, '\\t', type) FROM system.columns")) {
        return List.of(
            "app_id\tUInt32",
            "minute\tDateTime('UTC')",
            "count\tSimpleAggregateFunction(sum, UInt64)");
      }
      if (query.startsWith("SELECT toString(count()) FROM ")) {
        return List.of("5");
      }
      if (query.contains("HAVING abs(x - s) > c")) {
        return query.contains("sql_stat_raw") ? d12Minute : List.of();
      }
      if (query.contains("toString(sum(c0))")) {
        return query.contains("sql_stat_raw") ? d12Day : List.of();
      }
      Matcher m = ROLLUP_SIDE.matcher(query);
      String rollup = null;
      while (m.find()) {
        rollup = m.group(1); // the rollup side follows the source side
      }
      if (rollup != null) {
        Deque<List<String>> q = answers.get(rollup);
        if (q == null || q.isEmpty()) {
          return List.of();
        }
        return q.size() > 1 ? q.poll() : q.peek();
      }
      // A digest: changes once the table was replaced.
      String table = query.substring(query.indexOf(" FROM argos.") + 12).split(" ", -1)[0];
      return List.of(replaced.contains("argos." + table) ? "10:999" : "10:123");
    }
  }

  private static Reconciler reconciler(FakeClickHouse ch) {
    SchemaParameters params = SchemaParameters.defaults();
    return new Reconciler(
        ch, params, Map.of(), new RollupRebuilder(SELECTS, params, ch, CLOCK), CLOCK);
  }

  @Test
  @DisplayName(
      "T-SV-019 a clean day: every comparison runs (13 rollups, 2 D-12), nothing is rebuilt, one"
          + " summary row 'clean'")
  void cleanDay() throws Exception {
    FakeClickHouse ch = new FakeClickHouse();
    ch.d12Day.add("1\t500\t500\t0");
    ReconcileReport report = reconciler(ch).run(DAY, ReconcileReport.SCHEDULED, true);
    assertThat(report.tables()).hasSize(13);
    assertThat(report.d12()).hasSize(2);
    assertThat(report.result()).isEqualTo("clean");
    assertThat(report.rebuilds()).isEmpty();
    assertThat(ch.replaced).isEmpty();
    List<Row> rows = report.rows(CLOCK.instant());
    assertThat(rows).hasSize(1);
    Row summary = rows.get(0);
    assertThat(summary.appId()).isNull();
    assertThat(summary.checkName()).isEqualTo("summary");
    assertThat(summary.rebuildResult()).isEqualTo("clean");
    assertThat(summary.mismatchMinutes()).isZero();
    assertThat(JSON.readTree(summary.detailJson()).get("trigger").asString())
        .isEqualTo("scheduled");
  }

  @Test
  @DisplayName(
      "T-SV-019 T-ACC-14 mismatches in service_stat_1m (7 minutes) and service_stat_1h (1 hour)"
          + " are rebuilt once for the day and its month, checked again: repaired; D-03 gauge"
          + " minutes = 7; D-12 minutes recorded only")
  void repairs() throws Exception {
    FakeClickHouse ch = new FakeClickHouse();
    ch.answer("service_stat_1m", List.of("3\t7\t10:00,10:01"), List.of());
    ch.answer("service_stat_1h", List.of("3\t1\t10:00"), List.of());
    ch.d12Minute.add("3\t2\t11:00,11:01");
    ch.d12Day.add("3\t1000\t2000\t5");
    ReconcileReport report = reconciler(ch).run(DAY, ReconcileReport.SCHEDULED, true);

    assertThat(report.result()).isEqualTo("repaired");
    assertThat(report.rebuilds()).hasSize(1);
    assertThat(report.rebuilds().get(0).tables())
        .extracting(RebuildResult.TableResult::table)
        .containsExactly("service_stat_1m", "service_stat_1h");
    assertThat(ch.replaced).containsExactly("argos.service_stat_1m", "argos.service_stat_1h");
    assertThat(report.mismatchingTables()).containsExactly("service_stat_1m", "service_stat_1h");
    assertThat(report.remainingTables()).isEmpty();
    assertThat(report.d03Minutes(3)).isEqualTo(7);
    assertThat(report.d03MinutesByApp()).containsExactly(Map.entry(3, 7));

    List<Row> rows = report.rows(CLOCK.instant());
    assertThat(rows).hasSize(4);
    Row minute = rows.get(0);
    assertThat(minute.appId()).isEqualTo(3);
    assertThat(minute.checkName()).isEqualTo("d03");
    assertThat(minute.tableName()).isEqualTo("service_stat_1m");
    assertThat(minute.mismatchMinutes()).isEqualTo(7);
    assertThat(minute.rebuilt()).isTrue();
    assertThat(minute.rebuildResult()).isEqualTo("repaired");
    JsonNode detail = JSON.readTree(minute.detailJson());
    assertThat(detail.get("remaining_minutes").asInt()).isZero();
    assertThat(detail.get("attempts").asInt()).isEqualTo(1);
    assertThat(detail.get("first_buckets").toString()).isEqualTo("[\"10:00\",\"10:01\"]");
    assertThat(detail.get("rebuilds").get(0).get("identical").asBoolean()).isFalse();
    Row hour = rows.get(1);
    assertThat(hour.checkName()).isEqualTo("rollup_1h");
    assertThat(hour.mismatchMinutes()).isEqualTo(60);
    assertThat(hour.rebuildResult()).isEqualTo("repaired");
    Row d12 = rows.get(2);
    assertThat(d12.checkName()).isEqualTo("d12");
    assertThat(d12.tableName()).isEqualTo("sql_stat_raw");
    assertThat(d12.mismatchMinutes()).isEqualTo(2);
    assertThat(d12.rebuilt()).isFalse();
    assertThat(d12.rebuildResult()).isEqualTo("record_only");
    assertThat(JSON.readTree(d12.detailJson()).get("day_within_bound").asBoolean()).isFalse();
    Row summary = rows.get(3);
    assertThat(summary.rebuildResult()).isEqualTo("repaired");
    assertThat(summary.mismatchMinutes()).isEqualTo(67);
    assertThat(summary.rebuilt()).isTrue();
    assertThat(ch.replaced).noneMatch(t -> t.contains("raw"));
  }

  @Test
  @DisplayName(
      "T-SV-019 rows that race the rebuild: still mismatching after the first rebuild, a second"
          + " rebuild follows; after two it stops and records 'mismatch'")
  void attempts() throws Exception {
    FakeClickHouse race = new FakeClickHouse();
    Deque<List<String>> q = new ArrayDeque<>();
    q.add(List.of("1\t1\t09:00"));
    q.add(List.of("1\t1\t23:59"));
    q.add(List.of());
    race.answers.put("app_stat_1m", q);
    ReconcileReport twice = reconciler(race).run(DAY, ReconcileReport.SCHEDULED, true);
    assertThat(twice.rebuilds()).hasSize(2);
    assertThat(twice.result()).isEqualTo("repaired");

    FakeClickHouse stuck = new FakeClickHouse();
    stuck.answer("app_stat_1m", List.of("1\t1\t09:00"), List.of("1\t1\t09:00"));
    ReconcileReport report = reconciler(stuck).run(DAY, ReconcileReport.SCHEDULED, true);
    assertThat(report.rebuilds()).hasSize(Reconciler.MAX_ATTEMPTS);
    assertThat(report.result()).isEqualTo("mismatch");
    assertThat(report.rows(CLOCK.instant()).get(0).rebuildResult()).isEqualTo("mismatch");
  }

  @Test
  @DisplayName(
      "T-SV-019 without rebuild (consistency check without --rebuild) the mismatch is recorded"
          + " 'not_rebuilt'; a failed rebuild is recorded 'failed' with its message")
  void noRebuildAndFailure() throws Exception {
    FakeClickHouse ch = new FakeClickHouse();
    ch.answer("instance_stat_1m", List.of("2\t3\t08:00"), List.of());
    ReconcileReport report = reconciler(ch).run(DAY, ReconcileReport.COMMAND, false);
    assertThat(report.result()).isEqualTo("not_rebuilt");
    assertThat(ch.executed).isEmpty();
    Row row = report.rows(CLOCK.instant()).get(0);
    assertThat(row.rebuildResult()).isEqualTo("not_rebuilt");
    assertThat(row.rebuilt()).isFalse();
    JsonNode json = JSON.readTree(report.json());
    assertThat(json.get("command").asString()).isEqualTo("consistency check");
    assertThat(json.get("result").asString()).isEqualTo("not_rebuilt");
    assertThat(json.get("checks").get(0).get("table").asString()).isEqualTo("instance_stat_1m");

    FakeClickHouse failing = new FakeClickHouse();
    failing.answer("instance_stat_1m", List.of("2\t3\t08:00"), List.of());
    failing.failOn = "REPLACE PARTITION";
    ReconcileReport failed = reconciler(failing).run(DAY, ReconcileReport.SCHEDULED, true);
    assertThat(failed.result()).isEqualTo("failed");
    assertThat(failed.rebuildFailure()).contains("MEMORY_LIMIT_EXCEEDED");
    assertThat(failed.rows(CLOCK.instant()).get(0).rebuildResult()).isEqualTo("failed");
  }

  @Test
  @DisplayName(
      "T-SV-019 comparisons whose source no longer holds the day are skipped (raw-days 14 for SQL,"
          + " metric); a day after today is refused")
  void retentionAndFuture() throws Exception {
    FakeClickHouse ch = new FakeClickHouse();
    ReconcileReport report =
        reconciler(ch).run(LocalDate.of(2026, 11, 1), ReconcileReport.COMMAND, false);
    assertThat(report.tables())
        .extracting(ReconcileReport.TableCheck::table)
        .doesNotContain("sql_stat_1m", "ext_stat_1m", "metric_1m")
        .contains("app_stat_1m", "sql_stat_1h");
    assertThat(report.d12()).isEmpty();
    assertThat(report.skipped())
        .contains(
            "sql_stat_1m: outside retention.raw-days", "sql_stat_raw: outside retention (D-12)");
    assertThatThrownBy(
            () -> reconciler(ch).run(LocalDate.of(2026, 11, 24), ReconcileReport.COMMAND, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("after today");
  }
}
