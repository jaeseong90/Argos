/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import io.argos.server.collector.auth.AuthResult;
import io.argos.server.session.AgentSession;
import io.argos.server.session.SessionRegistry;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Third interceptor of the collector (12 §3.1 #3, 11 §2, §3): a data call names its session with
 * {@code argos-instance-id} and {@code argos-session-id}; it proceeds only when that instance's
 * live session has that id and was opened with the call's token. The {@code Session} call passes.
 *
 * <ul>
 *   <li>no or unparsable ids, no live session of the instance, or another session id: {@code
 *       FAILED_PRECONDITION} with description {@code SESSION_REQUIRED} (the agent opens a session
 *       and resends the batch with the same seq, 11 §3);
 *   <li>the session belongs to another token: {@code PERMISSION_DENIED} (SEC-01);
 *   <li>the collector is shutting down: {@code UNAVAILABLE} (12 §3.9).
 * </ul>
 *
 * The session goes into the {@code Context} ({@link CollectorCalls#SESSION}).
 */
public final class SessionInterceptor implements ServerInterceptor {

  private final SessionRegistry registry;
  private final CollectorState state;
  private final CollectorMetrics metrics;

  public SessionInterceptor(
      SessionRegistry registry, CollectorState state, CollectorMetrics metrics) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.state = Objects.requireNonNull(state, "state");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (CollectorCalls.isSession(call)) {
      return next.startCall(call, headers);
    }
    if (state.shuttingDown()) {
      metrics.rejected("server_shutting_down");
      return CollectorCalls.reject(
          call,
          Status.UNAVAILABLE.withDescription("The collector is shutting down"),
          CollectorCalls.trailers(null, CollectorState.SHUTDOWN_CLOSE_RETRY_AFTER_S * 1000L));
    }
    AuthResult auth = CollectorCalls.AUTH.get();
    Optional<AgentSession> session = session(headers);
    if (session.isEmpty()) {
      metrics.rejected("session_required");
      return CollectorCalls.reject(
          call,
          Status.FAILED_PRECONDITION.withDescription(CollectorCalls.SESSION_REQUIRED),
          new Metadata());
    }
    if (auth == null
        || !auth.accepted()
        || !auth.token().tokenId().equals(session.get().tokenId())) {
      metrics.rejected("session_token_mismatch");
      return CollectorCalls.reject(
          call,
          Status.PERMISSION_DENIED.withDescription("The session belongs to another agent token"),
          new Metadata());
    }
    Context context = Context.current().withValue(CollectorCalls.SESSION, session.get());
    return Contexts.interceptCall(context, call, headers, next);
  }

  /** The live session named by the call's metadata, when its ids match. */
  private Optional<AgentSession> session(Metadata headers) {
    String instance = headers.get(CollectorCalls.INSTANCE_ID);
    String sessionId = headers.get(CollectorCalls.SESSION_ID);
    if (instance == null || sessionId == null) {
      return Optional.empty();
    }
    int instanceId;
    UUID id;
    try {
      instanceId = Integer.parseUnsignedInt(instance.strip());
      id = UUID.fromString(sessionId.strip());
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    return registry.find(instanceId).filter(s -> s.sessionId().equals(id));
  }
}
