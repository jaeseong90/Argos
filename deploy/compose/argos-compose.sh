#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# argos-compose.sh: operator commands of the Docker Compose installation of Argos (docs/17
# section 3.1, ADR-0010 C-171 (4), C-173 and C-173 (4)). Prepare the directory with
# ./argos-env-init.sh first.
#
# Usage: ./argos-compose.sh <command> [options]
#   up [--timeout SECONDS]   check .env, secrets/ and tls/, start the stack and wait until every
#                            service is healthy (default 600 seconds)
#   down [--volumes]         stop and remove the containers; --volumes also deletes every volume
#                            (all monitoring data, users, apps, tokens and the audit log)
#   status                   the containers and their health
#   logs [SERVICE] [-f|--follow] [--tail N]
#                            container logs (the server log shows the setup token of a new
#                            installation)
#   token create --app NAME --env ENV [--expires-days N]
#                            issue an agent token for an application (created when it does not
#                            exist) through the server's token service (argos-admin token create
#                            in the running server container). The token is printed once and
#                            stored only as a SHA-256 hash; it expires after N days (1 to 3650,
#                            default 365).
#   compose ARGS...          run docker compose with the files of this installation
#   files                    print the docker compose file options of this installation (for
#                            scripts such as diag-bundle.sh)
#   backup [OPTIONS]         ./backup.sh with the same options: PostgreSQL dump (and optionally
#                            a ClickHouse backup) into ARGOS_BACKUP_DIR (docs/ops/backup-restore.md)
#   restore SET [OPTIONS]    ./restore.sh with the same options: restore a backup set
#   diag [--since 24h] [--out DIR]
#                            ./diag-bundle.sh: a diagnostic bundle with secrets masked
#                            (argos-diag-compose-<host>-<time>.tar.gz, owner-only)
#   upgrade --image REF [--yes]
#                            replace the server image: checks the stack and the version rules
#                            (no downgrade, no skipped major version), warns about connected agents
#                            outside the supported range, takes a PostgreSQL backup
#                            (backup.sh --label pre-upgrade-<from>-<to>), writes upgrade-state.json,
#                            sets ARGOS_SERVER_IMAGE in .env, restarts the server (the schema
#                            migrates at its start) and runs a consistency check of yesterday
#   upgrade --rollback [--yes]
#                            back to the image before the last upgrade, with the PostgreSQL data of
#                            its pre-upgrade backup (./restore.sh); data written since is lost
#   -h, --help               show this text
#
# Compose files: compose.yaml, plus compose.unattended.yaml when ARGOS_UNATTENDED=true,
# compose.demo.yaml and the profile "demo" when ARGOS_DEMO_MODE=true (argos-env-init.sh --demo;
# no gRPC certificate then), compose.chbackup.yaml when ARGOS_CH_BACKUP_DIR is set (the ClickHouse
# backup target), compose.mgmt.yaml when ARGOS_MGMT_EXPOSE=true, compose.proxy.yaml when
# ARGOS_PROXY=true, and compose.override.yaml when it exists (your own changes). The values come
# from .env; a variable set in the environment of this script wins, as it does for docker compose.
#
# Exit codes: 0 done (also: upgrade to the version that runs already); 1 failure (upgrade: nothing
# was changed); 2 missing prerequisite, usage error or refused upgrade (downgrade, skipped major
# version); 4 refused by a limit (5 active agent tokens per application); 8 upgrade failed after
# the server was replaced: roll back with ./argos-compose.sh upgrade --rollback; 130 or 143 token
# create interrupted (Ctrl-C, SIGTERM). backup, restore and diag exit with the codes of their
# scripts.
set -eu
umask 077
# Git Bash on Windows: keep MSYS from rewriting container paths in arguments.
MSYS_NO_PATHCONV=1
export MSYS_NO_PATHCONV

prog=argos-compose.sh
# backup, restore and diag run their scripts from the caller's directory, so that relative paths
# mean the same as when the scripts are run directly.
caller_dir=$(pwd)
here=$(cd "$(dirname "$0")" && pwd)
cd "$here"

SERVER_UID=10001
MOUNTED_SECRETS="pg_superuser_password pg_owner_password pg_app_password pg_audit_password pg_backup_password ch_writer_password ch_reader_password ch_migrator_password master_key metrics_token"
MAX_ACTIVE_TOKENS=5
# compose.proxy.yaml: default fixed address of the proxy on the front network (ARGOS_PROXY_IP).
DEFAULT_PROXY_IP=172.16.240.254
# Bind-mount sources that the containers read as their own users (ClickHouse, the server uid
# 10001, PostgreSQL), not as the owner of the checkout.
MOUNTED_CONFIG="../clickhouse/config.d/argos.xml ../clickhouse/users.d/argos.xml ../postgres/roles.sql config/server config/postgres"
# Demo profile (compose.demo.yaml, WO-41): its secret files and its configuration directory.
DEMO_SECRETS="demo_order_api_token demo_payment_api_token demo_db_password"
DEMO_CONFIG="config/demo"

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

is_true() {
  case $1 in
    true | TRUE | True | 1 | yes) return 0 ;;
    *) return 1 ;;
  esac
}

# The demo profile (ARGOS_DEMO_MODE=true, written only by argos-env-init.sh --demo).
demo_mode() { is_true "$(env_value ARGOS_DEMO_MODE false)"; }

# The compose files of this installation (fixed names without spaces) and, in demo mode, the
# demo profile.
compose_files() {
  files="-f compose.yaml"
  if is_true "$(env_value ARGOS_UNATTENDED false)"; then
    files="$files -f compose.unattended.yaml"
  fi
  if demo_mode; then
    # The order of COMPOSE_FILE that argos-env-init.sh --demo writes; the profile starts and stops
    # the demo services with the rest.
    files="$files -f compose.demo.yaml --profile demo"
  fi
  if [ -n "$(env_value ARGOS_CH_BACKUP_DIR)" ]; then
    # The ClickHouse backup target (an external mount, backup.sh --clickhouse).
    files="$files -f compose.chbackup.yaml"
  fi
  if is_true "$(env_value ARGOS_MGMT_EXPOSE false)"; then
    files="$files -f compose.mgmt.yaml"
  fi
  if is_true "$(env_value ARGOS_PROXY false)"; then
    files="$files -f compose.proxy.yaml"
  fi
  if [ -f compose.override.yaml ]; then
    files="$files -f compose.override.yaml"
  fi
  printf '%s' "$files"
}

is_ipv4() {
  case $1 in
    '' | *[!0-9.]* | .* | *. | *..*) return 1 ;;
  esac
  old_ifs=$IFS
  IFS=.
  # shellcheck disable=SC2086 # split on dots on purpose
  set -- $1
  IFS=$old_ifs
  [ $# -eq 4 ] || return 1
  for part in "$@"; do
    [ "${#part}" -le 3 ] && [ "$part" -le 255 ] || return 1
  done
}

# compose.proxy.yaml: the server trusts X-Forwarded-* from the proxy's fixed address only, and
# Tomcat takes that address as a regular expression. It is derived here from ARGOS_PROXY_IP for
# every docker compose call (the environment wins over a value in .env), so the address and the
# expression cannot differ.
export_proxy_trust() {
  proxy_ip=$(env_value ARGOS_PROXY_IP)
  [ -n "$proxy_ip" ] || proxy_ip=$DEFAULT_PROXY_IP
  is_ipv4 "$proxy_ip" || die 2 "ARGOS_PROXY_IP must be an IPv4 address such as $DEFAULT_PROXY_IP (now '$proxy_ip')"
  ARGOS_PROXY_IP_REGEX=$(printf '%s' "$proxy_ip" | sed 's/\./\\./g')
  export ARGOS_PROXY_IP_REGEX
}

dc() {
  export_proxy_trust
  # shellcheck disable=SC2046 # the file options are split on purpose
  docker compose $(compose_files) "$@"
}

# mode_of <path>: the permission string of ls -ld (for example -rw-------). ls of one known path:
# POSIX has no portable stat format (GNU, BusyBox and BSD differ).
# shellcheck disable=SC2012
mode_of() { ls -ld "$1" | cut -c1-10; }
# shellcheck disable=SC2012
owner_of() { ls -lnd "$1" | awk '{ print $3 }'; }

# Whether this file system keeps POSIX modes (NTFS under Git Bash does not).
posix_modes() {
  probe="$1/.argos-mode-probe.$$"
  : >"$probe"
  chmod 0600 "$probe"
  m1=$(mode_of "$probe")
  chmod 0640 "$probe"
  m2=$(mode_of "$probe")
  rm -f "$probe"
  [ "$m1" = "-rw-------" ] && [ "$m2" = "-rw-r-----" ]
}

linux_engine() {
  [ "$(uname -s)" = Linux ] || return 1
  case $(docker info --format '{{.OperatingSystem}}' 2>/dev/null || true) in
    *"Docker Desktop"* | '') return 1 ;;
  esac
  return 0
}

require_docker() {
  command -v docker >/dev/null 2>&1 || die 2 "docker is not installed (Docker Engine 24 or later)"
  docker info >/dev/null 2>&1 || die 2 "the Docker daemon is not running or this user may not use it"
  v=$(docker compose version --short 2>/dev/null || true)
  [ -n "$v" ] || die 2 "Docker Compose v2 is missing ('docker compose version' failed)"
}

# ---------------------------------------------------------------------------------------------
# up

# unreadable_config: prints the bind-mount sources of MOUNTED_CONFIG (and everything below the
# directories) that other users cannot read: files need o+r, directories o+r and o+x. The containers
# read them as their own users, so a checkout under umask 027 or 077 (0640 files, 0750 directories)
# keeps ClickHouse, PostgreSQL or the server from becoming healthy. The mode string is checked, not
# [ -r ], because this script runs as root. No execute bit is needed on files: the PostgreSQL
# entrypoint runs its scripts with /bin/sh or copies them first (config/postgres).
unreadable_config() {
  mounted=$MOUNTED_CONFIG
  if demo_mode; then
    mounted="$mounted $DEMO_CONFIG"
  fi
  if [ -n "$(env_value ARGOS_CH_BACKUP_DIR)" ]; then
    # compose.chbackup.yaml mounts config/clickhouse/backups.xml for ClickHouse (uid 101).
    mounted="$mounted config/clickhouse"
  fi
  # shellcheck disable=SC2086 # fixed paths without spaces
  find $mounted 2>/dev/null | while IFS= read -r path; do
    case $(mode_of "$path") in
      d??????r?[xt]) ;;
      d*) printf '%s\n' "$path" ;;
      ???????r??) ;;
      *) printf '%s\n' "$path" ;;
    esac
  done
}

preflight() {
  problems=
  add() {
    problems="$problems
  - $*"
  }
  if [ ! -f .env ]; then
    add ".env is missing: run ./argos-env-init.sh"
  elif [ ! -r .env ]; then
    add ".env is not readable by this user: run as the user that ran argos-env-init.sh (root on Linux)"
  fi
  if [ ! -d secrets ]; then
    add "secrets/ is missing: run ./argos-env-init.sh"
  fi
  if [ -n "$problems" ]; then
    printf '%s: cannot start:%s\n' "$prog" "$problems" >&2
    exit 2
  fi
  [ -n "$(env_value ARGOS_WEB_PUBLIC_URL)" ] || add "ARGOS_WEB_PUBLIC_URL is empty in .env"
  if is_true "$(env_value ARGOS_PROXY false)"; then
    # Behind the proxy 8080 must be reachable through the proxy only (compose.proxy.yaml).
    http_bind=$(env_value ARGOS_HTTP_BIND)
    [ "$http_bind" = 127.0.0.1 ] \
      || add "ARGOS_PROXY=true needs ARGOS_HTTP_BIND=127.0.0.1 in .env (now ${http_bind:-0.0.0.0}): port 8080 must be reachable from this host only, so that clients go through the proxy (docs/install/compose.md, section 8)"
  fi
  needed="$MOUNTED_SECRETS"
  if is_true "$(env_value ARGOS_UNATTENDED false)"; then
    needed="$needed admin_password"
    [ -n "$(env_value ARGOS_IP_MODE)" ] || add "ARGOS_UNATTENDED=true needs ARGOS_IP_MODE (masked, full or off) in .env"
  fi
  # The demo profile has no gRPC TLS (plaintext inside the internal network, compose.demo.yaml)
  # and needs its own secrets instead of tls/.
  tls_files="tls/server.key"
  if demo_mode; then
    needed="$needed $DEMO_SECRETS"
    tls_files=
  fi
  for name in $needed clickhouse.env; do
    if [ ! -s "secrets/$name" ]; then
      add "secrets/$name is missing or empty: run ./argos-env-init.sh$(if demo_mode; then printf ' --demo'; fi)"
    fi
  done
  if ! demo_mode; then
    for name in server.crt server.key; do
      if [ ! -s "tls/$name" ]; then
        add "tls/$name is missing: place the gRPC certificate and key (docs/install/compose.md, TLS)"
      fi
    done
  fi
  if posix_modes .; then
    [ "$(mode_of .env)" = "-rw-------" ] || add ".env must have mode 0600 (chmod 600 .env)"
    [ "$(mode_of secrets)" = "drwx------" ] || add "secrets/ must have mode 0700 (chmod 700 secrets)"
    for name in $needed clickhouse.env; do
      if [ -f "secrets/$name" ] && [ "$(mode_of "secrets/$name")" != "-rw-------" ]; then
        add "secrets/$name must have mode 0600"
      fi
    done
    if [ -f tls/server.key ]; then
      case $(mode_of tls/server.key) in
        -rw------- | -r--------) ;;
        *) add "tls/server.key must have mode 0600 (chmod 600 tls/server.key)" ;;
      esac
    fi
    if linux_engine; then
      # The server and the demo applications (uid 10001) read their secrets and the TLS files from
      # bind mounts.
      for path in $(for n in $needed; do printf 'secrets/%s ' "$n"; done) $tls_files; do
        if [ -f "$path" ] && [ "$(owner_of "$path")" != "$SERVER_UID" ]; then
          add "$path must belong to uid $SERVER_UID, the server's user (sudo chown $SERVER_UID:$SERVER_UID $path)"
        fi
      done
      if [ -f tls/server.crt ] && [ "$(owner_of tls/server.crt)" != "$SERVER_UID" ]; then
        case $(mode_of tls/server.crt) in
          -??????r??) ;;
          *) add "tls/server.crt must be readable by uid $SERVER_UID (chown $SERVER_UID:$SERVER_UID or chmod 644)" ;;
        esac
      fi
      unreadable=$(unreadable_config)
      if [ -n "$unreadable" ]; then
        count=$(printf '%s\n' "$unreadable" | wc -l | tr -d ' ')
        shown=$(printf '%s\n' "$unreadable" | head -n 5 | tr '\n' ' ' | sed 's/ *$//')
        more=
        if [ "$count" -gt 5 ]; then
          more=" and $((count - 5)) more"
        fi
        add "the containers cannot read these configuration files (a checkout under umask 027 or 077?): $shown$more. Fix them here with: chmod -R a+rX ../clickhouse/config.d ../clickhouse/users.d ../postgres config"
      fi
    fi
  else
    warn "this file system has no POSIX modes (for example NTFS): the modes of .env, secrets/ and tls/ cannot be checked"
  fi
  image=$(env_value ARGOS_SERVER_IMAGE)
  if [ -n "$image" ]; then
    case $image in
      */*) ;; # a registry image: docker compose pulls it
      *)
        docker image inspect "$image" >/dev/null 2>&1 \
          || add "the server image $image is not in the local Docker image store: build it with ./gradlew :server:jibDockerBuild"
        ;;
    esac
  fi
  if demo_mode; then
    # The defaults of compose.demo.yaml are local builds (published images: WO-44).
    for pair in "ARGOS_DEMO_APP_IMAGE :sample-apps:demo-app:jibDockerBuild" \
      "ARGOS_DEMO_LOAD_IMAGE :sample-apps:load:jibDockerBuild"; do
      # shellcheck disable=SC2086 # two words without spaces
      set -- $pair
      # The default of the line "image: ${<variable>:-<default>}".
      default=$(awk -v v="$1" '{ i = index($0, "${" v ":-"); if (i > 0) { s = substr($0, i + length(v) + 4); print substr(s, 1, index(s, "}") - 1); exit } }' compose.demo.yaml)
      image=$(env_value "$1" "$default")
      case $image in
        */*) ;;
        *)
          docker image inspect "$image" >/dev/null 2>&1 \
            || add "the demo image $image is not in the local Docker image store: build it with ./gradlew $2"
          ;;
      esac
    done
  fi
  if [ -n "$problems" ]; then
    printf '%s: cannot start:%s\n' "$prog" "$problems" >&2
    exit 2
  fi
}

cmd_up() {
  timeout=600
  while [ $# -gt 0 ]; do
    case $1 in
      --timeout)
        [ $# -ge 2 ] || die 2 "--timeout needs a number of seconds"
        timeout=$2
        shift 2
        ;;
      --timeout=*) timeout=${1#*=}; shift ;;
      *) die 2 "up: unknown option $1" ;;
    esac
  done
  case $timeout in '' | *[!0-9]*) die 2 "--timeout must be a number of seconds" ;; esac
  require_docker
  preflight
  printf 'Starting Argos (%s); waiting up to %s s until every service is healthy...\n' "$(compose_files)" "$timeout"
  if ! dc up -d --wait --wait-timeout "$timeout"; then
    dc ps --all >&2 || true
    die 1 "the stack did not become healthy; see ./argos-compose.sh logs server (and clickhouse, postgres)"
  fi
  dc ps
  url=$(env_value ARGOS_WEB_PUBLIC_URL)
  printf '\nArgos is up: %s\n' "$url"
  if demo_mode; then
    printf 'Demo mode: plaintext gRPC and sample traffic (evaluation only). Remove it with all its data:\n'
    printf '  ./argos-compose.sh down --volumes   (or: docker compose --profile demo down -v)\n'
  fi
  if is_true "$(env_value ARGOS_UNATTENDED false)"; then
    printf 'Unattended setup: sign in as %s with the password in secrets/admin_password.\n' \
      "$(env_value ARGOS_ADMIN_USERNAME admin)"
  elif dc exec -T server test -f /data/setup-token >/dev/null 2>&1; then
    # Run through sudo: show the commands the same way.
    run=
    if [ -n "${SUDO_USER:-}" ]; then
      run="sudo "
    fi
    printf 'Create the first administrator at %s/setup with the setup token (valid 30 minutes after the start;\n' "${url%/}"
    printf 'a restart issues a new one and the previous one is invalid). Read the current token from its file:\n'
    printf '  %s./argos-compose.sh compose exec server cat /data/setup-token\n' "$run"
    printf 'or from the last "Setup token" line of the server log (the value after the colon at the end of "message"):\n'
    printf '  %s./argos-compose.sh logs --no-color server | grep "Setup token" | tail -n 1\n' "$run"
  fi
}

# ---------------------------------------------------------------------------------------------
# down, status, logs

cmd_down() {
  volumes=
  while [ $# -gt 0 ]; do
    case $1 in
      --volumes | -v) volumes=--volumes; shift ;;
      *) die 2 "down: unknown option $1" ;;
    esac
  done
  require_docker
  if [ -n "$volumes" ]; then
    warn "deleting the volumes: all monitoring data, users, apps, agent tokens and the audit log"
  fi
  # shellcheck disable=SC2086 # empty or one option
  dc down $volumes
}

cmd_status() {
  require_docker
  dc ps --all
}

cmd_logs() {
  require_docker
  set -- "$@"
  dc logs "$@"
}

# ---------------------------------------------------------------------------------------------
# token create (ADR-0010 C-173 (4), docs/16 section 2.1, C-155)

# The command line of the server jar (argos-admin, docs/17 section 4.4) in the server container:
# the image's Java and classpath (Jib entrypoint), a small heap of its own. The container's
# JAVA_TOOL_OPTIONS (the server heap, -Xms4096m by default) is cleared for this second JVM.
ADMIN_JAVA="java -Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -cp @/opt/argos/app/jib-classpath-file io.argos.server.ServerApplication admin"

# json_field <name> <one-line JSON object>: the value of a top-level string, number or boolean
# field of the token create output (its values never contain quotes, commas or braces).
json_field() {
  printf '%s\n' "$2" | sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}[,}].*/\1/p" | head -n 1
}

# cert_san_line <certificate>: the line of names after "X509v3 Subject Alternative Name"
# (openssl x509 -ext where it exists, else the -text output of older OpenSSL and LibreSSL).
cert_san_line() {
  line=$(openssl x509 -noout -ext subjectAltName -in "$1" 2>/dev/null \
    | awk '/Subject Alternative Name/ { getline; print; exit }' || true)
  if [ -z "$line" ]; then
    line=$(openssl x509 -noout -text -in "$1" 2>/dev/null \
      | awk '/Subject Alternative Name/ { getline; print; exit }' || true)
  fi
  printf '%s' "$line"
}

# agent_host <certificate>: the host agents connect to on 9100, from the subject alternative names
# of the gRPC certificate (they must match it): the first DNS name, else the first IP address
# (IPv6 in brackets). Nothing when openssl is missing, the certificate cannot be read or names
# none. Never the public URL of the UI: a reverse proxy or localhost there says nothing about 9100.
agent_host() {
  command -v openssl >/dev/null 2>&1 || return 0
  [ -r "$1" ] || return 0
  names=$(cert_san_line "$1" | tr ',' '\n' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
  dns=$(printf '%s\n' "$names" | sed -n 's/^DNS:\([A-Za-z0-9][A-Za-z0-9.-]*\)$/\1/p' | head -n 1)
  if [ -n "$dns" ]; then
    printf '%s' "$dns"
    return 0
  fi
  ip=$(printf '%s\n' "$names" | sed -n 's/^IP Address:\([0-9A-Fa-f.:]*[0-9A-Fa-f]\)$/\1/p' | head -n 1)
  case $ip in
    '') ;;
    *:*) printf '[%s]' "$ip" ;;
    *) printf '%s' "$ip" ;;
  esac
}

cmd_token() {
  [ $# -ge 1 ] || die 2 "usage: token create --app NAME --env ENV [--expires-days N]"
  sub=$1
  shift
  [ "$sub" = create ] || die 2 "token: unknown subcommand '$sub' (only 'create' is provided in this version)"
  app=
  env=
  days=
  while [ $# -gt 0 ]; do
    case $1 in
      --app) [ $# -ge 2 ] || die 2 "--app needs a name"; app=$2; shift 2 ;;
      --app=*) app=${1#*=}; shift ;;
      --env) [ $# -ge 2 ] || die 2 "--env needs a name"; env=$2; shift 2 ;;
      --env=*) env=${1#*=}; shift ;;
      --expires-days) [ $# -ge 2 ] || die 2 "--expires-days needs a number"; days=$2; shift 2 ;;
      --expires-days=*) days=${1#*=}; shift ;;
      *) die 2 "token create: unknown option $1" ;;
    esac
  done
  # The agent keeps A-Z a-z 0-9 . _ - of app.name, at most 64; environments are at most 32 (C-155).
  case $app in
    '' | *[!A-Za-z0-9._-]*) die 2 "--app: 1 to 64 characters A-Z a-z 0-9 . _ - (the agent's app.name)" ;;
  esac
  [ "${#app}" -le 64 ] || die 2 "--app: at most 64 characters"
  case $env in
    '' | *[!A-Za-z0-9._-]*) die 2 "--env: 1 to 32 characters A-Z a-z 0-9 . _ - (the agent's app.env)" ;;
  esac
  [ "${#env}" -le 32 ] || die 2 "--env: at most 32 characters"
  case $days in
    '') ;;
    *[!0-9]* | 0*) die 2 "--expires-days must be a whole number of days from 1 to 3650" ;;
    *) [ "$days" -le 3650 ] || die 2 "--expires-days must be at most 3650" ;;
  esac
  require_docker
  cid=$(dc ps -q server 2>/dev/null || true)
  [ -n "$cid" ] || die 2 "the server service is not running: ./argos-compose.sh up"
  health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$cid" 2>/dev/null || true)
  [ "$health" = healthy ] || die 2 "the server service is not healthy (${health:-unknown}): ./argos-compose.sh status"

  work=$(mktemp -d 2>/dev/null || mktemp -d -t argos-compose)
  # The EXIT trap cleans up; an interrupt ends the command (dash runs no EXIT trap when a signal
  # ends it, and a POSIX shell would go on after an INT or TERM trap).
  trap 'rm -rf "$work"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  # The server's own token service issues the token (argos-admin token create, ADR-0010 C-173 (4)):
  # inside the server container, as its user, with its configuration and its runtime database
  # account. It creates the app when missing, keeps the limit of 5 active tokens per app, stores
  # only the SHA-256 hash and the prefix, and writes the audit row TOKEN_ISSUED with the actor
  # argos-compose. The token comes back once on its standard output, into a shell variable only;
  # its standard error (messages, never the token) goes to the temporary directory.
  set -- token create --app "$app" --env "$env" --actor argos-compose --json
  [ -z "$days" ] || set -- "$@" --expires-days "$days"
  rc=0
  # shellcheck disable=SC2086 # ADMIN_JAVA is split into words on purpose (no spaces in its parts)
  out=$(dc exec -T -e JAVA_TOOL_OPTIONS= server $ADMIN_JAVA "$@" </dev/null 2>"$work/err") || rc=$?
  case $rc in
    0) ;;
    4)
      out=
      msg=$(sed -n 's/^argos-admin token create: //p' "$work/err" | head -n 1)
      printf '%s: %s\n' "$prog" "${msg:-the application has its maximum of $MAX_ACTIVE_TOKENS active agent tokens}" >&2
      exit 4
      ;;
    130 | 143) out=; exit "$rc" ;;
    *)
      out=
      grep -v '^{' "$work/err" | sed 's/^/  /' >&2 || true
      die 1 "the token was not created (exit code $rc of argos-admin token create in the server container)"
      ;;
  esac
  token=$(json_field token "$out")
  token_prefix=$(json_field prefix "$out")
  app_id=$(json_field app_id "$out")
  app_created=$(json_field app_created "$out")
  expires=$(json_field expires_at "$out")
  out=
  case $token in
    argos_*) [ "${#token}" -eq 49 ] || { token=; die 1 "the server returned a token of unexpected length"; } ;;
    *) token=; die 1 "the server did not return a token" ;;
  esac

  host=$(agent_host tls/server.crt)
  host_note=
  if [ -z "$host" ]; then
    host="<argos-host>"
    host_note="
Replace <argos-host> with the name agents connect to: a name in the subject alternative names of
tls/server.crt.
"
  fi

  printf 'Agent token for app %s, environment %s (app id %s%s), prefix %s, expires: %s\n\n' \
    "$app" "$env" "$app_id" "$(if [ "$app_created" = true ]; then printf ', created now'; fi)" \
    "$token_prefix" "$expires"
  printf '    %s\n\n' "$token"
  token=
  cat <<EOF
This is the only time the token is shown: Argos keeps only its SHA-256 hash. Store it now in a file
that only the application's user can read (chmod 600), without putting it on a command line, and
point the agent at it (argos-agent.yml; ca-file is the CA certificate that signed tls/server.crt):

  server:
    address: $host:9100
    tls:
      ca-file: /opt/argos-agent/argos-ca.crt
    token-file: /opt/argos-agent/token
  app:
    name: $app
    env: $env
  log:
    dir: /opt/argos-agent/logs
$host_note
The token writes to this app and to environment variants of it that its agents register
(at most 8). An app has at most $MAX_ACTIVE_TOKENS active tokens.
EOF
}

# ---------------------------------------------------------------------------------------------
# backup, restore, diag (WO-43): the scripts next to this one, with the same arguments

run_script() { # run_script <script> [arguments...]
  script=$1
  shift
  [ -f "$here/$script" ] || die 2 "$script is missing next to $prog"
  cd "$caller_dir" || die 2 "cannot return to $caller_dir"
  exec sh "$here/$script" "$@"
}

# ---------------------------------------------------------------------------------------------
# upgrade and upgrade --rollback (docs/17 section 4.4 and 8, O-R02; WO-43)

STATE_FILE=upgrade-state.json
VERSION_LABEL=org.opencontainers.image.version
UPGRADE_WAIT_SECONDS=600

# version_ok <version>: MAJOR.MINOR.PATCH with an optional -pre-release and +build (SemVer 2.0).
version_ok() {
  printf '%s\n' "$1" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'
}

# version_cmp <a> <b>: prints -1, 0 or 1 by SemVer precedence (the build part is ignored).
version_cmp() {
  awk -v a="$1" -v b="$2" '
    function cmpid(x, y,   xn, yn) {
      xn = (x ~ /^[0-9]+$/); yn = (y ~ /^[0-9]+$/)
      if (xn && yn) { if (x + 0 < y + 0) return -1; return (x + 0 > y + 0) }
      if (xn) return -1
      if (yn) return 1
      if (x < y) return -1
      return (x > y)
    }
    function cmp(a, b,   pa, pb, ca, cb, xa, xb, na, nb, i, r) {
      sub(/\+.*/, "", a); sub(/\+.*/, "", b)
      pa = ""; pb = ""
      i = index(a, "-"); if (i) { pa = substr(a, i + 1); a = substr(a, 1, i - 1) }
      i = index(b, "-"); if (i) { pb = substr(b, i + 1); b = substr(b, 1, i - 1) }
      split(a, ca, "."); split(b, cb, ".")
      for (i = 1; i <= 3; i++) {
        if (ca[i] + 0 != cb[i] + 0) { if (ca[i] + 0 < cb[i] + 0) return -1; return 1 }
      }
      if (pa == "" && pb == "") return 0
      if (pa == "") return 1
      if (pb == "") return -1
      na = split(pa, xa, "."); nb = split(pb, xb, ".")
      for (i = 1; i <= na && i <= nb; i++) { r = cmpid(xa[i], xb[i]); if (r != 0) return r }
      if (na < nb) return -1
      return (na > nb)
    }
    BEGIN { print cmp(a, b) }'
}

major_of() { printf '%s' "${1%%.*}"; }
minor_of() { m=${1#*.}; printf '%s' "${m%%.*}"; }

image_ref_ok() {
  case $1 in
    '' | -* | *[[:space:]]* | *'"'* | *"'"* | *\\* | *,* | *'$'* | *'`'*) return 1 ;;
  esac
  return 0
}

# image_label <image> : the version label of a local image (empty when it has none).
image_version() {
  docker image inspect --format "{{index .Config.Labels \"$VERSION_LABEL\"}}" "$1" 2>/dev/null | tr -d '\r' | head -n 1
}
image_id() { docker image inspect --format '{{.Id}}' "$1" 2>/dev/null | tr -d '\r' | head -n 1; }

# stack_healthy: every service of compose.yaml runs and is healthy (exit 2 otherwise).
stack_healthy() {
  for svc in postgres clickhouse server; do
    cid=$(dc ps -q "$svc" 2>/dev/null || true)
    [ -n "$cid" ] || die 2 "the $svc service is not running: start the stack first (./argos-compose.sh up)"
    health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$cid" 2>/dev/null || true)
    [ "$health" = healthy ] || die 2 "the $svc service is not healthy (${health:-unknown}): ./argos-compose.sh status"
  done
}

# confirm <question>: --yes answers it; otherwise a terminal must (exit 2 without one).
confirm() {
  $assume_yes && return 0
  [ -t 0 ] || die 2 "$1: give --yes to run without a terminal"
  printf '%s [y/N] ' "$1" >&2
  answer=
  read -r answer || true
  case $answer in
    y | Y | yes | YES) return 0 ;;
    *) die 1 "cancelled; nothing was changed" ;;
  esac
}

# set_env_image <reference>: ARGOS_SERVER_IMAGE in .env, rewritten in place (owner and mode stay).
set_env_image() {
  tmp_env=".env.argos-upgrade.$$"
  if grep -Eq '^[[:space:]]*(export[[:space:]]+)?ARGOS_SERVER_IMAGE=' .env; then
    awk -v v="$1" '{
      if ($0 ~ /^[[:space:]]*(export[[:space:]]+)?ARGOS_SERVER_IMAGE=/) print "ARGOS_SERVER_IMAGE=" v
      else print
    }' .env >"$tmp_env" || { rm -f "$tmp_env"; return 1; }
  else
    cat .env >"$tmp_env" || { rm -f "$tmp_env"; return 1; }
    if [ -s "$tmp_env" ] && [ -n "$(tail -c 1 "$tmp_env")" ]; then
      printf '\n' >>"$tmp_env"
    fi
    printf 'ARGOS_SERVER_IMAGE=%s\n' "$1" >>"$tmp_env"
  fi
  cat "$tmp_env" >.env || { rm -f "$tmp_env"; return 1; }
  rm -f "$tmp_env"
  if [ -n "${ARGOS_SERVER_IMAGE+x}" ] && [ "$ARGOS_SERVER_IMAGE" != "$1" ]; then
    warn "ARGOS_SERVER_IMAGE is set in the environment ($ARGOS_SERVER_IMAGE) and wins over .env for docker compose: unset it, or later commands start that image"
  fi
  ARGOS_SERVER_IMAGE=$1
  export ARGOS_SERVER_IMAGE
}

# state_field <name>: a string field of upgrade-state.json (one "name": "value" per line).
state_field() { sed -n "s/^  \"$1\": \"\\(.*\\)\",\\{0,1\}\$/\\1/p" "$STATE_FILE" | head -n 1; }

# write_state <status>: upgrade-state.json (0600; umask 077), replaced as a whole.
write_state() {
  tmp_state="$STATE_FILE.tmp.$$"
  cat >"$tmp_state" <<EOF
{
  "status": "$1",
  "from_image": "$from_image",
  "from_image_id": "$from_id",
  "from_version": "$from_version",
  "to_image": "$to_image",
  "to_image_id": "$to_id",
  "to_version": "$to_version",
  "backup_set": "$backup_set",
  "started_at": "$started_at",
  "updated_at": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
}
EOF
  chmod 0600 "$tmp_state" && mv -f "$tmp_state" "$STATE_FILE"
}

utc_date_days_ago() { # utc_date_days_ago <days>: yyyy-mm-dd (GNU and BusyBox date -d @, BSD -r)
  t=$(($(date -u +%s) - $1 * 86400))
  date -u -d "@$t" '+%Y-%m-%d' 2>/dev/null || date -u -r "$t" '+%Y-%m-%d'
}

# agent_versions_warning <server version>: warns about connected agents outside X.(Y-2) to X.Y
# (O-R02), read from PostgreSQL as argos_backup inside the postgres container. Never fails.
agent_versions_warning() {
  smaj=$(major_of "$1")
  smin=$(minor_of "$1")
  # shellcheck disable=SC2016 # the postgres container's shell expands it (the password stays there)
  rows=$(printf '%s\n' "SELECT coalesce(agent_version, '') || '|' || count(*) FROM argos.instances WHERE archived_at IS NULL AND status IN ('UP', 'STALE') GROUP BY agent_version ORDER BY 1;" \
    | dc exec -T postgres sh -c 'PGPASSWORD=$(cat /run/secrets/pg_backup_password) psql -X -q -A -t -v ON_ERROR_STOP=1 -h 127.0.0.1 -U argos_backup -d argos -f -' 2>/dev/null) || {
    warn "could not read the versions of the connected agents from PostgreSQL; check them in the UI before you upgrade (continuing)"
    return 0
  }
  outside=$(printf '%s\n' "$rows" | tr -d '\r' | awk -F'|' -v maj="$smaj" -v min="$smin" '
    NF >= 2 && $2 ~ /^[0-9]+$/ {
      v = $1; ok = 0
      if (v ~ /^[0-9]+\.[0-9]+\./) {
        split(v, p, "."); am = p[1] + 0; an = p[2] + 0
        ok = (am == maj + 0 && an >= min - 2 && an <= min + 0)
      }
      if (!ok) { n += $2; list = list sep (v == "" ? "unknown" : v) " (" $2 ")"; sep = ", " }
    }
    END { if (n > 0) print n " agents: " list }')
  if [ -n "$outside" ]; then
    warn "connected agents outside the range the server $1 accepts ($smaj.$((smin - 2 < 0 ? 0 : smin - 2)) to $smaj.$smin, O-R02): ${outside}. The server refuses them after the upgrade; replace those agents"
  fi
}

cmd_upgrade() {
  to_image=
  rollback=false
  assume_yes=false
  while [ $# -gt 0 ]; do
    case $1 in
      --image) [ $# -ge 2 ] || die 2 "--image needs an image reference"; to_image=$2; shift 2 ;;
      --image=*) to_image=${1#*=}; shift ;;
      --rollback) rollback=true; shift ;;
      --yes | -y) assume_yes=true; shift ;;
      *) die 2 "upgrade: unknown option $1 (usage: upgrade --image REF [--yes] | upgrade --rollback [--yes])" ;;
    esac
  done
  if $rollback; then
    [ -z "$to_image" ] || die 2 "upgrade: --rollback takes no --image (it returns to the image of $STATE_FILE)"
    upgrade_rollback
    return
  fi
  [ -n "$to_image" ] || die 2 "usage: upgrade --image REF [--yes] | upgrade --rollback [--yes]"
  image_ref_ok "$to_image" || die 2 "--image is not an image reference: $to_image"
  [ -f "$here/backup.sh" ] || die 2 "backup.sh is missing next to $prog: an upgrade needs its PostgreSQL backup"
  [ -f .env ] || die 2 ".env is missing: run ./argos-env-init.sh"
  require_docker
  t_start=$(date +%s)
  stack_healthy

  server_cid=$(dc ps -q server)
  from_image=$(docker inspect --format '{{.Config.Image}}' "$server_cid" 2>/dev/null | tr -d '\r' | head -n 1)
  from_id=$(docker inspect --format '{{.Image}}' "$server_cid" 2>/dev/null | tr -d '\r' | head -n 1)
  [ -n "$from_image" ] && [ -n "$from_id" ] || die 1 "cannot read the image of the running server container"
  image_ref_ok "$from_image" || die 1 "the running server image has an unexpected reference: $from_image"
  from_version=$(docker image inspect --format "{{index .Config.Labels \"$VERSION_LABEL\"}}" "$from_id" 2>/dev/null | tr -d '\r' | head -n 1)

  if ! docker image inspect "$to_image" >/dev/null 2>&1; then
    printf 'Pulling %s...\n' "$to_image"
    docker pull "$to_image" >&2 || die 2 "the image $to_image is not in the local image store and cannot be pulled"
  fi
  to_id=$(image_id "$to_image")
  to_version=$(image_version "$to_image")
  [ -n "$to_version" ] || die 2 "the image $to_image has no $VERSION_LABEL label: not an Argos server image of a release"
  [ -n "$from_version" ] || die 2 "the running server image $from_image has no $VERSION_LABEL label"
  version_ok "$to_version" || die 2 "the image $to_image has the version '$to_version', which is not MAJOR.MINOR.PATCH"
  version_ok "$from_version" || die 2 "the running server image has the version '$from_version', which is not MAJOR.MINOR.PATCH"

  case $(version_cmp "$to_version" "$from_version") in
    0)
      printf 'The server runs version %s already (%s); nothing to do.\n' "$from_version" "$from_image"
      return 0
      ;;
    -1) die 2 "refused: $to_version is older than the running $from_version (downgrades are not supported; to go back after an upgrade use: ./argos-compose.sh upgrade --rollback)" ;;
  esac
  if [ $(($(major_of "$to_version") - $(major_of "$from_version"))) -ge 2 ]; then
    die 2 "refused: $from_version to $to_version skips a major version; upgrade to the last minor version of each major version in turn"
  fi
  agent_versions_warning "$to_version"

  printf 'Upgrade of the Argos server: %s (%s) -> %s (%s)\n' "$from_version" "$from_image" "$to_version" "$to_image"
  printf 'Steps: PostgreSQL backup, then the server restarts with the new image (the schema migrates at its start;\n'
  printf 'agents buffer and reconnect), then a consistency check. Target: 10 minutes (docs/17 section 8.2).\n'
  confirm "Upgrade now?"

  # backup.sh takes 1 to 32 characters A-Z a-z 0-9 . _ - (long pre-release versions are cut).
  label=$(printf 'pre-upgrade-%s-%s' "$from_version" "$to_version" | sed 's/[^A-Za-z0-9._-]/-/g' | cut -c1-32)
  printf '\n[1/4] PostgreSQL backup (backup.sh --label %s)\n' "$label"
  backup_out=$(mktemp 2>/dev/null || mktemp -t argos-upgrade)
  rc=0
  sh "$here/backup.sh" --label "$label" --json >"$backup_out" || rc=$?
  last=$(grep '^{' "$backup_out" | tail -n 1 || true)
  grep -v '^{' "$backup_out" || true
  rm -f "$backup_out"
  backup_set=$(json_field set "$last")
  if [ "$rc" -ne 0 ] || [ -z "$backup_set" ]; then
    die 1 "the backup failed (backup.sh exit code $rc); nothing was changed: the server runs $from_version"
  fi
  case $backup_set in
    *'"'* | *\\*) die 1 "backup.sh named an unexpected backup set; nothing was changed" ;;
  esac
  printf 'Backup set: %s\n' "$backup_set"

  started_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  write_state started || die 1 "cannot write $STATE_FILE; nothing was changed (the backup set stays)"
  printf '\n[2/4] ARGOS_SERVER_IMAGE=%s in .env\n' "$to_image"
  set_env_image "$to_image" || die 1 "cannot update .env; nothing was changed (the backup set stays)"

  printf '\n[3/4] Restarting the server with %s (waiting up to %s s until it is healthy)\n' "$to_image" "$UPGRADE_WAIT_SECONDS"
  if ! dc up -d --wait --wait-timeout "$UPGRADE_WAIT_SECONDS" server; then
    write_state failed || true
    dc ps --all >&2 || true
    printf '%s: the server did not become healthy with %s; see ./argos-compose.sh logs server.\n' "$prog" "$to_image" >&2
    printf '%s: roll back to %s and the PostgreSQL data of %s with:\n  ./argos-compose.sh upgrade --rollback --yes\n' \
      "$prog" "$from_version" "$backup_set" >&2
    exit 8
  fi

  check_date=$(utc_date_days_ago 1)
  printf '\n[4/4] Consistency check of %s (argos-admin consistency check, report only)\n' "$check_date"
  rc=0
  # shellcheck disable=SC2086 # ADMIN_JAVA is split into words on purpose
  dc exec -T -e JAVA_TOOL_OPTIONS= server $ADMIN_JAVA consistency check --date "$check_date" </dev/null || rc=$?
  if [ "$rc" -ne 0 ]; then
    warn "the consistency check of $check_date reported a problem (exit code $rc); the upgrade itself is done. Check and rebuild that day with: ./argos-compose.sh compose exec -e JAVA_TOOL_OPTIONS= server $ADMIN_JAVA consistency check --date $check_date --rebuild (docs/ops/admin-cli.md)"
  fi
  write_state 'done' || warn "cannot update $STATE_FILE"
  elapsed=$(($(date +%s) - t_start))
  printf '\nUpgraded to %s in %s s (docs/17 target: 600 s). The pre-upgrade backup is %s;\n' "$to_version" "$elapsed" "$backup_set"
  printf 'roll back with ./argos-compose.sh upgrade --rollback (PostgreSQL data written since then is lost).\n'
}

upgrade_rollback() {
  [ -f "$STATE_FILE" ] || die 2 "no $STATE_FILE here: there is no upgrade to roll back"
  [ -f "$here/restore.sh" ] || die 2 "restore.sh is missing next to $prog"
  status=$(state_field status)
  from_image=$(state_field from_image)
  from_id=$(state_field from_image_id)
  from_version=$(state_field from_version)
  to_image=$(state_field to_image)
  to_id=$(state_field to_image_id)
  to_version=$(state_field to_version)
  backup_set=$(state_field backup_set)
  started_at=$(state_field started_at)
  case $status in
    started | failed | done) ;;
    rolled_back) die 2 "the upgrade to $to_version was rolled back already ($STATE_FILE)" ;;
    *) die 2 "$STATE_FILE is not readable (status '$status')" ;;
  esac
  image_ref_ok "$from_image" || die 2 "$STATE_FILE names no previous image"
  [ -n "$backup_set" ] || die 2 "$STATE_FILE names no backup set"
  [ -d "$backup_set" ] || die 2 "the pre-upgrade backup set $backup_set does not exist"
  [ -f .env ] || die 2 ".env is missing"
  require_docker
  t_start=$(date +%s)
  docker image inspect "$from_image" >/dev/null 2>&1 \
    || die 2 "the previous image $from_image is not in the local image store (pull or load it first)"
  now_id=$(image_id "$from_image")
  if [ -n "$from_id" ] && [ "$now_id" != "$from_id" ]; then
    warn "$from_image now names the image $now_id, not $from_id of the upgrade; tag that image again if it is not the one you want"
  fi
  printf 'Rollback of the upgrade %s -> %s: the server returns to %s (%s) and PostgreSQL to the backup %s.\n' \
    "$from_version" "$to_version" "$from_version" "$from_image" "$backup_set"
  printf 'PostgreSQL data written since the backup (users, tokens, rules, audit rows) is lost; ClickHouse is kept.\n'
  confirm "Roll back now?"

  printf '\n[1/3] ARGOS_SERVER_IMAGE=%s in .env\n' "$from_image"
  set_env_image "$from_image" || die 1 "cannot update .env; nothing was changed"
  printf '\n[2/3] Restoring PostgreSQL from %s (restore.sh)\n' "$backup_set"
  rc=0
  sh "$here/restore.sh" "$backup_set" --yes || rc=$?
  if [ "$rc" -ne 0 ]; then
    die 1 "restore.sh failed (exit code $rc); .env names $from_image again. Fix the cause it printed and run ./argos-compose.sh upgrade --rollback --yes again"
  fi
  printf '\n[3/3] Server on %s\n' "$from_image"
  dc up -d --wait --wait-timeout "$UPGRADE_WAIT_SECONDS" server \
    || die 1 "the server did not become healthy with $from_image; see ./argos-compose.sh logs server"
  write_state rolled_back || warn "cannot update $STATE_FILE"
  printf '\nRolled back to %s in %s s.\n' "$from_version" "$(($(date +%s) - t_start))"
}

# ---------------------------------------------------------------------------------------------

[ $# -ge 1 ] || {
  usage >&2
  exit 2
}
command=$1
shift
case $command in
  up) cmd_up "$@" ;;
  down) cmd_down "$@" ;;
  status | ps) cmd_status "$@" ;;
  logs) cmd_logs "$@" ;;
  token) cmd_token "$@" ;;
  compose) dc "$@" ;;
  files) compose_files; echo ;;
  backup) run_script backup.sh "$@" ;;
  restore) run_script restore.sh "$@" ;;
  diag) run_script diag-bundle.sh "$@" ;;
  upgrade) cmd_upgrade "$@" ;;
  -h | --help | help) usage ;;
  *) die 2 "unknown command '$command' (see --help)" ;;
esac
