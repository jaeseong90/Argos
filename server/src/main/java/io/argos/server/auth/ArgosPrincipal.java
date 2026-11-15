/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The authenticated user of a request (12 §7.2, DSN-12-10): set by the access-token authentication
 * filter (WO-24) after the JWT and the session record were checked, read by {@link
 * RoleAuthorizationInterceptor}, controllers and services.
 *
 * <p>{@code role} and {@code permissions} are the session's <em>current</em> values from the user
 * record (the JWT claims are only a hint, 12 §7.2), so a role change takes effect at the next
 * request. Nothing secret is held here: no token, no password, no hash.
 *
 * @param userId {@code users.id}
 * @param username {@code users.username} (audit actor name)
 * @param sid the session id ({@code user_sessions.id}; JWT claim {@code sid})
 * @param role the user's role (never {@link Role#PUBLIC})
 * @param permissions extra permissions, e.g. {@value #PII_VIEW} (14 §7.1, ADR-0010 C-106 (6))
 * @param mustChangePassword every API but the password change, {@code /auth/me} and logout answers
 *     403 {@code password_change_required} while set (16 §7.9)
 */
public record ArgosPrincipal(
    UUID userId,
    String username,
    UUID sid,
    Role role,
    Set<String> permissions,
    boolean mustChangePassword) {

  /** Permission to see bind parameters in {@code full} mode (14 §7.3, 16 §7.10). */
  public static final String PII_VIEW = "pii.view";

  /** Every permission the server knows (1.0: {@value #PII_VIEW} only). */
  public static final Set<String> KNOWN_PERMISSIONS = Set.of(PII_VIEW);

  public ArgosPrincipal {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(sid, "sid");
    Objects.requireNonNull(role, "role");
    if (!role.assignable()) {
      throw new IllegalArgumentException("a principal has a user role, not " + role);
    }
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }

  /** Whether the principal has {@code permission}. */
  public boolean hasPermission(String permission) {
    return permissions.contains(permission);
  }

  /** Whether the principal's role includes {@code required}. */
  public boolean hasRole(Role required) {
    return role.includes(required);
  }

  /**
   * The principal of the current request: present when the access-token filter authenticated the
   * request (an {@link ArgosAuthentication} in the security context), empty for anonymous requests.
   */
  public static Optional<ArgosPrincipal> current() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof ArgosAuthentication argos && argos.isAuthenticated()) {
      return Optional.of(argos.getPrincipal());
    }
    return Optional.empty();
  }
}
