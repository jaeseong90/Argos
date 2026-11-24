/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.diagnostics;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/**
 * A minimal POSIX ustar (IEEE 1003.1-1988) writer with gzip compression for the diagnostic bundle,
 * so that the server needs no archive library: regular files only, mode {@code 0600}, owner and
 * group 0, ASCII names of at most 255 bytes (split into prefix and name at a {@code /}), sizes
 * below 8 GiB. The archive ends with two zero blocks; {@code tar -xzf} and every common reader
 * accept it.
 */
public final class TarGzWriter implements Closeable {

  /** Block size of the format. */
  static final int BLOCK = 512;

  /** Largest entry the 11 octal digits of the size field hold. */
  static final long MAX_SIZE = 077777777777L;

  private final OutputStream out;
  private final long mtime;
  private boolean closed;

  /**
   * Writes to {@code target} (which the caller created with the permissions it wants).
   *
   * @param mtime the modification time of every entry
   */
  public TarGzWriter(OutputStream target, Instant mtime) throws IOException {
    this.out = new GZIPOutputStream(target, 64 * 1024);
    this.mtime = mtime.getEpochSecond();
  }

  /** Adds a file entry with {@code data}. */
  public void add(String name, byte[] data) throws IOException {
    header(name, data.length);
    out.write(data);
    pad(data.length);
  }

  /** Adds a file entry with the content of {@code file}. */
  public void add(String name, Path file) throws IOException {
    long size = Files.size(file);
    header(name, size);
    long copied;
    try (InputStream in = Files.newInputStream(file)) {
      copied = in.transferTo(out);
    }
    if (copied != size) {
      throw new IOException("The size of " + file.getFileName() + " changed while it was archived");
    }
    pad(size);
  }

  private void header(String name, long size) throws IOException {
    if (size < 0 || size > MAX_SIZE) {
      throw new IOException("Entry too large for ustar: " + name);
    }
    byte[] h = new byte[BLOCK];
    String[] split = split(name);
    put(h, 0, 100, split[1]);
    octal(h, 100, 8, 0600);
    octal(h, 108, 8, 0);
    octal(h, 116, 8, 0);
    octal(h, 124, 12, size);
    octal(h, 136, 12, mtime);
    Arrays.fill(h, 148, 156, (byte) ' ');
    h[156] = '0';
    put(h, 257, 6, "ustar");
    h[263] = '0';
    h[264] = '0';
    put(h, 265, 32, "argos");
    put(h, 297, 32, "argos");
    put(h, 345, 155, split[0]);
    long sum = 0;
    for (byte b : h) {
      sum += b & 0xff;
    }
    byte[] chk = String.format(Locale.ROOT, "%06o", sum).getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(chk, 0, h, 148, 6);
    h[154] = 0;
    h[155] = ' ';
    out.write(h);
  }

  /** {prefix, name}: the name in the 100-byte field, the leading directories in the prefix. */
  static String[] split(String name) {
    if (name.isEmpty() || name.startsWith("/") || name.contains("..") || name.contains("\\")) {
      throw new IllegalArgumentException("Not a relative entry name: " + name);
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c < 0x20 || c > 0x7e) {
        throw new IllegalArgumentException("Entry names are printable ASCII: " + name);
      }
    }
    if (name.length() <= 100) {
      return new String[] {"", name};
    }
    for (int i = name.length() - 1; i > 0; i--) {
      if (name.charAt(i) == '/' && i <= 155 && name.length() - i - 1 <= 100) {
        return new String[] {name.substring(0, i), name.substring(i + 1)};
      }
    }
    throw new IllegalArgumentException("Entry name too long for ustar: " + name);
  }

  private static void put(byte[] h, int offset, int length, String value) {
    byte[] b = value.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(b, 0, h, offset, Math.min(b.length, length));
  }

  /** {@code length - 1} octal digits, zero padded, then NUL. */
  private static void octal(byte[] h, int offset, int length, long value) {
    String digits = Long.toOctalString(value);
    String padded = "0".repeat(Math.max(0, length - 1 - digits.length())) + digits;
    put(h, offset, length - 1, padded);
    h[offset + length - 1] = 0;
  }

  private void pad(long size) throws IOException {
    int rest = (int) (size % BLOCK);
    if (rest != 0) {
      out.write(new byte[BLOCK - rest]);
    }
  }

  /** Writes the end-of-archive blocks and finishes the gzip stream. */
  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    out.write(new byte[2 * BLOCK]);
    out.close();
  }
}
