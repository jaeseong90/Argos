#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# First initialisation of the PostgreSQL container (docker-entrypoint-initdb.d, runs once on an
# empty data volume as the postgres user; ../argos-entrypoint.sh installs it): creates the Argos
# roles with deploy/postgres/roles.sql (C-11, ADR-0010 C-97 and C-171 (3)) before the server's
# Flyway runs as the owner:
#   owner "argos"   owns the database and the schema argos (Flyway, ClickHouse migration history)
#   argos_app       server runtime (not a superuser)
#   argos_audit     audit partition retention job
#   argos_backup    read-only dump
#
# The passwords are the Compose secrets and roles.sql the read-only mount of deploy/postgres/roles.sql,
# both staged by ../argos-entrypoint.sh in the private tmpfs /run/argos-init. psql reads each
# password file itself (\set name `cat file`), so no password appears in a process argument, in the
# output or in the PostgreSQL log (roles.sql switches statement logging off for its session). The
# staged files are deleted afterwards.
#
# The image's entrypoint executes this file, or sources it when it is not executable: everything
# runs in a subshell, so neither the options nor an exit leak into the entrypoint, and a failure
# still stops the initialisation.
(
  set -eu
  init_dir=/run/argos-init
  for name in pg_owner_password pg_app_password pg_audit_password pg_backup_password roles.sql; do
    if [ ! -s "$init_dir/$name" ]; then
      echo "10-argos-roles: $init_dir/$name is missing or empty" >&2
      exit 1
    fi
  done
  psql -X -q -v ON_ERROR_STOP=1 --no-password \
    --username "${POSTGRES_USER:-postgres}" --dbname "${POSTGRES_DB:-argos}" <<'SQL'
\set owner_password `cat /run/argos-init/pg_owner_password`
\set app_password `cat /run/argos-init/pg_app_password`
\set audit_password `cat /run/argos-init/pg_audit_password`
\set backup_password `cat /run/argos-init/pg_backup_password`
\i /run/argos-init/roles.sql
\unset owner_password
\unset app_password
\unset audit_password
\unset backup_password
SQL
  rm -f "$init_dir"/pg_owner_password "$init_dir"/pg_app_password \
    "$init_dir"/pg_audit_password "$init_dir"/pg_backup_password "$init_dir"/roles.sql
  echo "10-argos-roles: created the roles argos, argos_app, argos_audit and argos_backup"
)
