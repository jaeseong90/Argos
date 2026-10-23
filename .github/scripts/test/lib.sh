#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Minimal assertion helpers shared by the script tests in this directory.
# Source it, call expect_* for each case, then call finish.

TESTS_RUN=0
TESTS_FAILED=0

# expect_exit <code> <description> <command...>
expect_exit() {
  local want=$1 desc=$2
  shift 2
  local got=0
  "$@" >"${TMPDIR:-/tmp}/argos-test-out.$$" 2>&1 || got=$?
  TESTS_RUN=$((TESTS_RUN + 1))
  if [[ "$got" -eq "$want" ]]; then
    echo "PASS  $desc"
  else
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo "FAIL  $desc (exit $got, expected $want)"
    sed 's/^/      | /' "${TMPDIR:-/tmp}/argos-test-out.$$"
  fi
  rm -f "${TMPDIR:-/tmp}/argos-test-out.$$"
}

# expect_output <substring> <description> <command...>  (exit code is ignored)
expect_output() {
  local needle=$1 desc=$2
  shift 2
  local out
  out=$("$@" 2>&1 || true)
  TESTS_RUN=$((TESTS_RUN + 1))
  if [[ "$out" == *"$needle"* ]]; then
    echo "PASS  $desc"
  else
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo "FAIL  $desc (output does not contain '$needle')"
    printf '%s\n' "$out" | sed 's/^/      | /'
  fi
}

finish() {
  echo "${TESTS_RUN} test(s), ${TESTS_FAILED} failure(s)"
  [[ "$TESTS_FAILED" -eq 0 ]]
}
