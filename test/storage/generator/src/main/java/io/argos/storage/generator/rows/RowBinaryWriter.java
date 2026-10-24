/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.rows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Growable little-endian buffer for the ClickHouse RowBinary format: fixed-size integers and floats
 * little-endian, strings and arrays prefixed by a LEB128 length, IPv6 as 16 bytes in network order,
 * FixedString(n) as n bytes.
 */
public final class RowBinaryWriter {

  private byte[] buf;
  private int len;

  public RowBinaryWriter(int initialCapacity) {
    buf = new byte[Math.max(16, initialCapacity)];
  }

  /** Bytes written so far. */
  public int length() {
    return len;
  }

  /** Discards the content, keeping the capacity. */
  public void reset() {
    len = 0;
  }

  /** A copy of the content. */
  public byte[] toByteArray() {
    return Arrays.copyOf(buf, len);
  }

  /** Appends the content of another writer. */
  public void append(RowBinaryWriter other) {
    ensure(other.len);
    System.arraycopy(other.buf, 0, buf, len, other.len);
    len += other.len;
  }

  public void u8(int v) {
    ensure(1);
    buf[len++] = (byte) v;
  }

  public void u16(int v) {
    ensure(2);
    buf[len++] = (byte) v;
    buf[len++] = (byte) (v >>> 8);
  }

  public void u32(long v) {
    i32((int) v);
  }

  public void i32(int v) {
    ensure(4);
    buf[len++] = (byte) v;
    buf[len++] = (byte) (v >>> 8);
    buf[len++] = (byte) (v >>> 16);
    buf[len++] = (byte) (v >>> 24);
  }

  public void i64(long v) {
    ensure(8);
    for (int i = 0; i < 8; i++) {
      buf[len++] = (byte) (v >>> (8 * i));
    }
  }

  public void f64(double v) {
    i64(Double.doubleToRawLongBits(v));
  }

  /** Unsigned LEB128 (lengths of strings and arrays). */
  public void varint(long v) {
    ensure(10);
    long x = v;
    while ((x & ~0x7FL) != 0) {
      buf[len++] = (byte) ((x & 0x7F) | 0x80);
      x >>>= 7;
    }
    buf[len++] = (byte) x;
  }

  /** String (UTF-8 with LEB128 byte length). */
  public void string(String s) {
    int n = s.length();
    boolean ascii = true;
    for (int i = 0; i < n; i++) {
      if (s.charAt(i) >= 0x80) {
        ascii = false;
        break;
      }
    }
    if (ascii) {
      varint(n);
      ensure(n);
      for (int i = 0; i < n; i++) {
        buf[len++] = (byte) s.charAt(i);
      }
    } else {
      bytesWithLength(s.getBytes(StandardCharsets.UTF_8));
    }
  }

  /** Length-prefixed bytes (String column with arbitrary bytes). */
  public void bytesWithLength(byte[] bytes) {
    varint(bytes.length);
    raw(bytes);
  }

  /** Raw bytes (FixedString). */
  public void raw(byte[] bytes) {
    ensure(bytes.length);
    System.arraycopy(bytes, 0, buf, len, bytes.length);
    len += bytes.length;
  }

  /** IPv6 column holding an IPv4 address as {@code ::ffff:a.b.c.d}; 0 writes {@code ::}. */
  public void ipv4Mapped(int ipv4) {
    ensure(16);
    Arrays.fill(buf, len, len + 16, (byte) 0);
    if (ipv4 != 0) {
      buf[len + 10] = (byte) 0xFF;
      buf[len + 11] = (byte) 0xFF;
      buf[len + 12] = (byte) (ipv4 >>> 24);
      buf[len + 13] = (byte) (ipv4 >>> 16);
      buf[len + 14] = (byte) (ipv4 >>> 8);
      buf[len + 15] = (byte) ipv4;
    }
    len += 16;
  }

  /** Array(UInt64) with the first {@code n} values. */
  public void u64Array(long[] values, int n) {
    varint(n);
    for (int i = 0; i < n; i++) {
      i64(values[i]);
    }
  }

  /** Array(UInt32) from long values. */
  public void u32Array(long[] values) {
    varint(values.length);
    for (long v : values) {
      i32((int) v);
    }
  }

  private void ensure(int extra) {
    int need = len + extra;
    if (need > buf.length) {
      int capacity =
          Math.max(need, buf.length < (1 << 29) ? buf.length * 2 : buf.length + (1 << 28));
      if (capacity < 0) {
        throw new IllegalStateException("Block too large: " + need + " bytes");
      }
      buf = Arrays.copyOf(buf, capacity);
    }
  }
}
