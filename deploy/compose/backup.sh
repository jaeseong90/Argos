#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# backup.sh: backup of the Docker Compose installation of Argos (docs/17 sections 4.4 and 10,
# docs/13 section 10, docs/16 section 10.9, SEC-84; WO-43). ./argos-compose.sh backup runs it; it
# can also run on its own from any directory, as the user that runs ./argos-compose.sh (root on a
# Linux Docker Engine). Procedure, contents and restore: docs/ops/backup-restore.md.
#
# Usage: ./backup.sh [options]
#   --dir DIR            directory of the backup sets (default: ARGOS_BACKUP_DIR of .env, else
#                        ./backups), created with mode 0700. Put it on another disk or a mount.
#   --encrypt age:RECIPIENT | gpg:KEYID
#                        encrypt the PostgreSQL dump on its way to the disk with age (an age
#                        public key, or a file of recipients) or GnuPG (a public key of the
#                        keyring). The tool must already be installed on this host: it is never
#                        downloaded. No unencrypted copy is written.
#   --clickhouse         also back up the ClickHouse database to ARGOS_CH_BACKUP_DIR (an external
#                        disk or mount, refused on the disk of the ClickHouse data), incremental
#                        on the previous ClickHouse backup until its full backup is keep-days - 1
#                        days old
#   --keep-days N        then delete the backup sets older than N days, 1 to 3650 (default:
#                        ARGOS_BACKUP_KEEP_DAYS of .env, else 7); a set that a kept ClickHouse
#                        incremental backup builds on is kept
#   --label LABEL        suffix of the set name: 1 to 32 characters A-Z a-z 0-9 . _ -
#   --json               print one JSON line on standard output at the end (messages go to
#                        standard error): {"set","files","bytes","clickhouse"}
#   -h, --help           show this text
#
# A backup set is a directory argos-backup-<UTC yyyymmddTHHMMSSZ>[-LABEL] (mode 0700) with
# pg.dump (pg_dump -Fc --schema=argos as argos_backup; pg.dump.age or pg.dump.gpg when encrypted)
# and manifest.json (server image and version, Flyway version, master key id, checksums,
# ClickHouse backup name), each mode 0600. It holds personal data, command results and secrets
# encrypted with the master key, never the key itself: keep secrets/master_key and tls/ apart.
#
# Exit codes: 0 done; 1 failure (no new set is left behind); 2 missing prerequisite or usage
# error (nothing was done).
set -eu
umask 077
# Git Bash on Windows: keep MSYS from rewriting container paths in arguments.
MSYS_NO_PATHCONV=1
export MSYS_NO_PATHCONV

prog=backup.sh
here=$(cd "$(dirname "$0")" && pwd)
cd "$here"

DEFAULT_KEEP_DAYS=7
# The ClickHouse image's user (uid 101) owns the backup mount; ClickHouse writes it.
LOCK_DIR=$here/.backup-restore.lock

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
usage() { sed -n '/^# Usage:/,/^# Exit codes:/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'; }

# env_value <NAME> [default]: the environment wins over .env (as in docker compose).
env_value() {
  eval "is_set=\${$1+x}"
  if [ -n "$is_set" ]; then
    eval "printf '%s' \"\$$1\""
    return 0
  fi
  if [ -r .env ]; then
    line=$(grep -E "^[[:space:]]*(export[[:space:]]+)?$1=" .env | tail -n 1 || true)
    if [ -n "$line" ]; then
      value=${line#*=}
      value=$(printf '%s' "$value" | sed -e 's/[[:space:]]*$//' -e 's/^[[:space:]]*//' \
        -e 's/^"\(.*\)"$/\1/' -e "s/^'\\(.*\\)'\$/\\1/")
      printf '%s' "$value"
      return 0
    fi
  fi
  printf '%s' "${2:-}"
}

# sha256_of <file>: the hex SHA-256 of a file.
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

# key_kid <key file>: the key id of a master key file, SHA-256 of the 32 key bytes, first 8 bytes
# as 16 hex characters (docs/16 section 8.2; io.argos.server.secrets.MasterKey). The key is 64 hex
# characters or base64/base64url; it never leaves this function except as its hash.
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

# json_str <value>: a JSON string literal.
json_str() { printf '"%s"' "$(printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"; }

# mf_get <manifest> <key>: a top-level string or number field of a manifest written by this script
# (one field per line).
mf_get() {
  sed -n "s/^ *\"$2\": *\"\{0,1\}\([^\",]*\)\"\{0,1\},\{0,1\}\$/\1/p" "$1" | head -n 1
}

# ---------------------------------------------------------------------------------------------
# Docker calls (the Compose execution layer; a package installation replaces these functions).

dc() { sh ./argos-compose.sh compose "$@"; }
running() { [ -n "$(dc ps -q "$1" 2>/dev/null || true)" ]; }
# The argos-admin command line of argos-compose.sh (the image's Java and classpath).
admin_java() { sed -n 's/^ADMIN_JAVA="\(.*\)"$/\1/p' argos-compose.sh; }

# pg_dump of the schema argos as argos_backup, inside the PostgreSQL container: the password is read
# there from the Compose secret, never passed through this host's command lines.
pg_dump_stream() {
  # shellcheck disable=SC2016 # expanded by the container's shell
  dc exec -T postgres sh -c 'PGPASSWORD=$(cat /run/secrets/pg_backup_password) && export PGPASSWORD && exec pg_dump -h 127.0.0.1 -U argos_backup -d argos --no-password -Fc --schema=argos' argos-pg-dump </dev/null
}
pg_flyway_version() {
  # shellcheck disable=SC2016 # expanded by the container's shell
  dc exec -T postgres sh -c 'PGPASSWORD=$(cat /run/secrets/pg_backup_password) && export PGPASSWORD && exec psql -X -q -A -t -h 127.0.0.1 -U argos_backup -d argos --no-password -c "SELECT version FROM argos.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"' argos-pg-flyway </dev/null
}
server_admin() {
  # shellcheck disable=SC2046 # the command line of argos-compose.sh is split into words on purpose
  dc exec -T -e JAVA_TOOL_OPTIONS= server $(admin_java) "$@" </dev/null
}
# ch_prepare: in the ClickHouse container as root: /backups must be a mount of its own; it is given
# to the clickhouse user (0700); prints the device numbers of the data directory and of /backups.
ch_prepare() {
  # shellcheck disable=SC2016 # expanded by the container's shell
  dc exec -T -u 0 clickhouse sh -c 'awk '"'"'$5 == "/backups" { found = 1 } END { exit !found }'"'"' /proc/self/mountinfo || exit 3
chown clickhouse:clickhouse /backups && chmod 0700 /backups || exit 4
printf "%s %s\n" "$(stat -c %d /var/lib/clickhouse)" "$(stat -c %d /backups)"' argos-ch-prepare </dev/null
}
# ch_list: the names of the complete ClickHouse backups under /backups.
ch_list() {
  # shellcheck disable=SC2016 # expanded by the container's shell
  dc exec -T -u 0 clickhouse sh -c 'for d in /backups/*/; do [ -f "$d.backup" ] && basename "$d"; done; exit 0' argos-ch-list </dev/null
}
ch_remove() {
  # shellcheck disable=SC2016 # expanded by the container's shell
  dc exec -T -u 0 clickhouse sh -c 'for n in "$@"; do rm -rf "/backups/$n"; done' argos-ch-remove "$@" </dev/null
}
# ch_query <account> <password file> <query>: clickhouse-client in the ClickHouse container; the
# password goes through standard input into the client's environment, never into an argument.
ch_query() {
  # shellcheck disable=SC2016 # expanded by the container's shell
  dc exec -T clickhouse sh -c 'IFS= read -r CLICKHOUSE_PASSWORD; [ -n "$CLICKHOUSE_PASSWORD" ] && export CLICKHOUSE_PASSWORD && exec clickhouse-client --user "$1" --receive_timeout=86400 --send_timeout=86400 -q "$2"' argos-ch-query "$1" "$3" <"$2"
}

# ---------------------------------------------------------------------------------------------

dir=
encrypt=
clickhouse=false
keep_days=
label=
while [ $# -gt 0 ]; do
  case $1 in
    --dir) [ $# -ge 2 ] || die 2 "--dir needs a directory"; dir=$2; shift 2 ;;
    --dir=*) dir=${1#*=}; shift ;;
    --encrypt) [ $# -ge 2 ] || die 2 "--encrypt needs age:RECIPIENT or gpg:KEYID"; encrypt=$2; shift 2 ;;
    --encrypt=*) encrypt=${1#*=}; shift ;;
    --clickhouse) clickhouse=true; shift ;;
    --keep-days) [ $# -ge 2 ] || die 2 "--keep-days needs a number of days"; keep_days=$2; shift 2 ;;
    --keep-days=*) keep_days=${1#*=}; shift ;;
    --label) [ $# -ge 2 ] || die 2 "--label needs a name"; label=$2; shift 2 ;;
    --label=*) label=${1#*=}; shift ;;
    --json) json=true; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die 2 "unknown option $1 (see --help)" ;;
  esac
done

case $label in
  '') ;;
  *[!A-Za-z0-9._-]*) die 2 "--label: 1 to 32 characters A-Z a-z 0-9 . _ -" ;;
  *) [ "${#label}" -le 32 ] || die 2 "--label: at most 32 characters" ;;
esac
[ -n "$keep_days" ] || keep_days=$(env_value ARGOS_BACKUP_KEEP_DAYS "$DEFAULT_KEEP_DAYS")
[ -n "$keep_days" ] || keep_days=$DEFAULT_KEEP_DAYS
case $keep_days in
  *[!0-9]* | 0*) die 2 "--keep-days (ARGOS_BACKUP_KEEP_DAYS) must be a whole number of days from 1 to 3650" ;;
esac
[ "$keep_days" -le 3650 ] || die 2 "--keep-days (ARGOS_BACKUP_KEEP_DAYS) must be at most 3650"

enc_tool=none
enc_to=
case $encrypt in
  '') ;;
  age:?*)
    enc_tool=age
    enc_to=${encrypt#age:}
    command -v age >/dev/null 2>&1 \
      || die 2 "--encrypt age: the age tool is not installed on this host (install it from your distribution; backup.sh never downloads tools)"
    ;;
  gpg:?*)
    enc_tool=gpg
    enc_to=${encrypt#gpg:}
    command -v gpg >/dev/null 2>&1 \
      || die 2 "--encrypt gpg: GnuPG (gpg) is not installed on this host (install it from your distribution; backup.sh never downloads tools)"
    gpg --batch --list-keys -- "$enc_to" >/dev/null 2>&1 \
      || die 2 "--encrypt gpg: no public key '$enc_to' in the keyring of this user (gpg --import it first)"
    ;;
  *) die 2 "--encrypt must be age:RECIPIENT or gpg:KEYID" ;;
esac
case $enc_to in
  *'
'*) die 2 "--encrypt: the recipient must be one line" ;;
esac

have_sha256 || die 2 "a SHA-256 tool is missing: sha256sum, shasum or openssl"

ch_dir=
if $clickhouse; then
  ch_dir=$(env_value ARGOS_CH_BACKUP_DIR)
  [ -n "$ch_dir" ] \
    || die 2 "--clickhouse needs ARGOS_CH_BACKUP_DIR in .env: a directory on another disk or a mount (then ./argos-compose.sh up; docs/ops/backup-restore.md)"
  [ -r secrets/ch_migrator_password ] \
    || die 2 "cannot read secrets/ch_migrator_password: run as the user that runs ./argos-compose.sh (root on Linux: sudo)"
fi

[ -n "$dir" ] || dir=$(env_value ARGOS_BACKUP_DIR)
[ -n "$dir" ] || dir=./backups

command -v docker >/dev/null 2>&1 || die 2 "docker is not installed"
docker info >/dev/null 2>&1 || die 2 "the Docker daemon is not running or this user may not use it"
running postgres || die 2 "the postgres service is not running: ./argos-compose.sh up"
if $clickhouse; then
  running clickhouse || die 2 "the clickhouse service is not running: ./argos-compose.sh up"
fi

mkdir -p "$dir" 2>/dev/null || die 2 "cannot create the backup directory $dir"
chmod 0700 "$dir" 2>/dev/null || die 2 "cannot set mode 0700 on the backup directory $dir"
dir=$(cd "$dir" && pwd)

# One backup or restore at a time (a lock directory; a lock of a process that no longer runs is
# taken over).
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  old=$(cat "$LOCK_DIR/pid" 2>/dev/null || true)
  if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
    die 1 "another backup or restore is running (process $old)"
  fi
  rm -rf "$LOCK_DIR"
  mkdir "$LOCK_DIR" 2>/dev/null || die 1 "another backup or restore is running ($LOCK_DIR)"
fi
printf '%s\n' "$$" >"$LOCK_DIR/pid"

work=$(mktemp -d 2>/dev/null || mktemp -d -t argos-backup)
partial=
ch_created=
done_ok=false
cleanup() {
  if ! $done_ok; then
    [ -z "$partial" ] || rm -rf "$partial"
    if [ -n "$ch_created" ]; then
      ch_remove "$ch_created" >/dev/null 2>&1 || true
    fi
  fi
  rm -rf "$work" "$LOCK_DIR"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

now=$(date -u '+%Y%m%dT%H%M%SZ %Y-%m-%dT%H:%M:%SZ %s')
# shellcheck disable=SC2086 # three words
set -- $now
stamp=$1
created_at=$2
created_epoch=$3
name=argos-backup-$stamp
[ -z "$label" ] || name=$name-$label
[ ! -e "$dir/$name" ] || die 1 "the backup set $dir/$name exists already"

# --- ClickHouse checks before anything is written (docs/17 section 10: never the data disk) ----
ch_base=
ch_chain=
ch_root_epoch=
if $clickhouse; then
  rc=0
  devs=$(ch_prepare 2>"$work/ch.err") || rc=$?
  case $rc in
    0) ;;
    3) die 2 "/backups is not mounted in the clickhouse container: ARGOS_CH_BACKUP_DIR was set after the stack started; run ./argos-compose.sh up" ;;
    *)
      sed 's/^/  /' "$work/ch.err" >&2 || true
      die 1 "cannot prepare /backups in the clickhouse container (exit code $rc)"
      ;;
  esac
  # shellcheck disable=SC2086 # two numbers
  set -- $devs
  [ $# -eq 2 ] || die 1 "cannot read the devices of the ClickHouse data and of /backups"
  if [ "$1" = "$2" ]; then
    die 1 "ARGOS_CH_BACKUP_DIR ($ch_dir) is on the same disk as the ClickHouse data (device $1): a backup there is lost with the data. Use another disk or a mount (docs/17 section 10)"
  fi
  # The newest set with a complete ClickHouse backup is the base while its chain's full backup is
  # younger than keep-days - 1 days; otherwise this backup is a full one.
  ch_list >"$work/ch.list" 2>/dev/null || die 1 "cannot list the ClickHouse backups in /backups"
  best_epoch=0
  for m in "$dir"/argos-backup-*/manifest.json; do
    [ -f "$m" ] || continue
    n=$(mf_get "$m" clickhouse_backup)
    e=$(mf_get "$m" created_epoch)
    case $n in '' | *[!A-Za-z0-9._-]*) continue ;; esac
    case $e in '' | *[!0-9]*) continue ;; esac
    grep -qxF -- "$n" "$work/ch.list" || continue
    if [ "$e" -gt "$best_epoch" ]; then
      best_epoch=$e
      ch_base=$n
      ch_chain=$(mf_get "$m" clickhouse_chain)
      ch_root_epoch=$(mf_get "$m" clickhouse_chain_root_epoch)
    fi
  done
  if [ -n "$ch_base" ]; then
    case $ch_root_epoch in '' | *[!0-9]*) ch_root_epoch=0 ;; esac
    if [ $((created_epoch - ch_root_epoch)) -gt $(((keep_days - 1) * 86400)) ] || [ -z "$ch_chain" ]; then
      ch_base=
    fi
  fi
  if [ -n "$ch_base" ]; then
    ch_chain="$ch_chain $name"
  else
    ch_chain=$name
    ch_root_epoch=$created_epoch
  fi
fi

partial=$dir/.$name.partial
rm -rf "$partial"
mkdir "$partial"
chmod 0700 "$partial"

# --- PostgreSQL (mandatory) -----------------------------------------------------------------------
pg_file=pg.dump
case $enc_tool in
  age) pg_file=pg.dump.age ;;
  gpg) pg_file=pg.dump.gpg ;;
esac
encrypt_stream() {
  case $enc_tool in
    none) cat ;;
    age)
      if [ -f "$enc_to" ]; then age -R "$enc_to"; else age -r "$enc_to"; fi
      ;;
    gpg) gpg --batch --yes --quiet --trust-model always --recipient "$enc_to" --encrypt --output - ;;
  esac
}
say "Backing up PostgreSQL (pg_dump of the schema argos)..."
enc_rc=0
{
  dump_rc=0
  pg_dump_stream 2>"$work/dump.err" || dump_rc=$?
  printf '%s\n' "$dump_rc" >"$work/dump.rc"
} | encrypt_stream >"$partial/$pg_file" 2>"$work/enc.err" || enc_rc=$?
dump_rc=$(cat "$work/dump.rc" 2>/dev/null || echo 1)
if [ "$dump_rc" != 0 ]; then
  sed 's/^/  /' "$work/dump.err" >&2 || true
  die 1 "pg_dump failed (exit code $dump_rc); no backup set was written"
fi
if [ "$enc_rc" != 0 ]; then
  sed 's/^/  /' "$work/enc.err" >&2 || true
  die 1 "$enc_tool failed (exit code $enc_rc); no backup set was written"
fi
[ -s "$partial/$pg_file" ] || die 1 "pg_dump wrote nothing; no backup set was written"
if [ "$enc_tool" = none ]; then
  magic=$(dd if="$partial/$pg_file" bs=5 count=1 2>/dev/null)
  [ "$magic" = PGDMP ] || die 1 "the dump is not a pg_dump custom-format archive; no backup set was written"
fi
chmod 0600 "$partial/$pg_file"

flyway=$(pg_flyway_version 2>/dev/null | tr -d '\r' | head -n 1) || flyway=
case $flyway in
  *[!0-9A-Za-z._]*) flyway= ;;
esac
[ -n "$flyway" ] || warn "could not read the Flyway version of the database"

# --- master key id, server image -------------------------------------------------------------------
kid=
prev_kid=
verifiable=
if running server; then
  rc=0
  out=$(server_admin secrets verify --purpose backup --json 2>"$work/verify.err") || rc=$?
  kid=$(printf '%s\n' "$out" | sed -n 's/.*"kid":"\([0-9a-f]\{16\}\)".*/\1/p' | head -n 1)
  prev_kid=$(printf '%s\n' "$out" | sed -n 's/.*"previous_kid":"\([0-9a-f]\{16\}\)".*/\1/p' | head -n 1)
  case $out in
    *'"verifiable":true'*) verifiable=true ;;
    *'"verifiable":false'*) verifiable=false ;;
  esac
  failed=$(printf '%s\n' "$out" | sed -n 's/.*"failed":\([0-9]*\)[,}].*/\1/p' | head -n 1)
  if [ "$rc" != 0 ] && [ -n "$kid" ]; then
    warn "the server's master key (key id $kid) does not decrypt ${failed:-some} stored secret(s) (argos-admin secrets verify exit code $rc): this backup keeps them, but a restore with this key cannot read them"
  elif [ "$rc" != 0 ]; then
    warn "argos-admin secrets verify failed in the server container (exit code $rc)"
  fi
fi
if [ -z "$kid" ]; then
  if [ -r secrets/master_key ] && kid=$(key_kid secrets/master_key); then
    warn "the key id was computed from secrets/master_key (the server did not report it)"
  else
    kid=
    warn "the master key id is unknown (secrets/master_key cannot be read): restore.sh cannot check the key before it starts"
  fi
fi

image=
image_id=
image_digest=
version=
if cid=$(dc ps -q server 2>/dev/null) && [ -n "$cid" ]; then
  image=$(docker inspect --format '{{.Config.Image}}' "$cid" 2>/dev/null || true)
  image_id=$(docker inspect --format '{{.Image}}' "$cid" 2>/dev/null || true)
fi
if [ -z "$image" ]; then
  image=$(env_value ARGOS_SERVER_IMAGE)
  if [ -z "$image" ]; then
    # shellcheck disable=SC2016 # the literal ${ARGOS_SERVER_IMAGE:-...} of compose.yaml
    image=$(sed -n 's/^ *image: \${ARGOS_SERVER_IMAGE:-\([^}]*\)}.*$/\1/p' compose.yaml | head -n 1)
  fi
fi
if [ -n "$image" ]; then
  [ -n "$image_id" ] || image_id=$(docker image inspect --format '{{.Id}}' "$image" 2>/dev/null || true)
  image_digest=$(docker image inspect --format '{{range .RepoDigests}}{{.}} {{end}}' "$image" 2>/dev/null | awk '{ print $1 }' || true)
  version=$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' "$image" 2>/dev/null || true)
  [ "$version" != "<no value>" ] || version=
fi

# --- ClickHouse (optional) ----------------------------------------------------------------------------
ch_epoch=
if $clickhouse; then
  query="BACKUP DATABASE argos TO Disk('backups', '$name')"
  if [ -n "$ch_base" ]; then
    query="$query SETTINGS base_backup = Disk('backups', '$ch_base')"
    say "Backing up ClickHouse to ARGOS_CH_BACKUP_DIR as $name (incremental on $ch_base)..."
  else
    say "Backing up ClickHouse to ARGOS_CH_BACKUP_DIR as $name (full backup)..."
  fi
  ch_created=$name
  rc=0
  out=$(ch_query argos_migrator secrets/ch_migrator_password "$query" 2>"$work/ch.err") || rc=$?
  case $out in
    *BACKUP_CREATED*) ;;
    *)
      sed 's/^/  /' "$work/ch.err" >&2 || true
      die 1 "the ClickHouse backup failed (exit code $rc); no backup set was written"
      ;;
  esac
  ch_epoch=$(date -u +%s)
fi

# --- manifest -----------------------------------------------------------------------------------------
sum=$(sha256_of "$partial/$pg_file")
size=$(bytes_of "$partial/$pg_file")
{
  printf '{\n'
  printf '  "format": 1,\n'
  printf '  "kind": "argos-compose-backup",\n'
  printf '  "name": %s,\n' "$(json_str "$name")"
  printf '  "created_at": "%s",\n' "$created_at"
  printf '  "created_epoch": %s,\n' "$created_epoch"
  printf '  "label": %s,\n' "$(json_str "$label")"
  printf '  "server_image": %s,\n' "$(json_str "$image")"
  printf '  "server_image_id": %s,\n' "$(json_str "$image_id")"
  printf '  "server_image_digest": %s,\n' "$(json_str "$image_digest")"
  printf '  "server_version": %s,\n' "$(json_str "$version")"
  printf '  "flyway_version": %s,\n' "$(json_str "$flyway")"
  printf '  "master_key_kid": %s,\n' "$(json_str "$kid")"
  printf '  "master_key_previous_kid": %s,\n' "$(json_str "$prev_kid")"
  printf '  "master_key_verifiable": %s,\n' "$(json_str "$verifiable")"
  printf '  "encryption": "%s",\n' "$enc_tool"
  printf '  "encryption_recipient": %s,\n' "$(json_str "$enc_to")"
  printf '  "pg_file": "%s",\n' "$pg_file"
  printf '  "pg_sha256": "%s",\n' "$sum"
  printf '  "pg_bytes": %s,\n' "$size"
  if $clickhouse; then
    printf '  "clickhouse_backup": "%s",\n' "$name"
    printf '  "clickhouse_base": %s,\n' "$(json_str "$ch_base")"
    printf '  "clickhouse_chain": %s,\n' "$(json_str "$ch_chain")"
    printf '  "clickhouse_chain_root_epoch": %s,\n' "$ch_root_epoch"
    printf '  "clickhouse_created_epoch": %s,\n' "$ch_epoch"
  else
    printf '  "clickhouse_backup": "",\n'
  fi
  printf '  "files": [\n'
  printf '    {"name": "%s", "sha256": "%s", "bytes": %s}\n' "$pg_file" "$sum" "$size"
  printf '  ]\n'
  printf '}\n'
} >"$partial/manifest.json"
chmod 0600 "$partial/manifest.json"

mv "$partial" "$dir/$name"
partial=
done_ok=true
set_dir=$dir/$name

say "Backup set: $set_dir"
say "  $pg_file  $size bytes  sha256 $sum"
if $clickhouse; then
  say "  ClickHouse backup: $name in ARGOS_CH_BACKUP_DIR ($ch_dir)$(if [ -n "$ch_base" ]; then printf ', incremental on %s' "$ch_base"; fi)"
fi
if [ -n "$kid" ]; then
  say "  Master key id: $kid. Keep secrets/master_key (and tls/) apart from this backup: without it the stored secrets cannot be read."
fi
[ "$enc_tool" != none ] || say "  Not encrypted: the dump holds personal data (--encrypt age:... or gpg:...)."

# --- retention ----------------------------------------------------------------------------------------
# Sets older than keep-days are deleted, except the sets whose ClickHouse backup is part of the chain
# of a kept set (an incremental backup needs its base backups). Incomplete sets of interrupted runs
# (.argos-backup-*.partial) are deleted too.
for p in "$dir"/.argos-backup-*.partial; do
  [ -e "$p" ] || continue
  rm -rf "$p"
done
limit=$((keep_days * 86400))
records=$work/sets
: >"$records"
for m in "$dir"/argos-backup-*/manifest.json; do
  [ -f "$m" ] || continue
  s=$(basename "$(dirname "$m")")
  e=$(mf_get "$m" created_epoch)
  case $e in '' | *[!0-9]*) continue ;; esac
  n=$(mf_get "$m" clickhouse_backup)
  case $n in *[!A-Za-z0-9._-]*) n= ;; esac
  c=$(mf_get "$m" clickhouse_chain)
  printf '%s\t%s\t%s\t%s\n' "$s" "$e" "${n:--}" "${c:--}" >>"$records"
done
awk -F '\t' -v now="$created_epoch" -v limit="$limit" -v keep="$name" '
  { set[NR] = $1; epoch[NR] = $2; ch[NR] = $3; chain[NR] = $4 }
  END {
    for (i = 1; i <= NR; i++) {
      kept[i] = (set[i] == keep || now - epoch[i] <= limit)
      if (kept[i] && chain[i] != "-") {
        k = split(chain[i], parts, " ")
        for (j = 1; j <= k; j++) needed[parts[j]] = 1
      }
    }
    for (i = 1; i <= NR; i++) {
      if (kept[i]) continue
      if (ch[i] != "-" && (ch[i] in needed)) continue
      print set[i] "\t" ch[i]
    }
  }' "$records" >"$work/expired"
removed=0
ch_gone=
while IFS="$(printf '\t')" read -r s n; do
  [ -n "$s" ] || continue
  case $s in argos-backup-*) ;; *) continue ;; esac
  rm -rf "${dir:?}/$s"
  removed=$((removed + 1))
  [ "$n" = - ] || ch_gone="$ch_gone $n"
done <"$work/expired"
if [ -n "$ch_gone" ]; then
  # shellcheck disable=SC2086 # names without spaces (checked above)
  if running clickhouse && ch_remove $ch_gone >/dev/null 2>&1; then
    :
  else
    warn "could not delete the ClickHouse backups of the deleted sets:$ch_gone (delete them in ARGOS_CH_BACKUP_DIR)"
  fi
fi
if [ "$removed" -gt 0 ]; then
  say "Retention: deleted $removed backup set(s) older than $keep_days days."
fi

if $json; then
  if $clickhouse; then ch_json=$(json_str "$name"); else ch_json=null; fi
  printf '{"set":%s,"files":["%s","manifest.json"],"bytes":%s,"clickhouse":%s}\n' \
    "$(json_str "$set_dir")" "$pg_file" "$((size + $(bytes_of "$set_dir/manifest.json")))" "$ch_json"
fi
