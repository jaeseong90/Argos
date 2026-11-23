-- SPDX-License-Identifier: Apache-2.0
--
-- Argos PostgreSQL schema V010: the revocation record of an agent token (WO-26; 12 section 7.1,
-- 16 section 2.2 and 2.3). Rules of V001 apply: never edited once released, explicit grants for
-- every new table (none here: a new column inherits the table's grants).
--
-- agent_tokens.revoked_by: the administrator who revoked the token (16 section 2.2 revoked_by);
--   NULL for a revocation by the server itself (an application deleted by the system, a command
--   line) and once that user is deleted (ON DELETE SET NULL keeps the token row).
-- agent_tokens.revoke_reason: the reason the administrator gave (POST /admin/tokens/{id}/revoke,
--   14 section 4.14), at most 512 characters. Free text of an administrator, not collected
--   application data; the audit log records only its length (as for mute and acknowledgement
--   notes).
--
-- The stored status values stay those of V001 (ACTIVE, GRACE, REVOKED, EXPIRED); the API names
-- them active, rotating, revoked and expired.

SET search_path TO argos, public;

ALTER TABLE argos.agent_tokens
  ADD COLUMN IF NOT EXISTS revoked_by UUID NULL REFERENCES argos.users(id) ON DELETE SET NULL;
ALTER TABLE argos.agent_tokens
  ADD COLUMN IF NOT EXISTS revoke_reason VARCHAR(512) NULL;
