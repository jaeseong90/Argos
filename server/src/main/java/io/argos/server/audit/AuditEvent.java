/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

/**
 * The audit event catalogue (16 §11.1 and §5.7; names per ADR-0010 C-106 (7); final list of WO-28:
 * 74 events, ADR-0010 C-231 (4), C-176 (1), C-220 (3)).
 *
 * <p>Differences from the 16 §11.1 table, pending its correction (fixed by {@code
 * AuditCatalogTest}, T-SV-044a): {@code CONFIG_CHANGE_ROLLED_BACK} is not an event (a rollback is
 * {@link #CONFIG_ROLLBACK}); {@link #EVENT_CREATED}, {@link #EVENT_DELETED}, {@link
 * #RETENTION_EMERGENCY_DROP}, {@link #ROLLUP_REBUILT}, {@link #CONSISTENCY_CHECKED}, {@link
 * #APDEX_REAPPLIED} and {@link #ADMIN_COMMAND_EXECUTED} are added. Every export of a list or a
 * single record is {@link #EXPORT_CSV} with {@code format=csv|json} in its detail; there is no
 * generic {@code EXPORT} name.
 *
 * <p>The catalogue is a public contract: the committed snapshot {@code audit-catalog.txt} (unit
 * test resources) must change in the same change as this enum. The {@code audit_logs.action} column
 * holds {@link #name()}.
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
  /** An agent token was rotated: the old one is {@code rotating} until its grace ends (WO-26). */
  TOKEN_ROTATED,
  /** An agent token was revoked; its live sessions were closed (WO-26). */
  TOKEN_REVOKED,
  /** An agent token passed its expiry or rotation grace; its live sessions were closed (WO-26). */
  TOKEN_EXPIRED,
  /**
   * An agent presented a missing, unknown, revoked or expired token (ip, token prefix when one was
   * given). Rate limited per (event, client address or prefix) to {@code
   * argos.audit.agent-events-per-minute}; the suppressed count is in the next record (ADR-0010
   * C-158 (1)).
   */
  AGENT_AUTH_FAILED,
  /** A client address was blocked after repeated agent authentication failures (ip). */
  AGENT_AUTH_BLOCKED,
  /**
   * An agent Hello was refused after authentication (the reason is the code: unsupported protocol,
   * application not registered or pending approval, session limit, ...).
   */
  AGENT_SESSION_REJECTED,

  // Users and roles (16 §11.1 group 사용자·역할): target user
  /** A user was created (administration API, setup wizard or unattended setup). */
  USER_CREATED,
  /** A user's settings changed (enabled, permissions, kiosk); before and after are recorded. */
  USER_UPDATED,
  /** A user was disabled (all sessions revoked). */
  USER_DISABLED,
  /** A user's role changed; before and after are recorded. */
  ROLE_CHANGED,
  /**
   * A user was deleted (WO-26, ADR-0010 C-136 (3)): the row is anonymised and disabled, its
   * sessions revoked; the target name is the name it had.
   */
  USER_DELETED,

  // Applications and instances (16 §11.1 group 앱·인스턴스; WO-26 lane C): app, env
  /** An application was created by an administrator ({@code POST /apps}). */
  APP_CREATED,
  /**
   * An application's settings changed ({@code PATCH /apps/{appId}}, a service Apdex T override);
   * the changed fields before and after are recorded.
   */
  APP_UPDATED,
  /** An application was deleted: its tokens revoked, sessions closed, data left to its TTL. */
  APP_DELETED,
  /** A queued registration was approved: the application exists and a token was issued. */
  APP_APPROVED,
  /** A queued registration was rejected (the request row is deleted). */
  APP_REJECTED,
  /** An instance was archived by an operator. */
  INSTANCE_ARCHIVED,
  /** An archived instance was deleted by an administrator. */
  INSTANCE_DELETED,
  /**
   * A deploy marker or custom event was created ({@code POST /events}, 14 §4.12 {@code
   * event.create}); the actor is the user, or the agent token's prefix (02 C-12). Not in the 16
   * §11.1 table yet (reported to the PM with WO-26).
   */
  EVENT_CREATED,
  /** A deploy marker or custom event was deleted ({@code DELETE /events/{eventId}}). */
  EVENT_DELETED,

  // Commands (16 §11.1 group 명령·설정, §5.7; WO-26 lane B): instance, command id; never a result
  // body or an argument value
  /** A command was requested and queued (type, instance). */
  COMMAND_REQUESTED,
  /** A queued command was sent on the instance's session. */
  COMMAND_SENT,
  /** A command's result was received and stored encrypted (size, truncated). */
  COMMAND_COMPLETED,
  /**
   * A command was refused: by the server before it was queued (role, offline, one in flight, rate
   * limit) or by the agent ({@code REJECTED}, {@code COOLDOWN}); the reason is the code.
   */
  COMMAND_REJECTED,
  /** A command expired: not sent within its TTL, no result in time, or its session ended. */
  COMMAND_EXPIRED,
  /** A command's result was viewed (requester or administrator). */
  COMMAND_RESULT_VIEWED,
  /**
   * A configuration change was pushed ({@code apply()}): keys, before and after (secret-like values
   * masked), the instances that are not sent a key because their allow list does not name it.
   */
  CONFIG_PUSH,
  /** A configuration change was reverted: a new change with the previous values (revert_of). */
  CONFIG_ROLLBACK,
  /** The kill switch was turned on ({@code enabled=false}) or off for an app's instances. */
  KILL_SWITCH,
  /**
   * A configuration change touched the custom method instrumentation keys of an application (16
   * §5.7); recorded besides {@link #CONFIG_PUSH} once its instances answered (applied: success,
   * refused: rejected; ADR-0010 C-238 (8)), with only those keys (before and after masked like
   * {@link #CONFIG_PUSH}: class and method patterns) and {@code _outcome} (counts, per-instance
   * status and rejection code).
   */
  CUSTOM_METHOD_CHANGED,

  // Configuration change control (16 §11.1 group 설정 변경 통제; WO-26 lane B): app, change id
  /** A configuration change was proposed (reason, diff); not applied yet. */
  CONFIG_CHANGE_CREATED,
  /**
   * The results of a configuration change are in (every pushed instance answered, timed out or went
   * offline): per-instance status and rejected keys, never values.
   */
  CONFIG_CHANGE_APPLIED,

  // Sensitive views (16 §11.1 group 민감 열람; WO-23): type, tx_id
  /**
   * Sensitive data was shown: {@code type = sql_params_full} for bind parameters in {@code full}
   * mode (16 §6.7, 14 §7.3); {@code env_dump} and {@code thread_dump} for command results (16 §5.7,
   * WO-26; a thread stack counts as a thread dump), with the command id.
   */
  SENSITIVE_VIEW,

  // Exports (16 §11.1 group 내보내기; WO-23): view, filters, rows
  /**
   * A list or a single record was exported (14 §4.17, SEC-63): the view, the filter names, the row
   * count and {@code format=csv|json} (ADR-0010 C-176 (1)).
   */
  EXPORT_CSV,
  /** The audit log was queried ({@code GET /admin/audit}): the filters and the row count. */
  AUDIT_LOG_VIEWED,
  /** The audit log was exported ({@code GET /admin/audit/export}): the filters and the rows. */
  AUDIT_LOG_EXPORTED,

  // Privacy and retention (16 §11.1 group 개인정보·보존; WO-28)
  /** The server's privacy defaults changed ({@code PUT /admin/settings/privacy}). */
  PRIVACY_SETTING_CHANGED,
  /** A retention period changed ({@code PUT /admin/settings/retention}): before and after. */
  RETENTION_CHANGED,
  /**
   * The emergency retention dropped a daily partition because the disk was above the emergency
   * ratio (13 §8): table, partition, disk ratio.
   */
  RETENTION_EMERGENCY_DROP,
  /**
   * A privacy deletion was requested: subject type, the SHA-256 of the value (never the value),
   * range, job id.
   */
  PRIVACY_DELETE_REQUESTED,
  /** A privacy deletion job ended: status, tables and rows affected. */
  PRIVACY_DELETE_COMPLETED,

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
  SETUP_COMPLETED,
  /** A TLS certificate was reloaded (listener, new expiry), by the file watch or the API. */
  TLS_RELOADED,
  /** Stored secrets were re-encrypted with a new master key (key ids only, rows per table). */
  MASTER_KEY_ROTATED,
  /** The server finished starting (version). */
  SERVER_STARTED,
  /** A PostgreSQL or ClickHouse schema version was applied (store, version). */
  MIGRATION_APPLIED,

  // Operations (argos-admin and the nightly jobs; ADR-0010 C-220 (3))
  /** Rollup partitions were rebuilt ({@code argos-admin rollup rebuild}): date, tables, digests. */
  ROLLUP_REBUILT,
  /** A consistency check ran (nightly job or {@code argos-admin consistency check}). */
  CONSISTENCY_CHECKED,
  /** Apdex T was re-applied to stored transactions ({@code argos-admin apdex reapply}). */
  APDEX_REAPPLIED,
  /**
   * An {@code argos-admin} subcommand without an event of its own ran: target type {@code cli},
   * target name the subcommand.
   */
  ADMIN_COMMAND_EXECUTED
}
