-- SPDX-License-Identifier: Apache-2.0
--
-- Argos PostgreSQL schema V002: user authentication (WO-24; 16 §7.2, §7.3, §7.9, §7.10; 14 §4.1,
-- §4.14; ADR-0010 C-98, C-106, C-117). Rules of V001 apply: never edited once released, explicit
-- grants for every new table (argos_app the DML it needs, argos_backup SELECT).

SET search_path TO argos, public;

-- 1. user_sessions: refresh rotation and expiry columns of 16 §7.3 (13 §12 key types kept: users.id
--    UUID, refresh_hash CHAR(64) = hex SHA-256 of the refresh token; user_agent VARCHAR(256) and ip
--    as in V001, C-117).
--    * family_id: every session created by one login and its refresh rotations, the unit of
--      revocation; reuse of a rotated refresh token revokes every session of the user (SEC-41,
--      14 §2.2, 12 §7.2).
--    * rotated_at / rotated_from: set on the old row / the new row of a rotation (logical reference,
--      no foreign key: expired rows are deleted 7 days after expiry or revocation, C-117).
--    * revoked_reason: logout, password_changed, role_changed, admin_terminated, account_disabled,
--      refresh_reuse, session_limit, kiosk_not_allowed, expired (16 §7.2, §7.4, §7.5).
--    * idle_expires_at: last use + 8 h (kiosk: 24 h); absolute_expires_at: creation + 24 h (kiosk:
--      30 days) (16 §7.3, §7.5, §15 #15). absolute_expires_at is V001's expires_at, which 13 §12
--      already documents as the absolute expiry: renamed to the 16 §7.3 name instead of duplicated.
--    Rows that exist already (none in a new installation) keep their id as family and expire idle
--    at their absolute expiry.
ALTER TABLE user_sessions RENAME COLUMN expires_at TO absolute_expires_at;
ALTER TABLE user_sessions
  ADD COLUMN family_id       UUID,
  ADD COLUMN idle_expires_at TIMESTAMPTZ,
  ADD COLUMN rotated_at      TIMESTAMPTZ,
  ADD COLUMN rotated_from    UUID,
  ADD COLUMN revoked_reason  VARCHAR(40);
UPDATE user_sessions SET family_id = id, idle_expires_at = absolute_expires_at;
ALTER TABLE user_sessions
  ALTER COLUMN family_id SET NOT NULL,
  ALTER COLUMN idle_expires_at SET NOT NULL;
CREATE INDEX ix_user_sessions_family ON user_sessions(family_id);
COMMENT ON COLUMN user_sessions.revoked_reason IS
  'logout | password_changed | role_changed | admin_terminated | account_disabled | refresh_reuse | session_limit | kiosk_not_allowed | expired';

-- 2. users: lockout progression (16 §7.9, SEC-48). 10 consecutive failures lock the account for
--    15 minutes; each further lockout without a successful login in between doubles it, at most 60
--    minutes. lockout_level counts those lockouts (0 = none since the last successful login,
--    password change or reset, or administrator unlock); failed_logins and locked_until are V001's.
ALTER TABLE users ADD COLUMN lockout_level SMALLINT NOT NULL DEFAULT 0 CHECK (lockout_level >= 0);

-- 3. user_permissions: extra permissions granted per administrator, none by default (14 §4.14,
--    02 §4.3, ADR-0010 C-106 (6)). 1.0 knows pii.view only (bind parameters in full mode, 14 §7.3).
CREATE TABLE user_permissions (
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  permission VARCHAR(32) NOT NULL CHECK (permission IN ('pii.view')),
  granted_by UUID REFERENCES users(id) ON DELETE SET NULL,
  granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, permission)
);

-- Privileges (as V001): the runtime reads and changes grants, the backup account reads.
GRANT SELECT, INSERT, UPDATE, DELETE ON user_permissions TO argos_app;
GRANT SELECT ON user_permissions TO argos_backup;

-- 4. login_failures_unknown: the failed-login counter of names that are no account (16 §7.9,
--    SEC-48, WO-24 "사용자 열거 방지"). An unknown name counts failures and locks by the rule of a
--    users row (same columns, same rules, durable across restarts and nodes, never forgotten by
--    time), so the tenth answer (423) does not tell whether a name exists. A login reads the users
--    row and this row in one SELECT; a locked name of either kind is refused without a write, and
--    a counted failure is one transaction of two statements for both. The statements differ (an
--    upsert here), so the timing is close, not identical.
--    * name_hash: hex SHA-256 of the attempted name as typed (the name itself is not stored).
--    * failed_logins / locked_until / lockout_level: as users (section 2).
--    * last_failed_at: the latest failure; the runtime keeps at most a fixed number of rows and
--      deletes the oldest by this column when there are more (bounded table, 16 §15 #12).
--    Server security record of its own login form, like user_sessions.ip (ADR-0010 C-117).
CREATE TABLE login_failures_unknown (
  name_hash      CHAR(64) PRIMARY KEY,
  failed_logins  INT NOT NULL DEFAULT 0 CHECK (failed_logins >= 0),
  locked_until   TIMESTAMPTZ,
  lockout_level  SMALLINT NOT NULL DEFAULT 0 CHECK (lockout_level >= 0),
  last_failed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_login_failures_unknown_last_failed ON login_failures_unknown(last_failed_at);

-- Privileges (as V001): the runtime counts, locks and prunes, the backup account reads.
GRANT SELECT, INSERT, UPDATE, DELETE ON login_failures_unknown TO argos_app;
GRANT SELECT ON login_failures_unknown TO argos_backup;
