/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The partition statements and the digest query of the rollup rebuild (ADR-0007 C-59, D-14). */
@Tag("req:D-14")
@Tag("req:F-S22")
class RollupPartitionsTest {

  /** Records statements; answers the staging count with {@code stagingRows}. */
  private static final class Recorder implements RollupPartitions.Statements<RuntimeException> {
    final List<String> statements = new ArrayList<>();
    final long stagingRows;

    Recorder(long stagingRows) {
      this.stagingRows = stagingRows;
    }

    @Override
    public void execute(String statement) {
      statements.add(statement);
    }

    @Override
    public List<String> queryColumn(String query) {
      statements.add(query);
      return List.of(Long.toString(stagingRows));
    }
  }

  @Test
  @DisplayName(
      "T-SV-019 a day partition of a 1m rollup and a month partition of a 1h rollup: literals and"
          + " predicates (the month boundary falls between two 1h partitions)")
  void partitions() {
    RollupPartitions.Partition day = RollupPartitions.Partition.day(LocalDate.of(2026, 10, 31));
    assertThat(day.key()).isEqualTo("20261031");
    assertThat(day.literal()).isEqualTo("'2026-10-31'");
    assertThat(day.sourcePredicate()).isEqualTo("toDate(ts) = '2026-10-31'");
    assertThat(day.targetPredicate()).isEqualTo("toDate(minute) = '2026-10-31'");

    RollupPartitions.Partition october = RollupPartitions.Partition.month(YearMonth.of(2026, 10));
    RollupPartitions.Partition november = RollupPartitions.Partition.month(YearMonth.of(2026, 11));
    assertThat(october.literal()).isEqualTo("202610");
    assertThat(november.literal()).isEqualTo("202611");
    assertThat(november.sourcePredicate()).isEqualTo("toYYYYMM(minute) = 202611");
    assertThat(november.targetPredicate()).isEqualTo("toYYYYMM(hour) = 202611");
    assertThat(RollupPartitions.Partition.ofKey(true, "202611")).isEqualTo(november);
    assertThat(RollupPartitions.Partition.ofKey(false, "20261031")).isEqualTo(day);
  }

  @Test
  @DisplayName(
      "T-SV-019 replace: staging created like the target, filled, swapped with REPLACE PARTITION,"
          + " dropped; nothing is inserted into the rollup itself")
  void replaceWithRows() {
    Recorder sql = new Recorder(42);
    RollupPartitions.Replacement r =
        RollupPartitions.replace(
            sql,
            "argos.app_stat_1m",
            "argos.app_stat_1m_rebuild",
            RollupPartitions.Partition.day(LocalDate.of(2026, 11, 20)),
            "SELECT 1",
            RollupPartitions.EmptyStaging.DROP);

    assertThat(r).isEqualTo(new RollupPartitions.Replacement(42, true, false));
    assertThat(sql.statements)
        .containsExactly(
            "DROP TABLE IF EXISTS argos.app_stat_1m_rebuild SYNC",
            "CREATE TABLE argos.app_stat_1m_rebuild AS argos.app_stat_1m",
            "INSERT INTO argos.app_stat_1m_rebuild\nSELECT 1",
            "SELECT toString(count()) FROM argos.app_stat_1m_rebuild",
            "ALTER TABLE argos.app_stat_1m REPLACE PARTITION '2026-11-20' FROM"
                + " argos.app_stat_1m_rebuild",
            "DROP TABLE IF EXISTS argos.app_stat_1m_rebuild SYNC");
    assertThat(sql.statements).noneMatch(s -> s.startsWith("INSERT INTO argos.app_stat_1m\n"));
  }

  @Test
  @DisplayName(
      "T-SV-019 an empty staging table drops the partition for a rebuild (DROP) and keeps it for the"
          + " migration backfill (KEEP)")
  void emptyStaging() {
    Recorder rebuild = new Recorder(0);
    assertThat(
            RollupPartitions.replace(
                rebuild,
                "argos.app_stat_1h",
                "argos.app_stat_1h_rebuild",
                RollupPartitions.Partition.month(YearMonth.of(2026, 11)),
                "SELECT 1",
                RollupPartitions.EmptyStaging.DROP))
        .isEqualTo(new RollupPartitions.Replacement(0, false, true));
    assertThat(rebuild.statements).contains("ALTER TABLE argos.app_stat_1h DROP PARTITION 202611");

    Recorder backfill = new Recorder(0);
    assertThat(
            RollupPartitions.replace(
                backfill,
                "argos.app_stat_1h",
                "argos.app_stat_1h_backfill",
                RollupPartitions.Partition.month(YearMonth.of(2026, 11)),
                "SELECT 1",
                RollupPartitions.EmptyStaging.KEEP))
        .isEqualTo(new RollupPartitions.Replacement(0, false, false));
    assertThat(backfill.statements).noneMatch(s -> s.startsWith("ALTER TABLE"));
  }

  @Test
  @DisplayName(
      "T-SV-019 digest query: keys grouped, simple aggregates merged with their function, aggregate"
          + " states as hex of -MergeState, anyLast left out, one cityHash64 per key summed")
  void digestQuery() {
    RollupPartitions.DigestQuery q =
        RollupPartitions.digestQuery(
            "argos.metric_1m",
            List.of(
                "app_id\tUInt32",
                "env\tLowCardinality(String)",
                "minute\tDateTime('UTC')",
                "count\tSimpleAggregateFunction(sum, UInt64)",
                "max_v\tSimpleAggregateFunction(max, Float64)",
                "min_v\tSimpleAggregateFunction(min, Float64)",
                "last_v\tSimpleAggregateFunction(anyLast, Float64)",
                "q_elapsed\tAggregateFunction(quantilesDD(0.01, 0.5, 0.95, 0.99), UInt32)",
                "buckets\tAggregateFunction(sumForEach, Array(UInt64))"),
            "toDate(minute) = '2026-11-20'");

    assertThat(q.excluded()).containsExactly("last_v");
    assertThat(q.sql())
        .isEqualTo(
            "SELECT concat(toString(count()), ':', toString(sum(cityHash64(app_id, env, minute,"
                + " d_count, d_max_v, d_min_v, d_q_elapsed, d_buckets)))) FROM (SELECT app_id,"
                + " env, minute, sum(count) AS d_count, max(max_v) AS d_max_v, min(min_v) AS"
                + " d_min_v, hex(quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed)) AS"
                + " d_q_elapsed, hex(sumForEachMergeState(buckets)) AS d_buckets FROM"
                + " argos.metric_1m WHERE toDate(minute) = '2026-11-20' GROUP BY app_id, env,"
                + " minute)");
  }

  @Test
  @DisplayName("T-SV-019 the digest refuses an unknown aggregate instead of skipping a measure")
  void digestRefusesUnknownAggregate() {
    assertThatThrownBy(
            () ->
                RollupPartitions.digestQuery(
                    "argos.x",
                    List.of("app_id\tUInt32", "v\tSimpleAggregateFunction(groupBitOr, UInt64)"),
                    "true"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no digest");
    assertThatThrownBy(() -> RollupPartitions.digestQuery("argos.x", List.of(), "true"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("T-SV-019 table listing strips the prefix and keeps only prefixed tables")
  void tables() {
    RollupPartitions.Statements<RuntimeException> sql =
        new RollupPartitions.Statements<>() {
          @Override
          public void execute(String statement) {}

          @Override
          public List<String> queryColumn(String query) {
            assertThat(query).contains("database = 'argos'");
            return List.of("t_app_stat_1m", "t_app_stat_1h", "other");
          }
        };
    assertThat(RollupPartitions.tables(sql, "argos", "t_"))
        .containsExactly("app_stat_1m", "app_stat_1h");
    assertThatThrownBy(() -> RollupPartitions.tables(sql, "argos'; DROP", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
