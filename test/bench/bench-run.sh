#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# bench-run.sh: one overhead set in the A-B-A-B-A-B alternation of 18 §7.3 (01 §7.3 items 2-7):
# off, on, off, on, off, on, the app restarted for every run, 3 minutes of warm-up excluded, a
# 10-minute measured window at 1,000 requests/s (sample-apps/load/bench.js). The off-run CPU
# coefficient of variation above 2% makes the set INVALID. Steps: bench-env.sh (18 §7.1; stops on a
# governor other than performance), the load set, optionally the startup set (01 §7.3 item 8), and
# the report of 18 §7.6 (docs/bench/<YYYY-MM-DD>-<commit7>.md by default).
#
# usage: test/bench/bench-run.sh --app shop-boot3 --jdk 21 --rounds 3 --exporter noop|grpc [options] [-- harness options]
#   --app NAME              sample app (shop-boot3)
#   --jdk N                 JDK feature release of the app (21). Resolved from --java, $ARGOS_BENCH_JDK_<N>,
#                           $JAVA_HOME (when it is release N), ~/.gradle/jdks, ~/.jdks
#   --java PATH             java executable of the app (instead of --jdk)
#   --rounds N              off/on rounds (3)
#   --exporter X            noop (18 §5.4: argos.test.exporter=noop, encode and drop), drop (WO-02
#                           setting: argos.test.exporter=drop, record and drop; both need a -SNAPSHOT
#                           agent build, C-88) or grpc (the transport cost, WO-11; needs
#                           --collector HOST:PORT)                                           (noop)
#   --collector HOST:PORT   collector for --exporter grpc (a collector server, or the standalone
#                           FakeCollector: agent/agent-testing/README.md "Standalone collector")
#   --agent-config K=V,...  more agent settings of the load and startup sets, e.g. for a plaintext
#                           FakeCollector: server.tls=false,server.token-file=<its --token-file>
#   --profile P             default | worst (every normal request profiled, 18 §7.5)       (default)
#   --vt                    virtual threads (SAMPLE_VT=true, Boot 3.2+, N-28)
#   --container             app in a container: --cpus 2 --memory 4g, heap 2g (N-29)
#   --cpus N, --memory M    container limits (2, 4g)
#   --reference otel        add the OpenTelemetry Java agent variant (reference only, 18 §7.5): in
#                           process mode it exports OTLP http/protobuf (traces, metrics) to the
#                           harness's black-hole receiver on 127.0.0.1; a container keeps exporters none
#   --otel-agent auto|JAR   opentelemetry-javaagent jar for --reference otel. auto (the default) is
#                           test/bench/build/otel/opentelemetry-javaagent-2.32.0.jar, SHA-256 checked;
#                           run test/bench/fetch-otel-agent.sh once first (a run never downloads)
#   --loader USER@HOST      k6 on the load machine over ssh (01 §7.3 item 1); needs --app-address
#   --app-address IP        this machine's address as the load machine reaches it
#   --startup               also the startup set (01 §7.3 item 8, N-04, N-27, N-30)
#   --startup-apps A,B      apps of the startup set (shop-boot3,shop-heavy)
#   --startup-runs N        starts per variant (10)
#   --startup-cds C         CDS modes (default,off)
#   --machine-profile ID    the bench machine (-Pbench.machine-profile); none: the report is INVALID
#   --out DIR               set directories (build/bench/<UTC time>-<commit7>)
#   --report-dir DIR        report directory (docs/bench)
#   --baseline FILE|auto    report for the 18 §7.7 regression check; auto: the newest approved report
#                           in the report directory with the same machine profile, app, JDK, exporter
#                           and encoding (none: NOT COMPARED with the reason)
#   --latest                also update latest.md and trend.md/.svg in the report directory
#   --role NAME             directory and label of the load set (default: container, vt, worst or
#                           main from the options; bench-protocol.sh uses grpc for its grpc set)
#   --allow-invalid-env     record an unfit machine and continue (the report is INVALID)
#   --smoke                 end-to-end check of the chain on any machine: 1 round, warm-up 30 s,
#                           window 60 s, startup 1 start of shop-boot3 with default CDS, no waiting
#                           for a quiet machine; implies --allow-invalid-env; the report goes to
#                           <out>/docs-bench and is INVALID (protocol deviations, environment)
#   --no-build              use the jars already built
#   -- ...                  further options for the harness's load command (bench.sh load --help)
# Exit code: the report's (0 PASS, 3 FAIL, 4 INVALID), 2 usage error, 1 other error.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

usage() { sed -n '/^# usage:/,/^# Exit code/p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
die() { echo "bench-run: $*" >&2; exit 2; }
log() { echo "[bench-run $(date -u +%H:%M:%SZ)] $*"; }

command_line="test/bench/bench-run.sh$(printf ' %q' "$@")"
app="shop-boot3"
jdk="21"
java_bin=""
rounds=3
exporter="noop"
collector=""
agent_config=""
profile="default"
vt=0
container=0
cpus=2
memory="4g"
reference=""
otel_agent="auto"
loader=""
app_address=""
startup=0
startup_apps="shop-boot3,shop-heavy"
startup_runs=10
startup_cds="default,off"
machine_profile=""
out=""
report_dir=""
baseline=""
latest=0
role=""
allow_invalid=0
smoke=0
build=1
extra=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --app) app="${2:?--app needs a value}"; shift 2 ;;
    --jdk) jdk="${2:?--jdk needs a value}"; shift 2 ;;
    --java) java_bin="${2:?--java needs a value}"; shift 2 ;;
    --rounds) rounds="${2:?--rounds needs a value}"; shift 2 ;;
    --exporter) exporter="${2:?--exporter needs a value}"; shift 2 ;;
    --collector) collector="${2:?--collector needs a value}"; shift 2 ;;
    --agent-config) agent_config="${2:?--agent-config needs a value}"; shift 2 ;;
    --profile) profile="${2:?--profile needs a value}"; shift 2 ;;
    --vt) vt=1; shift ;;
    --container) container=1; shift ;;
    --cpus) cpus="${2:?--cpus needs a value}"; shift 2 ;;
    --memory) memory="${2:?--memory needs a value}"; shift 2 ;;
    --reference) reference="${2:?--reference needs a value}"; shift 2 ;;
    --otel-agent) otel_agent="${2:?--otel-agent needs a value}"; shift 2 ;;
    --loader) loader="${2:?--loader needs a value}"; shift 2 ;;
    --app-address) app_address="${2:?--app-address needs a value}"; shift 2 ;;
    --startup) startup=1; shift ;;
    --startup-apps) startup_apps="${2:?--startup-apps needs a value}"; shift 2 ;;
    --startup-runs) startup_runs="${2:?--startup-runs needs a value}"; shift 2 ;;
    --startup-cds) startup_cds="${2:?--startup-cds needs a value}"; shift 2 ;;
    --machine-profile) machine_profile="${2-}"; shift 2 ;;
    --out) out="${2:?--out needs a value}"; shift 2 ;;
    --report-dir) report_dir="${2:?--report-dir needs a value}"; shift 2 ;;
    --baseline) baseline="${2:?--baseline needs a value}"; shift 2 ;;
    --latest) latest=1; shift ;;
    --role) role="${2:?--role needs a value}"; shift 2 ;;
    --allow-invalid-env) allow_invalid=1; shift ;;
    --smoke) smoke=1; allow_invalid=1; shift ;;
    --no-build) build=0; shift ;;
    --) shift; extra=("$@"); break ;;
    -h | --help) usage ;;
    *) die "unknown option $1 (--help lists the options)" ;;
  esac
done
[[ "$exporter" =~ ^(noop|drop|grpc)$ ]] || die "--exporter must be noop, drop or grpc"
[[ "$profile" =~ ^(default|worst)$ ]] || die "--profile must be default or worst"
[[ -z "$reference" || "$reference" == otel ]] || die "--reference takes otel"
[[ -z "$reference" || -n "$otel_agent" ]] || die "--reference otel needs --otel-agent auto|JAR"
[[ -z "$role" || "$role" =~ ^[a-z][a-z0-9-]*$ ]] || die "--role takes a lower-case name (main, grpc, ...)"
[[ -z "$loader" || -n "$app_address" ]] || die "--loader needs --app-address (this machine's IP as the load machine sees it)"
((container == 0 || ${#loader} == 0)) || die "--container runs k6 in a container on this host; --loader is for process mode"
cd "$repo_root"

# ---- the app's JDK -------------------------------------------------------------------------------

java_release() { "$1" -version 2>&1 | tr -d '\r' | sed -n 's/.*version "\(1\.\)\{0,1\}\([0-9]*\).*/\2/p' | head -n 1; }
if [[ -z "$java_bin" ]]; then
  env_name="ARGOS_BENCH_JDK_$jdk"
  if [[ -n "${!env_name:-}" ]]; then
    java_bin="${!env_name}/bin/java"
  elif [[ -n "${JAVA_HOME:-}" && "$(java_release "$JAVA_HOME/bin/java")" == "$jdk" ]]; then
    java_bin="$JAVA_HOME/bin/java"
  else
    for home in "$HOME"/.gradle/jdks/*-"$jdk"-* "$HOME"/.gradle/jdks/*-"$jdk"-*/* "$HOME"/.jdks/*"$jdk"*; do
      for candidate in "$home/bin/java" "$home/bin/java.exe"; do
        if [[ -z "$java_bin" && -x "$candidate" && "$(java_release "$candidate")" == "$jdk" ]]; then
          java_bin="$candidate"
        fi
      done
    done
  fi
  [[ -n "$java_bin" ]] || die "no JDK $jdk found: pass --java PATH or set ARGOS_BENCH_JDK_$jdk"
fi
[[ "$(java_release "$java_bin")" == "$jdk" ]] || die "$java_bin is not JDK $jdk"
((vt == 0 || jdk >= 21)) || die "--vt needs JDK 21 or later (virtual threads)"

# ---- directories, build, environment -------------------------------------------------------------

commit7="$(git rev-parse --short=7 HEAD 2>/dev/null || echo nocommit)"
out="${out:-build/bench/$(date -u +%Y%m%d-%H%M%S)-$commit7}"
if [[ -z "$report_dir" ]]; then
  report_dir="docs/bench"
  ((smoke)) && report_dir="$out/docs-bench"
fi
mkdir -p "$out"

if ((build)); then
  tasks=(:test:bench:benchJar :agent:agent-dist:agentJar :sample-apps:ext-stub:sampleAppJar
    ":sample-apps:$app:sampleAppJar")
  if ((startup)); then
    IFS=, read -r -a sapps <<<"$startup_apps"
    for a in "${sapps[@]}"; do tasks+=(":sample-apps:$a:sampleAppJar"); done
  fi
  log "building ${tasks[*]}"
  ./gradlew -q -Pargos.ui.skip=true "${tasks[@]}"
fi

env_args=(--out "$out/env-record.json" --java "$java_bin" --machine-profile "$machine_profile")
[[ -n "$loader" ]] && env_args+=(--loader "$loader")
((allow_invalid)) && env_args+=(--allow-invalid-env)
log "environment record (18 §7.1)"
bash "$script_dir/bench-env.sh" "${env_args[@]}" || exit $?

# Harness exit codes 0 PASS, 3 FAIL and 4 INVALID are results; anything else stops the run.
run_harness() {
  local code=0
  bash "$script_dir/bench.sh" --no-build "$@" || code=$?
  if ((code != 0 && code != 3 && code != 4)); then
    echo "bench-run: harness '$1' failed with exit code $code" >&2
    exit "$code"
  fi
}

# ---- load set (18 §7.3) --------------------------------------------------------------------------

# The set's role in the report: container (N-29), vt (N-28), worst (profile) or main; --role names
# it explicitly (the grpc set of bench-protocol.sh).
if [[ -z "$role" ]]; then
  role="main"
  if ((container)); then
    role="container"
  elif ((vt)); then
    role="vt"
  elif [[ "$profile" == worst ]]; then
    role="worst"
  fi
fi
((smoke)) && [[ "$rounds" == 3 ]] && rounds=1
load=(load --out "$out/$role" --app "$app" --java "$java_bin" --rounds "$rounds" --exporter "$exporter"
  --profile "$profile" --env-record "$out/env-record.json" --label "bench-run $role")
[[ -n "$collector" ]] && load+=(--collector "$collector")
[[ -n "$agent_config" ]] && load+=(--agent-config "$agent_config")
((vt)) && load+=(--vt)
((container)) && load+=(--mode container --cpus "$cpus" --memory "$memory" --heap 2g)
# shellcheck disable=SC2054 # off,on,otel is one comma-separated value of --variants, not three elements
[[ -n "$reference" ]] && load+=(--variants off,on,otel --otel-agent "$otel_agent")
[[ -n "$loader" ]] && load+=(--k6-ssh "$loader" --app-address "$app_address")
if ((smoke)); then
  load+=(--warmup 30s --duration 60s --max-wait 2m --max-load 100 --quiet 5s --retries 0 --settle 5s)
fi
load+=("${extra[@]+"${extra[@]}"}")
sets=("$out/$role")
log "load set $role: ${load[*]}"
run_harness "${load[@]}"

# ---- startup set (01 §7.3 item 8) -----------------------------------------------------------------

if ((startup)); then
  start=(startup --out "$out/startup" --java "$java_bin" --exporter "$exporter"
    --env-record "$out/env-record.json" --label "bench-run startup")
  [[ -n "$collector" ]] && start+=(--collector "$collector")
  [[ -n "$agent_config" ]] && start+=(--agent-config "$agent_config")
  if ((smoke)); then
    start+=(--apps "$app" --runs 1 --cds default --max-wait 1m --max-load 100)
  else
    start+=(--apps "$startup_apps" --runs "$startup_runs" --cds "$startup_cds")
  fi
  sets+=("$out/startup")
  log "startup set: ${start[*]}"
  run_harness "${start[@]}"
fi

# ---- report (18 §7.6, §7.7) ----------------------------------------------------------------------

# Native paths: Git Bash converts only the first path of a comma-separated list for java.exe.
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
set_list=""
for s in "${sets[@]}"; do set_list+="${set_list:+,}$(native "$s")"; done
publish=(publish --sets "$set_list" --out "$report_dir" --command "$command_line")
[[ -n "$baseline" ]] && publish+=(--baseline "$baseline")
((latest)) && publish+=(--latest --trend)
log "report: ${publish[*]}"
code=0
bash "$script_dir/bench.sh" --no-build "${publish[@]}" || code=$?
log "done: exit code $code (0 PASS, 3 FAIL, 4 INVALID); sets in $out, report in $report_dir"
exit "$code"
