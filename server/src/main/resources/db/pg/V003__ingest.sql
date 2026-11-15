-- SPDX-License-Identifier: Apache-2.0
--
-- Argos PostgreSQL schema V003: ingest (WO-21; 12 §3.5 (2), 13 §12.3, ADR-0010 C-101, C-155). Rules
-- of V001 apply: never edited once released, explicit grants for every new table.
--
-- DictionaryKind POOL = 12 (ADR-0010 C-101, proto/README.md): connection pool names of DBC steps
-- (10 §6.3, 13 §12.3 'pool', ADR-0010 C-143 (4)) are dictionary entries of their own kind. The
-- value goes at the end of the type, like the protocol number. No new table, so no new grant:
-- argos_app already has its DML on argos.dictionary (V001).
--
-- ALTER TYPE ... ADD VALUE runs inside Flyway's transaction (PostgreSQL 12 and later); the new value
-- is not used in this script.
--
-- apps.registered_from_app_id (ADR-0010 C-155, token scope): an app registered automatically by
-- the Hello of an agent token for another environment of the token's app (its "home app") names
-- that home app. A token may use its home app and the environment variants registered from it,
-- at most 8 of them; an administrator-created app has NULL. When the home app is deleted its
-- variants keep their rows and only their own tokens reach them (ON DELETE SET NULL). A new column
-- of an existing table: argos_app's table grants of V001 cover it.

SET search_path TO argos, public;

ALTER TYPE argos.dict_kind ADD VALUE IF NOT EXISTS 'pool';

ALTER TABLE argos.apps
  ADD COLUMN registered_from_app_id INT NULL REFERENCES argos.apps(id) ON DELETE SET NULL;
