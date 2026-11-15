/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.grpc;

import io.argos.server.collector.CollectorMetrics;
import io.argos.server.collector.limits.Violation;
import io.grpc.Metadata;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import java.util.Objects;

/**
 * Counts the message-size rejections that grpc-java makes itself, before any collector code sees
 * the call: a frame (compressed, or uncompressed) larger than {@code maxInboundMessageSize} is
 * refused by the transport's deframer with {@code RESOURCE_EXHAUSTED "gRPC message exceeds maximum
 * size ..."}. Without this tracer {@code argos_collector_rejected_total{reason="message_size"}}
 * would miss them (12 §3.3 class 1); decompressed overflows are counted by the handler ({@link
 * InboundMarshaller}).
 */
public final class TransportRejectionCounter extends ServerStreamTracer.Factory {

  /** The start of grpc-java's deframer description ({@code MessageDeframer.processHeader}). */
  static final String GRPC_TOO_LARGE = "gRPC message exceeds maximum size";

  private final CollectorMetrics metrics;

  public TransportRejectionCounter(CollectorMetrics metrics) {
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
    return new ServerStreamTracer() {
      @Override
      public void streamClosed(Status status) {
        if (isTransportTooLarge(status)) {
          metrics.rejected(Violation.MESSAGE_SIZE);
        }
      }
    };
  }

  /** Whether {@code status} is grpc-java's own message-size refusal. */
  static boolean isTransportTooLarge(Status status) {
    return status.getCode() == Status.Code.RESOURCE_EXHAUSTED
        && status.getDescription() != null
        && status.getDescription().startsWith(GRPC_TOO_LARGE);
  }
}
