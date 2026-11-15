/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.api;

import java.util.Locale;

/**
 * {@code agent_tokens.status} of PostgreSQL V001 ({@code ACTIVE|GRACE|REVOKED|EXPIRED}). 16 §2.2
 * names the rotation state {@code rotating}; the stored name is {@code GRACE}.
 */
public enum TokenStatus {
  /** Issued and usable until {@code expires_at}. */
  ACTIVE,
  /** Rotated (16 §2.3): still usable until {@code grace_until}. */
  GRACE,
  /** Revoked (16 §2.3); every session of the token is closed (WO-26). */
  REVOKED,
  /** Expired or past its grace period. */
  EXPIRED;

  /** The stored text. */
  public String storedName() {
    return name();
  }

  /**
   * The status of a stored value, any case; {@code rotating} (16 §2.2) is {@link #GRACE}. Null and
   * unknown text are {@link #REVOKED}: an unreadable status never authenticates (fail closed).
   */
  public static TokenStatus fromStored(String value) {
    if (value == null) {
      return REVOKED;
    }
    String upper = value.strip().toUpperCase(Locale.ROOT);
    if (upper.equals("ROTATING")) {
      return GRACE;
    }
    for (TokenStatus status : values()) {
      if (status.name().equals(upper)) {
        return status;
      }
    }
    return REVOKED;
  }
}
