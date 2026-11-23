-- SPDX-License-Identifier: Apache-2.0
--
-- Argos PostgreSQL schema V007: alert engine state (WO-25; 12 §5.1, §5.4, F-S06, F-S17, F-S25,
-- F-S26; ADR-0010 C-98). Rules of V001 apply: never edited once released, explicit grants for every
-- new table.
--
-- alert_rules.deleted_at: a deleted rule is kept (soft delete) because alert_events.rule_id refers
--   to it (the history stays readable for its 365 days); the engine and the API ignore deleted rules.
--
-- alert_states: the in-memory state of one rule and evaluation unit (12 §5.4), written on every
--   transition only (never per evaluation) and read when the server starts:
--   rule_id, unit_key   the rule and its unit: 'app', 'instance:<id>', 'service:<id>', 'host:<id>',
--                       'server' or 'channel:<uuid>'
--   app_id, env         the rule's application and environment (NULL for server self rules)
--   target_kind, target_id, target_name
--                       the unit as alert_events stores it (target_name: instance name, service or
--                       host text, channel name; collected text, at most 256 characters)
--   state               OK | PENDING | FIRING | ACKED | RESOLVED | NO_DATA
--   severity            warn | critical (NULL while OK)
--   state_since, pending_since, firing_since, recover_since, last_notified_at
--                       times of the state machine (pending: condition first true; firing: the
--                       FIRING transition; recover: recovery condition first true)
--   deescalate_to       a lower severity applied at the next renotify (12 §5.4 two levels)
--   before_no_data      the state NO_DATA interrupted (restored when data returns)
--   event_id, event_ts  the alert_events row of the current firing (the ACK target, deep link)
--   value, threshold    last evaluated value and the threshold of its severity
--   acked_by, acked_at, ack_note
--                       the acknowledgement of the current firing (F-S26; ack_note is operator text)
--   No personal data beyond the user id of an acknowledgement.
--
-- mutes: one-off mutes (14 §4.13 /alerts/mutes, at most 30 days each): a rule is not evaluated for
--   a matching target between starts_at and ends_at. Every target column is optional (NULL: any).
--   reason is operator text, created_by the user (no foreign key, like events.created_by).
--
-- Grants: argos_app reads and writes both tables (the engine and the API); argos_backup reads them.

SET search_path TO argos, public;

ALTER TABLE argos.alert_rules ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ix_alert_rules_app
  ON argos.alert_rules (app_id, template_key) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS argos.alert_states (
  rule_id          UUID         NOT NULL REFERENCES argos.alert_rules(id),
  unit_key         VARCHAR(64)  NOT NULL,
  app_id           INT,
  env              VARCHAR(32),
  target_kind      VARCHAR(16)  NOT NULL,
  target_id        BIGINT,
  target_name      VARCHAR(256),
  state            VARCHAR(10)  NOT NULL
                   CHECK (state IN ('OK', 'PENDING', 'FIRING', 'ACKED', 'RESOLVED', 'NO_DATA')),
  severity         VARCHAR(8)   CHECK (severity IN ('warn', 'critical')),
  state_since      TIMESTAMPTZ  NOT NULL,
  pending_since    TIMESTAMPTZ,
  firing_since     TIMESTAMPTZ,
  recover_since    TIMESTAMPTZ,
  last_notified_at TIMESTAMPTZ,
  deescalate_to    VARCHAR(8),
  before_no_data   VARCHAR(10),
  event_id         UUID,
  event_ts         TIMESTAMPTZ,
  value            DOUBLE PRECISION,
  threshold        DOUBLE PRECISION,
  acked_by         UUID,
  acked_at         TIMESTAMPTZ,
  ack_note         VARCHAR(512),
  updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (rule_id, unit_key)
);

CREATE INDEX IF NOT EXISTS ix_alert_states_active
  ON argos.alert_states (app_id) WHERE state IN ('FIRING', 'ACKED');

CREATE TABLE IF NOT EXISTS argos.mutes (
  id          UUID         PRIMARY KEY,
  app_id      INT          REFERENCES argos.apps(id) ON DELETE CASCADE,
  env         VARCHAR(32),
  instance_id INT,
  rule_id     UUID         REFERENCES argos.alert_rules(id),
  starts_at   TIMESTAMPTZ  NOT NULL,
  ends_at     TIMESTAMPTZ  NOT NULL,
  reason      VARCHAR(512),
  created_by  UUID,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CHECK (ends_at > starts_at)
);

CREATE INDEX IF NOT EXISTS ix_mutes_ends ON argos.mutes (ends_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON argos.alert_states, argos.mutes TO argos_app;
GRANT SELECT ON argos.alert_states, argos.mutes TO argos_backup;
