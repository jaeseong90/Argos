/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.auth;

import io.argos.server.auth.Sensitive;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A {@code user_sessions} row to insert (login or refresh rotation). {@link #toString()} never
 * shows the refresh hash.
 *
 * @param id new session id (UUIDv4)
 * @param userId owner
 * @param familyId the login (equal to {@code id} for a login, the old row's family for a rotation)
 * @param refreshHash hex SHA-256 of the refresh token
 * @param issuedAt now
 * @param idleExpiresAt now + idle lifetime
 * @param absoluteExpiresAt login time + absolute lifetime
 * @param rotatedFrom the replaced row, or null for a login
 * @param userAgent client {@code User-Agent} (already cut), or null
 * @param ip client address literal, or null
 * @param kiosk whether this is a kiosk session
 */
public record NewSession(
    UUID id,
    UUID userId,
    UUID familyId,
    @Sensitive String refreshHash,
    Instant issuedAt,
    Instant idleExpiresAt,
    Instant absoluteExpiresAt,
    UUID rotatedFrom,
    String userAgent,
    String ip,
    boolean kiosk) {

  public NewSession {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(familyId, "familyId");
    Objects.requireNonNull(refreshHash, "refreshHash");
    Objects.requireNonNull(issuedAt, "issuedAt");
    Objects.requireNonNull(idleExpiresAt, "idleExpiresAt");
    Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
  }

  /** The end of the session as the client sees it: the earlier of both expiries. */
  public Instant expiresAt() {
    return idleExpiresAt.isBefore(absoluteExpiresAt) ? idleExpiresAt : absoluteExpiresAt;
  }

  @Override
  public String toString() {
    return "NewSession[id=" + id + ", userId=" + userId + ", familyId=" + familyId + "]";
  }
}
