#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end run of the demo profile (WO-41; docs/17 section 3.9, N-16, O-A01; T-NFR-16 and the
# proposed T-NFR-16e, T-SEC-74 and T-SEC-75 demo variants). Run by ./gradlew :deploy:demoSmoke, which
# builds the server image and the two demo images first. Development and CI only, never shipped;
# needs Docker, curl and Node.js (JSON of the API) and about 7 GiB of memory for Docker.
#
# In a fresh copy of deploy/ (project argos-wo41-demo):
#   argos-env-init.sh --demo --ram-mb 7396 (the smallest demo plan: a small stack for a shared
#   machine) -> t0 -> `docker compose --profile demo up -d` exactly as the README says (the files
#   and the profile come from the .env of --demo) -> sign in as the administrator with
#   secrets/admin_password and make the forced password change -> poll GET /api/v1/apps/{id}/xlog
#   until the first point of order-api = t1 (T-NFR-16, report build/reports/demo/first-xlog.md;
#   --enforce fails above 60 s, the default only records: a shared laptop is no measuring machine)
#   -> T-NFR-16e: payment-api points, a trace with order-api and payment-api transactions, an N+1
#   transaction with sql_count 21, slow and error points, a BATCH transaction within 150 s of t0,
#   the recommended alert rules of both applications, non-empty service, SQL and error statistics,
#   setup/status demo_mode=true -> smoke.sh (files, ports, images, network, user, health, actuator)
#   -> T-SEC-74/75 demo: published ports exactly 127.0.0.1:8080 and 127.0.0.1:9100, the demo
#   containers run as uid 10001, no token text in .env, the container logs, `docker inspect` of the
#   containers or the history and configuration of the images -> with --long (about 9 more minutes)
#   the order-api error_rate alert FIRING -> `docker compose --profile demo down -v` and no
#   container, volume or network of the project left.
#
# Options and environment:
#   --enforce, ARGOS_DEMO_ENFORCE=1   fail when the first XLog point takes more than 60 s
#   --long, ARGOS_DEMO_LONG=1         also wait for the order-api error_rate alert (FIRING)
#   ARGOS_DEMO_KEEP=1                 leave the demo running at the end and print the URL and the
#                                     file with the changed administrator password (screenshots)
#   ARGOS_DEMO_REPORT_DIR             report directory (default <repository>/build/reports/demo)
#   ARGOS_DEMO_RAM_MB                 --ram-mb of argos-env-init.sh (default 7396)
#   ARGOS_LOCK_WAIT_SECONDS           bounded wait for the server-it lock (default 1800)
# The whole run holds the working tree's server-it lock (test/lock/argos-lock.sh run, ADR-0010
# C-235 (8)): the script re-executes itself under it, or uses the lock of an enclosing
# `argos-lock.sh run server-it` (around ./gradlew :deploy:demoSmoke, whose image build rebuilds the
# server boot jar before this script starts). Uses host ports 8080 and 9100 (127.0.0.1).
# On a Linux Docker Engine run it as root (argos-env-init.sh gives the secrets to uid 10001).
# Nothing secret is printed: tokens appear as argos_****, passwords never.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [[ "${ARGOS_DEMO_UNDER_LOCK:-}" != 1 ]]; then
  ARGOS_DEMO_UNDER_LOCK=1 exec bash "$here/../../../test/lock/argos-lock.sh" run server-it \
    --purpose "deploy/compose/test/demo-smoke.sh (:deploy:demoSmoke)" -- bash "$here/demo-smoke.sh" "$@"
fi
# shellcheck source=deploy/compose/test/lib.sh
source "$here/lib.sh"

PROJECT=argos-wo41-demo
ROOT_DIR=$(cd "$DEPLOY_DIR/.." && pwd)
REPORT_DIR=${ARGOS_DEMO_REPORT_DIR:-$ROOT_DIR/build/reports/demo}
RAM_MB=${ARGOS_DEMO_RAM_MB:-7396}
BASE=http://127.0.0.1:8080
ORIGIN=http://localhost:8080
FIRST_XLOG_LIMIT_S=60
BATCH_LIMIT_S=150
ENFORCE=${ARGOS_DEMO_ENFORCE:-0}
LONG=${ARGOS_DEMO_LONG:-0}
KEEP=${ARGOS_DEMO_KEEP:-0}
while [[ $# -gt 0 ]]; do
  case $1 in
    --enforce) ENFORCE=1; shift ;;
    --long) LONG=1; shift ;;
    -h | --help) sed -n '/^# In a fresh copy/,/^set -euo/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "demo-smoke: unknown option $1" >&2; exit 2 ;;
  esac
done

docker info >/dev/null 2>&1 || { echo "demo-smoke: Docker is not available" >&2; exit 2; }
command -v node >/dev/null 2>&1 || { echo "demo-smoke: Node.js is needed to read the API's JSON" >&2; exit 2; }
command -v curl >/dev/null 2>&1 || { echo "demo-smoke: curl is not installed" >&2; exit 2; }

log() { printf '[demo-smoke %s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
# lib.sh sets MSYS_NO_PATHCONV for docker under Git Bash; curl and node need the usual conversion of
# their file arguments there (no effect elsewhere).
curl() { env -u MSYS_NO_PATHCONV curl "$@"; }
fail() {
  printf '[demo-smoke] FAIL %s\n' "$*" >&2
  exit 1
}
redact() { sed -E 's/argos_[A-Za-z0-9_-]{43}/argos_****/g'; }
now() { date +%s; }
now_ms() { date +%s%3N; }
iso() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }
timings=()
timing() { timings+=("$1: $2"); log "$1: $2"; }
results=()
ok() { results+=("ok: $*"); log "ok: $*"; }

# js <function body>: the JSON on standard input as d; prints what the body returns (nothing for
# undefined or null). Used to read the API's answers without jq.
js() {
  env -u MSYS_NO_PATHCONV node -e '
    let s = "";
    process.stdin.on("data", (c) => (s += c)).on("end", () => {
      let d;
      try { d = JSON.parse(s); } catch (e) { process.exit(3); }
      const r = new Function("d", process.argv[1])(d);
      if (r !== undefined && r !== null) process.stdout.write(String(r));
    });' "$1"
}

work=$(mktemp -d "${TMPDIR:-/tmp}/argos-wo41-demo.XXXXXX")
chmod 0700 "$work"
demo_dir=
down_done=false
started=$(now)

dc() { (cd "$demo_dir" && docker compose "$@"); }

cleanup() {
  local rc=$?
  rm -f "$work/session.cfg" "$work/cookies" "$work/headers" "$work/body" "$work/request" "$work/.patterns"
  if [[ "$KEEP" == 1 && -n "$demo_dir" && "$down_done" != true ]]; then
    log "ARGOS_DEMO_KEEP=1: the demo keeps running (exit $rc)."
    log "  UI:        $ORIGIN  (user admin; its new password is in $work/admin-new-password)"
    log "  remove it: cd $demo_dir && COMPOSE_PROJECT_NAME=$PROJECT docker compose --profile demo down -v && rm -rf $work"
    return
  fi
  if [[ -n "$demo_dir" && -d "$demo_dir" && "$down_done" != true ]]; then
    if [[ $rc -ne 0 ]]; then
      log "logs of the failed demo (last 40 lines per service; tokens redacted):"
      (dc ps --all 2>&1
        for svc in server sample-app-a sample-app-b load-generator demo-db; do
          dc logs --no-color --tail 40 "$svc" 2>&1
        done) | redact | cut -c1-600 || true
    fi
    dc down -v >/dev/null 2>&1 || true
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

# --- the images (built by the Gradle task) --------------------------------------------------------
image_default() { # image_default <file> <variable>: the default of "image: ${<variable>:-<default>}"
  awk -v v="$2" '{ i = index($0, "${" v ":-"); if (i > 0) { s = substr($0, i + length(v) + 4); print substr(s, 1, index(s, "}") - 1); exit } }' "$1"
}
SERVER_IMAGE=$(image_default "$COMPOSE_DIR/compose.yaml" ARGOS_SERVER_IMAGE)
APP_IMAGE=$(image_default "$COMPOSE_DIR/compose.demo.yaml" ARGOS_DEMO_APP_IMAGE)
LOAD_IMAGE=$(image_default "$COMPOSE_DIR/compose.demo.yaml" ARGOS_DEMO_LOAD_IMAGE)
for image in "$SERVER_IMAGE" "$APP_IMAGE" "$LOAD_IMAGE"; do
  docker image inspect "$image" >/dev/null 2>&1 \
    || fail "image $image is missing: ./gradlew :server:jibDockerBuild :sample-apps:demo-app:jibDockerBuild :sample-apps:load:jibDockerBuild"
done

# --- a fresh copy with the layout of deploy/ and argos-env-init.sh --demo -------------------------
mkdir -p "$work/deploy/clickhouse" "$work/deploy/postgres"
cp -R "$COMPOSE_DIR" "$work/deploy/compose"
rm -rf "$work/deploy/compose/.env" "$work/deploy/compose/secrets" "$work/deploy/compose/tls" \
  "$work/deploy/compose/test" "$work/deploy/compose/compose.override.yaml"
cp -R "$DEPLOY_DIR/clickhouse/config.d" "$DEPLOY_DIR/clickhouse/users.d" "$DEPLOY_DIR/clickhouse/image.properties" "$work/deploy/clickhouse/"
cp "$DEPLOY_DIR/postgres/roles.sql" "$DEPLOY_DIR/postgres/image.properties" "$work/deploy/postgres/"
demo_dir=$work/deploy/compose
cd "$demo_dir"
# Plain docker compose reads the project name from the environment first, then the files; the
# files and the profile must come from the .env of --demo, not from this shell.
export COMPOSE_PROJECT_NAME=$PROJECT
unset COMPOSE_FILE COMPOSE_PROFILES COMPOSE_PATH_SEPARATOR ARGOS_DEMO_MODE ARGOS_MGMT_EXPOSE || true

t=$(now)
./argos-env-init.sh --demo --ram-mb "$RAM_MB" >"$work/env-init.out" 2>&1 || { cat "$work/env-init.out" >&2; fail "argos-env-init.sh --demo failed"; }
timing "argos-env-init.sh --demo" "$(($(now) - t)) s"
grep -q '^ARGOS_DEMO_MODE=true$' .env || fail ".env of --demo has no ARGOS_DEMO_MODE=true"

# The secret files stay as argos-env-init.sh left them, as in the demo quick start (README): on a
# Linux Docker Engine env-init gives them to uid 10001 with mode 0600; on Docker Desktop they show
# as 0777 inside the containers and the demo overlay's ARGOS_SECURITY_MASTERKEY_PERMISSIVE lets
# the server start (one WARN per start, ADR-0010 C-243 (1)). No manual chmod step.

# Every token and password of this run, for the leak checks (a private file, deleted at the end).
patterns=$work/.patterns
: >"$patterns"
chmod 0600 "$patterns"
for f in secrets/*; do
  [[ "$f" == secrets/clickhouse.env ]] && continue
  head -n 1 "$f" >>"$patterns"
  printf '\n' >>"$patterns"
done
sed -i '/^$/d' "$patterns"

# --- T-NFR-16: docker compose --profile demo up -d -> first XLog point via the API -----------------
t0=$(now)
t0_ms=$(now_ms)
log "docker compose --profile demo up -d (t0 = $(iso "$t0"))"
docker compose --profile demo up -d >"$work/up.out" 2>&1 || { cat "$work/up.out" >&2; fail "docker compose --profile demo up -d failed"; }
up_s=$((($(now_ms) - t0_ms) / 1000))
timing "docker compose --profile demo up -d returned" "${up_s} s"

# api <method> <path> [request file]: the status in code, the body in $work/body, the headers in
# $work/headers. The session (cookies and CSRF header) comes from $work/session.cfg once signed in.
code=
api() {
  local args=(-s -o "$work/body" -D "$work/headers" -w '%{http_code}' --max-time 20 -X "$1"
    -H 'Accept: application/json' -H "Origin: $ORIGIN")
  [[ -f "$work/session.cfg" ]] && args+=(--config "$work/session.cfg")
  if [[ -n "${3:-}" ]]; then
    args+=(-H 'Content-Type: application/json' --data-binary "@$3")
  fi
  code=$(curl "${args[@]}" "$BASE$2" 2>/dev/null || true)
}
# save_session: the session and CSRF cookies of the last answer as request headers (the cookies are
# Secure; curl keeps those for https only, so they are passed by hand as compose-smoke.sh does).
save_session() {
  local cookies csrf
  # The cookies set now replace those of the same name kept from earlier answers ($work/cookies,
  # one name=value per line); an emptied cookie is dropped.
  (umask 077 && touch "$work/cookies")
  sed -n 's/^[Ss][Ee][Tt]-[Cc][Oo][Oo][Kk][Ii][Ee]: *\([^;]*\).*$/\1/p' "$work/headers" | tr -d '\r' >"$work/cookies.new"
  awk -F= 'NR == FNR { seen[$1] = 1; if (substr($0, length($1) + 2) != "") print; next } !($1 in seen)' \
    "$work/cookies.new" "$work/cookies" >"$work/cookies.merged"
  mv -f "$work/cookies.merged" "$work/cookies"
  rm -f "$work/cookies.new"
  cookies=$(awk 'BEGIN { ORS = "" } { if (NR > 1) print "; "; print }' "$work/cookies")
  csrf=$(sed -n 's/^argos_csrf=//p' "$work/cookies" | head -n 1)
  [[ -n "$cookies" && -n "$csrf" ]] || fail "no session or CSRF cookie after $(head -n 1 "$work/headers" | tr -d '\r')"
  (umask 077 && printf 'header = "Cookie: %s"\nheader = "X-Argos-CSRF: %s"\n' "$cookies" "$csrf" >"$work/session.cfg")
}
# private_json <file> <printf format> <values...>: a request body only this user can read.
private_json() {
  local f=$1 fmt=$2
  shift 2
  # shellcheck disable=SC2059 # the format is the caller's fixed JSON template
  (umask 077 && printf "$fmt" "$@" >"$f")
}
rand_password() {
  local p
  while :; do
    p=$(head -c 24 /dev/urandom | base64 | tr -d '\n=' | tr '+/' '-_' | cut -c1-24)
    case $(printf '%s' "$p" | tr '[:upper:]' '[:lower:]') in *argos* | *admin*) continue ;; esac
    printf '%s' "$p"
    return
  done
}

deadline=$((t0 + 600))
while :; do
  api GET /api/v1/setup/status
  [[ "$code" == 200 ]] && break
  [[ $(now) -lt $deadline ]] || fail "GET /api/v1/setup/status did not answer within 600 s (last $code)"
  sleep 1
done
timing "server answers GET /api/v1/setup/status" "$(($(now) - t0)) s after t0"
demo_mode=$(js 'return d.data ? d.data.demo_mode : d.demo_mode' <"$work/body" || true)
[[ "$demo_mode" == true ]] || fail "GET /api/v1/setup/status: demo_mode is '${demo_mode:-missing}', expected true"
ok "GET /api/v1/setup/status says demo_mode=true (the UI shows the demo banner)"

# Sign in with secrets/admin_password; the password must be changed first (16 section 7.9).
private_json "$work/request" '{"username":"admin","password":"%s"}' "$(head -n 1 secrets/admin_password)"
api POST /api/v1/auth/login "$work/request"
rm -f "$work/request"
[[ "$code" == 200 ]] || fail "sign-in with secrets/admin_password: HTTP $code"
[[ "$(js 'return d.data ? d.data.must_change_password : d.must_change_password' <"$work/body")" == true ]] \
  || fail "the administrator of the demo is not asked to change the password"
save_session
new_password=$(rand_password)
(umask 077 && printf '%s\n' "$new_password" >"$work/admin-new-password")
printf '%s\n' "$new_password" >>"$patterns"
private_json "$work/request" '{"current":"%s","new":"%s"}' "$(head -n 1 secrets/admin_password)" "$new_password"
new_password=
api PUT /api/v1/auth/password "$work/request"
rm -f "$work/request"
[[ "$code" == 204 ]] || fail "the forced password change: HTTP $code $(head -c 300 "$work/body")"
save_session
ok "signed in with secrets/admin_password and made the forced password change"

# app_id <name>: the id of the application in the environment demo.
app_id() {
  api GET "/api/v1/apps?env=demo"
  [[ "$code" == 200 ]] || return 0
  js "const a = d.data.find((x) => x.name === '$1' && x.env === 'demo'); return a ? a.app_id : null" <"$work/body"
}
# xlog_total <app id> [query]: points of the last 15 minutes (and up to a minute ahead).
xlog_total() {
  local from to
  from=$(iso $(($(now) - 900)))
  to=$(iso $(($(now) + 60)))
  api GET "/api/v1/apps/$1/xlog?from=$from&to=$to${2:+&$2}"
  [[ "$code" == 200 ]] || { echo 0; return 0; }
  js 'return d.data.tx_id.length' <"$work/body" || echo 0
}

order_id=
deadline=$((t0 + 600))
while :; do
  [[ -n "$order_id" ]] || order_id=$(app_id order-api)
  if [[ -n "$order_id" ]] && [[ "$(xlog_total "$order_id")" -ge 1 ]]; then
    break
  fi
  [[ $(now) -lt $deadline ]] || fail "no XLog point of order-api within 600 s"
  sleep 1
done
t1_ms=$(now_ms)
first_ts=$(js 'return Math.min(...d.data.ts)' <"$work/body" || true)
first_s=$(((t1_ms - t0_ms + 500) / 1000))
timing "T-NFR-16 first XLog point of order-api through the API" "${first_s} s after t0"

mkdir -p "$REPORT_DIR"
verdict="recorded only (shared machine: INVALID as a measurement; the 60 s verdict needs --enforce on a quiet machine)"
if [[ "$ENFORCE" == 1 ]]; then
  if [[ $first_s -le $FIRST_XLOG_LIMIT_S ]]; then verdict="PASS (--enforce, <= ${FIRST_XLOG_LIMIT_S} s)"; else verdict="FAIL (--enforce, > ${FIRST_XLOG_LIMIT_S} s)"; fi
fi
{
  printf '# T-NFR-16: demo profile, first XLog point\n\n'
  printf 'Written by deploy/compose/test/demo-smoke.sh (./gradlew :deploy:demoSmoke).\n\n'
  printf '| Item | Value |\n|---|---|\n'
  # shellcheck disable=SC2016 # Markdown code spans, not expansions
  printf '| t0 (`docker compose --profile demo up -d` started, UTC) | %s |\n' "$(iso "$t0")"
  # shellcheck disable=SC2016 # Markdown code spans, not expansions
  printf '| `up -d` returned after | %s s |\n' "$up_s"
  # shellcheck disable=SC2016 # Markdown code spans, not expansions
  printf '| first order-api point seen through `GET /api/v1/apps/{id}/xlog` after | **%s s** |\n' "$first_s"
  if [[ -n "$first_ts" ]]; then
    printf '| start of that transaction after t0 | %s s |\n' "$(((first_ts - t0_ms) / 1000))"
  fi
  printf '| criterion (N-16) | <= %s s, images already in the local store |\n' "$FIRST_XLOG_LIMIT_S"
  printf '| verdict | %s |\n' "$verdict"
  printf '| memory plan | argos-env-init.sh --demo --ram-mb %s |\n' "$RAM_MB"
  printf '| Docker | %s |\n' "$(docker info --format '{{.OperatingSystem}}, {{.NCPU}} CPUs, {{.MemTotal}} bytes' 2>/dev/null || echo unknown)"
  printf '| images | %s, %s, %s |\n' "$SERVER_IMAGE" "$APP_IMAGE" "$LOAD_IMAGE"
  printf '\nThe time includes the server start (migrations of new volumes), the start of both demo\n'
  printf 'applications with the agent, the first requests of the load generator and the ingest path.\n'
} >"$REPORT_DIR/first-xlog.md"
log "report: $REPORT_DIR/first-xlog.md"
if [[ "$ENFORCE" == 1 && $first_s -gt $FIRST_XLOG_LIMIT_S ]]; then
  fail "T-NFR-16: the first XLog point took ${first_s} s (> ${FIRST_XLOG_LIMIT_S} s)"
fi

# --- T-NFR-16e: what the demo shows -------------------------------------------------------------
payment_id=$(app_id payment-api)
[[ -n "$payment_id" ]] || fail "payment-api is not registered"
ok "order-api (app $order_id) and payment-api (app $payment_id) are registered in the environment demo"

# wait_for <seconds after now> <description> <command...>: polls every 3 s.
wait_for() {
  local limit=$(($(now) + $1)) desc=$2
  shift 2
  while ! "$@"; do
    [[ $(now) -lt $limit ]] || fail "$desc: not within $(($(now) - started)) s of the start of this run"
    sleep 3
  done
}
has_points() { [[ "$(xlog_total "$1" "${2:-}")" -ge 1 ]]; }
wait_for 120 "payment-api XLog points" has_points "$payment_id"
ok "payment-api has XLog points"
wait_for 120 "a slow order-api point (>= 300 ms)" has_points "$order_id" "min_ms=300"
ok "slow order-api points (>= 300 ms)"
wait_for 120 "an order-api error point" has_points "$order_id" "error=true"
ok "order-api error points"
instances_up() {
  api GET "/api/v1/apps?env=demo"
  [[ "$(js "return d.data.filter((a) => (a.name === 'order-api' || a.name === 'payment-api') && a.instances_up >= 1).length" <"$work/body")" == 2 ]]
}
wait_for 60 "an UP instance of each demo application" instances_up
ok "each demo application has an instance UP"

# search <app id>: the transactions of the last 15 minutes (newest first, 1,000 rows) in $work/body.
search() {
  private_json "$work/request" '{"from":"%s","to":"%s","limit":1000,"sort":"ts:desc"}' "$(iso $(($(now) - 900)))" "$(iso $(($(now) + 60)))"
  api POST "/api/v1/apps/$1/transactions/search" "$work/request"
  rm -f "$work/request"
  [[ "$code" == 200 ]]
}
n_plus_one() {
  search "$order_id" || return 1
  [[ -n "$(js "const r = d.data.find((x) => x.sql_count === 21 && /items/.test(x.service || '')); return r ? r.tx_id : null" <"$work/body")" ]]
}
wait_for 120 "an order-api N+1 transaction with sql_count 21" n_plus_one
ok "an N+1 transaction of order-api with sql_count 21 (1 + 20)"

trace_apps=
checkout_trace() {
  local row tx ts trace
  search "$order_id" || return 1
  row=$(js "const r = d.data.find((x) => /checkout/.test(x.service || '')); return r ? r.tx_id + ' ' + r.ts : null" <"$work/body")
  [[ -n "$row" ]] || return 1
  tx=${row%% *}
  ts=${row#* }
  api GET "/api/v1/transactions/$tx?ts=$ts&app=$order_id"
  [[ "$code" == 200 ]] || return 1
  trace=$(js 'return d.data.trace_id' <"$work/body")
  [[ -n "$trace" ]] || return 1
  api GET "/api/v1/traces/$trace?ts=$ts"
  [[ "$code" == 200 ]] || return 1
  trace_apps=$(js 'return [...new Set(d.data.nodes.map((n) => n.app))].sort().join(" ")' <"$work/body")
  [[ "$trace_apps" == "order-api payment-api" ]]
}
wait_for 120 "a checkout trace with transactions of order-api and payment-api" checkout_trace
ok "a checkout trace holds transactions of order-api and payment-api (distributed call)"

batch_points() { [[ "$(xlog_total "$order_id" kind=BATCH)" -ge 1 || "$(xlog_total "$payment_id" kind=BATCH)" -ge 1 ]]; }
wait_for $((t0 + BATCH_LIMIT_S - $(now))) "a BATCH transaction within ${BATCH_LIMIT_S} s of t0" batch_points
timing "first BATCH transaction (the @Scheduled settlement) seen" "$(($(now) - t0)) s after t0"
ok "a BATCH transaction (@Scheduled) within ${BATCH_LIMIT_S} s of t0"

rules_for() { # rules_for <app id>: the server's template job applied the recommended rules
  api GET "/api/v1/apps/$1"
  [[ "$code" == 200 && "$(js 'return d.data.recommended_rules_applied' <"$work/body")" == true ]] || return 1
  api GET "/api/v1/alerts/rules?app=$1"
  [[ "$code" == 200 && "$(js 'return d.data.filter((r) => r.template_key).length' <"$work/body")" -ge 1 ]]
}
wait_for 150 "the recommended alert rules of order-api" rules_for "$order_id"
wait_for 60 "the recommended alert rules of payment-api" rules_for "$payment_id"
ok "the recommended alert rules exist for both demo applications"

stats_rows() { # stats_rows <path>: the statistics of the last 15 minutes are not empty
  api GET "$1?from=$(iso $(($(now) - 900)))&to=$(iso $(($(now) + 60)))"
  [[ "$code" == 200 && "$(js 'return d.data.length' <"$work/body" || echo 0)" -ge 1 ]]
}
wait_for 180 "service statistics of order-api" stats_rows "/api/v1/apps/$order_id/services/stats"
wait_for 120 "SQL statistics of order-api" stats_rows "/api/v1/apps/$order_id/sql/stats"
wait_for 120 "error groups of order-api" stats_rows "/api/v1/apps/$order_id/errors/groups"
ok "service, SQL and error statistics of order-api are not empty"

# --- installation state and the security checks (T-SEC-74, T-SEC-75 in demo mode) ----------------
./smoke.sh --only files,ports,images,network,user,health,actuator >"$work/smoke.out" 2>&1 \
  || { cat "$work/smoke.out" >&2; fail "smoke.sh failed on the demo installation"; }
ok "smoke.sh (files, ports, images, network, user, health, actuator) passes on the demo installation"

ports=$(dc ps --format '{{.Service}} {{.Ports}}' | tr -d '\r')
published=$(grep -oE '[0-9.:]+:[0-9]+->[0-9]+/tcp' <<<"$ports" | sort -u | tr '\n' ' ')
[[ "$published" == "127.0.0.1:8080->8080/tcp 127.0.0.1:9100->9100/tcp " ]] \
  || fail "published ports: '$published', expected exactly 127.0.0.1:8080 and 127.0.0.1:9100"
ok "T-SEC-75: the published ports are exactly 127.0.0.1:8080 and 127.0.0.1:9100; no demo service publishes one"

for svc in sample-app-a sample-app-b load-generator; do
  ids=$(dc exec -T "$svc" sh -c 'grep -E "^(Uid|Gid):" /proc/1/status' | awk '{ print $2, $3, $4, $5 }' | tr -d '\r' | tr '\n' ' ')
  [[ "$ids" == "10001 10001 10001 10001 10001 10001 10001 10001 " ]] || fail "$svc runs as uid/gid '$ids', expected 10001"
  nets=$(docker inspect --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$(dc ps -q "$svc")")
  [[ "$nets" == "${PROJECT}_back " ]] || fail "$svc is attached to '$nets', expected ${PROJECT}_back only"
done
ok "the demo applications and the load generator run as uid/gid 10001 on the internal network only"

leaks() { # leaks <description> <file>: no token or password of this run in the file
  if grep -qF -f "$patterns" "$2"; then
    fail "T-SEC-74: a token or password of the demo is in $1"
  fi
}
leaks ".env" .env
dc logs --no-color >"$work/logs.txt" 2>&1 || true
leaks "the container logs" "$work/logs.txt"
# shellcheck disable=SC2046 # container ids: one word each, split on purpose
docker inspect $(dc ps -aq) >"$work/inspect.txt" 2>&1
leaks "docker inspect of the containers (environment, labels, command)" "$work/inspect.txt"
for image in "$SERVER_IMAGE" "$APP_IMAGE" "$LOAD_IMAGE"; do
  { docker image history --no-trunc "$image"; docker image inspect "$image"; } >"$work/image.txt" 2>&1
  leaks "the history or configuration of $image" "$work/image.txt"
done
rm -f "$work/logs.txt" "$work/inspect.txt" "$work/image.txt"
ok "T-SEC-74: no token or password in .env, the container logs, docker inspect of the containers or the images"

# --- --long: the order-api error_rate alert fires (warm-up 3 min + pending 5 min) ----------------
if [[ "$LONG" == 1 ]]; then
  firing() {
    api GET "/api/v1/alerts/events?from=$(iso "$t0")&to=$(iso $(($(now) + 60)))&app=$order_id&state=FIRING"
    [[ "$code" == 200 && -n "$(js "const e = d.data.find((x) => JSON.stringify(x.rule || {}).includes('error_rate')); return e ? e.event_id : null" <"$work/body")" ]]
  }
  wait_for $((t0 + 900 - $(now))) "the order-api error_rate alert FIRING within 15 minutes of t0" firing
  timing "order-api error_rate alert FIRING" "$(($(now) - t0)) s after t0"
  ok "the order-api error_rate alert fires (recommended rule, payment-api failures)"
fi

if [[ "$KEEP" == 1 ]]; then
  log "=== summary (shared laptop: reference values only, INVALID as measurements)"
  printf '  %s\n' "${timings[@]}" "${results[@]}"
  log "PASSED (the demo keeps running)"
  exit 0
fi

# --- docker compose --profile demo down -v removes everything ----------------------------------
t=$(now)
docker compose --profile demo down -v >"$work/down.out" 2>&1 || { cat "$work/down.out" >&2; fail "docker compose --profile demo down -v failed"; }
down_done=true
timing "docker compose --profile demo down -v" "$(($(now) - t)) s"
left=$( (docker ps -aq --filter "label=com.docker.compose.project=$PROJECT"
  docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT"
  docker network ls -q --filter "label=com.docker.compose.project=$PROJECT") | wc -l | tr -d ' ')
[[ "$left" == 0 ]] || fail "after down -v $left containers, volumes or networks of $PROJECT are left"
ok "docker compose --profile demo down -v leaves no container, volume or network of the project"

log "=== summary (shared laptop: reference values only, INVALID as measurements)"
printf '  %s\n' "${timings[@]}" "${results[@]}"
log "total $(($(now) - started)) s"
log "PASSED"
