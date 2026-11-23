-- SPDX-License-Identifier: Apache-2.0
--
-- Argos PostgreSQL schema V011: remote commands and configuration change control (WO-26 lane B;
-- 12 §6.1, §6.2, 16 §5.2, §5.3, 14 §4.10, §4.14). Rules of V001 apply: never edited once released,
-- explicit grants for every new table (new columns inherit the table's grants).
--
-- commands (V001) gains:
--   args        the command arguments (THREAD_STACK: thread_id or tx_id, <= 8 entries, values
--               <= 256 B, digits only; 11 §8.2). No secret, no collected text.
--   sent_at     when the command went out on the instance's session (NULL while QUEUED).
--   error_code  why a command did not finish DONE, a closed list (never agent text):
--                 rejected       the agent refused it (REJECTED)
--                 cooldown       the agent's cooldown was running (COOLDOWN, 16 §5.2)
--                 agent_failed   the agent ran it and failed (FAILED)
--                 agent_expired  the agent received it after its TTL (EXPIRED)
--                 ttl            not sent within the TTL: never delivered (SEC-24)
--                 timeout        sent, no complete result within argos.commands.result-timeout
--                 session_ended  the instance's session ended while it was queued or sent
--                 invalid_result the agent's result chunks were inconsistent
--   status is the closed list QUEUED|SENT|DONE|FAILED|EXPIRED (14 §4.10 queued|sent|done|failed|
--   expired; the agent's REJECTED and COOLDOWN are FAILED with their error_code, WO-26 assumption).
--
-- command_results (V001) gains content_type (text/plain or application/json, as the agent sent
-- it). body_enc holds the 16 §8.3 envelope (ASCII) of the gzip-compressed result, associated data
-- command_results:body_enc:<command id> (SEC-22); body_size is the uncompressed size.
--
-- config_changes (V001) gains, for ConfigChangeService and the kill switch (WO-26 lane B step 2):
--   kind        config | kill_switch (argos.enabled, 12 §6.2 KillSwitchService)
--   status      previewed (proposed, not applied) | applying (pushed, results arriving) | applied
--               (every target answered or is pending/offline) | superseded (a newer change of the
--               app was applied before this preview)
--   target_all  the change targets every instance of the app (target_instance_ids NULL then)
--   preview     the 201 preview: {diff: [{key, before, after}], rejected_keys_by_instance: [...]}
--   applied_at, applied_by  who applied it and when
--   reverted_from (V001) is the revert link of 12 §6.2 revert_of.
--
-- config_change_results (V001) gains applied_keys, rejected (the [{key, code, by}] list: codes of
-- session.proto RejectCode from the agent, or not_allowed from the server for keys outside the
-- instance's remote_config_allow that were never pushed; no values), effective_config_hash,
-- duration_ms and pushed_at. status is
-- applied | rejected | partial | offline | pending (14 §4.14 plus partial: some keys applied, some
-- rejected).
--
-- app_config: the values the server holds per application (12 §6.2 "서버 보관 값"), the base of the
-- diff, of the pending push in the next ServerHello.config and of the drift check. Keys are the
-- agent's dotted keys (docs/agent-config.md "Remote configuration"); values are the agent's string
-- form. No secrets: the remote allow list never covers secret keys (16 §5.5).

SET search_path TO argos, public;

ALTER TABLE commands ADD COLUMN IF NOT EXISTS args JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE commands ADD COLUMN IF NOT EXISTS sent_at TIMESTAMPTZ;
ALTER TABLE commands ADD COLUMN IF NOT EXISTS error_code VARCHAR(16)
  CONSTRAINT ck_commands_error_code CHECK (error_code IN (
    'rejected', 'cooldown', 'agent_failed', 'agent_expired', 'ttl', 'timeout', 'session_ended',
    'invalid_result'));
ALTER TABLE commands ADD CONSTRAINT ck_commands_status
  CHECK (status IN ('QUEUED', 'SENT', 'DONE', 'FAILED', 'EXPIRED'));
ALTER TABLE commands ADD CONSTRAINT ck_commands_type
  CHECK (type IN ('thread_dump', 'thread_stack', 'self_diagnosis', 'reload_config', 'env_dump'));

-- History of an instance (GET /instances/{id}/commands), per-instance retention (newest 100).
CREATE INDEX IF NOT EXISTS ix_commands_instance_requested ON commands(instance_id, requested_at DESC);
-- The expiry sweep and the in-flight check (few rows: at most one per instance).
CREATE INDEX IF NOT EXISTS ix_commands_inflight ON commands(status) WHERE status IN ('QUEUED', 'SENT');
-- The 30-day retention.
CREATE INDEX IF NOT EXISTS ix_commands_requested ON commands(requested_at);

ALTER TABLE command_results ADD COLUMN IF NOT EXISTS content_type VARCHAR(32) NOT NULL DEFAULT 'text/plain';

ALTER TABLE config_changes ADD COLUMN IF NOT EXISTS kind VARCHAR(12) NOT NULL DEFAULT 'config'
  CONSTRAINT ck_config_changes_kind CHECK (kind IN ('config', 'kill_switch'));
ALTER TABLE config_changes ADD COLUMN IF NOT EXISTS status VARCHAR(10) NOT NULL DEFAULT 'previewed'
  CONSTRAINT ck_config_changes_status CHECK (status IN ('previewed', 'applying', 'applied', 'superseded'));
ALTER TABLE config_changes ADD COLUMN IF NOT EXISTS target_all BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE config_changes ADD COLUMN IF NOT EXISTS preview JSONB;
ALTER TABLE config_changes ADD COLUMN IF NOT EXISTS applied_at TIMESTAMPTZ;
ALTER TABLE config_changes ADD COLUMN IF NOT EXISTS applied_by UUID;
CREATE INDEX IF NOT EXISTS ix_config_changes_app_requested ON config_changes(app_id, requested_at DESC);

ALTER TABLE config_change_results ADD COLUMN IF NOT EXISTS applied_keys TEXT[];
ALTER TABLE config_change_results ADD COLUMN IF NOT EXISTS rejected JSONB;
ALTER TABLE config_change_results ADD COLUMN IF NOT EXISTS effective_config_hash VARCHAR(64);
ALTER TABLE config_change_results ADD COLUMN IF NOT EXISTS duration_ms INT;
-- When the ConfigPush went out on the instance's session; NULL for a target that was not
-- connected (pending until its next ServerHello.config) or got nothing to push. A pending row with
-- pushed_at set is waiting for the agent's ConfigApplyResult.
ALTER TABLE config_change_results ADD COLUMN IF NOT EXISTS pushed_at TIMESTAMPTZ;
ALTER TABLE config_change_results ADD CONSTRAINT ck_config_change_results_status
  CHECK (status IN ('applied', 'rejected', 'partial', 'offline', 'pending'));
CREATE INDEX IF NOT EXISTS ix_config_change_results_instance ON config_change_results(instance_id);
-- The result timeout sweep (rows waiting for a ConfigApplyResult: few).
CREATE INDEX IF NOT EXISTS ix_config_change_results_awaiting ON config_change_results(pushed_at)
  WHERE status = 'pending' AND pushed_at IS NOT NULL;

CREATE TABLE app_config (
  app_id     INT NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
  key        VARCHAR(128) NOT NULL,
  value      VARCHAR(1024) NOT NULL,
  change_id  UUID REFERENCES config_changes(id) ON DELETE SET NULL,
  updated_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (app_id, key)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON app_config TO argos_app;
GRANT SELECT ON app_config TO argos_backup;
