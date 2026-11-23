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
#   upgrade, backup, diag    not provided in this version
#   -h, --help               show this text
#
# Compose files: compose.yaml, plus compose.unattended.yaml when ARGOS_UNATTENDED=true,
# compose.mgmt.yaml when ARGOS_MGMT_EXPOSE=true, compose.proxy.yaml when ARGOS_PROXY=true, and
# compose.override.yaml when it exists (your own changes). The values come from .env; a variable
# set in the environment of this script wins, as it does for docker compose.
#
# Exit codes: 0 done; 1 failure; 2 missing prerequisite or usage error;
# 4 refused by a limit (5 active agent tokens per application); 130 or 143 token create
# interrupted (Ctrl-C, SIGTERM).
set -eu
umask 077
# Git Bash on Windows: keep MSYS from rewriting container paths in arguments.
MSYS_NO_PATHCONV=1
export MSYS_NO_PATHCONV

prog=argos-compose.sh
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

# The compose files of this installation (fixed names without spaces).
compose_files() {
  files="-f compose.yaml"
  if is_true "$(env_value ARGOS_UNATTENDED false)"; then
    files="$files -f compose.unattended.yaml"
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
  # shellcheck disable=SC2086 # fixed paths without spaces
  find $MOUNTED_CONFIG 2>/dev/null | while IFS= read -r path; do
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
  for name in $needed clickhouse.env; do
    if [ ! -s "secrets/$name" ]; then
      add "secrets/$name is missing or empty: run ./argos-env-init.sh"
    fi
  done
  for name in server.crt server.key; do
    if [ ! -s "tls/$name" ]; then
      add "tls/$name is missing: place the gRPC certificate and key (docs/install/compose.md, TLS)"
    fi
  done
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
      # The server (uid 10001) reads its secrets and the TLS files from bind mounts.
      for path in $(for n in $needed; do printf 'secrets/%s ' "$n"; done) tls/server.key; do
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
  upgrade | backup | diag | restore)
    die 2 "'$command' is not provided in this version of the Compose installation" ;;
  -h | --help | help) usage ;;
  *) die 2 "unknown command '$command' (see --help)" ;;
esac
