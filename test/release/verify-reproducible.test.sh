#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: SEC-73, O-R08
# test-ids: T-BLD-009a
#
# Tests for test/release/verify-reproducible.sh (T-BLD-009): usage errors without Gradle, then the
# `compare` command on fixture outputs through the real Gradle task compareReleaseOutputs
# (identical outputs, a jar whose entry times differ, a missing file). The Gradle cases need a JDK
# (JAVA_HOME or java on PATH, the JDK's jar tool) and are skipped with ARGOS_RELEASE_TEST_GRADLE=0.
# The two full builds of `run` are not repeated here (minutes each).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
script="$here/verify-reproducible.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$root/.github/scripts/test/lib.sh"

work="$(mktemp -d "${TMPDIR:-/tmp}/argos-repro-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/a" "$work/b"

# --- usage (exit 2, no build) ------------------------------------------------------------------
expect_exit 2 "no command: usage error" bash "$script"
expect_exit 2 "unknown command: usage error" bash "$script" verify
expect_exit 2 "compare with one directory: usage error" bash "$script" compare "$work/a"
expect_exit 2 "compare with a missing directory: usage error" bash "$script" compare "$work/a" "$work/none"
expect_exit 2 "run with an unknown option: usage error" bash "$script" run --fast
expect_exit 2 "run --agent-only --images: usage error" bash "$script" run --agent-only --images
expect_exit 2 "run --out without a value: usage error" bash "$script" run --out
expect_exit 2 "run with a non-numeric SOURCE_DATE_EPOCH: usage error" \
  env SOURCE_DATE_EPOCH=yesterday bash "$script" run --agent-only
expect_output "verify-reproducible.sh compare <dirA> <dirB>" "usage text names both commands" bash "$script" --help

# --- compare through Gradle --------------------------------------------------------------------
jar_tool=""
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jar" ]]; then
  jar_tool="$JAVA_HOME/bin/jar"
elif command -v jar >/dev/null 2>&1; then
  jar_tool="$(command -v jar)"
fi
if [[ "${ARGOS_RELEASE_TEST_GRADLE:-1}" == 0 || -z "$jar_tool" ]]; then
  echo "SKIP  compare through Gradle (ARGOS_RELEASE_TEST_GRADLE=0 or no JDK jar tool)"
  finish
  exit $?
fi

mkdir -p "$work/content/io/argos" "$work/same-a" "$work/same-b" "$work/diff-a" "$work/diff-b"
printf 'class bytes\n' >"$work/content/io/argos/A.class"
printf 'Manifest-Version: 1.0\n' >"$work/manifest.txt"
(cd "$work" && "$jar_tool" --create --file same-a/argos-agent-0.1.0.jar --date=1980-01-01T00:00:02Z -C content .)
cp "$work/same-a/argos-agent-0.1.0.jar" "$work/same-b/"
printf 'sums\n' | tee "$work/same-a/SHA256SUMS" >"$work/same-b/SHA256SUMS"
expect_exit 0 "compare: identical outputs exit 0" bash "$script" compare "$work/same-a" "$work/same-b"
expect_output "compare: IDENTICAL: 2 file(s)" "compare: the report says IDENTICAL" cat "$root/build/reproducibility/compare-report.txt"

(cd "$work" && "$jar_tool" --create --file diff-a/argos-agent-0.1.0.jar --date=1980-01-01T00:00:02Z -C content .)
(cd "$work" && "$jar_tool" --create --file diff-b/argos-agent-0.1.0.jar --date=2026-11-26T10:00:00Z -C content .)
printf 'only in A\n' >"$work/diff-a/extra.txt"
expect_exit 1 "compare: different outputs exit 1" bash "$script" compare "$work/diff-a" "$work/diff-b"
report="$root/build/reproducibility/compare-report.txt"
expect_output "compare: DIFFERENT" "compare: the report says DIFFERENT" cat "$report"
expect_output "argos-agent-0.1.0.jar!/io/argos/A.class" "compare: the differing entry is listed" cat "$report"
expect_output "likely cause: timestamps" "compare: entry times are named as the likely cause" cat "$report"
expect_output "extra.txt" "compare: a file only one output has is listed" cat "$report"

finish
