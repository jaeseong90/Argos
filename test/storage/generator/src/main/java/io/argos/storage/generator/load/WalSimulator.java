/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.load;

import io.argos.storage.generator.rows.Table;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Positions and block manifest of the server WAL (12 §3.4, §3.8, DSN-12-02, C-2), without the file:
 * every received row is a record ({@code [len][crc][type][instance][epoch][seq][received]} header
 * of 37 bytes plus the payload) at a position {@code segment:offset} (256 MiB segments with a
 * 16-byte file header); before an insert the writer appends {@code BLOCK_INTENT{table, first,
 * last}}, whose position is the block id, and after the ack {@code BLOCK_COMMIT}. On restart the
 * intents without a commit are re-inserted with the same token. The real WAL is WO-21.
 *
 * <p>The block id starts with a WAL id (one per WAL lifetime) so that blocks of two WALs, for
 * example two replay runs against the same tables, never share a token.
 *
 * <p>Differs from the C-2 / DSN-02-12 wording (block_id = COMMIT record position): the token is
 * needed before the insert and the COMMIT is written after the ack, so the INTENT position is used
 * (12 §3.8); see docs/poc/clickhouse-benchmark.md section 14.2 for the PM decision.
 *
 * <p>Simulation only: no file is written, so the WAL append, the group fsync (10 ms) and the
 * server-side decode are not part of the measured ingest lag.
 */
public final class WalSimulator {

  static final int FILE_HEADER_BYTES = 16;
  static final int RECORD_HEADER_BYTES = 37;
  static final long SEGMENT_BYTES = 256L << 20;
  static final int INTENT_PAYLOAD_BYTES = 64;
  static final int COMMIT_PAYLOAD_BYTES = 48;

  /**
   * A BLOCK_INTENT record.
   *
   * @param table target table
   * @param blockId position of the intent record ({@code <walId>-<segment>-<offset>})
   * @param firstPosition first data record of the block
   * @param lastPosition last data record of the block
   * @param rows rows in the block
   */
  public record Intent(
      Table table, String blockId, long firstPosition, long lastPosition, int rows) {
    /** The insert token of the block. */
    public String token() {
      return DedupToken.of(table, blockId);
    }
  }

  private final String walId;
  private long segment = 1;
  private long offset = FILE_HEADER_BYTES;
  private long records;
  private long commits;
  private final Map<String, Intent> open = new LinkedHashMap<>();

  /**
   * @param walId identity of this WAL lifetime (letters and digits)
   */
  public WalSimulator(String walId) {
    if (!walId.matches("[A-Za-z0-9]{1,32}")) {
      throw new IllegalArgumentException("Invalid WAL id: " + walId);
    }
    this.walId = walId;
  }

  /** Appends a data record with a payload of {@code payloadBytes}; returns its position. */
  public synchronized long append(int payloadBytes) {
    long size = RECORD_HEADER_BYTES + (long) payloadBytes;
    if (offset + size > SEGMENT_BYTES) {
      segment++;
      offset = FILE_HEADER_BYTES;
    }
    long position = (segment << 32) | offset;
    offset += size;
    records++;
    return position;
  }

  /** Appends a BLOCK_INTENT for the records {@code first..last}. */
  public synchronized Intent intent(Table table, long first, long last, int rows) {
    long position = append(INTENT_PAYLOAD_BYTES);
    String blockId =
        String.format(Locale.ROOT, "%s-%08x-%08x", walId, position >>> 32, position & 0xFFFFFFFFL);
    Intent intent = new Intent(table, blockId, first, last, rows);
    open.put(blockId, intent);
    return intent;
  }

  /** Appends the BLOCK_COMMIT of an acknowledged block. */
  public synchronized void commit(Intent intent) {
    if (open.remove(intent.blockId()) == null) {
      throw new IllegalStateException("Commit without open intent: " + intent.blockId());
    }
    append(COMMIT_PAYLOAD_BYTES);
    commits++;
  }

  /** Intents without a commit (what a restart re-inserts), oldest first. */
  public synchronized List<Intent> uncommitted() {
    return new ArrayList<>(open.values());
  }

  /** Records appended so far (data, intents and commits). */
  public synchronized long records() {
    return records;
  }

  /** Commits so far. */
  public synchronized long commits() {
    return commits;
  }

  /** Bytes written so far (all segments). */
  public synchronized long bytes() {
    return (segment - 1) * SEGMENT_BYTES + offset;
  }

  /** The WAL id. */
  public String walId() {
    return walId;
  }
}
