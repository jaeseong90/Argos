-- SPDX-License-Identifier: Apache-2.0
--
-- Argos PostgreSQL schema V008: notification channel health and webhook signing keys (WO-25;
-- 12 §5.5, 16 §8.3, §8.4, SEC-51..54, F-S27, ADR-0010 C-98, C-111 (2)). Rules of V001 apply: never
-- edited once released, explicit grants for every new table.
--
-- notification_channels (V001) gets:
--   status               'ok' or 'failing': failing after 3 consecutive failed deliveries (every
--                        retry of a delivery used up), ok again after the next successful one
--   consecutive_failures failed deliveries since the last successful one
--   last_success_at      end of the last successful delivery
--   last_failure_at      end of the last failed delivery
--   deleted_at           a deleted channel keeps its row (notification_deliveries refer to it) with
--                        its secrets destroyed: config_enc is emptied and its signing keys deleted
-- The secrets of a channel (webhook or Slack URL, e-mail recipients) stay in config_enc: the
-- envelope string of 16 §8.3 ('v1:<kid>:<nonce>:<ct||tag>', AES-256-GCM under the master key, AAD
-- 'notification_channels:config_enc:<id>') as UTF-8 bytes (C-98: V001's BYTEA column is kept).
-- V001's signing_key_enc stays NULL: the keys live in webhook_signing_keys.
--
-- webhook_signing_keys: the HMAC-SHA256 keys of a webhook channel (16 §8.4). At most two are
-- valid at a time: the newest (retire_after NULL) and, for 7 days after a rotation, the previous
-- one (retire_after = rotation + 7 days). Both sign every request during that grace (two v1=
-- values). A key past its retire_after is deleted by the daily cleanup.
--   channel_id   the channel (rows go with it)
--   key_id       16 hex characters, random; never shown
--   key_enc      the key as an envelope string (UTF-8 bytes), AAD
--                'webhook_signing_keys:key_enc:<channel_id>/<key_id>'
--   created_at   creation time
--   retire_after end of the grace after a rotation; NULL for the current key
--
-- notification_deliveries (V001, a plain table, C-111 (2)): one row per alert event and channel,
-- updated by every attempt (attempt = attempts so far, status pending|sent|failed|dropped,
-- http_status of the last attempt, error = an error code, never a response body). The daily
-- cleanup deletes rows older than 365 days; the indexes below serve it, the 24-hour failure count
-- of the overview and the deliveries of an event.
--
-- No personal data in the clear: the e-mail recipients are inside config_enc; config_masked holds
-- the masked form only.
--
-- Grants (as V002): argos_app reads and writes; argos_backup reads (V001's GRANT ... ON ALL TABLES
-- covered only the tables of that time).

SET search_path TO argos, public;

ALTER TABLE argos.notification_channels
  ADD COLUMN IF NOT EXISTS status VARCHAR(8) NOT NULL DEFAULT 'ok',
  ADD COLUMN IF NOT EXISTS consecutive_failures INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS last_success_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS last_failure_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_notification_channels_status') THEN
    ALTER TABLE argos.notification_channels
      ADD CONSTRAINT ck_notification_channels_status CHECK (status IN ('ok', 'failing'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_notification_channels_failures') THEN
    ALTER TABLE argos.notification_channels
      ADD CONSTRAINT ck_notification_channels_failures CHECK (consecutive_failures >= 0);
  END IF;
END
$$;

CREATE TABLE IF NOT EXISTS argos.webhook_signing_keys (
  channel_id   UUID        NOT NULL REFERENCES argos.notification_channels(id) ON DELETE CASCADE,
  key_id       VARCHAR(16) NOT NULL,
  key_enc      BYTEA       NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  retire_after TIMESTAMPTZ,
  PRIMARY KEY (channel_id, key_id)
);

CREATE INDEX IF NOT EXISTS ix_deliveries_sent ON argos.notification_deliveries (sent_at);
CREATE INDEX IF NOT EXISTS ix_deliveries_event ON argos.notification_deliveries (alert_event_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON argos.webhook_signing_keys TO argos_app;
GRANT SELECT ON argos.webhook_signing_keys TO argos_backup;
