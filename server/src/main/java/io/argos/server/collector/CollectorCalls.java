/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import io.argos.proto.v1.AgentServiceGrpc;
import io.argos.server.collector.auth.AuthResult;
import io.argos.server.session.AgentSession;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Names shared by the collector's interceptors and handlers: the metadata and trailer keys of 11 §2
 * and §10, the gRPC {@code Context} keys the interceptor chain fills, and the rejection helpers.
 * Statuses and trailers never carry a token (11 §10, §11).
 */
public final class CollectorCalls {

  /** {@code argos.v1.AgentService/Session}. */
  public static final String SESSION_METHOD =
      AgentServiceGrpc.getSessionMethod().getFullMethodName();

  /** {@code authorization: Bearer argos_...} (11 §2). Never logged. */
  public static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  /** {@code argos-instance-id} of data calls (11 §2). */
  public static final Metadata.Key<String> INSTANCE_ID =
      Metadata.Key.of("argos-instance-id", Metadata.ASCII_STRING_MARSHALLER);

  /** {@code argos-session-id} of data calls (11 §2). */
  public static final Metadata.Key<String> SESSION_ID =
      Metadata.Key.of("argos-session-id", Metadata.ASCII_STRING_MARSHALLER);

  /** Trailer {@code argos-reject-reason} (11 §10). */
  public static final Metadata.Key<String> REJECT_REASON =
      Metadata.Key.of("argos-reject-reason", Metadata.ASCII_STRING_MARSHALLER);

  /** Trailer {@code argos-retry-after-ms} (11 §10). */
  public static final Metadata.Key<String> RETRY_AFTER_MS =
      Metadata.Key.of("argos-retry-after-ms", Metadata.ASCII_STRING_MARSHALLER);

  /** Description of the {@code FAILED_PRECONDITION} of a data call without a session (11 §3). */
  public static final String SESSION_REQUIRED = "SESSION_REQUIRED";

  /** The caller's address (host only, no port), set by {@code IpAuthFailureLimiter}. */
  public static final Context.Key<String> PEER = Context.key("argos-collector-peer");

  /** Whether the call has no TLS session (11 §5 check 3), set by {@code IpAuthFailureLimiter}. */
  public static final Context.Key<Boolean> PLAINTEXT = Context.key("argos-collector-plaintext");

  /** The token check, set by {@code TokenAuthInterceptor}. */
  public static final Context.Key<AuthResult> AUTH = Context.key("argos-collector-auth");

  /** The session of a data call, set by {@code SessionInterceptor}. */
  public static final Context.Key<AgentSession> SESSION = Context.key("argos-collector-session");

  private CollectorCalls() {}

  /** Whether {@code call} is the session stream. */
  public static boolean isSession(ServerCall<?, ?> call) {
    return SESSION_METHOD.equals(call.getMethodDescriptor().getFullMethodName());
  }

  /** The caller's address without port; {@code "unknown"} when the transport does not say. */
  public static String peerOf(ServerCall<?, ?> call) {
    SocketAddress address = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
    if (address instanceof InetSocketAddress inet) {
      return inet.getAddress() != null ? inet.getAddress().getHostAddress() : inet.getHostString();
    }
    return address == null ? "unknown" : address.toString();
  }

  /** Whether {@code call} arrived without TLS (no SSL session on the transport). */
  public static boolean plaintext(ServerCall<?, ?> call) {
    return call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION) == null;
  }

  /**
   * Trailers of a rejection: {@code argos-reject-reason} when {@code reason} is given, {@code
   * argos-retry-after-ms} when {@code retryAfterMs} is positive.
   */
  public static Metadata trailers(String reason, long retryAfterMs) {
    Metadata trailers = new Metadata();
    if (reason != null && !reason.isEmpty()) {
      trailers.put(REJECT_REASON, reason);
    }
    if (retryAfterMs > 0) {
      trailers.put(RETRY_AFTER_MS, Long.toString(retryAfterMs));
    }
    return trailers;
  }

  /** {@code status} with the rejection trailers, as an exception for a stream observer. */
  public static StatusRuntimeException error(Status status, String reason, long retryAfterMs) {
    return status.asRuntimeException(trailers(reason, retryAfterMs));
  }

  /** Closes {@code call} with {@code status} and {@code trailers}; the listener ignores input. */
  public static <ReqT, RespT> ServerCall.Listener<ReqT> reject(
      ServerCall<ReqT, RespT> call, Status status, Metadata trailers) {
    call.close(status, trailers);
    return new ServerCall.Listener<>() {};
  }
}
