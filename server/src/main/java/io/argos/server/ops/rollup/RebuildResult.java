/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import io.argos.server.storage.clickhouse.RollupPartitions.Digest;
import java.time.LocalDate;
import java.util.List;

/**
 * The result of {@link RollupRebuilder#rebuildDay}: one entry per rebuilt table, the 1m tables (day
 * partition) first, then the 1h tables (month partition), in the order they were rebuilt.
 *
 * @param day the rebuilt day (UTC)
 * @param tables the rebuilt tables
 * @param durationMs wall time of the whole rebuild
 */
public record RebuildResult(LocalDate day, List<TableResult> tables, long durationMs) {

  public RebuildResult {
    tables = List.copyOf(tables);
  }

  /** True when every table had the same digest before and after (nothing was repaired). */
  public boolean identical() {
    return tables.stream().allMatch(TableResult::identical);
  }

  /** Tables whose digest changed (the rebuild repaired them). */
  public List<String> changedTables() {
    return tables.stream().filter(t -> !t.identical()).map(TableResult::table).toList();
  }

  /** The rollup level of a table. */
  public enum Level {
    /** 1m rollup from a raw table, day partition. */
    MINUTE("1m"),
    /** 1h rollup from a 1m rollup (cascade), month partition. */
    HOUR("1h");

    private final String label;

    Level(String label) {
      this.label = label;
    }

    /** {@code 1m} or {@code 1h}. */
    public String label() {
      return label;
    }
  }

  /**
   * One rebuilt table.
   *
   * @param table the physical table name without database and prefix
   * @param level 1m or 1h
   * @param partition the partition key ({@code yyyyMMdd} or {@code yyyyMM})
   * @param before digest of the partition before the rebuild
   * @param after digest of the partition after the rebuild
   * @param stagingRows rows the rollup SELECT wrote into the staging table
   * @param dropped the partition was dropped because the source had no row for it
   * @param durationMs wall time of this table (digests included)
   */
  public record TableResult(
      String table,
      Level level,
      String partition,
      Digest before,
      Digest after,
      long stagingRows,
      boolean dropped,
      long durationMs) {

    /** Same digest before and after: the stored aggregates were already right (D-14). */
    public boolean identical() {
      return before.sameAs(after);
    }
  }
}
