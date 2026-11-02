#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# bench-protocol.sh: the full overhead protocol of 01 §7.3 on a dedicated bench machine (18 §7,
# DSN-18-07), as run by ./gradlew :test:bench:benchTest -Pbench.machine-profile=<id>. One
# environment record (bench-env.sh, 18 §7.1: stops unless the governor is performance), then the
# sets, then one report of 18 §7.6 over all of them (docs/bench/<YYYY-MM-DD>-<commit7>.md):
#   main       A-B-A-B-A-B load set, profile default (items 2-7: N-01, N-02, N-03, N-26)
#   startup    shop-boot3 and shop-heavy x CDS default/off x 10 starts (item 8: N-04, N-27, N-30)
#   container  load set in a --cpus=2 --memory=4g container (item 9: N-29)
#   vt         load set with virtual threads (item 10: N-28)
#   worst      load set with every normal request profiled (item 3, optional)
#   otel       adds the OpenTelemetry Java agent variant to the main set (item 11, reference only)
# A report is PASS only when all nine requirements are measured and PASS; a set left out leaves
# its requirements INVALID ("not measured"). Duration with the defaults: about 5 hours (four load
# sets of about 85 minutes, the startup set about 30 to 50 minutes); worst and otel add about 85
# and 45 minutes.
#
# usage: test/bench/bench-protocol.sh --machine-profile ID [options]
#   --machine-profile ID    the dedicated bench machine (required)
#   --sets LIST             main,startup,container,vt (default); also worst, otel
#   --loader USER@HOST      load machine for the process-mode sets (01 §7.3 item 1); without it the
#                           process-mode sets deviate from the protocol and their verdicts are INVALID
#   --app-address IP        this machine's address as the load machine reaches it
#   --otel-agent JAR        opentelemetry-javaagent jar (set otel)
#   --jdk N, --java PATH    JDK of the apps (21; the startup set uses --java, else $JAVA_HOME)
#   --exporter X            noop (default), drop or grpc (see bench-run.sh)
#   --collector HOST:PORT   with --exporter grpc
#   --out DIR               set directories (build/bench/protocol-<UTC time>-<commit7>)
#   --report-dir DIR        report directory (docs/bench)
#   --baseline FILE         last approved report (18 §7.7)
#   --allow-invalid-env     record an unfit machine and continue (the report is INVALID)
#   --no-build              use the jars already built (benchTest builds them first)
# Exit code: the report's (0 PASS, 3 FAIL, 4 INVALID), 2 usage error, 1 other error.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

usage() { sed -n '/^# usage:/,/^# Exit code/p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
die() { echo "bench-protocol: $*" >&2; exit 2; }
log() { echo "[bench-protocol $(date -u +%H:%M:%SZ)] $*"; }

command_line="test/bench/bench-protocol.sh$(printf ' %q' "$@")"
machine_profile=""
sets_arg="main,startup,container,vt"
loader=""
app_address=""
otel_agent=""
jdk="21"
java_bin=""
exporter="noop"
collector=""
out=""
report_dir="docs/bench"
baseline=""
allow_invalid=0
build=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --machine-profile) machine_profile="${2-}"; shift 2 ;;
    --sets) sets_arg="${2:?--sets needs a value}"; shift 2 ;;
    --loader) loader="${2:?--loader needs a value}"; shift 2 ;;
    --app-address) app_address="${2:?--app-address needs a value}"; shift 2 ;;
    --otel-agent) otel_agent="${2:?--otel-agent needs a value}"; shift 2 ;;
    --jdk) jdk="${2:?--jdk needs a value}"; shift 2 ;;
    --java) java_bin="${2:?--java needs a value}"; shift 2 ;;
    --exporter) exporter="${2:?--exporter needs a value}"; shift 2 ;;
    --collector) collector="${2:?--collector needs a value}"; shift 2 ;;
    --out) out="${2:?--out needs a value}"; shift 2 ;;
    --report-dir) report_dir="${2:?--report-dir needs a value}"; shift 2 ;;
    --baseline) baseline="${2:?--baseline needs a value}"; shift 2 ;;
    --allow-invalid-env) allow_invalid=1; shift ;;
    --no-build) build=0; shift ;;
    -h | --help) usage ;;
    *) die "unknown option $1 (--help lists the options)" ;;
  esac
done
[[ -n "$machine_profile" ]] || die "--machine-profile ID is required (benchTest: -Pbench.machine-profile=<id>, 18 §2.2)"
IFS=, read -r -a sets <<<"$sets_arg"
for s in "${sets[@]}"; do
  [[ "$s" =~ ^(main|startup|container|vt|worst|otel)$ ]] || die "unknown set '$s' (main, startup, container, vt, worst, otel)"
done
has() { [[ ",$sets_arg," == *",$1,"* ]]; }
has otel && [[ -z "$otel_agent" ]] && die "set otel needs --otel-agent JAR"
has otel && ! has main && die "set otel adds a variant to the main set: include main"
[[ -z "$loader" || -n "$app_address" ]] || die "--loader needs --app-address"
cd "$repo_root"

commit7="$(git rev-parse --short=7 HEAD 2>/dev/null || echo nocommit)"
out="${out:-build/bench/protocol-$(date -u +%Y%m%d-%H%M%S)-$commit7}"
mkdir -p "$out"

if ((build)); then
  log "building the harness, the agent jar and the sample apps"
  ./gradlew -q -Pargos.ui.skip=true :test:bench:benchJar :agent:agent-dist:agentJar \
    :sample-apps:ext-stub:sampleAppJar :sample-apps:shop-boot3:sampleAppJar :sample-apps:shop-heavy:sampleAppJar
  log "stopping the Gradle daemon (no daemon during the measurement)"
  ./gradlew --stop >/dev/null
fi

common=(--no-build --jdk "$jdk" --rounds 3 --exporter "$exporter" --machine-profile "$machine_profile")
[[ -n "$java_bin" ]] && common+=(--java "$java_bin")
[[ -n "$collector" ]] && common+=(--collector "$collector")
((allow_invalid)) && common+=(--allow-invalid-env)
remote=()
[[ -n "$loader" ]] && remote=(--loader "$loader" --app-address "$app_address")
if [[ -z "$loader" ]] && { has main || has vt || has worst; }; then
  log "WARNING: no --loader: k6 runs on this machine; the process-mode sets deviate from 01 §7.3 item 1 (INVALID)"
fi

# Each set runs through bench-run.sh (environment check, set, set-level report) into its own
# directory below $out; the combined report is published at the end.
set_dirs=()
run_set() {
  local name=$1
  shift
  local code=0
  log "set $name"
  bash "$script_dir/bench-run.sh" "${common[@]}" --out "$out/$name" --report-dir "$out/$name/docs-bench" "$@" || code=$?
  if ((code != 0 && code != 3 && code != 4)); then
    echo "bench-protocol: set $name failed with exit code $code" >&2
    exit "$code"
  fi
}

if has main; then
  ref=()
  has otel && ref=(--reference otel --otel-agent "$otel_agent")
  run_set main "${remote[@]+"${remote[@]}"}" "${ref[@]+"${ref[@]}"}"
  set_dirs+=("$out/main/main")
fi
if has worst; then
  run_set worst --profile worst "${remote[@]+"${remote[@]}"}"
  set_dirs+=("$out/worst/worst")
fi
if has vt; then
  run_set vt --vt "${remote[@]+"${remote[@]}"}"
  set_dirs+=("$out/vt/vt")
fi
if has container; then
  run_set container --container --cpus 2 --memory 4g
  set_dirs+=("$out/container/container")
fi
if has startup; then
  # bench-run.sh always runs a load set; the startup set is run by the harness directly.
  log "set startup"
  mkdir -p "$out/startup"
  env_args=(--out "$out/startup/env-record.json" --machine-profile "$machine_profile")
  [[ -n "$java_bin" ]] && env_args+=(--java "$java_bin")
  ((allow_invalid)) && env_args+=(--allow-invalid-env)
  bash "$script_dir/bench-env.sh" "${env_args[@]}" || exit $?
  # shellcheck disable=SC2054 # --apps and --cds take one comma-separated value each, not array elements
  start=(startup --out "$out/startup/startup" --apps shop-boot3,shop-heavy --runs 10 --cds default,off
    --exporter "$exporter" --env-record "$out/startup/env-record.json" --label "bench-protocol startup")
  if [[ -n "$java_bin" ]]; then
    start+=(--java "$java_bin")
  elif [[ -n "${JAVA_HOME:-}" ]]; then
    start+=(--java "$JAVA_HOME/bin/java")
  fi
  code=0
  bash "$script_dir/bench.sh" --no-build "${start[@]}" || code=$?
  ((code == 0 || code == 3 || code == 4)) || { echo "bench-protocol: startup set failed ($code)" >&2; exit "$code"; }
  set_dirs+=("$out/startup/startup")
fi

# Native paths: Git Bash converts only the first path of a comma-separated list for java.exe.
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
set_list=""
for s in "${set_dirs[@]}"; do set_list+="${set_list:+,}$(native "$s")"; done
publish=(publish --sets "$set_list" --out "$report_dir" --command "$command_line")
[[ -n "$baseline" ]] && publish+=(--baseline "$baseline")
log "report: ${publish[*]}"
code=0
bash "$script_dir/bench.sh" --no-build "${publish[@]}" || code=$?
log "done: exit code $code (0 PASS, 3 FAIL, 4 INVALID); sets in $out, report in $report_dir"
exit "$code"
