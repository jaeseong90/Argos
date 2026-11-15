/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import java.util.List;

/**
 * What the ClickHouse migration runner keeps in PostgreSQL: the history {@code
 * argos.ch_schema_history} and the {@code rollup_alias} entry of {@code argos.settings} (13 §12,
 * §13.2). Written with the schema owner account during the {@code CLICKHOUSE_MIGRATION} phase; the
 * runtime account {@code argos_app} only reads the history (C-11).
 */
interface MigrationStore {

  /** Takes the migration lock (one runner at a time); blocks until it is free. */
  void lock() throws MigrationException;

  /** Releases the lock taken by {@link #lock()}. */
  void unlock();

  /** Every history row. */
  List<HistoryEntry> history() throws MigrationException;

  /**
   * Inserts or replaces the history row of {@code version} with {@code applied_at = now()}.
   *
   * @param error one-line error of a failed attempt, or null
   */
  void record(
      String version,
      String description,
      String checksum,
      long durationMs,
      boolean success,
      String error)
      throws MigrationException;

  /**
   * Re-records the checksum of {@code version} and of its backfill progress rows ({@code
   * version/...}).
   *
   * @return the number of rows changed
   */
  int updateChecksum(String version, String checksum) throws MigrationException;

  /**
   * Points the logical rollup name {@code alias} at the physical table {@code table}: merges {@code
   * {"<alias>": "<table>"}} into the JSON object {@code argos.settings['rollup_alias']} (read by
   * the query layer, WO-23).
   */
  void setRollupAlias(String alias, String table) throws MigrationException;
}
