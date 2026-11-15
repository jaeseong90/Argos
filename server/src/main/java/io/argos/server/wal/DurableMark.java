/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal;

import io.argos.server.wal.api.WalPosition;

/**
 * The durable end of the WAL: every record before offset {@code offset} of segment {@code
 * segmentId}, and every record of an earlier segment, is on disk (fsynced). Readers that feed
 * ClickHouse never read beyond it, so a crash can never leave rows in ClickHouse whose batch was
 * not acked (D-13).
 *
 * @param segmentId the active segment when the mark was taken
 * @param offset end of the last durable record in that segment
 */
public record DurableMark(int segmentId, long offset) implements Comparable<DurableMark> {

  /** The mark of an empty WAL that has not opened a segment yet. */
  public static final DurableMark NONE = new DurableMark(-1, 0);

  /** Whether the record at {@code position} is durable under this mark. */
  public boolean covers(WalPosition position) {
    return position.segmentId() < segmentId
        || (position.segmentId() == segmentId && position.end() <= offset);
  }

  @Override
  public int compareTo(DurableMark other) {
    int bySegment = Integer.compare(segmentId, other.segmentId);
    return bySegment != 0 ? bySegment : Long.compare(offset, other.offset);
  }
}
