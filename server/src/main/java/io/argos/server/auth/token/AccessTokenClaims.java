/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.token;

import io.argos.server.auth.Role;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The claims of an access token (16 §7.1). {@code role} and {@code perm} are hints only: the
 * request is authorised with the session's current user record (12 §7.2, DSN-12-10).
 *
 * @param subject {@code sub}: the user id
 * @param sid {@code sid}: the session row id
 * @param role {@code role}
 * @param permissions {@code perm}
 * @param issuedAt {@code iat}, epoch seconds
 * @param expiresAt {@code exp}, epoch seconds
 * @param tokenId {@code jti}
 */
public record AccessTokenClaims(
    UUID subject,
    UUID sid,
    Role role,
    Set<String> permissions,
    long issuedAt,
    long expiresAt,
    String tokenId) {

  public AccessTokenClaims {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(sid, "sid");
    Objects.requireNonNull(role, "role");
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    Objects.requireNonNull(tokenId, "tokenId");
  }
}
