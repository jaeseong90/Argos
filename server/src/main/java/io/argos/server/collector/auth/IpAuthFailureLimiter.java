/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.argos.server.audit.AgentAuditEvents;
import io.argos.server.collector.CollectorCalls;
import io.argos.server.collector.CollectorMetrics;
import io.argos.server.collector.limits.SlidingWindowCounter;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * First interceptor of the collector (12 §3.1 #1, 16 §2.5, SEC-05): an address with {@code
 * argos.collector.auth-fail.per-ip-per-minute} (20) authentication failures within a minute is
 * blocked for {@code block-minutes} (10). While blocked, every call from it, the Hello included, is
 * refused at once, without any token lookup, with {@code RESOURCE_EXHAUSTED}, the trailer {@code
 * argos-retry-after-ms} = the remaining block time and {@code argos-reject-reason} {@code
 * auth_blocked} ({@code argos_collector_auth_blocked_total}). ADR-0010 C-156: the block is a rate
 * limit, not a token verdict; an agent behind the same NAT address with a valid token therefore
 * waits for the block to end instead of taking the 5-minute token back-off and its "token rejected"
 * WARN (C-140). 16 §2.5's {@code UNAUTHENTICATED} is to be corrected.
 *
 * <p>Failures are reported by {@link TokenAuthInterceptor} ({@link #recordFailure}). The state is
 * in memory only (16 §2.5: a restart clears it) and bounded ({@value #MAX_ADDRESSES} addresses).
 * Time comes from the injected {@link Clock}, so tests move it.
 *
 * <p>This interceptor also puts the caller's address and the transport's TLS state into the {@code
 * Context} ({@link CollectorCalls#PEER}, {@link CollectorCalls#PLAINTEXT}).
 */
public final class IpAuthFailureLimiter implements ServerInterceptor {

  private static final Logger LOG = LoggerFactory.getLogger(IpAuthFailureLimiter.class);

  /** Upper bound of addresses tracked. */
  static final long MAX_ADDRESSES = 100_000;

  /** {@code argos_collector_rejected_total{reason}} and trailer reason of a blocked call. */
  public static final String AUTH_BLOCKED = "auth_blocked";

  private final Duration block;
  private final Clock clock;
  private final CollectorMetrics metrics;
  private final SlidingWindowCounter<String> failures;
  private final Cache<String, Long> blockedUntil;
  private volatile AgentAuditEvents audit = AgentAuditEvents.NONE;

  /**
   * @param failuresPerMinute failures within a minute that block an address
   * @param block how long an address stays blocked
   * @param clock the time source
   * @param metrics the collector meters
   */
  public IpAuthFailureLimiter(
      int failuresPerMinute, Duration block, Clock clock, CollectorMetrics metrics) {
    this.block = Objects.requireNonNull(block, "block");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.failures =
        new SlidingWindowCounter<>(
            failuresPerMinute, Duration.ofMinutes(1), MAX_ADDRESSES, Duration.ofMinutes(2));
    this.blockedUntil = Caffeine.newBuilder().maximumSize(MAX_ADDRESSES).build();
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String peer = CollectorCalls.peerOf(call);
    long remaining = blockedForMs(peer);
    if (remaining > 0) {
      metrics.authBlocked();
      metrics.rejected(AUTH_BLOCKED);
      return CollectorCalls.reject(
          call,
          Status.RESOURCE_EXHAUSTED.withDescription(
              "Too many authentication failures from this address; retry later"),
          CollectorCalls.trailers(AUTH_BLOCKED, remaining));
    }
    Context context =
        Context.current()
            .withValue(CollectorCalls.PEER, peer)
            .withValue(CollectorCalls.PLAINTEXT, CollectorCalls.plaintext(call));
    return Contexts.interceptCall(context, call, headers, next);
  }

  /**
   * Where the agent audit events go ({@code AGENT_AUTH_FAILED}, {@code AGENT_AUTH_BLOCKED};
   * ADR-0010 C-158 (1)); {@link AgentAuditEvents#NONE} until the audit service hands itself over.
   */
  public void auditEvents(AgentAuditEvents events) {
    this.audit = events == null ? AgentAuditEvents.NONE : events;
  }

  /** Records one authentication failure of {@code peer}; blocks it at the threshold. */
  public void recordFailure(String peer) {
    recordFailure(peer, null, null);
  }

  /**
   * Records one authentication failure of {@code peer} and its audit event {@code
   * AGENT_AUTH_FAILED} (when {@code reason} is given); blocks the address at the threshold ({@code
   * AGENT_AUTH_BLOCKED}). The audit calls never block (they queue).
   *
   * @param tokenPrefix the token's display prefix, or null
   * @param reason the failure reason code, or null to record no {@code AGENT_AUTH_FAILED}
   */
  public void recordFailure(String peer, String tokenPrefix, String reason) {
    long now = clock.millis();
    if (reason != null) {
      audit.authFailed(peer, tokenPrefix, reason);
    }
    if (failures.record(peer, now)) {
      blockedUntil.put(peer, now + block.toMillis());
      failures.reset(peer);
      audit.authBlocked(peer, block.toMinutes());
      LOG.warn(
          "Blocking agent connections from {} for {} min after repeated authentication failures"
              + " (16 §2.5)",
          peer,
          block.toMinutes());
    }
  }

  /** Whether {@code peer} is blocked now. */
  public boolean isBlocked(String peer) {
    return blockedForMs(peer) > 0;
  }

  private long blockedForMs(String peer) {
    Long until = blockedUntil.getIfPresent(peer);
    if (until == null) {
      return 0;
    }
    long remaining = until - clock.millis();
    if (remaining <= 0) {
      blockedUntil.invalidate(peer);
      return 0;
    }
    return remaining;
  }
}
