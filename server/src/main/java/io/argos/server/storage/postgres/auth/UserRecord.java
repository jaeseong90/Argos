/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.auth;

import io.argos.server.auth.Role;
import io.argos.server.auth.Sensitive;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One {@code users} row with its {@code user_permissions} grants (13 §12.1, V002). {@link
 * #toString()} never shows the password hash.
 *
 * @param id {@code users.id} (UUIDv4)
 * @param username login name
 * @param passwordHash bcrypt hash ({@code $2a$12$...})
 * @param role {@code ADMIN}, {@code OPERATOR} or {@code VIEWER}
 * @param enabled whether the account may log in
 * @param mustChangePassword whether every API but the password change is refused (16 §7.9)
 * @param failedLogins consecutive failed logins since the last success or lockout
 * @param lockedUntil end of the current lockout, or null
 * @param lockoutLevel lockouts since the last successful login or unlock (V002)
 * @param kioskAllowed whether long kiosk sessions may be issued (16 §7.5)
 * @param kioskCidrs the address ranges kiosk sessions may come from ({@code network/prefix})
 * @param grants granted permissions ({@code user_permissions}), whatever the role
 * @param createdAt creation time
 * @param updatedAt last change
 */
public record UserRecord(
    UUID id,
    String username,
    @Sensitive String passwordHash,
    Role role,
    boolean enabled,
    boolean mustChangePassword,
    int failedLogins,
    Instant lockedUntil,
    int lockoutLevel,
    boolean kioskAllowed,
    List<String> kioskCidrs,
    Set<String> grants,
    Instant createdAt,
    Instant updatedAt) {

  public UserRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(role, "role");
    kioskCidrs = kioskCidrs == null ? List.of() : List.copyOf(kioskCidrs);
    grants = grants == null ? Set.of() : Set.copyOf(grants);
  }

  /**
   * The permissions in effect: {@code pii.view} applies to administrators only (16 §7.10, 14 §7.1),
   * so a grant left over from an earlier role has no effect.
   */
  public Set<String> effectivePermissions() {
    return role == Role.ADMIN ? grants : Set.of();
  }

  /** Whether the account is locked at {@code now}. */
  public boolean lockedAt(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  @Override
  public String toString() {
    return "UserRecord[id="
        + id
        + ", username="
        + username
        + ", role="
        + role
        + ", enabled="
        + enabled
        + ", mustChangePassword="
        + mustChangePassword
        + ", lockedUntil="
        + lockedUntil
        + "]";
  }
}
