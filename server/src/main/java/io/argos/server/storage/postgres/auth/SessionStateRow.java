/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.auth;

import io.argos.server.auth.Role;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * What a request needs to know about its session (16 §7.3 check point 1): the session row and the
 * owner's <em>current</em> user record (role, permissions, enabled, must-change flag; 12 §7.2 "the
 * JWT claim is a hint").
 *
 * @param sid session id
 * @param familyId the login
 * @param userId owner
 * @param username owner's name
 * @param role owner's current role
 * @param enabled whether the owner's account is enabled
 * @param mustChangePassword whether the owner must change the password first
 * @param kioskAllowed whether the owner may get kiosk sessions
 * @param permissions owner's effective permissions ({@code pii.view} for administrators only)
 * @param revokedAt revocation time, or null
 * @param revokedReason revocation reason, or null
 * @param idleExpiresAt idle expiry of this row
 * @param absoluteExpiresAt absolute expiry of the login
 * @param kiosk whether this is a kiosk session
 */
public record SessionStateRow(
    UUID sid,
    UUID familyId,
    UUID userId,
    String username,
    Role role,
    boolean enabled,
    boolean mustChangePassword,
    boolean kioskAllowed,
    Set<String> permissions,
    Instant revokedAt,
    String revokedReason,
    Instant idleExpiresAt,
    Instant absoluteExpiresAt,
    boolean kiosk) {

  public SessionStateRow {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }

  /** The end of the session as the client sees it: the earlier of both expiries. */
  public Instant expiresAt() {
    return idleExpiresAt.isBefore(absoluteExpiresAt) ? idleExpiresAt : absoluteExpiresAt;
  }
}
