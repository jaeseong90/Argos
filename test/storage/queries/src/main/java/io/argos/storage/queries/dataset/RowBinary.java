/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.dataset;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Encoder for the ClickHouse RowBinary format (little-endian fixed-size values, LEB128 lengths),
 * the format of the batch writer (13 §7.4: explicit columns, RowBinary). Columns are written in the
 * order of the INSERT column list; there is no header.
 */
public final class RowBinary {

  private final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
  private int rows;

  /** Marks the end of a row (for {@link #rows()}). */
  public RowBinary endRow() {
    rows++;
    return this;
  }

  /** Number of rows ended so far. */
  public int rows() {
    return rows;
  }

  /** The encoded bytes. */
  public byte[] toByteArray() {
    return out.toByteArray();
  }

  /** Bytes written so far. */
  public int size() {
    return out.size();
  }

  /** UInt8, Int8, Enum8. */
  public RowBinary u8(int value) {
    out.write(value & 0xFF);
    return this;
  }

  /** UInt16. */
  public RowBinary u16(int value) {
    out.write(value & 0xFF);
    out.write((value >>> 8) & 0xFF);
    return this;
  }

  /** UInt32, Int32, DateTime. */
  public RowBinary u32(long value) {
    for (int i = 0; i < 4; i++) {
      out.write((int) (value >>> (8 * i)) & 0xFF);
    }
    return this;
  }

  /** UInt64, Int64, DateTime64 (ticks). */
  public RowBinary u64(long value) {
    for (int i = 0; i < 8; i++) {
      out.write((int) (value >>> (8 * i)) & 0xFF);
    }
    return this;
  }

  /** Float64. */
  public RowBinary f64(double value) {
    return u64(Double.doubleToRawLongBits(value));
  }

  /** String (and LowCardinality(String)): LEB128 length then UTF-8 bytes. */
  public RowBinary string(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    varint(bytes.length);
    out.writeBytes(bytes);
    return this;
  }

  /** FixedString(n): exactly {@code bytes.length} bytes. */
  public RowBinary fixed(byte[] bytes) {
    out.writeBytes(bytes);
    return this;
  }

  /** IPv6: 16 bytes in network order. */
  public RowBinary ipv6(byte[] address) {
    if (address.length != 16) {
      throw new IllegalArgumentException("IPv6 needs 16 bytes");
    }
    out.writeBytes(address);
    return this;
  }

  /** Array(UInt64). */
  public RowBinary u64Array(long[] values) {
    varint(values.length);
    for (long v : values) {
      u64(v);
    }
    return this;
  }

  /** Array(UInt32). */
  public RowBinary u32Array(long[] values) {
    varint(values.length);
    for (long v : values) {
      u32(v);
    }
    return this;
  }

  private void varint(long value) {
    long v = value;
    while ((v & ~0x7FL) != 0) {
      out.write((int) ((v & 0x7F) | 0x80));
      v >>>= 7;
    }
    out.write((int) v);
  }
}
