-- SPDX-License-Identifier: Apache-2.0
--
-- Argos PostgreSQL schema V006: service Apdex T overrides (WO-27; D-17, F-S16, 12 §3.5 (5)). Rules
-- of V001 apply: never edited once released, explicit grants for every new table.
--
-- service_apdex_overrides: the Apdex T of one service (SERVICE dictionary key, the xlog.service_id
-- of its transactions) when it differs from the app's apps.apdex_t_ms. The ingest path stamps a
-- transaction with its service's override, otherwise with the app's T, at receipt (forward only;
-- the WAL keeps the snapshot of the receipt, WalDataPayload format 2). At most 1,000 rows per app:
-- the server refuses a 1,001st (ApdexChangeService) and ignores the overrides of an app that has
-- more. Every change writes an events row of type apdex_t_change (the UI marker). The rows of a
-- deleted app go with it. updated_by names the user who set it (NULL for an operator command; no
-- foreign key, like events.created_by, so that deleting a user keeps the history readable).
-- No personal data: ids and milliseconds only.
--
-- Grants (as V002): argos_app reads and writes the overrides (the runtime changes them);
-- argos_backup reads them (V001's GRANT ... ON ALL TABLES covered only the tables of that time).

SET search_path TO argos, public;

CREATE TABLE IF NOT EXISTS argos.service_apdex_overrides (
  app_id     INT         NOT NULL REFERENCES argos.apps(id) ON DELETE CASCADE,
  service_id BIGINT      NOT NULL,
  apdex_t_ms INT         NOT NULL CHECK (apdex_t_ms BETWEEN 1 AND 65535),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by UUID,
  PRIMARY KEY (app_id, service_id)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON argos.service_apdex_overrides TO argos_app;
GRANT SELECT ON argos.service_apdex_overrides TO argos_backup;
