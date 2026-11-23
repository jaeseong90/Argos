/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.token;

import io.argos.server.collector.api.TokenStatus;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The API state of an agent token (14 §4.14 {@code active|rotating|revoked}, plus {@code expired}
 * of 16 §2.2): the stored status of V001 ({@link TokenStatus}) judged at a time like the collector
 * judges it ({@code TokenRecord#verdict}), so a row whose expiry or grace has passed but which the
 * expiry job has not swept yet already reads {@code expired}.
 */
public enum TokenState {
  /** Issued and authenticating. */
  ACTIVE,
  /** Rotated (stored {@code GRACE}): authenticating until {@code grace_until}. */
  ROTATING,
  /** Revoked by an administrator or the server. */
  REVOKED,
  /** Past {@code expires_at} or its grace period. */
  EXPIRED;

  /** The API value: {@code active}, {@code rotating}, {@code revoked}, {@code expired}. */
  public String apiName() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The state of a row at {@code now}. */
  public static TokenState of(
      TokenStatus status,
      @Nullable Instant expiresAt,
      @Nullable Instant graceUntil,
      @Nullable Instant revokedAt,
      Instant now) {
    if (status == TokenStatus.REVOKED || revokedAt != null) {
      return REVOKED;
    }
    if (status == TokenStatus.EXPIRED || (expiresAt != null && !now.isBefore(expiresAt))) {
      return EXPIRED;
    }
    if (status == TokenStatus.GRACE) {
      return graceUntil != null && now.isBefore(graceUntil) ? ROTATING : EXPIRED;
    }
    return ACTIVE;
  }

  /** Whether a token in this state authenticates. */
  public boolean authenticates() {
    return this == ACTIVE || this == ROTATING;
  }
}
