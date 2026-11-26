#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# fetch-coexist-agents.sh: puts the other Java agents of the compatibility matrix's coexistence rows
# (18 §6.4, T-MX-110~113, CoexistenceTest of agent-testing's matrixTest) into one folder, where the
# matrix tests find them through argos.test.matrix.agentsDir:
#
#   opentelemetry-javaagent-<version>.jar  from test/bench/fetch-otel-agent.sh (version and SHA-256
#                                          pinned there, Apache-2.0)
#   jacocoagent-<version>.jar              the JaCoCo agent runtime jar
#                                          (org.jacoco:org.jacoco.agent:<version>:runtime, EPL-2.0,
#                                          test-only, docs/licenses/dev-only.md), pinned below
#
# A file with another SHA-256 is deleted and the script fails. Both agents are test inputs only:
# fetched on demand by this script (operator or matrix runner), never during a test run, never by
# the Argos agent or server, and never part of an Argos artifact.
#
# usage: test/matrix/fetch-coexist-agents.sh [--dest <dir>]
#        (default <repo>/agent/agent-testing/build/matrix-agents; prints the folder)

set -euo pipefail

readonly JACOCO_VERSION="0.8.14"
readonly JACOCO_SHA256="3fb76eea65f81bd9415202bab34b6571728841dff1ab8e6bbe81adc2e299face"
readonly JACOCO_URL="https://repo1.maven.org/maven2/org/jacoco/org.jacoco.agent/$JACOCO_VERSION/org.jacoco.agent-$JACOCO_VERSION-runtime.jar"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
dest="$repo_dir/agent/agent-testing/build/matrix-agents"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dest)
      [[ $# -ge 2 && -n "$2" ]] || { echo "fetch-coexist-agents: --dest needs a folder" >&2; exit 2; }
      dest="$2"
      shift 2
      ;;
    -h | --help)
      sed -n '/^# usage:/,/^#        (/p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "fetch-coexist-agents: unknown argument $1 (--help)" >&2
      exit 2
      ;;
  esac
done

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

mkdir -p "$dest"

# OpenTelemetry: the bench script downloads and checks it; copy the verified jar.
otel_jar="$(bash "$repo_dir/test/bench/fetch-otel-agent.sh")"
otel_name="$(basename "$otel_jar")"
rm -f "$dest"/opentelemetry-javaagent-*.jar.part
for old in "$dest"/opentelemetry-javaagent-*.jar; do
  [[ -e "$old" && "$(basename "$old")" != "$otel_name" ]] && rm -f "$old"
done
cp -f "$otel_jar" "$dest/$otel_name"

# JaCoCo agent runtime jar.
jacoco_jar="$dest/jacocoagent-$JACOCO_VERSION.jar"
for old in "$dest"/jacocoagent-*.jar; do
  [[ -e "$old" && "$old" != "$jacoco_jar" ]] && rm -f "$old"
done
if [[ -f "$jacoco_jar" && "$(sha256_of "$jacoco_jar")" != "$JACOCO_SHA256" ]]; then
  echo "fetch-coexist-agents: $jacoco_jar has another SHA-256; deleting it and downloading again" >&2
  rm -f "$jacoco_jar"
fi
if [[ ! -f "$jacoco_jar" ]]; then
  part="$jacoco_jar.part"
  rm -f "$part"
  echo "fetch-coexist-agents: downloading $JACOCO_URL" >&2
  curl -fsSL --retry 3 -o "$part" "$JACOCO_URL"
  actual="$(sha256_of "$part")"
  if [[ "$actual" != "$JACOCO_SHA256" ]]; then
    rm -f "$part"
    echo "fetch-coexist-agents: SHA-256 mismatch: $actual (expected $JACOCO_SHA256); the file was deleted" >&2
    exit 1
  fi
  mv -f "$part" "$jacoco_jar"
fi

echo "$dest"
