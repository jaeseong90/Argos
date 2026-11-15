/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal;

import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * In-memory summary of one segment for {@link WalGc}: its size, whether the writer has left it, and
 * the last data record of each data type in it (12 §3.4 "회수": a segment is deleted once every
 * record in it is covered by a {@code BLOCK_COMMIT}). Built by the recovery scan for old segments
 * and kept up to date by the writer for the active one. Thread-safe.
 */
public final class SegmentInfo {

  private static final WalRecordType[] DATA_TYPES = {
    WalRecordType.TX, WalRecordType.PROFILE, WalRecordType.METRIC, WalRecordType.STAT
  };

  private final int segmentId;
  private final AtomicLong size;
  private volatile boolean closed;
  // Packed position + 1 of the last data record per type (0: none), index of DATA_TYPES.
  private final AtomicLongArray lastData = new AtomicLongArray(DATA_TYPES.length);

  /**
   * @param segmentId segment id
   * @param size current file size
   */
  public SegmentInfo(int segmentId, long size) {
    this.segmentId = segmentId;
    this.size = new AtomicLong(size);
  }

  /** The segment id. */
  public int segmentId() {
    return segmentId;
  }

  /** Bytes of the file. */
  public long size() {
    return size.get();
  }

  void grow(long bytes) {
    size.addAndGet(bytes);
  }

  /** Sets the size after a recovery cut. */
  void truncatedTo(long newSize) {
    size.set(newSize);
  }

  /** Whether the writer will never append to this segment again. */
  public boolean isClosed() {
    return closed;
  }

  void close() {
    closed = true;
  }

  /** Notes a record written to (or found in) this segment. */
  void recordWritten(WalRecordType type, WalPosition position) {
    int index = index(type);
    if (index >= 0) {
      long value = position.packed() + 1;
      lastData.accumulateAndGet(index, value, Math::max);
    }
  }

  /**
   * The position of the last data record of {@code type} in this segment, packed ({@link
   * WalPosition#packed()}), or -1 when the segment has none.
   */
  public long lastDataPacked(WalRecordType type) {
    int index = index(type);
    return index < 0 ? -1 : lastData.get(index) - 1;
  }

  private static int index(WalRecordType type) {
    for (int i = 0; i < DATA_TYPES.length; i++) {
      if (DATA_TYPES[i] == type) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public String toString() {
    return WalFormat.segmentName(segmentId)
        + "["
        + size.get()
        + " bytes"
        + (closed ? ", closed]" : "]");
  }
}
