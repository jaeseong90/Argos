#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# ops-report.sh: read-only daily check of the Docker Compose installation of Argos (WO-42). It
# collects the values of the daily operating checklist (docs/dogfooding/ops-checklist.md) and
# prints them as one Markdown snippet for the operating log (docs/dogfooding/
# operating-log-template.md): the containers, the nightly consistency check of a day
# (reconcile_results, argos_reconcile_mismatch_minutes), ingest lag, drops, disk, alerts, instances,
# certificate and token expiry, and the newest backup set.
#
# It changes nothing: no container is started, stopped or restarted, no check is re-run and no row
# is written. It reads PostgreSQL as argos_backup (SELECT only, in a read-only transaction) inside
# the postgres container, /actuator/prometheus of the server on 127.0.0.1:8081 inside the server
# container (the bearer token of /run/secrets/metrics_token goes to curl through its standard
# input, never into an argument), `df -P` in the clickhouse container and the manifest.json of the
# backup sets. Passwords and tokens stay inside the containers. Nothing is sent anywhere: the
# result goes to standard output only. It prints numbers, states and application ids, never
# application, instance or host names, addresses, tokens or passwords.
#
# Usage: ./ops-report.sh [options]
#   --date YYYY-MM-DD    the UTC day whose nightly consistency check is reported (default:
#                        yesterday in UTC, the day the nightly job checks; run the report after the
#                        job, argos.ops.reconcile.cron, default 03:00 server time)
#   --since TIME         start of the observation window in UTC, YYYY-MM-DDTHH:MM:SSZ: a server
#                        start after it is reported as a restart (Gate 1: 2 weeks without one)
#   --backup-dir DIR     directory of the backup sets (default: ARGOS_BACKUP_DIR of .env, else
#                        ./backups, the same rule as backup.sh)
#   -h, --help           show this text
#
# Run it in deploy/compose of the installation, as the user that runs ./argos-compose.sh (root on a
# Linux Docker Engine: the backup directory is 0700).
#
# Exit codes: 0 every section was collected (findings are listed under "Checks to act on"); 1 a
# section could not be collected (it is marked FAILED, the other sections are still printed);
# 2 missing prerequisite or usage error (nothing was read).
set -u
umask 077
# Git Bash on Windows: keep MSYS from rewriting container paths in arguments.
MSYS_NO_PATHCONV=1
export MSYS_NO_PATHCONV

prog=ops-report.sh
caller_dir=$(pwd)
here=$(cd "$(dirname "$0")" && pwd)

# Thresholds of the daily checklist (docs/dogfooding/ops-checklist.md).
LAG_LIMIT_S=5
DISK_WARN_RATIO=0.80
CH_DISK_WARN_PERCENT=80
BACKUP_MAX_AGE_H=26
EXPIRY_WARN_DAYS=30
# Marker argument of the container shells: it names the caller in `ps` of the containers.
MARK=argos-ops-report

die() {
  code=$1
  shift
  printf '%s: %s\n' "$prog" "$*" >&2
  exit "$code"
}
usage() { sed -n '/^# Usage:/,/^# Exit codes:/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'; }

day=
since=
backup_dir=
while [ $# -gt 0 ]; do
  case $1 in
    --date) [ $# -ge 2 ] || die 2 "--date needs a day such as 2026-11-25"; day=$2; shift 2 ;;
    --date=*) day=${1#*=}; shift ;;
    --since) [ $# -ge 2 ] || die 2 "--since needs a time such as 2026-11-12T00:00:00Z"; since=$2; shift 2 ;;
    --since=*) since=${1#*=}; shift ;;
    --backup-dir) [ $# -ge 2 ] || die 2 "--backup-dir needs a directory"; backup_dir=$2; shift 2 ;;
    --backup-dir=*) backup_dir=${1#*=}; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die 2 "unknown option: $1 (see --help)" ;;
  esac
done
case $day in
  '') ;;
  [12][0-9][0-9][0-9]-0[1-9]-[0-2][0-9] | [12][0-9][0-9][0-9]-1[0-2]-[0-2][0-9]) ;;
  [12][0-9][0-9][0-9]-0[1-9]-3[01] | [12][0-9][0-9][0-9]-1[0-2]-3[01]) ;;
  *) die 2 "--date must be a day YYYY-MM-DD (UTC), for example 2026-11-25" ;;
esac
case $day in *-00) die 2 "--date must be a day YYYY-MM-DD (UTC), for example 2026-11-25" ;; esac
case $since in
  '') ;;
  [12][0-9][0-9][0-9]-[01][0-9]-[0-3][0-9]T[0-2][0-9]:[0-5][0-9]:[0-5][0-9]Z) ;;
  *) die 2 "--since must be a UTC time YYYY-MM-DDTHH:MM:SSZ, for example 2026-11-12T00:00:00Z" ;;
esac
if [ -n "$backup_dir" ]; then
  case $backup_dir in
    /* | [A-Za-z]:*) ;;
    *) backup_dir=$caller_dir/$backup_dir ;;
  esac
fi

cd "$here" || die 2 "cannot enter $here"
[ -f compose.yaml ] && [ -f argos-compose.sh ] || die 2 "run it in deploy/compose of an Argos installation"
command -v docker >/dev/null 2>&1 || die 2 "docker is not installed"
docker info >/dev/null 2>&1 || die 2 "the Docker daemon is not running or this user may not use it"

work=$(mktemp -d 2>/dev/null || mktemp -d -t argos-ops-report) || die 2 "cannot create a temporary directory"
trap 'rm -rf "$work"' EXIT
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

dc() { sh ./argos-compose.sh compose "$@"; }

# reason <file>: the last non-empty line of an error output, one line, without tokens or
# addresses, at most 160 characters (it goes into the report).
reason() {
  r=$(tr -d '\r' <"$1" 2>/dev/null | sed '/^[[:space:]]*$/d' | tail -n 1 \
    | sed -E -e 's/argos_[A-Za-z0-9_-]{4,}/argos_****/g' -e 's/[Bb]earer [^ ]+/Bearer ****/g' \
      -e 's/[0-9]{1,3}(\.[0-9]{1,3}){3}/<address>/g' -e 's/[|`]/ /g' | cut -c1-160)
  printf '%s' "${r:-no message}"
}

is_count() {
  case $1 in
    '' | *[!0-9]*) return 1 ;;
    *) return 0 ;;
  esac
}
# gt <a> <b>: a > b for decimal numbers.
gt() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a + 0 > b + 0) }'; }

sections=
failed=0
section_ok() { sections="$sections
| $1 | ok | $2 |"; }
section_failed() {
  failed=$((failed + 1))
  sections="$sections
| $1 | **FAILED** | $2 |"
}
findings=
finding_count=0
act() {
  finding_count=$((finding_count + 1))
  findings="$findings
- $*"
}

now_epoch=$(date -u +%s)
now_iso=$(date -u +%Y-%m-%dT%H:%M:%SZ)
today=$(date -u +%Y-%m-%d)

# ---------------------------------------------------------------------------------------------
# containers: state, health, start time and restart count (docker inspect)

services_table=
server_started=
server_restarts=
containers_error=
for svc in server clickhouse postgres; do
  if ! ids=$(dc ps --all -q "$svc" 2>"$work/ps.err"); then
    containers_error="$svc: docker compose ps: $(reason "$work/ps.err")"
    services_table="$services_table
| $svc | unknown | - | - | - |"
    continue
  fi
  cid=$(printf '%s\n' "$ids" | tr -d '\r' | sed '/^$/d' | head -n 1)
  if [ -z "$cid" ]; then
    services_table="$services_table
| $svc | no container | - | - | - |"
    act "$svc: no container. Start the stack (./argos-compose.sh up) and record the outage."
    continue
  fi
  if ! info=$(docker inspect --format '{{.State.Status}}|{{.State.StartedAt}}|{{.RestartCount}}' "$cid" 2>"$work/inspect.err"); then
    containers_error="$svc: $(reason "$work/inspect.err")"
    services_table="$services_table
| $svc | unknown | - | - | - |"
    continue
  fi
  health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$cid" 2>/dev/null | tr -d '\r')
  info=$(printf '%s' "$info" | tr -d '\r' | head -n 1)
  state=${info%%|*}
  rest=${info#*|}
  started=$(printf '%s' "${rest%%|*}" | cut -c1-19)
  restarts=${rest##*|}
  case $state in *[!a-z]* | '') state=unknown ;; esac
  case $health in *[!a-z]*) health=unknown ;; esac
  case $started in
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]) started=${started}Z ;;
    *) started=unknown ;;
  esac
  is_count "$restarts" || restarts=unknown
  services_table="$services_table
| $svc | $state | ${health:-none} | $started | $restarts |"
  if [ "$state" != running ]; then
    act "$svc is $state. See docs/17 section 12 (server OOM and restart) and record the outage."
  elif [ "${health:-}" != healthy ]; then
    act "$svc is ${health:-without a health check}, not healthy: ./argos-compose.sh status, ./argos-compose.sh logs $svc."
  fi
  if is_count "$restarts" && [ "$restarts" -gt 0 ]; then
    act "$svc was restarted $restarts time(s) by Docker (restart policy): a crash. Check ./argos-compose.sh logs $svc and dmesg."
  fi
  if [ "$svc" = server ]; then
    server_started=$started
    server_restarts=$restarts
  fi
done
if [ -n "$containers_error" ]; then
  section_failed containers "docker inspect: $containers_error"
else
  section_ok containers "docker compose ps, docker inspect"
fi

# The restart column of the daily row.
if [ -z "$server_started" ]; then
  restart_cell="unknown (no server container)"
elif [ -n "$since" ]; then
  if [ "$server_started" = unknown ]; then
    restart_cell="unknown start time"
  elif awk -v a="$server_started" -v b="$since" 'BEGIN { exit !(a > b) }'; then
    restart_cell="**restarted** at $server_started (after $since)"
    act "The server started at $server_started, after the window start $since: Gate 1 needs 2 weeks without a server restart; the 14 days start again."
  else
    restart_cell="0 since $since"
  fi
else
  restart_cell="up since $server_started, restart count ${server_restarts:-unknown}"
fi

# ---------------------------------------------------------------------------------------------
# PostgreSQL: consistency check, instances, alerts, channels, tokens (argos_backup, read-only)

if [ -n "$day" ]; then
  day_sql="DATE '$day'"
else
  day_sql="((now() AT TIME ZONE 'UTC')::date - 1)"
fi
ts_fmt="'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'"
cat >"$work/report.sql" <<EOF
BEGIN TRANSACTION READ ONLY;
SELECT 'day|' || to_char($day_sql, 'YYYY-MM-DD');
SELECT 'summary|' || mismatch_minutes || '|' || rebuild_result || '|' || CASE WHEN rebuilt THEN 'yes' ELSE 'no' END
  || '|' || to_char(checked_at AT TIME ZONE 'UTC', $ts_fmt) || '|' || coalesce(detail->>'trigger', '-')
  || '|' || coalesce(detail->>'apps_mismatching', '0') || '|' || coalesce(detail->>'d12_apps_mismatching', '0')
  || '|' || CASE WHEN jsonb_typeof(detail->'remaining_tables') = 'array' THEN jsonb_array_length(detail->'remaining_tables') ELSE 0 END
  FROM argos.reconcile_results WHERE day = $day_sql AND check_name = 'summary'
  ORDER BY checked_at DESC, id DESC LIMIT 1;
SELECT 'runs|' || count(*) FROM argos.reconcile_results WHERE day = $day_sql AND check_name = 'summary';
SELECT 'mismatch|' || coalesce(app_id::text, '-') || '|' || check_name || '|' || table_name || '|' || mismatch_minutes
  || '|' || rebuild_result
  FROM argos.reconcile_results
  WHERE day = $day_sql AND check_name <> 'summary' AND checked_at = (SELECT max(checked_at)
    FROM argos.reconcile_results WHERE day = $day_sql AND check_name = 'summary')
  ORDER BY mismatch_minutes DESC, app_id, check_name, table_name LIMIT 20;
SELECT 'instances|' || count(*) FILTER (WHERE status = 'UP') || '|' || count(*) FILTER (WHERE status = 'STALE')
  || '|' || count(*) FILTER (WHERE status = 'DOWN') || '|' || count(*) FILTER (WHERE 'drops' = ANY (warn_flags))
  || '|' || count(*) FILTER (WHERE 'module_disabled' = ANY (warn_flags))
  || '|' || count(*) FILTER (WHERE 'clock_skew' = ANY (warn_flags)) || '|' || count(DISTINCT app_id)
  || '|' || count(*) FILTER (WHERE status = 'WARN')
  FROM argos.instances WHERE archived_at IS NULL;
SELECT 'pending|' || count(*) FROM argos.pending_registrations;
SELECT 'alerts|' || count(*) FILTER (WHERE s.state = 'FIRING')
  || '|' || count(*) FILTER (WHERE s.state = 'FIRING' AND s.severity = 'critical')
  || '|' || count(*) FILTER (WHERE s.state = 'ACKED')
  FROM argos.alert_states s JOIN argos.alert_rules r ON r.id = s.rule_id WHERE r.deleted_at IS NULL;
SELECT 'firing|' || coalesce(s.app_id::text, 'server') || '|' || coalesce(r.template_key, 'custom')
  || '|' || coalesce(s.severity, '-') || '|' || coalesce(to_char(s.firing_since AT TIME ZONE 'UTC', $ts_fmt), '-')
  FROM argos.alert_states s JOIN argos.alert_rules r ON r.id = s.rule_id
  WHERE r.deleted_at IS NULL AND s.state = 'FIRING' ORDER BY s.firing_since, s.app_id LIMIT 20;
SELECT 'channels|' || (SELECT count(*) FROM argos.notification_deliveries
    WHERE status = 'failed' AND sent_at >= now() - interval '24 hours')
  || '|' || (SELECT count(*) FROM argos.notification_channels WHERE deleted_at IS NULL AND status = 'failing');
SELECT 'tokens|' || count(*) FILTER (WHERE expires_at < now() + interval '$EXPIRY_WARN_DAYS days')
  || '|' || count(*) FILTER (WHERE expires_at < now() + interval '7 days')
  FROM argos.agent_tokens WHERE status IN ('ACTIVE', 'GRACE');
COMMIT;
EOF

pg_rc=0
# shellcheck disable=SC2016 # the postgres container's shell expands it (the password stays there)
dc exec -T postgres sh -c 'PGPASSWORD=$(cat /run/secrets/pg_backup_password) psql -X -q -A -t -v ON_ERROR_STOP=1 -h 127.0.0.1 -U argos_backup -d argos -f -' "$MARK" \
  <"$work/report.sql" >"$work/pg.out" 2>"$work/pg.err" || pg_rc=$?
tr -d '\r' <"$work/pg.out" >"$work/pg.txt"

pg_field() { # pg_field <key>: the first line of that key without the key
  sed -n "s/^$1|//p" "$work/pg.txt" | head -n 1
}
pg_ok=false
if [ "$pg_rc" -ne 0 ]; then
  section_failed postgres "psql as argos_backup in the postgres container, exit code $pg_rc: $(reason "$work/pg.err")"
else
  checked_day=$(pg_field day)
  instances=$(pg_field instances)
  alerts=$(pg_field alerts)
  channels=$(pg_field channels)
  tokens=$(pg_field tokens)
  pending=$(pg_field pending)
  runs=$(pg_field runs)
  case $checked_day in
    [12][0-9][0-9][0-9]-[01][0-9]-[0-3][0-9]) ;;
    *) checked_day= ;;
  esac
  if [ -z "$checked_day" ] || [ -z "$instances" ] || [ -z "$alerts" ] || [ -z "$channels" ] \
    || [ -z "$tokens" ] || ! is_count "$pending" || ! is_count "$runs"; then
    section_failed postgres "unexpected psql output (is the schema older than this script?)"
  else
    pg_ok=true
    section_ok postgres "argos_backup, read-only transaction"
  fi
fi

# cq <value>: the value when it is a count, else "?".
cq() {
  if is_count "$1"; then printf '%s' "$1"; else printf '?'; fi
}

consistency_cell="FAILED (PostgreSQL)"
consistency_md="- Not collected: PostgreSQL could not be read."
if $pg_ok; then
  summary=$(pg_field summary)
  if [ -z "$summary" ]; then
    consistency_cell="**no run recorded** ($checked_day)"
    consistency_md="- Day checked: $checked_day (UTC). **No run is recorded for this day** in reconcile_results."
    act "No nightly consistency check is recorded for $checked_day: was the server down at the job time, the job off (argos.ops.reconcile.enabled) or is it before the job time? Run the check by hand (docs/dogfooding/ops-checklist.md) and record it."
  else
    old_ifs=$IFS
    IFS='|'
    # shellcheck disable=SC2086 # split on | on purpose
    set -- $summary
    IFS=$old_ifs
    s_minutes=${1:-?} s_result=${2:-?} s_rebuilt=${3:-?} s_checked=${4:-?} s_trigger=${5:-?}
    s_apps=${6:-?} s_d12=${7:-?} s_remaining=${8:-?}
    is_count "$s_minutes" || s_minutes='?'
    is_count "$s_apps" || s_apps='?'
    is_count "$s_d12" || s_d12='?'
    is_count "$s_remaining" || s_remaining='?'
    case $s_result in *[!a-z_]* | '') s_result='?' ;; esac
    case $s_trigger in *[!a-z_-]* | '') s_trigger='?' ;; esac
    case $s_rebuilt in yes | no) ;; *) s_rebuilt='?' ;; esac
    case $s_checked in *[!0-9TZ:-]* | '') s_checked='?' ;; esac
    consistency_cell="$s_minutes ($checked_day, $s_result)"
    consistency_md="- Day checked: $checked_day (UTC); runs recorded for the day: $runs (the latest is shown)
- Latest run: mismatched minutes $s_minutes, result $s_result, rebuilt $s_rebuilt, checked at $s_checked, trigger $s_trigger
- Applications mismatching: $s_apps; tables still mismatching after the rebuild: $s_remaining; D-12 applications (recorded only, not rebuilt): $s_d12"
    if [ "$s_minutes" != 0 ] || [ "$s_apps" != 0 ]; then
      consistency_cell="**$s_minutes** ($checked_day, $s_result)"
      act "Nightly consistency check of $checked_day: $s_minutes mismatched minutes, result $s_result. Gate 1 needs 0: record the day in the issue log; if the result is not repaired run argos-admin consistency check --date $checked_day --rebuild (docs/dogfooding/ops-checklist.md)."
    fi
    case $s_result in
      clean | repaired) ;;
      *) act "The consistency check of $checked_day ended '$s_result' (not clean or repaired): see docs/ops/admin-cli.md and docs/17 section 12 (nightly consistency mismatch)." ;;
    esac
    if [ "$s_d12" != 0 ]; then
      act "D-12 (statement counts against the SQL and external call statistics) differed for $s_d12 application(s) on $checked_day: recorded only; note it in the operating log."
    fi
    rows=$(sed -n 's/^mismatch|//p' "$work/pg.txt" | awk -F'|' '
      $1 ~ /^([0-9]+|-)$/ && $2 ~ /^[a-z0-9_]+$/ && $3 ~ /^[a-z0-9_*]+$/ && $4 ~ /^[0-9]+$/ && $5 ~ /^[a-z_]+$/ {
        printf "| %s | %s | %s | %s | %s |\n", $1, $2, $3, $4, $5
      }')
    if [ -n "$rows" ]; then
      consistency_md="$consistency_md

| App id | Check | Table | Mismatched minutes | Result |
|---|---|---|---|---|
$rows"
    fi
  fi
fi

instances_md="- Not collected: PostgreSQL could not be read."
alerts_md=$instances_md
expiry_tokens="?"
firing_cell="FAILED (PostgreSQL)"
if $pg_ok; then
  # status (12 §4.2): UP, WARN (up with a warning condition: clock skew, drops, old agent, disabled
  # module), STALE, DOWN. UP and WARN instances both send data.
  IFS='|' read -r i_up i_stale i_down i_drops i_modules i_skew i_apps i_warn <<EOF
$instances
EOF
  i_up=$(cq "$i_up") i_stale=$(cq "$i_stale") i_down=$(cq "$i_down") i_drops=$(cq "$i_drops")
  i_modules=$(cq "$i_modules") i_skew=$(cq "$i_skew") i_apps=$(cq "$i_apps") i_warn=$(cq "$i_warn")
  instances_md="- Instances (not archived) in $i_apps application(s): UP $i_up, WARN $i_warn, STALE $i_stale, DOWN $i_down (UP and WARN send data; WARN = up with a warning condition)
- Instances with a warning: drops $i_drops, module disabled $i_modules, clock skew $i_skew
- Registrations waiting for approval: $pending"
  [ "$i_stale" = 0 ] || act "$i_stale instance(s) STALE: check the instance list (network, application hang, agent log)."
  [ "$i_down" = 0 ] || act "$i_down instance(s) DOWN and not archived: expected only for an application that was stopped on purpose; otherwise check it."
  [ "$i_drops" = 0 ] || act "$i_drops instance(s) report agent drops: open the instance's self-diagnosis; record it in the issue log."
  [ "$i_modules" = 0 ] || act "$i_modules instance(s) have an automatically disabled module: open the instance's self-diagnosis; record it in the issue log."
  [ "$i_skew" = 0 ] || act "$i_skew instance(s) have a clock skew warning: fix NTP on the application host (docs/17 section 12)."
  if [ "$i_warn" != 0 ] && [ "$i_drops" = 0 ] && [ "$i_modules" = 0 ] && [ "$i_skew" = 0 ]; then
    act "$i_warn instance(s) WARN for another condition (for example an old agent version): open the instance list and the instance's self-diagnosis."
  fi
  [ "$pending" = 0 ] || act "$pending registration(s) wait for approval (Admin > Pending registrations): approve or reject them."

  IFS='|' read -r a_firing a_critical a_acked <<EOF
$alerts
EOF
  IFS='|' read -r c_failed c_failing <<EOF
$channels
EOF
  IFS='|' read -r t_30 t_7 <<EOF
$tokens
EOF
  a_firing=$(cq "$a_firing") a_critical=$(cq "$a_critical") a_acked=$(cq "$a_acked")
  c_failed=$(cq "$c_failed") c_failing=$(cq "$c_failing") t_30=$(cq "$t_30") t_7=$(cq "$t_7")
  expiry_tokens=$t_30
  firing_cell=$a_firing
  [ "$a_critical" = 0 ] || [ "$a_critical" = '?' ] || firing_cell="$a_firing ($a_critical critical)"
  [ "$a_firing" = 0 ] || firing_cell="**$firing_cell**"
  [ "$a_acked" = 0 ] || firing_cell="$firing_cell, acknowledged $a_acked"
  alerts_md="- FIRING alert units: $a_firing (critical $a_critical); acknowledged: $a_acked
- Notification deliveries failed in the last 24 h: $c_failed; channels in state failing: $c_failing"
  frows=$(sed -n 's/^firing|//p' "$work/pg.txt" | awk -F'|' '
    ($1 ~ /^[0-9]+$/ || $1 == "server") && $2 ~ /^[a-z0-9_]+$/ && $3 ~ /^[a-z-]+$/ && $4 ~ /^[0-9TZ:-]+$/ {
      printf "| %s | %s | %s | %s |\n", $1, $2, $3, $4
    }')
  if [ -n "$frows" ]; then
    alerts_md="$alerts_md

| App id | Rule template | Severity | Firing since (UTC) |
|---|---|---|---|
$frows"
  fi
  [ "$a_firing" = 0 ] || act "$a_firing alert(s) FIRING: open Alerts, decide whether the agent is involved, acknowledge or fix; record agent-related ones in the issue log."
  [ "$c_failed" = 0 ] && [ "$c_failing" = 0 ] \
    || act "Alert channels: $c_failed failed deliveries in 24 h, $c_failing channel(s) failing. Send a test message from the channel page (docs/17 section 12, alert channel failure)."
  [ "$t_30" = 0 ] || act "$t_30 agent token(s) expire within $EXPIRY_WARN_DAYS days ($t_7 within 7): rotate them (argos-admin token rotate, docs/ops/admin-cli.md)."
fi

# ---------------------------------------------------------------------------------------------
# Prometheus: ingest lag, WAL, drops, disk ratio, gauges (server container, 127.0.0.1:8081)

prom_rc=0
# shellcheck disable=SC2016 # the server container's shell expands it (the token stays there)
dc exec -T server sh -c 'printf "header = \"Authorization: Bearer %s\"\n" "$(cat /run/secrets/metrics_token)" | curl -s -f --max-time 20 --config - http://127.0.0.1:8081/actuator/prometheus' "$MARK" \
  </dev/null >"$work/prom.txt" 2>"$work/prom.err" || prom_rc=$?
prom_ok=false
if [ "$prom_rc" -ne 0 ]; then
  section_failed prometheus "/actuator/prometheus in the server container, exit code $prom_rc: $(reason "$work/prom.err")"
elif ! grep -q '^argos_' "$work/prom.txt"; then
  section_failed prometheus "/actuator/prometheus answered without argos_ metrics"
else
  prom_ok=true
  section_ok prometheus "/actuator/prometheus on the management port, with the metrics token"
fi

lag_cell="FAILED (Prometheus)"
ingest_md="- Not collected: /actuator/prometheus could not be read."
disk_ratio='?'
if $prom_ok; then
  tr -d '\r' <"$work/prom.txt" | awk -v limit="$LAG_LIMIT_S" '
    /^#/ || NF < 2 { next }
    {
      v = $NF
      if (v ~ /[Nn][Aa][Nn]|[Ii][Nn][Ff]/) next
      line = $0
      b = index(line, "{"); s = index(line, " ")
      name = (b > 0 && b < s) ? substr(line, 1, b - 1) : substr(line, 1, s - 1)
      x = v + 0
      if (name == "argos_ingest_lag_seconds_count") { lc += x; seen_lag = 1 }
      else if (name == "argos_ingest_lag_seconds_sum") ls += x
      else if (name == "argos_ingest_lag_seconds_max") { if (x > lmax) lmax = x }
      else if (name == "argos_ingest_lag_seconds") {
        if (match(line, /quantile="[^"]*"/)) {
          q = substr(line, RSTART + 10, RLENGTH - 11) + 0
          if (q > 0.98 && q < 0.995) { if (x > p99) p99 = x; seen_p99 = 1 }
          if (q > 0.49 && q < 0.51) { if (x > p50) p50 = x; seen_p50 = 1 }
        }
      }
      else if (name == "argos_ingest_lag_seconds_bucket") {
        if (match(line, /le="[^"]*"/)) {
          le = substr(line, RSTART + 4, RLENGTH - 5)
          if (le !~ /[Ii]nf/) bucket[le] += x
        }
      }
      else if (name == "argos_wal_bytes") { wal += x; seen_wal = 1 }
      else if (name == "argos_server_disk_usage_ratio") { if (!seen_disk || x > disk) disk = x; seen_disk = 1 }
      else if (name == "argos_collector_dropped_total") { drop += x; seen_drop = 1 }
      else if (name == "argos_writer_dead_letter_blocks") { dl += x; seen_dl = 1 }
      else if (name == "argos_retention_emergency_drops_total") { em += x; seen_em = 1 }
      else if (name == "argos_reconcile_mismatch_minutes") { rg += x; if (x > 0) rga++; seen_rg = 1 }
      else if (name == "argos_server_tls_cert_expiry_days") { if (!seen_cert || x < cert) cert = x; seen_cert = 1 }
      else if (name == "argos_ch_parts_active") { if (x > parts) parts = x; seen_parts = 1 }
      else if (name == "argos_sessions_active") { sess += x; seen_sess = 1 }
    }
    END {
      best = -1
      for (k in bucket) { kv = k + 0; if (kv <= limit && kv > best) { best = kv; bc = bucket[k] } }
      if (seen_lag) {
        printf "lag_count=%d\n", lc
        if (lc > 0) printf "lag_mean=%.3f\n", ls / lc
        printf "lag_max=%.3f\n", lmax
        if (best >= 0 && lc > 0) { printf "lag_bound=%.3g\n", best; printf "lag_above=%.3f\n", (lc - bc) * 100 / lc }
      }
      if (seen_p99) printf "lag_p99=%.3f\n", p99
      if (seen_p50) printf "lag_p50=%.3f\n", p50
      # The server timer publishes a percentile histogram; Micrometer with the Prometheus 1.x client
      # then exports no quantile series. Estimate p50 and p99 since the start as the upper bound
      # of the first cumulative bucket that holds the share (le = +Inf falls back to the max).
      if (lc > 0) {
        h50 = -1; h99 = -1
        for (k in bucket) {
          kv = k + 0
          if (bucket[k] >= lc * 0.5 && (h50 < 0 || kv < h50)) h50 = kv
          if (bucket[k] >= lc * 0.99 && (h99 < 0 || kv < h99)) h99 = kv
        }
        if (h50 < 0) h50 = lmax
        if (h99 < 0) h99 = lmax
        printf "lag_p50h=%.3f\n", h50
        printf "lag_p99h=%.3f\n", h99
      }
      if (seen_wal) printf "wal=%.0f\n", wal
      if (seen_disk) printf "disk=%.2f\n", disk
      if (seen_drop) printf "drop=%.0f\n", drop
      if (seen_dl) printf "dead=%.0f\n", dl
      if (seen_em) printf "emergency=%.0f\n", em
      if (seen_rg) { printf "gauge=%.0f\n", rg; printf "gauge_apps=%d\n", rga }
      if (seen_cert) printf "cert=%.0f\n", cert
      if (seen_parts) printf "parts=%.0f\n", parts
      if (seen_sess) printf "sessions=%.0f\n", sess
    }' >"$work/prom.values"
  pv() { # pv <key>: the value, or "-" when the scrape had no such metric
    v=$(sed -n "s/^$1=//p" "$work/prom.values" | head -n 1)
    printf '%s' "${v:--}"
  }
  lag_p99=$(pv lag_p99)
  lag_p50=$(pv lag_p50)
  lag_count=$(pv lag_count)
  lag_mean=$(pv lag_mean)
  lag_max=$(pv lag_max)
  lag_bound=$(pv lag_bound)
  lag_above=$(pv lag_above)
  disk_ratio=$(pv disk)
  wal=$(pv wal)
  drop=$(pv drop)
  dead=$(pv dead)
  emergency=$(pv emergency)
  gauge=$(pv gauge)
  gauge_apps=$(pv gauge_apps)
  cert=$(pv cert)
  parts=$(pv parts)
  sessions=$(pv sessions)
  lag_source="recent window"
  if [ "$lag_p99" = - ] && [ "$(pv lag_p99h)" != - ]; then
    # No quantile series (percentile histogram, see above): bucket estimate since the start.
    lag_p99=$(pv lag_p99h)
    lag_p50=$(pv lag_p50h)
    lag_source="since the server start, upper bound of the histogram bucket"
  fi
  if [ "$lag_p99" = - ]; then
    lag_cell="no inserts yet"
  else
    lag_cell="p99 $lag_p99 s"
    gt "$lag_p99" "$LAG_LIMIT_S" && lag_cell="**p99 $lag_p99 s**"
  fi
  if [ "$lag_above" != - ]; then
    lag_cell="$lag_cell ($lag_above% > $lag_bound s since start)"
  fi
  ingest_md="- argos_ingest_lag_seconds: p50 $lag_p50 s, p99 $lag_p99 s ($lag_source); since the server start $lag_count batches, mean $lag_mean s, max $lag_max s, above $lag_bound s: $lag_above%
- argos_collector_dropped_total (server drops since start): $drop; argos_writer_dead_letter_blocks: $dead; argos_retention_emergency_drops_total: $emergency
- argos_wal_bytes: $wal; argos_ch_parts_active (largest): $parts; argos_sessions_active: $sessions
- argos_reconcile_mismatch_minutes (last nightly run, sum over applications): $gauge in $gauge_apps application(s)"
  if [ "$lag_p99" != - ] && gt "$lag_p99" "$LAG_LIMIT_S"; then
    act "Ingest lag p99 $lag_p99 s is above $LAG_LIMIT_S s: docs/17 section 12 (ingest lag rising)."
  fi
  if [ "$drop" != - ] && gt "$drop" 0; then
    act "The server dropped $drop item(s) since its start (argos_collector_dropped_total): compare with yesterday's value; a rise means quota or WAL full (docs/17 section 12)."
  fi
  if [ "$dead" != - ] && gt "$dead" 0; then
    act "$dead dead-letter block(s) (argos_writer_dead_letter_blocks): ClickHouse refused data; record it in the issue log."
  fi
  if [ "$emergency" != - ] && gt "$emergency" 0; then
    act "Emergency retention dropped $emergency partition(s) since the start (disk at 90%, docs/17 section 9.2)."
  fi
  if [ "$gauge" != - ] && gt "$gauge" 0; then
    act "argos_reconcile_mismatch_minutes is $gauge for $gauge_apps application(s): the last nightly run found a mismatch (see the consistency section)."
  fi
  if [ "$disk_ratio" != - ] && ! gt "$DISK_WARN_RATIO" "$disk_ratio"; then
    act "Server disk usage ratio $disk_ratio is at or above $DISK_WARN_RATIO: docs/17 section 9.2 (0.85 stops, 0.9 emergency retention, 0.95 inserts stop)."
  fi
  if [ "$cert" != - ] && gt "$EXPIRY_WARN_DAYS" "$cert"; then
    act "The gRPC certificate expires in $cert days: replace tls/server.crt and tls/server.key (docs/install/compose.md, certificates)."
  fi
fi

# ---------------------------------------------------------------------------------------------
# ClickHouse disk (df in the clickhouse container, as diag-bundle.sh)

ch_percent='?'
if dc exec -T clickhouse df -P /var/lib/clickhouse </dev/null >"$work/df.txt" 2>"$work/df.err"; then
  ch_percent=$(tr -d '\r' <"$work/df.txt" | awk 'NR > 1 && $5 ~ /^[0-9]+%$/ { sub(/%/, "", $5); p = $5 } END { if (p != "") print p }')
  if [ -n "$ch_percent" ]; then
    section_ok clickhouse_disk "df -P /var/lib/clickhouse in the clickhouse container"
    [ "$ch_percent" -lt "$CH_DISK_WARN_PERCENT" ] \
      || act "The ClickHouse data volume is $ch_percent% full: docs/17 section 9.2 and section 12 (disk full)."
  else
    ch_percent='?'
    section_failed clickhouse_disk "unexpected df output"
  fi
else
  section_failed clickhouse_disk "df -P in the clickhouse container: $(reason "$work/df.err")"
fi
disk_cell="server ${disk_ratio}, ClickHouse ${ch_percent}%"
case $disk_ratio in '?') disk_cell="server FAILED, ClickHouse ${ch_percent}%" ;; -) disk_cell="server -, ClickHouse ${ch_percent}%" ;; esac

# ---------------------------------------------------------------------------------------------
# Backups: the newest complete set (manifest.json) in the backup directory of backup.sh

[ -n "$backup_dir" ] || backup_dir=$(env_value ARGOS_BACKUP_DIR)
[ -n "$backup_dir" ] || backup_dir=./backups
backup_cell=
backups_md=
if [ ! -d "$backup_dir" ]; then
  backup_cell="**none**"
  backups_md="- No backup directory (ARGOS_BACKUP_DIR of .env, else ./backups): no backup was taken."
  act "No backup set: run ./argos-compose.sh backup and schedule it daily (docs/ops/backup-restore.md)."
  section_ok backups "no backup directory"
elif ! ls "$backup_dir" >/dev/null 2>&1; then
  backup_cell="FAILED (not readable)"
  section_failed backups "the backup directory is not readable by this user (run as the user of ./argos-compose.sh, root on Linux)"
else
  newest_epoch=0
  newest_name=
  newest_ch=
  sets=0
  for m in "$backup_dir"/argos-backup-*/manifest.json; do
    [ -f "$m" ] || continue
    e=$(sed -n 's/^ *"created_epoch": *\([0-9][0-9]*\),\{0,1\}$/\1/p' "$m" | head -n 1)
    is_count "$e" || continue
    sets=$((sets + 1))
    if [ "$e" -gt "$newest_epoch" ]; then
      newest_epoch=$e
      newest_name=$(basename "$(dirname "$m")" | sed -n 's/^argos-backup-\([0-9]\{8\}T[0-9]\{6\}Z\).*/\1/p')
      newest_ch=$(sed -n 's/^ *"clickhouse_backup": *"\([^"]*\)".*/\1/p' "$m" | head -n 1)
    fi
  done
  if [ "$sets" -eq 0 ]; then
    backup_cell="**none**"
    backups_md="- No complete backup set (argos-backup-*/manifest.json) in the backup directory."
    act "No backup set: run ./argos-compose.sh backup and schedule it daily (docs/ops/backup-restore.md)."
  else
    age_h=$(((now_epoch - newest_epoch) / 3600))
    [ "$age_h" -ge 0 ] || age_h=0
    backup_cell="${age_h} h old"
    if [ "$age_h" -gt "$BACKUP_MAX_AGE_H" ]; then
      backup_cell="**${age_h} h old**"
      act "The newest backup set is $age_h hours old (more than $BACKUP_MAX_AGE_H): check the daily backup job and its log (docs/ops/backup-restore.md)."
    fi
    backups_md="- Complete sets: $sets; newest: argos-backup-${newest_name:-?} ($age_h h old), ClickHouse backup: $([ -n "$newest_ch" ] && echo yes || echo no)"
  fi
  section_ok backups "manifest.json of the backup sets"
fi

cert_md="- gRPC certificate (argos_server_tls_cert_expiry_days): ${cert:-?} days left
- Agent tokens (ACTIVE or GRACE) expiring within $EXPIRY_WARN_DAYS days: $expiry_tokens"

# ---------------------------------------------------------------------------------------------
# Output

if [ "$finding_count" -eq 0 ]; then
  note_cell=
  findings="
- none"
else
  note_cell="$finding_count check(s) to act on"
fi

cat <<EOF
### Argos daily check $today (UTC)

Collected at $now_iso by deploy/compose/ops-report.sh (read-only). Keep it in the private records
repository; copy the table row into the operating log and fill in the last two columns.

| Date (UTC) | Consistency mismatch (minutes) | Server restarts | Ingest lag | Disk | FIRING alerts | Backup | Agent-caused incident | Note |
|---|---|---|---|---|---|---|---|---|
| $today | $consistency_cell | $restart_cell | $lag_cell | $disk_cell | $firing_cell | $backup_cell | _y/n_ | $note_cell |

#### Checks to act on
$findings

#### Services

| Service | State | Health | Started (UTC) | Restart count |
|---|---|---|---|---|$services_table

#### Nightly consistency check

$consistency_md

#### Ingest and storage

$ingest_md
- Disk: server usage ratio (argos_server_disk_usage_ratio, largest mount) $disk_ratio; ClickHouse data volume ${ch_percent}% used

#### Instances

$instances_md

#### Alerts and channels

$alerts_md

#### Certificate and tokens

$cert_md

#### Backups

${backups_md:-- Not collected.}

#### Sections

| Section | Result | Source or reason |
|---|---|---|$sections
EOF

[ "$failed" -eq 0 ] || exit 1
exit 0
