#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# smoke.sh: checks the state of the Docker Compose installation of Argos right after
# ./argos-compose.sh up (docs/17 section 3.3 and section 14 "install state"; SEC-47, SEC-55,
# SEC-73..76). It stops at the first violation with a message and exit code 1. By default it
# changes nothing and signs in as nobody: the setup wizard stays open for you.
#
# Usage: ./smoke.sh [options]
#   --complete-setup         also create the first administrator through the setup wizard API
#                            (setup token mode) and check that the token cannot be used again and
#                            that the wizard is closed afterwards
#   --admin-user NAME        user name for --complete-setup (default admin)
#   --admin-password-file F  password for --complete-setup. Without it a random password is used
#                            and not kept, so nobody can sign in with it (test installations only)
#   --check-admin-login      unattended setup only: also sign in as the administrator with
#                            secrets/admin_password, check that the password must be changed, and
#                            sign out again. For a test right after the installation, before the
#                            password was changed: afterwards it fails and counts as a failed sign-in
#   --only CHECKS            run only these checks (comma-separated): files, ports, listen, images,
#                            network, user, health, actuator, setup
#   -h, --help               show this text
#
# Exit codes: 0 every check passed (lines "NOT VERIFIABLE" name what this host cannot check);
# 1 a violation; 2 missing prerequisite or usage error; 130 or 143 interrupted (Ctrl-C, SIGTERM).
set -eu
umask 077
# Git Bash on Windows: MSYS_NO_PATHCONV is set by argos-compose.sh for the docker calls with
# container paths only; curl here needs the usual conversion of its file arguments.
unset MSYS_NO_PATHCONV

prog=smoke.sh
here=$(cd "$(dirname "$0")" && pwd)
cd "$here"

SERVER_UID=10001
ALL_CHECKS="files ports listen images network user health actuator setup"
FORBIDDEN="changeme password argos admin postgres clickhouse 123456 qwerty default"

passed=0
not_verifiable=0
fail() {
  printf 'smoke: FAIL %s\n' "$*" >&2
  exit 1
}
ok() {
  passed=$((passed + 1))
  printf 'smoke: ok   %s\n' "$*"
}
nv() {
  not_verifiable=$((not_verifiable + 1))
  printf 'smoke: NOT VERIFIABLE %s\n' "$*"
}
die() {
  printf '%s: %s\n' "$prog" "$*" >&2
  exit 2
}
usage() { sed -n '/^# Usage:/,/^# Exit codes:/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'; }

complete_setup=false
check_admin_login=false
admin_user="admin"
admin_password_file=
only=$ALL_CHECKS
while [ $# -gt 0 ]; do
  case $1 in
    --complete-setup) complete_setup=true; shift ;;
    --check-admin-login) check_admin_login=true; shift ;;
    --admin-user) [ $# -ge 2 ] || die "--admin-user needs a name"; admin_user=$2; shift 2 ;;
    --admin-password-file) [ $# -ge 2 ] || die "--admin-password-file needs a file"; admin_password_file=$2; shift 2 ;;
    --only) [ $# -ge 2 ] || die "--only needs a list of checks"; only=$(printf '%s' "$2" | tr ',' ' '); shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done
for c in $only; do
  case " $ALL_CHECKS " in
    *" $c "*) ;;
    *) die "unknown check '$c' (checks: $ALL_CHECKS)" ;;
  esac
done
case $admin_user in
  '' | *[!A-Za-z0-9._@+-]*) die "--admin-user: letters, digits and . _ @ + - only" ;;
esac
if [ -n "$admin_password_file" ] && [ ! -r "$admin_password_file" ]; then
  die "cannot read $admin_password_file"
fi
command -v docker >/dev/null 2>&1 || die "docker is not installed"

tmp=$(mktemp -d 2>/dev/null || mktemp -d -t argos-smoke)
# The EXIT trap cleans up; an interrupt ends the run (a POSIX shell would go on after an INT or TERM
# trap, and dash runs no EXIT trap when a signal ends it).
trap 'rm -rf "$tmp"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

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
      printf '%s' "${line#*=}" | sed -e 's/[[:space:]]*$//' -e 's/^[[:space:]]*//' \
        -e 's/^"\(.*\)"$/\1/' -e "s/^'\\(.*\\)'\$/\\1/"
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
selected() {
  case " $only " in
    *" $1 "*) return 0 ;;
    *) return 1 ;;
  esac
}
compose() { ./argos-compose.sh compose "$@"; }
# ls of one known path: POSIX has no portable stat format (GNU, BusyBox and BSD differ).
# shellcheck disable=SC2012
mode_of() { ls -ld "$1" | cut -c1-10; }
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
container_of() {
  cid=$(compose ps -q "$1" 2>/dev/null | head -n 1 || true)
  [ -n "$cid" ] || fail "the $1 container is not running (./argos-compose.sh up)"
  printf '%s' "$cid"
}
rand_b64url() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 "$1"
  else
    dd if=/dev/urandom bs="$1" count=1 2>/dev/null | base64
  fi | tr -d '\r\n=' | tr '+/' '-_'
}
json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

mgmt_exposed=false
if is_true "$(env_value ARGOS_MGMT_EXPOSE false)"; then
  mgmt_exposed=true
fi
unattended=false
if is_true "$(env_value ARGOS_UNATTENDED false)"; then
  unattended=true
fi
proxy_enabled=false
if is_true "$(env_value ARGOS_PROXY false)"; then
  proxy_enabled=true
fi
if $check_admin_login && ! $unattended; then
  die "--check-admin-login applies to the unattended setup (ARGOS_UNATTENDED=true, secrets/admin_password) only"
fi

# ---------------------------------------------------------------------------------------------
# files: .env 0600 without secrets, secrets/ 0700 with files 0600 (SEC-74, docs/17 section 3.4)

check_files() {
  [ -f .env ] || fail ".env is missing (./argos-env-init.sh)"
  [ -d secrets ] || fail "secrets/ is missing (./argos-env-init.sh)"
  # A variable that holds a secret value (not a *_FILE path) has no place in .env.
  bad=$(grep -E '^[[:space:]]*(export[[:space:]]+)?[A-Z0-9_]*(PASSWORD|SECRET|TOKEN|MASTER_KEY|JWT_KEY|SHA256)[A-Z0-9_]*=' .env \
    | grep -vE '^[[:space:]]*(export[[:space:]]+)?[A-Z0-9_]*_FILE=' | sed 's/=.*//' | tr '\n' ' ' || true)
  [ -z "$bad" ] || fail ".env sets secret variables: $bad(secrets belong in secrets/, SEC-74)"
  count=0
  for f in secrets/*; do
    [ -f "$f" ] || continue
    count=$((count + 1))
    [ -s "$f" ] || fail "$f is empty"
    if [ "$f" = secrets/clickhouse.env ]; then continue; fi
    if grep -qF -f "$f" .env; then
      fail ".env contains the value of $f (SEC-74)"
    fi
    value=
    IFS= read -r value <"$f" || true
    low=$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')
    for word in $FORBIDDEN; do
      [ "$low" != "$word" ] || fail "$f holds a forbidden default password (SEC-74)"
    done
    [ "${#value}" -ge 12 ] || fail "$f is shorter than 12 characters (SEC-74)"
    value=
    low=
  done
  [ "$count" -gt 0 ] || fail "secrets/ is empty (./argos-env-init.sh)"
  ok "no secret in .env; $count files in secrets/, none empty, short or a default password"
  if posix_modes .; then
    m=$(mode_of .env)
    [ "$m" = "-rw-------" ] || fail ".env has mode $m, expected 0600 (-rw-------)"
    m=$(mode_of secrets)
    [ "$m" = "drwx------" ] || fail "secrets/ has mode $m, expected 0700 (drwx------)"
    for f in secrets/*; do
      [ -f "$f" ] || continue
      m=$(mode_of "$f")
      [ "$m" = "-rw-------" ] || fail "$f has mode $m, expected 0600 (-rw-------)"
    done
    if [ -f tls/server.key ]; then
      m=$(mode_of tls/server.key)
      case $m in
        -rw------- | -r--------) ;;
        *) fail "tls/server.key has mode $m, expected 0600" ;;
      esac
    fi
    ok ".env 0600, secrets/ 0700, its files 0600, tls/server.key 0600"
  else
    nv "file modes: this file system has no POSIX modes (for example NTFS); on a Linux host .env must be 0600, secrets/ 0700 and its files 0600"
  fi
}

# ---------------------------------------------------------------------------------------------
# ports: the published ports are exactly {8080, 9100} (+ 127.0.0.1:8081) (SEC-75, SEC-55)

# Prints "<host address> <published port> <container port>" for every published port of the JSON of
# docker compose ps --format json (one object per line, or one array).
published_ports() {
  awk '{
    s = $0
    while (match(s, /\{[^{}]*"PublishedPort"[^{}]*\}/)) {
      obj = substr(s, RSTART, RLENGTH)
      s = substr(s, RSTART + RLENGTH)
      url = ""; pub = 0; target = 0
      if (match(obj, /"URL":"[^"]*"/)) url = substr(obj, RSTART + 7, RLENGTH - 8)
      if (match(obj, /"PublishedPort":[0-9]+/)) pub = substr(obj, RSTART + 16, RLENGTH - 16)
      if (match(obj, /"TargetPort":[0-9]+/)) target = substr(obj, RSTART + 13, RLENGTH - 13)
      if (pub + 0 > 0) print (url == "" ? "-" : url), pub, target
    }
  }'
}

bind_matches() { # bind_matches <published address> <configured bind>
  if [ "$2" = 0.0.0.0 ]; then
    case $1 in 0.0.0.0 | :: | -) return 0 ;; esac
    return 1
  fi
  [ "$1" = "$2" ]
}

check_ports() {
  compose ps --all --format json >"$tmp/ps.json" 2>"$tmp/ps.err" || {
    cat "$tmp/ps.err" >&2
    fail "docker compose ps failed"
  }
  [ -s "$tmp/ps.json" ] || fail "no container of this installation exists (./argos-compose.sh up)"
  published_ports <"$tmp/ps.json" >"$tmp/ports"
  http_bind=$(env_value ARGOS_HTTP_BIND 0.0.0.0)
  grpc_bind=$(env_value ARGOS_GRPC_BIND 0.0.0.0)
  # Behind the proxy of compose.proxy.yaml clients must not reach 8080 directly (17 section 3.10).
  if $proxy_enabled && [ "$http_bind" != 127.0.0.1 ]; then
    fail "ARGOS_PROXY=true but ARGOS_HTTP_BIND is ${http_bind:-0.0.0.0}; behind the proxy 8080 must be published on 127.0.0.1 only"
  fi
  seen_http=false
  seen_grpc=false
  seen_mgmt=false
  while read -r addr port target; do
    [ -n "$port" ] || continue
    case $target in
      5432 | 8123 | 9000 | 9004 | 9005 | 9009)
        fail "database port $target is published on the host as $addr:$port; PostgreSQL and ClickHouse must not publish ports (SEC-75)" ;;
    esac
    case $port in
      8080)
        [ "$target" = 8080 ] || fail "host port 8080 leads to container port $target"
        bind_matches "$addr" "$http_bind" || fail "port 8080 is published on $addr, expected ARGOS_HTTP_BIND=$http_bind"
        seen_http=true
        ;;
      9100)
        [ "$target" = 9100 ] || fail "host port 9100 leads to container port $target"
        bind_matches "$addr" "$grpc_bind" || fail "port 9100 is published on $addr, expected ARGOS_GRPC_BIND=$grpc_bind"
        seen_grpc=true
        ;;
      8081)
        $mgmt_exposed || fail "the management port 8081 is published ($addr) although ARGOS_MGMT_EXPOSE is not true (SEC-55)"
        [ "$addr" = 127.0.0.1 ] || fail "the management port 8081 is published on $addr; only 127.0.0.1 is allowed (SEC-55)"
        [ "$target" = 8081 ] || fail "host port 8081 leads to container port $target"
        seen_mgmt=true
        ;;
      443)
        $proxy_enabled || fail "port 443 is published ($addr) although ARGOS_PROXY is not true"
        [ "$target" = 443 ] || fail "host port 443 leads to container port $target"
        ;;
      5432 | 8123 | 9000)
        fail "port $port is published on the host ($addr:$port -> $target); the databases must not publish ports (SEC-75)" ;;
      *)
        fail "unexpected published port $addr:$port -> $target; allowed are 8080, 9100 and 127.0.0.1:8081 (SEC-75)" ;;
    esac
  done <"$tmp/ports"
  $seen_http || fail "port 8080 is not published"
  $seen_grpc || fail "port 9100 is not published"
  if $mgmt_exposed; then
    $seen_mgmt || fail "ARGOS_MGMT_EXPOSE=true but 127.0.0.1:8081 is not published"
    ok "published ports: 8080 and 9100, management 127.0.0.1:8081; no database port"
  else
    ok "published ports: exactly 8080 and 9100; no database or management port"
  fi
}

# listen: on a Linux Docker Engine also the host listeners of docker-proxy (ss -ltnp)
check_listen() {
  if ! linux_engine; then
    nv "host listeners (ss -ltn): not a Linux Docker Engine; the published ports were checked through docker compose ps"
    return 0
  fi
  if ! command -v ss >/dev/null 2>&1; then
    nv "host listeners: ss is not installed"
    return 0
  fi
  if [ "$(id -u)" != 0 ]; then
    nv "host listeners: ss -ltnp needs root to see which sockets belong to docker-proxy"
    return 0
  fi
  ss -Hltnp 2>/dev/null | grep 'docker-proxy' | awk '{ print $4 }' >"$tmp/listen" || true
  if [ ! -s "$tmp/listen" ]; then
    nv "host listeners: no docker-proxy socket (userland proxy switched off?); the published ports were checked through docker compose ps"
    return 0
  fi
  while read -r local; do
    port=${local##*:}
    addr=${local%:*}
    case $port in
      8080 | 9100) ;;
      443) $proxy_enabled || fail "a docker-proxy listens on $local although ARGOS_PROXY is not true" ;;
      8081)
        $mgmt_exposed || fail "a docker-proxy listens on $local although ARGOS_MGMT_EXPOSE is not true (SEC-55)"
        [ "$addr" = 127.0.0.1 ] || fail "a docker-proxy listens on $local; the management port is allowed on 127.0.0.1 only"
        ;;
      *) fail "a docker-proxy listens on $local; only 8080, 9100 (and 127.0.0.1:8081) are expected on an Argos host (another container may publish it)" ;;
    esac
  done <"$tmp/listen"
  ok "host listeners of docker-proxy (ss -ltnp): only the allowed ports"
}

# ---------------------------------------------------------------------------------------------
# images: digests of deploy/clickhouse/image.properties and deploy/postgres/image.properties (SEC-73)

check_images() {
  for svc in clickhouse postgres; do
    props=../$svc/image.properties
    cid=$(container_of "$svc")
    got=$(docker inspect --format '{{.Config.Image}}' "$cid")
    if [ -f "$props" ]; then
      want=$(sed -n 's/^image=//p' "$props" | head -n 1)
      [ "$got" = "$want" ] || fail "$svc runs $got, expected $want ($props)"
    else
      nv "$svc image: $props is not next to this directory"
    fi
    case $got in
      *@sha256:*) ;;
      *) fail "$svc image $got is not pinned by digest (SEC-73)" ;;
    esac
  done
  cid=$(container_of server)
  got=$(docker inspect --format '{{.Config.Image}}' "$cid")
  want=$(env_value ARGOS_SERVER_IMAGE "$got")
  [ "$got" = "$want" ] || fail "server runs $got, expected ARGOS_SERVER_IMAGE=$want"
  ok "images: ClickHouse and PostgreSQL pinned as in image.properties; server $got"
}

# ---------------------------------------------------------------------------------------------
# network: back is internal, the databases are on back only (docs/17 section 3.2, open item 6)

check_network() {
  cid=$(container_of server)
  project=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$cid")
  [ -n "$project" ] || fail "cannot read the Compose project of the server container"
  internal=$(docker network inspect --format '{{.Internal}}' "${project}_back" 2>/dev/null || true)
  [ "$internal" = true ] || fail "network ${project}_back is not internal (Internal=${internal:-missing})"
  for svc in clickhouse postgres; do
    c=$(container_of "$svc")
    nets=$(docker inspect --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$c" | sed 's/ *$//')
    [ "$nets" = "${project}_back" ] || fail "$svc is attached to '$nets'; expected ${project}_back only"
  done
  nets=$(docker inspect --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$cid")
  case " $nets " in
    *" ${project}_front "*) ;;
    *) fail "server is not attached to ${project}_front" ;;
  esac
  ok "network ${project}_back is internal; ClickHouse and PostgreSQL are attached to it only"
}

# ---------------------------------------------------------------------------------------------
# user: the server runs as 10001:10001 and can write /data (ADR-0003, SEC-76)

check_user() {
  container_of server >/dev/null
  ids=$(compose exec -T server sh -c 'grep -E "^(Uid|Gid):" /proc/1/status' | awk '{ print $2, $3, $4, $5 }' | tr '\n' ' ')
  # shellcheck disable=SC2086 # the eight ids are split on purpose
  set -- $ids
  [ $# -eq 8 ] || fail "cannot read the user of the server process"
  for id in "$@"; do
    [ "$id" = "$SERVER_UID" ] || fail "the server process runs as uid/gid '$ids', expected $SERVER_UID (never root)"
  done
  # shellcheck disable=SC2016 # the script runs in the server container's shell, which expands it
  compose exec -T server sh -c 'p=/data/.argos-smoke.$$ && : >"$p" && rm -f "$p"' >/dev/null 2>&1 \
    || fail "the server cannot write /data"
  data=$(compose exec -T server stat -c '%u:%g %a' /data | tr -d '\r')
  [ "$data" = "$SERVER_UID:$SERVER_UID 700" ] || fail "/data is '$data', expected $SERVER_UID:$SERVER_UID 700"
  ok "server process uid/gid $SERVER_UID; /data owned by it (0700) and writable"
}

# ---------------------------------------------------------------------------------------------
# health: every container healthy, server health and readiness UP on the management port

check_health() {
  for svc in postgres clickhouse server; do
    c=$(container_of "$svc")
    h=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$c")
    [ "$h" = healthy ] || fail "$svc is not healthy (${h:-no health check})"
  done
  for path in health health/readiness health/liveness; do
    body=$(compose exec -T server wget -q -O - "http://127.0.0.1:8081/actuator/$path" 2>/dev/null || true)
    case $body in
      *'"status":"UP"'*) ;;
      *) fail "/actuator/$path on the management port is not UP" ;;
    esac
  done
  ok "postgres, clickhouse and server healthy; server health, readiness and liveness UP"
}

# ---------------------------------------------------------------------------------------------
# actuator: nothing of Actuator on 8080; the management port only as configured (SEC-55)

http_host() {
  b=$(env_value ARGOS_HTTP_BIND 0.0.0.0)
  if [ "$b" = 0.0.0.0 ]; then
    printf '127.0.0.1'
  else
    printf '%s' "$b"
  fi
}

http_code() { # http_code <url> [curl options]: prints the status code (000 when no connection)
  url=$1
  shift
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@" "$url" 2>/dev/null || true
}

check_actuator() {
  command -v curl >/dev/null 2>&1 || die "curl is not installed"
  base="http://$(http_host):8080"
  for path in env health prometheus info; do
    code=$(http_code "$base/actuator/$path")
    [ "$code" = 404 ] || fail "$base/actuator/$path answers $code, expected 404 (Actuator only on the management port, SEC-55)"
  done
  if $mgmt_exposed; then
    code=$(http_code "http://127.0.0.1:8081/actuator/health")
    [ "$code" = 200 ] || fail "http://127.0.0.1:8081/actuator/health answers $code, expected 200"
    code=$(http_code "http://127.0.0.1:8081/actuator/env")
    [ "$code" = 404 ] || fail "http://127.0.0.1:8081/actuator/env answers $code, expected 404"
    code=$(http_code "http://127.0.0.1:8081/actuator/prometheus")
    case $code in
      401 | 403) ;;
      *) fail "http://127.0.0.1:8081/actuator/prometheus without a token answers $code, expected 401 or 403" ;;
    esac
    ok "Actuator: 404 on 8080; 127.0.0.1:8081 serves health, refuses prometheus without the token, no env"
  else
    rc=0
    curl -s -o /dev/null --max-time 5 "http://127.0.0.1:8081/actuator/health" 2>/dev/null || rc=$?
    case $rc in
      7 | 28 | 52 | 56) ;;
      *) fail "something answers on 127.0.0.1:8081 of the host although ARGOS_MGMT_EXPOSE is not true (curl exit $rc)" ;;
    esac
    ok "Actuator: 404 on 8080 (env, health, prometheus, info); management port not reachable from the host"
  fi
}

# ---------------------------------------------------------------------------------------------
# setup: the setup token protects the wizard (SEC-47, docs/17 section 6.4)

origin_of() { # scheme://authority of a URL
  rest=${1#*://}
  printf '%s://%s' "${1%%://*}" "${rest%%/*}"
}

# request <method> <path> [curl options]: body from stdin when --data-binary @- is given.
# Sets code; the body is in $tmp/body, the headers in $tmp/headers.
request() {
  method=$1
  path=$2
  shift 2
  code=$(curl -s -o "$tmp/body" -D "$tmp/headers" -w '%{http_code}' --max-time 15 -X "$method" \
    -H 'Accept: application/json' -H "Origin: $origin" "$@" "$base$path" || true)
}

# post_json <path>: POST the JSON in $tmp/request (private temporary directory, deleted at once).
post_json() {
  request POST "$1" -H 'Content-Type: application/json' --data-binary "@$tmp/request"
  rm -f "$tmp/request"
}

error_code() { sed -n 's/.*"code" *: *"\([a-z_]*\)".*/\1/p' "$tmp/body" | head -n 1; }

expect() { # expect <status> <error code or -> <description>
  if [ "$code" != "$1" ]; then
    fail "$3: HTTP $code, expected $1"
  fi
  if [ "$2" != - ] && [ "$(error_code)" != "$2" ]; then
    fail "$3: error code '$(error_code)', expected '$2'"
  fi
}

setup_status() { # prints true or false
  request GET /api/v1/setup/status
  [ "$code" = 200 ] || fail "GET /api/v1/setup/status answers $code"
  sed -n -e 's/.*"needs_setup" *: *true.*/true/p' -e 's/.*"needs_setup" *: *false.*/false/p' "$tmp/body" | head -n 1
}

admin_post_with() { # admin_post_with <token>: POST /setup/admin with that token and a dummy body
  printf '{"setup_token":"%s","username":"%s","password":"%s","ip_mode":"masked"}' \
    "$1" "$admin_user" "$(rand_b64url 24)" >"$tmp/request"
  post_json /api/v1/setup/admin
}

check_setup() {
  command -v curl >/dev/null 2>&1 || die "curl is not installed"
  base="http://$(http_host):8080"
  url=$(env_value ARGOS_WEB_PUBLIC_URL)
  [ -n "$url" ] || fail "ARGOS_WEB_PUBLIC_URL is empty in .env"
  origin=$(origin_of "$url")
  needs=$(setup_status)
  if $unattended; then
    [ "$needs" = false ] || fail "unattended setup: the server has no administrator (needs_setup=$needs)"
    admin_post_with "$(rand_b64url 32)"
    expect 404 not_found "unattended setup: POST /api/v1/setup/admin"
    compose exec -T server test ! -e /data/setup-token || fail "unattended setup: /data/setup-token exists"
    if ! $check_admin_login; then
      # No sign-in by default: after the first password change the file grants nothing, and every
      # failed attempt would count towards the account lockout (16 section 7.9).
      ok "unattended setup: an administrator exists (needs_setup=false), wizard closed (404), no setup token file"
      return 0
    fi
    user=$(env_value ARGOS_ADMIN_USERNAME admin)
    [ -r secrets/admin_password ] || fail "secrets/admin_password is not readable"
    pw=
    IFS= read -r pw <secrets/admin_password || true
    printf '{"username":"%s","password":"%s"}' "$(json_escape "$user")" "$(json_escape "$pw")" >"$tmp/request"
    post_json /api/v1/auth/login
    pw=
    expect 200 - "unattended setup: sign-in of $user with secrets/admin_password (--check-admin-login works only before the password was changed)"
    grep -q '"must_change_password" *: *true' "$tmp/body" \
      || fail "unattended setup: the administrator is not asked to change the password"
    # Sign out again (POST /api/v1/auth/logout: the session cookies, Origin and the CSRF cookie
    # repeated in X-Argos-CSRF, 16 section 7.6), so the check leaves no session behind.
    cookies=$(sed -n 's/^[Ss][Ee][Tt]-[Cc][Oo][Oo][Kk][Ii][Ee]: *\([^;]*\).*$/\1/p' "$tmp/headers" | tr -d '\r' \
      | awk 'BEGIN { ORS = "" } { if (NR > 1) print "; "; print }')
    csrf=$(sed -n 's/^[Ss][Ee][Tt]-[Cc][Oo][Oo][Kk][Ii][Ee]: *argos_csrf=\([^;]*\).*$/\1/p' "$tmp/headers" | tr -d '\r' | head -n 1)
    [ -n "$csrf" ] || fail "unattended setup: the sign-in returned no CSRF cookie (argos_csrf)"
    printf 'header = "Cookie: %s"\nheader = "X-Argos-CSRF: %s"\n' "$cookies" "$csrf" >"$tmp/session.cfg"
    cookies=
    csrf=
    request POST /api/v1/auth/logout --config "$tmp/session.cfg"
    rm -f "$tmp/session.cfg" "$tmp/headers"
    expect 204 - "unattended setup: sign-out after the sign-in check"
    ok "unattended setup: wizard closed (404), no setup token file, $user signs in, must change the password, signed out again"
    return 0
  fi
  if [ "$needs" = false ]; then
    admin_post_with "$(rand_b64url 32)"
    expect 404 not_found "setup completed: POST /api/v1/setup/admin"
    compose exec -T server test ! -e /data/setup-token || fail "setup completed but /data/setup-token still exists"
    ok "setup already completed: the wizard answers 404 and the setup token file is gone"
    return 0
  fi
  [ "$needs" = true ] || fail "cannot read needs_setup from GET /api/v1/setup/status"
  file=$(compose exec -T server stat -c '%a %u:%g' /data/setup-token 2>/dev/null | tr -d '\r' || true)
  [ "$file" = "600 $SERVER_UID:$SERVER_UID" ] \
    || fail "/data/setup-token is '${file:-missing}', expected mode 600 owned by $SERVER_UID:$SERVER_UID"
  printf '{"username":"%s","password":"%s","ip_mode":"masked"}' "$admin_user" "$(rand_b64url 24)" >"$tmp/request"
  post_json /api/v1/setup/admin
  expect 403 forbidden "POST /api/v1/setup/admin without a setup token"
  admin_post_with "$(rand_b64url 32)"
  expect 403 forbidden "POST /api/v1/setup/admin with a wrong setup token"
  if ! $complete_setup; then
    ok "setup wizard: token file 0600 uid $SERVER_UID; no token and a wrong token are refused (403); the wizard stays open"
    return 0
  fi
  token=$(compose exec -T server cat /data/setup-token | head -n 1 | tr -d '\r\n')
  [ "${#token}" -ge 32 ] || fail "cannot read the setup token from /data/setup-token"
  if [ -n "$admin_password_file" ]; then
    IFS= read -r password <"$admin_password_file" || true
  else
    password=$(rand_b64url 24)
  fi
  printf '{"setup_token":"%s","username":"%s","password":"%s","ip_mode":"masked"}' \
    "$token" "$(json_escape "$admin_user")" "$(json_escape "$password")" >"$tmp/request"
  post_json /api/v1/setup/admin
  password=
  expect 201 - "POST /api/v1/setup/admin with the setup token"
  cookies=$(sed -n 's/^[Ss][Ee][Tt]-[Cc][Oo][Oo][Kk][Ii][Ee]: *\([^;]*\).*$/\1/p' "$tmp/headers" | tr -d '\r' \
    | awk 'BEGIN { ORS = "" } { if (NR > 1) print "; "; print }')
  [ -n "$cookies" ] || fail "POST /api/v1/setup/admin returned no session cookie"
  printf 'header = "Cookie: %s"\n' "$cookies" >"$tmp/session.cfg"
  cookies=
  admin_post_with "$token"
  expect 403 forbidden "POST /api/v1/setup/admin with the used setup token again"
  token=
  admin_post_with "$(rand_b64url 32)"
  expect 404 not_found "POST /api/v1/setup/admin after the administrator exists"
  needs=$(setup_status)
  [ "$needs" = false ] || fail "GET /api/v1/setup/status still says needs_setup=$needs"
  compose exec -T server test ! -e /data/setup-token || fail "/data/setup-token still exists after the setup"
  request GET /api/v1/auth/me --config "$tmp/session.cfg"
  expect 200 - "GET /api/v1/auth/me with the new session"
  request GET /api/v1/overview --config "$tmp/session.cfg"
  expect 200 - "GET /api/v1/overview with the new session"
  rm -f "$tmp/session.cfg"
  ok "setup wizard: token file 0600 uid $SERVER_UID; no or wrong token 403; administrator created (201); token reuse 403; wizard closed (404); session works"
}

# ---------------------------------------------------------------------------------------------

for c in $ALL_CHECKS; do
  if selected "$c"; then
    "check_$c"
  fi
done
printf 'smoke: passed (%s checks ok, %s not verifiable on this host)\n' "$passed" "$not_verifiable"
