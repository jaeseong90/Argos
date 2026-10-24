/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.rows;

/** A row of one raw table, written in the column order of its {@link Table}. */
public interface Row {

  /** Appends the row in RowBinary encoding. */
  void write(RowBinaryWriter out);

  /** The stored {@code ts} (UTC ms) of the row: partition, day accounting. */
  long tsMs();
}
