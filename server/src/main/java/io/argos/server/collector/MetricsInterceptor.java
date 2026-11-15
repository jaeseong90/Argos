/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Objects;

/**
 * Last interceptor of the collector (12 §3.1 #5): the latency and final status of every data call
 * that passed authentication and the session check ({@code argos_collector_calls_seconds{method,
 * code}}). Received items are counted by the service, which sees the decoded batch ({@code
 * argos_collector_received_total{type}}); calls refused earlier are counted by the refusing
 * interceptor ({@code argos_collector_rejected_total{reason}}).
 */
public final class MetricsInterceptor implements ServerInterceptor {

  private final CollectorMetrics metrics;

  public MetricsInterceptor(CollectorMetrics metrics) {
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (CollectorCalls.isSession(call)) {
      return next.startCall(call, headers);
    }
    long started = System.nanoTime();
    String method = call.getMethodDescriptor().getBareMethodName();
    ServerCall<ReqT, RespT> timed =
        new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
          @Override
          public void close(Status status, Metadata trailers) {
            metrics.call(
                method == null ? "unknown" : method, status.getCode(), System.nanoTime() - started);
            super.close(status, trailers);
          }
        };
    return next.startCall(timed, headers);
  }
}
