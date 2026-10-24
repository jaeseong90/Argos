/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.sim;

import io.argos.storage.generator.rows.Row;
import io.argos.storage.generator.rows.Table;

/** Receives rows in server arrival order (the loader: bulk blocks or the replay writer path). */
public interface RowConsumer {

  /**
   * One row that reached the server at {@code receivedMs} (UTC ms).
   *
   * @param table raw table
   * @param row the row (after the timestamp guard)
   * @param receivedMs server receive time
   */
  void accept(Table table, Row row, long receivedMs);
}
