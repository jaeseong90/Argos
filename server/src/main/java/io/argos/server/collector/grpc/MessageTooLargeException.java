/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.grpc;

import java.io.IOException;

/**
 * A message grew beyond {@code argos.collector.max-message-bytes} while it was read (decompressed),
 * thrown by {@link BoundedInputStream} as soon as the limit is crossed (12 §3.1, §3.3 class 1,
 * SEC-11). An {@link IOException} so that protobuf parsing stops with it.
 */
public final class MessageTooLargeException extends IOException {

  private static final long serialVersionUID = 1L;

  private final long limit;

  /**
   * @param limit the limit that was crossed, in bytes
   */
  public MessageTooLargeException(long limit) {
    super("Message larger than " + limit + " bytes after decompression");
    this.limit = limit;
  }

  /** The limit that was crossed, in bytes. */
  public long limit() {
    return limit;
  }
}
