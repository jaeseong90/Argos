#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Backup round trip and recovery of the Docker Compose installation on a real stack (WO-43; docs/17
# sections 4.4, 8, 10 and 14; docs/03 section 3.4; SEC-52, SEC-84). Run by
# ./gradlew :deploy:backupRoundTrip, which builds the server image, the agent jar and the shop-boot3
# sample app first. Development and CI only, never shipped; needs Docker and about 6 GiB of memory.
#
# One fresh copy of deploy/ (project argos-wo43-backup): argos-env-init.sh --unattended, a throwaway
# gRPC certificate, compose.override.yaml with small memory limits, the test label and the
# ClickHouse backup target /backups on a tmpfs Docker volume (another device than the ClickHouse
# data; ARGOS_CH_BACKUP_DIR is set so that argos-compose.sh adds compose.chbackup.yaml, whose bind
# mount the override replaces), argos-compose.sh up, the administrator's first password change,
# token create for app A and a real agent (shop-boot3) with it. Then, one result line each:
#   [wo43-roundtrip] T-NFR-20a  backup.sh --clickhouse; token create for app B and more requests;
#                               restore.sh --clickhouse --yes: app B and its token are gone, the
#                               token of app A works (the agent reconnects, its new requests are
#                               stored, --check passes), PostgreSQL rows and ClickHouse counts equal
#                               the backup point, the administrator signs in.
#   [wo43-wrongkey] T-SEC-52e   restore.sh --master-key with another valid key: exit 1, the live
#                               database, the server container and its start time unchanged, no
#                               staging database, the server healthy.
#   [wo43-pitr]     T-NFR-20b   backup.sh without ClickHouse; app C with its own agent and data,
#                               more requests of app A; restore.sh without ClickHouse: a
#                               'point-in-time mismatch' warning, exit 0, the server healthy, the
#                               agent of app A reconnects and its new requests are stored, the query
#                               API over the whole window answers without 5xx (also for the app id
#                               of C that PostgreSQL no longer knows), the token of app C is refused.
#   [wo43-kill]     T-ACC-13 (b) while agent requests run, docker kill -s KILL of the server, then
#                               argos-compose.sh up: count(xlog) = requests sent, no duplicate tx_id.
#   [wo43-downup]   T-ACC-13 (b) the same with argos-compose.sh down (volumes kept) and up.
#   [wo43-upgrade]  T-NFR-11a   a relabelled copy of the server image (docker build FROM it, no
#                               network, higher org.opencontainers.image.version): argos-compose.sh
#                               upgrade --image --yes exit 0, pre-upgrade backup set present, data
#                               kept, duration printed; then app D and upgrade --rollback --yes: the
#                               previous image runs, app D is gone.
#   [wo43-chsame]   T-NFR-20c   /backups on a plain named volume (the device of the ClickHouse data):
#                               backup.sh --clickhouse exit 1, no set and no ClickHouse backup written.
# At the end: no agent token and no administrator password in any output of the scripts, file of
# this run or container log (T-SEC-73), then down --volumes and the removal of every container,
# volume, network and image this run created (also on failure).
#
# File modes are not asserted here: on Docker Desktop the bind mounts have no POSIX modes (ADR-0010
# C-243 (1)); deploy/compose/test/backup.test.sh covers T-SEC-84a..c in Linux containers.
#
# Environment (set by the Gradle task):
#   ARGOS_RT_AGENT_JAR    agent jar (./gradlew :agent:agent-dist:agentJar)
#   ARGOS_RT_SAMPLE_JAR   shop-boot3 jar (./gradlew :sample-apps:shop-boot3:sampleAppJar)
#   ARGOS_RT_JAVA         java executable for the sample app and --check (JDK 17 or later)
#   ARGOS_RT_APP_PORT     host port of the sample app of app A (default 18094; app C uses +1)
#   ARGOS_RT_KEEP_ON_FAILURE=1  keep the stack and the work directory after a failure
#   ARGOS_LOCK_WAIT_SECONDS bounded wait for the server-it lock (default 1800)
# The whole run holds the working tree's server-it lock (test/lock/argos-lock.sh run, ADR-0010
# C-235 (8)): the script re-executes itself under it, or uses the lock of an enclosing
# `argos-lock.sh run server-it` (around ./gradlew :deploy:backupRoundTrip, whose image build
# rebuilds the server boot jar before this script starts).
# Nothing secret is printed: tokens appear as argos_****.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [[ "${ARGOS_RT_UNDER_LOCK:-}" != 1 ]]; then
  ARGOS_RT_UNDER_LOCK=1 exec bash "$here/../../../test/lock/argos-lock.sh" run server-it \
    --purpose "deploy/compose/test/backup-roundtrip.sh (:deploy:backupRoundTrip)" -- bash "$here/backup-roundtrip.sh" "$@"
fi
# shellcheck source=deploy/compose/test/lib.sh
source "$here/lib.sh"

PROJECT=argos-wo43-backup
LABEL_KEY=io.argos.wo43
APP_PORT=${ARGOS_RT_APP_PORT:-18094}
APP_PORT_C=$((APP_PORT + 1))
AGENT_JAR=${ARGOS_RT_AGENT_JAR:-}
SAMPLE_JAR=${ARGOS_RT_SAMPLE_JAR:-}
JAVA=${ARGOS_RT_JAVA:-java}
# shellcheck disable=SC2016 # the literal ${ARGOS_SERVER_IMAGE:-...} of compose.yaml
SERVER_IMAGE=argos-server:$(sed -n 's/^ *image: \${ARGOS_SERVER_IMAGE:-argos-server:\([^}]*\)}.*$/\1/p' "$COMPOSE_DIR/compose.yaml" | head -n 1)
# Small stack for a shared machine (every container below 5 GiB).
SERVER_MEM=2048m
SERVER_HEAP=1024m
CH_MEM_MIB=3072
PG_MEM=1024m
# Requests of the scenarios.
N_FIRST=30
N_CHANGE=15
N_AFTER=10
N_TRAFFIC=120

docker info >/dev/null 2>&1 || { echo "backup-roundtrip: Docker is not available" >&2; exit 2; }
for f in "$AGENT_JAR" "$SAMPLE_JAR"; do
  [[ -n "$f" && -f "$f" ]] || { echo "backup-roundtrip: missing jar '$f' (run through ./gradlew :deploy:backupRoundTrip)" >&2; exit 2; }
done
docker image inspect "$SERVER_IMAGE" >/dev/null 2>&1 \
  || { echo "backup-roundtrip: the server image $SERVER_IMAGE is missing (./gradlew :server:jibDockerBuild)" >&2; exit 2; }

work=$(mktemp -d "${TMPDIR:-/tmp}/argos-wo43-backup.XXXXXX")
chmod 0700 "$work"
mkdir -p "$work/out"
dir=
upgrade_image=
pids=()
traffic_pid=
tokens=()
passwords=()
started=$(date +%s)
timings=()
results=()

log() { printf '[backup-roundtrip %s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
# lib.sh sets MSYS_NO_PATHCONV for docker under Git Bash; curl needs the usual conversion of its file
# arguments there (no effect elsewhere).
curl() { env -u MSYS_NO_PATHCONV curl "$@"; }
fail() {
  printf '[backup-roundtrip] FAIL %s\n' "$*" >&2
  exit 1
}
redact() { sed -E 's/argos_[A-Za-z0-9_-]{43}/argos_****/g'; }
now() { date +%s; }
timing() { timings+=("$1: $2 s"); log "$1 took $2 s"; }
result() { results+=("$*"); printf '%s\n' "$*"; }
show() { redact <"$1" | sed 's/^/  | /'; }

cleanup() {
  local rc=$?
  set +e
  if [[ -n "$traffic_pid" ]]; then
    kill "$traffic_pid" 2>/dev/null
    wait "$traffic_pid" 2>/dev/null
  fi
  for p in "${pids[@]}"; do
    kill "$p" 2>/dev/null
    wait "$p" 2>/dev/null
  done
  # The agent token files go in every case.
  rm -f "$work"/agent-*/token
  if [[ $rc -ne 0 && "${ARGOS_RT_KEEP_ON_FAILURE:-0}" == 1 ]]; then
    log "FAILED (exit $rc); ARGOS_RT_KEEP_ON_FAILURE=1: the stack and $work are kept for diagnosis."
    log "Remove them with: cd $dir && COMPOSE_PROJECT_NAME=$PROJECT ./argos-compose.sh down --volumes; rm -rf $work"
    return
  fi
  if [[ -n "$dir" && -d "$dir" ]]; then
    if [[ $rc -ne 0 ]]; then
      log "logs of the failed stack (last 60 lines per service; tokens redacted):"
      (cd "$dir" && COMPOSE_PROJECT_NAME=$PROJECT ./argos-compose.sh compose ps --all 2>&1
        for svc in postgres clickhouse server; do
          COMPOSE_PROJECT_NAME=$PROJECT ./argos-compose.sh compose logs --no-color --tail 60 "$svc" 2>&1
        done) | redact | cut -c1-600
    fi
    (cd "$dir" && COMPOSE_PROJECT_NAME=$PROJECT ./argos-compose.sh down --volumes >/dev/null 2>&1)
  fi
  docker ps -aq --filter "label=com.docker.compose.project=$PROJECT" | xargs -r docker rm -f >/dev/null 2>&1
  docker ps -aq --filter "label=$LABEL_KEY=backup-roundtrip" | xargs -r docker rm -f >/dev/null 2>&1
  docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT" | xargs -r docker volume rm >/dev/null 2>&1
  docker network ls -q --filter "label=com.docker.compose.project=$PROJECT" | xargs -r docker network rm >/dev/null 2>&1
  if [[ -n "$upgrade_image" ]]; then
    docker image rm -f "$upgrade_image" >/dev/null 2>&1
  fi
  rm -rf "$work"
  if [[ $rc -eq 0 ]]; then
    log "cleaned up (containers, volumes, networks, the relabelled image, generated secrets)"
  else
    log "FAILED (exit $rc); cleaned up"
  fi
}
trap cleanup EXIT

# ---------------------------------------------------------------------------------------------
# helpers on the running stack

dc() { ./argos-compose.sh compose "$@"; }
env_of() { sed -n "s/^$1=//p" .env | tail -n 1; }
psql_owner() { dc exec -T postgres psql -X -q -A -t -U argos -d "${2:-argos}" -c "$1" | tr -d '\r'; }
# ch_q <query>: clickhouse-client as argos_reader in the ClickHouse container (the password goes in
# through standard input, never into an argument), tab-separated result.
ch_q() {
  # shellcheck disable=SC2016 # expanded by the container's shell
  dc exec -T clickhouse sh -c 'IFS= read -r CLICKHOUSE_PASSWORD; [ -n "$CLICKHOUSE_PASSWORD" ] && export CLICKHOUSE_PASSWORD && exec clickhouse-client --user argos_reader -q "$1"' ch-q "$1" \
    <secrets/ch_reader_password | tr -d '\r'
}
server_cid() { dc ps -q server 2>/dev/null | tr -d '\r' | head -n 1; }
server_health() {
  local cid
  cid=$(server_cid)
  [[ -n "$cid" ]] || { echo missing; return; }
  docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$cid" 2>/dev/null | tr -d '\r'
}
await_healthy() { # await_healthy <seconds>
  local i
  for ((i = 0; i < $1; i += 3)); do
    [[ "$(server_health)" == healthy ]] && return 0
    sleep 3
  done
  fail "the server is not healthy after $1 s ($(server_health))"
}

# same_pg <expected state> <message>: pg_state equals the expected state; on a difference both are
# printed with the token prefixes masked.
same_pg() {
  local now_state
  now_state=$(pg_state)
  [[ "$now_state" == "$1" ]] && return 0
  printf '  expected: %s\n  actual:   %s\n' "$1" "$now_state" | sed -E 's/argos_[A-Za-z0-9_-]{8}/argos_********/g' >&2
  fail "$2"
}

# The PostgreSQL state the backups are compared by: apps, agent tokens (prefix and status), users
# (and whether they must change the password), instance, rule and channel counts. Token prefixes
# are not printed.
pg_state() {
  psql_owner "SELECT concat_ws(' | ',
      (SELECT coalesce(string_agg(name || '/' || env, ',' ORDER BY id), '') FROM argos.apps),
      (SELECT coalesce(string_agg(token_prefix || ':' || status, ',' ORDER BY token_prefix), '') FROM argos.agent_tokens),
      (SELECT coalesce(string_agg(username || ':' || must_change_password, ',' ORDER BY username), '') FROM argos.users),
      (SELECT count(*) FROM argos.instances),
      (SELECT count(*) FROM argos.alert_rules),
      (SELECT count(*) FROM argos.notification_channels))"
}
# ClickHouse counts: all of xlog, its distinct tx_id and the transactions of app_stat_1m.
ch_state() { ch_q "SELECT count(), uniqExact(tx_id), (SELECT sum(count) FROM argos.app_stat_1m) FROM argos.xlog FORMAT TSV" | tr '\t' ' '; }
app_count() { # app_count <app id>: "count distinct" of xlog
  ch_q "SELECT count(), uniqExact(tx_id) FROM argos.xlog WHERE app_id = $1 FORMAT TSV" | tr '\t' ' '
}
# await_count <app id> <expected> <seconds>: count(xlog) and its distinct tx_id equal expected.
await_count() {
  local i got=
  for ((i = 0; i < $3; i += 2)); do
    got=$(app_count "$1" 2>/dev/null || true)
    [[ "$got" == "$2 $2" ]] && return 0
    sleep 2
  done
  fail "app $1: count(xlog), uniqExact(tx_id) = '${got:-none}', expected $2 (after $3 s)"
}
# await_stable <app id> <seconds>: the count of the app stops changing (three equal reads 3 s
# apart, at least one row); prints it.
await_stable() {
  local i last=-1 same=0 c
  for ((i = 0; i < $2; i += 3)); do
    c=$(app_count "$1" 2>/dev/null | awk '{ print $1 }' || true)
    if [[ "$c" =~ ^[0-9]+$ && "$c" -gt 0 && "$c" == "$last" ]]; then
      same=$((same + 1))
      [[ $same -ge 2 ]] && { printf '%s' "$c"; return 0; }
    else
      same=0
    fi
    last=$c
    sleep 3
  done
  fail "app $1: count(xlog) did not settle within $2 s (last '$last')"
}

# token_create <app> <env> <name>: issues a token, keeps it in memory and in $work/.tok-<name>
# (umask 077), sets APP_ID to its app id. The output is kept redacted. Not to be called in a
# command substitution (the token list of this shell would not see the token).
APP_ID=
token_create() {
  local out rc=0 token
  out=$(./argos-compose.sh token create --app "$1" --env "$2" 2>&1) || rc=$?
  printf '%s\n' "$out" | redact >"$work/out/token-create-$3.txt"
  [[ $rc -eq 0 ]] || { cat "$work/out/token-create-$3.txt" >&2; fail "token create for $1 failed (exit $rc)"; }
  token=$(grep -oE 'argos_[A-Za-z0-9_-]{43}' <<<"$out" | head -n 1 || true)
  out=
  [[ ${#token} -eq 49 ]] || fail "token create for $1 printed no token"
  tokens+=("$token")
  (umask 077 && printf '%s\n' "$token" >"$work/.tok-$3")
  token=
  APP_ID=$(sed -n 's/.*(app id \([0-9]*\).*/\1/p' "$work/out/token-create-$3.txt" | head -n 1)
  [[ -n "$APP_ID" ]] || fail "token create for $1 printed no app id"
}

# agent_config <name> <app> <env>: an agent configuration with the token file of <name>.
agent_config() {
  local d=$work/agent-$1
  mkdir -p "$d/logs"
  (umask 077 && cp "$work/.tok-$1" "$d/token")
  cat >"$d/argos-agent.yml" <<EOF
server:
  address: localhost:9100
  tls:
    ca-file: $(host_path "$dir/tls/server.crt" | sed 's|\\|/|g')
  token-file: $(host_path "$d/token" | sed 's|\\|/|g')
app:
  name: $2
  env: $3
log:
  dir: $(host_path "$d/logs" | sed 's|\\|/|g')
EOF
}
# start_app <name> <port>: shop-boot3 with the agent of agent_config <name>; waits for HTTP 200.
start_app() {
  local d=$work/agent-$1 i code=000 pid
  env -u ARGOS_TOKEN -u ARGOS_SERVER_TOKEN "$JAVA" -Xmx256m "-javaagent:$(host_path "$AGENT_JAR")=config=$(host_path "$d/argos-agent.yml")" \
    -jar "$(host_path "$SAMPLE_JAR")" --server.address=127.0.0.1 --server.port="$2" >"$d/app.log" 2>&1 &
  pid=$!
  pids+=("$pid")
  printf '%s' "$pid" >"$d/pid"
  for i in $(seq 1 120); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$2/api/runtime" 2>/dev/null || true)
    [[ "$code" == 200 ]] && break
    kill -0 "$pid" 2>/dev/null || { tail -n 30 "$d/app.log" | redact >&2; fail "the sample app $1 stopped"; }
    sleep 1
  done
  [[ "$code" == 200 ]] || fail "the sample app $1 did not start within 120 s"
  for i in $(seq 1 60); do
    grep -rqF 'collector session established' "$d/logs" 2>/dev/null && return 0
    sleep 1
  done
  tail -n 40 "$d/logs/"*.log 2>/dev/null | redact >&2
  fail "the agent of $1 reports no 'collector session established'"
}
stop_app() { # stop_app <name>
  local pid
  pid=$(cat "$work/agent-$1/pid")
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  rm -f "$work/agent-$1/token"
}
# drive <port> <n>: n requests, every one must answer 200.
drive() {
  local i code
  for i in $(seq 1 "$2"); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "http://127.0.0.1:$1/api/orders/$(((i % 5) + 1))" || true)
    [[ "$code" == 200 ]] || fail "request $i to the sample app on $1 answered $code"
  done
}
# agent_check <name> <app> <env>: java -jar agent.jar --check with the token of <name>; prints the
# exit code, keeps the (redacted) output in $work/out/check-<name>.txt.
agent_check() {
  local d=$work/check-$1 rc=0
  mkdir -p "$d/logs"
  (umask 077 && cp "$work/.tok-$1" "$d/token")
  cat >"$d/argos-agent.yml" <<EOF
server:
  address: localhost:9100
  tls:
    ca-file: $(host_path "$dir/tls/server.crt" | sed 's|\\|/|g')
  token-file: $(host_path "$d/token" | sed 's|\\|/|g')
app:
  name: $2
  env: $3
log:
  dir: $(host_path "$d/logs" | sed 's|\\|/|g')
EOF
  env -u ARGOS_TOKEN -u ARGOS_SERVER_TOKEN "$JAVA" -jar "$(host_path "$AGENT_JAR")" --check --config "$(host_path "$d/argos-agent.yml")" \
    >"$work/out/check-$1.raw" 2>&1 || rc=$?
  redact <"$work/out/check-$1.raw" >"$work/out/check-$1.txt"
  rm -f "$work/out/check-$1.raw" "$d/token"
  printf '%s' "$rc"
}

# expect_refused <name> <app>: --check with the token of <name> (issued after the restored backup)
# exits 1 with a failed hello check.
expect_refused() {
  local rc
  rc=$(agent_check "$1" "$2" rt)
  if [[ "$rc" != 1 ]] || ! grep -qE '^ *[0-9]+ +hello +FAIL' "$work/out/check-$1.txt"; then
    show "$work/out/check-$1.txt" >&2
    fail "--check with the token of $2 (issued after the backup): exit $rc, expected 1 with a failed hello"
  fi
}

# --- web sessions (the query API)--------------------------------------------------------------------
BASE=http://127.0.0.1:8080
ORIGIN=http://localhost:8080
json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
# login <password file>: signs the administrator in; writes $work/.session.cfg (cookies and the CSRF
# header) and prints the HTTP status; the body is in $work/.login.body.
login() {
  local user pw code cookies csrf
  user=$(env_of ARGOS_ADMIN_USERNAME)
  [[ -n "$user" ]] || user="admin"
  IFS= read -r pw <"$1" || true
  code=$(printf '{"username":"%s","password":"%s"}' "$(json_escape "$user")" "$(json_escape "$pw")" \
    | curl -s -o "$work/.login.body" -D "$work/.login.headers" -w '%{http_code}' -H 'Content-Type: application/json' \
      -H "Origin: $ORIGIN" --data-binary @- "$BASE/api/v1/auth/login" || true)
  pw=
  cookies=$(sed -n 's/^[Ss][Ee][Tt]-[Cc][Oo][Oo][Kk][Ii][Ee]: *\([^;]*\).*$/\1/p' "$work/.login.headers" | tr -d '\r' | paste -sd ';' -)
  csrf=$(sed -n 's/^[Ss][Ee][Tt]-[Cc][Oo][Oo][Kk][Ii][Ee]: *argos_csrf=\([^;]*\).*$/\1/p' "$work/.login.headers" | tr -d '\r' | head -n 1)
  (umask 077 && printf 'header = "Cookie: %s"\nheader = "X-Argos-CSRF: %s"\nheader = "Origin: %s"\n' "$cookies" "$csrf" "$ORIGIN" >"$work/.session.cfg")
  rm -f "$work/.login.headers"
  printf '%s' "$code"
}
api_get() { # api_get <path>: prints the HTTP status; the body is in $work/.api.body
  curl -s -o "$work/.api.body" -w '%{http_code}' --max-time 60 --config "$work/.session.cfg" -H 'Accept: application/json' "$BASE$1" || echo 000
}

# ---------------------------------------------------------------------------------------------
# preparation

# write_override <backups volume>: limits, labels, /backups on the named volume of this run.
write_override() {
  local ch_bytes=$((CH_MEM_MIB * 1048576 * 9 / 10)) opts
  case $1 in
    tmpfs) opts=$'    driver_opts:\n      type: tmpfs\n      device: tmpfs\n      o: "size=536870912"' ;;
    plain) opts='    {}' ;;
  esac
  cat >compose.override.yaml <<EOF
# Written by deploy/compose/test/backup-roundtrip.sh: test limits, labels and the ClickHouse backup
# target of this run (a $1 Docker volume in place of the ARGOS_CH_BACKUP_DIR bind mount).
services:
  server:
    labels:
      $LABEL_KEY: backup-roundtrip
    mem_limit: $SERVER_MEM
    environment:
      JAVA_TOOL_OPTIONS: "-Xms$SERVER_HEAP -Xmx$SERVER_HEAP"
  clickhouse:
    labels:
      $LABEL_KEY: backup-roundtrip
    mem_limit: ${CH_MEM_MIB}m
    environment:
      ARGOS_CH_MAX_SERVER_MEMORY_USAGE: "$ch_bytes"
      ARGOS_CH_WRITER_MAX_MEMORY_USAGE: "$((ch_bytes * 4 / 15))"
      ARGOS_CH_READER_MAX_MEMORY_USAGE: "$((ch_bytes * 2 / 5))"
    volumes:
      - type: volume
        source: wo43-backups-$1
        target: /backups
  postgres:
    labels:
      $LABEL_KEY: backup-roundtrip
    mem_limit: $PG_MEM
volumes:
  wo43-backups-$1:
$opts
EOF
}

prepare() {
  local t0
  dir=$work/deploy/compose
  mkdir -p "$work/deploy/clickhouse" "$work/deploy/postgres" "$work/ch-backup-dir"
  cp -R "$COMPOSE_DIR" "$dir"
  rm -rf "$dir/.env" "$dir/secrets" "$dir/tls" "$dir/test" "$dir/compose.override.yaml" "$dir/backups" "$dir/upgrade-state.json"
  cp -R "$DEPLOY_DIR/clickhouse/config.d" "$DEPLOY_DIR/clickhouse/users.d" "$DEPLOY_DIR/clickhouse/image.properties" "$work/deploy/clickhouse/"
  cp "$DEPLOY_DIR/postgres/roles.sql" "$DEPLOY_DIR/postgres/image.properties" "$work/deploy/postgres/"
  cd "$dir"
  export COMPOSE_PROJECT_NAME=$PROJECT
  t0=$(now)
  ./argos-env-init.sh --ram-mb 8192 --public-url "$ORIGIN" --unattended --ip-mode masked >"$work/out/env-init.txt" 2>&1 \
    || { show "$work/out/env-init.txt" >&2; fail "argos-env-init.sh --unattended failed"; }
  # ARGOS_CH_BACKUP_DIR makes argos-compose.sh add compose.chbackup.yaml (the backup disk of
  # ClickHouse); compose.override.yaml puts a Docker volume of this run in place of its bind mount.
  printf 'ARGOS_CH_BACKUP_DIR=%s\n' "$(host_path "$work/ch-backup-dir" | sed 's|\\|/|g')" >>.env
  timing "argos-env-init.sh --unattended" $(($(now) - t0))
  # The server refuses a master key that group or others can read (C-226 (2)); a file written from
  # Windows shows as 0777 in a container, so mode and owner are set from one (as in compose-smoke.sh).
  docker run --rm --network none --label "$LABEL_KEY=backup-roundtrip" --entrypoint sh -v "$(host_path "$PWD/secrets"):/s" "$POSTGRES_IMAGE" -c \
    "chmod 0600 /s/master_key && chown 10001:10001 /s/master_key"
  mkdir -p tls
  docker run --rm --network none --label "$LABEL_KEY=backup-roundtrip" --entrypoint sh -v "$(host_path "$PWD/tls"):/out" "$POSTGRES_IMAGE" -c \
    "openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 2 -subj /CN=localhost \
       -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' -keyout /out/server.key -out /out/server.crt 2>/dev/null \
     && chmod 0600 /out/server.key && chmod 0644 /out/server.crt && chown 10001:10001 /out/server.key /out/server.crt"
  write_override tmpfs
}

# first_password_change: the unattended administrator signs in with secrets/admin_password, must
# change it (PUT /api/v1/auth/password), and signs in with the new one ($work/admin-password).
first_password_change() {
  local code
  code=$(login secrets/admin_password)
  [[ "$code" == 200 ]] || fail "sign-in with secrets/admin_password: HTTP $code"
  grep -q '"must_change_password" *: *true' "$work/.login.body" || fail "the administrator is not asked to change the password"
  (umask 077 && head -c 18 /dev/urandom | base64 | tr -d '\n=' | tr '+/' '-_' >"$work/admin-password")
  passwords+=("$(head -n 1 secrets/admin_password)" "$(head -n 1 "$work/admin-password")")
  code=$(printf '{"current":"%s","new":"%s"}' "$(json_escape "$(head -n 1 secrets/admin_password)")" "$(head -n 1 "$work/admin-password")" \
    | curl -s -o "$work/.api.body" -w '%{http_code}' --config "$work/.session.cfg" -X PUT -H 'Content-Type: application/json' \
      --data-binary @- "$BASE/api/v1/auth/password" || true)
  [[ "$code" == 204 ]] || { cat "$work/.api.body" >&2; fail "PUT /api/v1/auth/password: HTTP $code"; }
  code=$(login "$work/admin-password")
  [[ "$code" == 200 ]] || fail "sign-in with the new password: HTTP $code"
  grep -q '"must_change_password" *: *false' "$work/.login.body" || fail "the administrator must still change the password"
  log "ok: the administrator changed the first password and signs in with the new one"
}

# ---------------------------------------------------------------------------------------------
# scenarios

app_a=
last_set=
stale_app_c=
exp_a=0

setup_stack() {
  local t0
  t0=$(now)
  prepare
  ./argos-compose.sh up --timeout 420 >"$work/out/up-1.txt" 2>&1 || { show "$work/out/up-1.txt" >&2; fail "argos-compose.sh up failed"; }
  timing "argos-compose.sh up -> healthy" $(($(now) - t0))
  [[ "$(dc exec -T -u 0 clickhouse sh -c 'stat -c %d /var/lib/clickhouse; stat -c %d /backups' | tr -d '\r' | sort -u | wc -l)" == 2 ]] \
    || fail "/backups (tmpfs volume) is on the device of the ClickHouse data"
  first_password_change
  t0=$(now)
  token_create wo43-a rt a
  app_a=$APP_ID
  agent_config a wo43-a rt
  start_app a "$APP_PORT"
  exp_a=$(await_stable "$app_a" 120)
  drive "$APP_PORT" "$N_FIRST"
  exp_a=$((exp_a + N_FIRST))
  await_count "$app_a" "$exp_a" 180
  # The server applies the recommended alert rules of a new app within a minute (TemplateService,
  # a periodic job); the backup point is taken after that, so that the state compared is stable.
  local i
  for ((i = 0; i < 120; i += 3)); do
    [[ "$(psql_owner "SELECT recommended_rules_applied FROM argos.apps WHERE id = $app_a")" == t ]] && break
    sleep 3
  done
  [[ "$(psql_owner "SELECT recommended_rules_applied FROM argos.apps WHERE id = $app_a")" == t ]] \
    || fail "the recommended alert rules of app A were not applied within 120 s"
  log "ok: app A (id $app_a): a real agent with its token file; $exp_a transactions in argos.xlog; recommended rules applied"
  timing "token create, agent, $N_FIRST requests" $(($(now) - t0))
}

# backup_set <out name> [backup.sh options]: ./argos-compose.sh backup --json; prints the set path.
backup_set() {
  local name=$1 rc=0 set
  shift
  ./argos-compose.sh backup --json "$@" >"$work/out/$name.json" 2>"$work/out/$name.err" || rc=$?
  [[ $rc -eq 0 ]] || { show "$work/out/$name.err" >&2; fail "argos-compose.sh backup $* exit $rc"; }
  set=$(grep '^{' "$work/out/$name.json" | tail -n 1 | sed -n 's/.*"set":"\([^"]*\)".*/\1/p')
  [[ -n "$set" && -f "$set/manifest.json" ]] || fail "backup.sh named no backup set"
  printf '%s' "$set"
}

roundtrip() { # T-NFR-20a
  local t0 t1 set pg_before ch_before app_b code rc=0 last d_backup d_restore
  t0=$(now)
  pg_before=$(pg_state)
  ch_before=$(ch_state)
  t1=$(now)
  set=$(backup_set backup-1 --clickhouse)
  d_backup=$(($(now) - t1))
  show "$work/out/backup-1.err"
  grep -q '"clickhouse":"argos-backup-' "$work/out/backup-1.json" || fail "backup.sh --clickhouse wrote no ClickHouse backup"
  [[ "$(dc exec -T -u 0 clickhouse sh -c "test -f /backups/$(basename "$set")/.backup && echo yes" | tr -d '\r')" == yes ]] \
    || fail "the ClickHouse backup $(basename "$set") is not complete in /backups"

  # Changes after the backup.
  token_create wo43-b rt b
  app_b=$APP_ID
  drive "$APP_PORT" "$N_CHANGE"
  await_count "$app_a" $((exp_a + N_CHANGE)) 180
  [[ "$(pg_state)" != "$pg_before" ]] || fail "token create for app B changed nothing in PostgreSQL"

  t1=$(now)
  ./argos-compose.sh restore "$set" --clickhouse --yes --json >"$work/out/restore-1.json" 2>"$work/out/restore-1.err" || rc=$?
  d_restore=$(($(now) - t1))
  show "$work/out/restore-1.err"
  [[ $rc -eq 0 ]] || fail "restore.sh --clickhouse exit $rc"
  last=$(grep '^{' "$work/out/restore-1.json" | tail -n 1)
  [[ "$last" == *'"clickhouse":true'* && "$last" == *'"point_in_time_mismatch":false'* && "$last" == *'"previous_database_dropped":true'* ]] \
    || fail "restore.sh --json: $last"
  ! grep -q 'point-in-time mismatch' "$work/out/restore-1.err" || fail "restore.sh warned about a point-in-time mismatch after a PostgreSQL and ClickHouse restore of one set"
  await_healthy 120
  same_pg "$pg_before" "PostgreSQL after the restore differs from the backup point"
  [[ "$(psql_owner "SELECT count(*) FROM argos.apps WHERE id = $app_b")" == 0 ]] || fail "app B is still there"
  [[ "$(ch_state)" == "$ch_before" ]] || fail "ClickHouse counts after the restore ($(ch_state)) differ from the backup point ($ch_before)"
  [[ "$(psql_owner "SELECT count(*) FROM pg_database WHERE datname IN ('argos_restore') OR datname LIKE 'argos_pre_restore%'")" == 0 ]] \
    || fail "a staging or previous database is left after the restore"
  code=$(login "$work/admin-password")
  [[ "$code" == 200 ]] || fail "sign-in of the administrator after the restore: HTTP $code"
  expect_refused b wo43-b
  rc=$(agent_check a wo43-a rt)
  [[ "$rc" == 0 ]] || { show "$work/out/check-a.txt" >&2; fail "--check with the token of app A: exit $rc"; }
  # The running agent of app A reconnects with its token and its new requests are stored.
  drive "$APP_PORT" "$N_AFTER"
  exp_a=$((exp_a + N_AFTER))
  await_count "$app_a" "$exp_a" 240
  result "[wo43-roundtrip] T-NFR-20a PASS backup.sh --clickhouse ${d_backup} s, restore.sh --clickhouse ${d_restore} s: PostgreSQL state and ClickHouse counts ($ch_before) equal the backup point, app B and its token gone (--check exit 1), token of app A works (--check exit 0, agent reconnected, $N_AFTER new requests stored, count $exp_a), administrator signs in"
  timing "T-NFR-20a" $(($(now) - t0))
  last_set=$set
}

wrong_key() { # T-SEC-52e
  local t0 rc=0 before cid_before cid_after started_before started_after audit_before audit_after
  t0=$(now)
  (umask 077 && head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' >"$work/other-master-key")
  before=$(pg_state)
  audit_before=$(psql_owner "SELECT count(*) FROM argos.audit_logs")
  cid_before=$(server_cid)
  started_before=$(docker inspect --format '{{.State.StartedAt}}' "$cid_before" | tr -d '\r')
  ./argos-compose.sh restore "$last_set" --master-key "$work/other-master-key" --yes >"$work/out/restore-wrongkey.txt" 2>&1 || rc=$?
  show "$work/out/restore-wrongkey.txt"
  [[ $rc -eq 1 ]] || fail "restore.sh with another master key: exit $rc, expected 1"
  grep -q 'is not the key of this backup' "$work/out/restore-wrongkey.txt" || fail "restore.sh did not name the key mismatch"
  grep -q 'nothing was changed' "$work/out/restore-wrongkey.txt" || fail "restore.sh did not say that nothing was changed"
  audit_after=$(psql_owner "SELECT count(*) FROM argos.audit_logs")
  cid_after=$(server_cid)
  started_after=$(docker inspect --format '{{.State.StartedAt}}' "$cid_after" | tr -d '\r')
  same_pg "$before" "the live database changed after the refused restore"
  [[ "$audit_after" == "$audit_before" ]] || fail "the refused restore wrote audit rows ($audit_before -> $audit_after)"
  [[ "$cid_after" == "$cid_before" && "$started_after" == "$started_before" ]] || fail "the server container was stopped or replaced by the refused restore"
  [[ "$(psql_owner "SELECT count(*) FROM pg_database WHERE datname = 'argos_restore' OR datname LIKE 'argos_pre_restore%'")" == 0 ]] \
    || fail "the refused restore left a staging database"
  ! cmp -s "$work/other-master-key" secrets/master_key || fail "secrets/master_key was replaced"
  [[ "$(server_health)" == healthy ]] || fail "the server is not healthy after the refused restore"
  rm -f "$work/other-master-key"
  result "[wo43-wrongkey] T-SEC-52e PASS restore.sh --master-key <another valid key>: exit 1 before any change; PostgreSQL state and $audit_before audit rows unchanged, server container and start time unchanged, no staging database, secrets/master_key kept, server healthy"
  timing "T-SEC-52e" $(($(now) - t0))
}

point_in_time() { # T-NFR-20b
  local t0 t1 set pg_before app_c app_e inst_c next_inst c_count rc=0 last code path bad=() codes=() from to
  t0=$(now)
  pg_before=$(pg_state)
  set=$(backup_set backup-2)
  grep -q '"clickhouse":null' "$work/out/backup-2.json" || fail "backup.sh without --clickhouse wrote a ClickHouse backup"
  # After the backup: app C with an agent and data in ClickHouse, more requests of app A.
  token_create wo43-c rt c
  app_c=$APP_ID
  agent_config c wo43-c rt
  start_app c "$APP_PORT_C"
  drive "$APP_PORT_C" 10
  c_count=$(await_stable "$app_c" 120)
  stop_app c
  drive "$APP_PORT" "$N_AFTER"
  exp_a=$((exp_a + N_AFTER))
  await_count "$app_a" "$exp_a" 180

  t1=$(now)
  ./argos-compose.sh restore "$set" --yes --json >"$work/out/restore-2.json" 2>"$work/out/restore-2.err" || rc=$?
  show "$work/out/restore-2.err"
  [[ $rc -eq 0 ]] || fail "restore.sh (PostgreSQL only, older than ClickHouse): exit $rc, expected 0"
  grep -q 'WARNING: point-in-time mismatch' "$work/out/restore-2.err" || fail "restore.sh printed no 'point-in-time mismatch' warning"
  last=$(grep '^{' "$work/out/restore-2.json" | tail -n 1)
  [[ "$last" == *'"point_in_time_mismatch":true'* && "$last" == *'"clickhouse":false'* ]] || fail "restore.sh --json: $last"
  await_healthy 120
  same_pg "$pg_before" "PostgreSQL after the restore differs from the backup point"
  [[ "$(psql_owner "SELECT count(*) FROM argos.apps WHERE id = $app_c")" == 0 ]] || fail "app C is still in PostgreSQL"
  [[ "$(app_count "$app_c" | awk '{ print $1 }')" == "$c_count" ]] || fail "the ClickHouse rows of app C changed"
  # The ids of app C and its instance, which only ClickHouse still holds, are not handed out again.
  grep -q 'The apps id sequence now continues after' "$work/out/restore-2.err" || fail "restore.sh did not move the app id sequence past the ids of ClickHouse"
  inst_c=$(ch_q "SELECT max(instance_id) FROM argos.xlog WHERE app_id = $app_c FORMAT TSV" | tr -d '\r')
  [[ "$inst_c" =~ ^[1-9][0-9]*$ ]] || fail "no instance id of app C in ClickHouse ($inst_c)"
  next_inst=$(psql_owner "SELECT CASE WHEN is_called THEN last_value + 1 ELSE last_value END FROM argos.instances_id_seq")
  [[ "$next_inst" =~ ^[0-9]+$ && "$next_inst" -gt "$inst_c" ]] || fail "the next instance id $next_inst would reuse the id $inst_c of the instance of app C"
  token_create wo43-e rt e
  app_e=$APP_ID
  [[ "$app_e" -gt "$app_c" ]] || fail "app E created after the restore got the id $app_e, not after app C ($app_c): its screens would show the ClickHouse data of app C"
  # The agent of app A reconnects; its new requests are stored.
  drive "$APP_PORT" "$N_AFTER"
  exp_a=$((exp_a + N_AFTER))
  await_count "$app_a" "$exp_a" 240
  # The query API over the whole window: no 5xx, also for the app id PostgreSQL no longer knows.
  code=$(login "$work/admin-password")
  [[ "$code" == 200 ]] || fail "sign-in after the restore: HTTP $code"
  from=$(date -u -d "@$((started - 3600))" +%Y-%m-%dT%H:%M:%SZ)
  to=$(date -u -d "@$(($(now) + 300))" +%Y-%m-%dT%H:%M:%SZ)
  for path in /api/v1/apps /api/v1/overview /api/v1/overview/alerts \
    "/api/v1/apps/$app_a/dashboard?from=$from&to=$to" "/api/v1/apps/$app_a/xlog?from=$from&to=$to" \
    "/api/v1/apps/$app_a/services/stats?from=$from&to=$to" \
    "/api/v1/apps/$app_a/sql/stats?from=$from&to=$to" "/api/v1/apps/$app_a/errors/groups?from=$from&to=$to" \
    "/api/v1/apps/$app_a/external/stats?from=$from&to=$to" "/api/v1/apps/$app_a/instances" \
    "/api/v1/apps/$app_a/collection-health" "/api/v1/apps/$app_c/xlog?from=$from&to=$to" \
    "/api/v1/apps/$app_c/dashboard?from=$from&to=$to"; do
    code=$(api_get "$path")
    codes+=("${path%%\?*}=$code")
    [[ "$code" =~ ^[234][0-9][0-9]$ ]] || bad+=("${path%%\?*} $code")
  done
  [[ ${#bad[@]} -eq 0 ]] || fail "the query API answered 5xx (or nothing) after the point-in-time mismatch: ${bad[*]}"
  code=$(api_get "/api/v1/apps/$app_a/xlog?from=$from&to=$to")
  [[ "$code" == 200 ]] || fail "GET /api/v1/apps/$app_a/xlog: HTTP $code"
  expect_refused c wo43-c
  log "query API status codes: ${codes[*]}"
  result "[wo43-pitr] T-NFR-20b PASS PostgreSQL-only restore older than ClickHouse: 'point-in-time mismatch' warning, exit 0, server healthy, agent of app A reconnected ($N_AFTER new requests stored, count $exp_a), ${#codes[@]} query API calls without 5xx (app C id $app_c with $c_count ClickHouse rows: ${codes[-1]}), token of app C refused (--check exit 1), id sequences moved past ClickHouse (new app E id $app_e > $app_c, next instance id $next_inst > $inst_c)"
  timing "T-NFR-20b" $(($(now) - t0))
  stale_app_c=$app_c
}

# recovery <kill|downup>: T-ACC-13 (b) Compose: requests of app A run while the server is killed (or
# the stack goes down and up); afterwards count(xlog) = requests answered by the app.
recovery() {
  local mode=$1 t0 t_down base sent out=$work/out/traffic-$1.txt
  t0=$(now)
  base=$exp_a
  : >"$out"
  (
    for i in $(seq 1 "$N_TRAFFIC"); do
      curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 "http://127.0.0.1:$APP_PORT/api/orders/$(((i % 5) + 1))" >>"$out" || echo 000 >>"$out"
      sleep 0.25
    done
  ) &
  traffic_pid=$!
  sleep 8
  t_down=$(now)
  case $mode in
    kill)
      docker kill -s KILL "$(server_cid)" >/dev/null
      log "docker kill -s KILL server during agent traffic"
      ;;
    downup)
      ./argos-compose.sh down >"$work/out/down.txt" 2>&1 || { show "$work/out/down.txt" >&2; fail "argos-compose.sh down failed"; }
      log "argos-compose.sh down (volumes kept) during agent traffic"
      ;;
  esac
  ./argos-compose.sh up --timeout 420 >"$work/out/up-$mode.txt" 2>&1 || { show "$work/out/up-$mode.txt" >&2; fail "argos-compose.sh up after $mode failed"; }
  local d_up=$(($(now) - t_down))
  wait "$traffic_pid"
  traffic_pid=
  sent=$(grep -c '^200$' "$out" || true)
  [[ "$sent" -ge 1 ]] || fail "no request of the traffic was answered"
  exp_a=$((base + sent))
  await_count "$app_a" "$exp_a" 300
  local tag label
  if [[ $mode == kill ]]; then tag=wo43-kill; label="docker kill -s KILL server -> argos-compose.sh up"; else tag=wo43-downup; label="argos-compose.sh down (volumes kept) -> up"; fi
  result "[$tag] T-ACC-13 (b) Compose PASS $label during agent traffic: $sent requests answered, count(xlog) = uniqExact(tx_id) = $sent new rows (total $exp_a), outage to healthy ${d_up} s"
  timing "T-ACC-13 (b) $mode" $(($(now) - t0))
}

upgrade() { # T-NFR-11a
  local t0 from_version major minor patch to_version rc=0 pg_before ch_before app_d img elapsed pre_set
  t0=$(now)
  from_version=$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' "$SERVER_IMAGE" | tr -d '\r')
  [[ "$from_version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+) ]] || fail "the server image has no MAJOR.MINOR.PATCH version label ($from_version)"
  major=${BASH_REMATCH[1]}
  minor=${BASH_REMATCH[2]}
  patch=${BASH_REMATCH[3]}
  to_version=$major.$minor.$((patch + 1))-wo43
  upgrade_image=argos-wo43-upgrade:$to_version
  printf 'FROM %s\nLABEL org.opencontainers.image.version=%s\n' "$SERVER_IMAGE" "$to_version" \
    | docker build -q --network none --label "$LABEL_KEY=backup-roundtrip" -t "$upgrade_image" - >"$work/out/build.txt" 2>&1 \
    || { cat "$work/out/build.txt" >&2; fail "docker build of the relabelled image failed"; }
  pg_before=$(pg_state)
  ch_before=$(ch_state)
  ./argos-compose.sh upgrade --image "$upgrade_image" --yes >"$work/out/upgrade.txt" 2>&1 || rc=$?
  show "$work/out/upgrade.txt"
  [[ $rc -eq 0 ]] || fail "argos-compose.sh upgrade --image $upgrade_image: exit $rc, expected 0"
  elapsed=$(sed -n 's/^Upgraded to .* in \([0-9]*\) s .*/\1/p' "$work/out/upgrade.txt" | head -n 1)
  [[ -n "$elapsed" ]] || fail "argos-compose.sh upgrade printed no duration"
  img=$(docker inspect --format '{{.Config.Image}}' "$(server_cid)" | tr -d '\r')
  [[ "$img" == "$upgrade_image" ]] || fail "the server runs $img after the upgrade, expected $upgrade_image"
  pre_set=$(find backups -maxdepth 1 -type d -name "argos-backup-*-pre-upgrade-*" | head -n 1)
  [[ -n "$pre_set" && -f "$pre_set/manifest.json" ]] || fail "no pre-upgrade backup set in backups/"
  grep -q '"status": "done"' upgrade-state.json || fail "upgrade-state.json does not say done"
  same_pg "$pg_before" "PostgreSQL changed by the upgrade"
  [[ "$(ch_state)" == "$ch_before" ]] || fail "ClickHouse counts changed by the upgrade"

  # A change after the upgrade, then the rollback.
  token_create wo43-d rt d
  app_d=$APP_ID
  [[ "$(pg_state)" != "$pg_before" ]] || fail "token create for app D changed nothing"
  if [[ -n "${stale_app_c:-}" && "$app_d" -le "$stale_app_c" ]]; then
    fail "app D got the id $app_d, not after app C ($stale_app_c), whose ClickHouse rows outlived the PostgreSQL-only restore (T-NFR-20b)"
  fi
  rc=0
  ./argos-compose.sh upgrade --rollback --yes >"$work/out/rollback.txt" 2>&1 || rc=$?
  show "$work/out/rollback.txt"
  [[ $rc -eq 0 ]] || fail "argos-compose.sh upgrade --rollback: exit $rc, expected 0"
  await_healthy 120
  img=$(docker inspect --format '{{.Config.Image}}' "$(server_cid)" | tr -d '\r')
  [[ "$img" == "$SERVER_IMAGE" ]] || fail "the server runs $img after the rollback, expected $SERVER_IMAGE"
  same_pg "$pg_before" "PostgreSQL after the rollback differs from the pre-upgrade state"
  [[ "$(psql_owner "SELECT count(*) FROM argos.apps WHERE name = 'wo43-d'")" == 0 ]] || fail "app D is still there after the rollback"
  grep -q '"status": "rolled_back"' upgrade-state.json || fail "upgrade-state.json does not say rolled_back"
  result "[wo43-upgrade] T-NFR-11a PASS upgrade $from_version -> $to_version (relabelled local image, no network): exit 0 in ${elapsed} s, pre-upgrade set $(basename "$pre_set"), data kept; app D then upgrade --rollback: exit 0, $SERVER_IMAGE runs again, app D gone"
  timing "T-NFR-11a" $(($(now) - t0))
}

same_device() { # T-NFR-20c
  local t0 rc=0 sets_before sets_after
  t0=$(now)
  write_override plain
  ./argos-compose.sh up --timeout 420 >"$work/out/up-plain.txt" 2>&1 || { show "$work/out/up-plain.txt" >&2; fail "argos-compose.sh up with /backups on a named volume failed"; }
  sets_before=$(find backups -mindepth 1 -maxdepth 1 | sort | paste -sd ' ' -)
  ./argos-compose.sh backup --clickhouse >"$work/out/backup-same.txt" 2>&1 || rc=$?
  show "$work/out/backup-same.txt"
  [[ $rc -eq 1 ]] || fail "backup.sh --clickhouse with /backups on the disk of the ClickHouse data: exit $rc, expected 1"
  grep -q 'same disk as the ClickHouse data' "$work/out/backup-same.txt" || fail "backup.sh did not name the same disk"
  sets_after=$(find backups -mindepth 1 -maxdepth 1 | sort | paste -sd ' ' -)
  [[ "$sets_after" == "$sets_before" ]] || fail "backup.sh left something in backups/ ($sets_after)"
  [[ "$(dc exec -T -u 0 clickhouse sh -c 'ls -A /backups | wc -l' | tr -d '\r ')" == 0 ]] || fail "backup.sh wrote into /backups"
  result "[wo43-chsame] T-NFR-20c PASS /backups on a named volume of the ClickHouse data disk: backup.sh --clickhouse exit 1 (same disk), nothing written in backups/ or /backups"
  timing "T-NFR-20c" $(($(now) - t0))
}

# no_secret_written: no agent token and no administrator password in any output, file of this run
# (other than the token files themselves) or container log (T-SEC-73).
no_secret_written() {
  local patterns=$work/.patterns
  (umask 077 && printf '%s\n' "${tokens[@]}" "${passwords[@]}" >"$patterns")
  ./argos-compose.sh logs --no-color >"$work/out/container-logs.txt" 2>&1 || true
  if grep -rlF -f "$patterns" "$work" --exclude=.patterns --exclude='.tok-*' --exclude=token --exclude=admin-password \
    --exclude-dir=secrets >"$work/.hits" 2>/dev/null; then
    rm -f "$patterns"
    fail "a token or password is written in $(tr '\n' ' ' <"$work/.hits")"
  fi
  rm -f "$patterns" "$work/out/container-logs.txt"
  log "ok: T-SEC-73: no agent token and no administrator password in the script outputs, files of this run or container logs"
}

# ---------------------------------------------------------------------------------------------

log "=== preparation (project $PROJECT)"
# argos-admin secrets verify must be in the image: restore.sh checks the master key with it.
docker run --rm --network none --label "$LABEL_KEY=backup-roundtrip" --entrypoint java "$SERVER_IMAGE" -Xmx256m -XX:+UseSerialGC \
  -cp @/opt/argos/app/jib-classpath-file io.argos.server.ServerApplication admin help >"$work/out/admin-help.txt" 2>&1 || true
grep -q 'secrets verify' "$work/out/admin-help.txt" \
  || { cat "$work/out/admin-help.txt" >&2; fail "the image $SERVER_IMAGE has no 'argos-admin secrets verify' (rebuild it: ./gradlew :server:jibDockerBuild)"; }
setup_stack
log "=== T-NFR-20a backup round trip"
roundtrip
log "=== T-SEC-52e restore with another master key"
wrong_key
log "=== T-NFR-20b point-in-time mismatch"
point_in_time
log "=== T-ACC-13 (b) Compose: kill -9"
recovery kill
log "=== T-ACC-13 (b) Compose: down and up"
recovery downup
log "=== T-NFR-11a upgrade and rollback"
upgrade
log "=== T-NFR-20c ClickHouse backup on the data disk"
same_device
stop_app a
no_secret_written
t0=$(now)
./argos-compose.sh down --volumes >/dev/null
timing "argos-compose.sh down --volumes" $(($(now) - t0))
cd "$work"

log "=== results"
printf '  %s\n' "${results[@]}"
log "=== durations (shared laptop: reference values only, INVALID as measurements)"
printf '  %s\n' "${timings[@]}"
log "total $(($(now) - started)) s"
log "PASSED"
