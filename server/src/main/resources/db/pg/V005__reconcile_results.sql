-- SPDX-License-Identifier: Apache-2.0
--
-- Argos PostgreSQL schema V005: results of the nightly consistency check (WO-27; F-S22, D-14,
-- 12 §9.1, ADR-0010 C-98). Rules of V001 apply: never edited once released, explicit grants for
-- every new table.
--
-- reconcile_results: one row per run of a day (check_name 'summary', app_id NULL) and one row per
-- application, check and table that did not match:
--   day              the checked UTC day
--   app_id           ClickHouse app_id (no foreign key: the rows stay readable after an app is
--                    deleted, and ClickHouse may hold rows of an app PostgreSQL no longer has)
--   check_name       'summary', 'd03' (xlog vs app_stat_1m, service_stat_1m, instance_stat_1m),
--                    'rollup_1m' (raw table vs the other 1m rollups), 'rollup_1h' (1h vs 1m),
--                    'd12' (sum(xlog.sql_count / ext_count) vs sql_stat_raw / ext_stat_raw:
--                    recorded only, never rebuilt)
--   table_name       the rollup table checked (physical name without prefix), the raw statistics
--                    table for 'd12', '*' for the summary
--   mismatch_minutes minutes that did not match on the first check (a 1h bucket counts 60)
--   rebuilt          the day was rebuilt for this table (RollupRebuilder, ADR-0007 C-59)
--   rebuild_result   clean | repaired | mismatch | failed | not_rebuilt | record_only
--   checked_at       end of the run
--   detail           JSON: first mismatching minutes (at most 10), minutes left after the last
--                    rebuild, attempts, digests before and after, the trigger (scheduled, command)
-- No personal data: ids, table names, counts and times only. A clean day writes the summary row
-- only, so the table grows by about one row per day.
--
-- Grants: argos_app inserts and reads the rows (the job and the argos-admin command run as
-- argos_app); no UPDATE or DELETE. argos_backup reads them (V001's GRANT ... ON ALL TABLES covered
-- only the tables of that time).

SET search_path TO argos, public;

CREATE TABLE IF NOT EXISTS argos.reconcile_results (
  id               BIGSERIAL PRIMARY KEY,
  day              DATE         NOT NULL,
  app_id           INT,
  check_name       VARCHAR(32)  NOT NULL,
  table_name       VARCHAR(64)  NOT NULL,
  mismatch_minutes INT          NOT NULL DEFAULT 0 CHECK (mismatch_minutes >= 0),
  rebuilt          BOOLEAN      NOT NULL DEFAULT FALSE,
  rebuild_result   VARCHAR(16)  NOT NULL,
  checked_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
  detail           JSONB
);

CREATE INDEX IF NOT EXISTS ix_reconcile_results_day
  ON argos.reconcile_results (day DESC, check_name, app_id);

GRANT SELECT, INSERT ON argos.reconcile_results TO argos_app;
GRANT USAGE ON SEQUENCE argos.reconcile_results_id_seq TO argos_app;
GRANT SELECT ON argos.reconcile_results TO argos_backup;
GRANT SELECT ON SEQUENCE argos.reconcile_results_id_seq TO argos_backup;
