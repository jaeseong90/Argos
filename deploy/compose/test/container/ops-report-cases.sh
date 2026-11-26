#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# req: C-255
#
# Cases of ops-report.sh without a stack (WO-42, proposed T-BLD-030; docs/dogfooding/
# ops-checklist.md): the daily row and the sections from the answers of the test double
# container/fake-bin/docker (containers, psql as argos_backup, the /actuator/prometheus scrape, df
# of the ClickHouse volume) and from backup sets on disk: a clean day (mismatch 0, nothing to act
# on), a day with a consistency mismatch, a day without a recorded run, the thresholds of the
# checklist, a PostgreSQL, Prometheus or df failure (exit 1, the section marked FAILED, the others
# still printed), usage errors (exit 2 before any docker call). Over every run: no secrets/* value,
# no planted application or host name, token or address in the output, no docker call that writes
# (only docker info, inspect and compose ps/exec of the three read-only commands) and only
# read-only SQL that selects no name or host column. Run as root in pinned Linux containers by
# ../ops-report.test.sh. SRC is a read-only copy of deploy/compose, T this test directory.
set -u
# shellcheck source=deploy/compose/test/container/lib.sh
. "$T/container/lib.sh"

mkdir -p /tmp/fake-bin
cp "$T/container/fake-bin/docker" /tmp/fake-bin/docker
chmod 0755 /tmp/fake-bin/docker
PATH=/tmp/fake-bin:$PATH
export PATH
LOG=/tmp/docker.log
SQL_LOG=/tmp/ops-sql.log
FAKE_DOCKER_LOG=$LOG
FAKE_OPS_SQL_LOG=$SQL_LOG
export FAKE_DOCKER_LOG FAKE_OPS_SQL_LOG

WORK=/tmp/ops-compose
cd /
rm -rf "$WORK"
cp -R "$SRC" "$WORK"
rm -rf "$WORK/.env" "$WORK/secrets" "$WORK/tls" "$WORK/compose.override.yaml" "$WORK/test" "$WORK/backups"
cd "$WORK" || exit 1
sh ./argos-env-init.sh --ram-mb 16384 --public-url http://ui.example.test:8080 >/dev/null 2>&1 \
  || { echo "FAIL  argos-env-init.sh did not prepare the directory"; exit 1; }
: >"$LOG"

rand() { head -c 24 /dev/urandom | od -An -v -tx1 | tr -d ' \n' | cut -c1-"$1"; }
# Sentinels generated now (never literals in the tree): an application name (mixed case, so that it
# never passes as a check or table name), a token prefix and a host name, planted in the scrape
# labels and in rows the psql double returns.
APPNAME=ShopAPP$(rand 10)
TOKSENT=$(rand 8)
HOSTSENT=host-$(rand 10)
octet() { echo $(($(od -An -N1 -tu1 /dev/urandom) % 250 + 2)); }
ADDRSENT=10.$(octet).$(octet).$(octet)
OPS=/tmp/ops
rm -rf "$OPS"
mkdir -p "$OPS"
ALL=$OPS/all-output

# prom <p99> <disk ratio> <reconcile gauge> <dropped> <cert days>: a Prometheus scrape in the format
# of Micrometer 1.x (histogram buckets and the published percentiles of argos.ingest.lag).
prom() {
  cat <<EOF
# HELP argos_ingest_lag_seconds Server receive time to ClickHouse insert acknowledgement (N-15)
# TYPE argos_ingest_lag_seconds histogram
argos_ingest_lag_seconds{quantile="0.5"} 0.4
argos_ingest_lag_seconds{quantile="0.99"} $1
argos_ingest_lag_seconds_bucket{le="1.0"} 9000
argos_ingest_lag_seconds_bucket{le="4.294967296"} 9990
argos_ingest_lag_seconds_bucket{le="5.726623061"} 9995
argos_ingest_lag_seconds_bucket{le="+Inf"} 10000
argos_ingest_lag_seconds_count 10000
argos_ingest_lag_seconds_sum 5000.0
# TYPE argos_ingest_lag_seconds_max gauge
argos_ingest_lag_seconds_max 6.1
argos_wal_bytes 1048576.0
argos_server_disk_usage_ratio{mount="/data"} $2
argos_server_disk_usage_ratio{mount="/var/lib/clickhouse"} 0.31
argos_collector_dropped_total{reason="quota"} $4
argos_collector_dropped_total{reason="wal_full"} 0.0
argos_writer_dead_letter_blocks 0.0
argos_retention_emergency_drops_total 0.0
argos_reconcile_mismatch_minutes{app="7"} $3
argos_reconcile_mismatch_minutes{app="8"} 0.0
argos_server_tls_cert_expiry_days $5
argos_ch_parts_active{table="xlog"} 12.0
argos_sessions_active 4.0
argos_clock_offset_ms{instance="$APPNAME-1 on $HOSTSENT"} 3.0
argos_agent_token_expiry_seconds{token_prefix="argos_$TOKSENT"} 3.0E7
argos_instances{state="UP",app="$APPNAME"} 4.0
jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 1.0E8
argos_test_address_info{addr="$ADDRSENT"} 1.0
argos_test_nan NaN
EOF
}
prom 0.8 0.42 0.0 0.0 200.0 >"$OPS/prom-clean.txt"
prom 7.5 0.91 12.0 3.0 20.0 >"$OPS/prom-bad.txt"
# The real server (Micrometer 1.17 and the Prometheus 1.x client): a timer with a percentile
# histogram exports no quantile series, only the buckets, count, sum and max.
grep -v 'quantile=' "$OPS/prom-clean.txt" >"$OPS/prom-histonly.txt"
grep -v 'quantile=' "$OPS/prom-clean.txt" | sed 's/le="4.294967296"} 9990/le="4.294967296"} 9800/' >"$OPS/prom-histonly-slow.txt"

cat >"$OPS/psql-clean.txt" <<EOF
day|2026-11-25
summary|0|clean|no|2026-11-26T03:00:41Z|scheduled|0|0|0
runs|1
instances|4|0|0|0|0|0|2|0
pending|0
alerts|0|0|0
channels|0|0
tokens|0|0
host|$HOSTSENT
EOF
cat >"$OPS/psql-mismatch.txt" <<EOF
day|2026-11-25
summary|12|repaired|yes|2026-11-26T03:01:10Z|scheduled|1|2|0
runs|2
mismatch|7|d03|app_stat_1m|12|repaired
mismatch|7|rollup_1m|$APPNAME|3|repaired
mismatch|$APPNAME|d03|app_stat_1m|1|repaired
instances|3|1|1|1|1|1|2|2
pending|1
alerts|2|1|1
firing|3|error_rate|critical|2026-11-26T01:00:00Z
firing|$APPNAME|heap|warn|2026-11-26T02:00:00Z
firing|server|server_disk|warn|2026-11-26T02:30:00Z
channels|4|1
tokens|2|1
EOF
grep -v '^summary|' "$OPS/psql-clean.txt" >"$OPS/psql-norun.txt"
printf 'day|2026-11-25\n' >"$OPS/psql-short.txt"
# Up instances with a warning condition are stored as WARN, not UP (12 §4.2, InstanceState).
sed 's/^instances|.*/instances|0|0|0|0|0|1|1|1/' "$OPS/psql-clean.txt" >"$OPS/psql-warn-skew.txt"
sed 's/^instances|.*/instances|1|0|0|0|0|0|1|2/' "$OPS/psql-clean.txt" >"$OPS/psql-warn-other.txt"

# backup_set <dir> <age in hours> [clickhouse]: a complete backup set as backup.sh writes it.
backup_set() {
  e=$(($(date -u +%s) - $2 * 3600))
  d=$1/argos-backup-$(date -u +%Y%m%dT%H%M%SZ)-$2h
  mkdir -p "$d"
  {
    printf '{\n  "kind": "argos-backup",\n  "created_epoch": %s,\n' "$e"
    if [ -n "${3:-}" ]; then
      printf '  "clickhouse_backup": "argos-ch-%s",\n' "$e"
    fi
    printf '  "label": "%s"\n}\n' "$APPNAME"
  } >"$d/manifest.json"
}
mkdir -p "$WORK/backups"
backup_set "$WORK/backups" 5 clickhouse
backup_set "$WORK/backups" 29
mkdir -p "$WORK/backups/.argos-backup-20261126T000000Z.partial" "$WORK/backups/argos-backup-20261127T000000Z"

FAKE_OPS_PSQL=$OPS/psql-clean.txt
FAKE_OPS_PROMETHEUS=$OPS/prom-clean.txt
export FAKE_OPS_PSQL FAKE_OPS_PROMETHEUS

# report [NAME=value...] [arguments...]: ops-report.sh from the installation directory with those
# variables in its environment (for the test doubles); every output is collected.
report() {
  rc=0
  (
    cd "$WORK" || exit 1
    while [ $# -gt 0 ]; do
      case $1 in
        [A-Z]*=*) export "${1?}"; shift ;;
        *) break ;;
      esac
    done
    exec sh ./ops-report.sh "$@"
  ) >"$OPS/run.out" 2>&1 || rc=$?
  cat "$OPS/run.out"
  cat "$OPS/run.out" >>"$ALL"
  return "$rc"
}
no_docker_call() { [ ! -s "$LOG" ] || { echo "docker was called:"; cat "$LOG"; return 1; }; }
# Files of the installation directory written after this marker were written by a run.
MARKER=$OPS/start-marker
: >"$MARKER"
sleep 1

# --- usage ------------------------------------------------------------------------------------
: >"$LOG"
expect_exit 0 "--help exits 0" report --help
output_has "Usage: ./ops-report.sh [options]" "--help shows the usage"
for args in "--bogus" "--date 2026-13-01" "--date 2026-11-00" "--date yesterday" "--date" \
  "--since 2026-11-12" "--since 2026-11-12T00:00:00+09:00" "--backup-dir"; do
  : >"$LOG"
  # shellcheck disable=SC2086 # the arguments are split on purpose
  expect_exit 2 "usage error: $args exits 2" report $args
  check "usage error: $args calls no docker" no_docker_call
done
: >"$LOG"
expect_exit 2 "no Docker daemon: exit 2" report FAKE_DOCKER_DOWN=1
output_has "the Docker daemon is not running" "no Docker daemon: the message says so"

# --- a clean day ------------------------------------------------------------------------------
expect_exit 0 "T-BLD-030 clean day: exit 0" report
output_has "### Argos daily check $(date -u +%Y-%m-%d) (UTC)" "clean day: the heading names today (UTC)"
output_has "| Date (UTC) | Consistency mismatch (minutes) | Server restarts | Ingest lag | Disk | FIRING alerts | Backup | Agent-caused incident | Note |" \
  "clean day: the row has the columns of the operating log"
output_has "| 0 (2026-11-25, clean) | up since 2026-11-12T08:00:00Z, restart count 0 | p99 0.800 s (0.100% > 4.29 s since start) | server 0.42, ClickHouse 40% | 0 | 5 h old | _y/n_ |  |" \
  "clean day: the daily row holds the collected values"
output_has "#### Checks to act on

- none" "clean day: nothing to act on"
output_has "| server | running | healthy | 2026-11-12T08:00:00Z | 0 |" "clean day: the server container row"
output_has "Latest run: mismatched minutes 0, result clean, rebuilt no, checked at 2026-11-26T03:00:41Z, trigger scheduled" \
  "clean day: the consistency summary of reconcile_results"
output_has "argos_reconcile_mismatch_minutes (last nightly run, sum over applications): 0 in 0 application(s)" "clean day: the gauge"
output_has "since the server start 10000 batches, mean 0.500 s, max 6.100 s, above 4.29 s: 0.100%" "clean day: the lag histogram since the start"
output_has "UP 4, WARN 0, STALE 0, DOWN 0" "clean day: instance states"
output_has "Complete sets: 2; newest: argos-backup-" "clean day: two complete sets (partial and manifest-less directories ignored)"
output_has "ClickHouse backup: yes" "clean day: the newest set has a ClickHouse backup"
output_has "gRPC certificate (argos_server_tls_cert_expiry_days): 200 days left" "clean day: certificate expiry"
output_lacks "FAILED" "clean day: no section failed"
for s in containers postgres prometheus clickhouse_disk backups; do
  output_has "| $s | ok |" "clean day: section $s ok"
done
check "the SQL runs in a read-only transaction" grep -q 'BEGIN TRANSACTION READ ONLY;' "$SQL_LOG"
check "default day: yesterday in UTC" grep -q "day = ((now() AT TIME ZONE 'UTC')::date - 1)" "$SQL_LOG"

# --- window start and restarts ----------------------------------------------------------------
# The fake server started at 2026-11-12T08:00:00Z.
expect_exit 0 "window start after the server start: exit 0" report --since 2026-11-20T00:00:00Z
output_has "| 0 since 2026-11-20T00:00:00Z |" "window start after the server start: no restart in the window"
output_has "- none" "window start after the server start: nothing to act on"
expect_exit 0 "window start before the server start: exit 0" report --since 2026-11-01T00:00:00Z
output_has "**restarted** at 2026-11-12T08:00:00Z (after 2026-11-01T00:00:00Z)" "window start before the server start: the restart is in the row"
output_has "the 14 days start again" "window start before the server start: listed as a check to act on"
expect_exit 0 "restart count 2: exit 0" report FAKE_OPS_RESTARTS=2
output_has "server was restarted 2 time(s) by Docker" "restart count 2: listed as a check to act on"

# --- --date -----------------------------------------------------------------------------------
: >"$SQL_LOG"
expect_exit 0 "--date 2026-11-20: exit 0" report --date 2026-11-20
check "--date: the SQL reads that day" grep -q "day = DATE '2026-11-20'" "$SQL_LOG"

# --- a consistency mismatch and the other thresholds -------------------------------------------
expect_exit 0 "T-BLD-030 mismatch day: exit 0 (findings are not failures)" report \
  FAKE_OPS_PSQL="$OPS/psql-mismatch.txt" FAKE_OPS_PROMETHEUS="$OPS/prom-bad.txt" FAKE_OPS_DF_PERCENT=85
output_has "| **12** (2026-11-25, repaired) |" "mismatch: the row shows the minutes"
output_has "| 7 | d03 | app_stat_1m | 12 | repaired |" "mismatch: the mismatch rows of reconcile_results"
output_has "Gate 1 needs 0: record the day in the issue log" "mismatch: listed as a check to act on"
output_has "D-12 (statement counts against the SQL and external call statistics) differed for 2 application(s)" "mismatch: D-12 noted"
output_has "argos_reconcile_mismatch_minutes is 12 for 1 application(s)" "mismatch: the gauge"
output_has "**p99 7.500 s**" "lag above 5 s: marked in the row"
output_has "Ingest lag p99 7.500 s is above 5 s" "lag above 5 s: listed"
output_has "Server disk usage ratio 0.91 is at or above 0.80" "disk ratio 0.91: listed"
output_has "ClickHouse data volume is 85% full" "ClickHouse 85%: listed"
output_has "The server dropped 3 item(s)" "server drops: listed"
output_has "The gRPC certificate expires in 20 days" "certificate in 20 days: listed"
output_has "| **2 (1 critical)**, acknowledged 1 |" "FIRING: the row"
output_has "| 3 | error_rate | critical | 2026-11-26T01:00:00Z |" "FIRING: the alert rows (app id, template)"
output_has "| server | server_disk | warn | 2026-11-26T02:30:00Z |" "FIRING: a server alert row"
output_has "Alert channels: 4 failed deliveries in 24 h, 1 channel(s) failing" "channel failures: listed"
output_has "2 agent token(s) expire within 30 days (1 within 7)" "token expiry: listed"
output_has "1 instance(s) STALE" "STALE: listed"
output_has "1 instance(s) report agent drops" "agent drops: listed"
output_has "1 instance(s) have an automatically disabled module" "disabled module: listed"
output_has "1 registration(s) wait for approval" "pending registrations: listed"
output_has "UP 3, WARN 2, STALE 1, DOWN 1" "mismatch day: WARN counted apart from UP"
output_lacks "WARN for another condition" "WARN explained by the warning flags: no extra check"
output_lacks "| 7 | rollup_1m |" "a mismatch row with an unexpected table name is dropped"

expect_exit 0 "WARN instance with clock skew: exit 0" report FAKE_OPS_PSQL="$OPS/psql-warn-skew.txt"
output_has "UP 0, WARN 1, STALE 0, DOWN 0" "WARN instance with clock skew: counted as WARN"
output_has "1 instance(s) have a clock skew warning" "WARN instance with clock skew: the skew is listed"
expect_exit 0 "WARN instance without a listed flag: exit 0" report FAKE_OPS_PSQL="$OPS/psql-warn-other.txt"
output_has "UP 1, WARN 2, STALE 0, DOWN 0" "WARN without a listed flag: counted"
output_has "2 instance(s) WARN for another condition" "WARN without a listed flag: listed as a check to act on"

expect_exit 0 "no quantile series (real server format): exit 0" report FAKE_OPS_PROMETHEUS="$OPS/prom-histonly.txt"
output_has "| p99 4.295 s (0.100% > 4.29 s since start) |" "no quantile series: p99 estimated from the buckets in the row"
output_has "p50 1.000 s, p99 4.295 s (since the server start, upper bound of the histogram bucket)" \
  "no quantile series: p50 and p99 from the buckets, the source named"
output_lacks "no inserts yet" "no quantile series: inserts are not reported as missing"
expect_exit 0 "no quantile series, slow: exit 0" report FAKE_OPS_PROMETHEUS="$OPS/prom-histonly-slow.txt"
output_has "Ingest lag p99 5.727 s is above 5 s" "no quantile series, p99 bucket above 5 s: listed"

expect_exit 0 "no run recorded: exit 0" report FAKE_OPS_PSQL="$OPS/psql-norun.txt"
output_has "| **no run recorded** (2026-11-25) |" "no run recorded: the row says so"
output_has "No nightly consistency check is recorded for 2026-11-25" "no run recorded: listed"

expect_exit 0 "unhealthy containers: exit 0" report FAKE_HEALTH=unhealthy
output_has "server is unhealthy, not healthy" "unhealthy: listed"
expect_exit 0 "exited containers: exit 0" report FAKE_OPS_STATE=exited
output_has "| postgres | exited |" "exited: the services table"
expect_exit 0 "no containers: exit 0" report FAKE_PS_EMPTY=1
output_has "| server | no container |" "no containers: the services table"
output_has "unknown (no server container)" "no containers: the restart cell"

# --- backups ----------------------------------------------------------------------------------
mkdir -p /tmp/bk-old
backup_set /tmp/bk-old 40
expect_exit 0 "ARGOS_BACKUP_DIR wins (backup.sh rule): exit 0" report ARGOS_BACKUP_DIR=/tmp/bk-old
output_has "| **40 h old** |" "backup 40 h old: marked in the row"
output_has "The newest backup set is 40 hours old" "backup 40 h old: listed"
output_has "ClickHouse backup: no" "backup without ClickHouse: no"
mkdir -p /tmp/caller/bk
backup_set /tmp/caller/bk 2
rc=0
(cd /tmp/caller && sh "$WORK/ops-report.sh" --backup-dir bk) >"$LAST" 2>&1 || rc=$?
cat "$LAST" >>"$ALL"
if [ "$rc" -eq 0 ] && grep -qF '| 2 h old |' "$LAST"; then
  pass_case "--backup-dir is relative to the caller's directory"
else
  fail_case "--backup-dir is relative to the caller's directory (exit $rc)"
fi
expect_exit 0 "no backup directory: exit 0" report ARGOS_BACKUP_DIR=/tmp/no-such-dir
output_has "| **none** |" "no backup directory: the row says none"

# --- failed sections --------------------------------------------------------------------------
expect_exit 1 "T-BLD-030 PostgreSQL read failure: exit 1" report FAKE_OPS_PSQL_EXIT=2
output_has "| postgres | **FAILED** | psql as argos_backup in the postgres container, exit code 2: psql: error: connection to server at \"<address>\"" \
  "PostgreSQL failure: the section is marked with the reason (address masked)"
output_has "| FAILED (PostgreSQL) |" "PostgreSQL failure: the row says FAILED"
output_has "| prometheus | ok |" "PostgreSQL failure: the other sections are still collected"
output_has "p99 0.800 s" "PostgreSQL failure: the Prometheus values are still printed"
expect_exit 1 "unexpected psql output: exit 1" report FAKE_OPS_PSQL="$OPS/psql-short.txt"
output_has "| postgres | **FAILED** | unexpected psql output" "unexpected psql output: marked"
expect_exit 1 "T-BLD-030 Prometheus failure: exit 1" report FAKE_OPS_PROM_EXIT=22
output_has "| prometheus | **FAILED** | /actuator/prometheus in the server container, exit code 22: curl: (22) The requested URL returned error: 401 |" \
  "Prometheus failure: the section is marked with the reason"
output_has "| FAILED (Prometheus) |" "Prometheus failure: the lag cell says FAILED"
output_has "| postgres | ok |" "Prometheus failure: PostgreSQL is still collected"
printf '# nothing\n' >"$OPS/prom-empty.txt"
expect_exit 1 "a scrape without argos_ metrics: exit 1" report FAKE_OPS_PROMETHEUS="$OPS/prom-empty.txt"
output_has "answered without argos_ metrics" "a scrape without argos_ metrics: marked"
expect_exit 1 "df failure: exit 1" report FAKE_DF_EXIT=1
output_has "| clickhouse_disk | **FAILED** |" "df failure: marked"

# --- nothing leaks, nothing is written -------------------------------------------------------
planted=$OPS/planted
{
  for f in secrets/*; do
    sed -e 's/^[A-Z0-9_]*=//' -e '/^#/d' -e '/^$/d' "$f"
    echo
  done | sed '/^$/d'
  printf '%s\n' "$APPNAME" "$TOKSENT" "$HOSTSENT" "$ADDRSENT"
} >"$planted"
if [ "$(wc -l <"$planted")" -ge 14 ] && ! grep -qF -f "$planted" "$ALL" && ! grep -qF -f "$planted" "$LOG"; then
  pass_case "T-BLD-030 no secrets/* value, application or host name, token prefix or address in any output or docker argument"
else
  grep -nF -f "$planted" "$ALL" "$LOG" | head -n 5 >"$LAST"
  fail_case "T-BLD-030 no secrets/* value, application or host name, token prefix or address in any output or docker argument"
fi
if grep -qE '[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}' "$ALL"; then
  grep -nE '[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}' "$ALL" | head -n 5 >"$LAST"
  fail_case "no IPv4 address in any output"
else
  pass_case "no IPv4 address in any output"
fi

# Every docker call of every run (the log of the whole file, minus the usage cases' resets): only
# info, inspect, compose ps and the three read-only exec commands with the marker.
check_calls() {
  awk '
    {
      n = split($0, w, " ")
      if (w[1] == "info" || w[1] == "inspect" || w[1] == "version") next
      if (w[1] != "compose") { print "not allowed: " $0; bad = 1; next }
      i = 2
      while (i <= n && (w[i] == "-f" || w[i] == "-p" || w[i] == "--profile")) i += 2
      sub_cmd = w[i]
      if (sub_cmd == "ps") next
      if (sub_cmd == "exec") {
        rest = ""
        for (j = i + 1; j <= n; j++) rest = rest " " w[j]
        if (rest ~ /^ -T postgres sh -c .*psql .*-U argos_backup .* argos-ops-report$/) next
        if (rest ~ /^ -T server sh -c .*curl .*http:\/\/127\.0\.0\.1:8081\/actuator\/prometheus argos-ops-report$/) next
        if (rest == " -T clickhouse df -P /var/lib/clickhouse") next
      }
      print "not allowed: " $0; bad = 1
    }
    END { exit bad }' "$LOG"
}
check "T-BLD-030 read-only: only docker info, inspect, compose ps and the three read-only exec calls" check_calls
if grep -qiwE 'insert|update|delete|truncate|drop|alter|create|grant|revoke|copy|vacuum|lock|set' "$SQL_LOG"; then
  grep -niwE 'insert|update|delete|truncate|drop|alter|create|grant|revoke|copy|vacuum|lock|set' "$SQL_LOG" | head -n 5 >"$LAST"
  fail_case "T-BLD-030 read-only: the SQL has no write statement"
else
  pass_case "T-BLD-030 read-only: the SQL has no write statement"
fi
if grep -qiwE 'name|host|target_name|token_hash|token_prefix|pid|ip|config_masked' "$SQL_LOG"; then
  grep -niwE 'name|host|target_name|token_hash|token_prefix|pid|ip|config_masked' "$SQL_LOG" | head -n 5 >"$LAST"
  fail_case "the SQL selects no name, host, address or token column"
else
  pass_case "the SQL selects no name, host, address or token column"
fi
if grep -q 'metrics_token' "$LOG" && ! grep -qF "$(first_line "$WORK/secrets/metrics_token")" "$LOG"; then
  pass_case "the metrics token is read inside the server container only (never a docker argument)"
else
  fail_case "the metrics token is read inside the server container only (never a docker argument)"
fi
if [ -z "$(find "$WORK" -newer "$MARKER" 2>/dev/null)" ]; then
  pass_case "no file of the installation directory changed during the runs"
else
  find "$WORK" -newer "$MARKER" >"$LAST"
  fail_case "no file of the installation directory changed during the runs"
fi

finish
