#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# restore.sh: restore of a backup set of backup.sh into the Docker Compose installation of Argos
# (docs/17 sections 4.4 and 10, docs/13 section 10, docs/16 sections 8.2 and 10.9, SEC-52,
# SEC-84; WO-43). ./argos-compose.sh restore runs it. Run it as the user that runs
# ./argos-compose.sh (root on a Linux Docker Engine). Procedure: docs/ops/backup-restore.md.
#
# Usage: ./restore.sh SET_DIR [options]
#   SET_DIR                 a backup set directory (argos-backup-...) written by backup.sh
#   --clickhouse            also restore the ClickHouse backup of the set from ARGOS_CH_BACKUP_DIR
#                           (the ClickHouse database is replaced)
#   --master-key FILE       the master key that goes with the backup, when it is not
#                           secrets/master_key; it replaces secrets/master_key (the previous key is
#                           kept as secrets/master_key.pre-restore-<time>, mode 0600)
#   --decrypt-identity FILE the age identity file that decrypts an age-encrypted dump (GnuPG
#                           uses the keyring of this user)
#   --yes                   do not ask for confirmation (required when not run in a terminal)
#   --json                  print one JSON line on standard output at the end (messages go to
#                           standard error)
#   -h, --help              show this text
#
# Order: the set is checked first (manifest, SHA-256 of every file, decryption, the key id of the
# master key against the manifest) and nothing is changed when a check fails. Then the server
# stops, the dump is restored into a staging database argos_restore, and argos-admin secrets
# verify must decrypt its stored secrets with the master key; only then the staging database
# replaces the live one (the live database stays untouched before this point). ClickHouse is then
# replaced (--clickhouse), argos-admin migrate brings an older schema forward, the server starts,
# and the consistency check runs for the days since the backup.
#
# Exit codes: 0 restored (a point-in-time mismatch is a warning); 1 failure (the message says
# what was changed); 2 missing prerequisite or usage error (nothing was done).
set -eu
umask 077
# Git Bash on Windows: keep MSYS from rewriting container paths in arguments.
MSYS_NO_PATHCONV=1
export MSYS_NO_PATHCONV

prog=restore.sh
here=$(cd "$(dirname "$0")" && pwd)
start_dir=$(pwd)
cd "$here"

LOCK_DIR=$here/.backup-restore.lock
# At most this many days are checked for consistency after the restore.
MAX_CHECK_DAYS=7

json=false
say() {
  if $json; then printf '%s\n' "$*" >&2; else printf '%s\n' "$*"; fi
}
die() {
  code=$1
  shift
  printf '%s: %s\n' "$prog" "$*" >&2
  exit "$code"
}
warn() { printf '%s: WARNING: %s\n' "$prog" "$*" >&2; }
usage() { sed -n '/^# Usage:/,/^# Order:/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'; }

# abs_path <path>: relative to the directory restore.sh was started from.
abs_path() {
  case $1 in
    /* | [A-Za-z]:[/\\]*) printf '%s' "$1" ;;
    *) printf '%s/%s' "$start_dir" "$1" ;;
  esac
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum <"$1" | cut -c1-64
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 <"$1" | cut -c1-64
  else
    openssl dgst -sha256 -r <"$1" | cut -c1-64
  fi
}
sha256_stdin() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | cut -c1-64
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 | cut -c1-64
  else
    openssl dgst -sha256 -r | cut -c1-64
  fi
}
have_sha256() {
  command -v sha256sum >/dev/null 2>&1 || command -v shasum >/dev/null 2>&1 \
    || command -v openssl >/dev/null 2>&1
}
bytes_of() { wc -c <"$1" | tr -d ' '; }

# key_kid <key file>: the key id of a master key file (docs/16 section 8.2; backup.sh has the same
# function): SHA-256 of the 32 key bytes, first 8 bytes as 16 hex characters. Fails for a file
# that is not 64 hex characters or base64 of 32 bytes.
key_kid() {
  text=$(tr -d ' \t\r\n' <"$1" 2>/dev/null) || return 1
  case $text in
    '') return 1 ;;
  esac
  if [ "${#text}" -eq 64 ] && ! printf '%s' "$text" | grep -q '[^0-9A-Fa-f]'; then
    fmt=$(printf '%s' "$text" | tr 'A-F' 'a-f' | awk '{
      d = "0123456789abcdef"
      for (i = 1; i <= length($0); i += 2) {
        v = (index(d, substr($0, i, 1)) - 1) * 16 + index(d, substr($0, i + 1, 1)) - 1
        printf "\\%03o", v
      }
    }')
    # shellcheck disable=SC2059 # the format is the octal escapes of the key bytes
    kid=$(printf "$fmt" | sha256_stdin | cut -c1-16)
  else
    b64=$(printf '%s' "$text" | tr -- '-_' '+/')
    case $((${#b64} % 4)) in
      2) b64="$b64==" ;;
      3) b64="$b64=" ;;
    esac
    n=$(printf '%s' "$b64" | base64 -d 2>/dev/null | wc -c | tr -d ' ') || return 1
    [ "$n" = 32 ] || return 1
    kid=$(printf '%s' "$b64" | base64 -d 2>/dev/null | sha256_stdin | cut -c1-16)
  fi
  text=
  fmt=
  b64=
  printf '%s' "$kid"
}

json_str() { printf '"%s"' "$(printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"; }
mf_get() {
  sed -n "s/^ *\"$2\": *\"\{0,1\}\([^\",]*\)\"\{0,1\},\{0,1\}\$/\1/p" "$1" | head -n 1
}

# utc_date <epoch seconds>: yyyy-mm-dd (UTC), without date -d (not POSIX).
utc_date() {
  awk -v t="$1" 'BEGIN {
    z = int(t / 86400) + 719468
    era = int(z / 146097); doe = z - era * 146097
    yoe = int((doe - int(doe / 1460) + int(doe / 36524) - int(doe / 146096)) / 365)
    y = yoe + era * 400; doy = doe - (365 * yoe + int(yoe / 4) - int(yoe / 100))
    mp = int((5 * doy + 2) / 153); d = doy - int((153 * mp + 2) / 5) + 1
    m = mp < 10 ? mp + 3 : mp - 9
    if (m <= 2) y++
    printf "%04d-%02d-%02d\n", y, m, d
  }'
}

# owner_of <path>: uid:gid (ls of one known path; POSIX has no portable stat format).
# shellcheck disable=SC2012
owner_of() { ls -lnd "$1" | awk '{ print $3 ":" $4 }'; }

# ---------------------------------------------------------------------------------------------
# Docker calls (the Compose execution layer; a package installation replaces these functions).

dc() { sh ./argos-compose.sh compose "$@"; }
running() { [ -n "$(dc ps -q "$1" 2>/dev/null || true)" ]; }
admin_java() { sed -n 's/^ADMIN_JAVA="\(.*\)"$/\1/p' argos-compose.sh; }

# pg_list: pg_restore -l of the archive on standard input (reads nothing from the database).
pg_list() { dc exec -T postgres sh -c 'exec pg_restore -l' argos-pg-list; }
# pg_sql <database>: the SQL on standard input as the bootstrap superuser (its password is read in
# the container from the Compose secret).
pg_sql() {
  # shellcheck disable=SC2016 # expanded by the container's shell
  dc exec -T postgres sh -c 'PGPASSWORD=$(cat /run/secrets/pg_superuser_password) && export PGPASSWORD && exec psql -X -q -A -t -v ON_ERROR_STOP=1 -h 127.0.0.1 -U postgres --no-password -d "$1" -f -' argos-pg-sql "$1"
}
pg_put_list() { dc exec -T postgres sh -c 'umask 077 && cat >/tmp/argos-restore.list' argos-pg-putlist; }
# pg_restore_staging: the archive on standard input into argos_restore, in one transaction, with the
# table of contents of pg_put_list.
pg_restore_staging() {
  # shellcheck disable=SC2016 # expanded by the container's shell
  dc exec -T postgres sh -c 'PGPASSWORD=$(cat /run/secrets/pg_superuser_password) && export PGPASSWORD && pg_restore -h 127.0.0.1 -U postgres --no-password -d argos_restore --exit-on-error --single-transaction -L /tmp/argos-restore.list; rc=$?; rm -f /tmp/argos-restore.list; exit $rc' argos-pg-restore
}
# server_run_admin <argos-admin arguments...>: argos-admin in a one-off server container (the
# server itself is stopped), with the key on standard input: it is written to the container's
# private /tmp (tmpfs, owned by the server user, 0600) and named by --argos.master-key-file.
server_verify_staging() {
  # shellcheck disable=SC2016,SC2046 # expanded by the container's shell; the command line is split on purpose
  dc run --rm --no-deps -T -e JAVA_TOOL_OPTIONS= --entrypoint sh server -c 'umask 077 && cat >/tmp/argos-restore-key && exec "$@"' argos-verify \
    $(admin_java) secrets verify --purpose restore --json \
    --argos.postgres.url=jdbc:postgresql://postgres:5432/argos_restore \
    --argos.master-key-file=/tmp/argos-restore-key
}
server_migrate() {
  # shellcheck disable=SC2016,SC2046 # expanded by the container's shell; the command line is split on purpose
  dc run --rm --no-deps -T -e JAVA_TOOL_OPTIONS= --entrypoint sh server -c 'exec "$@"' argos-migrate \
    $(admin_java) migrate </dev/null
}
server_admin() {
  # shellcheck disable=SC2046 # the command line of argos-compose.sh is split into words on purpose
  dc exec -T -e JAVA_TOOL_OPTIONS= server $(admin_java) "$@" </dev/null
}
ch_has_backups() {
  # shellcheck disable=SC2016 # expanded by the container's shell
  dc exec -T -u 0 clickhouse sh -c 'for n in "$@"; do [ -f "/backups/$n/.backup" ] || { echo "$n"; exit 1; }; done' argos-ch-has "$@" </dev/null
}
ch_query() {
  # shellcheck disable=SC2016 # expanded by the container's shell
  dc exec -T clickhouse sh -c 'IFS= read -r CLICKHOUSE_PASSWORD; [ -n "$CLICKHOUSE_PASSWORD" ] && export CLICKHOUSE_PASSWORD && exec clickhouse-client --user "$1" --receive_timeout=86400 --send_timeout=86400 -q "$2"' argos-ch-query "$1" "$3" <"$2"
}
# ch_read <query>: a query as argos_reader, without setting changes (its profile is readonly = 1,
# which refuses the timeouts of ch_query).
ch_read() {
  # shellcheck disable=SC2016 # expanded by the container's shell
  dc exec -T clickhouse sh -c 'IFS= read -r CLICKHOUSE_PASSWORD; [ -n "$CLICKHOUSE_PASSWORD" ] && export CLICKHOUSE_PASSWORD && exec clickhouse-client --user argos_reader -q "$1"' argos-ch-read "$1" <secrets/ch_reader_password
}

# ---------------------------------------------------------------------------------------------

set_dir=
clickhouse=false
key_arg=
identity=
yes=false
while [ $# -gt 0 ]; do
  case $1 in
    --clickhouse) clickhouse=true; shift ;;
    --master-key) [ $# -ge 2 ] || die 2 "--master-key needs a file"; key_arg=$2; shift 2 ;;
    --master-key=*) key_arg=${1#*=}; shift ;;
    --decrypt-identity) [ $# -ge 2 ] || die 2 "--decrypt-identity needs a file"; identity=$2; shift 2 ;;
    --decrypt-identity=*) identity=${1#*=}; shift ;;
    --yes | -y) yes=true; shift ;;
    --json) json=true; shift ;;
    -h | --help) usage; exit 0 ;;
    -*) die 2 "unknown option $1 (see --help)" ;;
    *)
      [ -z "$set_dir" ] || die 2 "one backup set only (got '$set_dir' and '$1')"
      set_dir=$1
      shift
      ;;
  esac
done
[ -n "$set_dir" ] || die 2 "usage: restore.sh SET_DIR [--clickhouse] [--master-key FILE] [--decrypt-identity FILE] [--yes] [--json]"
set_dir=$(abs_path "$set_dir")
[ -d "$set_dir" ] || die 2 "$set_dir is not a directory"
manifest=$set_dir/manifest.json
[ -r "$manifest" ] || die 2 "$set_dir has no readable manifest.json: not a backup set of backup.sh"
have_sha256 || die 2 "a SHA-256 tool is missing: sha256sum, shasum or openssl"

# --- 1. the set (nothing is changed when a check fails) ------------------------------------------------
[ "$(mf_get "$manifest" kind)" = argos-compose-backup ] || die 2 "$manifest is not a manifest of backup.sh"
[ "$(mf_get "$manifest" format)" = 1 ] || die 2 "$manifest has an unknown format (this restore.sh reads format 1)"
pg_file=$(mf_get "$manifest" pg_file)
case $pg_file in
  pg.dump | pg.dump.age | pg.dump.gpg) ;;
  *) die 1 "$manifest names no PostgreSQL dump" ;;
esac
pg_path=$set_dir/$pg_file
[ -f "$pg_path" ] || die 1 "$pg_path is missing"
want_sum=$(mf_get "$manifest" pg_sha256)
want_bytes=$(mf_get "$manifest" pg_bytes)
got_bytes=$(bytes_of "$pg_path")
[ "$got_bytes" = "$want_bytes" ] \
  || die 1 "$pg_file has $got_bytes bytes, the manifest says $want_bytes: the set is damaged; nothing was changed"
got_sum=$(sha256_of "$pg_path")
[ "$got_sum" = "$want_sum" ] \
  || die 1 "the SHA-256 of $pg_file does not match the manifest: the set is damaged; nothing was changed"

enc=$(mf_get "$manifest" encryption)
case $enc in
  none)
    [ -z "$identity" ] || die 2 "--decrypt-identity: the dump of this set is not encrypted"
    ;;
  age)
    command -v age >/dev/null 2>&1 || die 2 "the dump is encrypted with age, which is not installed on this host"
    [ -n "$identity" ] || die 2 "the dump is encrypted with age: give the identity file with --decrypt-identity FILE"
    identity=$(abs_path "$identity")
    [ -r "$identity" ] || die 2 "cannot read the age identity file $identity"
    ;;
  gpg)
    command -v gpg >/dev/null 2>&1 || die 2 "the dump is encrypted with GnuPG, which is not installed on this host"
    [ -z "$identity" ] || die 2 "--decrypt-identity applies to age; GnuPG uses the keyring of this user"
    ;;
  *) die 1 "$manifest names an unknown encryption '$enc'" ;;
esac
decrypt_stream() {
  case $enc in
    none) cat "$pg_path" ;;
    age) age -d -i "$identity" "$pg_path" ;;
    gpg) gpg --quiet --decrypt "$pg_path" ;;
  esac
}

if [ -n "$key_arg" ]; then
  key_file=$(abs_path "$key_arg")
  [ -f "$key_file" ] && [ -r "$key_file" ] || die 2 "cannot read the master key file $key_file"
else
  key_file=$here/secrets/master_key
  [ -r "$key_file" ] \
    || die 2 "cannot read secrets/master_key: run as the user that runs ./argos-compose.sh (root on Linux: sudo), or give the key with --master-key FILE"
fi
cand_kid=$(key_kid "$key_file") \
  || die 2 "$key_file is not a master key (32 bytes as 64 hex characters or base64)"
want_kid=$(mf_get "$manifest" master_key_kid)
if [ -n "$want_kid" ]; then
  [ "$cand_kid" = "$want_kid" ] \
    || die 1 "the master key $key_file (key id $cand_kid) is not the key of this backup (key id $want_kid): restore the key that was kept with it and pass it with --master-key FILE; nothing was changed"
else
  warn "the manifest names no master key id: the key is checked against the restored secrets only"
fi

ch_name=
ch_chain=
if $clickhouse; then
  ch_name=$(mf_get "$manifest" clickhouse_backup)
  ch_chain=$(mf_get "$manifest" clickhouse_chain)
  [ -n "$ch_name" ] || die 2 "--clickhouse: this set has no ClickHouse backup (backup.sh --clickhouse)"
  case "$ch_name $ch_chain" in *[!A-Za-z0-9._\ -]*) die 1 "$manifest names an invalid ClickHouse backup" ;; esac
  [ -n "$ch_chain" ] || ch_chain=$ch_name
  [ -r secrets/ch_migrator_password ] \
    || die 2 "cannot read secrets/ch_migrator_password: run as the user that runs ./argos-compose.sh (root on Linux: sudo)"
fi
replace_key=false
if [ -n "$key_arg" ] && ! { [ -r secrets/master_key ] && cmp -s "$key_file" secrets/master_key; }; then
  replace_key=true
  [ -d secrets ] && [ -w secrets ] \
    || die 2 "cannot write secrets/ to put the master key in place: run as the user that runs ./argos-compose.sh (root on Linux: sudo)"
fi

pg_epoch=$(mf_get "$manifest" created_epoch)
case $pg_epoch in '' | *[!0-9]*) die 1 "$manifest has no creation time" ;; esac
created_at=$(mf_get "$manifest" created_at)

if ! $yes; then
  if [ -t 0 ]; then
    printf 'This replaces the PostgreSQL database%s of this installation with the backup of %s.\n' \
      "$(if $clickhouse; then printf ' and the ClickHouse database'; fi)" "$created_at" >&2
    printf 'Users, apps, tokens, rules and audit rows created after it are lost. Type "restore" to go on: ' >&2
    answer=
    IFS= read -r answer || true
    [ "$answer" = restore ] || die 2 "not confirmed; nothing was changed"
  else
    die 2 "--yes is required when restore.sh does not run in a terminal; nothing was changed"
  fi
fi

command -v docker >/dev/null 2>&1 || die 2 "docker is not installed"
docker info >/dev/null 2>&1 || die 2 "the Docker daemon is not running or this user may not use it"

if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  old=$(cat "$LOCK_DIR/pid" 2>/dev/null || true)
  if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
    die 1 "another backup or restore is running (process $old)"
  fi
  rm -rf "$LOCK_DIR"
  mkdir "$LOCK_DIR" 2>/dev/null || die 1 "another backup or restore is running ($LOCK_DIR)"
fi
printf '%s\n' "$$" >"$LOCK_DIR/pid"
work=$(mktemp -d 2>/dev/null || mktemp -d -t argos-restore)
trap 'rm -rf "$work" "$LOCK_DIR"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# --- 2. read-only checks with the databases ----------------------------------------------------------
if ! running postgres || ! running clickhouse; then
  say "Starting PostgreSQL and ClickHouse..."
  dc up -d --wait postgres clickhouse >&2 || die 1 "PostgreSQL and ClickHouse did not start: ./argos-compose.sh status"
fi
rc=0
{
  dec_rc=0
  decrypt_stream 2>"$work/dec.err" || dec_rc=$?
  printf '%s\n' "$dec_rc" >"$work/dec.rc"
} | pg_list >"$work/toc" 2>"$work/toc.err" || rc=$?
dec_rc=$(cat "$work/dec.rc" 2>/dev/null || echo 1)
if [ "$dec_rc" != 0 ]; then
  sed 's/^/  /' "$work/dec.err" >&2 || true
  die 1 "cannot decrypt $pg_file ($enc, exit code $dec_rc); nothing was changed"
fi
if [ "$rc" != 0 ] || ! grep -q ' TABLE argos flyway_schema_history ' "$work/toc"; then
  sed 's/^/  /' "$work/toc.err" >&2 || true
  die 1 "$pg_file is not a pg_dump archive of the schema argos; nothing was changed"
fi
# The schema argos exists before the restore (it holds the pg_trgm extension, which pg_dump
# --schema leaves out), so its CREATE SCHEMA entry is skipped.
grep -v ' SCHEMA - argos ' "$work/toc" >"$work/toc.restore"

if $clickhouse; then
  rc=0
  # shellcheck disable=SC2086 # names without spaces (checked above)
  missing=$(ch_has_backups $ch_chain 2>/dev/null) || rc=$?
  [ "$rc" = 0 ] \
    || die 1 "the ClickHouse backup ${missing:-$ch_name} is not in ARGOS_CH_BACKUP_DIR (or /backups is not mounted: ./argos-compose.sh up); nothing was changed"
fi

# --- 3. staging database ------------------------------------------------------------------------------
now_stamp=$(date -u +%Y%m%dT%H%M%SZ | tr 'TZ' 'tz')
pre=argos_pre_restore_$now_stamp
server_was_running=false
if running server; then
  server_was_running=true
fi
say "Stopping the server..."
dc stop server >&2 || die 1 "the server did not stop; nothing was changed"

start_server_again() {
  if $server_was_running; then
    dc up -d --wait server >&2 || warn "the server did not start again: ./argos-compose.sh up"
  fi
}
drop_staging() {
  printf 'DROP DATABASE IF EXISTS argos_restore;\n' | pg_sql postgres >/dev/null 2>&1 || true
}
fail_before_swap() {
  drop_staging
  start_server_again
  die 1 "$1; the live database was not changed"
}

say "Restoring PostgreSQL into the staging database argos_restore..."
if ! pg_sql postgres >"$work/sql.out" 2>&1 <<'SQL'
DROP DATABASE IF EXISTS argos_restore;
CREATE DATABASE argos_restore OWNER argos TEMPLATE template0;
REVOKE ALL ON DATABASE argos_restore FROM PUBLIC;
GRANT CONNECT ON DATABASE argos_restore TO argos_app, argos_audit, argos_backup;
SQL
then
  sed 's/^/  /' "$work/sql.out" >&2 || true
  fail_before_swap "cannot create the staging database argos_restore"
fi
if ! pg_sql argos_restore >"$work/sql.out" 2>&1 <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA argos AUTHORIZATION argos;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA argos;
SQL
then
  sed 's/^/  /' "$work/sql.out" >&2 || true
  fail_before_swap "cannot prepare the schema argos in argos_restore"
fi
pg_put_list <"$work/toc.restore" >/dev/null 2>&1 || fail_before_swap "cannot hand the table of contents to the PostgreSQL container"
rc=0
{
  dec_rc=0
  decrypt_stream 2>"$work/dec.err" || dec_rc=$?
  printf '%s\n' "$dec_rc" >"$work/dec.rc"
} | pg_restore_staging >"$work/restore.out" 2>&1 || rc=$?
dec_rc=$(cat "$work/dec.rc" 2>/dev/null || echo 1)
if [ "$dec_rc" != 0 ] || [ "$rc" != 0 ]; then
  sed 's/^/  /' "$work/dec.err" "$work/restore.out" >&2 || true
  fail_before_swap "pg_restore into argos_restore failed (exit code $rc)"
fi

# --- 4. the master key decrypts the restored secrets (SEC-52) ------------------------------------------
say "Checking the master key against the restored secrets (argos-admin secrets verify)..."
rc=0
verify=$(server_verify_staging <"$key_file" 2>"$work/verify.err") || rc=$?
verify=$(printf '%s\n' "$verify" | grep '^{' | tail -n 1)
if [ "$rc" != 0 ]; then
  failed=$(printf '%s\n' "$verify" | sed -n 's/.*"failed":\([0-9]*\)[,}].*/\1/p' | head -n 1)
  if [ -n "$failed" ]; then
    printf '  %s stored secret(s) do not decrypt with the key %s\n' "$failed" "$cand_kid" >&2
  else
    grep -v '^{' "$work/verify.err" | tail -n 5 | sed 's/^/  /' >&2 || true
  fi
  fail_before_swap "the master key does not decrypt the secrets of the backup (argos-admin secrets verify exit code $rc)"
fi
case $verify in
  *'"verifiable":false'*) warn "the backup holds no encrypted secret: nothing could prove the master key" ;;
esac

# --- 5. swap (one transaction: both renames or none) ---------------------------------------------------
say "Replacing the live database (the previous one is kept as $pre until the restore is done)..."
if ! pg_sql postgres >"$work/sql.out" 2>&1 <<SQL
SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity
 WHERE datname IN ('argos', 'argos_restore') AND pid <> pg_backend_pid();
BEGIN;
ALTER DATABASE argos RENAME TO $pre;
ALTER DATABASE argos_restore RENAME TO argos;
COMMIT;
SQL
then
  sed 's/^/  /' "$work/sql.out" >&2 || true
  fail_before_swap "cannot rename the databases"
fi

if $replace_key; then
  old_copy=secrets/master_key.pre-restore-$now_stamp
  if [ -f secrets/master_key ]; then
    cp -p secrets/master_key "$old_copy"
    chmod 0600 "$old_copy"
  fi
  owner=
  [ ! -f secrets/master_key ] || owner=$(owner_of secrets/master_key)
  cat "$key_file" >secrets/.master_key.new
  chmod 0600 secrets/.master_key.new
  if [ -n "$owner" ] && [ "$(id -u)" = 0 ]; then
    chown "$owner" secrets/.master_key.new
  fi
  mv secrets/.master_key.new secrets/master_key
  say "secrets/master_key is now the key of the backup (key id $cand_kid); the previous key is $old_copy."
fi

# --- 6. ClickHouse -------------------------------------------------------------------------------------
ch_failed() {
  die 1 "PostgreSQL is restored (the previous database is kept as $pre), but the ClickHouse restore failed: $1. The server is stopped. Run the same restore.sh command again, or start without the ClickHouse data with ./argos-compose.sh up (the server creates empty tables)"
}
if $clickhouse; then
  say "Restoring ClickHouse from $ch_name..."
  rc=0
  ch_query argos_migrator secrets/ch_migrator_password "DROP DATABASE IF EXISTS argos SYNC" >"$work/ch.out" 2>&1 || rc=$?
  if [ "$rc" != 0 ]; then
    sed 's/^/  /' "$work/ch.out" >&2 || true
    ch_failed "DROP DATABASE argos (exit code $rc)"
  fi
  rc=0
  ch_query argos_migrator secrets/ch_migrator_password "RESTORE DATABASE argos FROM Disk('backups', '$ch_name')" >"$work/ch.out" 2>&1 || rc=$?
  if [ "$rc" != 0 ] || ! grep -q RESTORED "$work/ch.out"; then
    sed 's/^/  /' "$work/ch.out" >&2 || true
    ch_failed "RESTORE DATABASE argos (exit code $rc)"
  fi
fi

# --- 7. schema forward, point in time, start -----------------------------------------------------------
say "Applying the schema migrations of this server version (argos-admin migrate)..."
rc=0
server_migrate >"$work/migrate.out" 2>&1 || rc=$?
if [ "$rc" != 0 ]; then
  grep -v '^{"' "$work/migrate.out" | tail -n 10 | sed 's/^/  /' >&2 || true
  die 1 "argos-admin migrate failed (exit code $rc): PostgreSQL is restored (the previous database is kept as $pre) and the server is stopped"
fi

mismatch=false
threshold=$pg_epoch
if $clickhouse; then
  e=$(mf_get "$manifest" clickhouse_created_epoch)
  case $e in '' | *[!0-9]*) ;; *) threshold=$e ;; esac
fi
rc=0
ch_max=$(ch_read "SELECT toUnixTimestamp(max(ts)) FROM argos.xlog" 2>/dev/null | tr -d '\r' | head -n 1) || rc=$?
case $ch_max in
  '' | *[!0-9]*) warn "could not read the newest ClickHouse transaction time: the point in time was not compared" ;;
  *)
    if [ "$rc" = 0 ] && [ "$ch_max" -gt "$threshold" ]; then
      mismatch=true
      warn "point-in-time mismatch: ClickHouse holds transactions up to $(utc_date "$ch_max"), newer than this PostgreSQL backup of $created_at. Data of apps and instances created after the backup stays under ids that no app holds (their agents register under new ids), and agent tokens issued after the backup are gone: issue them again (./argos-compose.sh token create). Dictionary entries come back when the agents reconnect."
    fi
    ;;
esac

# Ids of apps and instances that only ClickHouse still knows (created after the backup, or kept by
# an older ClickHouse restore) must never be handed out again: a new app would show their data.
# The id sequences move past the largest ids of the hourly rollups (the longest-kept tables; every
# raw row reaches them through their materialized views) and of the 1h metrics.
rc=0
ch_ids=$(ch_read "SELECT greatest((SELECT max(app_id) FROM argos.app_stat_1h), (SELECT max(app_id) FROM argos.metric_1h), (SELECT max(app_id) FROM argos.sql_stat_1h), (SELECT max(app_id) FROM argos.ext_stat_1h)), greatest((SELECT max(instance_id) FROM argos.instance_stat_1h), (SELECT max(instance_id) FROM argos.metric_1h)) FORMAT TSV" 2>/dev/null \
  | tr -d '\r' | head -n 1) || rc=$?
ch_app=$(printf '%s\n' "$ch_ids" | cut -f 1)
ch_inst=$(printf '%s\n' "$ch_ids" | cut -f 2)
case "$rc:$ch_app:$ch_inst" in
  0:[0-9]*:[0-9]*)
    case "$ch_app$ch_inst" in *[!0-9]*) ch_app= ;; esac
    ;;
  *) ch_app= ;;
esac
if [ -z "$ch_app" ]; then
  warn "could not read the largest app and instance ids of ClickHouse: the id sequences were not checked. If ClickHouse holds data newer than this backup, new apps may get the id of an app that is gone"
else
  # setval only when the next value of a sequence would reuse an id ClickHouse holds; prints one
  # line per moved sequence.
  if ! pg_sql argos >"$work/seq.out" 2>&1 <<SQL
SELECT 'apps ' || setval('argos.apps_id_seq', $ch_app, true) FROM argos.apps_id_seq
 WHERE $ch_app >= CASE WHEN is_called THEN last_value + 1 ELSE last_value END;
SELECT 'instances ' || setval('argos.instances_id_seq', $ch_inst, true) FROM argos.instances_id_seq
 WHERE $ch_inst >= CASE WHEN is_called THEN last_value + 1 ELSE last_value END;
SQL
  then
    sed 's/^/  /' "$work/seq.out" >&2 || true
    warn "could not move the app and instance id sequences past the ids of ClickHouse ($ch_app, $ch_inst): new apps may get the id of an app that is gone"
  else
    while read -r seq_name seq_value; do
      [ -n "${seq_name:-}" ] || continue
      say "The $seq_name id sequence now continues after $seq_value (ids ClickHouse still holds are not reused)."
    done <"$work/seq.out"
  fi
fi

say "Starting the server..."
if $replace_key; then
  dc up -d --wait --force-recreate server >&2 || die 1 "the server did not become healthy: ./argos-compose.sh logs server (the previous database is kept as $pre)"
else
  dc up -d --wait server >&2 || die 1 "the server did not become healthy: ./argos-compose.sh logs server (the previous database is kept as $pre)"
fi

# --- 8. consistency check of the days since the backup ------------------------------------------------
today=$(utc_date "$(date -u +%s)")
day_epoch=$((pg_epoch / 86400 * 86400))
checked=0
consistency=ok
while [ "$checked" -lt "$MAX_CHECK_DAYS" ]; do
  day=$(utc_date "$day_epoch")
  [ "$day" != "$today" ] || break
  rc=0
  out=$(server_admin consistency check --date "$day" 2>/dev/null) || rc=$?
  printf '%s\n' "$out" | sed "s/^/  consistency $day: /" >&2
  if [ "$rc" != 0 ]; then
    consistency=mismatch
    warn "the consistency check of $day reported a mismatch or failed (exit code $rc): ./argos-compose.sh compose exec -T server ... admin consistency check --date $day --rebuild (docs/ops/admin-cli.md)"
  fi
  checked=$((checked + 1))
  day_epoch=$((day_epoch + 86400))
done
[ "$checked" -gt 0 ] || consistency=skipped

if printf 'DROP DATABASE IF EXISTS %s;\n' "$pre" | pg_sql postgres >/dev/null 2>&1; then
  dropped=true
else
  dropped=false
  warn "could not drop the previous database $pre: drop it with psql when you no longer need it"
fi

say "Restored the backup of $created_at$(if $clickhouse; then printf ' (PostgreSQL and ClickHouse)'; fi)."
say "Users may have to sign in again: sessions opened after the backup are gone, and on a new host the server has a new JWT key (/data/jwt.key is not part of a backup)."
if $json; then
  printf '{"set":%s,"clickhouse":%s,"master_key_replaced":%s,"point_in_time_mismatch":%s,"consistency":"%s","previous_database_dropped":%s}\n' \
    "$(json_str "$set_dir")" "$clickhouse" "$replace_key" "$mismatch" "$consistency" "$dropped"
fi
