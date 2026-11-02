#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# bench.sh: builds what the bench harness needs (harness jar, release agent jar, sample app jars)
# and runs the harness (test/bench/build/bench/bench-harness.jar) from the repository root. It is
# the WO-02 run-overhead.sh, moved here by WO-05. bench-run.sh (18 §7.3) and bench-protocol.sh
# (benchTest) call it; use it directly for single sets, re-reports and the report generator.
#
# usage: test/bench/bench.sh [--no-build] <load|startup|report|publish> [harness options]
#   --no-build   use the jars already built (on a dedicated machine: build, ./gradlew --stop, then
#                run with --no-build so that no Gradle daemon is alive during the measurement)
# Examples:
#   test/bench/bench.sh load --out build/bench/main --java "$JAVA_HOME/bin/java"
#   test/bench/bench.sh startup --out build/bench/startup
#   test/bench/bench.sh --no-build report --out build/bench/main
#   test/bench/bench.sh --no-build publish --sets build/bench/main,build/bench/startup --out docs/bench
# java -jar test/bench/build/bench/bench-harness.jar --help lists every option.
# Exit code: the harness's (0 PASS, 2 usage, 3 FAIL, 4 INVALID, 1 other error).

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

build=1
if [[ "${1:-}" == "--no-build" ]]; then
  build=0
  shift
fi
[[ $# -ge 1 ]] || { sed -n '/^# usage:/,/^# Exit code/p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
command="$1"

if ((build)); then
  tasks=(:test:bench:benchJar)
  case "$command" in
    load) tasks+=(:agent:agent-dist:agentJar :sample-apps:ext-stub:sampleAppJar
      :sample-apps:shop-boot3:sampleAppJar) ;;
    startup) tasks+=(:agent:agent-dist:agentJar :sample-apps:shop-boot3:sampleAppJar
      :sample-apps:shop-heavy:sampleAppJar) ;;
  esac
  echo "bench: building ${tasks[*]}"
  (cd "$repo_root" && ./gradlew -q -Pargos.ui.skip=true "${tasks[@]}")
fi

java_bin="java"
if [[ -n "${JAVA_HOME:-}" ]]; then
  java_bin="$JAVA_HOME/bin/java"
fi
cd "$repo_root"
exec "$java_bin" -jar test/bench/build/bench/bench-harness.jar "$@"
