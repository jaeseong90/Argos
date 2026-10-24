#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# run-bench.sh: runs sample-apps/load/bench.js (k6, docs/18 §7.2) from the grafana/k6 image pinned
# by digest against shop-boot3 + ext-stub started here without an agent, and writes the k6 JSON
# summary to the given path. Development and benchmark tool: k6 is AGPL-3.0 and runs only as a
# separate container (docs/licenses/dev-only.md, ADR-0003 C-33). k6 talks to the app only (usage
# reports off); the app calls ext-stub only.
#
# Where the apps run (--apps):
#   process    java processes on 127.0.0.1, k6 on the host network. Default on Linux.
#   container  the sample images argos-sample/shop-boot3:dev and argos-sample/ext-stub:dev on a
#              private Docker network shared with k6. Default elsewhere (Docker Desktop): its
#              container-to-host proxy left about 1% of requests unanswered for 10 s at 1,000/s
#              even against ext-stub alone, which a private network does not.
#
# usage: sample-apps/load/run-bench.sh --summary FILE [options]
#   --summary FILE      k6 JSON summary (required; its directory receives the other outputs)
#   --rate N            requests per second, constant arrival rate               (1000)
#   --duration D        measured window (k6 duration: 30s, 1m, 10m)              (1m)
#   --warmup D          warm-up at the same rate, excluded from the results     (30s; 0s disables)
#   --apps MODE         process | container (see above)
#   --target-url URL    load an app that is already running instead (nothing is started); the
#                       URL must be reachable from the k6 container
#   --docker-network N  with --target-url: attach k6 to this Docker network
#   --app-port N        host port of shop-boot3 (process: listen, container: publish)  (18580)
#   --ext-port N        host port of ext-stub                                   (18581)
#   --java PATH         java 17+ for process-mode apps and the machine monitor ($JAVA_HOME/bin/java)
#   --jdk-image IMAGE   container mode: rebuild the images on this base (JDK_IMAGE, pinned digest)
#   --app-opts "OPTS"   JVM options of shop-boot3 (01 §7.3 #2: -Xms2g -Xmx2g -XX:+UseG1GC
#                       -XX:+AlwaysPreTouch; a GC log goes next to the summary)
#   --app-env K=V       extra environment of shop-boot3 (repeatable: SAMPLE_VT=true, ...)
#   --pre-vus N         k6 preAllocatedVUs                                      (300)
#   --max-vus N         k6 maxVUs                                               (600)
#   --gate on|off       k6 thresholds fail the run (exit code 99)               (on)
#   --raw               also write every k6 sample (k6 --out json, k6-raw.json.gz; large)
#   --no-build          use the jars and images already built (no Gradle run)
#
# Outputs next to the summary: machine-before.json (CPU load just before the run),
# machine-during.json (system CPU load every second while k6 runs, with a timeline; in process
# mode also the CPU time of shop-boot3), app-cpu.json (container mode: shop-boot3 CPU from cgroup
# cpu.stat), app.log, gc.log, ext-stub.log, k6.log, run.env (parameters, k6 and JDK versions).
# Exit code: k6's (0 thresholds passed, 99 thresholds failed, other k6 errors), 1 setup failure,
# 2 usage error.

set -euo pipefail

readonly K6_IMAGE="grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34"
readonly APP_IMAGE="argos-sample/shop-boot3:dev"
readonly EXT_IMAGE="argos-sample/ext-stub:dev"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

summary=""
rate=1000
duration="1m"
warmup="30s"
apps=""
target_url=""
docker_network=""
app_port=18580
ext_port=18581
java_bin=""
jdk_image=""
app_opts="-Xms2g -Xmx2g -XX:+UseG1GC -XX:+AlwaysPreTouch"
app_env=()
pre_vus=300
max_vus=600
gate="on"
raw=0
build=1

usage() {
  sed -n '/^# usage:/,/^# 2 usage error\./p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

die() {
  echo "run-bench: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --summary) summary="${2:?--summary needs a value}"; shift 2 ;;
    --rate) rate="${2:?}"; shift 2 ;;
    --duration) duration="${2:?}"; shift 2 ;;
    --warmup) warmup="${2:?}"; shift 2 ;;
    --apps) apps="${2:?}"; shift 2 ;;
    --target-url) target_url="${2:?}"; shift 2 ;;
    --docker-network) docker_network="${2:?}"; shift 2 ;;
    --app-port) app_port="${2:?}"; shift 2 ;;
    --ext-port) ext_port="${2:?}"; shift 2 ;;
    --java) java_bin="${2:?}"; shift 2 ;;
    --jdk-image) jdk_image="${2:?}"; shift 2 ;;
    --app-opts) app_opts="${2?}"; shift 2 ;;
    --app-env) app_env+=("${2:?}"); shift 2 ;;
    --pre-vus) pre_vus="${2:?}"; shift 2 ;;
    --max-vus) max_vus="${2:?}"; shift 2 ;;
    --gate) gate="${2:?}"; shift 2 ;;
    --raw) raw=1; shift ;;
    --no-build) build=0; shift ;;
    -h | --help) usage ;;
    *) echo "run-bench: unknown option $1" >&2; usage ;;
  esac
done
[[ -n "$summary" ]] || { echo "run-bench: --summary is required" >&2; usage; }
[[ "$rate" =~ ^[0-9]+([.][0-9]+)?$ ]] || die "--rate must be a number: $rate"
[[ "$app_port" =~ ^[0-9]+$ && "$ext_port" =~ ^[0-9]+$ ]] || die "ports must be numbers"
[[ "$pre_vus" =~ ^[0-9]+$ && "$max_vus" =~ ^[0-9]+$ ]] || die "--pre-vus and --max-vus must be numbers"
[[ "$gate" == on || "$gate" == off ]] || die "--gate must be on or off"
for kv in "${app_env[@]+"${app_env[@]}"}"; do
  [[ "$kv" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]] || die "--app-env expects K=V: $kv"
done
if [[ -z "$apps" ]]; then
  if [[ "$(uname -s)" == "Linux" ]]; then apps="process"; else apps="container"; fi
fi
[[ "$apps" == process || "$apps" == container ]] || die "--apps must be process or container"
[[ -z "$docker_network" || -n "$target_url" ]] || die "--docker-network needs --target-url"
[[ -z "$jdk_image" || "$apps" == container ]] || die "--jdk-image applies to --apps container"
[[ -z "$jdk_image" || "$build" == 1 ]] || die "--jdk-image rebuilds the images; drop --no-build"

# k6 duration -> whole seconds (500ms, 30s, 1m30s, 2h; a bare number is seconds).
to_seconds() {
  local text="$1" total=0 rest
  if [[ "$text" =~ ^[0-9]+$ ]]; then echo "$text"; return; fi
  rest="$text"
  while [[ -n "$rest" ]]; do
    if [[ "$rest" =~ ^([0-9]+)(ms|s|m|h)(.*)$ ]]; then
      case "${BASH_REMATCH[2]}" in
        ms) total=$((total + BASH_REMATCH[1] / 1000)) ;;
        s) total=$((total + BASH_REMATCH[1])) ;;
        m) total=$((total + BASH_REMATCH[1] * 60)) ;;
        h) total=$((total + BASH_REMATCH[1] * 3600)) ;;
      esac
      rest="${BASH_REMATCH[3]}"
    else
      die "bad duration: $text"
    fi
  done
  echo "$total"
}
duration_s="$(to_seconds "$duration")"
warmup_s="$(to_seconds "$warmup")"
((duration_s > 0)) || die "--duration must be positive"

# Native path for java.exe and docker on Windows (Git Bash); unchanged elsewhere.
native() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s\n' "$1"; fi
}

# docker without Git Bash's path conversion (container paths such as /tmp stay as they are).
dock() {
  MSYS_NO_PATHCONV=1 docker "$@"
}

# --- tools -------------------------------------------------------------------------------------
if [[ -z "$java_bin" ]]; then
  if [[ -n "${JAVA_HOME:-}" ]]; then java_bin="$JAVA_HOME/bin/java"; else java_bin="java"; fi
fi
java_major="$("$java_bin" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p' | tr -d '\r')"
[[ "$java_major" =~ ^[0-9]+$ && "$java_major" -ge 17 ]] || die "$java_bin is not Java 17+ (got '${java_major:-?}')"
command -v docker >/dev/null 2>&1 || die "docker is required (k6 runs as a container)"
command -v curl >/dev/null 2>&1 || die "curl is required"

out_dir="$(mkdir -p "$(dirname "$summary")" && cd "$(dirname "$summary")" && pwd)"
summary_name="$(basename "$summary")"
scratch="$out_dir/tmp"
mkdir -p "$scratch"
export TMPDIR="$scratch"

app_jar="$repo_root/sample-apps/shop-boot3/build/sample-app/shop-boot3.jar"
ext_jar="$repo_root/sample-apps/ext-stub/build/sample-app/ext-stub.jar"
load_jar="$repo_root/sample-apps/load/build/sample-app/load.jar"
start_apps=0
[[ -n "$target_url" ]] || start_apps=1
if ((build)); then
  tasks=(:sample-apps:load:sampleAppJar)
  gradle_args=()
  if ((start_apps)); then
    if [[ "$apps" == process ]]; then
      tasks+=(:sample-apps:shop-boot3:sampleAppJar :sample-apps:ext-stub:sampleAppJar)
    else
      tasks+=(:sample-apps:shop-boot3:sampleImage :sample-apps:ext-stub:sampleImage)
      [[ -z "$jdk_image" ]] || gradle_args+=("-Psample.jdkImage=$jdk_image")
    fi
  fi
  echo "run-bench: building ${tasks[*]}"
  (cd "$repo_root" && ./gradlew -q -Pargos.ui.skip=true "${gradle_args[@]+"${gradle_args[@]}"}" "${tasks[@]}") \
    || die "Gradle build failed"
fi
[[ -f "$load_jar" ]] || die "missing $load_jar (run without --no-build)"
if ((start_apps)); then
  if [[ "$apps" == process ]]; then
    for jar in "$app_jar" "$ext_jar"; do
      [[ -f "$jar" ]] || die "missing $jar (run without --no-build)"
    done
  else
    for image in "$APP_IMAGE" "$EXT_IMAGE"; do
      dock image inspect "$image" >/dev/null 2>&1 || die "missing image $image (run without --no-build)"
    done
  fi
fi

# --- processes, containers and cleanup ---------------------------------------------------------
app_pid=""
ext_pid=""
monitor_pid=""
k6_name="argos-bench-k6-$$"
bench_net="argos-bench-$$"
app_c="argos-bench-app-$$"
ext_c="argos-bench-ext-$$"
containers_started=0

# shellcheck disable=SC2329 # called from cleanup (EXIT trap)
stop_pid() {
  local pid="$1" name="$2" i
  [[ -n "$pid" ]] || return 0
  kill -0 "$pid" 2>/dev/null || return 0
  kill "$pid" 2>/dev/null || true
  for ((i = 0; i < 100; i++)); do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 0.1
  done
  echo "run-bench: $name did not stop on SIGTERM, killing it" >&2
  kill -9 "$pid" 2>/dev/null || true
}

# shellcheck disable=SC2329 # the EXIT trap runs it
cleanup() {
  local code=$?
  dock rm -f "$k6_name" >/dev/null 2>&1 || true
  stop_pid "$monitor_pid" "machine monitor"
  stop_pid "$app_pid" "shop-boot3"
  stop_pid "$ext_pid" "ext-stub"
  if ((containers_started)); then
    dock logs "$app_c" >"$out_dir/app.log" 2>&1 || true
    dock cp "$app_c:/tmp/gc.log" "$(native "$out_dir/gc.log")" >/dev/null 2>&1 || true
    dock logs "$ext_c" >"$out_dir/ext-stub.log" 2>&1 || true
    dock rm -f "$app_c" "$ext_c" >/dev/null 2>&1 || true
    dock network rm "$bench_net" >/dev/null 2>&1 || true
  fi
  rm -rf "$scratch"
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# Windows PID of a process started from Git Bash (ProcessHandle needs it); the PID elsewhere.
os_pid() {
  if [[ -r "/proc/$1/winpid" ]]; then cat "/proc/$1/winpid"; else echo "$1"; fi
}

# Free when the connection is refused (curl exit code 7). Windows refuses a closed loopback port
# only after about 2 s (SYN retries), hence the 5 s limit.
port_free() {
  local rc=0
  curl -s -o /dev/null --max-time 5 "http://127.0.0.1:$1/" || rc=$?
  [[ $rc -eq 7 ]]
}

alive() {
  if [[ "$apps" == process ]]; then
    kill -0 "$1" 2>/dev/null
  else
    [[ "$(dock inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" == true ]]
  fi
}

wait_ready() {
  local url="$1" who="$2" name="$3" log="$4" i
  for ((i = 0; i < 360; i++)); do
    if ! alive "$who"; then
      if [[ "$apps" == container ]]; then dock logs "$who" >"$log" 2>&1 || true; fi
      tail -n 40 "$log" >&2
      die "$name exited during startup (log: $log)"
    fi
    if curl -sf --max-time 5 "$url" 2>/dev/null | grep -q '"UP"'; then return 0; fi
    sleep 0.5
  done
  die "$name not ready after 180 s ($url, log: $log)"
}

# CPU seconds a container used so far (cgroup v2 cpu.stat usage_usec, v1 cpuacct.usage).
container_cpu_seconds() {
  local usec
  usec="$(dock exec "$1" sh -c 'cat /sys/fs/cgroup/cpu.stat 2>/dev/null' | sed -n 's/^usage_usec //p' | tr -d '\r')"
  if [[ -n "$usec" ]]; then
    awk -v u="$usec" 'BEGIN { printf "%.3f", u / 1e6 }'
    return
  fi
  usec="$(dock exec "$1" sh -c 'cat /sys/fs/cgroup/cpuacct/cpuacct.usage 2>/dev/null' | tr -d '\r')"
  [[ -n "$usec" ]] && awk -v n="$usec" 'BEGIN { printf "%.3f", n / 1e9 }' || echo ""
}

# --- k6 image and networking --------------------------------------------------------------------
if ! dock image inspect "$K6_IMAGE" >/dev/null 2>&1; then
  echo "run-bench: pulling $K6_IMAGE"
  dock pull "$K6_IMAGE" >/dev/null || die "docker pull $K6_IMAGE failed"
fi
net_args=()
user_args=()
[[ "$(uname -s)" == "Linux" ]] && user_args=(--user "$(id -u):$(id -g)")
if [[ -n "$docker_network" ]]; then
  net_args=(--network "$docker_network")
elif [[ -n "$target_url" ]]; then
  if [[ "$(uname -s)" == "Linux" ]]; then
    net_args=(--network host)
  else
    net_args=(--add-host "host.docker.internal:host-gateway")
  fi
elif [[ "$apps" == container ]]; then
  net_args=(--network "$bench_net")
elif [[ "$(uname -s)" == "Linux" ]]; then
  net_args=(--network host)
else
  # Docker Desktop with --apps process: host.docker.internal reaches ports bound to the host's
  # loopback, through Docker Desktop's proxy (see the note on --apps above).
  net_args=(--add-host "host.docker.internal:host-gateway")
fi

# --- start ext-stub and shop-boot3 ---------------------------------------------------------------
# ext-stub answers with TCP_NODELAY: otherwise the JDK HTTP server's two writes (headers, body) meet
# the Linux client's delayed ACK and every external call takes 40 ms (measured in containers).
ext_opts="-Dsun.net.httpserver.nodelay=true"
app_jdk=""
if ((start_apps)); then
  port_free "$ext_port" || die "port $ext_port is in use (pass --ext-port)"
  port_free "$app_port" || die "port $app_port is in use (pass --app-port)"
  if [[ "$apps" == process ]]; then
    echo "run-bench: starting ext-stub on 127.0.0.1:$ext_port"
    # shellcheck disable=SC2086 # ext_opts is a list of JVM options
    "$java_bin" $ext_opts "-Djava.io.tmpdir=$(native "$scratch")" -jar "$(native "$ext_jar")" \
      --port "$ext_port" --bind 127.0.0.1 --threads 64 >"$out_dir/ext-stub.log" 2>&1 &
    ext_pid=$!
    wait_ready "http://127.0.0.1:$ext_port/_stub/health" "$ext_pid" ext-stub "$out_dir/ext-stub.log"

    echo "run-bench: starting shop-boot3 on 127.0.0.1:$app_port ($app_opts${app_env[*]+ ${app_env[*]}})"
    (
      export SAMPLE_EXT_URL="http://127.0.0.1:$ext_port"
      export SERVER_PORT="$app_port"
      for kv in "${app_env[@]+"${app_env[@]}"}"; do export "${kv?}"; done
      # shellcheck disable=SC2086 # app_opts is a list of JVM options
      exec "$java_bin" $app_opts "-Xlog:gc:file=$(native "$out_dir/gc.log")" \
        "-Djava.io.tmpdir=$(native "$scratch")" -jar "$(native "$app_jar")" --server.address=127.0.0.1
    ) >"$out_dir/app.log" 2>&1 &
    app_pid=$!
    wait_ready "http://127.0.0.1:$app_port/actuator/health" "$app_pid" shop-boot3 "$out_dir/app.log"
    app_jdk="$("$java_bin" -version 2>&1 | head -n 1 | tr -d '\r')"
    if [[ "$(uname -s)" == "Linux" ]]; then
      k6_target="http://127.0.0.1:$app_port"
    else
      k6_target="http://host.docker.internal:$app_port"
    fi
  else
    dock network create "$bench_net" >/dev/null || die "docker network create $bench_net failed"
    containers_started=1
    echo "run-bench: starting $EXT_IMAGE (network $bench_net, published on 127.0.0.1:$ext_port)"
    dock run -d --name "$ext_c" --network "$bench_net" --network-alias ext-stub \
      -p "127.0.0.1:$ext_port:18080" -e "JAVA_OPTS=$ext_opts" "$EXT_IMAGE" --threads 64 >/dev/null \
      || die "ext-stub container failed"
    wait_ready "http://127.0.0.1:$ext_port/_stub/health" "$ext_c" ext-stub "$out_dir/ext-stub.log"

    echo "run-bench: starting $APP_IMAGE ($app_opts${app_env[*]+ ${app_env[*]}})"
    env_args=(-e "SAMPLE_EXT_URL=http://ext-stub:18080")
    for kv in "${app_env[@]+"${app_env[@]}"}"; do env_args+=(-e "$kv"); done
    dock run -d --name "$app_c" --network "$bench_net" --network-alias shop-boot3 \
      -p "127.0.0.1:$app_port:8080" -e "JAVA_OPTS=$app_opts -Xlog:gc:file=/tmp/gc.log" \
      "${env_args[@]}" "$APP_IMAGE" >/dev/null || die "shop-boot3 container failed"
    wait_ready "http://127.0.0.1:$app_port/actuator/health" "$app_c" shop-boot3 "$out_dir/app.log"
    app_jdk="$(dock exec "$app_c" java -version 2>&1 | head -n 1 | tr -d '\r') (container $APP_IMAGE)"
    k6_target="http://shop-boot3:8080"
  fi
else
  k6_target="$target_url"
fi

total_s=$((warmup_s + duration_s))
k6_version="$(dock run --rm -e K6_NO_USAGE_REPORT=true "$K6_IMAGE" version 2>&1 | head -n 1 | tr -d '\r')"
cat >"$out_dir/run.env" <<EOF
k6_image=$K6_IMAGE
k6_version=$k6_version
apps=$( ((start_apps)) && echo "$apps" || echo "external")
target_url=$k6_target
rate=$rate
duration=$duration
warmup=$warmup
pre_vus=$pre_vus
max_vus=$max_vus
gate=$gate
app_opts=$app_opts
app_env=${app_env[*]+${app_env[*]}}
app_jdk=$app_jdk
ext_stub_opts=$ext_opts
monitor_java=$java_bin (Java $java_major)
started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

monitor() {
  "$java_bin" "-Djava.io.tmpdir=$(native "$scratch")" -cp "$(native "$load_jar")" \
    io.argos.sample.load.MachineMonitor "$@"
}
echo "run-bench: machine load before the run"
monitor --duration 0 --interval 2s --label before --out "$(native "$out_dir/machine-before.json")"
grep -E '"(mean|availableProcessors)"' "$out_dir/machine-before.json" | sed 's/^ */  /' || true
pid_args=()
[[ -n "$app_pid" ]] && pid_args=(--pid "$(os_pid "$app_pid")")
# Samples until k6 ends (stop file); the duration is only an upper bound.
stop_file="$scratch/monitor.stop"
monitor --duration "$((total_s + 300))s" --interval 1s --label during \
  "${pid_args[@]+"${pid_args[@]}"}" --stop-file "$(native "$stop_file")" \
  --out "$(native "$out_dir/machine-during.json")" &
monitor_pid=$!
cpu_start=""
if ((containers_started)); then
  cpu_start="$(container_cpu_seconds "$app_c")"
  wall_start="$(date +%s%N)"
fi

# --- k6 -------------------------------------------------------------------------------------------
k6_out=()
((raw)) && k6_out=(--out "json=/out/k6-raw.json.gz")
echo "run-bench: k6 $rate/s for $duration after $warmup warm-up -> $k6_target"
set +e
dock run --rm --name "$k6_name" "${net_args[@]}" "${user_args[@]+"${user_args[@]}"}" \
  -v "$(native "$script_dir/bench.js"):/scripts/bench.js:ro" \
  -v "$(native "$out_dir"):/out" \
  -e K6_NO_USAGE_REPORT=true \
  -e TARGET_URL="$k6_target" -e RATE="$rate" -e DURATION="$duration" -e WARMUP="$warmup" \
  -e PRE_ALLOCATED_VUS="$pre_vus" -e MAX_VUS="$max_vus" -e GATE="$gate" \
  -e SUMMARY_PATH="/out/$summary_name" \
  "$K6_IMAGE" run --quiet --no-color --no-usage-report "${k6_out[@]+"${k6_out[@]}"}" /scripts/bench.js \
  2>&1 | tee "$out_dir/k6.log"
k6_code=${PIPESTATUS[0]}
set -e

touch "$stop_file"
if [[ -n "$cpu_start" ]]; then
  cpu_end="$(container_cpu_seconds "$app_c")"
  wall_end="$(date +%s%N)"
  awk -v a="$cpu_start" -v b="$cpu_end" -v s="$wall_start" -v e="$wall_end" 'BEGIN {
    wall = (e - s) / 1e9; cpu = b - a;
    printf "{\n  \"source\": \"cgroup cpu.stat of the shop-boot3 container\",\n";
    printf "  \"cpuSeconds\": %.3f,\n  \"wallSeconds\": %.3f,\n  \"cores\": %.3f\n}\n", cpu, wall, cpu / wall }' \
    >"$out_dir/app-cpu.json"
fi
wait "$monitor_pid" 2>/dev/null || true
monitor_pid=""
[[ -f "$out_dir/$summary_name" ]] || die "k6 wrote no summary (exit code $k6_code, log: $out_dir/k6.log)"
echo "run-bench: summary $out_dir/$summary_name (k6 exit code $k6_code)"
echo "run-bench: machine load during the run: $out_dir/machine-during.json"
grep -E '^ *"(min|mean|max|cpuSeconds|cpuCores|availableProcessors)"' "$out_dir/machine-during.json" \
  | sed 's/^ */  /' || true
[[ -f "$out_dir/app-cpu.json" ]] && grep -E '"(cpuSeconds|cores)"' "$out_dir/app-cpu.json" | sed 's/^ */  app /'
exit "$k6_code"
