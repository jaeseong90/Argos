/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@code user_sessions} row (16 §7.3, V002) without its refresh hash.
 *
 * @param id session id ({@code sid} of the access token)
 * @param userId owner
 * @param familyId the login this row belongs to (the id of its first row)
 * @param issuedAt creation of this row (login or rotation)
 * @param lastSeenAt last refresh
 * @param idleExpiresAt idle expiry
 * @param absoluteExpiresAt absolute expiry (kept across rotations)
 * @param rotatedAt when the refresh token of this row was rotated, or null
 * @param rotatedFrom the row this one replaced, or null
 * @param revokedAt revocation time, or null
 * @param revokedReason revocation reason, or null
 * @param userAgent client {@code User-Agent} (cut to 256 characters)
 * @param ip client address
 * @param kiosk whether this is a kiosk session
 */
public record SessionRow(
    UUID id,
    UUID userId,
    UUID familyId,
    Instant issuedAt,
    Instant lastSeenAt,
    Instant idleExpiresAt,
    Instant absoluteExpiresAt,
    Instant rotatedAt,
    UUID rotatedFrom,
    Instant revokedAt,
    String revokedReason,
    String userAgent,
    String ip,
    boolean kiosk) {

  /** Whether the row is past its idle or absolute expiry at {@code now}. */
  public boolean expiredAt(Instant now) {
    return !idleExpiresAt.isAfter(now) || !absoluteExpiresAt.isAfter(now);
  }
}
