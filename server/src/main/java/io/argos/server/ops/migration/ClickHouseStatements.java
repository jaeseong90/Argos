/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import io.argos.server.storage.clickhouse.RollupPartitions;
import java.util.List;

/**
 * The ClickHouse operations of the migration runner (migration account {@code argos_migrator}). The
 * same operations as {@link RollupPartitions.Statements}, so the backfill shares the partition
 * replacement of the rollup rebuild ({@link RollupPartitions#replace}, ADR-0007 C-59).
 */
interface ClickHouseStatements extends RollupPartitions.Statements<MigrationException> {

  /**
   * Executes one statement and waits for its end. The statement runs at most the statement timeout
   * of the runner (10 minutes, 12 §9.3).
   *
   * @throws MigrationException with the ClickHouse error, one line
   */
  @Override
  void execute(String statement) throws MigrationException;

  /** The first column of every result row as text. */
  @Override
  List<String> queryColumn(String query) throws MigrationException;
}
