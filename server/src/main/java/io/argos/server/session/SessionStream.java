/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.SessionClose;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.Objects;

/**
 * The server side of one {@code Session} stream (11 §3): every send is serialized on this object (a
 * gRPC call is not thread-safe; heartbeat acks, dictionary requests, the Hello timeout and closes
 * come from different threads, 12 §4.1 "동기화된 send").
 *
 * <p>States: open, half-closed by the agent (it sends nothing more but the session stays valid for
 * its last data calls, 11 §3 and the contract vectors' "a session stays valid after its stream
 * ends"), ended (completed or failed by the server, or cancelled by the agent or the transport).
 * Nothing is sent after the end.
 */
public final class SessionStream {

  private final ServerCallStreamObserver<ServerMessage> out;
  private boolean halfClosed;
  private boolean ended;

  public SessionStream(ServerCallStreamObserver<ServerMessage> out) {
    this.out = Objects.requireNonNull(out, "out");
  }

  /**
   * Sends {@code message}.
   *
   * @return {@code false} when the stream has ended (nothing sent)
   */
  public synchronized boolean send(ServerMessage message) {
    if (ended || out.isCancelled()) {
      return false;
    }
    try {
      out.onNext(message);
      return true;
    } catch (RuntimeException e) {
      ended = true;
      return false;
    }
  }

  /**
   * Sends an advisory message only when the transport is ready for it (a dictionary request may be
   * dropped; the server asks again, 11 §7 (3)), so that an agent that stops reading never makes the
   * server buffer.
   */
  public synchronized boolean sendIfReady(ServerMessage message) {
    if (ended || out.isCancelled() || !out.isReady()) {
      return false;
    }
    return send(message);
  }

  /**
   * Sends {@code close} (unless the agent has half-closed or the stream has ended) and ends the
   * stream with {@code status} and {@code trailers}.
   */
  public synchronized void close(SessionClose close, Status status, Metadata trailers) {
    if (ended) {
      return;
    }
    if (close != null && !halfClosed) {
      send(ServerMessage.newBuilder().setClose(close).build());
    }
    end(status, trailers);
  }

  /** Ends the stream with {@code status} (OK completes it). */
  public synchronized void end(Status status, Metadata trailers) {
    if (ended) {
      return;
    }
    ended = true;
    try {
      if (status.isOk()) {
        out.onCompleted();
      } else {
        out.onError(status.asRuntimeException(trailers == null ? new Metadata() : trailers));
      }
    } catch (RuntimeException e) {
      // The call ended meanwhile (cancelled by the transport): nothing left to do.
    }
  }

  /** The agent half-closed the stream. */
  public synchronized void markHalfClosed() {
    halfClosed = true;
  }

  /** The agent or the transport cancelled the call. */
  public synchronized void markCancelled() {
    ended = true;
  }

  /** Whether the stream has ended. */
  public synchronized boolean ended() {
    return ended || out.isCancelled();
  }

  /** Whether the agent half-closed the stream. */
  public synchronized boolean halfClosed() {
    return halfClosed;
  }
}
