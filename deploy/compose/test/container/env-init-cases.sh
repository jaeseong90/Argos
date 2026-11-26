#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# req: SEC-74, N-16
#
# Cases of argos-env-init.sh (docs/17 section 3.7 and 7.3, T-SEC-74, 17 section 14 "resource
# calculation"; the demo profile --demo of docs/17 section 3.9, T-NFR-16d; --self-signed-tls of
# ADR-0010 C-172 (1), T-SEC-74a, with openssl on the PostgreSQL image), run as root inside a
# pinned Linux container on a container-local file system by ../env-init.test.sh. SRC is a
# read-only copy of deploy/compose, T this test directory. docker is the test double
# container/fake-bin/docker.
set -u
# shellcheck source=deploy/compose/test/container/lib.sh
. "$T/container/lib.sh"

mkdir -p /tmp/fake-bin /tmp/fake-df
cp "$T/container/fake-bin/docker" /tmp/fake-bin/docker
cp "$T/container/fake-df/df" /tmp/fake-df/df
chmod 0755 /tmp/fake-bin/docker /tmp/fake-df/df
PATH=/tmp/fake-bin:$PATH
export PATH

WORK=/tmp/argos-env-init
SECRETS="pg_superuser_password pg_owner_password pg_app_password pg_audit_password pg_backup_password ch_writer_password ch_reader_password ch_migrator_password master_key metrics_token"
EMPTY_SHA256=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855

fresh() {
  cd /
  rm -rf "$WORK"
  mkdir -p "$WORK"
  cp -R "$SRC/." "$WORK/"
  rm -rf "$WORK/.env" "$WORK/secrets" "$WORK/tls" "$WORK/compose.override.yaml"
  cd "$WORK" || exit 1
}
init() { sh ./argos-env-init.sh "$@"; }
sha() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum; else openssl dgst -sha256 -r; fi | awk '{ print $1 }'
}
tree_hash() { (cat .env; for f in secrets/*; do printf '%s:' "$f"; cat "$f"; done) | sha; }

# --- a standard run (setup token wizard) -----------------------------------------------------
fresh
expect_exit 0 "env-init: standard run exits 0" init --ram-mb 16384 --public-url http://argos.test:8080
output_has "secrets/        10 generated, 0 kept" "env-init: reports 10 generated secrets"

modes_ok() {
  [ "$(mode_of .env)" = "-rw-------" ] || { echo ".env $(mode_of .env)"; return 1; }
  [ "$(mode_of secrets)" = "drwx------" ] || { echo "secrets $(mode_of secrets)"; return 1; }
  for f in secrets/*; do
    [ "$(mode_of "$f")" = "-rw-------" ] || { echo "$f $(mode_of "$f")"; return 1; }
  done
}
check "T-SEC-74: .env is 0600, secrets/ 0700, every file in it 0600" modes_ok

# The names in secrets/ are the script's own fixed names (no blanks or line breaks).
# shellcheck disable=SC2012
secret_names() { ls -A secrets | sort | tr '\n' ' '; }

file_set_ok() {
  got=$(secret_names)
  # shellcheck disable=SC2086 # the name list is split on purpose
  want=$(printf '%s\n' $SECRETS clickhouse.env | sort | tr '\n' ' ')
  [ "$got" = "$want" ] || { echo "got: $got"; echo "want: $want"; return 1; }
}
check "env-init: exactly the C-11 database secrets, master_key, metrics_token and clickhouse.env (no admin_password)" file_set_ok

formats_ok() {
  for f in $SECRETS; do
    v=$(first_line "secrets/$f")
    case $f in
      master_key)
        [ "${#v}" -eq 64 ] || { echo "$f length ${#v}"; return 1; }
        case $v in *[!0-9a-f]*) echo "$f not hex"; return 1 ;; esac
        ;;
      *)
        [ "${#v}" -eq 43 ] || { echo "$f length ${#v}"; return 1; }
        case $v in *[!A-Za-z0-9_-]*) echo "$f not base64url"; return 1 ;; esac
        ;;
    esac
    [ "$(wc -l <"secrets/$f" | tr -d ' ')" = 0 ] || { echo "$f has a line break"; return 1; }
  done
}
check "T-SEC-74: secrets are 32 CSPRNG bytes: base64url 43 characters, master_key 64 hex" formats_ok

distinct_ok() {
  n=$(for f in $SECRETS; do first_line "secrets/$f"; echo; done | sort -u | wc -l | tr -d ' ')
  [ "$n" -eq 10 ] || { echo "only $n distinct values"; return 1; }
}
check "env-init: every secret is a different value" distinct_ok

ch_env_ok() {
  for name in writer reader migrator; do
    want=$(first_line "secrets/ch_${name}_password" | tr -d '\n' | sha)
    upper=$(printf '%s' "$name" | tr '[:lower:]' '[:upper:]')
    got=$(sed -n "s/^ARGOS_CH_${upper}_PASSWORD_SHA256=//p" secrets/clickhouse.env)
    [ "$got" = "$want" ] || { echo "$name: hash differs"; return 1; }
  done
  [ "$(grep -c '^ARGOS_' secrets/clickhouse.env)" -eq 3 ] || { echo "unexpected variables"; return 1; }
  ! grep -q "$EMPTY_SHA256" secrets/clickhouse.env || { echo "hash of the empty password"; return 1; }
}
check "env-init: secrets/clickhouse.env holds exactly the SHA-256 of the three ClickHouse passwords" ch_env_ok

env_clean() {
  for f in secrets/*; do
    [ "$f" = secrets/clickhouse.env ] && continue
    if grep -qF -f "$f" .env; then echo ".env contains $f"; return 1; fi
  done
  if grep -E '^[A-Z0-9_]*(PASSWORD|SECRET|TOKEN|SHA256|MASTER_KEY)[A-Z0-9_]*=' .env; then return 1; fi
  grep -q '^ARGOS_UNATTENDED=false$' .env || { echo "ARGOS_UNATTENDED"; return 1; }
  grep -q '^ARGOS_WEB_PUBLIC_URL=http://argos.test:8080$' .env || { echo "public URL"; return 1; }
  if ! { grep -q '^ARGOS_RETENTION_RAW_STAT_DAYS=14$' .env && grep -q '^ARGOS_RETENTION_METRIC_DAYS=14$' .env; }; then
    echo "retention stat and metric must be equal (C-112)"
    return 1
  fi
}
check "T-SEC-74: .env holds no secret value and no secret variable; stat = metric retention (C-112)" env_clean

no_secret_in_output() {
  init --ram-mb 16384 --public-url http://argos.test:8080 >/tmp/run.out 2>&1
  for f in secrets/*; do
    [ "$f" = secrets/clickhouse.env ] && continue
    if grep -qF -f "$f" /tmp/run.out; then echo "output contains $f"; return 1; fi
  done
  sed -n 's/^ARGOS_CH_.*=//p' secrets/clickhouse.env >/tmp/run.hashes
  while IFS= read -r h; do
    if grep -qF "$h" /tmp/run.out; then echo "output contains a hash"; return 1; fi
  done </tmp/run.hashes
}
check "T-SEC-74: the output shows no secret and no hash" no_secret_in_output

root_owned() { [ "$(owner_of secrets/master_key)" = 0 ]; }
check "env-init: default ownership kept on Docker Desktop (no chown)" root_owned

# --- idempotency and --force ----------------------------------------------------------------
before=$(tree_hash)
expect_exit 0 "env-init: a second run exits 0" init --ram-mb 16384 --public-url http://other.test:8080
output_has "0 generated, 10 kept" "env-init: a second run keeps every secret"
output_has ".env            kept" "env-init: a second run keeps .env"
unchanged() { [ "$(tree_hash)" = "$before" ]; }
check "env-init: a second run changes no file" unchanged

# --force while volumes of the Compose project exist: their databases keep the old passwords, so
# it is refused and changes nothing (fake docker: FAKE_VOLUMES of FAKE_VOLUME_PROJECT).
force_refused() { # force_refused <description> <expected message> <env assignments...>
  case_name="env-init: --force refused: $1" # expect_exit of lib.sh sets desc
  msg=$2
  shift 2
  before=$(tree_hash)
  expect_exit 2 "$case_name: exit 2" env "$@" sh ./argos-env-init.sh --force --ram-mb 16384 --public-url http://argos.test:8080
  output_has "$msg" "$case_name: the message names the project and its volumes"
  output_has "./argos-compose.sh down --volumes" "$case_name: the message says how to delete the volumes"
  check "$case_name: no file changed" unchanged
}
force_refused "volumes of the project argos" "project 'argos' still has volumes (argos_postgres-data argos_clickhouse-data)" \
  FAKE_VOLUMES="argos_postgres-data argos_clickhouse-data"
force_refused "COMPOSE_PROJECT_NAME of the environment" "project 'argos-two' still has volumes (argos-two_postgres-data)" \
  COMPOSE_PROJECT_NAME=argos-two FAKE_VOLUME_PROJECT=argos-two FAKE_VOLUMES=argos-two_postgres-data
cp .env /tmp/env.before-project
printf 'COMPOSE_PROJECT_NAME="argos-three"\n' >>.env
force_refused "COMPOSE_PROJECT_NAME of .env" "project 'argos-three' still has volumes (argos-three_argos-data)" \
  FAKE_VOLUME_PROJECT=argos-three FAKE_VOLUMES=argos-three_argos-data
cp /tmp/env.before-project .env
chmod 0600 .env

old_pg=$(first_line secrets/pg_app_password)
old_key=$(first_line secrets/master_key)
expect_exit 0 "env-init: --force without volumes of the project (only of another one) exits 0" \
  env FAKE_VOLUME_PROJECT=other FAKE_VOLUMES=other_postgres-data sh ./argos-env-init.sh --force --ram-mb 16384 --public-url http://argos.test:8080
force_ok() {
  [ "$(first_line secrets/pg_app_password)" != "$old_pg" ] || { echo "pg_app_password kept"; return 1; }
  [ "$(first_line secrets/master_key)" != "$old_key" ] || { echo "master_key kept"; return 1; }
  modes_ok && formats_ok && ch_env_ok
}
check "env-init: --force replaces the secrets with new valid values" force_ok

# --- forbidden, weak and empty secrets: exit 3 before anything is hashed ----------------------
refused() { # refused <file> <content> <description>
  fresh
  init --ram-mb 16384 --public-url http://argos.test:8080 >/dev/null 2>&1
  printf '%s' "$2" >"secrets/$1"
  rm -f secrets/clickhouse.env
  snapshot=$(secret_names)
  expect_exit 3 "T-SEC-74: $3: exit 3" init --ram-mb 16384 --public-url http://argos.test:8080
  output_has "secrets/$1" "T-SEC-74: $3: the message names secrets/$1"
  if [ ! -e secrets/clickhouse.env ] && [ "$(secret_names)" = "$snapshot" ]; then
    pass_case "T-SEC-74: $3: nothing is written or hashed"
  else
    fail_case "T-SEC-74: $3: files were written"
  fi
}
refused pg_app_password changeme "forbidden 'changeme'"
refused pg_owner_password Password "forbidden 'Password' (any case)"
refused ch_reader_password "  admin  " "forbidden 'admin' with spaces"
refused ch_migrator_password postgres "forbidden 'postgres'"
refused pg_backup_password clickhouse "forbidden 'clickhouse'"
refused pg_audit_password argos "forbidden 'argos'"
refused metrics_token 123456 "forbidden '123456'"
refused pg_superuser_password short123 "8 characters (below 12)"
refused ch_writer_password "" "empty ClickHouse writer password (sha256('') would be accepted)"
refused pg_app_password "   " "white space only"
refused master_key 0123 "master_key not 32 bytes hex"

fresh
init --ram-mb 16384 --public-url http://argos.test:8080 >/dev/null 2>&1
printf 'Changeme' >secrets/pg_app_password
expect_exit 3 "T-SEC-74: --force is needed to replace a refused file" init --ram-mb 16384 --public-url http://argos.test:8080
expect_exit 0 "T-SEC-74: --force replaces the refused file" init --force --ram-mb 16384 --public-url http://argos.test:8080

# --- unattended setup -------------------------------------------------------------------------
fresh
expect_exit 2 "env-init: --unattended without --ip-mode exits 2" init --unattended --ram-mb 16384
expect_exit 2 "env-init: --ip-mode without --unattended exits 2" init --ip-mode masked --ram-mb 16384
expect_exit 2 "env-init: --ip-mode other than masked, full, off exits 2" init --unattended --ip-mode none --ram-mb 16384
expect_exit 0 "env-init: --unattended --ip-mode masked exits 0" init --unattended --ip-mode masked --ram-mb 16384 --public-url http://argos.test:8080
unattended_ok() {
  [ "$(mode_of secrets/admin_password)" = "-rw-------" ] || { echo "mode"; return 1; }
  v=$(first_line secrets/admin_password)
  [ "${#v}" -eq 24 ] || { echo "length ${#v}"; return 1; }
  case $(printf '%s' "$v" | tr '[:upper:]' '[:lower:]') in *argos* | *admin*) echo "policy"; return 1 ;; esac
  if ! { grep -q '^ARGOS_UNATTENDED=true$' .env && grep -q '^ARGOS_IP_MODE=masked$' .env \
    && grep -q '^ARGOS_ADMIN_USERNAME=admin$' .env; }; then
    echo ".env"
    return 1
  fi
  ! grep -qF -f secrets/admin_password .env || { echo "password in .env"; return 1; }
}
check "env-init: --unattended adds admin_password (24 characters, 0600) and ARGOS_UNATTENDED/ARGOS_IP_MODE" unattended_ok

# --- prerequisites: exit 2 with the list --------------------------------------------------------
fresh
expect_exit 2 "env-init: unknown option exits 2" init --frobnicate
expect_exit 2 "env-init: --public-url must be http(s)" init --public-url ftp://argos.test --ram-mb 16384
expect_exit 2 "env-init: --public-url without user information" init --public-url http://u:p@argos.test --ram-mb 16384
expect_exit 2 "env-init: --http-bind must be IPv4" init --http-bind localhost --ram-mb 16384
expect_exit 2 "env-init: --ram-mb must be a number" init --ram-mb 16g

expect_exit 2 "env-init: no docker exits 2" env PATH=/usr/bin:/bin sh ./argos-env-init.sh --ram-mb 16384
output_has "docker: Docker Engine 24 or later" "env-init: the missing docker is listed"
expect_exit 2 "env-init: Docker daemon down exits 2" env FAKE_DOCKER_DOWN=1 sh ./argos-env-init.sh --ram-mb 16384
expect_exit 2 "env-init: Docker 20.10 exits 2" env FAKE_DOCKER_VERSION=20.10.24 sh ./argos-env-init.sh --ram-mb 16384
output_has "Docker Engine 24 or later (found 20.10.24)" "env-init: the old Docker version is named"
expect_exit 2 "env-init: no Compose v2 exits 2" env FAKE_NO_COMPOSE=1 sh ./argos-env-init.sh --ram-mb 16384
expect_exit 2 "env-init: Compose 2.17 exits 2" env FAKE_COMPOSE_VERSION=2.17.3 sh ./argos-env-init.sh --ram-mb 16384
output_has "Docker Compose v2.24 or later" "env-init: the old Compose version is named"
expect_exit 0 "env-init: Compose 5.0.2 is accepted" env FAKE_COMPOSE_VERSION=v5.0.2 sh ./argos-env-init.sh --ram-mb 16384 --public-url http://argos.test:8080
fresh
# ADR-0010 C-201: the documented 8 GB minimum is 7168 MiB visible to Docker. Between it and the
# 7965 MiB of the C-198 plan (server 3072, PostgreSQL 1024, ClickHouse 3072 MiB in 0.9 x RAM) the
# minimum-spec tier applies; below 7168 MiB the host is refused. (Before C-201 the refusal named
# 7965 MiB as the minimum and refused 7680 and 7964 MiB.)
min_tier_warning="minimum-spec tier (ADR-0010 C-201)"
expect_exit 2 "env-init: 4 GiB of memory exits 2" init --ram-mb 4096
output_has "at least 7168 MiB visible to Docker is needed (the 8 GB minimum; ADR-0010 C-201), 7965 MiB or more for the memory plan without the minimum-spec tier" \
  "C-201: the refusal names the 8 GB minimum and the normal plan's 7965 MiB"
expect_exit 2 "env-init: 6 GiB reported by Docker exits 2" env FAKE_DOCKER_MEM=6442450944 sh ./argos-env-init.sh
nothing_written() { [ ! -e .env ] && [ ! -e secrets ]; }
expect_exit 2 "C-201: 7167 MiB (one below the 8 GB minimum) exits 2" init --ram-mb 7167 --public-url http://argos.test:8080
output_has "memory: 7167 MiB from --ram-mb; at least 7168 MiB" "C-201: the refusal of 7167 MiB names the minimum"
check "C-201: 7167 MiB writes no .env and no secrets/" nothing_written
expect_exit 0 "C-201: 7168 MiB (the 8 GB minimum) exits 0" init --ram-mb 7168 --public-url http://argos.test:8080
output_has "$min_tier_warning: 7168 MiB visible to Docker is below the 7965 MiB of the normal memory plan, so ClickHouse gets a 2560 MiB container (normally at least 3072) and the server heap 1843 MiB (normally at least 2048)" \
  "C-201: 7168 MiB warns of the minimum-spec tier, ClickHouse at 2.5 GiB and the smaller heap"
output_has "evaluation or a small load only, and add memory before a heavier load" "C-201: the tier warning says what the host is for"
fresh
expect_exit 0 "C-201: an \"8 GB\" host that shows Docker 7680 MiB exits 0" env FAKE_DOCKER_MEM=8053063680 sh ./argos-env-init.sh --public-url http://argos.test:8080
output_has "$min_tier_warning: 7680 MiB visible to Docker is below the 7965 MiB of the normal memory plan, so ClickHouse gets a 2816 MiB container (normally at least 3072). Use" \
  "C-201: 7680 MiB warns of the tier with ClickHouse only (the heap stays 2048 MiB)"
fresh
expect_exit 0 "C-201: 7964 MiB (one below the normal plan) exits 0 in the tier" init --ram-mb 7964 --public-url http://argos.test:8080
output_has "$min_tier_warning" "C-201: 7964 MiB is in the minimum-spec tier"
fresh
expect_exit 0 "C-198: 7965 MiB (the normal plan's minimum) exits 0" init --ram-mb 7965 --public-url http://argos.test:8080
output_lacks "minimum-spec tier" "C-201: 7965 MiB has no minimum-spec tier warning"
expect_exit 2 "env-init: 10 GiB of free disk exits 2" env PATH="/tmp/fake-df:$PATH" FAKE_DF_AVAIL_KB=10485760 sh ./argos-env-init.sh --ram-mb 16384
output_has "disk space: 10 GiB free" "env-init: the free disk space is named"
expect_exit 0 "env-init: 50 GiB of free disk passes with a warning" env PATH="/tmp/fake-df:$PATH" FAKE_DF_AVAIL_KB=52428800 sh ./argos-env-init.sh --ram-mb 16384 --public-url http://argos.test:8080
output_has "100 GiB or more is recommended" "env-init: below 100 GiB a warning is printed"
fresh
expect_exit 2 "env-init: rootless Docker exits 2" env FAKE_DOCKER_OS="Ubuntu 22.04.5 LTS" FAKE_DOCKER_SECURITY='["name=seccomp","name=rootless"]' sh ./argos-env-init.sh --ram-mb 16384
output_has "rootless Docker is not supported" "env-init: rootless Docker is named"

# --- Linux Docker Engine as root: the secrets belong to uid 10001 -------------------------------
fresh
expect_exit 0 "env-init: Linux Docker Engine as root exits 0" env FAKE_DOCKER_OS="Ubuntu 22.04.5 LTS" sh ./argos-env-init.sh --ram-mb 16384 --public-url http://argos.test:8080
owner_ok() {
  for f in $SECRETS; do
    [ "$(owner_of "secrets/$f")" = 10001 ] || { echo "$f owner $(owner_of "secrets/$f")"; return 1; }
  done
  [ "$(owner_of secrets/clickhouse.env)" = 0 ] || { echo "clickhouse.env owner"; return 1; }
  [ "$(owner_of secrets)" = 0 ] && [ "$(owner_of .env)" = 0 ] || { echo "dir or .env owner"; return 1; }
  modes_ok
}
check "env-init: on a Linux Docker Engine the mounted secrets belong to uid 10001 (0600), clickhouse.env and .env to root" owner_ok

# --- memory plan snapshots (17 section 14 "resource calculation") --------------------------------
for ram in 7168 7680 7987 8192 16384 32768; do
  fresh
  init --ram-mb "$ram" --public-url http://argos.test:8080 >/tmp/plan.out 2>&1 || true
  sed -n '/^# Memory plan for/,/^ARGOS_PG_EFFECTIVE_CACHE_SIZE=/p' .env | sed 's/ (--ram-mb; / (/' >/tmp/plan.env
  if diff -u "$T/expected/ram-$ram.env" /tmp/plan.env >/tmp/plan.diff 2>&1; then
    pass_case "17 section 14: memory plan for --ram-mb $ram equals expected/ram-$ram.env"
  else
    cp /tmp/plan.diff "$LAST"
    fail_case "17 section 14: memory plan for --ram-mb $ram differs from expected/ram-$ram.env"
  fi
done
fresh
init --ram-mb 8192 --public-url http://argos.test:8080 >/tmp/plan.out 2>&1 || true
cp /tmp/plan.out "$LAST"
output_has "less than 16 GB of memory: an evaluation setup only" "env-init: 8 GiB prints the evaluation-only warning"
output_has "ClickHouse     3276 MiB, max_server_memory_usage 2948 MiB (0.9 x the container)" \
  "C-198: the summary shows the ClickHouse container and its max_server_memory_usage"
output_lacks "minimum-spec tier" "C-201: 8192 MiB has no minimum-spec tier warning"

# C-198 and C-201 rules on every size, from the 8 GB minimum to beyond the upper bounds: the three
# container limits fit in 0.9 x RAM, the server container is heap + 1 GiB, max_server_memory_usage
# is 0.9 x the ClickHouse container, the per-query limits are 4/15 and 2/5 of it. From 7965 MiB on
# ClickHouse is at least 3 GiB and the heap at least 2 GiB; below (the minimum-spec tier) at least
# 2.5 GiB and 1.5 GiB, and the heap is cut only once ClickHouse is at 2.5 GiB.
plan_value() { sed -n "s/^$1=//p" .env | tr -d '"m'; }
plan_rules() { # plan_rules <ram>
  fresh
  init --ram-mb "$1" --public-url http://argos.test:8080 >/dev/null 2>&1 || { echo "exit $?"; return 1; }
  server=$(plan_value ARGOS_SERVER_MEM)
  ch=$(plan_value ARGOS_CH_MEM)
  pg=$(plan_value ARGOS_PG_MEM)
  max=$(plan_value ARGOS_CH_MAX_SERVER_MEMORY_USAGE)
  heap=$(sed -n 's/^ARGOS_SERVER_JAVA_OPTS=.*-Xmx\([0-9][0-9]*\)m.*$/\1/p' .env)
  [ -n "$heap" ] || { echo "no -Xmx in ARGOS_SERVER_JAVA_OPTS"; return 1; }
  [ $((server + ch + pg)) -le $(($1 * 90 / 100)) ] || { echo "sum $((server + ch + pg)) > 0.9 x $1"; return 1; }
  [ "$server" -eq $((heap + 1024)) ] || { echo "server container $server is not heap $heap + 1024"; return 1; }
  if [ "$1" -ge 7965 ]; then
    if [ "$ch" -lt 3072 ] || [ "$heap" -lt 2048 ]; then echo "ClickHouse $ch < 3072 or heap $heap < 2048"; return 1; fi
  else
    if [ "$ch" -lt 2560 ] || [ "$heap" -lt 1536 ]; then echo "tier: ClickHouse $ch < 2560 or heap $heap < 1536"; return 1; fi
    if [ "$heap" -ne 2048 ] && [ "$ch" -ne 2560 ]; then echo "tier: heap $heap cut while ClickHouse is $ch"; return 1; fi
  fi
  [ "$max" -eq $((ch * 1048576 * 9 / 10)) ] || { echo "max_server_memory_usage $max is not 0.9 x $ch MiB"; return 1; }
  [ "$(plan_value ARGOS_CH_WRITER_MAX_MEMORY_USAGE)" -eq $((max * 4 / 15)) ] || { echo "writer limit"; return 1; }
  [ "$(plan_value ARGOS_CH_READER_MAX_MEMORY_USAGE)" -eq $((max * 2 / 5)) ] || { echo "reader limit"; return 1; }
}
for ram in 7168 7300 7395 7396 7680 7900 7964 7965 8192 10240 12288 16384 24576 32768 65536 262144; do
  check "C-198/C-201: --ram-mb $ram: containers <= 0.9 x RAM, server = heap + 1 GiB, ClickHouse and heap at or above their floors, max = 0.9 x container" plan_rules "$ram"
done

# --- public URL over plain HTTP (ADR-0010 C-197 (2)) --------------------------------------------
secure_cookie_warning="is plain HTTP and its host is not localhost"
url_warns() { # url_warns <url> <description>
  fresh
  expect_exit 0 "C-197 (2): $2: exit 0 (a warning)" init --ram-mb 16384 --public-url "$1"
  output_has "WARNING: the public URL $1 $secure_cookie_warning" "C-197 (2): $2: the warning names the URL"
  output_has "ssh -N -L 8080:127.0.0.1:8080" "C-197 (2): $2: option localhost or SSH tunnel"
  output_has "HTTPS reverse proxy (ARGOS_PROXY=true" "C-197 (2): $2: option HTTPS reverse proxy"
  output_has "evaluation only: ARGOS_SECURITY_COOKIE_SECURE=false" "C-197 (2): $2: option evaluation-only cookie setting"
}
url_quiet() { # url_quiet <url> <description>
  fresh
  expect_exit 0 "C-197 (2): $2: exit 0" init --ram-mb 16384 --public-url "$1"
  output_lacks "$secure_cookie_warning" "C-197 (2): $2: no Secure cookie warning"
}
url_warns http://argos.test:8080 "http on another host"
url_warns http://192.0.2.10:8080 "http on an IPv4 address"
url_warns "http://[2001:db8::10]:8080" "http on an IPv6 address"
url_quiet http://localhost:8080 "http://localhost"
url_quiet http://LOCALHOST:8080/ "http://LOCALHOST (any case)"
url_quiet http://127.0.0.1:8080 "http://127.0.0.1"
url_quiet "http://[::1]:8080" "http://[::1]"
url_quiet https://argos.example.com "https"

fresh
expect_exit 0 "C-197 (2): without --public-url (http://<host name>:8080): exit 0" init --ram-mb 16384
output_has "WARNING: the public URL http://$(uname -n):8080 $secure_cookie_warning" \
  "C-197 (2): without --public-url the default URL gets the warning"

# A kept .env: its URL is the one in force, not the --public-url of the rerun.
expect_exit 0 "C-197 (2): rerun with a kept .env: exit 0" init --ram-mb 16384 --public-url http://localhost:8080
output_has "WARNING: the public URL http://$(uname -n):8080 $secure_cookie_warning" \
  "C-197 (2): with a kept .env the warning names the URL in .env"
output_has "Check ARGOS_WEB_PUBLIC_URL in .env (now http://$(uname -n):8080)" \
  "C-197 (2): with a kept .env the next steps show the URL in .env"
printf 'ARGOS_SECURITY_COOKIE_SECURE="false"\n' >>.env
expect_exit 0 "C-197 (2): ARGOS_SECURITY_COOKIE_SECURE=false in .env: exit 0" init --ram-mb 16384
output_has "ARGOS_SECURITY_COOKIE_SECURE=false in .env: signing in over plain HTTP" \
  "C-197 (2): with ARGOS_SECURITY_COOKIE_SECURE=false the evaluation-only warning is shown"
output_lacks "$secure_cookie_warning" "C-197 (2): with ARGOS_SECURITY_COOKIE_SECURE=false no sign-in failure is predicted"

# --- demo profile: --demo (docs/17 section 3.9, WO-41) -------------------------------------------
DEMO_SECRETS="demo_order_api_token demo_payment_api_token demo_db_password"
DEMO_VARS='^(ARGOS_DEMO_MODE|ARGOS_DEMO_APP_IMAGE|ARGOS_DEMO_LOAD_IMAGE|COMPOSE_FILE|COMPOSE_PATH_SEPARATOR|COMPOSE_PROFILES)='

# Without --demo nothing of the demo is written (docs/17 section 3.2: a production installation never
# sets ARGOS_DEMO_MODE).
fresh
init --ram-mb 16384 --public-url http://argos.test:8080 >/dev/null 2>&1
no_demo_written() {
  if grep -E "$DEMO_VARS" .env; then echo "demo variables in a production .env"; return 1; fi
  for f in $DEMO_SECRETS; do [ ! -e "secrets/$f" ] || { echo "secrets/$f exists"; return 1; }; done
}
check "T-NFR-16d: without --demo .env has no ARGOS_DEMO_MODE, COMPOSE_FILE or COMPOSE_PROFILES and secrets/ no demo file" no_demo_written
fresh
init --unattended --ip-mode masked --ram-mb 16384 --public-url http://argos.test:8080 >/dev/null 2>&1
check "T-NFR-16d: --unattended without --demo writes no demo variable or file either" no_demo_written

fresh
expect_exit 0 "T-NFR-16d: --demo exits 0" init --demo --ram-mb 16384
cp "$LAST" /tmp/demo.out
output_has "secrets/        14 generated, 0 kept" "T-NFR-16d: --demo generates the 10 secrets, admin_password and the 3 demo secrets"
demo_file_set_ok() {
  got=$(secret_names)
  # shellcheck disable=SC2086 # the name lists are split on purpose
  want=$(printf '%s\n' $SECRETS admin_password $DEMO_SECRETS clickhouse.env | sort | tr '\n' ' ')
  [ "$got" = "$want" ] || { echo "got: $got"; echo "want: $want"; return 1; }
  modes_ok
}
check "T-SEC-74 T-NFR-16d: --demo writes exactly the secrets, admin_password and the demo files, every one 0600 in secrets/ 0700, .env 0600" demo_file_set_ok
demo_formats_ok() {
  for f in demo_order_api_token demo_payment_api_token; do
    v=$(first_line "secrets/$f")
    case $v in argos_*) ;; *) echo "$f does not start with argos_"; return 1 ;; esac
    body=${v#argos_}
    [ "${#body}" -eq 43 ] || { echo "$f: ${#body} characters after argos_"; return 1; }
    case $body in *[!A-Za-z0-9_-]*) echo "$f is not base64url"; return 1 ;; esac
    [ "$(wc -l <"secrets/$f" | tr -d ' ')" = 0 ] || { echo "$f has a line break"; return 1; }
  done
  [ "$(first_line secrets/demo_order_api_token)" != "$(first_line secrets/demo_payment_api_token)" ] || { echo "the two tokens are equal"; return 1; }
  v=$(first_line secrets/demo_db_password)
  [ "${#v}" -eq 43 ] || { echo "demo_db_password length ${#v}"; return 1; }
  case $v in *[!A-Za-z0-9_-]*) echo "demo_db_password not base64url"; return 1 ;; esac
  n=$(for f in $SECRETS admin_password $DEMO_SECRETS; do first_line "secrets/$f"; echo; done | sort -u | wc -l | tr -d ' ')
  [ "$n" -eq 14 ] || { echo "only $n distinct values"; return 1; }
}
check "T-SEC-74 T-NFR-16d: each demo app has its own agent token (argos_ + 43 base64url characters of 32 CSPRNG bytes, 16 section 2.1); the demo database password is 43 base64url characters; all values differ" demo_formats_ok
demo_env_ok() {
  for line in ARGOS_DEMO_MODE=true COMPOSE_FILE=compose.yaml:compose.unattended.yaml:compose.demo.yaml COMPOSE_PATH_SEPARATOR=: \
    COMPOSE_PROFILES=demo ARGOS_UNATTENDED=true ARGOS_IP_MODE=masked ARGOS_ADMIN_USERNAME=admin ARGOS_HTTP_BIND=127.0.0.1 \
    ARGOS_GRPC_BIND=127.0.0.1 ARGOS_WEB_PUBLIC_URL=http://localhost:8080 ARGOS_DEMO_APP_IMAGE=argos-demo-app:0.0.0-SNAPSHOT \
    ARGOS_DEMO_LOAD_IMAGE=argos-demo-load:0.0.0-SNAPSHOT; do
    [ "$(grep -c "^${line%%=*}=" .env)" -eq 1 ] || { echo "${line%%=*} is not set once"; return 1; }
    grep -qxF "$line" .env || { echo "missing: $line"; return 1; }
  done
}
check "T-NFR-16d: --demo writes ARGOS_DEMO_MODE=true, COMPOSE_FILE/COMPOSE_PATH_SEPARATOR/COMPOSE_PROFILES for plain docker compose, the unattended setup with masked IP addresses, 127.0.0.1 binds, http://localhost:8080 and the local demo images" demo_env_ok
demo_no_secret() {
  for f in secrets/*; do
    [ "$f" = secrets/clickhouse.env ] && continue
    if grep -qF -f "$f" .env; then echo ".env contains $f"; return 1; fi
    if grep -qF -f "$f" /tmp/demo.out; then echo "the output contains $f"; return 1; fi
  done
  if grep -E '^[A-Z0-9_]*(PASSWORD|SECRET|TOKEN|SHA256|MASTER_KEY)[A-Z0-9_]*=' .env; then return 1; fi
}
check "T-SEC-74 T-NFR-16d: neither .env nor the output of --demo holds a token or password" demo_no_secret
cp /tmp/demo.out "$LAST"
output_has "docker compose --profile demo up -d" "T-NFR-16d: the summary gives the start command"
output_has "sign in as admin with the password in secrets/admin_password" "T-NFR-16d: the summary says where the administrator password is"
output_has "docker compose --profile demo down -v" "T-NFR-16d: the summary gives the removal command"
output_has "demo           1536 MiB" "T-NFR-16d: the summary shows the memory reserved for the demo services"
output_lacks "Place the gRPC certificate" "T-NFR-16d: the demo needs no gRPC certificate"

demo_before=$(tree_hash)
expect_exit 0 "T-NFR-16d: a second --demo run exits 0" init --demo --ram-mb 16384
output_has "0 generated, 14 kept" "T-NFR-16d: a second --demo run keeps every secret and token"
demo_unchanged() { [ "$(tree_hash)" = "$demo_before" ]; }
check "T-NFR-16d: a second --demo run changes no file" demo_unchanged

# Explicit options win over the --demo defaults.
fresh
expect_exit 0 "T-NFR-16d: --demo with explicit options exits 0" init --demo --ip-mode full --http-bind 0.0.0.0 --grpc-bind 10.0.0.5 \
  --public-url https://argos.test --admin-user operator --ram-mb 16384
demo_options_ok() {
  for line in ARGOS_IP_MODE=full ARGOS_HTTP_BIND=0.0.0.0 ARGOS_GRPC_BIND=10.0.0.5 ARGOS_WEB_PUBLIC_URL=https://argos.test \
    ARGOS_ADMIN_USERNAME=operator ARGOS_DEMO_MODE=true; do
    grep -qxF "$line" .env || { echo "missing: $line"; return 1; }
  done
}
check "T-NFR-16d: --ip-mode, --http-bind, --grpc-bind, --public-url and --admin-user win over the --demo defaults" demo_options_ok

# A kept demo token file that is not an agent token: exit 3, nothing written or hashed.
demo_refused() { # demo_refused <file> <content> <description>
  fresh
  init --demo --ram-mb 16384 >/dev/null 2>&1
  printf '%s' "$2" >"secrets/$1"
  rm -f secrets/clickhouse.env
  snapshot=$(secret_names)
  expect_exit 3 "T-SEC-74 T-NFR-16d: $3: exit 3" init --demo --ram-mb 16384
  output_has "secrets/$1" "T-SEC-74 T-NFR-16d: $3: the message names secrets/$1"
  if [ ! -e secrets/clickhouse.env ] && [ "$(secret_names)" = "$snapshot" ]; then
    pass_case "T-SEC-74 T-NFR-16d: $3: nothing is written or hashed"
  else
    fail_case "T-SEC-74 T-NFR-16d: $3: files were written"
  fi
}
demo_refused demo_order_api_token "argos_short" "a demo token of 5 characters after argos_"
demo_refused demo_payment_api_token "token_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" "a demo token without the argos_ prefix"
demo_refused demo_order_api_token "argos_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA!" "a demo token with a character outside base64url"
demo_refused demo_payment_api_token "" "an empty demo token file"
demo_refused demo_db_password "changeme" "the forbidden demo database password 'changeme'"

# A kept production .env: --demo warns that it does not switch an existing installation.
fresh
init --ram-mb 16384 --public-url http://argos.test:8080 >/dev/null 2>&1
expect_exit 0 "T-NFR-16d: --demo on a kept production .env exits 0" init --demo --ram-mb 16384
output_has ".env is kept and has ARGOS_DEMO_MODE=false" "T-NFR-16d: --demo on a kept production .env warns that demo mode stays off"
check "T-NFR-16d: the kept production .env gets no demo variable" sh -c "! grep -E '$DEMO_VARS' .env"

# The Compose files of a release package (scripts/release-compose.sh) pin the image defaults to the
# published registry images by digest: --demo must not override them in .env (ADR-0010 C-246 (11)).
REL_DIGEST=sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
REL_SERVER=ghcr.io/argos-apm/argos-server:0.1.0@$REL_DIGEST
fresh
sed -i "s#\${ARGOS_SERVER_IMAGE:-[^}]*}#\${ARGOS_SERVER_IMAGE:-$REL_SERVER}#g" compose.yaml
sed -i -e "s#\${ARGOS_DEMO_APP_IMAGE:-[^}]*}#\${ARGOS_DEMO_APP_IMAGE:-ghcr.io/argos-apm/argos-demo-app:0.1.0@$REL_DIGEST}#g" \
  -e "s#\${ARGOS_DEMO_LOAD_IMAGE:-[^}]*}#\${ARGOS_DEMO_LOAD_IMAGE:-ghcr.io/argos-apm/argos-demo-load:0.1.0@$REL_DIGEST}#g" compose.demo.yaml
expect_exit 0 "T-NFR-16d C-246 (11): --demo with the Compose files of a release package exits 0" init --demo --ram-mb 16384
release_demo_env_ok() {
  if grep -E '^(ARGOS_DEMO_APP_IMAGE|ARGOS_DEMO_LOAD_IMAGE)=' .env; then echo "a demo image overrides the pinned default"; return 1; fi
  for line in "ARGOS_SERVER_IMAGE=$REL_SERVER" ARGOS_VERSION=0.1.0 ARGOS_DEMO_MODE=true COMPOSE_PROFILES=demo \
    COMPOSE_FILE=compose.yaml:compose.unattended.yaml:compose.demo.yaml; do
    grep -qxF "$line" .env || { echo "missing: $line"; return 1; }
  done
}
output_lacks "jibDockerBuild" "T-NFR-16d C-246 (11): with release Compose files the summary asks for no local image build"
output_has "the published ones pinned by digest" "T-NFR-16d C-246 (11): with release Compose files the summary says the published images are pulled"
check "T-NFR-16d C-246 (11): with release Compose files --demo writes no ARGOS_DEMO_APP_IMAGE or ARGOS_DEMO_LOAD_IMAGE (the digest-pinned defaults apply), the server image is the pinned default, the demo variables stay" release_demo_env_ok
fresh
expect_exit 0 "T-NFR-16d C-246 (11): --demo with the repository Compose files exits 0" init --demo --ram-mb 16384
output_has ":sample-apps:demo-app:jibDockerBuild :sample-apps:load:jibDockerBuild" "T-NFR-16d C-246 (11): with the repository Compose files the summary still asks for the local image build"

# Linux Docker Engine as root: the demo secrets belong to uid 10001 (the server and the demo apps).
fresh
expect_exit 0 "T-NFR-16d: --demo on a Linux Docker Engine as root exits 0" env FAKE_DOCKER_OS="Ubuntu 22.04.5 LTS" sh ./argos-env-init.sh --demo --ram-mb 16384
output_has "sudo docker compose --profile demo up -d" "T-NFR-16d: on a Linux Docker Engine the summary runs the demo with sudo"
demo_owner_ok() {
  for f in $SECRETS admin_password $DEMO_SECRETS; do
    [ "$(owner_of "secrets/$f")" = 10001 ] || { echo "$f owner $(owner_of "secrets/$f")"; return 1; }
  done
  modes_ok
}
check "T-NFR-16d: on a Linux Docker Engine the demo tokens and the demo database password belong to uid 10001 (0600)" demo_owner_ok

# Memory plan with the demo services (17 section 14 "resource calculation"): 7608 MiB is what Docker
# Desktop shows on the WO-41 laptop ("8 GB" VM of a 16 GB host).
for ram in 7608 8192 16384 32768; do
  fresh
  init --demo --ram-mb "$ram" >/tmp/plan.out 2>&1 || true
  sed -n '/^# Memory plan for/,/^ARGOS_PG_EFFECTIVE_CACHE_SIZE=/p' .env | sed 's/ (--ram-mb; / (/' >/tmp/plan.env
  if diff -u "$T/expected/ram-$ram-demo.env" /tmp/plan.env >/tmp/plan.diff 2>&1; then
    pass_case "T-NFR-16d 17 section 14: memory plan for --demo --ram-mb $ram equals expected/ram-$ram-demo.env"
  else
    cp /tmp/plan.diff "$LAST"
    fail_case "T-NFR-16d 17 section 14: memory plan for --demo --ram-mb $ram differs from expected/ram-$ram-demo.env"
  fi
done
demo_plan_rules() { # demo_plan_rules <ram>
  fresh
  init --demo --ram-mb "$1" >/dev/null 2>&1 || { echo "exit $?"; return 1; }
  server=$(plan_value ARGOS_SERVER_MEM)
  ch=$(plan_value ARGOS_CH_MEM)
  pg=$(plan_value ARGOS_PG_MEM)
  max=$(plan_value ARGOS_CH_MAX_SERVER_MEMORY_USAGE)
  heap=$(sed -n 's/^ARGOS_SERVER_JAVA_OPTS=.*-Xmx\([0-9][0-9]*\)m.*$/\1/p' .env)
  [ $((server + ch + pg + 1536)) -le $(($1 * 90 / 100)) ] || { echo "sum $((server + ch + pg)) + demo 1536 > 0.9 x $1"; return 1; }
  [ "$server" -eq $((heap + 1024)) ] || { echo "server container $server is not heap $heap + 1024"; return 1; }
  if [ "$1" -ge 9672 ]; then
    if [ "$ch" -lt 3072 ] || [ "$heap" -lt 2048 ]; then echo "ClickHouse $ch < 3072 or heap $heap < 2048"; return 1; fi
  else
    if [ "$ch" -lt 2048 ] || [ "$heap" -lt 1024 ]; then echo "demo tier: ClickHouse $ch < 2048 or heap $heap < 1024"; return 1; fi
    if [ "$heap" -ne 2048 ] && [ "$ch" -ne 2048 ]; then echo "demo tier: heap $heap cut while ClickHouse is $ch"; return 1; fi
  fi
  [ "$max" -eq $((ch * 1048576 * 9 / 10)) ] || { echo "max_server_memory_usage $max is not 0.9 x $ch MiB"; return 1; }
}
for ram in 7396 7608 8192 9000 9671 9672 12288 16384 32768 65536; do
  check "T-NFR-16d C-198: --demo --ram-mb $ram: containers + 1536 MiB demo <= 0.9 x RAM, server = heap + 1 GiB, demo tier floors ClickHouse 2 GiB and heap 1 GiB, max = 0.9 x container" demo_plan_rules "$ram"
done
fresh
expect_exit 2 "T-NFR-16d: --demo --ram-mb 7395 (one below the demo minimum) exits 2" init --demo --ram-mb 7395
output_has "+ demo services 1536 MiB = 6656 MiB exceed 0.9 x RAM = 6655 MiB" "T-NFR-16d: the refusal counts the demo services"
check "T-NFR-16d: the refused --demo writes no .env and no secrets/" nothing_written
expect_exit 0 "T-NFR-16d: --demo --ram-mb 7396 (the demo minimum) exits 0" init --demo --ram-mb 7396
output_has "below the 9672 MiB of the normal memory plan with the demo services" "T-NFR-16d: below 9672 MiB --demo warns of the minimum-spec tier"

# --- --self-signed-tls: evaluation-only gRPC certificate (ADR-0010 C-172 (1), T-SEC-74a, WO-43) ----
STD="--ram-mb 16384 --public-url http://argos.test:8080"
nothing_at_all() { [ ! -e .env ] && [ ! -e secrets ] && [ ! -e tls ] || { ls -A; return 1; }; }
tls_refused() { # tls_refused <description> <message> <options...>
  tdesc=$1 tmsg=$2
  shift 2
  fresh
  # shellcheck disable=SC2086 # STD is split on purpose
  expect_exit 2 "T-SEC-74a: $tdesc: exit 2" init $STD "$@"
  output_has "$tmsg" "T-SEC-74a: $tdesc: the message says why"
  check "T-SEC-74a: $tdesc: nothing is written" nothing_at_all
}
tls_refused "a name with '!'" "'bad!host' is not a host name" --self-signed-tls 'argos.test,bad!host'
tls_refused "an empty name between commas" "an empty name" --self-signed-tls 'argos.test,,192.0.2.1'
tls_refused "an empty value" "needs host names or IP addresses" --self-signed-tls ''
tls_refused "a name starting with a dash" "is not a host name" --self-signed-tls '-argos.test'
tls_refused "a wildcard" "is not a host name" --self-signed-tls '*.argos.test'
tls_refused "17 names" "at most 16 names" --self-signed-tls 'a1,a2,a3,a4,a5,a6,a7,a8,a9,a10,a11,a12,a13,a14,a15,a16,a17'
tls_refused "with --demo" "does not apply to --demo" --demo --self-signed-tls localhost
fresh
# shellcheck disable=SC2086
init $STD >/dev/null 2>&1
no_tls_dir() { [ ! -e tls ]; }
check "T-SEC-74a: never the default: without --self-signed-tls no tls/ is written" no_tls_dir

if command -v openssl >/dev/null 2>&1; then
  fresh
  # shellcheck disable=SC2086
  expect_exit 0 "T-SEC-74a: --self-signed-tls Argos.Test,192.0.2.10,[2001:db8::10]: exit 0" init $STD --self-signed-tls 'Argos.Test,192.0.2.10,[2001:db8::10]'
  cp "$LAST" /tmp/tls.out
  output_has "WARNING: tls/server.crt is a SELF-SIGNED certificate for an evaluation only (valid 365 days; names: Argos.Test, 192.0.2.10, 2001:db8::10)" \
    "T-SEC-74a: a WARN says that the certificate is self-signed and for an evaluation only"
  output_has "server.tls.ca-file" "T-SEC-74a: the output says that agents need it as server.tls.ca-file"
  output_lacks "Place the gRPC certificate and key" "T-SEC-74a: the next steps do not ask for a certificate any more"
  tls_modes_ok() {
    [ "$(mode_of tls)" = "drwx------" ] || { echo "tls $(mode_of tls)"; return 1; }
    [ "$(mode_of tls/server.key)" = "-rw-------" ] || { echo "key $(mode_of tls/server.key)"; return 1; }
    [ "$(mode_of tls/server.crt)" = "-rw-r--r--" ] || { echo "crt $(mode_of tls/server.crt)"; return 1; }
    [ "$(owner_of tls/server.key)" = 0 ] || { echo "Docker Desktop: key owner $(owner_of tls/server.key)"; return 1; }
    # shellcheck disable=SC2012 # the script's own fixed names
    [ "$(ls -A tls | tr '\n' ' ')" = "server.crt server.key " ] || { ls -A tls; return 1; }
  }
  check "T-SEC-74a: tls/ 0700, server.key 0600, server.crt 0644, nothing else left in tls/" tls_modes_ok
  san_ok() {
    san=$(openssl x509 -noout -ext subjectAltName -in tls/server.crt | tail -n 1)
    case $san in
      *"DNS:argos.test"*"IP Address:192.0.2.10"*"IP Address:2001:DB8:0:0:0:0:0:10"*) ;;
      *) echo "SAN: $san"; return 1 ;;
    esac
  }
  check "T-SEC-74a: the subject alternative names are DNS for a name (lower case), IP for IPv4 and IPv6" san_ok
  validity_ok() {
    openssl x509 -noout -checkend $((364 * 86400)) -in tls/server.crt >/dev/null || { echo "expires within 364 days"; return 1; }
    ! openssl x509 -noout -checkend $((366 * 86400)) -in tls/server.crt >/dev/null || { echo "valid beyond 366 days"; return 1; }
    [ "$(openssl x509 -noout -subject -in tls/server.crt | grep -c 'self-signed')" -eq 1 ] || return 1
    [ "$(openssl x509 -noout -subject -in tls/server.crt | sed 's/^subject=//')" = "$(openssl x509 -noout -issuer -in tls/server.crt | sed 's/^issuer=//')" ]
  }
  check "T-SEC-74a: self-signed (issuer = subject), valid 365 days" validity_ok
  key_ok() {
    [ "$(head -n 1 tls/server.key)" = "-----BEGIN PRIVATE KEY-----" ] || { echo "not unencrypted PKCS#8"; return 1; }
    [ "$(openssl pkey -in tls/server.key -pubout 2>/dev/null)" = "$(openssl x509 -noout -pubkey -in tls/server.crt)" ] || { echo "key does not match"; return 1; }
    ! grep -q 'PRIVATE KEY' /tmp/tls.out || { echo "the key is in the output"; return 1; }
  }
  check "T-SEC-74a: the key is unencrypted PKCS#8 (as the server needs), matches the certificate and is never printed" key_ok

  tls_before=$(cat tls/server.crt tls/server.key | cksum)
  tls_unchanged() { [ "$(cat tls/server.crt tls/server.key | cksum)" = "$tls_before" ]; }
  # shellcheck disable=SC2086
  expect_exit 2 "T-SEC-74a: --self-signed-tls with existing tls/server.* files: exit 2" init $STD --self-signed-tls argos.test
  output_has "--self-signed-tls refused, nothing was changed: tls/server.crt exists" "T-SEC-74a: the existing certificate is named"
  check "T-SEC-74a: the existing certificate and key are unchanged" tls_unchanged
  rm tls/server.crt
  # shellcheck disable=SC2086
  expect_exit 2 "T-SEC-74a: --self-signed-tls with an existing tls/server.key only: exit 2" init $STD --self-signed-tls argos.test
  output_has "tls/server.key exists" "T-SEC-74a: the existing key is named"
  # shellcheck disable=SC2086
  init $STD --force --self-signed-tls argos.test >/dev/null 2>&1
  tls_before=$(cat tls/server.crt tls/server.key | cksum)
  # shellcheck disable=SC2086
  expect_exit 0 "T-SEC-74a: a rerun without --self-signed-tls: exit 0" init $STD
  check "T-SEC-74a: a rerun without --self-signed-tls keeps the certificate" tls_unchanged
  # shellcheck disable=SC2086
  expect_exit 0 "T-SEC-74a: --force --self-signed-tls (no volumes): exit 0" init $STD --force --self-signed-tls other.test
  tls_replaced() { ! tls_unchanged && openssl x509 -noout -ext subjectAltName -in tls/server.crt | grep -q 'DNS:other.test'; }
  check "T-SEC-74a: --force replaces the certificate" tls_replaced

  fresh
  # shellcheck disable=SC2086 # STD is split on purpose
  expect_exit 0 "T-SEC-74a: --self-signed-tls on a Linux Docker Engine as root: exit 0" \
    env FAKE_DOCKER_OS="Ubuntu 22.04.5 LTS" sh ./argos-env-init.sh $STD --self-signed-tls argos.test
  linux_owner_ok() {
    [ "$(owner_of tls/server.key)" = 10001 ] || { echo "key owner $(owner_of tls/server.key)"; return 1; }
    [ "$(mode_of tls/server.key)" = "-rw-------" ] && [ "$(mode_of tls/server.crt)" = "-rw-r--r--" ]
  }
  check "T-SEC-74a: on a Linux Docker Engine the key belongs to uid 10001 (0600), the certificate is 0644" linux_owner_ok

  # OpenSSL without -addext (before 1.1.1): exit 2 before anything is written.
  mkdir -p /tmp/old-openssl
  real_openssl=$(command -v openssl)
  # shellcheck disable=SC2016 # writes a script: "$@" belongs to it
  printf '#!/bin/sh\nif [ "$1 $2" = "req -help" ]; then echo "usage: req [options]" >&2; exit 0; fi\nexec %s "$@"\n' "$real_openssl" >/tmp/old-openssl/openssl
  chmod 0755 /tmp/old-openssl/openssl
  fresh
  # shellcheck disable=SC2086 # STD is split on purpose
  expect_exit 2 "T-SEC-74a: an OpenSSL without -addext: exit 2" env PATH="/tmp/old-openssl:$PATH" sh ./argos-env-init.sh $STD --self-signed-tls argos.test
  output_has "openssl 1.1.1 or later for --self-signed-tls" "T-SEC-74a: the old OpenSSL is named"
  check "T-SEC-74a: an OpenSSL without -addext: nothing is written" nothing_at_all
else
  fresh
  # shellcheck disable=SC2086
  expect_exit 2 "T-SEC-74a: --self-signed-tls without openssl: exit 2" init $STD --self-signed-tls argos.test
  output_has "openssl 1.1.1 or later for --self-signed-tls" "T-SEC-74a: the missing openssl is named"
  check "T-SEC-74a: without openssl nothing is written" nothing_at_all
fi

finish
