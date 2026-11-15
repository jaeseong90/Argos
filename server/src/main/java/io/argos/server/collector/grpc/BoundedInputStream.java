/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.grpc;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Counts the bytes read through it and fails with {@link MessageTooLargeException} as soon as more
 * than {@code limit} bytes have been read (12 §3.1 "BoundedInputStream 래퍼가 해제 바이트를 세어 초과 시 즉시").
 * Wrapped around the gzip decompressor and around every message the collector parses, so that a
 * gzip bomb (18 §8: 1 MB that inflates to 1 GB) stops after at most {@code limit} plus one read
 * buffer of inflated bytes, whatever the transport does.
 *
 * <p>{@link #available()} reports at most what remains under the limit, so a parser never sizes a
 * buffer beyond it.
 */
public final class BoundedInputStream extends FilterInputStream {

  private final long limit;
  private long count;
  private long mark = -1;

  /**
   * @param in the stream to read
   * @param limit the largest number of bytes allowed
   */
  public BoundedInputStream(InputStream in, long limit) {
    super(in);
    if (limit < 0) {
      throw new IllegalArgumentException("limit < 0: " + limit);
    }
    this.limit = limit;
  }

  /** Bytes read so far. */
  public long count() {
    return count;
  }

  /** The limit in bytes. */
  public long limit() {
    return limit;
  }

  @Override
  public int read() throws IOException {
    int b = in.read();
    if (b >= 0) {
      add(1);
    }
    return b;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    // Never ask for more than one byte beyond the limit: crossing it is detected with at most one
    // extra byte read.
    long allowed = limit - count + 1;
    int n = in.read(b, off, (int) Math.max(1, Math.min(len, allowed)));
    if (n > 0) {
      add(n);
    }
    return n;
  }

  @Override
  public long skip(long n) throws IOException {
    long skipped = in.skip(Math.max(0, Math.min(n, limit - count + 1)));
    if (skipped > 0) {
      add(skipped);
    }
    return skipped;
  }

  @Override
  public int available() throws IOException {
    return (int) Math.max(0, Math.min(in.available(), limit - count));
  }

  @Override
  public synchronized void mark(int readlimit) {
    in.mark(readlimit);
    mark = count;
  }

  @Override
  public synchronized void reset() throws IOException {
    if (!in.markSupported()) {
      throw new IOException("mark/reset not supported");
    }
    if (mark < 0) {
      throw new IOException("mark not set");
    }
    in.reset();
    count = mark;
  }

  private void add(long n) throws MessageTooLargeException {
    count += n;
    if (count > limit) {
      throw new MessageTooLargeException(limit);
    }
  }
}
