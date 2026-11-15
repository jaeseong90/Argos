/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import java.util.Locale;

/**
 * Why a session was revoked (16 §7.2, §7.4): the value of {@code user_sessions.revoked_reason}, of
 * the audit reason of {@code SESSION_REVOKED} and of {@link SessionRevokedEvent#reason()}.
 */
public enum RevocationReason {
  /** The user logged out. */
  LOGOUT,
  /** The password was changed or reset. */
  PASSWORD_CHANGED,
  /** The role or the permissions changed: new login required. */
  ROLE_CHANGED,
  /** An administrator ended the session (also a withdrawn kiosk approval). */
  ADMIN_TERMINATED,
  /** The account was disabled. */
  ACCOUNT_DISABLED,
  /** A rotated refresh token was presented again: every session of the user is revoked. */
  REFRESH_REUSE,
  /** The sixth login of a user revoked the oldest session. */
  SESSION_LIMIT,
  /**
   * A kiosk session was refreshed without its conditions: address, approval or switch (16 §7.5).
   */
  KIOSK_NOT_ALLOWED,
  /** A refresh arrived after the idle or absolute expiry. */
  EXPIRED;

  /** The stored and audited value: {@code logout}, {@code refresh_reuse}, ... */
  public String code() {
    return name().toLowerCase(Locale.ROOT);
  }
}
