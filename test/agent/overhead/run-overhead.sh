#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# run-overhead.sh: builds what the WO-02 P-2 overhead harness needs (harness jar, release agent jar,
# sample app jars) and runs the harness from the repository root. Measurement procedure and
# options: test/agent/overhead/README.md and docs/poc/agent-overhead.md.
#
# usage: test/agent/overhead/run-overhead.sh [--no-build] <load|startup|report> [harness options]
#   --no-build   use the jars already built (on a dedicated machine: build, ./gradlew --stop, then
#                run with --no-build so that no Gradle daemon is alive during the measurement)
# Examples:
#   test/agent/overhead/run-overhead.sh load --out build/overhead/main --java "$JAVA_HOME/bin/java"
#   test/agent/overhead/run-overhead.sh load --out build/overhead/container --mode container
#   test/agent/overhead/run-overhead.sh startup --out build/overhead/startup
# Exit code: the harness's (0 PASS, 2 usage, 3 FAIL, 4 INVALID, 1 other error).

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../../.." && pwd)"

build=1
if [[ "${1:-}" == "--no-build" ]]; then
  build=0
  shift
fi
[[ $# -ge 1 ]] || { sed -n '/^# usage:/,/^# Exit code/p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
command="$1"

if ((build)); then
  tasks=(:test:agent:overhead:overheadJar :agent:agent-dist:agentJar
    :sample-apps:ext-stub:sampleAppJar :sample-apps:shop-boot3:sampleAppJar)
  if [[ "$command" == startup ]]; then
    tasks+=(:sample-apps:shop-heavy:sampleAppJar)
  fi
  echo "run-overhead: building ${tasks[*]}"
  (cd "$repo_root" && ./gradlew -q -Pargos.ui.skip=true "${tasks[@]}")
fi

java_bin="java"
if [[ -n "${JAVA_HOME:-}" ]]; then
  java_bin="$JAVA_HOME/bin/java"
fi
cd "$repo_root"
exec "$java_bin" -jar test/agent/overhead/build/overhead/overhead-bench.jar "$@"
