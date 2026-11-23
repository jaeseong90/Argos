#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Cases of argos-compose.sh without a stack (docs/17 section 3.1, ADR-0010 C-171 (4) and C-173,
# docs/03 section 2 "input limits", T-SEC-74): the commands of later work orders refuse, the up
# preflight refuses without starting anything, token create refuses out-of-range input before any
# docker call, issues through argos-admin token create in the running, healthy server container
# (C-173 (4)) and passes its exit codes 0, 4 and 1 on, prints the token once, takes the agent's
# host from tls/server.crt (never from the public URL), and an interrupt ends it with exit 130. Run as root
# in pinned Linux containers by ../argos-compose.test.sh. SRC is a read-only copy of deploy/compose,
# /deploy the read-only deploy/clickhouse and deploy/postgres, T this test directory. docker is the
# test double container/fake-bin/docker, which records every call in FAKE_DOCKER_LOG.
set -u
# shellcheck source=deploy/compose/test/container/lib.sh
. "$T/container/lib.sh"

mkdir -p /tmp/fake-bin
cp "$T/container/fake-bin/docker" /tmp/fake-bin/docker
chmod 0755 /tmp/fake-bin/docker
PATH=/tmp/fake-bin:$PATH
export PATH

LOG=/tmp/docker.log
FAKE_DOCKER_LOG=$LOG
export FAKE_DOCKER_LOG
LINUX="Ubuntu 22.04.5 LTS" # a Linux Docker Engine: the owner checks of the preflight apply
TOKEN_RE='argos_[A-Za-z0-9_-]\{43\}'

# The layout of deploy/ (compose next to clickhouse and postgres), prepared as on a Linux host.
ROOT=/tmp/deploy
WORK=$ROOT/compose
cd /
rm -rf "$ROOT"
mkdir -p "$ROOT"
cp -R "$SRC" "$WORK"
cp -R /deploy/clickhouse /deploy/postgres "$ROOT/"
rm -rf "$WORK/.env" "$WORK/secrets" "$WORK/tls" "$WORK/compose.override.yaml" "$WORK/test"
cd "$WORK" || exit 1
env FAKE_DOCKER_OS="$LINUX" sh ./argos-env-init.sh --ram-mb 16384 --public-url http://ui.example.test:8080 >/dev/null 2>&1 \
  || { echo "FAIL  argos-env-init.sh did not prepare the directory"; exit 1; }
mkdir -p tls
printf 'certificate\n' >tls/server.crt
printf 'key\n' >tls/server.key
chmod 0644 tls/server.crt
chmod 0600 tls/server.key
chown 10001:10001 tls/server.key

# ac <args...>: argos-compose.sh on a Linux Docker Engine, with a fresh docker call log.
ac() {
  : >"$LOG"
  env FAKE_DOCKER_OS="$LINUX" sh ./argos-compose.sh "$@"
}
no_docker_call() { [ ! -s "$LOG" ] || { echo "docker was called:"; cat "$LOG"; return 1; }; }
no_compose_up() { ! grep -qE '(^| )up( |$)' "$LOG" || { echo "docker compose up was called:"; cat "$LOG"; return 1; }; }
compose_up_called() { grep -qE '(^| )up -d --wait' "$LOG"; }

# --- commands of later work orders (C-171 (4)) --------------------------------------------------
for c in upgrade backup diag restore; do
  expect_exit 2 "C-171 (4): '$c' exits 2" ac "$c"
  output_has "not provided in this version" "C-171 (4): '$c' says it is not provided in this version"
  check "C-171 (4): '$c' calls no docker" no_docker_call
done
expect_exit 2 "argos-compose: an unknown command exits 2" ac frobnicate
output_has "unknown command 'frobnicate'" "argos-compose: the unknown command is named"
check "argos-compose: an unknown command calls no docker" no_docker_call
expect_exit 2 "argos-compose: no command exits 2" ac
output_has "Usage: ./argos-compose.sh <command>" "argos-compose: no command shows the usage"

# --- up: the preflight refuses (exit 2) and docker compose up is never called ---------------------
expect_exit 0 "argos-compose up: the prepared directory on a Linux Docker Engine starts (control case)" ac up
check "argos-compose up: the control case calls docker compose up -d --wait" compose_up_called

up_refused() { # up_refused <description> <expected message>
  expect_exit 2 "T-SEC-74 preflight: $1: up exits 2" ac up
  output_has "$2" "T-SEC-74 preflight: $1: the message names it"
  check "T-SEC-74 preflight: $1: docker compose up is not called" no_compose_up
}
aside() { mv "$1" "/tmp/aside.$$"; }
back() { mv "/tmp/aside.$$" "$1"; }

aside .env
up_refused ".env missing" ".env is missing: run ./argos-env-init.sh"
back .env
aside secrets
up_refused "secrets/ missing" "secrets/ is missing: run ./argos-env-init.sh"
back secrets
aside tls/server.crt
up_refused "tls/server.crt missing" "tls/server.crt is missing"
back tls/server.crt
aside tls/server.key
up_refused "tls/server.key missing" "tls/server.key is missing"
back tls/server.key
cp secrets/metrics_token /tmp/metrics.backup
: >secrets/metrics_token
up_refused "an empty secret" "secrets/metrics_token is missing or empty"
cat /tmp/metrics.backup >secrets/metrics_token # in place: BusyBox cp would recreate it owned by root
rm -f /tmp/metrics.backup

mode_refused() { # mode_refused <path> <mode> <restore mode> <expected message>
  chmod "$2" "$1"
  up_refused "$1 $2" "$4"
  chmod "$3" "$1"
}
mode_refused .env 0644 0600 ".env must have mode 0600"
mode_refused secrets 0755 0700 "secrets/ must have mode 0700"
mode_refused secrets/pg_app_password 0644 0600 "secrets/pg_app_password must have mode 0600"
mode_refused secrets/clickhouse.env 0644 0600 "secrets/clickhouse.env must have mode 0600"
mode_refused tls/server.key 0644 0600 "tls/server.key must have mode 0600"

chown 0:0 secrets/pg_app_password
up_refused "secrets/pg_app_password owned by root" "secrets/pg_app_password must belong to uid 10001"
chown 10001:10001 secrets/pg_app_password
chown 0:0 tls/server.key
up_refused "tls/server.key owned by root" "tls/server.key must belong to uid 10001"
chown 10001:10001 tls/server.key
chmod 0600 tls/server.crt
up_refused "tls/server.crt 0600 owned by root" "tls/server.crt must be readable by uid 10001"
chmod 0644 tls/server.crt

# Bind-mounted configuration that the containers read as their own users: a checkout under umask
# 027 leaves it unreadable for them (0640 files, 0750 directories) on a Linux Docker Engine.
config_refused() { # config_refused <path> <mode> <restore mode>
  chmod "$2" "$1"
  expect_exit 2 "17 section 3.1 preflight: $1 $2 (umask 027 checkout): up exits 2" ac up
  output_has "the containers cannot read these configuration files" "17 section 3.1 preflight: $1 $2: the problem is named"
  output_has "$1" "17 section 3.1 preflight: $1 $2: the file is named"
  output_has "chmod -R a+rX ../clickhouse/config.d ../clickhouse/users.d ../postgres config" \
    "17 section 3.1 preflight: $1 $2: the message gives the chmod that fixes it"
  check "17 section 3.1 preflight: $1 $2: docker compose up is not called" no_compose_up
  chmod "$3" "$1"
}
config_refused config/server/application-compose.yaml 0640 0644
config_refused config/postgres/init 0750 0755
config_refused ../clickhouse/users.d/argos.xml 0640 0644
config_refused ../postgres/roles.sql 0640 0644

# compose.proxy.yaml: behind the proxy 8080 is published on 127.0.0.1 only (17 section 3.10).
proxy_up() { # proxy_up [VAR=value...]: up with ARGOS_PROXY=true on a Linux Docker Engine
  : >"$LOG"
  env ARGOS_PROXY=true "$@" FAKE_DOCKER_OS="$LINUX" sh ./argos-compose.sh up
}
expect_exit 2 "T-SEC-75 preflight: ARGOS_PROXY=true with ARGOS_HTTP_BIND=0.0.0.0 of .env: up exits 2" proxy_up
output_has "ARGOS_PROXY=true needs ARGOS_HTTP_BIND=127.0.0.1" "T-SEC-75 preflight: ARGOS_PROXY=true with 0.0.0.0: the message names it"
check "T-SEC-75 preflight: ARGOS_PROXY=true with 0.0.0.0: docker compose up is not called" no_compose_up
expect_exit 2 "T-SEC-75 preflight: ARGOS_PROXY=true with ARGOS_HTTP_BIND=10.0.0.5: up exits 2" proxy_up ARGOS_HTTP_BIND=10.0.0.5
output_has "(now 10.0.0.5)" "T-SEC-75 preflight: ARGOS_PROXY=true with 10.0.0.5: the value is named"
expect_exit 0 "T-SEC-75 preflight: ARGOS_PROXY=true with ARGOS_HTTP_BIND=127.0.0.1 starts" proxy_up ARGOS_HTTP_BIND=127.0.0.1
with_proxy_file() { grep -q -- '-f compose.proxy.yaml' "$LOG"; }
check "T-SEC-75 preflight: ARGOS_PROXY=true runs docker compose with compose.proxy.yaml" with_proxy_file

FAKE_IMAGE_MISSING=1
export FAKE_IMAGE_MISSING
up_refused "the server image missing" "is not in the local Docker image store: build it with ./gradlew :server:jibDockerBuild"
unset FAKE_IMAGE_MISSING

expect_exit 2 "argos-compose up: no docker exits 2" env PATH=/usr/bin:/bin sh ./argos-compose.sh up
output_has "docker is not installed" "argos-compose up: the missing docker is named"
FAKE_DOCKER_DOWN=1
export FAKE_DOCKER_DOWN
expect_exit 2 "argos-compose up: Docker daemon down exits 2" ac up
output_has "the Docker daemon is not running" "argos-compose up: the stopped daemon is named"
check "argos-compose up: with the daemon down docker compose up is not called" no_compose_up
unset FAKE_DOCKER_DOWN
expect_exit 0 "argos-compose up: starts again after every restore (control case)" ac up

# --- token create: input limits (docs/03 section 2), refused before any docker call ---------------
A64=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
E32=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
tc_refused() { # tc_refused <description> <token create arguments...>
  case_name="input limit: token create $1" # expect_exit of lib.sh sets desc
  shift
  expect_exit 2 "$case_name: exit 2" ac token create "$@"
  check "$case_name: no docker call" no_docker_call
}
tc_refused "--app of 65 characters" --app "${A64}a" --env prod
tc_refused "--app with a space" --app "order api" --env prod
tc_refused "--app with a quote" --app "order'api" --env prod
tc_refused "--app with a slash" --app "order/api" --env prod
tc_refused "--app empty" --app "" --env prod
tc_refused "without --app" --env prod
tc_refused "--app without a value" --app
tc_refused "--env of 33 characters" --app order-api --env "${E32}e"
tc_refused "--env with a semicolon" --app order-api --env "prod;x"
tc_refused "--env with a non-ASCII letter" --app order-api --env "pröd"
tc_refused "--env empty" --app order-api --env ""
tc_refused "--expires-days 0" --app order-api --env prod --expires-days 0
tc_refused "--expires-days 3651" --app order-api --env prod --expires-days 3651
tc_refused "--expires-days abc" --app order-api --env prod --expires-days abc
tc_refused "--expires-days 07 (leading zero)" --app order-api --env prod --expires-days 07
tc_refused "--expires-days -1" --app order-api --env prod --expires-days -1
tc_refused "an unknown option" --app order-api --env prod --frobnicate
expect_exit 2 "token: without a subcommand exits 2" ac token
check "token: without a subcommand calls no docker" no_docker_call
expect_exit 2 "token: a subcommand other than create exits 2" ac token revoke --app order-api --env prod
output_has "only 'create' is provided in this version" "token: the other subcommand is refused by name"
check "token: a subcommand other than create calls no docker" no_docker_call

# --- token create: the accepted path (fake argos-admin in the server container) -------------------
# token_run <arguments...>: token create; the raw output stays in /tmp/token.raw (umask 077), the
# test output shows it with the token replaced by argos_****.
token_run() {
  : >"$LOG"
  rc=0
  (umask 077 && env FAKE_DOCKER_OS="$LINUX" FAKE_ADMIN_ARGS=/tmp/admin.args sh ./argos-compose.sh token create "$@" \
    >/tmp/token.raw 2>&1) || rc=$?
  sed "s/$TOKEN_RE/argos_****/g" /tmp/token.raw
  return "$rc"
}
expect_exit 0 "input limit: token create accepts --app of 64, --env of 32 characters and --expires-days 3650" \
  token_run --app "$A64" --env "$E32" --expires-days 3650
server_issues() {
  n=$(grep -c "$TOKEN_RE" /tmp/token.raw)
  [ "$n" -eq 1 ] || { echo "the token is printed $n times"; return 1; }
  t=$(grep -o "$TOKEN_RE" /tmp/token.raw | head -n 1)
  p=$(printf '%s' "$t" | cut -c1-14)
  grep -qF "prefix $p" /tmp/token.raw || { echo "the prefix of the printed token is not named"; return 1; }
  grep -qF "admin token create --app $A64 --env $E32 --actor argos-compose --json --expires-days 3650" /tmp/admin.args \
    || { echo "argos-admin did not get the options:"; cat /tmp/admin.args; return 1; }
  grep -q 'compose .*exec -T -e JAVA_TOOL_OPTIONS= server java -Xmx256m .*-cp @/opt/argos/app/jib-classpath-file io.argos.server.ServerApplication admin token create' "$LOG" \
    || { echo "argos-admin did not run in the server container with its classpath:"; cat "$LOG"; return 1; }
  ! grep -q 'psql' "$LOG" || { echo "psql was called"; return 1; }
  ! grep -qF "$t" "$LOG" || { echo "the token is on a docker command line"; return 1; }
  t=
}
check "C-173 (4): argos-admin token create issues in the server container; the token is printed once and never on a command line" server_issues
token_run --app order-api --env prod >/dev/null || true
no_expiry_option() { ! grep -q -- '--expires-days' /tmp/admin.args; }
check "C-173 (4): without --expires-days the server's default (365 days) applies" no_expiry_option

# Refusals and failures of the server's command pass on: 4 (limit) and 1, without a token.
no_token_printed() { ! grep -q "$TOKEN_RE" /tmp/token.raw; }
limit_run() { (FAKE_ADMIN_EXIT=4 && export FAKE_ADMIN_EXIT && token_run "$@"); }
expect_exit 4 "C-173 (4): the limit of 5 active tokens exits 4" limit_run --app order-api --env prod
output_has "maximum of 5 active agent tokens" "C-173 (4): the limit is named"
check "C-173 (4): no token after the limit" no_token_printed
failed_run() { (FAKE_ADMIN_EXIT=1 && export FAKE_ADMIN_EXIT && token_run "$@"); }
expect_exit 1 "C-173 (4): a failure of argos-admin exits 1" failed_run --app order-api --env prod
output_has "cannot connect as argos_app" "C-173 (4): the failure's message is shown"
output_has "the token was not created" "C-173 (4): the failure says no token was created"
check "C-173 (4): no token after a failure" no_token_printed
not_running_run() { (FAKE_PS_EMPTY=1 && export FAKE_PS_EMPTY && token_run "$@"); }
expect_exit 2 "C-173 (4): without a running server exits 2" not_running_run --app order-api --env prod
output_has "the server service is not running" "C-173 (4): the stopped server is named"
unhealthy_run() { (FAKE_HEALTH=starting && export FAKE_HEALTH && token_run "$@"); }
expect_exit 2 "C-173 (4): with a server that is not healthy exits 2" unhealthy_run --app order-api --env prod
output_has "the server service is not healthy (starting)" "C-173 (4): the server's health is named"
no_admin_call() { ! grep -q 'admin token create' "$LOG"; }
check "C-173 (4): argos-admin is not run before the server is healthy" no_admin_call

# The agent configuration example: the host of tls/server.crt (its first DNS name, else its first
# IP address), never the public URL of the UI (ui.example.test); <argos-host> when unknown.
expect_exit 0 "C-173: token create with a certificate openssl cannot read" token_run --app order-api --env prod
output_has "address: <argos-host>:9100" "C-173 example: without a readable certificate the host is the <argos-host> placeholder"
output_has "a name in the subject alternative names of" "C-173 example: the placeholder says which name to use"
output_has "dir: /opt/argos-agent/logs" "C-173 example: the configuration has log.dir as in the guide"
output_has "token-file: /opt/argos-agent/token" "C-173 example: the configuration names the token file"
no_public_url() { ! grep -q 'ui.example.test' /tmp/token.raw; }
check "C-173 example: the public URL of the UI is not used for the agent address" no_public_url
rm -f tls/server.crt
expect_exit 0 "C-173: token create without tls/server.crt" token_run --app order-api --env prod
output_has "address: <argos-host>:9100" "C-173 example: without tls/server.crt the host is the <argos-host> placeholder"
printf 'certificate\n' >tls/server.crt
chmod 0644 tls/server.crt

if command -v openssl >/dev/null 2>&1; then
  cert() { # cert <file> [subjectAltName]
    if [ $# -ge 2 ]; then
      openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 1 -subj /CN=argos-test \
        -addext "subjectAltName=$2" -keyout /tmp/cert.key -out "$1" >/dev/null 2>&1
    else
      openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 1 -subj /CN=argos-test \
        -keyout /tmp/cert.key -out "$1" >/dev/null 2>&1
    fi
  }
  cert /tmp/san-dns.crt "IP:192.0.2.10,DNS:argos.test,DNS:other.argos.test"
  cert /tmp/san-ip.crt "IP:192.0.2.10"
  cert /tmp/san-ipv6.crt "IP:2001:db8::10"
  cert /tmp/san-none.crt
  rm -f /tmp/cert.key
  with_cert() { # with_cert <certificate> <description> <expected line>
    cp "$1" tls/server.crt
    expect_exit 0 "C-173: token create with $2" token_run --app order-api --env prod
    output_has "$3" "C-173 example: $2 gives '$3'"
  }
  with_cert /tmp/san-dns.crt "SAN IP:192.0.2.10, DNS:argos.test, DNS:other.argos.test" "address: argos.test:9100"
  check "C-173 example: with a certificate the public URL of the UI is not used either" no_public_url
  with_cert /tmp/san-ip.crt "SAN IP:192.0.2.10 only" "address: 192.0.2.10:9100"
  with_cert /tmp/san-ipv6.crt "SAN IP:2001:db8::10 only" "address: [2001:DB8:0:0:0:0:0:10]:9100"
  with_cert /tmp/san-none.crt "no subject alternative names" "address: <argos-host>:9100"
  # OpenSSL without 'x509 -ext' (before 1.1.1, LibreSSL): the -text output is parsed instead.
  mkdir -p /tmp/old-openssl
  real_openssl=$(command -v openssl)
  # shellcheck disable=SC2016 # writes a script: "$@" and $a belong to it
  printf '#!/bin/sh\nfor a in "$@"; do [ "$a" != -ext ] || { echo "unknown option -ext" >&2; exit 1; }; done\nexec %s "$@"\n' \
    "$real_openssl" >/tmp/old-openssl/openssl
  chmod 0755 /tmp/old-openssl/openssl
  cp /tmp/san-dns.crt tls/server.crt
  old_openssl_run() { (PATH=/tmp/old-openssl:$PATH && export PATH && token_run "$@"); }
  expect_exit 0 "C-173: token create with an OpenSSL without x509 -ext" old_openssl_run --app order-api --env prod
  output_has "address: argos.test:9100" "C-173 example: without x509 -ext the -text output gives 'address: argos.test:9100'"
  printf 'certificate\n' >tls/server.crt
  chmod 0644 tls/server.crt
else
  echo "SKIP  C-173 example: the host from the certificate needs openssl (covered on the PostgreSQL image)"
fi
rm -f /tmp/token.raw /tmp/admin.args

# --- token create: an interrupt (Ctrl-C) ends it with exit 130, no token, no temporary files ------
# fake-bin/docker sends SIGINT to argos-compose.sh while it waits for argos-admin in the server.
mkdir -p /tmp/tc-tmp
tc_interrupted() {
  : >"$LOG"
  rc=0
  (umask 077 && env TMPDIR=/tmp/tc-tmp FAKE_DOCKER_OS="$LINUX" FAKE_SIGNAL=INT FAKE_SIGNAL_TARGET=./argos-compose.sh \
    sh ./argos-compose.sh token create --app order-api --env prod >/tmp/token.raw 2>&1) || rc=$?
  sed "s/$TOKEN_RE/argos_****/g" /tmp/token.raw
  return "$rc"
}
expect_exit 130 "token create: SIGINT while argos-admin runs ends it with exit 130" tc_interrupted
check "token create: after SIGINT no token is printed" no_token_printed
tc_tmp_empty() { [ -z "$(ls -A /tmp/tc-tmp)" ] || { ls -A /tmp/tc-tmp; return 1; }; }
check "token create: after SIGINT its temporary directory is removed" tc_tmp_empty
rm -f /tmp/token.raw

finish
