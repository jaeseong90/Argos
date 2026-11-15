/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import com.google.protobuf.Message;
import io.argos.proto.v1.Ack;
import io.grpc.Metadata;
import io.grpc.Status;

/**
 * The unary data RPCs of 11 §3 as the {@link Sender} sees them ({@code GrpcTransport} on the
 * channel; tests use a fake). {@link #send} never blocks on the network: it starts the call and
 * returns; the outcome arrives once through the callback, on any thread.
 */
interface BatchTransport {
  /** The outcome of one RPC. */
  interface Callback {
    /** {@code OK} with the server's acknowledgement. */
    void acknowledged(Ack ack);

    /** Any other status, with the trailers ({@code argos-retry-after-ms}, 11 §10). */
    void failed(Status status, Metadata trailers);
  }

  /**
   * Starts the RPC of {@code kind} with {@code batch} (header set), gzip-compressed when {@code
   * gzip}, with a deadline of {@code deadlineMillis}. A failure to start is reported through the
   * callback.
   */
  void send(BatchKind kind, Message batch, boolean gzip, long deadlineMillis, Callback callback);
}
