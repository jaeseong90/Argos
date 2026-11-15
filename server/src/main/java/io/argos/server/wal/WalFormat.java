/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal;

import io.argos.server.wal.api.WalRecordType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

/**
 * The on-disk format of the write-ahead log (12 §3.4 with the {@code ts_correction_ms} field of
 * ADR-0010 C-129; format version 1, big-endian).
 *
 * <p>Segment file {@code seg-<8 digits>.wal}: a 16-byte header {@code magic "ARGW", u16 version
 * (1), u16 flags (0), u64 created_ms}, then records back to back. Record:
 *
 * <pre>
 * u32 len                  bytes of the body (type .. payload), at least {@value #BODY_HEADER_BYTES}
 * u32 crc32c               CRC-32C of the body
 * body:
 *   u8  type               WalRecordType.code()
 *   u32 instance_id
 *   u64 epoch
 *   u64 seq
 *   u64 received_ms
 *   i64 ts_correction_ms   ADR-0010 C-129: decided at receipt, never recomputed
 *   ... payload            to the end of the body
 * </pre>
 *
 * <p>A {@link io.argos.server.wal.api.WalPosition} names the offset of the {@code len} field and
 * the total length {@value #RECORD_PREFIX_BYTES} + {@code len}. Nothing is preallocated: the file
 * size is the end of the last written record, and a torn tail (a crash during a write) shows as a
 * length beyond the file end, a length below the body header or a CRC mismatch.
 */
public final class WalFormat {

  /** Segment magic {@code ARGW}. */
  static final byte[] MAGIC = "ARGW".getBytes(StandardCharsets.US_ASCII);

  /** Format version written in every segment header. */
  public static final int VERSION = 1;

  /** Bytes of the segment header. */
  public static final int SEGMENT_HEADER_BYTES = 16;

  /** {@code len} and {@code crc32c}. */
  public static final int RECORD_PREFIX_BYTES = 8;

  /**
   * Body bytes before the payload: type, instance_id, epoch, seq, received_ms, ts_correction_ms.
   */
  public static final int BODY_HEADER_BYTES = 1 + 4 + 8 + 8 + 8 + 8;

  /**
   * Largest body a reader accepts. A larger {@code len} is a torn or corrupt record (the
   * collector's messages are at most 4 MiB after decompression, block records are tiny).
   */
  public static final int MAX_BODY_BYTES = 64 * 1024 * 1024;

  private static final Pattern SEGMENT_NAME = Pattern.compile("seg-(\\d{8})\\.wal");

  private WalFormat() {}

  /** {@code seg-<8 digits>.wal}. */
  public static String segmentName(int segmentId) {
    if (segmentId < 0 || segmentId > 99_999_999) {
      throw new IllegalArgumentException("Segment id out of range: " + segmentId);
    }
    return String.format(Locale.ROOT, "seg-%08d.wal", segmentId);
  }

  /** The id of a segment file name, or empty for any other name. */
  public static OptionalInt segmentId(String fileName) {
    Matcher m = SEGMENT_NAME.matcher(fileName);
    return m.matches() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
  }

  /** The 16-byte segment header. */
  static ByteBuffer segmentHeader(long createdMs) {
    ByteBuffer header = ByteBuffer.allocate(SEGMENT_HEADER_BYTES);
    header.put(MAGIC);
    header.putShort((short) VERSION);
    header.putShort((short) 0);
    header.putLong(createdMs);
    header.flip();
    return header;
  }

  /**
   * Checks a segment header.
   *
   * @throws IllegalArgumentException for a wrong magic or an unknown version
   */
  static void checkSegmentHeader(ByteBuffer header) {
    byte[] magic = new byte[MAGIC.length];
    header.get(magic);
    for (int i = 0; i < MAGIC.length; i++) {
      if (magic[i] != MAGIC[i]) {
        throw new IllegalArgumentException("Not a WAL segment (magic)");
      }
    }
    int version = header.getShort() & 0xFFFF;
    if (version != VERSION) {
      throw new IllegalArgumentException("Unsupported WAL segment version " + version);
    }
  }

  /** Total bytes of a record with {@code payloadLength} payload bytes. */
  public static int recordLength(int payloadLength) {
    return RECORD_PREFIX_BYTES + BODY_HEADER_BYTES + payloadLength;
  }

  /** The complete record bytes ({@code len}, {@code crc32c}, body). */
  static byte[] encode(
      WalRecordType type,
      int instanceId,
      long epoch,
      long seq,
      long receivedMs,
      long tsCorrectionMs,
      byte[] payload) {
    int bodyLength = BODY_HEADER_BYTES + payload.length;
    ByteBuffer out = ByteBuffer.allocate(RECORD_PREFIX_BYTES + bodyLength);
    out.putInt(bodyLength);
    out.putInt(0); // CRC below
    out.put((byte) type.code());
    out.putInt(instanceId);
    out.putLong(epoch);
    out.putLong(seq);
    out.putLong(receivedMs);
    out.putLong(tsCorrectionMs);
    out.put(payload);
    byte[] bytes = out.array();
    CRC32C crc = new CRC32C();
    crc.update(bytes, RECORD_PREFIX_BYTES, bodyLength);
    ByteBuffer.wrap(bytes).putInt(4, (int) crc.getValue());
    return bytes;
  }

  /** Whether {@code body} (without the prefix) has the CRC {@code expected}. */
  static boolean crcMatches(byte[] body, int offset, int length, int expected) {
    CRC32C crc = new CRC32C();
    crc.update(body, offset, length);
    return (int) crc.getValue() == expected;
  }
}
