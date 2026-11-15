#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end smoke of the Docker Compose installation (WO-40; docs/17 section 3 and 14 "install
# state"; T-SEC-47, T-SEC-55, T-SEC-74, T-SEC-75; ADR-0010 C-172, C-173). Run by
# ./gradlew :deploy:composeSmoke, which builds the server image, the agent jar and the sample app
# first. Development and CI only, never shipped; needs Docker and about 5 GiB of memory.
#
# For each mode (setup token wizard, then unattended) in a fresh copy of deploy/:
#   argos-env-init.sh --ram-mb 8192 -> a throwaway self-signed gRPC certificate (only here, never
#   committed, C-172 (1)) -> compose.override.yaml with small memory limits and the test label ->
#   argos-compose.sh up (project argos-wo40-smoke) -> smoke.sh -> mode-specific checks ->
#   argos-compose.sh down --volumes.
# Wizard mode adds: smoke.sh --complete-setup; argos-compose.sh token create, a real agent (the
# shop-boot3 sample app) connecting over TLS with the printed token in a server.token-file (the
# guide's pattern), its transactions in ClickHouse (reader account, and the query API of a
# logged-in session), the token absent from every log and file, the limit of 5 active tokens (the
# 6th is refused, exit 4) and one audit row per token; the management port published with
# ARGOS_MGMT_EXPOSE=true (bearer token, allow-cidrs).
# Unattended mode adds: smoke.sh --check-admin-login (sign-in with secrets/admin_password, the
# password must be changed, sign-out); a forbidden ClickHouse password stops the server start
# (T-SV-049 linkage) and makes smoke.sh fail (T-SEC-74).
#
# Environment (set by the Gradle task):
#   ARGOS_SMOKE_AGENT_JAR   agent jar (./gradlew :agent:agent-dist:agentJar)
#   ARGOS_SMOKE_SAMPLE_JAR  shop-boot3 jar (./gradlew :sample-apps:shop-boot3:sampleAppJar)
#   ARGOS_SMOKE_JAVA        java executable for the sample app (JDK 17 or later)
#   ARGOS_SMOKE_MODES       modes to run (default "wizard unattended")
#   ARGOS_SMOKE_APP_PORT    host port of the sample app (default 18093)
# Nothing secret is printed: tokens appear as argos_****.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/compose/test/lib.sh
source "$here/lib.sh"

PROJECT=argos-wo40-smoke
MODES=${ARGOS_SMOKE_MODES:-wizard unattended}
APP_PORT=${ARGOS_SMOKE_APP_PORT:-18093}
AGENT_JAR=${ARGOS_SMOKE_AGENT_JAR:-}
SAMPLE_JAR=${ARGOS_SMOKE_SAMPLE_JAR:-}
JAVA=${ARGOS_SMOKE_JAVA:-java}
# Small stack for a shared machine (the documented plan for 8 GiB is larger; heap 1 GiB here).
SERVER_MEM=2048m
SERVER_HEAP=1024m
CH_MEM_MIB=3072
PG_MEM=1024m

docker info >/dev/null 2>&1 || { echo "compose-smoke: Docker is not available" >&2; exit 2; }
for f in "$AGENT_JAR" "$SAMPLE_JAR"; do
  [[ -n "$f" && -f "$f" ]] || { echo "compose-smoke: missing jar '$f' (run through ./gradlew :deploy:composeSmoke)" >&2; exit 2; }
done

work=$(mktemp -d "${TMPDIR:-/tmp}/argos-wo40-smoke.XXXXXX")
chmod 0700 "$work"
java_pid=
current_dir=
started=$(date +%s)
timings=()
stats=()

log() { printf '[compose-smoke %s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
# lib.sh sets MSYS_NO_PATHCONV for docker under Git Bash; curl needs the usual conversion of its file
# arguments there (no effect elsewhere).
curl() { env -u MSYS_NO_PATHCONV curl "$@"; }
fail() {
  printf '[compose-smoke] FAIL %s\n' "$*" >&2
  exit 1
}
redact() { sed -E 's/argos_[A-Za-z0-9_-]{43}/argos_****/g'; }
now() { date +%s; }
timing() { timings+=("$1: $2 s"); log "$1 took $2 s"; }

cleanup() {
  local rc=$?
  # The agent token file goes in every case, also when the rest is kept for diagnosis (the agent
  # read it at its start).
  rm -f "$work/agent/token"
  if [[ $rc -ne 0 && "${ARGOS_SMOKE_KEEP_ON_FAILURE:-0}" == 1 ]]; then
    log "FAILED (exit $rc); ARGOS_SMOKE_KEEP_ON_FAILURE=1: the stack and $work are kept for diagnosis."
    log "Remove them with: cd $current_dir && COMPOSE_PROJECT_NAME=$PROJECT ./argos-compose.sh down --volumes; rm -rf $work"
    return
  fi
  if [[ -n "$java_pid" ]]; then
    kill "$java_pid" 2>/dev/null || true
    wait "$java_pid" 2>/dev/null || true
  fi
  if [[ -n "$current_dir" && -d "$current_dir" ]]; then
    if [[ $rc -ne 0 ]]; then
      log "logs of the failed stack (last 60 lines per service; tokens redacted):"
      (cd "$current_dir" && COMPOSE_PROJECT_NAME=$PROJECT ./argos-compose.sh compose ps --all 2>&1
        for svc in postgres clickhouse server; do
          COMPOSE_PROJECT_NAME=$PROJECT ./argos-compose.sh compose logs --no-color --tail 60 "$svc" 2>&1
        done) | redact | sed -E 's/(Setup token[^:]*\): ).*/\1****/' | cut -c1-600 || true
    fi
    (cd "$current_dir" && COMPOSE_PROJECT_NAME=$PROJECT ./argos-compose.sh down --volumes >/dev/null 2>&1) || true
  fi
  docker ps -aq --filter "label=com.docker.compose.project=$PROJECT" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT" | xargs -r docker volume rm >/dev/null 2>&1 || true
  docker network ls -q --filter "label=com.docker.compose.project=$PROJECT" | xargs -r docker network rm >/dev/null 2>&1 || true
  rm -rf "$work"
  if [[ $rc -eq 0 ]]; then
    log "cleaned up (containers, volumes, networks, generated secrets)"
  else
    log "FAILED (exit $rc); cleaned up"
  fi
}
trap cleanup EXIT

sample_stats() { # sample_stats <label>: memory of this project's containers only
  local line ids
  ids=$(docker ps -q --filter "label=com.docker.compose.project=$PROJECT" | tr '\n' ' ')
  [[ -n "${ids// /}" ]] || return 0
  # shellcheck disable=SC2086 # the container ids are split on purpose
  while IFS= read -r line; do
    stats+=("$1 | $line")
  done < <(docker stats --no-stream --format '{{.Name}} {{.MemUsage}} {{.CPUPerc}}' $ids 2>/dev/null || true)
}

# prepare <mode> [env-init options]: a fresh copy with the layout of deploy/.
prepare() {
  local mode=$1
  shift
  local dir=$work/$mode
  mkdir -p "$dir/clickhouse" "$dir/postgres"
  cp -R "$COMPOSE_DIR" "$dir/compose"
  rm -rf "$dir/compose/.env" "$dir/compose/secrets" "$dir/compose/tls" "$dir/compose/test" "$dir/compose/compose.override.yaml"
  cp -R "$DEPLOY_DIR/clickhouse/config.d" "$DEPLOY_DIR/clickhouse/users.d" "$DEPLOY_DIR/clickhouse/image.properties" "$dir/clickhouse/"
  cp "$DEPLOY_DIR/postgres/roles.sql" "$DEPLOY_DIR/postgres/image.properties" "$dir/postgres/"
  current_dir=$dir/compose
  cd "$current_dir"
  export COMPOSE_PROJECT_NAME=$PROJECT
  local t0
  t0=$(now)
  ./argos-env-init.sh --ram-mb 8192 --public-url http://localhost:8080 "$@"
  timing "$mode: argos-env-init.sh" $(($(now) - t0))

  # Throwaway self-signed certificate for 9100 (C-172 (1): only the smoke harness creates one). The
  # key belongs to uid 10001 (the server's user, as the installation guide asks), also where Docker
  # Desktop keeps the owner that a container sets.
  mkdir -p tls
  docker run --rm --network none --label io.argos.wo40=1 --entrypoint sh -v "$(host_path "$PWD/tls"):/out" "$POSTGRES_IMAGE" -c \
    "openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 2 -subj /CN=localhost \
       -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' -keyout /out/server.key -out /out/server.crt 2>/dev/null \
     && chmod 0600 /out/server.key && chmod 0644 /out/server.crt && chown 10001:10001 /out/server.key /out/server.crt"

  # Smaller limits than the plan (shared machine) and the label of this work order's containers.
  local ch_bytes=$((CH_MEM_MIB * 1048576 * 9 / 10))
  cat >compose.override.yaml <<EOF
# Written by deploy/compose/test/compose-smoke.sh: test limits and labels only.
services:
  server:
    labels:
      io.argos.wo40: "1"
    mem_limit: $SERVER_MEM
    environment:
      JAVA_TOOL_OPTIONS: "-Xms$SERVER_HEAP -Xmx$SERVER_HEAP"
  clickhouse:
    labels:
      io.argos.wo40: "1"
    mem_limit: ${CH_MEM_MIB}m
    environment:
      ARGOS_CH_MAX_SERVER_MEMORY_USAGE: "$ch_bytes"
      ARGOS_CH_WRITER_MAX_MEMORY_USAGE: "$((ch_bytes * 4 / 15))"
      ARGOS_CH_READER_MAX_MEMORY_USAGE: "$((ch_bytes * 2 / 5))"
  postgres:
    labels:
      io.argos.wo40: "1"
    mem_limit: $PG_MEM
EOF
}

up() { # up <label>
  local t0
  t0=$(now)
  ./argos-compose.sh up --timeout 420
  timing "$1: argos-compose.sh up -> healthy" $(($(now) - t0))
  sample_stats "$1 after up"
}

run_smoke() { # run_smoke <label> [smoke.sh options]
  local label=$1 t0
  shift
  t0=$(now)
  ./smoke.sh "$@"
  timing "$label: smoke.sh" $(($(now) - t0))
}

sha256_of() { printf '%s' "$1" | sha256sum | awk '{ print $1 }'; }
psql_owner() { ./argos-compose.sh compose exec -T postgres psql -X -q -A -t -U argos -d argos -c "$1" | tr -d '\r'; }
ch_count() { # rows of argos.xlog, read as argos_reader from the server container
  # shellcheck disable=SC2016 # the script runs in the server container's shell, which expands it
  ./argos-compose.sh compose exec -T server sh -c \
    'pw=$(cat /run/secrets/ch_reader_password); printf "header = \"X-ClickHouse-User: argos_reader\"\nheader = \"X-ClickHouse-Key: %s\"\n" "$pw" | curl -s --config - "http://clickhouse:8123/?query=SELECT%20count()%20FROM%20argos.xlog"' \
    | tr -d '\r\n'
}

# token_create <app> <env>: prints the token (kept in the caller's memory only).
token_create() {
  local out rc=0
  out=$(./argos-compose.sh token create --app "$1" --env "$2" 2>&1) || rc=$?
  printf '%s\n' "$out" | redact >"$work/token-create-$rc.txt"
  [[ $rc -eq 0 ]] || { cat "$work/token-create-$rc.txt" >&2; return "$rc"; }
  grep -oE 'argos_[A-Za-z0-9_-]{43}' <<<"$out" | head -n 1
}

# never_written <description> <token...>: no file under the work directory and no container log
# contains any of the tokens.
never_written() {
  local desc=$1
  shift
  local patterns=$work/.patterns
  : >"$patterns"
  chmod 0600 "$patterns"
  printf '%s\n' "$@" >"$patterns"
  ./argos-compose.sh logs --no-color >"$work/container-logs.txt" 2>&1 || true
  if grep -rlF -f "$patterns" "$work" --exclude=.patterns >"$work/.hits" 2>/dev/null; then
    rm -f "$patterns"
    fail "$desc: a token is written in $(tr '\n' ' ' <"$work/.hits")"
  fi
  rm -f "$patterns" "$work/container-logs.txt"
  log "ok: $desc"
}

wizard_extra() {
  local t0 token token_hash prefix rows audit app_id
  # --- token create (C-173) ---------------------------------------------------------------------
  t0=$(now)
  token=$(token_create wo40-smoke smoke) || fail "token create failed"
  [[ ${#token} -eq 49 ]] || fail "token create printed no token of the 16 section 2.1 format"
  redact <"$work/token-create-0.txt" | sed 's/^/  | /'
  app_id=$(sed -n 's/.*(app id \([0-9]*\).*/\1/p' "$work/token-create-0.txt" | head -n 1)
  [[ -n "$app_id" ]] || fail "token create printed no app id"
  token_hash=$(sha256_of "$token")
  prefix=${token:0:14}
  rows=$(psql_owner "SELECT token_prefix || '|' || token_hash || '|' || status FROM argos.agent_tokens WHERE app_id = $app_id")
  [[ "$rows" == "$prefix|$token_hash|ACTIVE" ]] || fail "agent_tokens does not hold the prefix and SHA-256 of the printed token"
  audit=$(psql_owner "SELECT count(*) FROM argos.audit_logs WHERE action = 'TOKEN_ISSUED' AND actor_id = 'argos-compose' AND target_name = '$prefix'")
  [[ "$audit" == 1 ]] || fail "expected one TOKEN_ISSUED audit row, found '$audit'"
  log "ok: token create stored only the prefix and the SHA-256 (app id $app_id) and wrote one audit row"

  # --- a real agent with the printed token (TLS with the throwaway CA) ----------------------------
  # The pattern of the installation guide (section 9): the token in a file only its user can read,
  # named by server.token-file. bash's builtin printf writes it from the variable in a umask 077
  # subshell, so the token is never a process argument; the file is deleted as soon as the agent
  # has stopped (and by cleanup on failure).
  local agent_dir=$work/agent
  mkdir -p "$agent_dir/logs"
  (umask 077 && printf '%s\n' "$token" >"$agent_dir/token")
  cat >"$agent_dir/argos-agent.yml" <<EOF
server:
  address: localhost:9100
  tls:
    ca-file: $(host_path "$PWD/tls/server.crt" | sed 's|\\|/|g')
  token-file: $(host_path "$agent_dir/token" | sed 's|\\|/|g')
app:
  name: wo40-smoke
  env: smoke
log:
  dir: $(host_path "$agent_dir/logs" | sed 's|\\|/|g')
EOF
  # Only the token file: no token of the environment may stand in for it (agent-config.md order).
  env -u ARGOS_TOKEN -u ARGOS_SERVER_TOKEN "$JAVA" "-javaagent:$(host_path "$AGENT_JAR")=config=$(host_path "$agent_dir/argos-agent.yml")" \
    -jar "$(host_path "$SAMPLE_JAR")" --server.address=127.0.0.1 --server.port="$APP_PORT" \
    >"$agent_dir/app.log" 2>&1 &
  java_pid=$!
  local i code=000
  for i in $(seq 1 90); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$APP_PORT/api/runtime" 2>/dev/null || true)
    [[ "$code" == 200 ]] && break
    kill -0 "$java_pid" 2>/dev/null || { tail -n 30 "$agent_dir/app.log" | redact >&2; fail "the sample app stopped"; }
    sleep 1
  done
  [[ "$code" == 200 ]] || fail "the sample app did not start within 90 s"
  for i in $(seq 1 40); do
    curl -s -o /dev/null "http://127.0.0.1:$APP_PORT/api/orders/$(((i % 5) + 1))" || true
  done
  local count=0
  for i in $(seq 1 60); do
    count=$(ch_count || echo 0)
    [[ "$count" =~ ^[0-9]+$ ]] || count=0
    [[ "$count" -ge 20 ]] && break
    sleep 2
  done
  [[ "$count" -ge 20 ]] || { tail -n 40 "$agent_dir/logs/"*.log 2>/dev/null | redact >&2; fail "only $count transactions reached argos.xlog"; }
  grep -rqF 'collector session established' "$agent_dir/logs" \
    || { tail -n 40 "$agent_dir/logs/"*.log 2>/dev/null | redact >&2; fail "the agent log does not report 'collector session established'"; }
  log "ok: the agent connected with the token file (server.token-file, TLS, own CA; 'collector session established') and $count transactions reached argos.xlog (argos_reader)"
  sample_stats "wizard after agent traffic"

  # The query API of a logged-in session reads them as argos_reader (WO-23 hand-over).
  local cfg=$work/.session.cfg body
  printf '{"username":"admin","password":"%s"}' "$(head -n 1 "$work/admin-password")" \
    | curl -s -D "$work/login.headers" -o /dev/null -H 'Content-Type: application/json' -H 'Origin: http://localhost:8080' \
      --data-binary @- http://127.0.0.1:8080/api/v1/auth/login
  printf 'header = "Cookie: %s"\n' "$(sed -n 's/^[Ss]et-[Cc]ookie: *\([^;]*\).*/\1/p' "$work/login.headers" | tr -d '\r' | paste -sd ';' -)" >"$cfg"
  rm -f "$work/login.headers"
  local from to
  from=$(date -u -d "@$(($(now) - 900))" +%Y-%m-%dT%H:%M:%SZ)
  to=$(date -u -d "@$(($(now) + 60))" +%Y-%m-%dT%H:%M:%SZ)
  body=$(curl -s --config "$cfg" "http://127.0.0.1:8080/api/v1/apps/$app_id/xlog?from=$from&to=$to")
  rm -f "$cfg"
  local total
  total=$(grep -oE '"total" *: *[0-9]+' <<<"$body" | head -n 1 | grep -oE '[0-9]+$' || true)
  [[ -n "$total" && "$total" -ge 20 ]] || fail "GET /api/v1/apps/$app_id/xlog returned total '${total:-none}'"
  log "ok: GET /api/v1/apps/$app_id/xlog of a logged-in session shows $total transactions"

  kill "$java_pid" 2>/dev/null || true
  wait "$java_pid" 2>/dev/null || true
  java_pid=
  rm -f "$agent_dir/token"
  timing "wizard: token create + agent + data in ClickHouse" $(($(now) - t0))

  # --- the limit of 5 active tokens and one audit row each --------------------------------------
  local tokens=("$token") t rc
  for i in 2 3 4 5; do
    t=$(token_create wo40-smoke smoke) || fail "token $i was refused"
    tokens+=("$t")
  done
  rc=0
  ./argos-compose.sh token create --app wo40-smoke --env smoke >"$work/sixth.txt" 2>&1 || rc=$?
  [[ $rc -eq 4 ]] || { redact <"$work/sixth.txt" >&2; fail "the 6th active token: exit $rc, expected 4"; }
  grep -q 'already has 5 active agent tokens' "$work/sixth.txt" || fail "the 6th token refusal does not name the limit"
  grep -qE 'argos_[A-Za-z0-9_-]{43}' "$work/sixth.txt" && fail "the refused 6th run printed a token"
  rm -f "$work/sixth.txt"
  rows=$(psql_owner "SELECT count(*) FROM argos.agent_tokens WHERE app_id = $app_id")
  audit=$(psql_owner "SELECT count(*) FROM argos.audit_logs WHERE action = 'TOKEN_ISSUED' AND actor_id = 'argos-compose'")
  [[ "$rows" == 5 && "$audit" == 5 ]] || fail "expected 5 tokens and 5 audit rows, found $rows and $audit"
  log "ok: 5 active tokens per app; the 6th is refused (exit 4); one TOKEN_ISSUED audit row per token"
  rm -f "$work"/token-create-*.txt
  never_written "no token in a file, the container logs or the agent log" "${tokens[@]}"
  tokens=()
  token=

  # --- management port with ARGOS_MGMT_EXPOSE=true (C-114, SEC-55) --------------------------------
  t0=$(now)
  ARGOS_MGMT_EXPOSE=true ./argos-compose.sh up --timeout 300 >/dev/null
  ARGOS_MGMT_EXPOSE=true ./smoke.sh --only ports,actuator
  local mcfg=$work/.metrics.cfg
  printf 'header = "Authorization: Bearer %s"\n' "$(head -n 1 secrets/metrics_token)" >"$mcfg"
  code=$(curl -s -o /dev/null -w '%{http_code}' --config "$mcfg" http://127.0.0.1:8081/actuator/prometheus || true)
  rm -f "$mcfg"
  [[ "$code" == 200 ]] || fail "127.0.0.1:8081/actuator/prometheus with the bearer token answers $code (allow-cidrs, C-114)"
  log "ok: ARGOS_MGMT_EXPOSE=true publishes 127.0.0.1:8081; prometheus needs the bearer token and accepts it from the host"
  timing "wizard: management port variant" $(($(now) - t0))
}

unattended_extra() {
  # T-SV-049 linkage: a forbidden database password stops the server start.
  local t0 rc=0
  t0=$(now)
  ./argos-compose.sh down >/dev/null 2>&1
  printf 'changeme' >secrets/ch_reader_password
  ./argos-compose.sh up --timeout 90 >"$work/forbidden-up.txt" 2>&1 || rc=$?
  [[ $rc -ne 0 ]] || fail "the stack started with a forbidden ClickHouse reader password"
  ./argos-compose.sh logs server --no-color 2>&1 | grep -q 'forbidden default password' \
    || fail "the server log does not name the forbidden password"
  log "ok: a forbidden ClickHouse password ('changeme') stops the server start (exit $rc)"
  # T-SEC-74: smoke.sh fails on the same installation (its first check reads secrets/).
  rc=0
  ./smoke.sh >"$work/forbidden-smoke.txt" 2>&1 || rc=$?
  [[ $rc -eq 1 ]] || { cat "$work/forbidden-smoke.txt" >&2; fail "smoke.sh with a forbidden password in secrets/: exit $rc, expected 1"; }
  grep -q 'secrets/ch_reader_password holds a forbidden default password' "$work/forbidden-smoke.txt" \
    || { cat "$work/forbidden-smoke.txt" >&2; fail "smoke.sh did not name the forbidden password file"; }
  rm -f "$work/forbidden-smoke.txt"
  log "ok: smoke.sh fails (exit 1) on the forbidden password in secrets/ch_reader_password"
  timing "unattended: forbidden password refusal" $(($(now) - t0))
}

for mode in $MODES; do
  log "=== mode: $mode"
  case $mode in
    wizard)
      prepare wizard
      up wizard
      (umask 077 && printf '%s' "$(head -c 18 /dev/urandom | base64 | tr -d '\n=' | tr '+/' '-_')" >"$work/admin-password")
      run_smoke wizard --complete-setup --admin-password-file "$work/admin-password"
      sample_stats "wizard after smoke"
      wizard_extra
      ;;
    unattended)
      prepare unattended --unattended --ip-mode masked
      up unattended
      run_smoke unattended --check-admin-login
      sample_stats "unattended after smoke"
      unattended_extra
      ;;
    *) fail "unknown mode $mode" ;;
  esac
  t0=$(now)
  ./argos-compose.sh down --volumes >/dev/null
  timing "$mode: argos-compose.sh down --volumes" $(($(now) - t0))
  cd "$work"
  rm -rf "${work:?}/$mode"
  current_dir=
done

log "=== summary (shared laptop: reference values only, INVALID as measurements)"
printf '  %s\n' "${timings[@]}"
log "docker stats samples (name, memory usage / limit, CPU):"
printf '  %s\n' "${stats[@]}"
log "total $(($(now) - started)) s"
log "PASSED"
