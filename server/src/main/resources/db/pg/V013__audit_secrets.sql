-- SPDX-License-Identifier: Apache-2.0
--
-- Argos PostgreSQL schema V013: audit queries, secrets and application salts (WO-28; 12 section
-- 8.1, 14 section 4.14, 16 section 6.3, 11.5). Rules of V001 apply: never edited once released,
-- explicit grants for every new table (none here: a new column or index inherits the table's
-- grants).
--
-- audit_logs indexes: GET /api/v1/admin/audit filters a [from, to) range by action or by target
--   (target_type, target_id) and pages newest first by (ts, id); V001 has (ts) and (actor_id, ts).
--   An index on the partitioned table is created on every partition, present and future.

SET search_path TO argos, public;

CREATE INDEX IF NOT EXISTS ix_audit_action_ts ON argos.audit_logs(action, ts DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_audit_target_ts ON argos.audit_logs(target_type, target_id, ts DESC, id DESC);

-- apps.user_id_salt_enc: the application's 32-byte user id salt (16 section 6.3, ADR-0010 C-125
--   (2), C-152), an envelope (16 section 8.3, AAD 'apps:user_id_salt_enc:<id>') as UTF-8 bytes like
--   notification_channels.config_enc. Created by the server the first time it is needed (an agent
--   Hello, a hashed user id search or deletion) with UPDATE ... WHERE user_id_salt_enc IS NULL, sent
--   to the agents in ServerHello.user_id_salt (TLS only) and re-encrypted by argos-admin secrets
--   rekey. NULL until then. Secret (02 classification: encrypted, never shown).
-- apps.auto_register: three states (ADR-0010 C-231 (7)). TRUE = registration policy auto, FALSE =
--   approval, NULL = the environment default argos.agent.auto-register.<env> (12 section 4.4). An
--   agent Hello of an unknown (name, env) follows its token's home application when that has a
--   value, else the environment default. The default FALSE of V001 could not tell "approval" from
--   "not set": the rows still FALSE become NULL (no 0.x installation predates this; a value set
--   with the API before this version is set again if it was approval). Rows an agent Hello created
--   keep TRUE (they also mark the variants that count against the home app's cap, C-155).

ALTER TABLE argos.apps ADD COLUMN IF NOT EXISTS user_id_salt_enc BYTEA NULL;
ALTER TABLE argos.apps ALTER COLUMN auto_register DROP NOT NULL;
ALTER TABLE argos.apps ALTER COLUMN auto_register DROP DEFAULT;
UPDATE argos.apps SET auto_register = NULL WHERE auto_register = FALSE;
