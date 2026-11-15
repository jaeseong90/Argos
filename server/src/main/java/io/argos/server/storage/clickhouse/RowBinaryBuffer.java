/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.clickhouse;

import com.google.protobuf.ByteString;
import io.argos.server.storage.api.Column;
import io.argos.server.storage.api.Table;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Growable buffer of the ClickHouse RowBinary format: fixed-size integers and floats little-endian,
 * strings and arrays with an unsigned LEB128 length, {@code Enum8} as one byte, {@code IPv6} as 16
 * bytes in network order, {@code FixedString(n)} as n bytes, {@code DateTime64(3)} as Int64
 * milliseconds and {@code DateTime} as UInt32 seconds, {@code Map} as a LEB128 count of key and
 * value pairs. The same encoding as the storage generator's {@code RowBinaryWriter} (WO-03),
 * rewritten for the server (the generator is a test project).
 */
public final class RowBinaryBuffer {

  private byte[] buf;
  private int len;

  /** A buffer with {@code initialCapacity} bytes reserved. */
  public RowBinaryBuffer(int initialCapacity) {
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

  /**
   * Cuts the content back to its first {@code length} bytes (a row that failed half-way is removed
   * this way).
   *
   * @param length a value {@link #length()} returned earlier, at most the current length
   */
  public void truncate(int length) {
    if (length < 0 || length > len) {
      throw new IllegalArgumentException("truncate(" + length + ") of " + len + " bytes");
    }
    len = length;
  }

  /** The content as an immutable byte string (a copy). */
  public ByteString toByteString() {
    return ByteString.copyFrom(buf, 0, len);
  }

  /** {@code UInt8}, {@code Int8}, {@code Enum8}. */
  public void u8(int v) {
    ensure(1);
    buf[len++] = (byte) v;
  }

  /** {@code UInt16}. */
  public void u16(int v) {
    ensure(2);
    buf[len++] = (byte) v;
    buf[len++] = (byte) (v >>> 8);
  }

  /** {@code UInt32}, {@code Int32}. */
  public void i32(int v) {
    ensure(4);
    buf[len++] = (byte) v;
    buf[len++] = (byte) (v >>> 8);
    buf[len++] = (byte) (v >>> 16);
    buf[len++] = (byte) (v >>> 24);
  }

  /** {@code UInt64}, {@code Int64}, {@code DateTime64(3)} milliseconds. */
  public void i64(long v) {
    ensure(8);
    for (int i = 0; i < 8; i++) {
      buf[len++] = (byte) (v >>> (8 * i));
    }
  }

  /** {@code Float64}. */
  public void f64(double v) {
    i64(Double.doubleToRawLongBits(v));
  }

  /** Unsigned LEB128 (lengths of strings, arrays and maps). */
  public void varint(long v) {
    ensure(10);
    long x = v;
    while ((x & ~0x7FL) != 0) {
      buf[len++] = (byte) ((x & 0x7F) | 0x80);
      x >>>= 7;
    }
    buf[len++] = (byte) x;
  }

  /** {@code String} (UTF-8 with its byte length); {@code null} is written as {@code ''}. */
  public void string(String s) {
    if (s == null || s.isEmpty()) {
      varint(0);
      return;
    }
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

  /** {@code String} from raw bytes. */
  public void bytesWithLength(byte[] bytes) {
    varint(bytes.length);
    raw(bytes, 0, bytes.length);
  }

  /** {@code FixedString(n)}: exactly {@code n} bytes, {@code value} cut or padded with zeros. */
  public void fixed(byte[] value, int n) {
    ensure(n);
    int copy = value == null ? 0 : Math.min(n, value.length);
    if (copy > 0) {
      System.arraycopy(value, 0, buf, len, copy);
    }
    Arrays.fill(buf, len + copy, len + n, (byte) 0);
    len += n;
  }

  /** Raw bytes. */
  public void raw(byte[] bytes, int offset, int length) {
    ensure(length);
    System.arraycopy(bytes, offset, buf, len, length);
    len += length;
  }

  /**
   * {@code IPv6}: 16 bytes as given, 4 bytes as the IPv4-mapped {@code ::ffff:a.b.c.d}, anything
   * else (empty included) as {@code ::}.
   */
  public void ipv6(byte[] address) {
    ensure(16);
    Arrays.fill(buf, len, len + 16, (byte) 0);
    if (address != null && address.length == 16) {
      System.arraycopy(address, 0, buf, len, 16);
    } else if (address != null && address.length == 4) {
      buf[len + 10] = (byte) 0xFF;
      buf[len + 11] = (byte) 0xFF;
      System.arraycopy(address, 0, buf, len + 12, 4);
    }
    len += 16;
  }

  private void ensure(int extra) {
    int need = len + extra;
    if (need < 0) {
      throw new IllegalStateException("RowBinary buffer too large");
    }
    if (need > buf.length) {
      int capacity =
          Math.max(need, buf.length < (1 << 29) ? buf.length * 2 : buf.length + (1 << 28));
      if (capacity < 0) {
        throw new IllegalStateException("RowBinary buffer too large: " + need + " bytes");
      }
      buf = Arrays.copyOf(buf, capacity);
    }
  }

  /**
   * The {@code RowBinaryWithNamesAndTypes} header of {@code table}: the column count, the column
   * names and the types of the catalogue ({@link Table#columns()}, the explicit column list of 13
   * §7.4).
   */
  public static ByteString header(Table table) {
    RowBinaryBuffer out = new RowBinaryBuffer(4096);
    out.varint(table.columns().size());
    for (Column column : table.columns()) {
      out.string(column.name());
    }
    for (Column column : table.columns()) {
      out.string(column.type());
    }
    return out.toByteString();
  }
}
