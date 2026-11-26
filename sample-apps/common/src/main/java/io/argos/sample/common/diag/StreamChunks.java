/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.diag;

import io.argos.sample.common.catalog.ShopCatalog;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Body of GET /api/stream (ADR-0010 C-162 (3)): {@link ShopCatalog#STREAM_CHUNKS} lines {@code
 * chunk N}, each flushed, 20 ms apart. The apps return it as Spring's {@code
 * StreamingResponseBody}: the stream is opened on the request thread before {@code startAsync}, the
 * writes and flushes run on the MVC async executor (F-A34: the first flush ends the transaction as
 * a streaming response).
 */
public final class StreamChunks {

  /** Pause between two chunks. */
  static final long PAUSE_MS = 20;

  private StreamChunks() {}

  /** Writes and flushes every chunk to {@code out}. */
  public static void write(OutputStream out) throws IOException {
    for (int i = 1; i <= ShopCatalog.STREAM_CHUNKS; i++) {
      if (i > 1) {
        try {
          Thread.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException("stream interrupted before chunk " + i);
        }
      }
      out.write(("chunk " + i + "\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
  }
}
