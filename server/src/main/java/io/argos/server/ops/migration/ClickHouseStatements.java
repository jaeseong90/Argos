/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import java.util.List;

/** The ClickHouse operations of the migration runner (migration account {@code argos_migrator}). */
interface ClickHouseStatements {

  /**
   * Executes one statement and waits for its end. The statement runs at most the statement timeout
   * of the runner (10 minutes, 12 §9.3).
   *
   * @throws MigrationException with the ClickHouse error, one line
   */
  void execute(String statement) throws MigrationException;

  /** The first column of every result row as text. */
  List<String> queryColumn(String query) throws MigrationException;
}
