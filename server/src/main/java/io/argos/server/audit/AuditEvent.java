/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

/**
 * Audit events (16 §11.1; names per ADR-0010 C-106 (7)). This is the subset that WO-24 emits:
 * authentication, users and roles, and the setup wizard. The full catalogue (tokens, applications,
 * alerts, commands, configuration changes, privacy, exports, system events), its snapshot test
 * against 16 §11.1 ({@code ST-80-1}) and the other sinks are WO-28's; new constants are added here
 * by the work order that emits them.
 *
 * <p>The {@code audit_logs.action} column holds {@link #name()}.
 */
public enum AuditEvent {
  // Authentication (16 §11.1 group 인증): target user, sid, ip, user agent
  /** A login succeeded (a new session was issued). */
  LOGIN_SUCCESS,
  /** A login failed (unknown user, wrong password, disabled or locked account). */
  LOGIN_FAILED,
  /** Repeated failures locked the account (15, 30, 60 minutes). */
  ACCOUNT_LOCKED,
  /** An administrator unlocked the account. */
  ACCOUNT_UNLOCKED,
  /** The user logged out (the current session was revoked). */
  LOGOUT,
  /** A session was revoked; the reason names the hook (16 §7.4). */
  SESSION_REVOKED,
  /** A rotated refresh token was presented again: the whole family was revoked (16 §7.2). */
  SESSION_REUSE_DETECTED,
  /** A long kiosk session was issued (16 §7.5). */
  KIOSK_SESSION_ISSUED,
  /** The user changed their password. */
  PASSWORD_CHANGED,
  /** An administrator reset the password (must change at next login). */
  PASSWORD_RESET,
  /** A request was refused because the password must be changed first (16 §7.9). */
  MUST_CHANGE_ENFORCED,

  // Tokens (16 §11.1 group 토큰): app, token prefix
  /**
   * An agent token was issued (prefix, expiry). Written today by {@code argos-compose.sh token
   * create} of the Compose installation (ADR-0010 C-173, C-199 (3)) with actor {@code system} /
   * {@code argos-compose}; the token API of WO-26 emits it too.
   */
  TOKEN_ISSUED,

  // Users and roles (16 §11.1 group 사용자·역할): target user
  /** A user was created (administration API, setup wizard or unattended setup). */
  USER_CREATED,
  /** A user's settings changed (enabled, permissions, kiosk); before and after are recorded. */
  USER_UPDATED,
  /** A user was disabled (all sessions revoked). */
  USER_DISABLED,
  /** A user's role changed; before and after are recorded. */
  ROLE_CHANGED,

  // Sensitive views (16 §11.1 group 민감 열람; WO-23): type, tx_id
  /**
   * Sensitive data was shown: {@code type = sql_params_full} for bind parameters in {@code full}
   * mode (16 §6.7, 14 §7.3); WO-26 adds the command result types.
   */
  SENSITIVE_VIEW,

  // Exports (16 §11.1 group 내보내기; WO-23): view, filters, rows
  /** A list was exported (14 §4.17, SEC-63): the view, the filter names and the row count. */
  EXPORT_CSV,

  // Alert rules, acknowledgements and mutes (16 §11.1 group 알림; WO-25): target rule or mute
  /** An alert rule was created (by hand, as a clone or from a template). */
  RULE_CREATED,
  /** An alert rule was changed or enabled (the version after the change). */
  RULE_UPDATED,
  /** An alert rule was deleted (kept for its history, soft delete). */
  RULE_DELETED,
  /** An alert rule was disabled. */
  RULE_DISABLED,
  /** A firing alert was acknowledged (F-S26); the note is not recorded, only its length. */
  RULE_ACKED,
  /** A one-off mute was created (target, range; the reason is not recorded, only its length). */
  MUTE_CREATED,
  /** A one-off mute was deleted. */
  MUTE_DELETED,

  // Alert channels (16 §11.1 group 알림; WO-25 lane B): target channel
  /** A notification channel was created (type and masked target only, never the secret). */
  CHANNEL_CREATED,
  /** A notification channel was changed (the names of the changed fields only). */
  CHANNEL_UPDATED,
  /** A notification channel was deleted (its secrets are destroyed). */
  CHANNEL_DELETED,
  /** A test notification was sent to a channel; the reason is the outcome (status, error code). */
  CHANNEL_TEST_SENT,
  /** A webhook signing key was rotated; the previous key stays valid for 7 days (16 §8.4). */
  CHANNEL_SIGNING_KEY_ROTATED,

  // System (16 §11.1 group 시스템)
  /** The first administrator was created through the setup token or unattended setup (SEC-47). */
  SETUP_COMPLETED
}
