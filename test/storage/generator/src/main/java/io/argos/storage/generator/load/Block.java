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
  private final String token;
  private final int rows;
  private final byte[] data;
  private final long[] receivedMs;
  private final long minReceivedMs;

  /**
   * @param table target table
   * @param sequence block number within the table in this run (0-based)
   * @param token deduplication token ({@link DedupToken}, C-61)
   * @param rows rows in the body
   * @param data body including the format header (not copied)
   * @param receivedMs server receive time of each row (replay lag), or an empty array
   * @param minReceivedMs earliest receive time in the block
   */
  public Block(
      Table table,
      long sequence,
      String token,
      int rows,
      byte[] data,
      long[] receivedMs,
      long minReceivedMs) {
    this.table = table;
    this.sequence = sequence;
    if (!DedupToken.FORMAT.matcher(token).matches()) {
      throw new IllegalArgumentException("Not a C-61 token: " + token);
    }
    this.token = token;
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

  /** {@code <wal_instance_id>:<epoch>:<first>-<last>} (13 §5.1, ADR-0007 C-61). */
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
