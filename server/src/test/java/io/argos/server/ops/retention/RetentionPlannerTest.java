/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.ops.migration.SchemaParameters;
import io.argos.server.ops.retention.RetentionPlanner.TtlChange;
import io.argos.server.storage.clickhouse.RollupPartitions;
import io.argos.server.storage.clickhouse.TableCatalog;
import io.argos.server.storage.clickhouse.TableCatalog.PartitionInfo;
import io.argos.server.storage.clickhouse.TableCatalog.TableInfo;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The retention decisions without a database (13 §8, 17 §9.1; ADR-0010 C-99, C-112): which key
 * governs a table, which TTLs change, which partitions are outside a retention, and the {@code
 * system.tables} parsing of {@link TableCatalog}.
 */
@Tag("req:N-08")
class RetentionPlannerTest {

  private static final RetentionSettings DEFAULTS = new RetentionSettings(30, 7, 14, 14, 90, 365);

  @Test
  @DisplayName(
      "T-ST-040a every governed table maps to its retention key; staging and other tables are"
          + " left alone (C-112: sql/ext_stat_raw follow stat, metric follows metric)")
  void fieldOf() {
    assertThat(RetentionPlanner.fieldOf("xlog")).contains("xlog_days");
    assertThat(RetentionPlanner.fieldOf("profile_step")).contains("profile_days");
    assertThat(RetentionPlanner.fieldOf("sql_stat_raw")).contains("stat_raw_days");
    assertThat(RetentionPlanner.fieldOf("ext_stat_raw")).contains("stat_raw_days");
    assertThat(RetentionPlanner.fieldOf("metric")).contains("metric_raw_days");
    for (String t :
        List.of(
            "app_stat_1m",
            "service_stat_1m",
            "instance_stat_1m",
            "error_stat_1m",
            "sql_stat_1m",
            "ext_stat_1m",
            "metric_1m",
            "app_stat_1m_v2")) {
      assertThat(RetentionPlanner.fieldOf(t)).as(t).contains("rollup_1m_days");
    }
    for (String t :
        List.of(
            "app_stat_1h",
            "service_stat_1h",
            "instance_stat_1h",
            "sql_stat_1h",
            "ext_stat_1h",
            "metric_1h",
            "app_stat_1h_v3")) {
      assertThat(RetentionPlanner.fieldOf(t)).as(t).contains("rollup_1h_days");
    }
    for (String t :
        List.of(
            "app_stat_1m_rebuild",
            "app_stat_1h_backfill",
            "app_stat_1m_mv",
            "app_stat_1h_mv",
            "something")) {
      assertThat(RetentionPlanner.fieldOf(t)).as(t).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "T-ST-040a only tables whose TTL differs change; stat != metric gives different TTLs; the"
          + " statement keeps the time expression and never materialises old parts")
  void ttlChanges() {
    RetentionSettings desired = new RetentionSettings(45, 7, 21, 14, 90, 400);
    List<TableInfo> tables =
        List.of(
            new TableInfo("xlog", "MergeTree", "toDate(ts)", "toDateTime(ts)", 30),
            new TableInfo("profile_step", "MergeTree", "toDate(ts)", "toDateTime(ts)", 7),
            new TableInfo("metric", "MergeTree", "toDate(ts)", "ts", 14),
            new TableInfo("sql_stat_raw", "MergeTree", "toDate(ts)", "ts", 14),
            new TableInfo("ext_stat_raw", "MergeTree", "toDate(ts)", "ts", 14),
            new TableInfo("app_stat_1h", "AggregatingMergeTree", "toYYYYMM(hour)", "hour", 365),
            new TableInfo("app_stat_1m_mv", "MaterializedView", "", null, -1),
            new TableInfo("app_stat_1m", "AggregatingMergeTree", "toDate(minute)", null, -1));
    List<TtlChange> changes = RetentionPlanner.ttlChanges(tables, desired);
    assertThat(changes)
        .containsExactly(
            new TtlChange("xlog", "xlog_days", "toDateTime(ts)", 30, 45),
            new TtlChange("sql_stat_raw", "stat_raw_days", "ts", 14, 21),
            new TtlChange("ext_stat_raw", "stat_raw_days", "ts", 14, 21),
            new TtlChange("app_stat_1h", "rollup_1h_days", "hour", 365, 400));
    assertThat(changes).noneMatch(TtlChange::shortens);
    assertThat(RetentionPlanner.unmanaged(tables)).containsExactly("app_stat_1m");
    assertThat(RetentionPlanner.modifyTtl("argos.xlog", changes.get(0)))
        .isEqualTo(
            "ALTER TABLE argos.xlog MODIFY TTL toDateTime(ts) + INTERVAL 45 DAY"
                + " SETTINGS materialize_ttl_after_modify = 0");
    assertThat(RetentionPlanner.ttlChanges(tables, DEFAULTS))
        .extracting(TtlChange::table)
        .isEmpty();
    TtlChange odd = new TtlChange("xlog", "xlog_days", "ts; DROP TABLE x", 30, 10);
    assertThatThrownBy(() -> RetentionPlanner.modifyTtl("argos.xlog", odd))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "T-ST-040b a partition is outside a retention only when its last row has expired at the"
          + " start of today (UTC); today is never outside; months of 1h rollups likewise")
  void outside() {
    LocalDate today = LocalDate.parse("2026-11-24");
    // Day partitions, 7 days: the 16th ended on the 17th, 17 + 7 = 24 = today -> outside.
    assertThat(RetentionPlanner.outside("20261116", 7, today)).isTrue();
    assertThat(RetentionPlanner.outside("20261117", 7, today)).isFalse();
    assertThat(RetentionPlanner.outside("20261124", 1, today)).isFalse();
    assertThat(RetentionPlanner.outside("20261123", 1, today)).isFalse();
    assertThat(RetentionPlanner.outside("20261122", 1, today)).isTrue();
    // Month partitions, 30 days: October ends on 1 November, + 30 = 1 December.
    assertThat(RetentionPlanner.outside("202610", 30, today)).isFalse();
    assertThat(RetentionPlanner.outside("202610", 23, today)).isTrue();
    assertThat(RetentionPlanner.outside("garbage", 1, today)).isFalse();
    assertThat(
            RetentionPlanner.outside(
                List.of(
                    new PartitionInfo("20261101", 5, 50),
                    new PartitionInfo("20261120", 6, 60),
                    new PartitionInfo("20261124", 7, 70)),
                10,
                today))
        .extracting(PartitionInfo::id)
        .containsExactly("20261101");
  }

  @Test
  @DisplayName(
      "T-ST-040a system.tables rows parse into TTL expression and days; tables of another prefix"
          + " are skipped")
  void catalogParsing() throws Exception {
    List<String> queries = new ArrayList<>();
    RollupPartitions.Statements<RuntimeException> sql =
        new RollupPartitions.Statements<>() {
          @Override
          public void execute(String statement) {
            queries.add(statement);
          }

          @Override
          public List<String> queryColumn(String query) {
            queries.add(query);
            return List.of(
                "t_xlog\tMergeTree\ttoDate(ts)\tMergeTree PARTITION BY toDate(ts) ORDER BY (app_id,"
                    + " ts, tx_id) TTL toDateTime(ts) + toIntervalDay(30) SETTINGS"
                    + " ttl_only_drop_parts = 1",
                "t_app_stat_1h\tAggregatingMergeTree\ttoYYYYMM(hour)\tAggregatingMergeTree"
                    + " PARTITION BY toYYYYMM(hour) ORDER BY (app_id) TTL hour + toIntervalDay(365)",
                "t_app_stat_1m_mv\tMaterializedView\t\tMaterializedView",
                "other_xlog\tMergeTree\ttoDate(ts)\tMergeTree TTL ts + toIntervalDay(1)");
          }
        };
    List<TableInfo> tables = TableCatalog.tables(sql, "argos", "t_");
    assertThat(tables)
        .containsExactly(
            new TableInfo("xlog", "MergeTree", "toDate(ts)", "toDateTime(ts)", 30),
            new TableInfo("app_stat_1h", "AggregatingMergeTree", "toYYYYMM(hour)", "hour", 365),
            new TableInfo("app_stat_1m_mv", "MaterializedView", "", null, -1));
    assertThat(queries.get(0)).contains("FROM system.tables WHERE database = 'argos'");
    assertThatThrownBy(() -> TableCatalog.tables(sql, "argos'; --", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TableCatalog.dropPartition(sql, "argos.xlog", "2026-11-01"))
        .isInstanceOf(IllegalArgumentException.class);
    TableCatalog.dropPartition(sql, "argos.xlog", "20261101");
    assertThat(queries.get(queries.size() - 1))
        .isEqualTo("ALTER TABLE argos.xlog DROP PARTITION ID '20261101'");
  }

  @Test
  @DisplayName(
      "T-ST-040c effective values: API fields override the configuration, a shortening is"
          + " detected per field, out-of-range values are refused")
  void settings() {
    RetentionSettings next = DEFAULTS.with(Map.of("xlog_days", 10, "rollup_1h_days", 400));
    assertThat(next.xlog()).isEqualTo(10);
    assertThat(next.rollup1h()).isEqualTo(400);
    assertThat(next.stat()).isEqualTo(14);
    assertThat(DEFAULTS.shortenedIn(next)).containsExactly("xlog_days");
    assertThat(DEFAULTS.changedIn(next)).containsExactly("xlog_days", "rollup_1h_days");
    assertThat(DEFAULTS.asMap()).containsOnlyKeys(RetentionSettings.FIELDS);
    assertThatThrownBy(() -> DEFAULTS.with(Map.of("xlog_days", 0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DEFAULTS.with(Map.of("nope", 3)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(SchemaParameters.defaults().retention("raw-days")).isEqualTo(DEFAULTS.metric());
  }

  @Test
  @DisplayName(
      "C-116 the audit partition drop bound is now minus the retention, never inside the current"
          + " month")
  void auditDropBound() {
    java.time.Instant now = java.time.Instant.parse("2026-11-24T04:30:00Z");
    assertThat(AuditPartitionJob.dropBefore(now, java.time.Duration.ofDays(365)))
        .isEqualTo(java.time.Instant.parse("2025-11-24T04:30:00Z"));
    assertThat(AuditPartitionJob.dropBefore(now, java.time.Duration.ofDays(7)))
        .isEqualTo(java.time.Instant.parse("2026-11-01T00:00:00Z"));
  }
}
