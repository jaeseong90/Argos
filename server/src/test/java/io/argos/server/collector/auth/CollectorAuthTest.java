/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import io.argos.proto.v1.RejectReason;
import io.argos.server.collector.CollectorCalls;
import io.argos.server.collector.CollectorMetrics;
import io.argos.server.collector.api.TokenHash;
import io.argos.server.collector.api.TokenRecord;
import io.argos.server.collector.api.TokenStatus;
import io.argos.server.collector.api.TokenStoreUnavailableException;
import io.argos.server.collector.api.TokenVerdict;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The address block (16 §2.5, SEC-05), the token cache (12 §7.1) and the token check of the
 * interceptor (16 §2.4, SEC-01, SEC-02), with an injectable clock and ticker (no wall-clock waits,
 * C-146 (2)).
 */
@Tag("req:SEC-05")
@Tag("req:SEC-02")
class CollectorAuthTest {

  private final MutableClock clock = new MutableClock();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final CollectorMetrics metrics = new CollectorMetrics(meters);

  @Test
  @DisplayName(
      "T-SEC-15 an address with 20 authentication failures within a minute is blocked 10 minutes")
  @Tag("req:SEC-15")
  void blockAfterTwentyFailures() {
    IpAuthFailureLimiter limiter =
        new IpAuthFailureLimiter(20, Duration.ofMinutes(10), clock, metrics);
    for (int i = 0; i < 19; i++) {
      limiter.recordFailure("10.0.0.1");
      clock.advance(Duration.ofMillis(100));
    }
    assertThat(limiter.isBlocked("10.0.0.1")).as("19 failures").isFalse();
    limiter.recordFailure("10.0.0.1");
    assertThat(limiter.isBlocked("10.0.0.1")).as("20th failure").isTrue();
    assertThat(limiter.isBlocked("10.0.0.2")).as("another address").isFalse();
    clock.advance(Duration.ofMinutes(10).minusMillis(1));
    assertThat(limiter.isBlocked("10.0.0.1")).isTrue();
    clock.advance(Duration.ofMillis(1));
    assertThat(limiter.isBlocked("10.0.0.1")).as("released after 10 minutes").isFalse();
    limiter.recordFailure("10.0.0.1");
    assertThat(limiter.isBlocked("10.0.0.1")).as("the count starts again").isFalse();
  }

  /** A server call that records how it was closed. */
  static final class RecordingCall extends ServerCall<byte[], byte[]> {
    private final Attributes attributes;
    private final MethodDescriptor<byte[], byte[]> method;
    Status status;
    Metadata trailers;

    RecordingCall(String address, String fullMethod) {
      this.attributes =
          Attributes.newBuilder()
              .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InetSocketAddress(address, 40_000))
              .build();
      MethodDescriptor.Marshaller<byte[]> bytes =
          new MethodDescriptor.Marshaller<>() {
            @Override
            public InputStream stream(byte[] value) {
              return new ByteArrayInputStream(value);
            }

            @Override
            public byte[] parse(InputStream stream) {
              return new byte[0];
            }
          };
      this.method =
          MethodDescriptor.<byte[], byte[]>newBuilder()
              .setType(MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(fullMethod)
              .setRequestMarshaller(bytes)
              .setResponseMarshaller(bytes)
              .build();
    }

    @Override
    public void request(int numMessages) {}

    @Override
    public void sendHeaders(Metadata headers) {}

    @Override
    public void sendMessage(byte[] message) {}

    @Override
    public void close(Status s, Metadata t) {
      status = s;
      trailers = t;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public MethodDescriptor<byte[], byte[]> getMethodDescriptor() {
      return method;
    }

    @Override
    public Attributes getAttributes() {
      return attributes;
    }
  }

  @Test
  @Tag("req:SEC-15")
  @Tag("req:C-156")
  @DisplayName(
      "T-SEC-15 C-156 a call from a blocked address is RESOURCE_EXHAUSTED with argos-retry-after-ms"
          + " = the remaining block time and reason auth_blocked, before any token lookup; other"
          + " addresses and the address after the block reach the token check")
  void blockedCallIsResourceExhausted() {
    IpAuthFailureLimiter limiter =
        new IpAuthFailureLimiter(20, Duration.ofMinutes(10), clock, metrics);
    for (int i = 0; i < 20; i++) {
      limiter.recordFailure("10.0.0.9");
    }
    clock.advance(Duration.ofMinutes(3));
    AtomicInteger lookups = new AtomicInteger();
    TokenAuthInterceptor auth =
        new TokenAuthInterceptor(
            new TokenCache(
                h -> {
                  lookups.incrementAndGet();
                  return Optional.empty();
                }),
            limiter,
            clock,
            metrics);
    AtomicInteger reachedService = new AtomicInteger();
    ServerCallHandler<byte[], byte[]> service =
        (call, headers) -> {
          reachedService.incrementAndGet();
          return new ServerCall.Listener<>() {};
        };
    ServerCallHandler<byte[], byte[]> chain =
        (call, headers) -> auth.interceptCall(call, headers, service);
    Metadata headers = new Metadata();
    headers.put(CollectorCalls.AUTHORIZATION, "Bearer " + AgentTokens.generate());

    RecordingCall blocked = new RecordingCall("10.0.0.9", "argos.v1.AgentService/SendTransactions");
    limiter.interceptCall(blocked, headers, chain);
    assertThat(blocked.status.getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    assertThat(blocked.trailers.get(CollectorCalls.RETRY_AFTER_MS))
        .as("10 minutes block, 3 passed")
        .isEqualTo(Long.toString(Duration.ofMinutes(7).toMillis()));
    assertThat(blocked.trailers.get(CollectorCalls.REJECT_REASON)).isEqualTo("auth_blocked");
    assertThat(lookups).as("no token lookup").hasValue(0);
    assertThat(reachedService).hasValue(0);
    assertThat(
            meters.get("argos.collector.rejected").tag("reason", "auth_blocked").counter().count())
        .isEqualTo(1);

    RecordingCall session = new RecordingCall("10.0.0.9", CollectorCalls.SESSION_METHOD);
    limiter.interceptCall(session, headers, chain);
    assertThat(session.status.getCode())
        .as("the Hello stream too")
        .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    assertThat(lookups).hasValue(0);

    RecordingCall other = new RecordingCall("10.0.0.10", "argos.v1.AgentService/SendTransactions");
    limiter.interceptCall(other, headers, chain);
    assertThat(lookups).as("another address reaches the token check").hasValue(1);
    assertThat(other.status.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);

    clock.advance(Duration.ofMinutes(7));
    RecordingCall released =
        new RecordingCall("10.0.0.9", "argos.v1.AgentService/SendTransactions");
    Metadata fresh = new Metadata();
    // Another unknown token: the cache remembers the first one, which would hide the lookup.
    fresh.put(CollectorCalls.AUTHORIZATION, "Bearer " + AgentTokens.generate());
    limiter.interceptCall(released, fresh, chain);
    assertThat(lookups).as("released after 10 minutes").hasValue(2);
    assertThat(released.status.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
  }

  @Test
  @DisplayName("T-SEC-15 failures spread over more than a minute do not block (sliding window)")
  @Tag("req:SEC-15")
  void slidingWindow() {
    IpAuthFailureLimiter limiter =
        new IpAuthFailureLimiter(20, Duration.ofMinutes(10), clock, metrics);
    for (int i = 0; i < 100; i++) {
      limiter.recordFailure("10.0.0.3");
      clock.advance(Duration.ofMillis(3_200));
    }
    assertThat(limiter.isBlocked("10.0.0.3")).isFalse();
  }

  private static TokenRecord token(
      TokenStatus status, Instant expires, Instant grace, Instant revoked) {
    return new TokenRecord(
        UUID.randomUUID(),
        3,
        "order-api",
        "prod",
        "argos_AbCdEfGh",
        status,
        expires,
        grace,
        revoked);
  }

  @Test
  @DisplayName(
      "12 §7.1 the token cache loads a hash once per TTL, caches unknown hashes, and invalidates")
  void cache() {
    AtomicInteger loads = new AtomicInteger();
    Map<TokenHash, TokenRecord> rows = new HashMap<>();
    AtomicLong ticks = new AtomicLong();
    Ticker ticker = ticks::get;
    TokenCache cache =
        new TokenCache(
            h -> {
              loads.incrementAndGet();
              return Optional.ofNullable(rows.get(h));
            },
            Duration.ofSeconds(60),
            10_000,
            ticker);
    String text = AgentTokens.generate();
    TokenHash hash = TokenHash.of(text);
    assertThat(cache.find(hash)).isEmpty();
    rows.put(hash, token(TokenStatus.ACTIVE, null, null, null));
    assertThat(cache.find(hash)).as("an unknown hash stays cached for the TTL").isEmpty();
    assertThat(loads).hasValue(1);
    cache.invalidate(hash);
    assertThat(cache.find(hash)).isPresent();
    assertThat(cache.find(hash)).isPresent();
    assertThat(loads).hasValue(2);
    ticks.addAndGet(Duration.ofSeconds(61).toNanos());
    assertThat(cache.find(hash)).isPresent();
    assertThat(loads).as("reloaded after 60 s").hasValue(3);
  }

  @Test
  @DisplayName(
      "12 §3.9 a database failure on a miss is TokenStoreUnavailableException and is not cached")
  void storeDown() {
    AtomicInteger calls = new AtomicInteger();
    TokenCache cache =
        new TokenCache(
            h -> {
              if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("connection refused");
              }
              return Optional.empty();
            });
    TokenHash hash = TokenHash.of(AgentTokens.generate());
    assertThatThrownBy(() -> cache.find(hash)).isInstanceOf(TokenStoreUnavailableException.class);
    assertThat(cache.find(hash)).isEmpty();
  }

  @Test
  @DisplayName(
      "16 §2.4 the token check: shape, hash lookup, ACTIVE / GRACE before grace_until / not expired")
  void verdicts() {
    Map<TokenHash, TokenRecord> rows = new HashMap<>();
    TokenAuthInterceptor auth =
        new TokenAuthInterceptor(
            new TokenCache(h -> Optional.ofNullable(rows.get(h))),
            new IpAuthFailureLimiter(20, Duration.ofMinutes(10), clock, metrics),
            clock,
            metrics);
    String active = AgentTokens.generate();
    String expired = AgentTokens.generate();
    String revoked = AgentTokens.generate();
    String grace = AgentTokens.generate();
    String graceOver = AgentTokens.generate();
    Instant now = clock.instant();
    rows.put(TokenHash.of(active), token(TokenStatus.ACTIVE, now.plusSeconds(60), null, null));
    rows.put(TokenHash.of(expired), token(TokenStatus.ACTIVE, now.minusSeconds(1), null, null));
    rows.put(TokenHash.of(revoked), token(TokenStatus.REVOKED, null, null, now.minusSeconds(5)));
    rows.put(TokenHash.of(grace), token(TokenStatus.GRACE, null, now.plusSeconds(60), null));
    rows.put(TokenHash.of(graceOver), token(TokenStatus.GRACE, null, now.minusSeconds(60), null));

    assertThat(auth.authenticate("Bearer " + active).accepted()).isTrue();
    assertThat(auth.authenticate("bearer " + active).accepted()).as("scheme case").isTrue();
    assertThat(auth.authenticate("Bearer " + grace).accepted()).isTrue();
    assertThat(auth.authenticate("Bearer " + expired).verdict()).isEqualTo(TokenVerdict.EXPIRED);
    assertThat(auth.authenticate("Bearer " + graceOver).verdict()).isEqualTo(TokenVerdict.EXPIRED);
    assertThat(auth.authenticate("Bearer " + revoked).verdict()).isEqualTo(TokenVerdict.REVOKED);
    assertThat(auth.authenticate("Bearer " + AgentTokens.generate()).verdict())
        .isEqualTo(TokenVerdict.UNKNOWN);
    assertThat(auth.authenticate(null).verdict()).isEqualTo(TokenVerdict.UNKNOWN);
    assertThat(auth.authenticate("Basic " + active).verdict()).isEqualTo(TokenVerdict.UNKNOWN);
    assertThat(auth.authenticate("Bearer argos_short").verdict()).isEqualTo(TokenVerdict.UNKNOWN);
    AuthResult malformed = auth.authenticate("Bearer not-a-token");
    assertThat(malformed.tokenPrefix()).isEqualTo("argos_****");
    assertThat(auth.authenticate("Bearer " + expired).tokenPrefix())
        .isEqualTo(expired.substring(0, 14))
        .doesNotContain(expired.substring(14));
  }

  @Test
  @DisplayName("T-SEC-02a a refused data call carries the RejectReason name, never the token")
  @Tag("req:SEC-01")
  void refusalTrailers() {
    AuthResult revoked =
        new AuthResult(AuthResult.Outcome.REJECTED, TokenVerdict.REVOKED, null, "argos_AbCdEfGh");
    Metadata trailers = TokenAuthInterceptor.trailers(revoked);
    assertThat(trailers.get(CollectorCalls.REJECT_REASON))
        .isEqualTo(RejectReason.TOKEN_REVOKED.name());
    assertThat(TokenAuthInterceptor.status(revoked).getDescription())
        .isEqualTo("Agent token rejected");
    AuthResult down =
        new AuthResult(
            AuthResult.Outcome.UNAVAILABLE, TokenVerdict.UNKNOWN, null, "argos_AbCdEfGh");
    assertThat(TokenAuthInterceptor.trailers(down).get(CollectorCalls.RETRY_AFTER_MS))
        .isEqualTo("30000");
  }
}
