/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditService;
import io.argos.server.collector.api.TokenHash;
import io.argos.server.collector.api.TokenLookup;
import io.argos.server.collector.api.TokenRecord;
import io.argos.server.collector.api.TokenStatus;
import io.argos.server.session.AgentSession;
import io.argos.server.session.SessionRegistry;
import io.argos.server.storage.postgres.token.AgentTokenStore;
import io.argos.server.testing.auth.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * T-SEC-05b (unit, WO-43, ADR-0010 C-238 (14)): the revocation watcher's decision over an in-memory
 * store: a token revoked or expired by another process (the command line, another server) loses its
 * cache entry and its live sessions without an audit record of this server; a recently revoked
 * token without a session here loses its cache entry only; authenticating tokens (active, rotated
 * within the grace) are left alone; a live token past its expiry runs the audited expiry sweep;
 * PostgreSQL failures are retried at the next run.
 */
@Tag("req:SEC-04")
@Tag("req:C-238")
class TokenRevocationWatcherTest {

  private static final int APP = 7;

  private final MutableClock clock = new MutableClock();
  private final Store store = new Store();
  private final List<AuditEvent> audits = new ArrayList<>();
  private final AuditService audit =
      (event, actor, target, result, before, after) -> audits.add(event);
  private final List<TokenHash> invalidated = new ArrayList<>();
  private final TokenLookup lookup =
      new TokenLookup() {
        @Override
        public Optional<TokenRecord> find(TokenHash hash) {
          return Optional.empty();
        }

        @Override
        public void invalidate(TokenHash hash) {
          invalidated.add(hash);
        }

        @Override
        public void invalidateAll() {}
      };
  private final SessionRegistry sessions = mock(SessionRegistry.class);
  private final TokenService service = new TokenService(store, audit, lookup, sessions, clock);
  private final AtomicLong nanos = new AtomicLong();
  private final TokenRevocationWatcher watcher = new TokenRevocationWatcher(service, nanos::get);

  /** Live sessions of the given tokens (one each). */
  private void live(UUID... tokenIds) {
    List<AgentSession> list = new ArrayList<>();
    for (UUID id : tokenIds) {
      AgentSession s = mock(AgentSession.class);
      when(s.tokenId()).thenReturn(id);
      list.add(s);
    }
    when(sessions.all()).thenReturn(list);
  }

  @Test
  @DisplayName(
      "T-SEC-05b a live session's token revoked outside the server: cache entry dropped, sessions"
          + " closed (TOKEN_REVOKED_CLOSE through closeByToken), no audit record; the query gets"
          + " the live token ids and the 5-minute window")
  void revokedElsewhere() {
    AgentTokenStore.StoredToken t = store.add(TokenStatus.REVOKED, null, null, clock.instant());
    AgentTokenStore.StoredToken other = store.add(TokenStatus.ACTIVE, null, null, null);
    live(t.id(), other.id());
    when(sessions.closeByToken(t.id())).thenReturn(2);

    TokenService.Sweep sweep = watcher.check();

    assertThat(sweep).isEqualTo(new TokenService.Sweep(2, 1, 2, 0));
    assertThat(invalidated).containsExactly(t.hash());
    verify(sessions).closeByToken(t.id());
    verify(sessions, never()).closeByToken(other.id());
    assertThat(audits).as("the revoking process audited it").isEmpty();
    assertThat(store.lastIds).containsExactlyInAnyOrder(t.id(), other.id());
    assertThat(store.lastRecent).isEqualTo(TokenRevocationWatcher.RECENT_REVOCATIONS);
    assertThat(TokenRevocationWatcher.RECENT_REVOCATIONS)
        .as("longer than the 60 s token cache")
        .isGreaterThan(Duration.ofSeconds(60));
    assertThat(TokenRevocationWatcher.PERIOD_MILLIS)
        .as("leaves room for the 5 s bar of T-SEC-02/05")
        .isLessThanOrEqualTo(2_000);
  }

  @Test
  @DisplayName(
      "T-SEC-05b a recently revoked token without a session on this server: cache entry dropped"
          + " (a new Hello reads the row), no session call; an EXPIRED row of another server's"
          + " sweep ends the live session the same way")
  void cacheOnlyAndExpiredElsewhere() {
    AgentTokenStore.StoredToken revoked =
        store.add(TokenStatus.REVOKED, null, null, clock.instant());
    AgentTokenStore.StoredToken expired =
        store.add(TokenStatus.EXPIRED, clock.instant().minusSeconds(1), null, null);
    live(expired.id());
    when(sessions.closeByToken(expired.id())).thenReturn(1);

    TokenService.Sweep sweep = watcher.check();

    assertThat(invalidated).containsExactlyInAnyOrder(revoked.hash(), expired.hash());
    verify(sessions, never()).closeByToken(revoked.id());
    verify(sessions).closeByToken(expired.id());
    assertThat(sweep.ended()).isEqualTo(1);
    assertThat(audits).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SEC-05b authenticating tokens are left alone: active, active with a future expiry, rotated"
          + " within its grace")
  void authenticatingLeftAlone() {
    AgentTokenStore.StoredToken active = store.add(TokenStatus.ACTIVE, null, null, null);
    AgentTokenStore.StoredToken expiring =
        store.add(TokenStatus.ACTIVE, clock.instant().plusSeconds(60), null, null);
    AgentTokenStore.StoredToken grace =
        store.add(TokenStatus.GRACE, null, clock.instant().plus(Duration.ofDays(7)), null);
    live(active.id(), expiring.id(), grace.id());

    TokenService.Sweep sweep = watcher.check();

    assertThat(sweep).isEqualTo(new TokenService.Sweep(3, 0, 0, 0));
    assertThat(invalidated).isEmpty();
    verify(sessions, never()).closeByToken(any());
    assertThat(audits).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SEC-05b a live token past its expiry or grace (still ACTIVE/GRACE in the database): the"
          + " expiry sweep runs now, with its TOKEN_EXPIRED records, and ends the sessions")
  void timeExpiredRunsSweep() {
    AgentTokenStore.StoredToken grace =
        store.add(TokenStatus.GRACE, null, clock.instant().plus(Duration.ofDays(1)), null);
    live(grace.id());
    when(sessions.closeByToken(grace.id())).thenReturn(1);
    assertThat(watcher.check().expired()).isZero();
    clock.advance(Duration.ofDays(1).plusSeconds(1));

    TokenService.Sweep sweep = watcher.check();

    assertThat(sweep.expired()).isEqualTo(1);
    assertThat(sweep.sessionsClosed()).isEqualTo(1);
    assertThat(store.rows.get(grace.id()).status()).isEqualTo(TokenStatus.EXPIRED);
    assertThat(audits).containsExactly(AuditEvent.TOKEN_EXPIRED);
    verify(sessions, times(1)).closeByToken(grace.id());
  }

  @Test
  @DisplayName(
      "T-SEC-05b a PostgreSQL failure: the run returns nothing, nothing is closed, the failure is"
          + " counted and the next successful run resets it; no session in this process is no"
          + " work")
  void failuresRetry() {
    live(UUID.randomUUID());
    store.fail = true;
    assertThat(watcher.check()).isNull();
    nanos.addAndGet(Duration.ofSeconds(2).toNanos());
    assertThat(watcher.check()).isNull();
    assertThat(watcher.failures()).isEqualTo(2);
    verify(sessions, never()).closeByToken(any());
    store.fail = false;
    assertThat(watcher.check()).isNotNull();
    assertThat(watcher.failures()).isZero();

    // The scheduled entry point never throws.
    store.fail = true;
    watcher.run();
    assertThat(watcher.failures()).isEqualTo(1);

    TokenService offline = TokenService.offline(store, audit, clock);
    assertThat(offline.endSessionsOfUnauthenticated(Duration.ofMinutes(5)))
        .isEqualTo(new TokenService.Sweep(0, 0, 0, 0));
  }

  /** The rows of {@code AgentTokenStore} in memory with the watcher's query rule. */
  static final class Store extends AgentTokenStore {
    final Map<UUID, StoredToken> rows = new LinkedHashMap<>();
    volatile boolean fail;
    @Nullable Set<UUID> lastIds;
    @Nullable Duration lastRecent;

    Store() {
      super(new DriverManagerDataSource());
    }

    StoredToken add(
        TokenStatus status,
        @Nullable Instant expiresAt,
        @Nullable Instant graceUntil,
        @Nullable Instant revokedAt) {
      UUID id = UUID.randomUUID();
      String token = "argos_" + id.toString().replace("-", "") + "0123456789a";
      StoredToken t =
          new StoredToken(
              id,
              APP,
              TokenHash.of(token),
              token.substring(0, 14),
              status,
              null,
              null,
              Instant.parse("2026-01-01T00:00:00Z"),
              expiresAt,
              graceUntil,
              revokedAt,
              null,
              null);
      rows.put(id, t);
      return t;
    }

    @Override
    public List<StoredToken> revocationCheck(Collection<UUID> ids, Duration recent) {
      if (fail) {
        throw new DataAccessResourceFailureException("down");
      }
      lastIds = Set.copyOf(ids);
      lastRecent = recent;
      return rows.values().stream()
          .filter(t -> ids.contains(t.id()) || t.revokedAt() != null)
          .toList();
    }

    @Override
    public List<StoredToken> expireDue(Instant now) {
      List<StoredToken> out = new ArrayList<>();
      for (StoredToken t : List.copyOf(rows.values())) {
        boolean due =
            (t.status() == TokenStatus.ACTIVE || t.status() == TokenStatus.GRACE)
                && ((t.expiresAt() != null && !now.isBefore(t.expiresAt()))
                    || (t.status() == TokenStatus.GRACE
                        && (t.graceUntil() == null || !now.isBefore(t.graceUntil()))));
        if (due) {
          StoredToken e =
              new StoredToken(
                  t.id(),
                  t.appId(),
                  t.hash(),
                  t.prefix(),
                  TokenStatus.EXPIRED,
                  null,
                  null,
                  t.issuedAt(),
                  t.expiresAt(),
                  t.graceUntil(),
                  null,
                  null,
                  null);
          rows.put(t.id(), e);
          out.add(e);
        }
      }
      return out;
    }
  }
}
