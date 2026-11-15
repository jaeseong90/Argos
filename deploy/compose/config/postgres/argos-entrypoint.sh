#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Entrypoint of the PostgreSQL container of the Compose installation (WO-40, C-11).
#
# The official image starts as root and switches to the postgres user before it initialises the
# database and runs /docker-entrypoint-initdb.d. Before the first initialisation only, this script
# (still root) stages what the initialisation needs inside the container, so that the bind mounts
# are read before initdb runs (a read error stops the container while the data volume is still
# empty, and the next start tries again):
#   * the four role passwords: Compose secrets under /run/secrets, which on a Linux Docker Engine
#     belong to uid 10001 (the server reads the same files) with mode 0600, so the postgres user
#     cannot read them there. They are copied into the private tmpfs /run/argos-init (0700, owned
#     by postgres), where init/10-argos-roles.sh reads and deletes them. Nothing is written to disk,
#     printed or passed as an argument;
#   * deploy/postgres/roles.sql (mounted at /argos/roles.sql), copied next to them;
#   * init/10-argos-roles.sh, installed into /docker-entrypoint-initdb.d.
# Then it runs the image's entrypoint unchanged.
set -eu
umask 077

init_dir=/run/argos-init
data_dir=${PGDATA:-/var/lib/postgresql/data}

if [ "$(id -u)" = 0 ] && [ ! -s "$data_dir/PG_VERSION" ]; then
  if [ ! -d "$init_dir" ]; then
    echo "argos-entrypoint: $init_dir is missing (tmpfs of compose.yaml)" >&2
    exit 1
  fi
  for name in pg_owner_password pg_app_password pg_audit_password pg_backup_password; do
    if [ ! -s "/run/secrets/$name" ]; then
      echo "argos-entrypoint: secret $name is missing or empty (run argos-env-init.sh)" >&2
      exit 1
    fi
    cp "/run/secrets/$name" "$init_dir/$name"
  done
  cp /argos/roles.sql "$init_dir/roles.sql"
  chown postgres:postgres "$init_dir" "$init_dir"/*
  chmod 0400 "$init_dir"/*
  chmod 0700 "$init_dir"
  cp /argos/config/init/10-argos-roles.sh /docker-entrypoint-initdb.d/10-argos-roles.sh
  chmod 0755 /docker-entrypoint-initdb.d/10-argos-roles.sh
fi

exec docker-entrypoint.sh "$@"
