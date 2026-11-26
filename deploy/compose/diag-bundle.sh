#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# diag-bundle.sh: diagnostic bundle of the Docker Compose installation of Argos (docs/17 section
# 4.4, docs/03 section 3.4, SEC-06; WO-43). Also run as ./argos-compose.sh diag.
#
# It runs argos-admin bundle inside the server container (the server log, effective configuration,
# versions, migrations, storage, instances and token prefixes, with secrets masked by the server)
# and adds only what the container cannot see: docker compose ps, the images and their labels, the
# names and SHA-256 of the compose files, .env with secret values masked, the last 1000 lines of the
# ClickHouse and PostgreSQL container logs (masked the same way), the Docker and Compose versions and
# the disk usage seen by ClickHouse. It never includes `docker compose config` (that would expand
# the ClickHouse password hashes of secrets/clickhouse.env), anything from secrets/ or tls/, or the
# server container's console (it shows the setup token of a new installation).
#
# Masking: values of .env keys whose name contains PASSWORD, TOKEN, SECRET, KEY or CREDENTIAL, URL
# user information, argos_ tokens, strings of 64 hexadecimal digits and password=/token=/secret=
# values become ****.
#
# Usage: ./diag-bundle.sh [options]
#   --since PERIOD   log period of the server bundle: 1h to 168h or 1d to 7d (default 24h)
#   --out DIR        directory for the bundle (default: the current directory); it must exist
#   -h, --help       show this text
#
# Output: argos-diag-compose-<host>-<yyyymmddTHHMMSSZ>.tar.gz (0600) with manifest.json, which
# lists every section and whether it could be collected.
#
# Exit codes: 0 written (also when a section could not be collected; the manifest says which);
# 1 the bundle could not be written; 2 missing prerequisite or usage error.
set -eu
umask 077
# Git Bash on Windows: keep MSYS from rewriting container paths in arguments.
MSYS_NO_PATHCONV=1
export MSYS_NO_PATHCONV

prog=diag-bundle.sh
caller_dir=$(pwd)
here=$(cd "$(dirname "$0")" && pwd)

LOG_LINES=1000
# The command line of argos-admin in the server image (as ADMIN_JAVA of argos-compose.sh), without
# the leading "java" so that it also serves `compose run --entrypoint java`.
ADMIN_ARGS="-Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -cp @/opt/argos/app/jib-classpath-file io.argos.server.ServerApplication admin"
CONTAINER_DIR=/data/tmp

die() {
  code=$1
  shift
  printf '%s: %s\n' "$prog" "$*" >&2
  exit "$code"
}
warn() { printf '%s: WARNING: %s\n' "$prog" "$*" >&2; }
usage() { sed -n '/^# Usage:/,/^# Exit codes:/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'; }

since=24h
out_dir=
while [ $# -gt 0 ]; do
  case $1 in
    --since) [ $# -ge 2 ] || die 2 "--since needs a period such as 24h"; since=$2; shift 2 ;;
    --since=*) since=${1#*=}; shift ;;
    --out) [ $# -ge 2 ] || die 2 "--out needs a directory"; out_dir=$2; shift 2 ;;
    --out=*) out_dir=${1#*=}; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die 2 "unknown option: $1 (see --help)" ;;
  esac
done
case $since in
  [1-9]h | [1-9][0-9]h | [1-9][0-9][0-9]h) [ "${since%h}" -le 168 ] || die 2 "--since: at most 168h (7 days)" ;;
  [1-7]d) ;;
  *) die 2 "--since must be hours (1h to 168h) or days (1d to 7d), for example 24h" ;;
esac
[ -n "$out_dir" ] || out_dir=$caller_dir
case $out_dir in
  /* | [A-Za-z]:*) ;;
  *) out_dir=$caller_dir/$out_dir ;;
esac
[ -d "$out_dir" ] || die 2 "--out: $out_dir is not a directory"

cd "$here"
[ -f compose.yaml ] && [ -f argos-compose.sh ] || die 2 "run it in deploy/compose of an Argos installation"
command -v docker >/dev/null 2>&1 || die 2 "docker is not installed"
docker info >/dev/null 2>&1 || die 2 "the Docker daemon is not running or this user may not use it"
command -v tar >/dev/null 2>&1 || die 2 "tar is not installed"
command -v gzip >/dev/null 2>&1 || die 2 "gzip is not installed"

sha=
if command -v sha256sum >/dev/null 2>&1; then
  sha=sha256sum
elif command -v shasum >/dev/null 2>&1; then
  sha=shasum
elif command -v openssl >/dev/null 2>&1; then
  sha=openssl
fi
sha256_file() {
  case $sha in
    sha256sum) sha256sum "$1" | awk '{ print $1 }' ;;
    shasum) shasum -a 256 "$1" | awk '{ print $1 }' ;;
    openssl) openssl dgst -sha256 -r "$1" | awk '{ print $1 }' ;;
    *) printf 'unavailable' ;;
  esac
}

ac() { sh ./argos-compose.sh "$@"; }

host=$(uname -n 2>/dev/null | sed 's/[^A-Za-z0-9.-]/-/g' | cut -c1-63)
[ -n "$host" ] || host=unknown
stamp=$(date -u '+%Y%m%dT%H%M%SZ')
name=argos-diag-compose-$host-$stamp
target=$out_dir/$name.tar.gz
[ ! -e "$target" ] || die 1 "$target exists already"

work=$(mktemp -d 2>/dev/null || mktemp -d -t argos-diag)
container_file=
server_mode=
cleanup() {
  rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
root=$work/$name
mkdir -p "$root/server" "$root/compose" "$root/logs" "$root/system"

# mask: the masking of container logs and other text (see the header).
mask() {
  sed -E \
    -e 's#(://)[^/@[:space:]]*@#\1****@#g' \
    -e 's/argos_[A-Za-z0-9_-]{16,}/argos_****/g' \
    -e 's/[0-9A-Fa-f]{64}/****/g' \
    -e 's/([Pp][Aa][Ss][Ss][Ww][Oo][Rr][Dd]|[Pp][Aa][Ss][Ss][Ww][Dd]|[Ss][Ee][Cc][Rr][Ee][Tt]|[Tt][Oo][Kk][Ee][Nn])(["'"'"']?[[:space:]]*[=:][[:space:]]*["'"'"']?)[^[:space:]"'"'"',;&]+/\1\2****/g'
}

# mask_env: .env with the value of every key that names a secret replaced, then mask.
mask_env() {
  awk '{
    line = $0
    if (match(line, /^[[:space:]]*(export[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=/)) {
      key = substr(line, 1, RLENGTH)
      name = toupper(key)
      if (name ~ /PASSWORD|TOKEN|SECRET|KEY|CREDENTIAL/) { print key "****"; next }
    }
    print line
  }' "$1" | mask
}

manifest_rows=
section() { # section <name> <ok|failed> <detail>
  # Without quotes (\042) and backslashes (\134): the detail is a JSON string.
  detail=$(printf '%s' "$3" | tr -d '\042\134' | tr '\n\r\t' '   ' | cut -c1-300)
  row=$(printf '    {"name": "%s", "ok": %s, "detail": "%s"}' "$1" "$(if [ "$2" = ok ]; then echo true; else echo false; fi)" "$detail")
  if [ -z "$manifest_rows" ]; then
    manifest_rows=$row
  else
    manifest_rows="$manifest_rows,
$row"
  fi
  if [ "$2" != ok ]; then
    warn "section $1 not collected: $3"
  fi
}

# --- the server's bundle (argos-admin bundle in the server container) ----------------------------
server_cid=$(ac compose ps -q server 2>/dev/null | tr -d '\r' | head -n 1 || true)
# in_server <entrypoint> [arguments...]: in the running server container, else in a new one of
# the same service (the volumes of the installation, no dependencies started).
in_server() {
  entry=$1
  shift
  if [ "$server_mode" = exec ]; then
    ac compose exec -T -e JAVA_TOOL_OPTIONS= server "$entry" "$@"
  else
    ac compose run --rm --no-deps -T -e JAVA_TOOL_OPTIONS= --entrypoint "$entry" server "$@"
  fi
}
if [ -n "$server_cid" ]; then
  server_mode='exec'
else
  server_mode='run'
  warn "the server container is not running: argos-admin bundle runs in a new container of the server image"
fi
rc=0
# shellcheck disable=SC2086 # ADMIN_ARGS is split into words on purpose (no spaces in its parts)
in_server java $ADMIN_ARGS bundle --since "$since" --out "$CONTAINER_DIR" --json </dev/null >"$work/bundle.json" 2>"$work/bundle.err" || rc=$?
container_file=$(tr -d '\r' <"$work/bundle.json" | sed -n 's/.*"file":"\([^"]*\)".*/\1/p' | head -n 1)
case $container_file in
  "$CONTAINER_DIR"/argos-diag-*.tar.gz)
    case ${container_file#"$CONTAINER_DIR"/} in
      */* | *[!A-Za-z0-9._-]*) container_file= ;;
    esac
    ;;
  *) container_file= ;;
esac
if [ "$rc" -ne 0 ] || [ -z "$container_file" ]; then
  section server_bundle failed "argos-admin bundle exit code $rc: $(grep -v '^{' "$work/bundle.err" | mask | tail -n 3)"
else
  inner=${container_file##*/}
  rc=0
  in_server cat "$container_file" </dev/null >"$root/server/$inner" 2>"$work/cat.err" || rc=$?
  if [ "$rc" -ne 0 ] || [ ! -s "$root/server/$inner" ]; then
    rm -f "$root/server/$inner"
    section server_bundle failed "cannot copy $container_file from the server container (exit code $rc)"
  else
    failed=$(tr -d '\r' <"$work/bundle.json" | grep -o '"name":"[a-z_]*","ok":false' | sed 's/"name":"\([a-z_]*\)".*/\1/' | tr '\n' ' ' | sed 's/ *$//' || true)
    section server_bundle ok "$inner${failed:+ (sections not collected by the server: $failed)}"
  fi
  in_server rm -f "$container_file" </dev/null >/dev/null 2>&1 \
    || warn "cannot delete $container_file in the argos-data volume; delete it with: ./argos-compose.sh compose exec server rm -f $container_file"
fi
rm -f "$work/bundle.json" "$work/bundle.err" "$work/cat.err"

# --- compose ps -----------------------------------------------------------------------------------
if ac compose ps --all --format json >"$root/compose/ps.json" 2>"$work/err"; then
  section compose_ps ok "compose/ps.json"
else
  section compose_ps failed "$(mask <"$work/err" | tail -n 1)"
fi

# --- images of the containers: id, RepoDigests, labels ----------------------------------------------
ids=$(ac compose ps --all -q 2>/dev/null | tr -d '\r' || true)
if [ -n "$ids" ]; then
  for cid in $ids; do
    line=$(docker inspect --format '{{.Name}} {{.Config.Image}} {{.Image}}' "$cid" 2>/dev/null | tr -d '\r' || true)
    [ -n "$line" ] || continue
    printf '%s\n' "$line"
    image=${line##* }
    docker image inspect --format '  RepoDigests: {{json .RepoDigests}}
  Labels: {{json .Config.Labels}}' "$image" 2>/dev/null | tr -d '\r' || printf '  (image not in the local store)\n'
  done >"$root/compose/images.txt"
  section images ok "compose/images.txt"
else
  section images failed "no container of this installation"
fi

# --- compose files: names and SHA-256 (not their expanded configuration) ---------------------------
if files=$(ac files 2>"$work/err"); then
  {
    printf 'docker compose options: %s\n' "$files"
    # shellcheck disable=SC2086 # the options are split on purpose (fixed names without spaces)
    set -- $files
    while [ $# -gt 0 ]; do
      if [ "$1" = -f ] && [ $# -ge 2 ]; then
        if [ -f "$2" ]; then
          printf '%s  %s\n' "$(sha256_file "$2")" "$2"
        else
          printf 'missing  %s\n' "$2"
        fi
        shift 2
      else
        shift
      fi
    done
  } >"$root/compose/files.txt"
  section compose_files ok "compose/files.txt"
else
  section compose_files failed "$(mask <"$work/err" | tail -n 1)"
fi

# --- .env, masked -------------------------------------------------------------------------------------
if [ -r .env ]; then
  mask_env .env >"$root/compose/env.masked"
  section env ok "compose/env.masked (secret values replaced by ****)"
else
  section env failed ".env is missing or not readable"
fi

# --- ClickHouse and PostgreSQL container logs, masked -------------------------------------------------
for svc in clickhouse postgres; do
  if ac compose logs --no-color --tail "$LOG_LINES" "$svc" </dev/null 2>"$work/err" | mask >"$root/logs/$svc.log" \
    && [ -s "$root/logs/$svc.log" ]; then
    section "logs_$svc" ok "logs/$svc.log (last $LOG_LINES lines, masked)"
  else
    section "logs_$svc" failed "no log of the $svc container"
  fi
done

# --- versions and disk ----------------------------------------------------------------------------------
{
  printf 'docker: %s\n' "$(docker version --format '{{.Server.Version}}' 2>/dev/null | tr -d '\r' || echo unknown)"
  printf 'docker compose: %s\n' "$(docker compose version --short 2>/dev/null | tr -d '\r' || echo unknown)"
  printf 'docker host: %s\n' "$(docker info --format '{{.OperatingSystem}}' 2>/dev/null | tr -d '\r' || echo unknown)"
  printf 'script host: %s\n' "$(uname -srm 2>/dev/null || echo unknown)"
} >"$root/system/versions.txt"
section versions ok "system/versions.txt"
if ac compose exec -T clickhouse df -P </dev/null >"$root/system/df-clickhouse.txt" 2>"$work/err"; then
  section disk ok "system/df-clickhouse.txt"
else
  rm -f "$root/system/df-clickhouse.txt"
  section disk failed "df in the clickhouse container: $(mask <"$work/err" | tail -n 1)"
fi
rm -f "$work/err"

cat >"$root/manifest.json" <<EOF
{
  "kind": "argos-diag-compose",
  "created_at": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')",
  "host": "$host",
  "since": "$since",
  "server_bundle_from": "$server_mode",
  "sections": [
$manifest_rows
  ]
}
EOF

# To standard output: GNU tar would read a C:/... path of Git Bash as host:file.
if ! tar -czf - -C "$work" "$name" 2>"$work/tar.err" >"$target.tmp"; then
  rm -f "$target.tmp"
  die 1 "cannot write $target: $(tail -n 1 "$work/tar.err")"
fi
chmod 0600 "$target.tmp"
mv -f "$target.tmp" "$target" || die 1 "cannot write $target"
printf 'Diagnostic bundle: %s (owner-only)\n' "$target"
sed -n 's/^    {"name": "\([^"]*\)", "ok": \([a-z]*\), "detail": "\(.*\)"},\{0,1\}$/  \1: \2 \3/p' "$root/manifest.json" \
  | sed -e 's/: true /: ok      /' -e 's/: false /: FAILED  /'
printf 'Secret values are masked; read it before you send it on.\n'
