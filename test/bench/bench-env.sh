#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# bench-env.sh: the environment record of 18 §7.1 (01 §7.3 item 1) for the head of a benchmark
# report. It writes a JSON record (read by the bench harness: load/startup --env-record, copied
# into every set) with: app machine CPU model, logical CPUs and physical cores, memory, CPU governor,
# transparent huge pages, kernel, OS and an OS image hash, virtualization, the app JDK build
# (java -version), the agent jar's SHA-256 and version, the argos-agent.yml hash, the repository
# commit (sample apps), k6's version, the load machine (over ssh) and the ping RTT to it.
#
# The governor must be "performance" on every CPU (18 §7.1): otherwise the script stops (exit 4)
# before anything is measured. --allow-invalid-env is for smoke runs on shared or unsuitable
# machines: the problems are recorded, the record is marked invalid and every report made with it
# is INVALID. A machine without a machine profile (-Pbench.machine-profile, --machine-profile) is
# treated the same way: it is not a registered bench machine.
#
# usage: test/bench/bench-env.sh --out FILE [options]
#   --out FILE              where the JSON record goes (required)
#   --machine-profile ID    bench machine profile (benchTest's -Pbench.machine-profile)
#   --java PATH             java of the measured app ($JAVA_HOME/bin/java)
#   --agent JAR             agent jar (newest agent/agent-dist/build/libs/argos-agent-*.jar)
#   --agent-yml FILE        argos-agent.yml of the measured agent (none: settings come from -D)
#   --k6 PATH               local k6 (test/bench/build/k6/k6[.exe], else k6 on the PATH)
#   --loader USER@HOST      load machine: its CPU, memory, kernel and k6 over ssh, ping RTT to it
#   --os-image-id TEXT      the machine image's id or digest, added to the OS image hash
#   --no-cpufreq-ok REASON  accept a machine without cpufreq (a VM whose host pins the governor);
#                           the reason is recorded and the record stays valid
#   --allow-invalid-env     record the problems and continue (smoke runs); the report is INVALID
# Exit code: 0 record written (valid, or invalid with --allow-invalid-env), 4 stop (the machine is
# not fit and no override), 2 usage error.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

usage() { sed -n '/^# usage:/,/^# Exit code/p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
die() { echo "bench-env: $*" >&2; exit 2; }

out=""
profile=""
java_bin=""
agent=""
agent_yml=""
k6=""
loader=""
os_image_id=""
no_cpufreq_reason=""
allow_invalid=0
command_line="test/bench/bench-env.sh$(printf ' %q' "$@")"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) out="${2:?--out needs a value}"; shift 2 ;;
    --machine-profile) profile="${2-}"; shift 2 ;;
    --java) java_bin="${2:?--java needs a value}"; shift 2 ;;
    --agent) agent="${2:?--agent needs a value}"; shift 2 ;;
    --agent-yml) agent_yml="${2:?--agent-yml needs a value}"; shift 2 ;;
    --k6) k6="${2:?--k6 needs a value}"; shift 2 ;;
    --loader) loader="${2:?--loader needs a value}"; shift 2 ;;
    --os-image-id) os_image_id="${2:?--os-image-id needs a value}"; shift 2 ;;
    --no-cpufreq-ok) no_cpufreq_reason="${2:?--no-cpufreq-ok needs a reason}"; shift 2 ;;
    --allow-invalid-env) allow_invalid=1; shift ;;
    -h | --help) usage ;;
    *) die "unknown option $1 (--help lists the options)" ;;
  esac
done
[[ -n "$out" ]] || die "--out FILE is required"

# ---- helpers -------------------------------------------------------------------------------------

json_escape() {
  local s=$1
  s=${s//\\/\\\\}
  s=${s//\"/\\\"}
  s=${s//$'\t'/\\t}
  s=${s//$'\r'/}
  s=${s//$'\n'/\\n}
  printf '%s' "$s"
}
jstr() { if [[ -z "${1-}" ]]; then printf 'null'; else printf '"%s"' "$(json_escape "$1")"; fi; }
jnum() { if [[ "${1-}" =~ ^-?[0-9]+(\.[0-9]+)?$ ]]; then printf '%s' "$1"; else printf 'null'; fi; }
jarr() {
  local first=1 item
  printf '['
  for item in "$@"; do
    ((first)) || printf ', '
    first=0
    jstr "$item"
  done
  printf ']'
}
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@" | cut -d' ' -f1; else shasum -a 256 "$@" | cut -d' ' -f1; fi
}
sha256_text() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi
}
first_line() { head -n 1 | tr -d '\r'; }
os_kind="$(uname -s)"
is_windows() { [[ "$os_kind" == MINGW* || "$os_kind" == MSYS* || "$os_kind" == CYGWIN* ]]; }
powershell_value() { powershell -NoProfile -Command "$1" 2>/dev/null | tr -d '\r' | head -n 1; }

# ---- app machine ---------------------------------------------------------------------------------

os="$(uname -s -r -m)"
kernel="$(uname -r)"
cpu_model="unknown"
logical_cpus="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo "")"
physical_cores=""
memory_mb=""
thp="n/a"
virtualization="n/a"
os_release=""
power_plan=""
if is_windows; then
  # Before the /proc branch: Git Bash emulates /proc/cpuinfo and /proc/meminfo.
  cpu_model="$(powershell_value '(Get-CimInstance Win32_Processor | Select-Object -First 1).Name')"
  physical_cores="$(powershell_value '(Get-CimInstance Win32_Processor | Measure-Object NumberOfCores -Sum).Sum')"
  memory_mb="$(powershell_value '[math]::Floor((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1MB)')"
  os="$(powershell_value '(Get-CimInstance Win32_OperatingSystem).Caption + " " + (Get-CimInstance Win32_OperatingSystem).Version') ($os)"
  os_release="$os"
  # The localized text of powercfg is not UTF-8: keep the GUID of the active power plan.
  power_plan="power plan $(powercfg -getactivescheme 2>/dev/null | tr -d '\r' \
    | grep -o '[0-9a-fA-F]\{8\}-[0-9a-fA-F-]\{27\}' | head -n 1 || true)"
elif [[ -r /proc/cpuinfo ]]; then
  cpu_model="$(grep -m1 '^model name' /proc/cpuinfo | sed 's/^[^:]*:[[:space:]]*//' || true)"
  if command -v lscpu >/dev/null 2>&1; then
    physical_cores="$(lscpu -p=Core,Socket 2>/dev/null | grep -v '^#' | sort -u | wc -l | tr -d ' ')"
  fi
  memory_mb="$(awk '/^MemTotal:/ {printf "%d", $2 / 1024}' /proc/meminfo)"
  if [[ -r /sys/kernel/mm/transparent_hugepage/enabled ]]; then
    thp="$(sed -n 's/.*\[\(.*\)\].*/\1/p' /sys/kernel/mm/transparent_hugepage/enabled)"
  fi
  if command -v systemd-detect-virt >/dev/null 2>&1; then
    virtualization="$(systemd-detect-virt 2>/dev/null || true)"
  fi
  [[ -r /etc/os-release ]] && os_release="$(cat /etc/os-release)"
  pretty="$(sed -n 's/^PRETTY_NAME="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' /etc/os-release 2>/dev/null || true)"
  [[ -n "$pretty" ]] && os="$pretty ($os)"
elif [[ "$os_kind" == Darwin ]]; then
  cpu_model="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown)"
  physical_cores="$(sysctl -n hw.physicalcpu 2>/dev/null || true)"
  memory_mb="$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1048576 ))"
  os_release="$(sw_vers 2>/dev/null || true)"
fi
cpu_model="$(printf '%s' "$cpu_model" | sed 's/[[:space:]]*$//')"
os_image_sha="$(printf '%s\n%s\n%s\n' "$os_release" "$(uname -s -r -v -m)" "$os_image_id" | sha256_text)"

# CPU governor of every CPU (18 §7.1: "performance", else stop).
governors=""
shopt -s nullglob
governor_files=(/sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_governor)
shopt -u nullglob
if ((${#governor_files[@]} > 0)); then
  governors="$(cat "${governor_files[@]}" | sort -u | paste -sd, -)"
fi

# ---- JDK, agent, repository, k6 ------------------------------------------------------------------

if [[ -z "$java_bin" ]]; then
  java_bin="java"
  [[ -n "${JAVA_HOME:-}" ]] && java_bin="$JAVA_HOME/bin/java"
fi
java_version="$("$java_bin" -version 2>&1 | tr -d '\r' || echo "java -version failed")"

if [[ -z "$agent" ]]; then
  # The newest agent jar of the build (a glob and -nt instead of parsing ls -t output).
  shopt -s nullglob
  for agent_candidate in "$repo_root"/agent/agent-dist/build/libs/argos-agent-*.jar; do
    if [[ -z "$agent" || "$agent_candidate" -nt "$agent" ]]; then
      agent="$agent_candidate"
    fi
  done
  shopt -u nullglob
fi
agent_json="null"
if [[ -n "$agent" && -f "$agent" ]]; then
  manifest="$(unzip -p "$agent" META-INF/MANIFEST.MF 2>/dev/null || true)"
  if [[ -z "$manifest" && -x "$(dirname "$java_bin")/jar" ]]; then
    # No unzip (JDK images): the JDK's jar tool extracts the manifest into a temporary directory.
    jar_tmp="$(mktemp -d)"
    agent_abs="$(cd "$(dirname "$agent")" && pwd)/$(basename "$agent")"
    (cd "$jar_tmp" && "$(dirname "$java_bin")/jar" xf "$agent_abs" META-INF/MANIFEST.MF 2>/dev/null) || true
    manifest="$(cat "$jar_tmp/META-INF/MANIFEST.MF" 2>/dev/null || true)"
    rm -rf "$jar_tmp"
  fi
  agent_version="$(printf '%s\n' "$manifest" | tr -d '\r' | sed -n 's/^Implementation-Version: //p' | head -n 1)"
  agent_json="{\"name\": $(jstr "$(basename "$agent")"), \"bytes\": $(jnum "$(wc -c <"$agent" | tr -d ' ')"),"
  agent_json+=" \"sha256\": $(jstr "$(sha256 "$agent")"), \"version\": $(jstr "${agent_version:-unknown}")}"
fi
if [[ -n "$agent_yml" ]]; then
  [[ -f "$agent_yml" ]] || die "no such --agent-yml $agent_yml"
  agent_yml_json="$(jstr "$(basename "$agent_yml") SHA-256 $(sha256 "$agent_yml")")"
else
  agent_yml_json="$(jstr "none: the harness sets the agent by -Dargos.* (set.json records them and their SHA-256)")"
fi

commit="$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || echo "n/a")"
uncommitted="$(git --no-optional-locks -C "$repo_root" status --porcelain 2>/dev/null | wc -l | tr -d ' ' || echo "")"
sample_apps=""
for jar in "$repo_root"/sample-apps/{ext-stub,shop-boot3,shop-heavy}/build/sample-app/*.jar; do
  [[ -f "$jar" ]] || continue
  [[ -n "$sample_apps" ]] && sample_apps+=", "
  sample_apps+="$(jstr "$(basename "$jar")"): $(jstr "$(sha256 "$jar")")"
done

if [[ -z "$k6" ]]; then
  for candidate in "$script_dir/build/k6/k6" "$script_dir/build/k6/k6.exe"; do
    [[ -x "$candidate" ]] && k6="$candidate"
  done
  [[ -z "$k6" ]] && k6="$(command -v k6 || true)"
fi
k6_version="not installed on this machine"
[[ -n "$k6" ]] && k6_version="$("$k6" version 2>/dev/null | first_line || echo "k6 version failed")"

# ---- load machine ---------------------------------------------------------------------------------

loader_json="null"
ping_json="null"
if [[ -n "$loader" ]]; then
  facts="$(ssh -o BatchMode=yes "$loader" \
    'grep -m1 "^model name" /proc/cpuinfo | sed "s/^[^:]*:[[:space:]]*//"; nproc; awk "/^MemTotal:/ {printf \"%d\\n\", \$2 / 1024}" /proc/meminfo; uname -r; k6 version 2>/dev/null | head -n 1' \
    2>/dev/null || true)"
  [[ -n "$facts" ]] || die "ssh -o BatchMode=yes $loader failed (key authentication to the load machine, 18 §7.1)"
  mapfile -t f <<<"$facts"
  loader_json="{\"target\": $(jstr "$loader"), \"cpuModel\": $(jstr "${f[0]-}"), \"logicalCpus\": $(jnum "${f[1]-}"),"
  loader_json+=" \"memoryMb\": $(jnum "${f[2]-}"), \"kernel\": $(jstr "${f[3]-}"), \"k6\": $(jstr "${f[4]-}")}"
  host="${loader#*@}"
  if is_windows; then
    rtt="$(ping -n 5 "$host" 2>/dev/null | tr -d '\r' | sed -n 's/.*Minimum = \([0-9]*\)ms, Maximum = \([0-9]*\)ms, Average = \([0-9]*\)ms.*/min \1 ms, avg \3 ms, max \2 ms/p')"
  else
    rtt="$(ping -c 5 -q "$host" 2>/dev/null | sed -n 's#.*= \([0-9.]*\)/\([0-9.]*\)/\([0-9.]*\)/.*#min \1 ms, avg \2 ms, max \3 ms#p')"
  fi
  ping_json="$(jstr "${rtt:-ping failed}")"
fi

# ---- verdict -------------------------------------------------------------------------------------

problems=()
overrides=()
if [[ -z "$governors" ]]; then
  if [[ -n "$no_cpufreq_reason" ]]; then
    overrides+=("no cpufreq on this machine, accepted: $no_cpufreq_reason")
  else
    problems+=("CPU governor unavailable (no cpufreq${power_plan:+; $power_plan}), not performance (18 §7.1)")
  fi
elif [[ "$governors" != "performance" ]]; then
  problems+=("CPU governor $governors, not performance (18 §7.1; sudo cpupower frequency-set -g performance)")
fi
if [[ -z "$profile" ]]; then
  problems+=("no machine profile (--machine-profile, -Pbench.machine-profile): not a registered bench machine")
fi
valid=true
if ((${#problems[@]} > 0)); then
  valid=false
  if ((allow_invalid)); then
    overrides+=("--allow-invalid-env: smoke run, every report made with this record is INVALID")
  else
    for p in "${problems[@]}"; do echo "bench-env: $p" >&2; done
    echo "bench-env: stop: this machine is not fit for a benchmark verdict (--allow-invalid-env records it for a smoke run)" >&2
    exit 4
  fi
fi

mkdir -p "$(dirname "$out")"
{
  printf '{\n'
  printf '  "tool": "bench-env.sh",\n'
  printf '  "format": 1,\n'
  printf '  "recordedAt": %s,\n' "$(jstr "$(date -u +%Y-%m-%dT%H:%M:%SZ)")"
  printf '  "command": %s,\n' "$(jstr "$command_line")"
  printf '  "machineProfile": %s,\n' "$(jstr "$profile")"
  printf '  "app": {\n'
  printf '    "os": %s,\n' "$(jstr "$os")"
  printf '    "kernel": %s,\n' "$(jstr "$kernel")"
  printf '    "cpuModel": %s,\n' "$(jstr "$cpu_model")"
  printf '    "logicalCpus": %s,\n' "$(jnum "$logical_cpus")"
  printf '    "physicalCores": %s,\n' "$(jnum "$physical_cores")"
  printf '    "memoryMb": %s,\n' "$(jnum "$memory_mb")"
  printf '    "governor": %s,\n' "$(jstr "${governors:-unavailable}")"
  printf '    "powerPlan": %s,\n' "$(jstr "${power_plan:-}")"
  printf '    "thp": %s,\n' "$(jstr "$thp")"
  printf '    "virtualization": %s,\n' "$(jstr "$virtualization")"
  printf '    "osImageSha256": %s,\n' "$(jstr "$os_image_sha")"
  printf '    "osImageId": %s\n' "$(jstr "$os_image_id")"
  printf '  },\n'
  printf '  "jdk": {"path": %s, "version": %s},\n' "$(jstr "$java_bin")" "$(jstr "$java_version")"
  printf '  "agentJar": %s,\n' "$agent_json"
  printf '  "agentYml": %s,\n' "$agent_yml_json"
  printf '  "repository": {"commit": %s, "uncommittedEntries": %s},\n' "$(jstr "$commit")" "$(jnum "$uncommitted")"
  printf '  "sampleApps": {%s},\n' "$sample_apps"
  printf '  "k6": %s,\n' "$(jstr "$k6_version")"
  printf '  "loader": %s,\n' "$loader_json"
  printf '  "pingRtt": %s,\n' "$ping_json"
  printf '  "valid": %s,\n' "$valid"
  printf '  "problems": %s,\n' "$(jarr "${problems[@]+"${problems[@]}"}")"
  printf '  "overrides": %s\n' "$(jarr "${overrides[@]+"${overrides[@]}"}")"
  printf '}\n'
} >"$out"

echo "bench-env: $out (valid: $valid; governor ${governors:-unavailable}; ${cpu_model}; ${logical_cpus} CPUs)"
if [[ "$valid" == false ]]; then
  for p in "${problems[@]}"; do echo "bench-env: recorded problem: $p"; done
fi
exit 0
