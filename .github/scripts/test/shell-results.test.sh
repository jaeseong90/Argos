#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: DSN-18-01, C-200, C-250
#
# Tests for test/traceability/shell-results.sh (T-BLD-014i, WO-53): the PASS/FAIL/SKIP lines of a
# shell test become the TAP file of test/README.md section 6 (header, numbering, SKIP reason, TAP
# escaping), deeper indented captured output is ignored while the two-space relay of the container
# suites counts, the script's own output and exit code pass through unchanged, a failure without
# case lines is still recorded, the result directory is created and a rerun rewrites the file.
# Fixture scripts are generated in a temporary directory; nothing is written under build/.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/../../.." && pwd)
lib="$repo/test/traceability/shell-results.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export ARGOS_SHELL_RESULTS_DIR="$work/results"

# run_lib <args...>: runs shell_results_run in a fresh shell (as a runner would), stdout to $work/stdout.
run_lib() {
  bash -c 'set -uo pipefail; source "$1"; shift; shell_results_run "$@"' _ "$lib" "$@" >"$work/stdout"
}

# --- fixtures ----------------------------------------------------------------------------------------
fx="$work/fixtures"
mkdir -p "$fx"
cat >"$fx/mixed.test.sh" <<'EOF'
echo "=== header line"
echo "PASS  T-SEC-84a: first case"
echo "FAIL  second case (exit 1, expected 0)"
echo "      | PASS  captured output of a failing command"
echo "   PASS  three spaces is not a relay"
echo "  PASS  T-NFR-20c relayed container case"
echo "  FAIL  relayed container failure"
echo "        | FAIL  captured inside the container relay"
echo "SKIP  buf cases (Docker is not available)"
echo "SKIP  plain skip without reason"
echo "PASS  code anchors: <FILE>.md#<anchor> and a back\\slash"
printf 'PASS  windows line\r\n'
echo "----  container suite: 1 passed, 1 failed"
echo "PASSED not a case"
echo "to stderr" >&2
exit 1
EOF
cat >"$fx/green.test.sh" <<'EOF'
echo "PASS  only case"
EOF
cat >"$fx/crash.test.sh" <<'EOF'
echo "starting"
exit 5
EOF
cat >"$fx/aborted.test.sh" <<'EOF'
echo "PASS  one case before the abort"
exit 3
EOF
cat >"$fx/args.test.sh" <<'EOF'
echo "PASS  got $# args: $*"
EOF

tap="$ARGOS_SHELL_RESULTS_DIR/unit/mixed.test.sh.tap"

# --- conversion ----------------------------------------------------------------------------------------
rc=0
run_lib unit "$fx/mixed.test.sh" 2>"$work/stderr" || rc=$?
expect_exit 0 "T-BLD-014i the script's exit code (1) is returned" test "$rc" -eq 1
expect_exit 0 "T-BLD-014i the result directory <dir>/<suite> is created and the file is named <script>.tap" test -f "$tap"
expect_exit 0 "T-BLD-014i the script's stdout is shown unchanged" \
  diff <(bash "$fx/mixed.test.sh" 2>/dev/null) "$work/stdout"
expect_exit 0 "T-BLD-014i the script's stderr is passed through" grep -qx 'to stderr' "$work/stderr"

cat >"$work/expected-body" <<'EOF'
ok 1 - T-SEC-84a: first case
not ok 2 - second case (exit 1, expected 0)
ok 3 - T-NFR-20c relayed container case
not ok 4 - relayed container failure
ok 5 - buf cases (Docker is not available) # SKIP Docker is not available
ok 6 - plain skip without reason # SKIP
ok 7 - code anchors: <FILE>.md\#<anchor> and a back\\slash
ok 8 - windows line
1..8
EOF
expect_exit 0 "T-BLD-014i PASS/FAIL/SKIP lines become numbered TAP cases (relay counted, deeper indent ignored, '#' and '\\' escaped)" \
  diff "$work/expected-body" <(sed -n '7,$p' "$tap")
expect_exit 0 "T-BLD-014i the file starts with 'TAP version 13' and the format marker" \
  diff <(printf 'TAP version 13\n# argos-shell-results 1\n# suite: unit\n') <(sed -n '1,3p' "$tap")
expect_exit 0 "T-BLD-014i the header records the script path" grep -qx "# script: $fx/mixed.test.sh" "$tap"
expect_exit 0 "T-BLD-014i the header records the start time in UTC" \
  grep -qE '^# started: [0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' "$tap"
expect_exit 0 "T-BLD-014i the header records the exit code" grep -qx '# exit: 1' "$tap"
expect_exit 1 "T-BLD-014i a failing script with FAIL lines gets no extra 'exited' case" grep -q 'exited' "$tap"
no_cr() { ! grep -q $'\r' "$1"; }
expect_exit 0 "T-BLD-014i the file is LF only" no_cr "$tap"

# --- exit codes, failures without case lines -----------------------------------------------------------
rc=0
run_lib unit "$fx/green.test.sh" || rc=$?
expect_exit 0 "T-BLD-014i a green script returns 0" test "$rc" -eq 0
expect_exit 0 "T-BLD-014i a green script's file has its case, exit 0 and the plan" \
  diff <(printf '# exit: 0\nok 1 - only case\n1..1\n') <(sed -n '6,$p' "$ARGOS_SHELL_RESULTS_DIR/unit/green.test.sh.tap")

rc=0
run_lib unit "$fx/crash.test.sh" || rc=$?
expect_exit 0 "T-BLD-014i a script failing without case lines returns its code (5)" test "$rc" -eq 5
expect_exit 0 "T-BLD-014i a failure without case lines is recorded as 'not ok 1 - <script> exited <code>'" \
  diff <(printf 'not ok 1 - crash.test.sh exited 5\n1..1\n') <(sed -n '7,$p' "$ARGOS_SHELL_RESULTS_DIR/unit/crash.test.sh.tap")

rc=0
run_lib unit "$fx/aborted.test.sh" || rc=$?
expect_exit 0 "T-BLD-014i an aborted script with PASS lines only returns its code (3)" test "$rc" -eq 3
expect_exit 0 "T-BLD-014i an abort after PASS lines adds a failing 'exited' case" \
  diff <(printf 'ok 1 - one case before the abort\nnot ok 2 - aborted.test.sh exited 3\n1..2\n') \
  <(sed -n '7,$p' "$ARGOS_SHELL_RESULTS_DIR/unit/aborted.test.sh.tap")

# --- arguments, rerun, paths, direct use ---------------------------------------------------------------
run_lib unit "$fx/args.test.sh" one "two words"
expect_exit 0 "T-BLD-014i arguments after the script are passed to it" grep -qx 'PASS  got 2 args: one two words' "$work/stdout"
printf 'echo "PASS  rerun case"\n' >"$fx/args.test.sh"
run_lib unit "$fx/args.test.sh"
expect_exit 0 "T-BLD-014i a rerun rewrites the file" \
  diff <(printf 'ok 1 - rerun case\n1..1\n') <(sed -n '7,$p' "$ARGOS_SHELL_RESULTS_DIR/unit/args.test.sh.tap")
no_tmp_files() { [[ -z "$(find "$1" -name '*.tmp')" ]]; }
expect_exit 0 "T-BLD-014i no temporary file is left next to the result" no_tmp_files "$ARGOS_SHELL_RESULTS_DIR"

(cd "$repo" && bash -c 'set -uo pipefail; source "$1"; shell_results_run in-repo .github/scripts/test/lib.sh' _ "$lib" >/dev/null)
expect_exit 0 "T-BLD-014i a script inside the repository is recorded by its repository-relative path" \
  grep -qx '# script: .github/scripts/test/lib.sh' "$ARGOS_SHELL_RESULTS_DIR/in-repo/lib.sh.tap"
expect_exit 0 "T-BLD-014i a script without case lines that exits 0 has an empty plan" \
  diff <(printf '# exit: 0\n1..0\n') <(sed -n '6,$p' "$ARGOS_SHELL_RESULTS_DIR/in-repo/lib.sh.tap")

nested="$work/deep/a/b"
expect_exit 0 "T-BLD-014i a missing ARGOS_SHELL_RESULTS_DIR is created" \
  env ARGOS_SHELL_RESULTS_DIR="$nested" bash "$lib" direct "$fx/green.test.sh"
expect_exit 0 "T-BLD-014i direct use writes <dir>/<suite>/<script>.tap" test -f "$nested/direct/green.test.sh.tap"
expect_exit 5 "T-BLD-014i direct use returns the script's exit code" bash "$lib" direct "$fx/crash.test.sh"
expect_exit 2 "T-BLD-014i a missing script argument is a usage error" bash "$lib" direct
expect_exit 2 "T-BLD-014i a suite name with a path separator is rejected" bash "$lib" "../x" "$fx/green.test.sh"
expect_exit 1 "T-BLD-014i a rejected suite name writes nothing outside the result directory" test -e "$work/x"

ro="$work/not-a-dir"
: >"$ro"
rc=0
env ARGOS_SHELL_RESULTS_DIR="$ro" bash "$lib" unit "$fx/green.test.sh" >/dev/null 2>"$work/stderr" || rc=$?
expect_exit 0 "T-BLD-014i an unwritable result directory keeps the script's exit code" test "$rc" -eq 0
expect_exit 0 "T-BLD-014i an unwritable result directory is reported on stderr" grep -q 'could not write' "$work/stderr"

finish
