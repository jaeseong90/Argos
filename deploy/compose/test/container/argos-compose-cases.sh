#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# req: N-11, N-16, O-R02, SEC-74, SEC-75
#
# Cases of argos-compose.sh without a stack (docs/17 section 3.1, ADR-0010 C-171 (4) and C-173,
# docs/03 section 2 "input limits", T-SEC-74): backup, restore and diag run their scripts with the
# same arguments from the caller's directory and pass their exit codes on (WO-43; stub backup.sh and
# restore.sh), upgrade and upgrade --rollback keep the version rules and exit codes 0/1/2/8 and change
# nothing before the backup succeeded (T-NFR-11a logic, O-R02 agent warning), the up
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

# --- backup, restore and diag run their scripts (WO-43, replaces C-171 (4) 'not provided') -------
# Stubs of backup.sh and restore.sh (lane B's scripts are tested by backup-cases.sh): they record
# their arguments and working directory and answer like the real ones (backup.sh --json: the last
# line is the JSON result).
STUB_SET=/tmp/backups/argos-backup-20261126T000000Z-pre
cat >"$WORK/backup.sh" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >>/tmp/backup.args
pwd >/tmp/backup.pwd
if [ "\${FAKE_BACKUP_EXIT:-0}" -ne 0 ]; then echo "backup.sh: pg_dump failed" >&2; exit "\$FAKE_BACKUP_EXIT"; fi
mkdir -p $STUB_SET
echo "PostgreSQL dump written"
printf '{"set":"%s","files":["pg.dump","manifest.json"],"bytes":10,"clickhouse":null}\n' "$STUB_SET"
EOF
cat >"$WORK/restore.sh" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >>/tmp/restore.args
pwd >/tmp/restore.pwd
if [ "${FAKE_RESTORE_EXIT:-0}" -ne 0 ]; then echo "restore.sh: refused" >&2; exit "$FAKE_RESTORE_EXIT"; fi
echo "restored"
EOF
rm -f /tmp/backup.args /tmp/restore.args
mkdir -p /tmp/caller
from_caller() { (cd /tmp/caller && env FAKE_DOCKER_OS="$LINUX" sh "$WORK/argos-compose.sh" "$@"); }
expect_exit 0 "WO-43: 'backup' runs backup.sh" from_caller backup --dir rel-dir --label nightly
backup_passed_on() {
  [ "$(cat /tmp/backup.args)" = "--dir rel-dir --label nightly" ] || { cat /tmp/backup.args; return 1; }
  [ "$(cat /tmp/backup.pwd)" = /tmp/caller ] || { echo "ran in $(cat /tmp/backup.pwd)"; return 1; }
}
check "WO-43: 'backup' passes its arguments unchanged and runs from the caller's directory (relative paths as with ./backup.sh)" backup_passed_on
expect_exit 3 "WO-43: 'backup' exits with the exit code of backup.sh" env FAKE_BACKUP_EXIT=3 sh -c "cd /tmp/caller && FAKE_DOCKER_OS='$LINUX' sh '$WORK/argos-compose.sh' backup"
expect_exit 0 "WO-43: 'restore' runs restore.sh" from_caller restore /tmp/some-set --clickhouse --yes
restore_passed_on() { [ "$(tail -n 1 /tmp/restore.args)" = "/tmp/some-set --clickhouse --yes" ] || { cat /tmp/restore.args; return 1; }; }
check "WO-43: 'restore' passes its arguments unchanged" restore_passed_on
expect_exit 2 "WO-43: 'diag' runs diag-bundle.sh (its usage error exits 2)" ac diag --since 9x
output_has "diag-bundle.sh: --since must be hours" "WO-43: 'diag' shows the message of diag-bundle.sh"
check "WO-43: 'diag' with a usage error calls no docker" no_docker_call
mv backup.sh /tmp/backup.sh.aside
expect_exit 2 "WO-43: 'backup' without backup.sh exits 2" ac backup
output_has "backup.sh is missing next to argos-compose.sh" "WO-43: the missing backup.sh is named"
mv /tmp/backup.sh.aside backup.sh
rm -f /tmp/backup.args /tmp/restore.args

# ARGOS_CH_BACKUP_DIR adds the ClickHouse backup target compose.chbackup.yaml (lane B's file).
with_chbackup() { env ARGOS_CH_BACKUP_DIR=/mnt/argos-ch-backup sh ./argos-compose.sh files | grep -q -- '-f compose.chbackup.yaml'; }
without_chbackup() { ! sh ./argos-compose.sh files | grep -q chbackup; }
check "WO-43: ARGOS_CH_BACKUP_DIR adds -f compose.chbackup.yaml to the compose files" with_chbackup
check "WO-43: without ARGOS_CH_BACKUP_DIR compose.chbackup.yaml is not used" without_chbackup

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
# compose.chbackup.yaml (WO-43) mounts config/clickhouse/backups.xml for ClickHouse: checked only
# when ARGOS_CH_BACKUP_DIR selects that overlay.
chmod 0640 config/clickhouse/backups.xml
chbackup_up() { : >"$LOG"; env ARGOS_CH_BACKUP_DIR=/mnt/argos-ch-backup FAKE_DOCKER_OS="$LINUX" sh ./argos-compose.sh up; }
expect_exit 2 "WO-43 preflight: config/clickhouse/backups.xml 0640 with ARGOS_CH_BACKUP_DIR: up exits 2" chbackup_up
output_has "config/clickhouse/backups.xml" "WO-43 preflight: config/clickhouse/backups.xml 0640: the file is named"
check "WO-43 preflight: config/clickhouse/backups.xml 0640: docker compose up is not called" no_compose_up
chmod 0644 config/clickhouse/backups.xml

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

# --- upgrade and upgrade --rollback (T-NFR-11a logic; docs/17 section 4.4 and 8, O-R02, WO-43) ----
# The running server is argos-server:0.9.0 (id sha256:old); FAKE_IMAGES is the local image store.
cat >/tmp/images.txt <<'EOF'
argos-server:0.9.0 sha256:old 0.9.0
argos-server:0.10.0 sha256:new 0.10.0
argos-server:0.8.5 sha256:older 0.8.5
argos-server:1.0.0 sha256:one 1.0.0
argos-server:2.0.0 sha256:two 2.0.0
argos-server:nolabel sha256:nolabel -
argos-server:0.9.0-rc.2 sha256:rc 0.9.0-rc.2
argos-server:bad sha256:bad 0.9
EOF
cp .env /tmp/env.orig
reset_upgrade() {
  cat /tmp/env.orig >.env # in place: owner and mode stay
  rm -f upgrade-state.json /tmp/backup.args /tmp/restore.args
  rm -rf /tmp/backups
}
# ug <arguments...>: upgrade with the fake image store, no terminal on standard input.
ug() {
  : >"$LOG"
  env FAKE_DOCKER_OS="$LINUX" FAKE_IMAGES=/tmp/images.txt FAKE_IMAGE_SERVER=argos-server:0.9.0 FAKE_IMAGE_ID_SERVER=sha256:old \
    sh ./argos-compose.sh upgrade "$@" </dev/null
}
env_sum() { cksum <.env; }
no_backup_call() { [ ! -e /tmp/backup.args ] || { echo "backup.sh ran: $(cat /tmp/backup.args)"; return 1; }; }
no_state() { [ ! -e upgrade-state.json ] || { echo "upgrade-state.json exists"; return 1; }; }

reset_upgrade
before_env=$(env_sum)
env_unchanged() { [ "$(env_sum)" = "$before_env" ] || { echo ".env changed"; diff /tmp/env.orig .env; return 1; }; }
refused_upgrade() { # refused_upgrade <exit> <description> <message> <upgrade arguments...>
  want=$1 udesc=$2 umsg=$3
  shift 3
  expect_exit "$want" "T-NFR-11a: $udesc: exit $want" ug "$@"
  output_has "$umsg" "T-NFR-11a: $udesc: the message says why"
  check "T-NFR-11a: $udesc: backup.sh does not run" no_backup_call
  check "T-NFR-11a: $udesc: .env is unchanged" env_unchanged
  check "T-NFR-11a: $udesc: docker compose up is not called" no_compose_up
  check "T-NFR-11a: $udesc: no upgrade-state.json" no_state
}
refused_upgrade 2 "upgrade without options" "usage: upgrade --image REF"
check "T-NFR-11a: upgrade without options calls no docker" no_docker_call
refused_upgrade 2 "an image reference with a space" "is not an image reference" --image "argos-server 1.0"
refused_upgrade 2 "--rollback with --image" "--rollback takes no --image" --rollback --image argos-server:0.10.0
refused_upgrade 2 "an unknown option" "upgrade: unknown option --frobnicate" --frobnicate
refused_upgrade 2 "a downgrade (0.9.0 -> 0.8.5)" "refused: 0.8.5 is older than the running 0.9.0" --image argos-server:0.8.5 --yes
refused_upgrade 2 "a pre-release of the running version (0.9.0 -> 0.9.0-rc.2, SemVer precedence)" "refused: 0.9.0-rc.2 is older" --image argos-server:0.9.0-rc.2 --yes
refused_upgrade 2 "a skipped major version (0.9.0 -> 2.0.0)" "skips a major version" --image argos-server:2.0.0 --yes
refused_upgrade 2 "an image without the version label" "has no org.opencontainers.image.version label" --image argos-server:nolabel --yes
refused_upgrade 2 "a version that is not MAJOR.MINOR.PATCH" "which is not MAJOR.MINOR.PATCH" --image argos-server:bad --yes
refused_upgrade 2 "an image neither local nor pullable" "is not in the local image store and cannot be pulled" --image registry.example.test/argos-server:9.9.9 --yes
unhealthy_ug() { (FAKE_HEALTH=starting && export FAKE_HEALTH && ug "$@"); }
expect_exit 2 "T-NFR-11a: a stack that is not healthy: exit 2" unhealthy_ug --image argos-server:0.10.0 --yes
output_has "is not healthy (starting)" "T-NFR-11a: the unhealthy service is named"
check "T-NFR-11a: a stack that is not healthy: backup.sh does not run" no_backup_call
expect_exit 2 "T-NFR-11a: without --yes and without a terminal: exit 2" ug --image argos-server:0.10.0
output_has "give --yes to run without a terminal" "T-NFR-11a: without a terminal --yes is asked for"
check "T-NFR-11a: without --yes: backup.sh does not run" no_backup_call
check "T-NFR-11a: without --yes: .env is unchanged" env_unchanged
expect_exit 0 "T-NFR-11a: the version that runs already (0.9.0): exit 0" ug --image argos-server:0.9.0 --yes
output_has "runs version 0.9.0 already" "T-NFR-11a: the same version: nothing to do"
check "T-NFR-11a: the same version: backup.sh does not run" no_backup_call
check "T-NFR-11a: the same version: .env is unchanged" env_unchanged

# A failed backup: nothing changes (exit 1).
backup_fails() { (FAKE_BACKUP_EXIT=1 && export FAKE_BACKUP_EXIT && ug "$@"); }
expect_exit 1 "T-NFR-11a: backup.sh fails: exit 1" backup_fails --image argos-server:0.10.0 --yes
output_has "the backup failed (backup.sh exit code 1); nothing was changed: the server runs 0.9.0" "T-NFR-11a: a failed backup says that nothing was changed"
check "T-NFR-11a: a failed backup: .env is unchanged" env_unchanged
check "T-NFR-11a: a failed backup: docker compose up is not called" no_compose_up
check "T-NFR-11a: a failed backup: no upgrade-state.json" no_state

# The upgrade 0.9.0 -> 0.10.0 (minor 10 > 9 numerically), with connected agents outside 0.8 to 0.10.
reset_upgrade
# psql output in the order of the query (ORDER BY the version text).
printf '|1\n0.10.0|3\n0.11.0|1\n0.7.1|2\n0.8.2|1\n' >/tmp/agents.txt
agents_ug() { (FAKE_AGENT_VERSIONS=/tmp/agents.txt && export FAKE_AGENT_VERSIONS && ug "$@"); }
expect_exit 0 "T-NFR-11a: upgrade 0.9.0 -> 0.10.0 --yes: exit 0" agents_ug --image argos-server:0.10.0 --yes
cp "$LAST" /tmp/upgrade.out
output_has "connected agents outside the range the server 0.10.0 accepts (0.8 to 0.10, O-R02): 4 agents: unknown (1), 0.11.0 (1), 0.7.1 (2)" \
  "O-R02: the agents outside X.(Y-2) to X.Y are counted and named (newer and older versions, no version)"
output_lacks "0.8.2 (1)" "O-R02: an agent at X.(Y-2) is not named"
output_has "Upgraded to 0.10.0 in" "T-NFR-11a: the upgrade prints its duration"
backup_labelled() { grep -qxF -- "--label pre-upgrade-0.9.0-0.10.0 --json" /tmp/backup.args || { cat /tmp/backup.args; return 1; }; }
check "T-NFR-11a: the mandatory PostgreSQL backup runs as backup.sh --label pre-upgrade-0.9.0-0.10.0 --json" backup_labelled
env_image_new() {
  [ "$(grep -c '^ARGOS_SERVER_IMAGE=' .env)" -eq 1 ] || { echo "ARGOS_SERVER_IMAGE is not set once"; return 1; }
  grep -qx 'ARGOS_SERVER_IMAGE=argos-server:0.10.0' .env || { grep ARGOS_SERVER_IMAGE .env; return 1; }
  [ "$(grep -v '^ARGOS_SERVER_IMAGE=' .env | cksum)" = "$(grep -v '^ARGOS_SERVER_IMAGE=' /tmp/env.orig | cksum)" ] || { echo "other lines changed"; return 1; }
  [ "$(mode_of .env)" = "-rw-------" ] && [ "$(owner_of .env)" = 0 ] || { echo ".env $(mode_of .env) owner $(owner_of .env)"; return 1; }
}
check "T-NFR-11a: .env names the new image once, its other lines and its mode 0600 and owner stay" env_image_new
server_recreated() { grep -qE 'up -d --wait --wait-timeout 600 server$' "$LOG" || { cat "$LOG"; return 1; }; }
check "T-NFR-11a: the server is started again with docker compose up -d --wait server" server_recreated
yesterday=$(date -u -d "@$(($(date -u +%s) - 86400))" +%Y-%m-%d)
consistency_run() { grep -q "server java .* admin consistency check --date $yesterday" "$LOG" || { cat "$LOG"; return 1; }; }
check "T-NFR-11a: argos-admin consistency check of yesterday ($yesterday) runs in the server container" consistency_run
state_ok() {
  [ "$(mode_of upgrade-state.json)" = "-rw-------" ] || { echo "mode $(mode_of upgrade-state.json)"; return 1; }
  for kv in '"status": "done"' '"from_image": "argos-server:0.9.0"' '"from_image_id": "sha256:old"' '"from_version": "0.9.0"' \
    '"to_image": "argos-server:0.10.0"' '"to_version": "0.10.0"' "\"backup_set\": \"$STUB_SET\""; do
    grep -qF "$kv" upgrade-state.json || { echo "missing $kv"; cat upgrade-state.json; return 1; }
  done
}
check "T-NFR-11a: upgrade-state.json (0600) names the previous image and its id, the versions and the pre-upgrade backup set" state_ok
agents_query() { ! grep -q 'pg_backup_password' /tmp/upgrade.out && grep -q 'postgres sh -c .*argos_backup' "$LOG"; }
check "O-R02: the agent versions are read as argos_backup inside the postgres container (no password in the output)" agents_query

# Rollback of that upgrade: the previous image in .env, restore.sh with the pre-upgrade set.
expect_exit 0 "T-NFR-11a: upgrade --rollback --yes: exit 0" ug --rollback --yes
restore_called() { grep -qxF "$STUB_SET --yes" /tmp/restore.args || { cat /tmp/restore.args 2>/dev/null; return 1; }; }
check "T-NFR-11a: rollback runs restore.sh with the pre-upgrade backup set and --yes" restore_called
env_image_old() { grep -qx 'ARGOS_SERVER_IMAGE=argos-server:0.9.0' .env && [ "$(mode_of .env)" = "-rw-------" ]; }
check "T-NFR-11a: rollback sets the previous image in .env (mode 0600 kept)" env_image_old
check "T-NFR-11a: rollback starts the server again with docker compose up -d --wait server" server_recreated
rolled_back() { grep -qF '"status": "rolled_back"' upgrade-state.json; }
check "T-NFR-11a: upgrade-state.json says rolled_back" rolled_back
expect_exit 2 "T-NFR-11a: a second rollback exits 2" ug --rollback --yes
output_has "was rolled back already" "T-NFR-11a: the second rollback is refused by name"

# The server does not become healthy with the new image: exit 8 and the rollback command.
reset_upgrade
up_fails() { (FAKE_UP_EXIT=1 && export FAKE_UP_EXIT && ug "$@"); }
expect_exit 8 "T-NFR-11a: the new server does not become healthy: exit 8 (rollback needed)" up_fails --image argos-server:0.10.0 --yes
output_has "./argos-compose.sh upgrade --rollback --yes" "T-NFR-11a: exit 8 gives the rollback command"
failed_state() { grep -qF '"status": "failed"' upgrade-state.json; }
check "T-NFR-11a: after exit 8 upgrade-state.json says failed" failed_state
restore_fails() { (FAKE_RESTORE_EXIT=1 && export FAKE_RESTORE_EXIT && ug "$@"); }
expect_exit 1 "T-NFR-11a: rollback whose restore.sh fails: exit 1" restore_fails --rollback --yes
output_has "restore.sh failed (exit code 1)" "T-NFR-11a: the failed restore is named"
expect_exit 0 "T-NFR-11a: rollback after the failed upgrade: exit 0" ug --rollback --yes
check "T-NFR-11a: rollback after exit 8 sets the previous image in .env" env_image_old

# A failed consistency check is reported, the upgrade stays done; a failed agent query only warns;
# ARGOS_SERVER_IMAGE of the environment is named.
reset_upgrade
cons_fails() { (FAKE_CONSISTENCY_EXIT=1 FAKE_PSQL_EXIT=2 ARGOS_SERVER_IMAGE=argos-server:0.9.0 && export FAKE_CONSISTENCY_EXIT FAKE_PSQL_EXIT ARGOS_SERVER_IMAGE && ug "$@"); }
expect_exit 0 "T-NFR-11a: a consistency check that reports a mismatch: exit 0" cons_fails --image argos-server:0.10.0 --yes
output_has "the consistency check of $yesterday reported a problem (exit code 1)" "T-NFR-11a: the consistency check result is reported"
output_has "could not read the versions of the connected agents" "O-R02: a failed agent version query warns and the upgrade goes on"
output_has "ARGOS_SERVER_IMAGE is set in the environment (argos-server:0.9.0) and wins over .env" "T-NFR-11a: ARGOS_SERVER_IMAGE of the environment is named"
image_used() { grep -qx 'ARGOS_SERVER_IMAGE=argos-server:0.10.0' .env; }
check "T-NFR-11a: .env names the new image although the environment has another one" image_used

# .env without ARGOS_SERVER_IMAGE (the compose.yaml default): the line is added.
reset_upgrade
grep -v '^ARGOS_SERVER_IMAGE=' /tmp/env.orig >.env
expect_exit 0 "T-NFR-11a: upgrade with .env without ARGOS_SERVER_IMAGE: exit 0" ug --image argos-server:1.0.0 --yes
added_once() { [ "$(grep -c '^ARGOS_SERVER_IMAGE=argos-server:1.0.0$' .env)" -eq 1 ]; }
check "T-NFR-11a: 0.9.0 -> 1.0.0 (one major) adds ARGOS_SERVER_IMAGE to .env" added_once
rm -rf /tmp/backups
expect_exit 2 "T-NFR-11a: rollback whose backup set is gone: exit 2" ug --rollback --yes
output_has "does not exist" "T-NFR-11a: the missing backup set is named"
reset_upgrade
expect_exit 2 "T-NFR-11a: rollback without upgrade-state.json: exit 2" ug --rollback --yes
output_has "there is no upgrade to roll back" "T-NFR-11a: rollback without a state says so"
# backup.sh takes labels of at most 32 characters: long pre-release versions are cut.
reset_upgrade
printf 'argos-server:0.10.0-rc.123456789 sha256:rcl 0.10.0-rc.123456789\n' >>/tmp/images.txt
expect_exit 0 "T-NFR-11a: upgrade to a long pre-release version (0.10.0-rc.123456789): exit 0" ug --image argos-server:0.10.0-rc.123456789 --yes
label_cut() { grep -qxF -- "--label pre-upgrade-0.9.0-0.10.0-rc.1234 --json" /tmp/backup.args || { cat /tmp/backup.args; return 1; }; }
check "T-NFR-11a: the pre-upgrade backup label is cut to the 32 characters backup.sh takes" label_cut
reset_upgrade

# --- demo profile (ARGOS_DEMO_MODE=true of argos-env-init.sh --demo; docs/17 section 3.9, WO-41) ----
# A second copy prepared with --demo on a Linux Docker Engine (secrets owned by uid 10001), without
# tls/: the demo has no gRPC certificate.
cd /
rm -rf /tmp/demo-deploy
mkdir -p /tmp/demo-deploy
cp -R "$SRC" /tmp/demo-deploy/compose
cp -R /deploy/clickhouse /deploy/postgres /tmp/demo-deploy/
rm -rf /tmp/demo-deploy/compose/.env /tmp/demo-deploy/compose/secrets /tmp/demo-deploy/compose/tls \
  /tmp/demo-deploy/compose/compose.override.yaml /tmp/demo-deploy/compose/test
cd /tmp/demo-deploy/compose || exit 1
env FAKE_DOCKER_OS="$LINUX" sh ./argos-env-init.sh --demo --ram-mb 16384 >/dev/null 2>&1 \
  || { echo "FAIL  argos-env-init.sh --demo did not prepare the directory"; exit 1; }

demo_files() { grep -q -- '-f compose.yaml -f compose.unattended.yaml -f compose.demo.yaml --profile demo' "$LOG"; }
expect_exit 0 "T-NFR-16d: argos-compose up in demo mode starts without tls/ (control case)" ac up
output_has "Demo mode: plaintext gRPC and sample traffic" "T-NFR-16d: up in demo mode says that it is the demo"
check "T-NFR-16d: in demo mode docker compose runs with compose.unattended.yaml, compose.demo.yaml and the demo profile" demo_files
check "T-NFR-16d: in demo mode docker compose up -d --wait is called" compose_up_called
expect_exit 0 "T-NFR-16d: argos-compose down --volumes in demo mode exits 0" ac down --volumes
demo_down() { grep -qE -- '--profile demo down --volumes$' "$LOG"; }
check "T-NFR-16d: down --volumes in demo mode includes the demo profile (its containers and its volume go too)" demo_down

demo_up_refused() { # demo_up_refused <description> <expected message>
  expect_exit 2 "T-NFR-16d preflight: $1: up exits 2" ac up
  output_has "$2" "T-NFR-16d preflight: $1: the message names it"
  check "T-NFR-16d preflight: $1: docker compose up is not called" no_compose_up
}
aside secrets/demo_order_api_token
demo_up_refused "the order-api token missing" "secrets/demo_order_api_token is missing or empty: run ./argos-env-init.sh --demo"
back secrets/demo_order_api_token
cp secrets/demo_db_password /tmp/demo-db.backup
: >secrets/demo_db_password
demo_up_refused "an empty demo database password" "secrets/demo_db_password is missing or empty"
cat /tmp/demo-db.backup >secrets/demo_db_password
rm -f /tmp/demo-db.backup
chmod 0644 secrets/demo_payment_api_token
demo_up_refused "the payment-api token 0644" "secrets/demo_payment_api_token must have mode 0600"
chmod 0600 secrets/demo_payment_api_token
chown 0:0 secrets/demo_order_api_token
demo_up_refused "the order-api token owned by root" "secrets/demo_order_api_token must belong to uid 10001"
chown 10001:10001 secrets/demo_order_api_token
chmod 0640 config/demo/bootstrap-apps.yaml
demo_up_refused "config/demo/bootstrap-apps.yaml 0640 (umask 027 checkout)" "the containers cannot read these configuration files"
chmod 0644 config/demo/bootstrap-apps.yaml
FAKE_IMAGE_MISSING=1
export FAKE_IMAGE_MISSING
expect_exit 2 "T-NFR-16d preflight: the demo images missing: up exits 2" ac up
output_has "the demo image argos-demo-app:0.0.0-SNAPSHOT is not in the local Docker image store: build it with ./gradlew :sample-apps:demo-app:jibDockerBuild" \
  "T-NFR-16d preflight: the missing demo application image is named with its build task"
output_has "the demo image argos-demo-load:0.0.0-SNAPSHOT is not in the local Docker image store: build it with ./gradlew :sample-apps:load:jibDockerBuild" \
  "T-NFR-16d preflight: the missing load generator image is named with its build task"
check "T-NFR-16d preflight: the demo images missing: docker compose up is not called" no_compose_up
unset FAKE_IMAGE_MISSING
expect_exit 0 "T-NFR-16d: argos-compose up in demo mode starts again after every restore (control case)" ac up

# Demo mode is only what .env (or the environment) says: without it the TLS files are required again
# and compose.demo.yaml is not used.
no_demo_up() { : >"$LOG"; env ARGOS_DEMO_MODE=false FAKE_DOCKER_OS="$LINUX" sh ./argos-compose.sh up; }
expect_exit 2 "T-NFR-16d: ARGOS_DEMO_MODE=false in the environment: up without tls/ exits 2" no_demo_up
output_has "tls/server.crt is missing" "T-NFR-16d: without demo mode the gRPC certificate is required"
no_demo_file() { ! grep -q 'compose.demo.yaml' "$LOG"; }
check "T-NFR-16d: without demo mode compose.demo.yaml is never passed to docker compose" no_demo_file

finish
