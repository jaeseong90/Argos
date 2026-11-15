/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal;

import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reads the records of one segment (12 §3.4 {@code WalReader}): sequentially from an offset, or one
 * record at a known position. Reading never changes the file; the torn tail of a crash is reported
 * by {@link #scan} and cut by {@link WalRecovery}.
 *
 * <p>Not thread-safe; each reader owns a read-only channel. Records may be read while the writer
 * appends to the same file: the caller limits itself to bytes that are known to be durable.
 */
public final class WalSegmentReader implements AutoCloseable {

  private static final int CHUNK = 1 << 20;

  private final int segmentId;
  private final FileChannel channel;
  private ByteBuffer chunk = ByteBuffer.allocate(CHUNK);
  private long chunkStart = -1;

  private WalSegmentReader(int segmentId, FileChannel channel) {
    this.segmentId = segmentId;
    this.channel = channel;
  }

  /**
   * Opens segment {@code segmentId} of {@code file} read-only and checks its header.
   *
   * @throws IOException when the file cannot be read or is not a version-1 segment
   */
  public static WalSegmentReader open(Path file, int segmentId) throws IOException {
    FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
    try {
      ByteBuffer header = ByteBuffer.allocate(WalFormat.SEGMENT_HEADER_BYTES);
      readFully(channel, header, 0);
      header.flip();
      WalFormat.checkSegmentHeader(header);
    } catch (IOException | RuntimeException e) {
      channel.close();
      throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
    }
    return new WalSegmentReader(segmentId, channel);
  }

  /** The segment id. */
  public int segmentId() {
    return segmentId;
  }

  /** Current size of the file. */
  public long size() throws IOException {
    return channel.size();
  }

  /** Outcome of {@link #scan}. */
  public record ScanResult(long validEnd, long fileSize, int records, String tornReason) {

    /** Whether bytes after {@link #validEnd()} are not a valid record (a torn or corrupt tail). */
    public boolean torn() {
      return tornReason != null;
    }
  }

  /** Receives the records of a scan in file order. */
  @FunctionalInterface
  public interface RecordSink {
    /** One valid record. */
    void accept(WalRecord record) throws IOException;
  }

  /**
   * Reads every valid record from {@code fromOffset} up to {@code limit} (exclusive; {@link
   * Long#MAX_VALUE} for the whole file) and stops at the first record that is incomplete, too long
   * or fails its CRC.
   *
   * @param fromOffset offset of a record, at least {@value WalFormat#SEGMENT_HEADER_BYTES}
   * @param limit offset after the last byte to look at
   * @param sink receives each record; {@code null} to only validate
   */
  public ScanResult scan(long fromOffset, long limit, RecordSink sink) throws IOException {
    long fileSize = channel.size();
    long end = Math.min(fileSize, limit);
    long offset = Math.max(fromOffset, WalFormat.SEGMENT_HEADER_BYTES);
    int count = 0;
    while (offset < end) {
      if (end - offset < WalFormat.RECORD_PREFIX_BYTES) {
        return new ScanResult(offset, fileSize, count, "incomplete record prefix");
      }
      ByteBuffer prefix = bytes(offset, WalFormat.RECORD_PREFIX_BYTES);
      int bodyLength = prefix.getInt();
      int crc = prefix.getInt();
      if (bodyLength < WalFormat.BODY_HEADER_BYTES || bodyLength > WalFormat.MAX_BODY_BYTES) {
        return new ScanResult(offset, fileSize, count, "invalid record length " + bodyLength);
      }
      long recordEnd = offset + WalFormat.RECORD_PREFIX_BYTES + bodyLength;
      if (recordEnd > end) {
        return new ScanResult(offset, fileSize, count, "record beyond the end");
      }
      byte[] body = new byte[bodyLength];
      bytes(offset + WalFormat.RECORD_PREFIX_BYTES, bodyLength).get(body);
      if (!WalFormat.crcMatches(body, 0, bodyLength, crc)) {
        return new ScanResult(offset, fileSize, count, "CRC mismatch");
      }
      WalRecord record;
      try {
        record =
            parse(
                new WalPosition(segmentId, offset, WalFormat.RECORD_PREFIX_BYTES + bodyLength),
                body);
      } catch (IllegalArgumentException e) {
        return new ScanResult(offset, fileSize, count, e.getMessage());
      }
      if (sink != null) {
        sink.accept(record);
      }
      count++;
      offset = recordEnd;
    }
    return new ScanResult(offset, fileSize, count, null);
  }

  /**
   * The record that starts at {@code offset}, read within {@code limit}; {@code null} when no
   * complete record lies between them.
   *
   * @throws IOException when the bytes are complete but not a valid record (corruption)
   */
  public WalRecord readAt(long offset, long limit) throws IOException {
    if (limit - offset < WalFormat.RECORD_PREFIX_BYTES) {
      return null;
    }
    ByteBuffer prefix = bytes(offset, WalFormat.RECORD_PREFIX_BYTES);
    int bodyLength = prefix.getInt();
    int crc = prefix.getInt();
    if (bodyLength < WalFormat.BODY_HEADER_BYTES || bodyLength > WalFormat.MAX_BODY_BYTES) {
      throw new IOException(
          "Invalid record length "
              + bodyLength
              + " at "
              + WalFormat.segmentName(segmentId)
              + ":"
              + offset);
    }
    long recordEnd = offset + WalFormat.RECORD_PREFIX_BYTES + bodyLength;
    if (recordEnd > limit) {
      return null;
    }
    byte[] body = new byte[bodyLength];
    bytes(offset + WalFormat.RECORD_PREFIX_BYTES, bodyLength).get(body);
    if (!WalFormat.crcMatches(body, 0, bodyLength, crc)) {
      throw new IOException("CRC mismatch at " + WalFormat.segmentName(segmentId) + ":" + offset);
    }
    return parse(
        new WalPosition(segmentId, offset, WalFormat.RECORD_PREFIX_BYTES + bodyLength), body);
  }

  /**
   * The record at {@code position}.
   *
   * @throws IOException when the bytes cannot be read or are not a valid record
   */
  public WalRecord read(WalPosition position) throws IOException {
    if (position.segmentId() != segmentId) {
      throw new IllegalArgumentException(
          "Position " + position + " is not in segment " + segmentId);
    }
    int bodyLength = position.length() - WalFormat.RECORD_PREFIX_BYTES;
    if (bodyLength < WalFormat.BODY_HEADER_BYTES) {
      throw new IOException("Invalid record length at " + position);
    }
    ByteBuffer all = ByteBuffer.allocate(position.length());
    readFully(channel, all, position.offset());
    all.flip();
    int storedLength = all.getInt();
    int crc = all.getInt();
    if (storedLength != bodyLength) {
      throw new IOException("Record length mismatch at " + position);
    }
    byte[] body = new byte[bodyLength];
    all.get(body);
    if (!WalFormat.crcMatches(body, 0, bodyLength, crc)) {
      throw new IOException("CRC mismatch at " + position);
    }
    return parse(position, body);
  }

  private static WalRecord parse(WalPosition position, byte[] body) {
    ByteBuffer in = ByteBuffer.wrap(body);
    WalRecordType type = WalRecordType.fromCode(in.get() & 0xFF);
    int instanceId = in.getInt();
    long epoch = in.getLong();
    long seq = in.getLong();
    long receivedMs = in.getLong();
    long tsCorrectionMs = in.getLong();
    byte[] payload = new byte[in.remaining()];
    in.get(payload);
    return new WalRecord(
        position, type, instanceId, epoch, seq, receivedMs, tsCorrectionMs, payload);
  }

  /** {@code length} bytes at {@code offset}, served from the read-ahead chunk when possible. */
  private ByteBuffer bytes(long offset, int length) throws IOException {
    if (length > CHUNK) {
      ByteBuffer big = ByteBuffer.allocate(length);
      readFully(channel, big, offset);
      big.flip();
      return big;
    }
    if (chunkStart < 0 || offset < chunkStart || offset + length > chunkStart + chunk.limit()) {
      chunk.clear();
      long size = channel.size();
      int want = (int) Math.min(CHUNK, size - offset);
      if (want < length) {
        throw new EOFException("Read beyond the end of " + WalFormat.segmentName(segmentId));
      }
      chunk.limit(want);
      readFully(channel, chunk, offset);
      chunk.flip();
      chunkStart = offset;
    }
    ByteBuffer view = chunk.duplicate();
    view.position((int) (offset - chunkStart));
    view.limit(view.position() + length);
    return view.slice();
  }

  /** Forgets the read-ahead chunk (the file grew or was truncated). */
  void invalidate() {
    chunkStart = -1;
  }

  static void readFully(FileChannel channel, ByteBuffer dst, long position) throws IOException {
    long at = position;
    while (dst.hasRemaining()) {
      int n = channel.read(dst, at);
      if (n < 0) {
        throw new EOFException("Unexpected end of WAL file at " + at);
      }
      at += n;
    }
  }

  @Override
  public void close() throws IOException {
    chunk = null;
    channel.close();
  }
}
