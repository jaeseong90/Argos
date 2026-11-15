/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal;

import io.argos.server.wal.api.WalPosition;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;

/**
 * Reads WAL records in WAL order from a start position up to the durable end and then follows the
 * writer (12 §3.4 {@code WalReader}, §3.8 {@code WalReplayer}). The ingest dispatcher uses one tail
 * reader for recovery, the live path and the catch-up after a backlog alike, so that every record
 * is decoded from the same bytes on every path (D-13: a replay stores what the original path would
 * have stored).
 *
 * <p>Never reads beyond {@link FileWal#durableMark()}: rows of a record reach ClickHouse only after
 * the record is fsynced, so a crash cannot leave rows of an un-acked batch behind. Not thread-safe.
 */
public final class WalTailReader implements AutoCloseable {

  private final FileWal wal;
  private WalSegmentReader reader;
  private int segmentId;
  private long offset;

  /**
   * A reader that starts at the record at ({@code segmentId}, {@code offset}).
   *
   * @param wal the started WAL
   * @param segmentId first segment to read
   * @param offset offset of the first record in it ({@value WalFormat#SEGMENT_HEADER_BYTES} for the
   *     segment start)
   */
  public WalTailReader(FileWal wal, int segmentId, long offset) {
    this.wal = Objects.requireNonNull(wal, "wal");
    this.segmentId = segmentId;
    this.offset = Math.max(offset, WalFormat.SEGMENT_HEADER_BYTES);
  }

  /** The position the next record is read from (segment, offset). */
  public long nextOffset() {
    return offset;
  }

  /** The segment the next record is read from. */
  public int nextSegmentId() {
    return segmentId;
  }

  /** Whether every durable record has been read. */
  public boolean atDurableEnd() {
    DurableMark mark = wal.durableMark();
    return segmentId > mark.segmentId()
        || (segmentId == mark.segmentId() && offset >= mark.offset());
  }

  /**
   * The next durable record, waiting up to {@code timeoutMs} for the writer; {@code null} when none
   * became durable in that time.
   *
   * @throws IOException when a durable record cannot be read (corruption, a deleted segment)
   */
  public WalRecord next(long timeoutMs) throws IOException, InterruptedException {
    long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
    while (true) {
      DurableMark mark = wal.durableMark();
      if (segmentId < mark.segmentId() || segmentId == mark.segmentId()) {
        long limit = segmentId < mark.segmentId() ? Long.MAX_VALUE : mark.offset();
        WalSegmentReader current = reader();
        if (current != null) {
          long end = Math.min(limit, current.size());
          if (offset < end) {
            WalRecord record = current.readAt(offset, end);
            if (record != null) {
              offset = record.position().end();
              return record;
            }
            if (segmentId == mark.segmentId()) {
              throw new IOException(
                  "Incomplete durable record at "
                      + WalFormat.segmentName(segmentId)
                      + ":"
                      + offset);
            }
          }
          if (segmentId < mark.segmentId()) {
            advanceSegment();
            continue;
          }
        } else if (segmentId < mark.segmentId()) {
          advanceSegment();
          continue;
        }
      }
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        return null;
      }
      wal.awaitDurableBeyond(mark, Math.max(1, left / 1_000_000L));
    }
  }

  private WalSegmentReader reader() throws IOException {
    if (reader == null || reader.segmentId() != segmentId) {
      closeReader();
      var file = wal.directory().segmentFile(segmentId);
      if (!Files.exists(file)) {
        return null;
      }
      reader = WalSegmentReader.open(file, segmentId);
    }
    return reader;
  }

  private void advanceSegment() throws IOException {
    closeReader();
    segmentId++;
    offset = WalFormat.SEGMENT_HEADER_BYTES;
  }

  private void closeReader() throws IOException {
    if (reader != null) {
      reader.close();
      reader = null;
    }
  }

  /**
   * Reads the records from {@code first} to {@code last} (inclusive, WAL order) of {@code dir}: the
   * range of a block intent that recovery inserts again (12 §3.4).
   *
   * @param sink receives each record of the range
   * @throws IOException when a record of the range cannot be read
   */
  public static void readRange(
      WalDirectory dir, WalPosition first, WalPosition last, WalSegmentReader.RecordSink sink)
      throws IOException {
    for (int id = first.segmentId(); id <= last.segmentId(); id++) {
      var file = dir.segmentFile(id);
      if (!Files.exists(file)) {
        throw new IOException("WAL segment " + file + " of a block range is missing");
      }
      try (WalSegmentReader segment = WalSegmentReader.open(file, id)) {
        long from = id == first.segmentId() ? first.offset() : WalFormat.SEGMENT_HEADER_BYTES;
        long to = id == last.segmentId() ? last.end() : Long.MAX_VALUE;
        segment.scan(from, to, sink);
      }
    }
  }

  @Override
  public void close() throws IOException {
    closeReader();
  }
}
