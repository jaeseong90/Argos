/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal.api;

/**
 * The location of one WAL record (12 §2 {@code WalPosition}): segment {@code seg-<segmentId>.wal},
 * byte offset of the record's {@code len} field in that segment, and the record's total length.
 *
 * <p>Positions are ordered by segment, then offset: the WAL order, which is the write order. Two
 * records never share (segment, offset), so {@link #compareTo} ignores the length.
 *
 * <p>The deduplication token is not derived here any more: 12 §2 {@code dedupToken()} ({@code
 * "wal:" + segment + ":" + offset}) is superseded by ADR-0007 C-61 ({@code
 * io.argos.server.storage.api.BlockToken}), which uses {@link #packed()}.
 *
 * @param segmentId segment number ({@code seg-<8 digits>.wal}), at least 0
 * @param offset byte offset in the segment, {@code 0..}{@value #MAX_OFFSET}; segments rotate at
 *     {@code argos.wal.segment-bytes} (256 MiB by default), far below the limit
 * @param length record length in bytes, header included, at least 1
 */
public record WalPosition(int segmentId, long offset, int length)
    implements Comparable<WalPosition> {

  /** Largest offset: positions pack into 64 bits as {@code segmentId << 32 | offset}. */
  public static final long MAX_OFFSET = 0xFFFF_FFFFL;

  /** Rejects positions that cannot be packed or do not exist. */
  public WalPosition {
    if (segmentId < 0) {
      throw new IllegalArgumentException("segmentId < 0: " + segmentId);
    }
    if (offset < 0 || offset > MAX_OFFSET) {
      throw new IllegalArgumentException("offset out of range: " + offset);
    }
    if (length < 1) {
      throw new IllegalArgumentException("length < 1: " + length);
    }
  }

  /**
   * The position as one unsigned 64-bit number, {@code segmentId << 32 | offset}: monotonic in WAL
   * order. The C-61 token renders it as lower-case hex ({@code BlockToken}).
   */
  public long packed() {
    return ((long) segmentId << 32) | offset;
  }

  /** The offset just after this record in its segment. */
  public long end() {
    return offset + length;
  }

  @Override
  public int compareTo(WalPosition other) {
    int bySegment = Integer.compare(segmentId, other.segmentId);
    return bySegment != 0 ? bySegment : Long.compare(offset, other.offset);
  }
}
