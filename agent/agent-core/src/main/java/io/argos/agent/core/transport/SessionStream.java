/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.ServerMessage;
import io.grpc.Metadata;
import io.grpc.Status;

/**
 * One {@code AgentService.Session} bidirectional stream (11 §3, §5) as the {@link SessionClient}
 * uses it: the gRPC call of {@code GrpcTransport}, a fake in tests. Every method is called on the
 * session's event loop; the listener is called there too.
 */
interface SessionStream {
  /** Opens a stream for {@code listener} (the call starts at once). */
  interface Opener {
    SessionStream open(Listener listener);

    /**
     * The session waits before its next attempt ({@code Backoff}, 10 §10.2): no connection, no
     * reconnects of the channel's own and no name resolution until the next {@link #open} ({@code
     * GrpcTransport} replaces its channel). Never blocks.
     */
    void idle();
  }

  /** Events of one stream. */
  interface Listener {
    /** A server message; the next one is requested after this returns. */
    void message(ServerMessage message);

    /** The stream can take more outbound messages without buffering ({@code isReady}). */
    void ready();

    /** The stream ended (server close, failure, or cancellation). */
    void closed(Status status, Metadata trailers);
  }

  /**
   * True when the transport accepts a message without buffering it (gRPC {@code isReady}, outbound
   * flow control, T-AG-071).
   */
  boolean isReady();

  void send(AgentMessage message);

  /** Ends the agent's side of the stream (the server may still answer). */
  void halfClose();

  /** Cancels the stream; {@code reason} is a constant (no collected data, no token). */
  void cancel(String reason);

  /**
   * The TLS protocol of the connection ({@code TLSv1.2}, {@code TLSv1.3}, as one of the constants
   * of {@link SessionClient}), {@code plaintext}, or {@code unknown} before the server's headers.
   */
  String security();
}
