#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Assertions for the release script tests, in addition to .github/scripts/test/lib.sh (source that
# first: it defines TESTS_RUN, TESTS_FAILED and finish).

# expect_run <exit> <substring> <description> <command...>: runs the command once; the exit code
# must be <exit> and the output (stdout and stderr) must contain <substring>.
expect_run() {
  local want=$1 needle=$2 desc=$3
  shift 3
  local out got=0
  out=$("$@" 2>&1) || got=$?
  TESTS_RUN=$((TESTS_RUN + 1))
  if [[ "$got" -eq "$want" && "$out" == *"$needle"* ]]; then
    echo "PASS  $desc"
  else
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo "FAIL  $desc (exit $got, expected $want; output must contain '$needle')"
    printf '%s\n' "$out" | sed 's/^/      | /'
  fi
}

# expect_no_output <substring> <description> <command...>: the output must not contain <substring>
# (the exit code is ignored).
expect_no_output() {
  local needle=$1 desc=$2
  shift 2
  local out
  out=$("$@" 2>&1 || true)
  TESTS_RUN=$((TESTS_RUN + 1))
  if [[ "$out" != *"$needle"* ]]; then
    echo "PASS  $desc"
  else
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo "FAIL  $desc (output contains '$needle')"
    printf '%s\n' "$out" | sed 's/^/      | /'
  fi
}
