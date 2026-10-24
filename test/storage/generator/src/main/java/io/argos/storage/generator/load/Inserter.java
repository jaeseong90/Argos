/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.load;

/** Sends blocks somewhere: ClickHouse, files, or a counter. Called by one thread per table. */
public interface Inserter extends AutoCloseable {

  /**
   * Inserts one block with its token. Throws on failure (the caller retries with the same token).
   *
   * @return rows written as reported by the server ({@code written_rows}, materialized views
   *     included), or -1 when unknown
   */
  long insert(Block block);

  /** Short description for the report. */
  String describe();

  @Override
  default void close() {}
}
