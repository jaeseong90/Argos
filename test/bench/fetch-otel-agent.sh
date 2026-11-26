#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# fetch-otel-agent.sh: downloads the OpenTelemetry Java agent that the overhead bench measures as
# its reference point (01 §7.3 item 11, 18 §7.5; --reference otel, --sets ...,otel), checks its
# SHA-256 against the pinned value below (docs/poc/agent-overhead.md 9.1) and installs it as
# test/bench/build/otel/opentelemetry-javaagent-<version>.jar, where the harness finds it with
# --otel-agent auto. A file with another SHA-256 is deleted and the script fails.
#
# The OpenTelemetry Java agent is Apache-2.0 (on build-logic/allowed-licenses.json). It is a bench
# input only: fetched on demand by this script, never by the harness during a run, never by the
# Argos agent or server at runtime, and never part of an Argos artifact.
#
# usage: test/bench/fetch-otel-agent.sh        (prints the installed path)

set -euo pipefail

readonly VERSION="2.32.0"
readonly SHA256="f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82"
readonly URL="https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/$VERSION/opentelemetry-javaagent-$VERSION.jar"

case "${1:-}" in
  "") ;;
  -h | --help) sed -n '/^# usage:/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "fetch-otel-agent: unknown argument $1 (--help)" >&2; exit 2 ;;
esac

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dest="$script_dir/build/otel"
jar="$dest/opentelemetry-javaagent-$VERSION.jar"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

if [[ -f "$jar" ]]; then
  if [[ "$(sha256_of "$jar")" == "$SHA256" ]]; then
    echo "$jar"
    exit 0
  fi
  echo "fetch-otel-agent: $jar has another SHA-256; deleting it and downloading again" >&2
  rm -f "$jar"
fi

mkdir -p "$dest"
part="$jar.part"
rm -f "$part"
echo "fetch-otel-agent: downloading $URL" >&2
curl -fsSL --retry 3 -o "$part" "$URL"
actual="$(sha256_of "$part")"
if [[ "$actual" != "$SHA256" ]]; then
  rm -f "$part"
  echo "fetch-otel-agent: SHA-256 mismatch: $actual (expected $SHA256); the file was deleted" >&2
  exit 1
fi
mv -f "$part" "$jar"
echo "$jar"
