/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.storage.postgres.auth.AuthTransactions;
import io.argos.server.storage.postgres.auth.RevokedRows;
import io.argos.server.storage.postgres.auth.SessionRepository;
import io.argos.server.storage.postgres.auth.SessionRepository.LiveSession;
import io.argos.server.storage.postgres.auth.SessionRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A user's own sessions (16 §7.10 "자기 계정: 세션 목록", ADR-0010 C-136 (3); WO-26): the list of the live
 * sessions and the revocation of one of them. A session is identified by its family id (the id of
 * the login's first row, UUIDv4), which stays the same across refresh rotations; tokens and refresh
 * hashes never leave the server.
 */
@Component
public class OwnSessions {

  private final SessionRepository sessions;
  private final AuthTransactions transactions;
  private final SessionInvalidation invalidation;
  private final Clock clock;

  public OwnSessions(
      SessionRepository sessions,
      AuthTransactions transactions,
      SessionInvalidation invalidation,
      Clock clock) {
    this.sessions = sessions;
    this.transactions = transactions;
    this.invalidation = invalidation;
    this.clock = clock;
  }

  /**
   * One of the caller's sessions.
   *
   * @param session the live session
   * @param current whether it is the session of this request
   */
  public record Own(LiveSession session, boolean current) {}

  /** The caller's live sessions, most recently used first, the current one flagged. */
  public List<Own> list(ArgosPrincipal principal) {
    UUID current = currentFamily(principal).orElse(null);
    return sessions.liveSessions(principal.userId(), clock.instant()).stream()
        .map(s -> new Own(s, s.familyId().equals(current)))
        .toList();
  }

  /**
   * Revokes one of the caller's live sessions (reason {@code logout}); revoking the current one
   * ends this session like a logout (its cookies stop working).
   *
   * @throws ApiException 404 {@code not_found} for a session that is not one of the caller's live
   *     sessions (another user's session is not revealed)
   */
  public void revoke(ArgosPrincipal principal, UUID sessionId, Actor actor) {
    Instant now = clock.instant();
    boolean own =
        sessions.liveSessions(principal.userId(), now).stream()
            .anyMatch(s -> s.familyId().equals(sessionId));
    if (!own) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    Optional<RevokedRows> revoked =
        transactions.inTransaction(
            () -> sessions.revokeFamily(sessionId, RevocationReason.LOGOUT.code(), now));
    revoked.ifPresent(r -> invalidation.announce(List.of(r), RevocationReason.LOGOUT, actor));
  }

  private Optional<UUID> currentFamily(ArgosPrincipal principal) {
    return sessions.findById(principal.sid()).map(SessionRow::familyId);
  }
}
