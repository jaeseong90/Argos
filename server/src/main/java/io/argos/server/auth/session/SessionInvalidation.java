/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.storage.postgres.auth.AuthTransactions;
import io.argos.server.storage.postgres.auth.RevokedRows;
import io.argos.server.storage.postgres.auth.SessionRepository;
import io.argos.server.storage.postgres.auth.SessionRow;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The single exit of every revocation (16 §7.4): what was revoked is announced here, once per
 * session and after the commit: one {@link SessionRevokedEvent} (the cache drops the session, WO-22
 * closes its WebSockets) and one {@code SESSION_REVOKED} audit record with the reason. Code that
 * revokes inside a larger transaction collects the {@link RevokedRows} of {@link SessionRepository}
 * and calls {@link #announce} after it; the others call {@link #revokeSession} or {@link
 * #revokeUser}.
 */
@Component
public class SessionInvalidation {

  private final SessionRepository sessions;
  private final AuthTransactions transactions;
  private final ApplicationEventPublisher events;
  private final AuditService audit;
  private final Clock clock;

  public SessionInvalidation(
      SessionRepository sessions,
      AuthTransactions transactions,
      ApplicationEventPublisher events,
      AuditService audit,
      Clock clock) {
    this.sessions = sessions;
    this.transactions = transactions;
    this.events = events;
    this.audit = audit;
    this.clock = clock;
  }

  /** Revokes the session (the whole family) that the row {@code sid} belongs to. */
  public Optional<RevokedRows> revokeSession(UUID sid, RevocationReason reason, Actor actor) {
    Optional<RevokedRows> revoked =
        transactions.inTransaction(
            () ->
                sessions
                    .findById(sid)
                    .map(SessionRow::familyId)
                    .flatMap(
                        family -> sessions.revokeFamily(family, reason.code(), clock.instant())));
    revoked.ifPresent(r -> announce(List.of(r), reason, actor));
    return revoked;
  }

  /**
   * Revokes every live session of a user.
   *
   * @return the revoked sessions
   */
  public List<RevokedRows> revokeUser(UUID userId, RevocationReason reason, Actor actor) {
    List<RevokedRows> revoked =
        transactions.inTransaction(
            () -> sessions.revokeUserSessions(userId, reason.code(), clock.instant(), null, false));
    announce(revoked, reason, actor);
    return revoked;
  }

  /** Announces committed revocations: one event and one audit record per session. */
  public void announce(List<RevokedRows> revoked, RevocationReason reason, Actor actor) {
    for (RevokedRows r : new ArrayList<>(revoked)) {
      events.publishEvent(new SessionRevokedEvent(r.sid(), r.userId(), reason, r.sessionIds()));
      audit.record(
          AuditEvent.SESSION_REVOKED,
          actor,
          Target.session(r.sid()),
          AuditResult.success(reason.code()),
          null,
          Map.of("user_id", r.userId().toString()));
    }
  }
}
