#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# req: SEC-06, SEC-84
#
# Cases of diag-bundle.sh without a stack (WO-43; docs/17 section 4.4, docs/03 section 3.4, SEC-06,
# SEC-84): the outer archive is 0600 under a caller umask of 022 (T-SEC-84d), holds the server's
# bundle (argos-admin bundle in the server container, copied out and deleted there) and only what
# the container cannot see, masks .env and the container logs, never runs `docker compose config`
# and takes nothing from secrets/ or tls/ (T-SEC-06i logic); the usage errors exit 2 before any
# docker call. Run as root in pinned Linux containers by ../diag.test.sh. SRC is a read-only copy of
# deploy/compose, T this test directory; docker is the test double container/fake-bin/docker.
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

WORK=/tmp/diag-compose
cd /
rm -rf "$WORK"
cp -R "$SRC" "$WORK"
rm -rf "$WORK/.env" "$WORK/secrets" "$WORK/tls" "$WORK/compose.override.yaml" "$WORK/test"
cd "$WORK" || exit 1
sh ./argos-env-init.sh --ram-mb 16384 --public-url http://ui.example.test:8080 >/dev/null 2>&1 \
  || { echo "FAIL  argos-env-init.sh did not prepare the directory"; exit 1; }
mkdir -p tls
rand() { head -c 24 /dev/urandom | od -An -v -tx1 | tr -d ' \n' | cut -c1-"$1"; }
printf 'certificate-%s\n' "$(rand 20)" >tls/server.crt
printf 'tls-key-%s\n' "$(rand 20)" >tls/server.key

# Sentinels generated now (never literals in the tree), planted in .env and in the container logs.
S1=$(rand 32) S2=$(rand 32) S3=$(rand 43) S4=$(rand 32) S5=$(rand 32)
S6=$(rand 48)$(rand 16) # 64 hexadecimal digits
S7=$(rand 32) S8=$(rand 32) S9=$(rand 43) S10=$(rand 48)$(rand 16) S11=$(rand 32)
cat >>.env <<EOF
ARGOS_SMTP_PASSWORD=$S1
ARGOS_WEBHOOK_URL=https://hook-user:$S2@hooks.example.test/path
ARGOS_NOTE=argos_$S3
ARGOS_SIGNING_KEY="$S4"
export ARGOS_API_TOKEN='$S5'
ARGOS_DIGEST_NOTE=$S6
EOF
mkdir -p /tmp/fake-logs
cat >/tmp/fake-logs/clickhouse.log <<EOF
clickhouse-1  | 2026.11.26 01:00:00 <Information> Application: Ready for connections.
clickhouse-1  | <Error> auth failed for password=$S7 from http://u:$S8@10.0.0.1:8123/
clickhouse-1  | <Debug> header argos_$S9 and hash $S10
EOF
cat >/tmp/fake-logs/postgres.log <<EOF
postgres-1  | LOG:  database system is ready to accept connections
postgres-1  | ERROR:  token: $S11 rejected
EOF
printf '[{"Service":"server","State":"running","Health":"healthy","Publishers":[]}]\n' >/tmp/ps.json
FAKE_LOG_DIR=/tmp/fake-logs
FAKE_PS_JSON=/tmp/ps.json
export FAKE_LOG_DIR FAKE_PS_JSON

# planted_values: every value the bundle must not contain, one per line.
planted_values() {
  printf '%s\n' "$S1" "$S2" "$S3" "$S4" "$S5" "$S6" "$S7" "$S8" "$S9" "$S10" "$S11"
  for f in secrets/*; do
    sed -e 's/^[A-Z0-9_]*=//' -e '/^#/d' -e '/^$/d' "$f"
    echo
  done | sed '/^$/d'
  head -n 1 tls/server.crt
  head -n 1 tls/server.key
}

no_docker_call() { [ ! -s "$LOG" ] || { echo "docker was called:"; cat "$LOG"; return 1; }; }
# diag <arguments...>: diag-bundle.sh from /tmp/caller under umask 022, a fresh docker call log.
mkdir -p /tmp/caller /tmp/out
diag() {
  : >"$LOG"
  (cd /tmp/caller && umask 022 && sh "$WORK/diag-bundle.sh" "$@")
}
bundle_file() { find "$1" -maxdepth 1 -name 'argos-diag-compose-*.tar.gz' | head -n 1; }
extract() { # extract <archive>: into /tmp/x (outer) and /tmp/x/inner (the server's bundle)
  rm -rf /tmp/x
  mkdir -p /tmp/x/inner
  tar -xzf "$1" -C /tmp/x || return 1
  inner=$(find /tmp/x -path '*/server/argos-diag-*.tar.gz' | head -n 1)
  [ -n "$inner" ] || return 0
  tar -xzf "$inner" -C /tmp/x/inner
}

# --- usage errors: exit 2 before any docker call -----------------------------------------------
usage_refused() { # usage_refused <description> <arguments...>
  udesc=$1
  shift
  expect_exit 2 "diag usage: $udesc: exit 2" diag "$@"
  check "diag usage: $udesc: no docker call" no_docker_call
}
usage_refused "an unknown option" --frobnicate
usage_refused "--since 9x" --since 9x
usage_refused "--since 0h" --since 0h
usage_refused "--since 169h (above 7 days)" --since 169h
usage_refused "--since 8d" --since 8d
usage_refused "--out without a value" --out
usage_refused "--out naming no directory" --out /tmp/does-not-exist
nothing_written() { [ -z "$(ls -A /tmp/out)" ] && [ -z "$(ls -A /tmp/caller)" ]; }
check "diag usage: nothing is written after the usage errors" nothing_written
expect_exit 2 "diag: without docker exits 2" env PATH=/usr/bin:/bin sh "$WORK/diag-bundle.sh" --out /tmp/out
output_has "docker is not installed" "diag: the missing docker is named"
expect_exit 2 "diag: Docker daemon down exits 2" env FAKE_DOCKER_DOWN=1 sh "$WORK/diag-bundle.sh" --out /tmp/out

# --- the bundle of a running installation ---------------------------------------------------------
expect_exit 0 "T-SEC-06i: diag-bundle.sh --out /tmp/out --since 7d: exit 0" diag --out /tmp/out --since 7d
output_has "Diagnostic bundle: /tmp/out/argos-diag-compose-" "diag: the output names the bundle"
output_has "server_bundle: ok" "diag: the output lists the sections"
B=$(bundle_file /tmp/out)
one_bundle() {
  [ -n "$B" ] || { ls -la /tmp/out; return 1; }
  [ "$(find /tmp/out -type f | wc -l | tr -d ' ')" = 1 ] || { ls -la /tmp/out; return 1; }
  case $B in */argos-diag-compose-*-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]Z.tar.gz) ;; *) echo "name $B"; return 1 ;; esac
}
check "diag: exactly one argos-diag-compose-<host>-<UTC time>.tar.gz in --out, no temporary file left" one_bundle
mode_0600() { [ "$(mode_of "$B")" = "-rw-------" ] || { mode_of "$B"; return 1; }; }
check "T-SEC-84d: the bundle is 0600 although the caller's umask is 022" mode_0600
check "diag: the bundle is a readable tar.gz" extract "$B"
D=$(find /tmp/x -maxdepth 1 -type d -name 'argos-diag-compose-*' | head -n 1)
layout_ok() {
  for f in manifest.json compose/ps.json compose/images.txt compose/files.txt compose/env.masked logs/clickhouse.log \
    logs/postgres.log system/versions.txt system/df-clickhouse.txt; do
    [ -s "$D/$f" ] || { echo "missing $f"; find /tmp/x; return 1; }
  done
  [ -s "$D/server/argos-diag-fake-20261126T000000Z.tar.gz" ] || { echo "no server bundle"; find /tmp/x; return 1; }
  [ -s /tmp/x/inner/manifest.json ] || { echo "the server bundle does not extract"; return 1; }
}
check "T-SEC-06i: the bundle holds the server's argos-admin bundle, compose ps, images, compose files, masked .env and logs, versions and df" layout_ok
admin_in_server() {
  grep -q 'compose .*exec -T -e JAVA_TOOL_OPTIONS= server java -Xmx256m .*-cp @/opt/argos/app/jib-classpath-file io.argos.server.ServerApplication admin bundle --since 7d --out /data/tmp --json' "$LOG" \
    || { cat "$LOG"; return 1; }
  grep -q 'exec -T -e JAVA_TOOL_OPTIONS= server cat /data/tmp/argos-diag-fake-20261126T000000Z.tar.gz' "$LOG" || { echo "not copied"; return 1; }
  grep -q 'exec -T -e JAVA_TOOL_OPTIONS= server rm -f /data/tmp/argos-diag-fake-20261126T000000Z.tar.gz' "$LOG" || { echo "not deleted in the container"; return 1; }
}
check "diag: argos-admin bundle runs in the running server container (JAVA_TOOL_OPTIONS cleared), its file is copied out and deleted there" admin_in_server
no_config() {
  if grep -E '(^| )config( |$)' "$LOG"; then echo "docker compose config was called"; return 1; fi
  if grep -E 'logs .*server$' "$LOG"; then echo "the server container log was read"; return 1; fi
}
check "T-SEC-06i: no docker compose config and no server container log (setup token) is read" no_config
manifest_ok() {
  grep -q '"kind": "argos-diag-compose"' "$D/manifest.json" || return 1
  grep -q '"since": "7d"' "$D/manifest.json" || return 1
  grep -q '"server_bundle_from": "exec"' "$D/manifest.json" || return 1
  grep -q '{"name": "server_bundle", "ok": true, "detail": "argos-diag-fake-20261126T000000Z.tar.gz (sections not collected by the server: storage)"}' "$D/manifest.json" \
    || { cat "$D/manifest.json"; return 1; }
  [ "$(grep -c '"ok": true' "$D/manifest.json")" -eq 9 ] || { cat "$D/manifest.json"; return 1; }
}
check "diag: manifest.json lists the 9 sections as collected and names the sections the server could not collect" manifest_ok
files_ok() {
  want=$(sha256sum compose.yaml | awk '{ print $1 }')
  grep -qx "$want  compose.yaml" "$D/compose/files.txt" || { cat "$D/compose/files.txt"; return 1; }
  grep -q '^docker compose options: -f compose.yaml' "$D/compose/files.txt"
}
check "diag: compose/files.txt names the compose files with their SHA-256" files_ok
images_ok() {
  if ! grep -q '^/argos-server-1 argos-server:0.0.0-SNAPSHOT sha256:old$' "$D/compose/images.txt" \
    || ! grep -q 'RepoDigests' "$D/compose/images.txt"; then
    cat "$D/compose/images.txt"
    return 1
  fi
}
check "diag: compose/images.txt names each container's image, id and RepoDigests" images_ok
ps_ok() { cmp -s /tmp/ps.json "$D/compose/ps.json"; }
check "diag: compose/ps.json is docker compose ps --all --format json" ps_ok
env_masked_ok() {
  for line in 'ARGOS_SMTP_PASSWORD=****' 'ARGOS_WEBHOOK_URL=https://****@hooks.example.test/path' 'ARGOS_NOTE=argos_****' \
    'ARGOS_SIGNING_KEY=****' "export ARGOS_API_TOKEN=****" 'ARGOS_DIGEST_NOTE=****' 'ARGOS_WEB_PUBLIC_URL=http://ui.example.test:8080' \
    'ARGOS_UNATTENDED=false'; do
    grep -qxF "$line" "$D/compose/env.masked" || { echo "missing: $line"; cat "$D/compose/env.masked"; return 1; }
  done
}
check "T-SEC-06i: .env is masked (PASSWORD/TOKEN/SECRET/KEY names, URL user information, argos_ tokens, 64 hex digits); other settings stay readable" env_masked_ok
logs_masked_ok() {
  grep -qF 'auth failed for password=**** from http://****@10.0.0.1:8123/' "$D/logs/clickhouse.log" || { cat "$D/logs/clickhouse.log"; return 1; }
  grep -qF 'header argos_**** and hash ****' "$D/logs/clickhouse.log" || { cat "$D/logs/clickhouse.log"; return 1; }
  grep -qF 'token: **** rejected' "$D/logs/postgres.log" || { cat "$D/logs/postgres.log"; return 1; }
  grep -qF 'Ready for connections.' "$D/logs/clickhouse.log"
}
check "T-SEC-06i: the ClickHouse and PostgreSQL container logs are masked the same way and otherwise kept" logs_masked_ok
grep_zero() { # grep_zero <directory>: none of the planted values, secrets/* values or tls/ content
  planted_values >/tmp/patterns
  [ "$(wc -l </tmp/patterns | tr -d ' ')" -ge 20 ] || { echo "too few patterns"; return 1; }
  if grep -rlF -f /tmp/patterns "$1"; then echo "a planted value is in the bundle"; return 1; fi
  if grep -rlE 'argos_[A-Za-z0-9_-]{40,}' "$1"; then echo "an argos_ token is in the bundle"; return 1; fi
}
check "T-SEC-06i: grep over the extracted bundle (outer and inner) finds 0 planted values (.env sentinels, log sentinels, every secrets/* value, tls/ content) and no argos_ token" grep_zero /tmp/x
no_secret_names() { ! tar -tzf "$B" | grep -E '(^|/)(secrets|tls)(/|$)|master_key|clickhouse\.env|server\.key'; }
check "T-SEC-06i: no file of secrets/ or tls/ is in the bundle" no_secret_names

# A relative --out is the caller's directory.
mkdir -p /tmp/caller/rel
expect_exit 0 "diag: a relative --out: exit 0" diag --out rel
relative_ok() { [ -n "$(bundle_file /tmp/caller/rel)" ]; }
check "diag: a relative --out is relative to the caller's directory" relative_ok
expect_exit 0 "diag: without --out: exit 0" diag
default_ok() { [ -n "$(bundle_file /tmp/caller)" ]; }
check "diag: without --out the bundle goes to the current directory" default_ok
rm -rf /tmp/caller/rel /tmp/caller/argos-diag-compose-*

# argos-compose.sh diag runs the same script with the same arguments.
expect_exit 0 "diag: ./argos-compose.sh diag --out /tmp/out2: exit 0" sh -c 'mkdir -p /tmp/out2 && cd /tmp/caller && sh '"$WORK"'/argos-compose.sh diag --out /tmp/out2'
via_compose_ok() { [ -n "$(bundle_file /tmp/out2)" ] && [ "$(mode_of "$(bundle_file /tmp/out2)")" = "-rw-------" ]; }
check "diag: ./argos-compose.sh diag writes the 0600 bundle" via_compose_ok

# --- the server container is not running: argos-admin in a new container (compose run) -------------
rm -rf /tmp/out && mkdir -p /tmp/out
not_running() { (FAKE_PS_EMPTY=1 && export FAKE_PS_EMPTY && diag --out /tmp/out); }
expect_exit 0 "diag: the server container not running: exit 0" not_running
output_has "the server container is not running" "diag: the stopped server is named"
run_used() {
  grep -q 'run --rm --no-deps -T -e JAVA_TOOL_OPTIONS= --entrypoint java server -Xmx256m .* admin bundle --since 24h --out /data/tmp --json' "$LOG" || { cat "$LOG"; return 1; }
  grep -q 'run --rm --no-deps -T -e JAVA_TOOL_OPTIONS= --entrypoint cat server /data/tmp/argos-diag-fake' "$LOG" || return 1
  grep -q 'run --rm --no-deps -T -e JAVA_TOOL_OPTIONS= --entrypoint rm server -f /data/tmp/argos-diag-fake' "$LOG"
}
check "diag: without a running server argos-admin bundle, cat and rm run in new server containers without dependencies" run_used
extract "$(bundle_file /tmp/out)" >/dev/null 2>&1
from_run() { grep -q '"server_bundle_from": "run"' /tmp/x/argos-diag-compose-*/manifest.json; }
check "diag: the manifest says the server bundle came from a new container" from_run

# --- failed sections: written all the same (exit 0), the manifest says which ----------------------
rm -rf /tmp/out && mkdir -p /tmp/out
bundle_fails() { (FAKE_BUNDLE_EXIT=1 FAKE_DF_EXIT=1 && export FAKE_BUNDLE_EXIT FAKE_DF_EXIT && diag --out /tmp/out); }
expect_exit 0 "diag: argos-admin bundle and df fail: exit 0 (written)" bundle_fails
output_has "server_bundle: FAILED" "diag: the failed server bundle is listed"
output_has "disk: FAILED" "diag: the failed df is listed"
extract "$(bundle_file /tmp/out)" >/dev/null 2>&1
failed_listed() {
  m=$(ls /tmp/x/argos-diag-compose-*/manifest.json)
  if ! grep -q '{"name": "server_bundle", "ok": false, "detail": "argos-admin bundle exit code 1' "$m" \
    || ! grep -q '{"name": "disk", "ok": false' "$m"; then
    cat "$m"
    return 1
  fi
  [ -z "$(find /tmp/x -path '*/server/*' -type f)" ] || { echo "a server file"; return 1; }
}
check "diag: manifest.json marks server_bundle and disk as not collected" failed_listed
no_cat() { ! grep -q ' cat /data/tmp' "$LOG"; }
check "diag: nothing is copied from the container after a failed argos-admin bundle" no_cat
rm -rf /tmp/out && mkdir -p /tmp/out
odd_file() { (FAKE_BUNDLE_FILE=/data/tmp/../../run/secrets/master_key && export FAKE_BUNDLE_FILE && diag --out /tmp/out); }
expect_exit 0 "diag: argos-admin names an unexpected file: exit 0" odd_file
output_has "server_bundle: FAILED" "diag: the unexpected file makes the server bundle section fail"
check "diag: a file outside /data/tmp/argos-diag-*.tar.gz is never read from the container" no_cat

# --- the archive cannot be written: exit 1 ----------------------------------------------------------
expect_exit 1 "diag: a read-only --out: exit 1" diag --out "$SRC"
output_has "cannot write" "diag: the failed write is named"

finish
