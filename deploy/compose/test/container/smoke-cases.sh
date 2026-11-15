#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Cases of smoke.sh without a running stack (T-SEC-74, T-SEC-75, SEC-55): the file check on a
# container-local file system (POSIX modes) and the port check on the JSON of
# `docker compose ps --format json` from the test double container/fake-bin/docker, each with
# mutations that must fail (a published 5432, .env 0644, ...). The image, network, user, health,
# Actuator and setup checks (SEC-73, SEC-76, T-SEC-55, T-SEC-47) run on the answers of the test
# doubles fake-bin/docker and fake-bin/curl, with their mutations too; the interrupt cases check
# exit 130/143. Run by ../smoke-logic.test.sh.
set -u
# shellcheck source=deploy/compose/test/container/lib.sh
. "$T/container/lib.sh"

mkdir -p /tmp/fake-bin
cp "$T/container/fake-bin/docker" /tmp/fake-bin/docker
chmod 0755 /tmp/fake-bin/docker
PATH=/tmp/fake-bin:$PATH
export PATH

WORK=/tmp/argos-smoke
cd /
rm -rf "$WORK"
mkdir -p "$WORK"
cp -R "$SRC/." "$WORK/"
rm -rf "$WORK/.env" "$WORK/secrets" "$WORK/tls" "$WORK/compose.override.yaml"
cd "$WORK" || exit 1
sh ./argos-env-init.sh --ram-mb 16384 --public-url http://argos.test:8080 >/dev/null 2>&1 \
  || { echo "FAIL  argos-env-init.sh did not prepare the directory"; exit 1; }
mkdir -p tls
printf 'certificate\n' >tls/server.crt
printf 'key\n' >tls/server.key
chmod 0644 tls/server.crt
chmod 0600 tls/server.key
cp .env /tmp/env.backup

smoke() { sh ./smoke.sh "$@"; }

# --- files ------------------------------------------------------------------------------------
expect_exit 0 "smoke files: the state written by argos-env-init.sh passes" smoke --only files
output_has "ok   .env 0600, secrets/ 0700, its files 0600" "smoke files: reports the verified modes"

mutate_mode() { # mutate_mode <path> <mode> <restore mode> <expected message>
  chmod "$2" "$1"
  expect_exit 1 "T-SEC-74 mutation: $1 $2 fails" smoke --only files
  output_has "$4" "T-SEC-74 mutation: $1 $2: the message names it"
  chmod "$3" "$1"
}
mutate_mode .env 0644 0600 ".env has mode -rw-r--r--, expected 0600"
mutate_mode .env 0640 0600 ".env has mode -rw-r-----"
mutate_mode secrets 0755 0700 "secrets/ has mode drwxr-xr-x, expected 0700"
mutate_mode secrets/pg_app_password 0644 0600 "secrets/pg_app_password has mode -rw-r--r--"
mutate_mode secrets/clickhouse.env 0644 0600 "secrets/clickhouse.env has mode -rw-r--r--"
mutate_mode tls/server.key 0644 0600 "tls/server.key has mode -rw-r--r--"

value=$(first_line secrets/pg_app_password)
printf 'SOME_SETTING=%s\n' "$value" >>.env
value=
expect_exit 1 "T-SEC-74 mutation: a secret value copied into .env fails" smoke --only files
output_has ".env contains the value of secrets/pg_app_password" "T-SEC-74 mutation: the leaked file is named"
cp /tmp/env.backup .env
chmod 0600 .env

printf 'ARGOS_PG_PASSWORD=Xy7-not-a-file-reference\n' >>.env
expect_exit 1 "T-SEC-74 mutation: a password variable in .env fails" smoke --only files
output_has ".env sets secret variables: ARGOS_PG_PASSWORD" "T-SEC-74 mutation: the variable is named"
cp /tmp/env.backup .env
chmod 0600 .env

cp secrets/pg_owner_password /tmp/owner.backup
printf 'changeme' >secrets/pg_owner_password
expect_exit 1 "T-SEC-74 mutation: a default password in secrets/ fails" smoke --only files
output_has "secrets/pg_owner_password holds a forbidden default password" "T-SEC-74 mutation: the default password file is named"
: >secrets/pg_owner_password
expect_exit 1 "T-SEC-74 mutation: an empty secret fails" smoke --only files
output_has "secrets/pg_owner_password is empty" "T-SEC-74 mutation: the empty file is named"
cp /tmp/owner.backup secrets/pg_owner_password
chmod 0600 secrets/pg_owner_password
expect_exit 0 "smoke files: passes again after the restore" smoke --only files

# --- ports --------------------------------------------------------------------------------------
pub() { printf '{"URL":"%s","TargetPort":%s,"PublishedPort":%s,"Protocol":"tcp"}' "$1" "$2" "$3"; }
container() { # container <service> <publishers...>: one line of docker compose ps --format json
  svc=$1
  shift
  list=
  for p in "$@"; do
    list="$list${list:+,}$p"
  done
  printf '{"Command":"x","ID":"%s0000","Name":"argos-%s-1","Project":"argos","Service":"%s","State":"running","Health":"healthy","Labels":"com.docker.compose.project=argos","Publishers":[%s]}\n' \
    "$svc" "$svc" "$svc" "$list"
}
CH="$(pub '' 8123 0) $(pub '' 9000 0) $(pub '' 9009 0)"
PG="$(pub '' 5432 0)"
HTTP="$(pub 0.0.0.0 8080 8080) $(pub :: 8080 8080)"
GRPC="$(pub 0.0.0.0 9100 9100) $(pub :: 9100 9100)"
# shellcheck disable=SC2086 # publisher lists are split on purpose
write_ps() { # write_ps <file> <server publishers> <clickhouse publishers> <postgres publishers>
  {
    container clickhouse $3
    container postgres $4
    container server $2
  } >"$1"
}

write_ps /tmp/good.json "$HTTP $GRPC" "$CH" "$PG"
expect_exit 0 "T-SEC-75: published 8080 and 9100 only (IPv4 and IPv6) passes" env FAKE_PS_JSON=/tmp/good.json sh ./smoke.sh --only ports
output_has "published ports: exactly 8080 and 9100" "T-SEC-75: the passing port set is reported"

ports_fail() { # ports_fail <file> <description> <message> [env assignments...]
  file=$1
  desc=$2
  msg=$3
  shift 3
  expect_exit 1 "$desc" env FAKE_PS_JSON="$file" "$@" sh ./smoke.sh --only ports
  output_has "$msg" "$desc: the message names it"
}

write_ps /tmp/pg.json "$HTTP $GRPC" "$CH" "$(pub 0.0.0.0 5432 5432)"
ports_fail /tmp/pg.json "T-SEC-75 mutation: PostgreSQL publishes 5432" "database port 5432 is published"
write_ps /tmp/ch.json "$HTTP $GRPC" "$(pub 0.0.0.0 8123 18123) $(pub '' 9000 0)" "$PG"
ports_fail /tmp/ch.json "T-SEC-75 mutation: ClickHouse 8123 published as 18123" "database port 8123 is published"
write_ps /tmp/ch9000.json "$HTTP $GRPC" "$(pub '' 8123 0) $(pub 127.0.0.1 9000 9000)" "$PG"
ports_fail /tmp/ch9000.json "T-SEC-75 mutation: ClickHouse 9000 published on 127.0.0.1" "database port 9000 is published"
write_ps /tmp/extra.json "$HTTP $GRPC $(pub 0.0.0.0 22 2222)" "$CH" "$PG"
ports_fail /tmp/extra.json "T-SEC-75 mutation: an extra published port" "unexpected published port 0.0.0.0:2222"
write_ps /tmp/no-grpc.json "$HTTP" "$CH" "$PG"
ports_fail /tmp/no-grpc.json "T-SEC-75 mutation: 9100 not published" "port 9100 is not published"
write_ps /tmp/mgmt-any.json "$HTTP $GRPC $(pub 0.0.0.0 8081 8081)" "$CH" "$PG"
ports_fail /tmp/mgmt-any.json "SEC-55 mutation: 8081 on 0.0.0.0 without ARGOS_MGMT_EXPOSE" "although ARGOS_MGMT_EXPOSE is not true"
ports_fail /tmp/mgmt-any.json "SEC-55 mutation: 8081 on 0.0.0.0 with ARGOS_MGMT_EXPOSE=true" "only 127.0.0.1 is allowed" ARGOS_MGMT_EXPOSE=true
write_ps /tmp/mgmt.json "$HTTP $GRPC $(pub 127.0.0.1 8081 8081)" "$CH" "$PG"
ports_fail /tmp/mgmt.json "SEC-55 mutation: 127.0.0.1:8081 without ARGOS_MGMT_EXPOSE" "although ARGOS_MGMT_EXPOSE is not true"
expect_exit 0 "T-SEC-75: ARGOS_MGMT_EXPOSE=true with 127.0.0.1:8081 passes" env FAKE_PS_JSON=/tmp/mgmt.json ARGOS_MGMT_EXPOSE=true sh ./smoke.sh --only ports
output_has "management 127.0.0.1:8081" "T-SEC-75: the management port is reported"
ports_fail /tmp/good.json "T-SEC-75 mutation: ARGOS_MGMT_EXPOSE=true but 8081 missing" "127.0.0.1:8081 is not published" ARGOS_MGMT_EXPOSE=true
ports_fail /tmp/good.json "T-SEC-75 mutation: ARGOS_HTTP_BIND=127.0.0.1 but 8080 on every address" "port 8080 is published on 0.0.0.0" ARGOS_HTTP_BIND=127.0.0.1
write_ps /tmp/local.json "$(pub 127.0.0.1 8080 8080) $GRPC" "$CH" "$PG"
expect_exit 0 "T-SEC-75: ARGOS_HTTP_BIND=127.0.0.1 with 127.0.0.1:8080 passes" env FAKE_PS_JSON=/tmp/local.json ARGOS_HTTP_BIND=127.0.0.1 sh ./smoke.sh --only ports
write_ps /tmp/proxy.json "$HTTP $GRPC" "$CH" "$PG"
container proxy "$(pub 0.0.0.0 443 443)" >>/tmp/proxy.json
ports_fail /tmp/proxy.json "T-SEC-75 mutation: 443 without ARGOS_PROXY" "port 443 is published"
# Behind the proxy 8080 is published on 127.0.0.1 only, so that no client bypasses the proxy.
write_ps /tmp/proxy-local.json "$(pub 127.0.0.1 8080 8080) $GRPC" "$CH" "$PG"
container proxy "$(pub 0.0.0.0 443 443)" >>/tmp/proxy-local.json
expect_exit 0 "T-SEC-75: ARGOS_PROXY=true with 443 and ARGOS_HTTP_BIND=127.0.0.1 passes" \
  env FAKE_PS_JSON=/tmp/proxy-local.json ARGOS_PROXY=true ARGOS_HTTP_BIND=127.0.0.1 sh ./smoke.sh --only ports
ports_fail /tmp/proxy.json "T-SEC-75 mutation: ARGOS_PROXY=true with ARGOS_HTTP_BIND of .env (0.0.0.0)" \
  "ARGOS_PROXY=true but ARGOS_HTTP_BIND is 0.0.0.0" ARGOS_PROXY=true
ports_fail /tmp/proxy.json "T-SEC-75 mutation: ARGOS_PROXY=true with ARGOS_HTTP_BIND=0.0.0.0" \
  "ARGOS_PROXY=true but ARGOS_HTTP_BIND is 0.0.0.0" ARGOS_PROXY=true ARGOS_HTTP_BIND=0.0.0.0
write_ps /tmp/proxy-lan.json "$(pub 10.0.0.5 8080 8080) $GRPC" "$CH" "$PG"
container proxy "$(pub 0.0.0.0 443 443)" >>/tmp/proxy-lan.json
ports_fail /tmp/proxy-lan.json "T-SEC-75 mutation: ARGOS_PROXY=true with 8080 on a LAN address (ARGOS_HTTP_BIND=10.0.0.5)" \
  "ARGOS_PROXY=true but ARGOS_HTTP_BIND is 10.0.0.5" ARGOS_PROXY=true ARGOS_HTTP_BIND=10.0.0.5
# Older Compose releases print one JSON array instead of one object per line.
# shellcheck disable=SC2086 # publisher lists are split on purpose
{
  printf '['
  container clickhouse $CH | tr -d '\n'
  printf ','
  container postgres $PG | tr -d '\n'
  printf ','
  container server $HTTP $GRPC | tr -d '\n'
  printf ']\n'
} >/tmp/array.json
expect_exit 0 "T-SEC-75: the JSON array format of older Compose releases passes" env FAKE_PS_JSON=/tmp/array.json sh ./smoke.sh --only ports
write_ps /tmp/array-pg.json "$HTTP $GRPC" "$CH" "$(pub :: 5432 5432)"
tr -d '\n' </tmp/array-pg.json | sed -e 's/}{/},{/g' -e 's/^/[/' -e 's/$/]/' >/tmp/array-pg2.json
ports_fail /tmp/array-pg2.json "T-SEC-75 mutation: 5432 on :: in the array format" "database port 5432 is published"
: >/tmp/empty.json
ports_fail /tmp/empty.json "smoke ports: no container fails" "no container of this installation exists"

# --- images, network, user, health, actuator, setup ---------------------------------------------
# On the answers of the test doubles: fake-bin/docker (docker inspect, docker network inspect,
# docker compose exec) and fake-bin/curl (the HTTP requests), both driven by FAKE_* variables.
cp "$T/container/fake-bin/curl" /tmp/fake-bin/curl
chmod 0755 /tmp/fake-bin/curl
CH_IMAGE=clickhouse/clickhouse-server:26.8.34.13@sha256:1111111111111111111111111111111111111111111111111111111111111111
PG_IMAGE=postgres:16.15@sha256:2222222222222222222222222222222222222222222222222222222222222222
mkdir -p /tmp/clickhouse /tmp/postgres # ../clickhouse and ../postgres of the working copy
printf 'image=%s\n' "$CH_IMAGE" >/tmp/clickhouse/image.properties
printf 'image=%s\n' "$PG_IMAGE" >/tmp/postgres/image.properties
FAKE_IMAGE_CLICKHOUSE=$CH_IMAGE
FAKE_IMAGE_POSTGRES=$PG_IMAGE
FAKE_IMAGE_SERVER=$(sed -n 's/^ARGOS_SERVER_IMAGE=//p' .env)
export FAKE_IMAGE_CLICKHOUSE FAKE_IMAGE_POSTGRES FAKE_IMAGE_SERVER
OTHER_DIGEST=3333333333333333333333333333333333333333333333333333333333333333

check_fail() { # check_fail <check> <description> <message> [env assignments...]
  chk=$1
  desc=$2
  msg=$3
  shift 3
  expect_exit 1 "$desc" env "$@" sh ./smoke.sh --only "$chk"
  output_has "$msg" "$desc: the message names it"
}

expect_exit 0 "SEC-73: images pinned as in image.properties pass" smoke --only images
output_has "images: ClickHouse and PostgreSQL pinned as in image.properties" "SEC-73: the pinned images are reported"
check_fail images "SEC-73 mutation: ClickHouse runs another digest than image.properties" \
  "clickhouse runs clickhouse/clickhouse-server:26.8.34.13@sha256:$OTHER_DIGEST, expected $CH_IMAGE" \
  FAKE_IMAGE_CLICKHOUSE="clickhouse/clickhouse-server:26.8.34.13@sha256:$OTHER_DIGEST"
check_fail images "SEC-73 mutation: PostgreSQL runs another digest than image.properties" \
  "postgres runs postgres:16.15@sha256:$OTHER_DIGEST, expected $PG_IMAGE" FAKE_IMAGE_POSTGRES="postgres:16.15@sha256:$OTHER_DIGEST"
check_fail images "SEC-73 mutation: the server runs another image than ARGOS_SERVER_IMAGE" \
  "server runs argos-server:other, expected ARGOS_SERVER_IMAGE=" FAKE_IMAGE_SERVER=argos-server:other

expect_exit 0 "T-SEC-75 (network): back internal, the databases on back only pass" smoke --only network
output_has "network argos_back is internal" "T-SEC-75 (network): the network layout is reported"
check_fail network "T-SEC-75 mutation: the back network is not internal" \
  "network argos_back is not internal (Internal=false)" FAKE_BACK_INTERNAL=false
check_fail network "T-SEC-75 mutation: PostgreSQL is attached to front too" \
  "postgres is attached to 'argos_back argos_front'; expected argos_back only" FAKE_NETS_POSTGRES="argos_back argos_front "
check_fail network "T-SEC-75 mutation: ClickHouse is attached to front only" \
  "clickhouse is attached to 'argos_front'; expected argos_back only" FAKE_NETS_CLICKHOUSE="argos_front "

expect_exit 0 "SEC-76: the server as uid/gid 10001 with a writable /data (0700) passes" smoke --only user
output_has "server process uid/gid 10001" "SEC-76: the server user is reported"
check_fail user "SEC-76 mutation: the server process runs as root" \
  "the server process runs as uid/gid '0 0 0 0 0 0 0 0 ', expected 10001 (never root)" FAKE_SERVER_UID=0
check_fail user "SEC-76 mutation: the server cannot write /data" "the server cannot write /data" FAKE_DATA_WRITABLE=0
check_fail user "SEC-76 mutation: /data is world-writable" "/data is '10001:10001 777', expected 10001:10001 700" \
  FAKE_DATA_STAT="10001:10001 777"

expect_exit 0 "smoke health: healthy containers and UP on the management port pass" smoke --only health
check_fail health "smoke health mutation: an unhealthy container fails" "postgres is not healthy (unhealthy)" FAKE_HEALTH=unhealthy

expect_exit 0 "T-SEC-55: Actuator 404 on 8080 and nothing on 127.0.0.1:8081 passes" smoke --only actuator
output_has "Actuator: 404 on 8080 (env, health, prometheus, info); management port not reachable" "T-SEC-55: the Actuator state is reported"
check_fail actuator "T-SEC-55 mutation: /actuator/env answers 200 on 8080" \
  "http://127.0.0.1:8080/actuator/env answers 200, expected 404" FAKE_ACTUATOR_ENV_8080=200
check_fail actuator "T-SEC-55 mutation: the management port answers on the host without ARGOS_MGMT_EXPOSE" \
  "something answers on 127.0.0.1:8081 of the host although ARGOS_MGMT_EXPOSE is not true" FAKE_MGMT=up
expect_exit 0 "T-SEC-55: ARGOS_MGMT_EXPOSE=true with health 200, env 404, prometheus 401 passes" \
  env FAKE_MGMT=up ARGOS_MGMT_EXPOSE=true sh ./smoke.sh --only actuator

# Setup token wizard and unattended setup (T-SEC-47). The default run never signs in.
no_sign_in() { ! grep -q '/api/v1/auth/' /tmp/curl.log; }
rm -f /tmp/curl.log
expect_exit 0 "T-SEC-47: wizard open: a token file 0600, no token and a wrong token refused (403)" \
  env FAKE_NEEDS_SETUP=true FAKE_SETUP_TOKEN_FILE=1 FAKE_CURL_LOG=/tmp/curl.log sh ./smoke.sh --only setup
output_has "the wizard stays open" "T-SEC-47: the open wizard is reported"
check "T-SEC-47: the wizard check does not sign in" no_sign_in
check_fail setup "T-SEC-47 mutation: no setup token file while the wizard is open" \
  "/data/setup-token is 'missing', expected mode 600" FAKE_NEEDS_SETUP=true
expect_exit 0 "T-SEC-47: setup completed: the wizard answers 404 and the token file is gone" smoke --only setup
check_fail setup "T-SEC-47 mutation: setup completed but the token file is left" \
  "setup completed but /data/setup-token still exists" FAKE_SETUP_TOKEN_FILE=1

(umask 077 && dd if=/dev/urandom bs=18 count=1 2>/dev/null | base64 | tr -d '\n=' | tr '+/' '-_' >secrets/admin_password)
(umask 077 && dd if=/dev/urandom bs=18 count=1 2>/dev/null | base64 | tr -d '\n=' | tr '+/' '-_' >/tmp/changed-password)
rm -f /tmp/curl.log
expect_exit 0 "T-SEC-47: unattended setup: administrator exists, wizard 404, no token file passes without signing in" \
  env ARGOS_UNATTENDED=true FAKE_CURL_LOG=/tmp/curl.log sh ./smoke.sh --only setup
output_has "an administrator exists (needs_setup=false), wizard closed (404), no setup token file" "T-SEC-47: the unattended state is reported"
check "T-SEC-47: the default unattended run does not sign in (no /api/v1/auth request)" no_sign_in
rm -f /tmp/curl.log
expect_exit 0 "T-SEC-47: unattended setup after the password change still passes by default" \
  env ARGOS_UNATTENDED=true FAKE_ADMIN_PASSWORD_FILE=/tmp/changed-password FAKE_CURL_LOG=/tmp/curl.log sh ./smoke.sh --only setup
check "T-SEC-47: after the password change the default run does not sign in either" no_sign_in
rm -f /tmp/curl.log
expect_exit 0 "T-SEC-47: --check-admin-login signs in with secrets/admin_password (must change the password)" \
  env ARGOS_UNATTENDED=true FAKE_CURL_LOG=/tmp/curl.log sh ./smoke.sh --only setup --check-admin-login
output_has "must change the password, signed out again" "T-SEC-47: --check-admin-login reports the sign-in and the sign-out"
signed_in_and_out() {
  grep -qx 'POST /api/v1/auth/login -> 200' /tmp/curl.log \
    && grep -qx 'POST /api/v1/auth/logout csrf=ok -> 204' /tmp/curl.log
}
check "T-SEC-47: --check-admin-login signs out again with the CSRF header (no session left)" signed_in_and_out
expect_exit 1 "T-SEC-47: --check-admin-login after the password change fails (it is for the time before the change)" \
  env ARGOS_UNATTENDED=true FAKE_ADMIN_PASSWORD_FILE=/tmp/changed-password sh ./smoke.sh --only setup --check-admin-login
output_has "sign-in of admin with secrets/admin_password (--check-admin-login works only before the password was changed): HTTP 401, expected 200" \
  "T-SEC-47: --check-admin-login after the password change: the failed sign-in is named"
expect_exit 2 "smoke: --check-admin-login without ARGOS_UNATTENDED=true is a usage error" smoke --only setup --check-admin-login
check_fail setup "T-SEC-47 mutation: unattended setup without an administrator" \
  "unattended setup: the server has no administrator (needs_setup=true)" ARGOS_UNATTENDED=true FAKE_NEEDS_SETUP=true
check_fail setup "T-SEC-47 mutation: unattended setup with a setup token file" \
  "unattended setup: /data/setup-token exists" ARGOS_UNATTENDED=true FAKE_SETUP_TOKEN_FILE=1
rm -f secrets/admin_password /tmp/changed-password /tmp/curl.log

# --- interrupts: Ctrl-C and SIGTERM end the run (exit 130, 143) and remove its temporary files ---
# fake-bin/docker sends the signal to smoke.sh while it waits for 'docker compose ps'.
mkdir -p /tmp/smoke-tmp
tmp_empty() { [ -z "$(ls -A /tmp/smoke-tmp)" ] || { ls -A /tmp/smoke-tmp; return 1; }; }
no_check_after_interrupt() { ! grep -q 'smoke: ok\|smoke: FAIL\|smoke: passed' "$OUT"; }
expect_exit 130 "smoke: SIGINT (Ctrl-C) ends the run with exit 130" \
  env TMPDIR=/tmp/smoke-tmp FAKE_PS_JSON=/tmp/good.json FAKE_SIGNAL=INT FAKE_SIGNAL_TARGET=./smoke.sh sh ./smoke.sh --only ports
check "smoke: after SIGINT no further check runs" no_check_after_interrupt
check "smoke: after SIGINT the temporary directory is removed" tmp_empty
expect_exit 143 "smoke: SIGTERM ends the run with exit 143" \
  env TMPDIR=/tmp/smoke-tmp FAKE_PS_JSON=/tmp/good.json FAKE_SIGNAL=TERM FAKE_SIGNAL_TARGET=./smoke.sh sh ./smoke.sh --only ports
check "smoke: after SIGTERM no further check runs" no_check_after_interrupt
check "smoke: after SIGTERM the temporary directory is removed" tmp_empty

# --- usage ------------------------------------------------------------------------------------
expect_exit 2 "smoke: an unknown check is a usage error" smoke --only frobnicate
expect_exit 2 "smoke: an unknown option is a usage error" smoke --frobnicate

finish
