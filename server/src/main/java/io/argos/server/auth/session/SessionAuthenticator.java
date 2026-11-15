/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Sensitive;
import io.argos.server.auth.token.AccessTokenClaims;
import io.argos.server.auth.token.AccessTokenService;
import io.argos.server.auth.token.TokenCheck;
import io.argos.server.storage.postgres.auth.SessionStateRow;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Decides what an access token is worth right now (16 §7.3 check point 1, 12 §7.2): the JWT is
 * verified, then the session record (through {@link SessionStateCache}) decides, and the role and
 * permissions come from the owner's current record; the JWT's claims are only a hint. The request
 * filter uses it, and so does the WebSocket handshake of WO-22 (16 §7.7).
 *
 * <ul>
 *   <li>a token that does not verify, or names a session that does not exist or belongs to someone
 *       else: {@code unauthenticated};
 *   <li>an expired token, a session past its idle or absolute expiry: {@code session_expired};
 *   <li>a revoked session, whatever the reason (a role or permission change and a deactivation
 *       among them), a disabled account: {@code session_revoked}. Such a session yields no
 *       principal and therefore no role: the 401 comes before any role check, so the first request
 *       after a demotion is 401 on every protected endpoint, and only a new login meets the new
 *       role (14 §2.2, §4.14, 16 §7.4).
 * </ul>
 */
@Component
public class SessionAuthenticator {

  /**
   * The outcome: a principal, or the 401 code.
   *
   * @param principal the authenticated user, else null
   * @param failure why not, else null
   */
  public record Outcome(ArgosPrincipal principal, ErrorCode failure) {

    /** Whether the token authenticates a user. */
    public boolean authenticated() {
      return principal != null;
    }

    static Outcome ok(ArgosPrincipal principal) {
      return new Outcome(principal, null);
    }

    static Outcome refused(ErrorCode code) {
      return new Outcome(null, code);
    }
  }

  private final AccessTokenService tokens;
  private final SessionStateCache states;
  private final Clock clock;

  public SessionAuthenticator(AccessTokenService tokens, SessionStateCache states, Clock clock) {
    this.tokens = tokens;
    this.states = states;
    this.clock = clock;
  }

  /** Checks an access token. */
  public Outcome authenticate(@Sensitive String accessToken) {
    TokenCheck check = tokens.verify(accessToken);
    if (!check.valid()) {
      return Outcome.refused(
          check.rejection() == TokenCheck.Rejection.EXPIRED
              ? ErrorCode.SESSION_EXPIRED
              : ErrorCode.UNAUTHENTICATED);
    }
    AccessTokenClaims claims = check.claims();
    Optional<SessionStateRow> found = states.get(claims.sid());
    if (found.isEmpty() || !found.get().userId().equals(claims.subject())) {
      return Outcome.refused(ErrorCode.UNAUTHENTICATED);
    }
    SessionStateRow state = found.get();
    if (state.revokedAt() != null) {
      return Outcome.refused(ErrorCode.SESSION_REVOKED);
    }
    if (!state.enabled()) {
      return Outcome.refused(ErrorCode.SESSION_REVOKED);
    }
    Instant now = clock.instant();
    if (!state.idleExpiresAt().isAfter(now) || !state.absoluteExpiresAt().isAfter(now)) {
      return Outcome.refused(ErrorCode.SESSION_EXPIRED);
    }
    return Outcome.ok(
        new ArgosPrincipal(
            state.userId(),
            state.username(),
            state.sid(),
            state.role(),
            state.permissions(),
            state.mustChangePassword()));
  }
}
