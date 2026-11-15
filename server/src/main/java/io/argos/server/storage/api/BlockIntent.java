/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.api;

import io.argos.server.wal.api.WalPosition;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The content of a {@code BLOCK_INTENT} WAL record (12 §3.4 "블록 인덱스", §3.8): the table, the
 * positions of the first and last data record of the block, and the token recorded before the
 * insert (ADR-0007 C-61). Recovery re-inserts every intent without a {@code BLOCK_COMMIT} with
 * exactly this token, before any listener opens (DSN-12-15).
 *
 * <p>Payload format version 1, big-endian: {@code u8 format (1), u8 table code ({@link
 * Table#code()}), first: u32 segment, u32 offset, u32 length, last: the same, u16 length + UTF-8
 * token}.
 *
 * @param table the raw table of the block
 * @param first position of the first data record of the block
 * @param last position of the last data record of the block, not before {@code first}
 * @param token the token of this block; its range equals {@code first}-{@code last}
 */
public record BlockIntent(Table table, WalPosition first, WalPosition last, BlockToken token) {

  /** The format byte written by this version. */
  public static final int FORMAT = 1;

  /** Validates that the token names this range. */
  public BlockIntent {
    Objects.requireNonNull(table, "table");
    Objects.requireNonNull(first, "first");
    Objects.requireNonNull(last, "last");
    Objects.requireNonNull(token, "token");
    if (first.compareTo(last) > 0) {
      throw new IllegalArgumentException("first " + first + " after last " + last);
    }
    if (token.first() != first.packed() || token.last() != last.packed()) {
      throw new IllegalArgumentException("Token " + token + " does not name " + first + "-" + last);
    }
  }

  /** The payload of the intent record ({@code WalWriter.appendBlockIntent}). */
  public byte[] encode() {
    byte[] tokenBytes = token.value().getBytes(StandardCharsets.UTF_8);
    ByteBuffer out = ByteBuffer.allocate(1 + 1 + 12 + 12 + 2 + tokenBytes.length);
    out.put((byte) FORMAT);
    out.put((byte) table.code());
    put(out, first);
    put(out, last);
    out.putShort((short) tokenBytes.length);
    out.put(tokenBytes);
    return out.array();
  }

  /**
   * Reads a payload written by {@link #encode()}.
   *
   * @throws IllegalArgumentException for an unknown format or table, or a truncated payload
   */
  public static BlockIntent decode(byte[] payload) {
    ByteBuffer in = ByteBuffer.wrap(payload);
    try {
      int format = in.get() & 0xFF;
      if (format != FORMAT) {
        throw new IllegalArgumentException("Unknown block intent format " + format);
      }
      int code = in.get() & 0xFF;
      Table table =
          Table.fromCode(code)
              .orElseThrow(() -> new IllegalArgumentException("Unknown table code " + code));
      WalPosition first = position(in);
      WalPosition last = position(in);
      byte[] tokenBytes = new byte[in.getShort() & 0xFFFF];
      in.get(tokenBytes);
      return new BlockIntent(
          table, first, last, BlockToken.parse(new String(tokenBytes, StandardCharsets.UTF_8)));
    } catch (BufferUnderflowException e) {
      throw new IllegalArgumentException(
          "Truncated block intent of " + payload.length + " bytes", e);
    }
  }

  private static void put(ByteBuffer out, WalPosition position) {
    out.putInt(position.segmentId());
    out.putInt((int) position.offset());
    out.putInt(position.length());
  }

  private static WalPosition position(ByteBuffer in) {
    int segmentId = in.getInt();
    long offset = in.getInt() & 0xFFFF_FFFFL;
    int length = in.getInt();
    return new WalPosition(segmentId, offset, length);
  }
}
