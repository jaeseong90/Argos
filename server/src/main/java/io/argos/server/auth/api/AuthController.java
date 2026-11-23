/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.api;

import io.argos.server.api.Envelope;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.ObjectAccessPolicy;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.auth.session.LoginService;
import io.argos.server.auth.session.OwnSessions;
import io.argos.server.auth.session.PasswordChangeService;
import io.argos.server.auth.session.RefreshRotation;
import io.argos.server.auth.session.RevocationReason;
import io.argos.server.auth.session.SessionCookies;
import io.argos.server.auth.session.SessionInvalidation;
import io.argos.server.auth.session.SessionStateCache;
import io.argos.server.auth.token.SecureTokens;
import io.argos.server.storage.postgres.auth.SessionRepository;
import io.argos.server.storage.postgres.auth.SessionStateRow;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Sessions: {@code /api/v1/auth/*} (14 §4.1, 16 §7). */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final LoginService logins;
  private final RefreshRotation refreshes;
  private final PasswordChangeService passwords;
  private final SessionInvalidation invalidation;
  private final SessionStateCache states;
  private final SessionCookies cookies;
  private final AuditService audit;
  private final OwnSessions ownSessions;

  public AuthController(
      LoginService logins,
      RefreshRotation refreshes,
      PasswordChangeService passwords,
      SessionInvalidation invalidation,
      SessionStateCache states,
      SessionCookies cookies,
      AuditService audit,
      OwnSessions ownSessions) {
    this.logins = logins;
    this.refreshes = refreshes;
    this.passwords = passwords;
    this.invalidation = invalidation;
    this.states = states;
    this.cookies = cookies;
    this.audit = audit;
    this.ownSessions = ownSessions;
  }

  /**
   * The CSRF token: sets {@value SessionCookies#CSRF_COOKIE} and returns its value. A well-formed
   * token the browser already holds stays, so that a second tab does not break the first one's next
   * request; a login always replaces it.
   */
  @GetMapping("/csrf")
  @RequiresRole(Role.PUBLIC)
  public Envelope<AuthDtos.CsrfResponse> csrf(
      HttpServletRequest request, HttpServletResponse response) {
    String token =
        SessionCookies.read(request, SessionCookies.CSRF_COOKIE)
            .filter(SecureTokens::looksLikeToken)
            .orElseGet(SecureTokens::newToken);
    cookies.setCsrf(response, token);
    return Envelope.of(new AuthDtos.CsrfResponse(token));
  }

  /** Logs in: new session and CSRF cookies. */
  @PostMapping("/login")
  @RequiresRole(Role.PUBLIC)
  @ArgosAudit({
    AuditEvent.LOGIN_SUCCESS,
    AuditEvent.LOGIN_FAILED,
    AuditEvent.ACCOUNT_LOCKED,
    AuditEvent.KIOSK_SESSION_ISSUED,
    AuditEvent.SESSION_REVOKED
  })
  public Envelope<AuthDtos.LoginResponse> login(
      @RequestBody(required = false) AuthDtos.LoginRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    AuthDtos.LoginRequest credentials = body == null ? new AuthDtos.LoginRequest(null, null) : body;
    LoginService.Result result =
        logins.login(credentials.username(), credentials.password(), request, response);
    return Envelope.of(
        new AuthDtos.LoginResponse(
            AuthDtos.UserView.of(result.user()),
            AuthDtos.roles(result.user()),
            result.user().mustChangePassword(),
            AuthDtos.millis(result.sessionExpiresAt())));
  }

  /**
   * Rotates the refresh token: new access and refresh cookies. A kiosk session is audited when the
   * login issues it, not here.
   */
  @PostMapping("/refresh")
  @RequiresRole(Role.PUBLIC)
  @ArgosAudit({AuditEvent.SESSION_REUSE_DETECTED, AuditEvent.SESSION_REVOKED})
  public Envelope<AuthDtos.RefreshResponse> refresh(
      HttpServletRequest request, HttpServletResponse response) {
    return Envelope.of(
        new AuthDtos.RefreshResponse(AuthDtos.millis(refreshes.refresh(request, response))));
  }

  /** Ends the current session and deletes the cookies. */
  @PostMapping("/logout")
  @RequiresRole(Role.VIEWER)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @ArgosAudit({AuditEvent.LOGOUT, AuditEvent.SESSION_REVOKED})
  public void logout(HttpServletRequest request, HttpServletResponse response) {
    ArgosPrincipal principal = principal();
    Actor actor = Actor.user(principal, request);
    invalidation.revokeSession(principal.sid(), RevocationReason.LOGOUT, actor);
    audit.record(AuditEvent.LOGOUT, actor, Target.session(principal.sid()), AuditResult.success());
    cookies.clearAll(response);
  }

  /** The caller's own live sessions (16 §7.10, ADR-0010 C-136 (3)). */
  @GetMapping("/sessions")
  @RequiresRole(Role.VIEWER)
  @Operation(
      operationId = "auth_sessions_list",
      summary = "My sessions",
      description =
          "The caller's live sessions (logins that are neither revoked nor expired), most"
              + " recently used first, at most 100; current marks the session of this request."
              + " No token or refresh value is ever returned.")
  public Envelope<List<AuthDtos.OwnSessionView>> sessions() {
    List<OwnSessions.Own> own = ownSessions.list(principal());
    return Envelope.of(
        own.stream()
            .map(
                o ->
                    new AuthDtos.OwnSessionView(
                        o.session().familyId().toString(),
                        o.current(),
                        AuthDtos.millis(o.session().loginAt()),
                        AuthDtos.millis(o.session().lastSeenAt()),
                        AuthDtos.millis(o.session().expiresAt()),
                        o.session().userAgent(),
                        o.session().ip(),
                        o.session().kiosk()))
            .toList(),
        own.size() >= SessionRepository.MAX_LISTED_SESSIONS);
  }

  /** Ends one of the caller's own sessions (16 §7.10, ADR-0010 C-136 (3)). */
  @DeleteMapping("/sessions/{sessionId}")
  @RequiresRole(Role.VIEWER)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @ArgosAudit(AuditEvent.SESSION_REVOKED)
  @Operation(
      operationId = "auth_sessions_delete",
      summary = "End one of my sessions",
      description =
          "sessionId is a session_id of GET /auth/sessions (UUIDv4). The session ends at once:"
              + " its next request is 401 session_revoked and its WebSockets close within 1 s."
              + " Ending the current session works like a logout. 404 for a session that is not"
              + " one of the caller's live sessions.")
  public void endSession(@PathVariable("sessionId") String sessionId, HttpServletRequest request) {
    ArgosPrincipal principal = principal();
    UUID id = ObjectAccessPolicy.requireUuidV4(sessionId, "sessionId");
    ownSessions.revoke(principal, id, Actor.user(principal, request));
  }

  /** Who is logged in. */
  @GetMapping("/me")
  @RequiresRole(Role.VIEWER)
  public Envelope<AuthDtos.MeResponse> me() {
    ArgosPrincipal principal = principal();
    Optional<SessionStateRow> state = states.get(principal.sid());
    SessionStateRow row = state.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    List<String> roles = List.of(principal.role().extensionValue());
    List<String> permissions = principal.permissions().stream().sorted().toList();
    return Envelope.of(
        new AuthDtos.MeResponse(
            new AuthDtos.UserView(
                principal.userId().toString(), principal.username(), roles, permissions),
            roles,
            permissions,
            principal.mustChangePassword(),
            AuthDtos.millis(row.expiresAt()),
            row.kioskAllowed()));
  }

  /** Changes the caller's password; the caller's session is replaced (16 §7.4). */
  @PutMapping("/password")
  @RequiresRole(Role.VIEWER)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @ArgosAudit({AuditEvent.PASSWORD_CHANGED, AuditEvent.SESSION_REVOKED, AuditEvent.ACCOUNT_LOCKED})
  public void changePassword(
      @RequestBody(required = false) AuthDtos.PasswordChangeRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    AuthDtos.PasswordChangeRequest change =
        body == null ? new AuthDtos.PasswordChangeRequest(null, null) : body;
    passwords.change(principal(), change.current(), change.next(), request, response);
  }

  private static ArgosPrincipal principal() {
    return ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
  }
}
