#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Helpers of the Compose script tests (host side, bash). Source it, call the expect_* functions,
# then finish. The cases that need POSIX file modes or a Linux userland run in pinned Linux
# containers (container/*.sh); this file starts them.

TESTS_RUN=0
TESTS_FAILED=0

here_lib=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
COMPOSE_DIR=$(cd "$here_lib/.." && pwd)
DEPLOY_DIR=$(cd "$COMPOSE_DIR/.." && pwd)

# Pinned Linux images (index digests, 2026-11-14). Ubuntu 22.04: dash as /bin/sh, mawk, no openssl
# (the /dev/urandom path of the scripts). Alpine 3: BusyBox ash and awk. The PostgreSQL image of
# deploy/postgres/image.properties: Debian dash with openssl (the openssl path).
# shellcheck disable=SC2034 # used by the test scripts that source this file
UBUNTU_IMAGE="ubuntu:22.04@sha256:74b12b11ffa550fba1929ad4d8d16533c34f6612728469671afba1b03b66881c"
# shellcheck disable=SC2034
ALPINE_IMAGE="alpine:3@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf"
# shellcheck disable=SC2034
POSTGRES_IMAGE=$(sed -n 's/^image=//p' "$DEPLOY_DIR/postgres/image.properties")

if command -v cygpath >/dev/null 2>&1; then
  # Git Bash on Windows: keep MSYS from rewriting container paths.
  export MSYS_NO_PATHCONV=1
fi
host_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

# require_docker: skip (exit 0) without Docker unless REQUIRE_DOCKER=1.
require_docker() {
  if ! docker info >/dev/null 2>&1; then
    if [[ "${REQUIRE_DOCKER:-0}" == "1" ]]; then
      echo "FAIL  Docker is required (REQUIRE_DOCKER=1) but not available"
      exit 1
    fi
    echo "SKIP  $(basename "$0") (Docker is not available)"
    exit 0
  fi
}

# in_container <image> [docker run options...] -- <command...>: deploy/compose at /src (read-only),
# this test directory at /t (read-only), network off, labelled for cleanup.
in_container() {
  local image=$1
  shift
  local opts=()
  while [[ $# -gt 0 && "$1" != "--" ]]; do
    opts+=("$1")
    shift
  done
  shift
  docker run --rm --network none --label io.argos.wo40=1 \
    -v "$(host_path "$COMPOSE_DIR"):/src:ro" -v "$(host_path "$here_lib"):/t:ro" \
    -e SRC=/src -e T=/t "${opts[@]}" "$image" "$@"
}

# expect_exit <code> <description> <command...>
expect_exit() {
  local want=$1 desc=$2
  shift 2
  local got=0
  "$@" >"${TMPDIR:-/tmp}/argos-compose-test.$$" 2>&1 || got=$?
  TESTS_RUN=$((TESTS_RUN + 1))
  if [[ "$got" -eq "$want" ]]; then
    echo "PASS  $desc"
  else
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo "FAIL  $desc (exit $got, expected $want)"
    sed 's/^/      | /' "${TMPDIR:-/tmp}/argos-compose-test.$$"
  fi
  rm -f "${TMPDIR:-/tmp}/argos-compose-test.$$"
}

# expect_container_suite <description> <image> [docker run options...] -- <command...>: runs a
# container-side suite; its PASS/FAIL lines are shown and counted.
expect_container_suite() {
  local desc=$1
  shift
  local out rc=0
  out=$(in_container "$@" 2>&1) || rc=$?
  printf '%s\n' "$out" | sed 's/^/  /'
  local passes fails
  passes=$(printf '%s\n' "$out" | grep -c '^PASS ' || true)
  fails=$(printf '%s\n' "$out" | grep -c '^FAIL ' || true)
  TESTS_RUN=$((TESTS_RUN + passes + fails))
  TESTS_FAILED=$((TESTS_FAILED + fails))
  if [[ "$rc" -ne 0 && "$fails" -eq 0 ]]; then
    TESTS_RUN=$((TESTS_RUN + 1))
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo "FAIL  $desc (container exit $rc without a FAIL line)"
  else
    echo "----  $desc: $passes passed, $fails failed"
  fi
}

finish() {
  echo "${TESTS_RUN} test(s), ${TESTS_FAILED} failure(s)"
  [[ "$TESTS_FAILED" -eq 0 ]]
}
