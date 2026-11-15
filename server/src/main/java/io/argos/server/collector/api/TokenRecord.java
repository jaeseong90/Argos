/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@code agent_tokens} row with its app (16 §2.4 {@code TokenRecord{id, appId, status,
 * expiresAt, graceUntil}}), as {@link TokenLookup} returns and caches it. Holds no secret: the
 * token itself is never read back.
 *
 * @param tokenId {@code agent_tokens.id}; the key of {@code SessionRegistry.closeByToken} (WO-26)
 * @param appId {@code agent_tokens.app_id} (one token = one app, 16 §2.1)
 * @param appName {@code apps.name} of that app: 11 §5 check 2 compares it with {@code
 *     AgentHello.app_name} ({@code APP_MISMATCH}, SEC-01)
 * @param appEnv {@code apps.env} of that app
 * @param prefix {@code agent_tokens.token_prefix} ({@code argos_} + 8 characters), for logs
 * @param status {@code agent_tokens.status}
 * @param expiresAt {@code agent_tokens.expires_at}, {@code null} = no expiry
 * @param graceUntil {@code agent_tokens.grace_until}, {@code null} unless rotated
 * @param revokedAt {@code agent_tokens.revoked_at}, {@code null} unless revoked
 */
public record TokenRecord(
    UUID tokenId,
    int appId,
    String appName,
    String appEnv,
    String prefix,
    TokenStatus status,
    Instant expiresAt,
    Instant graceUntil,
    Instant revokedAt) {

  /** Validates the required components. */
  public TokenRecord {
    Objects.requireNonNull(tokenId, "tokenId");
    Objects.requireNonNull(appName, "appName");
    Objects.requireNonNull(appEnv, "appEnv");
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(status, "status");
  }

  /**
   * The 16 §2.4 decision at {@code now}: {@link TokenVerdict#ACCEPTED} when the status is {@code
   * ACTIVE}, or {@code GRACE} before {@code grace_until}, and {@code now} is before {@code
   * expires_at}. Inconsistent rows fail closed: {@code revoked_at} set means revoked, a {@code
   * GRACE} row without {@code grace_until} is expired.
   */
  public TokenVerdict verdict(Instant now) {
    if (status == TokenStatus.REVOKED || revokedAt != null) {
      return TokenVerdict.REVOKED;
    }
    if (status == TokenStatus.EXPIRED) {
      return TokenVerdict.EXPIRED;
    }
    if (expiresAt != null && !now.isBefore(expiresAt)) {
      return TokenVerdict.EXPIRED;
    }
    if (status == TokenStatus.GRACE && (graceUntil == null || !now.isBefore(graceUntil))) {
      return TokenVerdict.EXPIRED;
    }
    return TokenVerdict.ACCEPTED;
  }
}
