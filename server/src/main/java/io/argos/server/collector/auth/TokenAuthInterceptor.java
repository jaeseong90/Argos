/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.auth;

import io.argos.proto.v1.RejectReason;
import io.argos.server.collector.CollectorCalls;
import io.argos.server.collector.CollectorMetrics;
import io.argos.server.collector.api.TokenHash;
import io.argos.server.collector.api.TokenLookup;
import io.argos.server.collector.api.TokenRecord;
import io.argos.server.collector.api.TokenStoreUnavailableException;
import io.argos.server.collector.api.TokenVerdict;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Second interceptor of the collector (12 §3.1 #2, 16 §2.4, SEC-01, SEC-02): reads {@code
 * authorization: Bearer argos_<43>}, checks the 16 §2.1 shape, hashes the token (SHA-256) and looks
 * the hash up ({@link TokenLookup}: cache, then PostgreSQL). The 16 §2.4 verdict decides: status
 * {@code ACTIVE}, or {@code GRACE} before {@code grace_until}, and not expired.
 *
 * <ul>
 *   <li>Accepted: the {@link AuthResult} (token id, app, prefix) goes into the {@code Context}.
 *   <li>Rejected data call: {@code UNAUTHENTICATED} with the trailer {@code argos-reject-reason}
 *       {@code TOKEN_INVALID|TOKEN_EXPIRED|TOKEN_REVOKED} (the contract vectors); the failure
 *       counts for the address ({@link IpAuthFailureLimiter}), which also records the audit event
 *       {@code AGENT_AUTH_FAILED} (rate limited, queued: never blocks this thread).
 *   <li>Rejected {@code Session} call: the stream goes on to the session handler with the result,
 *       which answers the Hello with {@code ServerHello{rejected, TOKEN_*}} (11 §5 check 1); the
 *       failure counts here, once.
 *   <li>PostgreSQL unreadable on a cache miss: {@code UNAVAILABLE} with {@code
 *       argos-retry-after-ms} 30,000 (12 §3.9); not an authentication failure.
 * </ul>
 *
 * The internal reason ({@code token_unknown}, {@code token_expired}, {@code token_revoked}) appears
 * only in the server log with the token's display prefix, at most once a minute per reason, and in
 * {@code argos_collector_rejected_total{reason}}; the token itself appears nowhere (11 §11).
 */
public final class TokenAuthInterceptor implements ServerInterceptor {

  private static final Logger LOG = LoggerFactory.getLogger(TokenAuthInterceptor.class);

  /** 12 §3.9: retry advice of a call refused because the token store is down. */
  public static final long STORE_UNAVAILABLE_RETRY_MS = 30_000;

  /** Reason label of a call refused because the token store is down. */
  public static final String STORE_UNAVAILABLE = "token_store_unavailable";

  private static final long LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
  private static final String MASKED_PREFIX = "argos_****";

  private final TokenLookup lookup;
  private final IpAuthFailureLimiter limiter;
  private final Clock clock;
  private final CollectorMetrics metrics;
  private final Map<String, Long> lastLog = new ConcurrentHashMap<>();

  public TokenAuthInterceptor(
      TokenLookup lookup, IpAuthFailureLimiter limiter, Clock clock, CollectorMetrics metrics) {
    this.lookup = Objects.requireNonNull(lookup, "lookup");
    this.limiter = Objects.requireNonNull(limiter, "limiter");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    AuthResult auth = authenticate(headers.get(CollectorCalls.AUTHORIZATION));
    String peer = Objects.requireNonNullElse(CollectorCalls.PEER.get(), "unknown");
    if (!auth.accepted()) {
      if (auth.outcome() == AuthResult.Outcome.UNAVAILABLE) {
        metrics.rejected(STORE_UNAVAILABLE);
        logRejection(STORE_UNAVAILABLE, auth.tokenPrefix(), peer);
      } else {
        limiter.recordFailure(peer, auth.tokenPrefix(), auth.verdict().logReason());
        metrics.rejected(auth.verdict().logReason());
        logRejection(auth.verdict().logReason(), auth.tokenPrefix(), peer);
      }
      if (!CollectorCalls.isSession(call)) {
        return CollectorCalls.reject(call, status(auth), trailers(auth));
      }
    }
    Context context = Context.current().withValue(CollectorCalls.AUTH, auth);
    return Contexts.interceptCall(context, call, headers, next);
  }

  /**
   * The token check of an {@code authorization} header value. Never logs; the token exists only in
   * locals of this method.
   */
  AuthResult authenticate(String authorization) {
    String token = bearer(authorization);
    if (!TokenHash.isWellFormed(token)) {
      return new AuthResult(AuthResult.Outcome.REJECTED, TokenVerdict.UNKNOWN, null, MASKED_PREFIX);
    }
    String prefix = TokenHash.displayPrefix(token);
    Optional<TokenRecord> record;
    try {
      record = lookup.find(TokenHash.of(token));
    } catch (TokenStoreUnavailableException e) {
      return new AuthResult(AuthResult.Outcome.UNAVAILABLE, TokenVerdict.UNKNOWN, null, prefix);
    }
    if (record.isEmpty()) {
      return new AuthResult(AuthResult.Outcome.REJECTED, TokenVerdict.UNKNOWN, null, prefix);
    }
    TokenVerdict verdict = record.get().verdict(clock.instant());
    if (!verdict.accepted()) {
      return new AuthResult(AuthResult.Outcome.REJECTED, verdict, null, prefix);
    }
    return new AuthResult(AuthResult.Outcome.ACCEPTED, verdict, record.get(), prefix);
  }

  /** The token of {@code Bearer <token>} (scheme case-insensitive), or {@code null}. */
  static String bearer(String authorization) {
    if (authorization == null) {
      return null;
    }
    String value = authorization.strip();
    if (value.length() < 7 || !value.substring(0, 7).toLowerCase(Locale.ROOT).equals("bearer ")) {
      return null;
    }
    return value.substring(7).strip();
  }

  /** The status of a refused data call. */
  static Status status(AuthResult auth) {
    if (auth.outcome() == AuthResult.Outcome.UNAVAILABLE) {
      return Status.UNAVAILABLE.withDescription("The agent token store is unavailable; retry");
    }
    return Status.UNAUTHENTICATED.withDescription("Agent token rejected");
  }

  /** The trailers of a refused data call. */
  static Metadata trailers(AuthResult auth) {
    if (auth.outcome() == AuthResult.Outcome.UNAVAILABLE) {
      return CollectorCalls.trailers(null, STORE_UNAVAILABLE_RETRY_MS);
    }
    RejectReason reason = auth.verdict().rejectReason();
    return CollectorCalls.trailers(reason.name(), 0);
  }

  private void logRejection(String reason, String prefix, String peer) {
    long now = System.nanoTime();
    boolean[] log = {false};
    lastLog.compute(
        reason,
        (k, last) -> {
          if (last == null || now - last >= LOG_INTERVAL_NANOS) {
            log[0] = true;
            return now;
          }
          return last;
        });
    if (log[0]) {
      LOG.warn(
          "Agent token rejected: reason={} prefix={} peer={} (at most one line per reason and"
              + " minute; argos_collector_rejected_total counts all)",
          reason,
          prefix,
          peer);
    }
  }
}
