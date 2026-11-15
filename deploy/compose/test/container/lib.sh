#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# POSIX assertion helpers of the container-side Compose script tests (dash, BusyBox ash). Prints
# one PASS or FAIL line per case; finish exits non-zero when a case failed.

TESTS_RUN=0
TESTS_FAILED=0
OUT=/tmp/argos-case-out
LAST=/tmp/argos-case-last

pass_case() {
  TESTS_RUN=$((TESTS_RUN + 1))
  echo "PASS  $1"
}
fail_case() {
  TESTS_RUN=$((TESTS_RUN + 1))
  TESTS_FAILED=$((TESTS_FAILED + 1))
  echo "FAIL  $1"
  if [ -s "$LAST" ]; then
    sed 's/^/      | /' "$LAST"
  fi
}

# expect_exit <code> <description> <command...>: the command's output is kept in $LAST.
expect_exit() {
  want=$1
  desc=$2
  shift 2
  got=0
  "$@" >"$OUT" 2>&1 || got=$?
  cp "$OUT" "$LAST"
  if [ "$got" -eq "$want" ]; then
    pass_case "$desc"
  else
    fail_case "$desc (exit $got, expected $want)"
  fi
}

# check <description> <command...>: passes when the command succeeds (its output goes to $LAST).
check() {
  desc=$1
  shift
  if "$@" >"$LAST" 2>&1; then
    pass_case "$desc"
  else
    fail_case "$desc"
  fi
}

# output_has <substring> <description>: the output of the last expect_exit contains it.
output_has() {
  if grep -qF -- "$1" "$LAST"; then
    pass_case "$2"
  else
    fail_case "$2 (output does not contain '$1')"
  fi
}

# output_lacks <substring> <description>: the output of the last expect_exit does not contain it.
output_lacks() {
  if grep -qF -- "$1" "$LAST"; then
    fail_case "$2 (output contains '$1')"
  else
    pass_case "$2"
  fi
}

# ls of one known path: POSIX has no portable stat format (GNU, BusyBox and BSD differ).
# shellcheck disable=SC2012
mode_of() { ls -ld "$1" | cut -c1-10; }
# shellcheck disable=SC2012
owner_of() { ls -lnd "$1" | awk '{ print $3 }'; }
first_line() {
  v=
  IFS= read -r v <"$1" || true
  printf '%s' "$v"
}

finish() {
  echo "$TESTS_RUN test(s), $TESTS_FAILED failure(s)"
  [ "$TESTS_FAILED" -eq 0 ]
}
