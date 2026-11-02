/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ArgosClickHouse;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.clickhouse.ClickHouseImage;
import io.argos.storage.schema.SchemaApplier;
import io.argos.storage.schema.SchemaParameters;
import io.argos.storage.schema.SchemaRenderer;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Upgrade from the previous LTS to the bundled LTS on the same data directory (T-ST-051, N-21, 13
 * §17 item 6). Aggregate states written by the old version are read by the new one: the stored
 * state bytes are identical and finalize to identical values, order-independent rollup aggregates
 * are identical, the deduplication log survives the restart, and new states merge with old ones.
 */
@Tag("integration")
@Tag("req:N-21")
@Tag("req:D-13")
class UpgradeIT {

  private static final String TOPIC = "upgrade";

  /** Order-independent aggregates of the rollups (sums, max, sumForEach). */
  // ORDER BY after UNION ALL sorts only the last SELECT in ClickHouse; the outer query orders the
  // whole result so that the two branches compare in a fixed order.
  private static final String EXACT =
      "SELECT * FROM (SELECT 'app' AS src, app_id AS id, kind, hour, sum(count),"
          + " sum(sum_elapsed_ms), max(max_elapsed_ms), sum(apdex_satisfied) FROM argos.app_stat_1h"
          + " GROUP BY app_id, kind, hour UNION ALL SELECT 'sql' AS src, sql_id AS id, kind, hour,"
          + " sum(count), sum(sum_ms), max(max_ms), arraySum(sumForEachMerge(buckets))"
          + " FROM argos.sql_stat_1h GROUP BY sql_id, kind, hour) ORDER BY 1, 2, 3, 4";

  private static final String MERGED_QUANTILES =
      "SELECT app_id, kind, hour, quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q_elapsed)"
          + " FROM argos.app_stat_1h GROUP BY app_id, kind, hour ORDER BY app_id, kind, hour";

  @Test
  @DisplayName(
      "T-ST-051 26.3 LTS -> 26.8 LTS: states read unchanged, dedup survives, new states merge")
  void previousLtsToBundled() {
    String volume = "argos-wo03-upgrade-" + UUID.randomUUID();
    SchemaParameters params = SchemaParameters.defaults();
    List<String> statements = new ArrayList<>();
    for (String s : SchemaRenderer.fromRepository().renderAll(params)) {
      // 26.3 does not know the 26.8 table setting; a 26.3-era installation has tables without it.
      statements.add(s.replaceAll(",\\s*allow_dimensions_outside_sorting_key = 1", ""));
    }
    Workload w = Workload.generate(11, Workload.at(LocalTime.of(23, 40)), 30, 60);
    try {
      List<List<String>> exactBefore;
      List<List<String>> mergedBefore;
      List<List<String>> statesFinalizedBefore;
      byte[] stateBytesBefore;
      try (ArgosClickHouse old =
          ArgosClickHouse.builder()
              .image(ClickHouseImage.previousLts())
              .dataVolume(volume)
              .start()) {
        ClickHouseHttp migrator = old.client(Account.MIGRATOR);
        assertThat(migrator.scalar("SELECT version()"))
            .isEqualTo(ClickHouseImage.previousLts().version());
        SchemaApplier.apply(migrator, statements);
        RollupViewsIT.insertAll(old.client(Account.WRITER), params, w, "up");
        // A plain MergeTree copy of the stored states (no aggregation on merge, fixed row order).
        migrator.execute(
            "CREATE TABLE argos.upgrade_states (rn UInt64, app_id UInt32, minute DateTime('UTC'),"
                + " q AggregateFunction(quantilesDD(0.01, 0.5, 0.95, 0.99), UInt32),"
                + " b AggregateFunction(sumForEach, Array(UInt64))) ENGINE = MergeTree ORDER BY rn");
        migrator.execute(
            "INSERT INTO argos.upgrade_states SELECT rowNumberInAllBlocks(), a.app_id, a.minute,"
                + " a.q_elapsed, s.buckets FROM (SELECT app_id, minute, q_elapsed, _part, _part_offset"
                + " FROM argos.app_stat_1m) a INNER JOIN (SELECT app_id, minute, buckets FROM"
                + " argos.sql_stat_1m LIMIT 1 BY app_id, minute) s USING (app_id, minute)"
                + " ORDER BY a.app_id, a.minute, a._part, a._part_offset",
            Map.of("max_threads", "1", "max_insert_threads", "1"));
        exactBefore = migrator.rows(EXACT);
        statesFinalizedBefore = finalizedStates(migrator);
        stateBytesBefore = stateBytes(migrator);
        Evidence.record(TOPIC, "written by", migrator.scalar("SELECT version()"));
        mergedBefore = migrator.rows(MERGED_QUANTILES);
        Evidence.record(TOPIC, "merged quantilesDD on the writer version", mergedBefore);
      }

      try (ArgosClickHouse current = ArgosClickHouse.builder().dataVolume(volume).start()) {
        ClickHouseHttp migrator = current.client(Account.MIGRATOR);
        ClickHouseHttp writer = current.client(Account.WRITER);
        assertThat(migrator.scalar("SELECT version()"))
            .isEqualTo(ClickHouseImage.bundled().version());
        Evidence.record(TOPIC, "read by", migrator.scalar("SELECT version()"));

        assertThat(stateBytes(migrator)).as("stored state bytes").isEqualTo(stateBytesBefore);
        assertThat(finalizedStates(migrator)).isEqualTo(statesFinalizedBefore);
        assertThat(migrator.rows(EXACT)).isEqualTo(exactBefore);
        Evidence.record(
            TOPIC,
            "states compared (quantilesDD + sumForEach rows, RowBinary bytes)",
            statesFinalizedBefore.size()
                + " rows, "
                + stateBytesBefore.length
                + " bytes, identical");
        Set<List<List<String>>> mergedRuns = new LinkedHashSet<>();
        for (int i = 0; i < 5; i++) {
          mergedRuns.add(migrator.rows(MERGED_QUANTILES));
        }
        Evidence.record(
            TOPIC,
            "merged quantilesDD on the reader version, distinct results over 5 runs",
            mergedRuns.size() + ": " + mergedRuns);
        // C-53: DD merges add bucket counts, so the merged values do not depend on the merge order
        // or the version (tDigest gave 80.4 vs 80.22222 here before ADR-0007).
        assertThat(mergedRuns).containsExactly(mergedBefore);

        // The deduplication log of the old server is still honoured after the restart.
        long rows = migrator.scalarLong("SELECT count() FROM argos.xlog");
        RollupViewsIT.insertAll(writer, params, w, "up");
        assertThat(migrator.scalarLong("SELECT count() FROM argos.xlog")).isEqualTo(rows);
        assertThat(migrator.rows(EXACT)).isEqualTo(exactBefore);

        // New states (26.8) merge with old states (26.3).
        Workload more = Workload.generate(12, Workload.at(1, LocalTime.of(0, 5)), 5, 60);
        RollupViewsIT.insertBlocks(
            writer, params, "xlog", Workload.XLOG_COLUMNS, more.xlog, 1000, "after");
        assertThat(migrator.scalarLong("SELECT sum(count) FROM argos.app_stat_1h"))
            .isEqualTo(rows + 300);
        assertThat(migrator.rows(MERGED_QUANTILES))
            .hasSize(12)
            .allSatisfy(r -> assertThat(r.get(3)).startsWith("["));

        // Tables created without the 26.8 setting load, accept it afterwards; V001 is a no-op
        // again.
        migrator.execute(
            "ALTER TABLE argos.app_stat_1m MODIFY SETTING allow_dimensions_outside_sorting_key = 1");
        SchemaApplier.apply(migrator, params);
        assertThat(migrator.scalarLong("SELECT count() FROM argos.xlog")).isEqualTo(rows + 300);
        Evidence.record(
            TOPIC,
            "objects in argos after upgrade (tables created on 26.3 without the 26.8 setting)",
            migrator.scalar("SELECT count() FROM system.tables WHERE database = 'argos'"));
      }
    } finally {
      ArgosClickHouse.removeVolume(volume);
    }
  }

  private static List<List<String>> finalizedStates(ClickHouseHttp migrator) {
    return migrator.rows(
        "SELECT rn, app_id, minute, finalizeAggregation(q), finalizeAggregation(b)"
            + " FROM argos.upgrade_states ORDER BY rn");
  }

  private static byte[] stateBytes(ClickHouseHttp migrator) {
    byte[] bytes =
        migrator.queryBytes("SELECT q, b FROM argos.upgrade_states ORDER BY rn FORMAT RowBinary");
    assertThat(bytes.length).isPositive();
    return Arrays.copyOf(bytes, bytes.length);
  }
}
