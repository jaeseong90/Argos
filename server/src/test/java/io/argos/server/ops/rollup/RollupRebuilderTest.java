/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.ops.migration.MigrationSource;
import io.argos.server.ops.migration.RollupSelects;
import io.argos.server.ops.migration.SchemaParameters;
import io.argos.server.storage.clickhouse.RollupPartitions;
import io.argos.server.storage.clickhouse.RollupPartitions.StatementException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The table plan, the refusals and the statement order of {@link RollupRebuilder} (C-59). */
@Tag("req:D-14")
@Tag("req:F-S22")
class RollupRebuilderTest {

  private static final RollupSelects SELECTS =
      RollupSelects.of(MigrationSource.fromClasspath(RollupRebuilderTest.class.getClassLoader()));
  private static final List<String> MINUTE =
      List.of(
          "app_stat_1m",
          "error_stat_1m",
          "ext_stat_1m",
          "instance_stat_1m",
          "metric_1m",
          "service_stat_1m",
          "sql_stat_1m");
  private static final List<String> HOUR =
      List.of(
          "app_stat_1h",
          "ext_stat_1h",
          "instance_stat_1h",
          "metric_1h",
          "service_stat_1h",
          "sql_stat_1h");

  /** The bundled schema: 5 raw tables and the 13 rollups. */
  private static List<String> existing() {
    List<String> tables =
        new ArrayList<>(List.of("xlog", "profile_step", "metric", "sql_stat_raw", "ext_stat_raw"));
    tables.addAll(MINUTE);
    tables.addAll(HOUR);
    return tables;
  }

  private static RollupRebuilder rebuilder(
      String now, RollupPartitions.Statements<StatementException> sql) {
    return new RollupRebuilder(
        SELECTS, SchemaParameters.defaults(), sql, Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
  }

  /** A ClickHouse double: table list, columns, digests that change after a REPLACE. */
  private static final class FakeClickHouse
      implements RollupPartitions.Statements<StatementException> {
    final List<String> statements = new ArrayList<>();
    final Map<String, Integer> replaced = new LinkedHashMap<>();
    String failOn;

    @Override
    public void execute(String statement) throws StatementException {
      statements.add(statement);
      if (failOn != null && statement.contains(failOn)) {
        throw new StatementException("ClickHouse: Code: 241. MEMORY_LIMIT_EXCEEDED", null);
      }
      if (statement.contains(" REPLACE PARTITION ")) {
        String table = statement.split(" ", -1)[2];
        replaced.merge(table, 1, Integer::sum);
      }
    }

    @Override
    public List<String> queryColumn(String query) {
      statements.add(query);
      if (query.startsWith("SELECT name FROM system.tables")) {
        return existing();
      }
      if (query.startsWith("SELECT concat(name, '\\t', type) FROM system.columns")) {
        return List.of(
            "app_id\tUInt32",
            "minute\tDateTime('UTC')",
            "count\tSimpleAggregateFunction(sum, UInt64)");
      }
      if (query.startsWith("SELECT toString(count()) FROM ")) {
        return List.of("7");
      }
      // Digest: the app_stat_1m partition is repaired (its digest changes), the others are not.
      String table = query.substring(query.indexOf(" FROM argos.") + 6).split(" ", -1)[0];
      boolean repaired = table.equals("argos.app_stat_1m") && replaced.containsKey(table);
      return List.of(repaired ? "10:999" : "10:123");
    }
  }

  @Test
  @DisplayName(
      "T-SV-019 without --table every 1m rollup (day) and every 1h rollup (month) is rebuilt, 1m"
          + " first, instance_stat_1h (C-212) included")
  void planAll() {
    RollupRebuilder.Plan plan =
        rebuilder("2026-11-23T05:00:00Z", null)
            .plan(LocalDate.of(2026, 11, 20), Set.of(), existing());
    assertThat(plan.minute()).containsExactlyElementsOf(MINUTE);
    assertThat(plan.hour()).containsExactlyElementsOf(HOUR);
    assertThat(plan.tables()).hasSize(13);
  }

  @Test
  @DisplayName(
      "T-SV-019 a requested 1m table brings its 1h cascade along (C-59 (b)); a 1h table alone is"
          + " only that table")
  void planRequested() {
    RollupRebuilder r = rebuilder("2026-11-23T05:00:00Z", null);
    LocalDate day = LocalDate.of(2026, 11, 20);
    RollupRebuilder.Plan sql = r.plan(day, Set.of("sql_stat_1m"), existing());
    assertThat(sql.minute()).containsExactly("sql_stat_1m");
    assertThat(sql.hour()).containsExactly("sql_stat_1h");

    RollupRebuilder.Plan instance = r.plan(day, Set.of("instance_stat_1m"), existing());
    assertThat(instance.hour()).containsExactly("instance_stat_1h");

    RollupRebuilder.Plan error = r.plan(day, Set.of("error_stat_1m"), existing());
    assertThat(error.minute()).containsExactly("error_stat_1m");
    assertThat(error.hour()).isEmpty();

    RollupRebuilder.Plan hourly = r.plan(day, Set.of("app_stat_1h"), existing());
    assertThat(hourly.minute()).isEmpty();
    assertThat(hourly.hour()).containsExactly("app_stat_1h");
  }

  @Test
  @DisplayName(
      "T-SV-019 unknown or missing tables and days after today are refused before any change")
  void planRefusals() {
    RollupRebuilder r = rebuilder("2026-11-23T05:00:00Z", null);
    LocalDate day = LocalDate.of(2026, 11, 20);
    assertThatThrownBy(() -> r.plan(day, Set.of("xlog"), existing()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown rollup table xlog");
    List<String> withoutInstance1h = new ArrayList<>(existing());
    withoutInstance1h.remove("instance_stat_1h");
    assertThatThrownBy(() -> r.plan(day, Set.of("instance_stat_1h"), withoutInstance1h))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not exist");
    // A table the schema does not have is left out of the default set (e.g. replaced by a
    // migration procedure and dropped).
    assertThat(r.plan(day, Set.of(), withoutInstance1h).hour()).doesNotContain("instance_stat_1h");
    assertThatThrownBy(() -> r.plan(LocalDate.of(2026, 11, 24), Set.of(), existing()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("after today");
  }

  @Test
  @DisplayName(
      "T-SV-019 a day whose source may have expired is refused: raw-days 14 for SQL, xlog-days 30,"
          + " rollup-1m-days 90 for the month of a 1h rebuild")
  void retentionRefusal() {
    RollupRebuilder r = rebuilder("2026-11-23T05:00:00Z", null);
    // 2026-11-09 + 14 days = 2026-11-23 00:00 <= now: SQL raw rows of that day may be gone.
    RollupRebuilder.Plan sql = r.plan(LocalDate.of(2026, 11, 9), Set.of("sql_stat_1m"), existing());
    assertThatThrownBy(() -> r.checkRetention(sql))
        .isInstanceOf(RollupRebuildException.class)
        .hasMessageContaining("sql_stat_1m (source sql_stat_raw, retention.raw-days 14 days)");
    RollupRebuilder.Plan app =
        r.plan(LocalDate.of(2026, 11, 10), Set.of("app_stat_1m"), existing());
    org.assertj.core.api.Assertions.assertThatCode(() -> r.checkRetention(app))
        .doesNotThrowAnyException();
    RollupRebuilder.Plan old =
        r.plan(LocalDate.of(2026, 10, 24), Set.of("app_stat_1m"), existing());
    assertThatThrownBy(() -> r.checkRetention(old))
        .hasMessageContaining("app_stat_1m (source xlog, retention.xlog-days 30 days)");
    assertThat(RollupRebuilder.rawRetentionKey("metric")).isEqualTo("raw-days");
    assertThat(RollupRebuilder.rawRetentionKey("profile_step")).isEqualTo("profile-days");
  }

  @Test
  @DisplayName(
      "T-SV-019 T-ACC-14 rebuild order: per table digest, staging <t>_rebuild, REPLACE PARTITION"
          + " of the day, digest; then the 1h month; changed and identical tables reported")
  void rebuildStatements() throws Exception {
    FakeClickHouse ch = new FakeClickHouse();
    RebuildResult result =
        rebuilder("2026-11-23T05:00:00Z", ch)
            .rebuildDay(LocalDate.of(2026, 10, 31), Set.of("app_stat_1m"));

    assertThat(result.tables())
        .extracting(RebuildResult.TableResult::table)
        .containsExactly("app_stat_1m", "app_stat_1h");
    assertThat(result.tables().get(0).partition()).isEqualTo("20261031");
    assertThat(result.tables().get(1).partition()).isEqualTo("202610");
    assertThat(result.tables().get(0).identical()).isFalse();
    assertThat(result.tables().get(1).identical()).isTrue();
    assertThat(result.changedTables()).containsExactly("app_stat_1m");
    assertThat(result.tables().get(0).stagingRows()).isEqualTo(7);
    List<String> ddl = ch.statements.stream().filter(s -> !s.startsWith("SELECT")).toList();
    assertThat(ddl)
        .containsExactly(
            "DROP TABLE IF EXISTS argos.app_stat_1m_rebuild SYNC",
            "CREATE TABLE argos.app_stat_1m_rebuild AS argos.app_stat_1m",
            ddl.get(2),
            "ALTER TABLE argos.app_stat_1m REPLACE PARTITION '2026-10-31' FROM"
                + " argos.app_stat_1m_rebuild",
            "DROP TABLE IF EXISTS argos.app_stat_1m_rebuild SYNC",
            "DROP TABLE IF EXISTS argos.app_stat_1h_rebuild SYNC",
            "CREATE TABLE argos.app_stat_1h_rebuild AS argos.app_stat_1h",
            ddl.get(7),
            "ALTER TABLE argos.app_stat_1h REPLACE PARTITION 202610 FROM argos.app_stat_1h_rebuild",
            "DROP TABLE IF EXISTS argos.app_stat_1h_rebuild SYNC");
    assertThat(ddl.get(2))
        .startsWith("INSERT INTO argos.app_stat_1m_rebuild\nSELECT")
        .contains("FROM argos.xlog")
        .contains("AND (toDate(ts) = '2026-10-31')");
    assertThat(ddl.get(7))
        .startsWith("INSERT INTO argos.app_stat_1h_rebuild\nSELECT")
        .contains("FROM argos.app_stat_1m")
        .contains("AND (toYYYYMM(minute) = 202610)");
    assertThat(ch.statements).noneMatch(s -> s.contains("app_id ="));
  }

  @Test
  @DisplayName("T-SV-019 month boundary: the 1st of the next month rebuilds the other 1h partition")
  void monthBoundary() throws Exception {
    FakeClickHouse ch = new FakeClickHouse();
    RebuildResult result =
        rebuilder("2026-11-23T05:00:00Z", ch)
            .rebuildDay(LocalDate.of(2026, 11, 1), Set.of("instance_stat_1m"));
    assertThat(result.tables())
        .extracting(RebuildResult.TableResult::partition)
        .containsExactly("20261101", "202611");
    assertThat(ch.statements)
        .contains(
            "ALTER TABLE argos.instance_stat_1h REPLACE PARTITION 202611 FROM"
                + " argos.instance_stat_1h_rebuild");
  }

  @Test
  @DisplayName(
      "T-SV-019 a failed statement stops the rebuild with the table and partition in the message")
  void failure() {
    FakeClickHouse ch = new FakeClickHouse();
    ch.failOn = "INSERT INTO argos.app_stat_1h_rebuild";
    assertThatThrownBy(
            () ->
                rebuilder("2026-11-23T05:00:00Z", ch)
                    .rebuildDay(LocalDate.of(2026, 11, 20), Set.of("app_stat_1m")))
        .isInstanceOf(RollupRebuildException.class)
        .hasMessage(
            "Rollup rebuild of app_stat_1h partition 202611 failed: ClickHouse: Code: 241."
                + " MEMORY_LIMIT_EXCEEDED");
    assertThat(ch.replaced).containsOnlyKeys("argos.app_stat_1m");
  }

  @Test
  @DisplayName("T-SV-019 logical rollup names resolve through settings.rollup_alias")
  void resolve() {
    assertThat(
            RollupRebuilder.resolve(
                List.of("error_stat_1m", "app_stat_1m"),
                Map.of("error_stat_1m", "error_stat_1m_v2")))
        .containsExactly("error_stat_1m_v2", "app_stat_1m");
  }
}
