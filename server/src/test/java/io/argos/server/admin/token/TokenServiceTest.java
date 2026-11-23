/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorBody;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.collector.api.TokenHash;
import io.argos.server.collector.api.TokenLookup;
import io.argos.server.collector.api.TokenRecord;
import io.argos.server.collector.api.TokenStatus;
import io.argos.server.collector.api.TokenVerdict;
import io.argos.server.session.SessionRegistry;
import io.argos.server.storage.postgres.token.AgentTokenStore;
import io.argos.server.testing.auth.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * T-SV-040 (unit): the token service over an in-memory store with the same rules as the PostgreSQL
 * one ({@code AgentTokenStore}; the SQL itself is proven by {@code TokenLifecycleIT}): token
 * format, hash-only storage, the per-app limit, rotation with both tokens valid during the grace,
 * revocation and the expiry sweep with an injected clock, each closing the token's sessions after
 * its cache entry is dropped, and the audit records without the token.
 */
@Tag("req:SEC-01")
@Tag("req:SEC-02")
@Tag("req:SEC-03")
@Tag("req:SEC-04")
class TokenServiceTest {

  private static final Pattern FORMAT = Pattern.compile("^argos_[A-Za-z0-9_-]{43}$");
  private static final Pattern ANY_TOKEN = Pattern.compile("argos_[A-Za-z0-9_-]{40,}");
  private static final int APP = 7;

  private final MutableClock clock = new MutableClock();
  private final MemoryStore store = new MemoryStore();
  private final List<Recorded> audits = new ArrayList<>();
  private final AuditService audit =
      (event, actor, target, result, before, after) ->
          audits.add(new Recorded(event, actor, target, result, copy(before), copy(after)));
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
  private final Actor admin =
      new Actor(Actor.Type.USER, UUID.randomUUID().toString(), "admin", "127.0.0.1", null);

  record Recorded(
      AuditEvent event,
      Actor actor,
      Target target,
      AuditResult result,
      @Nullable Map<String, Object> before,
      @Nullable Map<String, Object> after) {}

  private static @Nullable Map<String, Object> copy(@Nullable Map<String, ?> map) {
    return map == null ? null : new LinkedHashMap<>(map);
  }

  @Test
  @DisplayName(
      "T-SV-040a issue: argos_ + 43 base64url characters, the store gets only its SHA-256 and the"
          + " 14-character prefix, default expiry 365 days, TOKEN_ISSUED without the token")
  void issueFormatAndHashOnly() {
    IssuedToken t = service.issue(admin, APP, null);
    assertThat(t.token()).matches(FORMAT);
    assertThat(t.prefix()).isEqualTo(t.token().substring(0, 14));
    assertThat(t.toString()).doesNotContain(t.token());
    assertThat(ANY_TOKEN.matcher(t.toString()).find()).isFalse();

    AgentTokenStore.StoredToken row = store.rows.get(t.tokenId());
    assertThat(row.hash()).isEqualTo(TokenHash.of(t.token()));
    assertThat(row.prefix()).isEqualTo(t.prefix());
    assertThat(row.status()).isEqualTo(TokenStatus.ACTIVE);
    assertThat(row.issuedBy()).isEqualTo(UUID.fromString(admin.id()));
    assertThat(row.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(365)));
    assertThat(t.expiresAt()).isEqualTo(row.expiresAt());
    assertThat(t.tokenId().version()).as("UUIDv4").isEqualTo(4);

    Recorded issued = only(AuditEvent.TOKEN_ISSUED);
    assertThat(issued.target()).isEqualTo(new Target("token", t.tokenId().toString(), t.prefix()));
    assertThat(issued.result().outcome()).isEqualTo(AuditResult.Outcome.SUCCESS);
    assertThat(String.valueOf(issued.after())).doesNotContain(t.token());
    assertThat(ANY_TOKEN.matcher(String.valueOf(issued)).find()).isFalse();

    IssuedToken other = service.issue(admin, APP, 30);
    assertThat(other.token()).isNotEqualTo(t.token());
    assertThat(other.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
  }

  @Test
  @DisplayName("T-SV-040b expires_in_days 1 to 3650 (400 out of range); an unknown app is 404")
  void issueValidation() {
    assertThat(code(() -> service.issue(admin, APP, 0))).isEqualTo(ErrorCode.INVALID_REQUEST);
    assertThat(code(() -> service.issue(admin, APP, 3651))).isEqualTo(ErrorCode.INVALID_REQUEST);
    assertThat(service.issue(admin, APP, 3650).expiresAt())
        .isEqualTo(clock.instant().plus(Duration.ofDays(3650)));
    assertThat(code(() -> service.issue(admin, 999, null))).isEqualTo(ErrorCode.NOT_FOUND);
  }

  @Test
  @DisplayName(
      "T-SV-040c at most 5 active tokens per app: the 6th is 409 token_limit (audited as"
          + " rejected); a revoked or expired token frees a place")
  void limitOfFive() {
    List<IssuedToken> issued = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      issued.add(service.issue(admin, APP, 10));
    }
    ApiException e = catchApi(() -> service.issue(admin, APP, null));
    assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE);
    assertThat(e.details()).contains(new ErrorBody.Detail("app_id", "token_limit"));
    assertThat(audits.get(audits.size() - 1).result())
        .isEqualTo(AuditResult.rejected("token_limit"));
    assertThat(store.rows).hasSize(5);

    service.revoke(admin, issued.get(0).tokenId(), "leaked");
    IssuedToken sixth = service.issue(admin, APP, 10);
    assertThat(sixth.token()).matches(FORMAT);

    clock.advance(Duration.ofDays(11));
    assertThat(service.issue(admin, APP, null).token())
        .as("tokens past expires_at no longer count, even before the sweep")
        .matches(FORMAT);
  }

  @Test
  @DisplayName(
      "T-SV-040d rotate: a new token with the old lifetime, the old one GRACE until now + 7 days;"
          + " both verdicts ACCEPTED during the grace, the old one EXPIRED after it; the old"
          + " cache entry is dropped")
  void rotation() {
    IssuedToken old = service.issue(admin, APP, 90);
    clock.advance(Duration.ofDays(10));
    IssuedToken fresh = service.rotate(admin, old.tokenId(), TokenService.DEFAULT_GRACE_DAYS);
    Instant now = clock.instant();

    assertThat(fresh.token()).matches(FORMAT).isNotEqualTo(old.token());
    assertThat(fresh.rotatedTokenId()).isEqualTo(old.tokenId());
    assertThat(fresh.graceUntil()).isEqualTo(now.plus(Duration.ofDays(7)));
    assertThat(fresh.expiresAt()).isEqualTo(now.plus(Duration.ofDays(90)));
    AgentTokenStore.StoredToken oldRow = store.rows.get(old.tokenId());
    assertThat(oldRow.status()).isEqualTo(TokenStatus.GRACE);
    assertThat(oldRow.graceUntil()).isEqualTo(fresh.graceUntil());
    assertThat(invalidated).contains(TokenHash.of(old.token()));

    assertThat(store.record(old.tokenId()).verdict(now)).isEqualTo(TokenVerdict.ACCEPTED);
    assertThat(store.record(fresh.tokenId()).verdict(now)).isEqualTo(TokenVerdict.ACCEPTED);
    assertThat(service.find(old.tokenId()).orElseThrow().state()).isEqualTo(TokenState.ROTATING);

    Instant afterGrace = now.plus(Duration.ofDays(7));
    assertThat(store.record(old.tokenId()).verdict(afterGrace)).isEqualTo(TokenVerdict.EXPIRED);
    assertThat(store.record(fresh.tokenId()).verdict(afterGrace)).isEqualTo(TokenVerdict.ACCEPTED);

    assertThat(only(AuditEvent.TOKEN_ROTATED).target().id()).isEqualTo(old.tokenId().toString());
    assertThat(audits.stream().filter(r -> r.event() == AuditEvent.TOKEN_ISSUED)).hasSize(2);

    assertThat(code(() -> service.rotate(admin, old.tokenId(), 7)))
        .as("a rotating token cannot be rotated again")
        .isEqualTo(ErrorCode.INVALID_STATE);
    assertThat(code(() -> service.rotate(admin, fresh.tokenId(), 0)))
        .isEqualTo(ErrorCode.INVALID_REQUEST);
    assertThat(code(() -> service.rotate(admin, fresh.tokenId(), 31)))
        .isEqualTo(ErrorCode.INVALID_REQUEST);
    assertThat(code(() -> service.rotate(admin, UUID.randomUUID(), 7)))
        .isEqualTo(ErrorCode.NOT_FOUND);
  }

  @Test
  @DisplayName(
      "T-SV-040e revoke: REVOKED with reason and revoker, cache entry dropped before the"
          + " sessions are closed, TOKEN_REVOKED records only the reason length; a second"
          + " revocation is 409, a blank reason 400")
  void revocation() {
    IssuedToken t = service.issue(admin, APP, null);
    when(sessions.closeByToken(t.tokenId())).thenReturn(2);
    assertThat(code(() -> service.revoke(admin, t.tokenId(), "  ")))
        .isEqualTo(ErrorCode.INVALID_REQUEST);
    assertThat(code(() -> service.revoke(admin, t.tokenId(), "x".repeat(513))))
        .isEqualTo(ErrorCode.INVALID_REQUEST);
    verify(sessions, never()).closeByToken(any());

    service.revoke(admin, t.tokenId(), "laptop stolen, contact jane@example.test");
    AgentTokenStore.StoredToken row = store.rows.get(t.tokenId());
    assertThat(row.status()).isEqualTo(TokenStatus.REVOKED);
    assertThat(row.revokedAt()).isEqualTo(clock.instant());
    assertThat(row.revokedBy()).isEqualTo(UUID.fromString(admin.id()));
    assertThat(store.reasons.get(t.tokenId()))
        .isEqualTo("laptop stolen, contact jane@example.test");
    assertThat(invalidated).containsExactly(TokenHash.of(t.token()));
    verify(sessions).closeByToken(t.tokenId());
    assertThat(store.record(t.tokenId()).verdict(clock.instant())).isEqualTo(TokenVerdict.REVOKED);

    Recorded revoked = only(AuditEvent.TOKEN_REVOKED);
    assertThat(revoked.after())
        .containsEntry("reason_length", 40)
        .containsEntry("sessions_closed", 2);
    assertThat(String.valueOf(revoked)).doesNotContain("jane@example.test");

    assertThat(code(() -> service.revoke(admin, t.tokenId(), "again")))
        .isEqualTo(ErrorCode.INVALID_STATE);
    assertThat(code(() -> service.revoke(admin, UUID.randomUUID(), "x")))
        .isEqualTo(ErrorCode.NOT_FOUND);
  }

  @Test
  @DisplayName(
      "T-SV-040f revokeAllForApp revokes every unrevoked token of the app and closes their"
          + " sessions; tokens of other apps stay")
  void revokeAll() {
    IssuedToken a = service.issue(admin, APP, null);
    IssuedToken b = service.issue(admin, APP, null);
    service.rotate(admin, b.tokenId(), 7);
    IssuedToken other = service.issue(admin, 8, null);
    assertThat(service.revokeAllForApp(Actor.system(), APP, "app_deleted")).isEqualTo(3);
    assertThat(store.rows.values().stream().filter(r -> r.appId() == APP))
        .allMatch(r -> r.status() == TokenStatus.REVOKED);
    assertThat(store.rows.get(other.tokenId()).status()).isEqualTo(TokenStatus.ACTIVE);
    verify(sessions).closeByToken(a.tokenId());
    verify(sessions).closeByToken(b.tokenId());
    verify(sessions, never()).closeByToken(other.tokenId());
    assertThat(service.revokeAllForApp(Actor.system(), APP, "app_deleted")).isZero();
  }

  @Test
  @DisplayName(
      "T-SV-040g expiry job (injected clock): a token past expires_at and a GRACE token past"
          + " grace_until become EXPIRED, their cache entries are dropped and sessions closed,"
          + " TOKEN_EXPIRED by the system; valid tokens stay")
  void expirySweep() {
    IssuedToken shortLived = service.issue(admin, APP, 1);
    IssuedToken rotated = service.issue(admin, APP, 365);
    IssuedToken fresh = service.rotate(admin, rotated.tokenId(), 3);
    invalidated.clear();
    assertThat(service.expireDue()).isZero();

    clock.advance(Duration.ofDays(1));
    assertThat(service.expireDue()).isEqualTo(1);
    assertThat(store.rows.get(shortLived.tokenId()).status()).isEqualTo(TokenStatus.EXPIRED);
    assertThat(store.rows.get(rotated.tokenId()).status()).isEqualTo(TokenStatus.GRACE);
    verify(sessions).closeByToken(shortLived.tokenId());

    clock.advance(Duration.ofDays(2));
    assertThat(service.expireDue()).isEqualTo(1);
    assertThat(store.rows.get(rotated.tokenId()).status()).isEqualTo(TokenStatus.EXPIRED);
    assertThat(store.rows.get(fresh.tokenId()).status()).isEqualTo(TokenStatus.ACTIVE);
    verify(sessions).closeByToken(rotated.tokenId());
    assertThat(invalidated)
        .containsExactly(TokenHash.of(shortLived.token()), TokenHash.of(rotated.token()));
    List<Recorded> expired =
        audits.stream().filter(r -> r.event() == AuditEvent.TOKEN_EXPIRED).toList();
    assertThat(expired).hasSize(2).allMatch(r -> r.actor().type() == Actor.Type.SYSTEM);
    assertThat(expired.get(1).result().reason()).isEqualTo("grace_ended");
    assertThat(service.expireDue()).isZero();
  }

  @Test
  @DisplayName(
      "T-SV-040h list: newest first with the API state and live sessions; no hash or token in"
          + " the view")
  void list() {
    IssuedToken a = service.issue(admin, APP, null);
    clock.advance(Duration.ofSeconds(1));
    IssuedToken b = service.issue(admin, APP, null);
    when(sessions.countByToken(b.tokenId())).thenReturn(3);
    service.revoke(admin, a.tokenId(), "x");
    List<TokenView> views = service.list(APP);
    assertThat(views).extracting(TokenView::tokenId).containsExactly(b.tokenId(), a.tokenId());
    assertThat(views.get(0).state()).isEqualTo(TokenState.ACTIVE);
    assertThat(views.get(0).instancesUsing()).isEqualTo(3);
    assertThat(views.get(1).state()).isEqualTo(TokenState.REVOKED);
    assertThat(views.get(1).instancesUsing()).isZero();
    assertThat(String.valueOf(views)).doesNotContain(a.token()).doesNotContain(b.token());
    assertThat(code(() -> service.list(999))).isEqualTo(ErrorCode.NOT_FOUND);
  }

  @Test
  @DisplayName(
      "T-SV-040i offline service (argos-admin): issues without a token cache or session registry")
  void offline() {
    TokenService cli = TokenService.offline(store, audit, clock);
    IssuedToken t =
        cli.issue(
            new Actor(Actor.Type.SYSTEM, "argos-compose", "argos-compose", null, null), APP, null);
    assertThat(t.token()).matches(FORMAT);
    assertThat(store.rows.get(t.tokenId()).issuedBy()).isNull();
    assertThat(only(AuditEvent.TOKEN_ISSUED).actor().id()).isEqualTo("argos-compose");
  }

  // --- helpers ----------------------------------------------------------------------------------

  private Recorded only(AuditEvent event) {
    List<Recorded> found = audits.stream().filter(r -> r.event() == event).toList();
    assertThat(found).as(event.name()).hasSize(1);
    return found.get(0);
  }

  private static ErrorCode code(Runnable call) {
    return catchApi(call).errorCode();
  }

  private static ApiException catchApi(Runnable call) {
    try {
      call.run();
    } catch (ApiException e) {
      return e;
    }
    throw new AssertionError("expected an ApiException");
  }

  /**
   * The rules of {@code AgentTokenStore} in memory: apps 7 and 8 exist; the limit counts tokens
   * whose verdict is ACCEPTED at {@code now}.
   */
  static final class MemoryStore extends AgentTokenStore {
    final Map<UUID, StoredToken> rows = new LinkedHashMap<>();
    final Map<UUID, String> reasons = new LinkedHashMap<>();

    MemoryStore() {
      super(new DriverManagerDataSource());
    }

    TokenRecord record(UUID id) {
      StoredToken t = rows.get(id);
      return new TokenRecord(
          t.id(),
          t.appId(),
          "app",
          "prod",
          t.prefix(),
          t.status(),
          t.expiresAt(),
          t.graceUntil(),
          t.revokedAt());
    }

    private int active(int appId, Instant now) {
      return (int)
          rows.values().stream()
              .filter(t -> t.appId() == appId)
              .filter(t -> record(t.id()).verdict(now) == TokenVerdict.ACCEPTED)
              .count();
    }

    private static StoredToken row(NewToken t) {
      return new StoredToken(
          t.id(),
          t.appId(),
          t.hash(),
          t.prefix(),
          TokenStatus.ACTIVE,
          t.issuedBy(),
          t.issuedBy() == null ? null : "admin",
          t.issuedAt(),
          t.expiresAt(),
          null,
          null,
          null,
          null);
    }

    @Override
    public boolean appExists(int appId) {
      return appId == APP || appId == 8;
    }

    @Override
    public WriteResult insert(NewToken token, int maxActive, Instant now) {
      if (!appExists(token.appId())) {
        return new WriteResult(Outcome.NOT_FOUND, 0);
      }
      int active = active(token.appId(), now);
      if (active >= maxActive) {
        return new WriteResult(Outcome.LIMIT_REACHED, active);
      }
      rows.put(token.id(), row(token));
      return new WriteResult(Outcome.DONE, active);
    }

    @Override
    public WriteResult rotate(
        UUID oldId, NewToken replacement, Instant graceUntil, int maxActive, Instant now) {
      StoredToken old = rows.get(oldId);
      if (old == null || old.appId() != replacement.appId()) {
        return new WriteResult(Outcome.NOT_FOUND, 0);
      }
      if (old.status() != TokenStatus.ACTIVE
          || record(oldId).verdict(now) != TokenVerdict.ACCEPTED) {
        return new WriteResult(Outcome.NOT_ACTIVE, 0);
      }
      int active = active(old.appId(), now);
      if (active >= maxActive) {
        return new WriteResult(Outcome.LIMIT_REACHED, active);
      }
      rows.put(replacement.id(), row(replacement));
      rows.put(oldId, with(old, TokenStatus.GRACE, graceUntil, null, null));
      return new WriteResult(Outcome.DONE, active);
    }

    @Override
    public Optional<StoredToken> revoke(
        UUID id, @Nullable UUID revokedBy, @Nullable String reason, Instant now) {
      StoredToken t = rows.get(id);
      if (t == null || t.status() == TokenStatus.REVOKED) {
        return Optional.empty();
      }
      rows.put(id, with(t, TokenStatus.REVOKED, t.graceUntil(), now, revokedBy));
      reasons.put(id, reason);
      return Optional.of(t);
    }

    @Override
    public List<StoredToken> unrevokedOfApp(int appId) {
      return rows.values().stream()
          .filter(t -> t.appId() == appId && t.status() != TokenStatus.REVOKED)
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
          StoredToken expired = with(t, TokenStatus.EXPIRED, t.graceUntil(), null, null);
          rows.put(t.id(), expired);
          out.add(expired);
        }
      }
      return out;
    }

    @Override
    public Optional<StoredToken> find(UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public List<StoredToken> listByApp(int appId, int limit) {
      return rows.values().stream()
          .filter(t -> t.appId() == appId)
          .sorted((x, y) -> y.issuedAt().compareTo(x.issuedAt()))
          .limit(limit)
          .toList();
    }

    private static StoredToken with(
        StoredToken t,
        TokenStatus status,
        @Nullable Instant graceUntil,
        @Nullable Instant revokedAt,
        @Nullable UUID revokedBy) {
      return new StoredToken(
          t.id(),
          t.appId(),
          t.hash(),
          t.prefix(),
          status,
          t.issuedBy(),
          t.issuedByName(),
          t.issuedAt(),
          t.expiresAt(),
          graceUntil,
          revokedAt == null ? t.revokedAt() : revokedAt,
          revokedBy == null ? t.revokedBy() : revokedBy,
          t.lastUsedAt());
    }
  }
}
