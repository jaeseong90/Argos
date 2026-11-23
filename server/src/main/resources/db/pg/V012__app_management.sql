-- SPDX-License-Identifier: Apache-2.0
--
-- Argos PostgreSQL schema V012: application and user administration (WO-26; 14 section 4.3,
-- 4.14, 16 section 7.10, 11.1; ADR-0010 C-136 (3), C-153, C-207 (3)). Rules of V001 apply: never
-- edited once released, explicit grants for every new table (none here: a new column inherits the
-- table's grants).
--
-- apps.app_packages: the Java packages of the application's own code (PATCH /apps/{appId}, at
--   most 50 names of at most 255 characters each, validated by the server; never java., javax.,
--   jdk., sun. or io.argos.). The UI marks stack frames of these packages as application frames
--   (ADR-0010 C-207 (3)). Configuration of an administrator, no personal data.
-- apps.deleted_at: DELETE /apps/{appId} (14 section 4.3: tokens revoked, sessions closed, data
--   left to its TTL). The row stays because instances, agent_tokens and alert_rules reference it
--   (foreign keys without cascade, V001) and ClickHouse keeps rows of its app_id until their TTL;
--   the delete frees (name, env) for a new application by appending '#deleted-<id>' to the stored
--   name (the unique key (name, env) of DSN-02-14 stays as it is). A deleted application is no
--   longer listed, read, written or matched by an agent Hello.
-- users.deleted_at: DELETE /admin/users/{id} (16 section 11.1 USER_DELETED, ADR-0010 C-136 (3)).
--   The row stays because agent_tokens.issued_by and commands.requested_by reference it (foreign
--   keys without cascade, V001); it is anonymised in the same statement: the user name becomes
--   'deleted-<id>', the password hash is replaced by the hash of a random secret nobody knows, the
--   account is disabled, its kiosk approval and extra permissions are removed and its sessions are
--   revoked. A deleted user is no longer listed or changed; the audit log keeps the old name.

SET search_path TO argos, public;

ALTER TABLE argos.apps ADD COLUMN IF NOT EXISTS app_packages TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE argos.apps ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ NULL;

ALTER TABLE argos.users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ NULL;
