-- SPDX-License-Identifier: Apache-2.0
--
-- Argos PostgreSQL schema V004: the approval queue of automatic registration (WO-22; 12 §4.4,
-- F-S30, ADR-0010 C-155 (3)). Rules of V001 apply: never edited once released, explicit grants for
-- every new table.
--
-- pending_registrations.home_app_id: the home app of the agent token whose Hello was refused
-- (auto-register off for the environment, or the home app at its 8 automatically registered
-- variants). An approval creates the environment variant with registered_from_app_id = this app,
-- so that the same token reaches it on its next retry (C-155: a token reaches its home app and the
-- variants registered from it); no new token is needed. When the home app is deleted its queued
-- requests go with it (ON DELETE CASCADE). NULL only for a row queued before this version (none:
-- the table had no writer before WO-22).
--
-- A new column and an index of an existing table: argos_app's table grants of V001 cover them.
-- The index serves the 1,000-row cap (the least recently attempted row is replaced) and the list.

SET search_path TO argos, public;

ALTER TABLE argos.pending_registrations
  ADD COLUMN IF NOT EXISTS home_app_id INT NULL REFERENCES argos.apps(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS ix_pending_registrations_last_attempt
  ON argos.pending_registrations (last_attempt_at, id);
