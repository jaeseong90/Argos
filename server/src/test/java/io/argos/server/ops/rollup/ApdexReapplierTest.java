/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.ops.migration.MigrationSource;
import io.argos.server.ops.migration.RollupSelects;
import io.argos.server.ops.migration.SchemaParameters;
import io.argos.server.storage.clickhouse.RollupPartitions;
import io.argos.server.storage.clickhouse.RollupPartitions.StatementException;
import io.argos.server.storage.postgres.ops.AdminDatabase.AppApdex;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The T expression, the mutation, the range checks and the order of {@link ApdexReapplier}. */
@Tag("req:D-17")
@Tag("req:F-S16")
class ApdexReapplierTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-23T05:00:00Z"), ZoneOffset.UTC);
  private static final RollupSelects SELECTS =
      RollupSelects.of(MigrationSource.fromClasspath(ApdexReapplierTest.class.getClassLoader()));

  @Test
  @DisplayName(
      "T-ACC-17 the T of a row: the app T, or transform over the service overrides (unsigned ids),"
          + " or one service's T (its override, else the app T)")
  void tExpression() {
    AppApdex none = new AppApdex(1000, Map.of());
    assertThat(ApdexReapplier.tExpression(none, null)).isEqualTo("toUInt16(1000)");
    Map<Long, Integer> overrides = new LinkedHashMap<>();
    overrides.put(-1L, 300);
    overrides.put(42L, 2000);
    AppApdex some = new AppApdex(1000, overrides);
    assertThat(ApdexReapplier.tExpression(some, null))
        .isEqualTo(
            "transform(service_id, CAST([18446744073709551615, 42] AS Array(UInt64)),"
                + " CAST([300, 2000] AS Array(UInt16)), toUInt16(1000))");
    assertThat(ApdexReapplier.tExpression(some, 42L)).isEqualTo("toUInt16(2000)");
    assertThat(ApdexReapplier.tExpression(some, 7L)).isEqualTo("toUInt16(1000)");
  }

  @Test
  @DisplayName(
      "T-ACC-17 the mutation updates one day's partition of one app (and service), only rows whose"
          + " T differs, and waits for completion (mutations_sync = 2)")
  void updateStatement() {
    SchemaParameters params =
        new SchemaParameters("argos", "p_", SchemaParameters.defaults().retentionDays());
    assertThat(
            ApdexReapplier.updateStatement(
                params, 5, -2L, LocalDate.of(2026, 11, 20), "toUInt16(800)"))
        .isEqualTo(
            "ALTER TABLE argos.p_xlog UPDATE apdex_t_ms = toUInt16(800) IN PARTITION '2026-11-20'"
                + " WHERE toDate(ts) = '2026-11-20' AND app_id = 5 AND service_id ="
                + " 18446744073709551614 AND apdex_t_ms != toUInt16(800) SETTINGS mutations_sync"
                + " = 2");
  }

  @Test
  @DisplayName(
      "T-ACC-17 range checks: from after to, a day after today, more than 400 days are usage"
          + " errors; a first day outside argos.retention.xlog (30 days) is refused")
  void checkRange() {
    SchemaParameters params = SchemaParameters.defaults();
    assertThatThrownBy(
            () ->
                ApdexReapplier.checkRange(
                    LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 20), params, CLOCK))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ApdexReapplier.checkRange(
                    LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 24), params, CLOCK))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("after today");
    assertThatThrownBy(
            () ->
                ApdexReapplier.checkRange(
                    LocalDate.of(2025, 1, 1), LocalDate.of(2026, 11, 20), params, CLOCK))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most 400 days");
    assertThatThrownBy(
            () ->
                ApdexReapplier.checkRange(
                    LocalDate.of(2026, 10, 24), LocalDate.of(2026, 11, 20), params, CLOCK))
        .isInstanceOf(RollupRebuildException.class)
        .hasMessageContaining("outside the raw retention (argos.retention.xlog 30 days)");
    assertThatCode(
            () ->
                ApdexReapplier.checkRange(
                    LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 23), params, CLOCK))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "T-ACC-17 each day: count the rows to re-stamp, mutate (skipped when none), then rebuild"
          + " app_stat_1m, service_stat_1m, instance_stat_1m and their 1h months; day by day")
  void order() throws Exception {
    List<String> statements = new ArrayList<>();
    RollupPartitions.Statements<StatementException> ch =
        new RollupPartitions.Statements<>() {
          @Override
          public void execute(String statement) {
            statements.add(statement);
          }

          @Override
          public List<String> queryColumn(String query) {
            if (query.startsWith("SELECT name FROM system.tables")) {
              List<String> tables = new ArrayList<>(List.of("xlog", "sql_stat_raw", "metric"));
              tables.addAll(SELECTS.tables());
              return tables;
            }
            if (query.startsWith("SELECT concat(name, '\\t', type)")) {
              return List.of("app_id\tUInt32", "count\tSimpleAggregateFunction(sum, UInt64)");
            }
            if (query.contains("FROM argos.xlog WHERE toDate(ts) = '2026-11-21'")) {
              statements.add("count 21");
              return List.of("12");
            }
            if (query.contains("FROM argos.xlog WHERE toDate(ts) = '2026-11-22'")) {
              statements.add("count 22");
              return List.of("0");
            }
            if (query.startsWith("SELECT toString(count()) FROM ")) {
              return List.of("3");
            }
            return List.of("1:1");
          }
        };
    SchemaParameters params = SchemaParameters.defaults();
    ApdexReapplier reapplier =
        new ApdexReapplier(
            ch, params, new RollupRebuilder(SELECTS, params, ch, CLOCK), Map.of(), CLOCK);
    ApdexReapplier.Result result =
        reapplier.reapply(
            4,
            null,
            LocalDate.of(2026, 11, 21),
            LocalDate.of(2026, 11, 22),
            new AppApdex(1000, Map.of()));
    assertThat(result.rowsChanged()).isEqualTo(12);
    assertThat(result.days())
        .extracting(ApdexReapplier.DayResult::rowsChanged)
        .containsExactly(12L, 0L);
    assertThat(result.days().get(0).rebuild().tables())
        .extracting(RebuildResult.TableResult::table)
        .containsExactly(
            "app_stat_1m",
            "instance_stat_1m",
            "service_stat_1m",
            "app_stat_1h",
            "instance_stat_1h",
            "service_stat_1h");
    List<String> mutations =
        statements.stream().filter(s -> s.startsWith("ALTER TABLE argos.xlog UPDATE")).toList();
    assertThat(mutations).hasSize(1);
    assertThat(mutations.get(0)).contains("IN PARTITION '2026-11-21'");
    int count21 = statements.indexOf("count 21");
    int mutation = statements.indexOf(mutations.get(0));
    int firstReplace = indexOfFirst(statements, "REPLACE PARTITION '2026-11-21'");
    int count22 = statements.indexOf("count 22");
    assertThat(count21).isLessThan(mutation);
    assertThat(mutation).isLessThan(firstReplace);
    assertThat(firstReplace).isLessThan(count22);
    assertThat(indexOfFirst(statements, "REPLACE PARTITION '2026-11-22'")).isGreaterThan(count22);
  }

  private static int indexOfFirst(List<String> statements, String text) {
    for (int i = 0; i < statements.size(); i++) {
      if (statements.get(i).contains(text)) {
        return i;
      }
    }
    return -1;
  }
}
