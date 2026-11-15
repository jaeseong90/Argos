/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.api;

import io.argos.server.api.Envelope;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.ObjectAccessPolicy;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.auth.users.UserAdministration;
import io.argos.server.storage.postgres.auth.UserRecord;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * User administration: {@code /api/v1/admin/users*} (14 §4.14, ADR-0010 C-109), administrators
 * only. Identifiers in paths are UUIDv4 (16 §7.11).
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiresRole(Role.ADMIN)
public class AdminUsersController {

  private final UserAdministration administration;

  public AdminUsersController(UserAdministration administration) {
    this.administration = administration;
  }

  /** The users, by name. */
  @GetMapping
  public Envelope<List<AuthDtos.AdminUserView>> list() {
    List<UserRecord> users = administration.list(UserAdministration.MAX_LISTED + 1);
    boolean truncated = users.size() > UserAdministration.MAX_LISTED;
    List<UserRecord> shown = truncated ? users.subList(0, UserAdministration.MAX_LISTED) : users;
    return Envelope.of(shown.stream().map(AuthDtos.AdminUserView::of).toList(), truncated);
  }

  /** Creates a user; a generated initial password is in this response once. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @ArgosAudit(AuditEvent.USER_CREATED)
  public Envelope<AuthDtos.CreateUserResponse> create(
      @RequestBody(required = false) AuthDtos.CreateUserRequest body, HttpServletRequest request) {
    AuthDtos.CreateUserRequest r =
        body == null ? new AuthDtos.CreateUserRequest(null, null, null, null) : body;
    UserAdministration.Created created =
        administration.create(
            r.username(),
            role(r.roles(), true),
            r.mustChangePassword(),
            r.password(),
            principal(),
            request);
    return Envelope.of(
        new AuthDtos.CreateUserResponse(
            AuthDtos.AdminUserView.of(created.user()), created.initialPassword()));
  }

  /** Changes the role, state, permissions or kiosk approval of a user. */
  @PatchMapping("/{id}")
  @ArgosAudit({
    AuditEvent.USER_UPDATED,
    AuditEvent.ROLE_CHANGED,
    AuditEvent.USER_DISABLED,
    AuditEvent.SESSION_REVOKED
  })
  public Envelope<AuthDtos.AdminUserView> patch(
      @PathVariable("id") String id,
      @RequestBody(required = false) AuthDtos.PatchUserRequest body,
      HttpServletRequest request) {
    UUID userId = userId(id);
    if (body == null
        || (body.roles() == null
            && body.enabled() == null
            && body.permissions() == null
            && body.kioskSession() == null)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The request changes nothing");
    }
    UserAdministration.Change change =
        new UserAdministration.Change(
            body.roles() == null ? null : role(body.roles(), false),
            body.enabled(),
            permissions(body.permissions()),
            kiosk(body.kioskSession()));
    return Envelope.of(
        AuthDtos.AdminUserView.of(administration.patch(userId, change, principal(), request)));
  }

  /** Ends every session of a user. */
  @PostMapping("/{id}/sessions/revoke")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @ArgosAudit(AuditEvent.SESSION_REVOKED)
  public void revokeSessions(@PathVariable("id") String id, HttpServletRequest request) {
    administration.revokeSessions(userId(id), principal(), request);
  }

  /** Sets a new password that the user must change at the next login. */
  @PostMapping("/{id}/password/reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @ArgosAudit({AuditEvent.PASSWORD_RESET, AuditEvent.SESSION_REVOKED})
  public void resetPassword(
      @PathVariable("id") String id,
      @RequestBody(required = false) AuthDtos.ResetPasswordRequest body,
      HttpServletRequest request) {
    administration.resetPassword(
        userId(id), body == null ? null : body.password(), principal(), request);
  }

  /** Lifts a lockout. */
  @PostMapping("/{id}/unlock")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @ArgosAudit(AuditEvent.ACCOUNT_UNLOCKED)
  public void unlock(@PathVariable("id") String id, HttpServletRequest request) {
    administration.unlock(userId(id), principal(), request);
  }

  private static ArgosPrincipal principal() {
    return ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
  }

  /** A UUIDv4 in canonical form (16 §7.11). */
  private static UUID userId(String text) {
    return ObjectAccessPolicy.requireUuidV4(text, "id");
  }

  /** {@code roles[]} holds exactly one role (14 §4.14, 16 §7.10). */
  private static Role role(List<String> roles, boolean required) {
    if (roles == null || roles.isEmpty()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST)
          .detail("roles", required ? "required" : "invalid");
    }
    if (roles.size() > 1) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("roles", "exactly_one");
    }
    try {
      return Role.ofUserRole(roles.get(0));
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("roles", "invalid");
    }
  }

  private static Set<String> permissions(List<String> values) {
    if (values == null) {
      return null;
    }
    Set<String> out = new LinkedHashSet<>();
    for (String value : values) {
      if (value == null || !ArgosPrincipal.KNOWN_PERMISSIONS.contains(value)) {
        throw new ApiException(ErrorCode.INVALID_REQUEST).detail("permissions", "invalid");
      }
      out.add(value);
    }
    return out;
  }

  private static UserAdministration.Kiosk kiosk(AuthDtos.KioskSession value) {
    if (value == null) {
      return null;
    }
    if (value.allowed() == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("kiosk_session.allowed", "required");
    }
    return new UserAdministration.Kiosk(value.allowed(), value.ipCidrs());
  }
}
