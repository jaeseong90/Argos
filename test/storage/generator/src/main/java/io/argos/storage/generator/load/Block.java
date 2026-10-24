/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.load;

import io.argos.storage.generator.rows.Table;

/**
 * One insert: a RowBinaryWithNamesAndTypes body (header included) for one table, with the
 * deduplication token it is always sent with (first send, retries, re-sends and recovery).
 */
public final class Block {

  private final Table table;
  private final long sequence;
  private final String blockId;
  private final String token;
  private final int rows;
  private final byte[] data;
  private final long[] receivedMs;
  private final long minReceivedMs;

  /**
   * @param table target table
   * @param sequence block number within the table in this run (0-based)
   * @param blockId block id (bulk sequence or WAL position)
   * @param rows rows in the body
   * @param data body including the format header (not copied)
   * @param receivedMs server receive time of each row (replay lag), or an empty array
   * @param minReceivedMs earliest receive time in the block
   */
  public Block(
      Table table,
      long sequence,
      String blockId,
      int rows,
      byte[] data,
      long[] receivedMs,
      long minReceivedMs) {
    this.table = table;
    this.sequence = sequence;
    this.blockId = blockId;
    this.token = DedupToken.of(table, blockId);
    this.rows = rows;
    this.data = data;
    this.receivedMs = receivedMs;
    this.minReceivedMs = minReceivedMs;
  }

  public Table table() {
    return table;
  }

  public long sequence() {
    return sequence;
  }

  public String blockId() {
    return blockId;
  }

  /** {@code argos:<table>:<block_id>} (DSN-13-07, C-2). */
  public String token() {
    return token;
  }

  public int rows() {
    return rows;
  }

  /** The body (shared, do not modify). */
  public byte[] data() {
    return data;
  }

  /** Receive time per row (shared, do not modify); empty in bulk mode. */
  public long[] receivedMs() {
    return receivedMs;
  }

  public long minReceivedMs() {
    return minReceivedMs;
  }

  @Override
  public String toString() {
    return token + " (" + rows + " rows, " + data.length + " bytes)";
  }
}
