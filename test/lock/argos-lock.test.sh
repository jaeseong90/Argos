#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: C-235
# test-ids: T-BLD-040
#
# Tests for test/lock/argos-lock.sh (ADR-0010 C-235 (8)) in a throwaway lock root (ARGOS_LOCK_DIR):
# the owner file, run/acquire/release, the stale rule (only a dead PID of this host and PID kind),
# never deleting another run's lock (live owner, no owner file, another host), the bounded wait
# with the owner and the manual removal in the message, nesting through ARGOS_LOCK_TOKEN_<NAME>,
# and an owner file in the format of ui/e2e/stack/lock.ts. Runs on Linux, macOS and Git Bash.
#   bash test/lock/argos-lock.test.sh   (also run by .github/scripts/test/run-all.sh)
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
lock="$here/argos-lock.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$here/../../.github/scripts/test/lib.sh"

work=$(mktemp -d)
live_pid=
cleanup() {
  [[ -z "$live_pid" ]] || kill "$live_pid" 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT
export ARGOS_LOCK_DIR="$work/locks" ARGOS_LOCK_POLL_SECONDS=1
unset ARGOS_LOCK_TOKEN_SERVER_IT ARGOS_LOCK_WAIT_SECONDS
dir="$ARGOS_LOCK_DIR/server-it"
host=${HOSTNAME:-$(hostname 2>/dev/null || uname -n)}

# A live process of this host and the native PID of one that has exited, as "<kind> <pid>".
sleep 600 >/dev/null 2>&1 </dev/null &
live_pid=$!
read -r kind live < <(bash "$lock" pid-info "$live_pid")
sleep 30 >/dev/null 2>&1 </dev/null &
gone_shell=$!
read -r _ gone < <(bash "$lock" pid-info "$gone_shell")
kill "$gone_shell" 2>/dev/null || true
wait "$gone_shell" 2>/dev/null || true

# plant <host> <kind> <pid> [token]: a lock of another run, as ui/e2e/stack/lock.ts writes it
# (milliseconds in started_at).
plant() {
  rm -rf "$dir"
  mkdir -p "$dir"
  cat >"$dir/owner.json" <<EOF
{
  "version": 1,
  "name": "server-it",
  "token": "${4:-other-token}",
  "pid": $3,
  "pid_kind": "$2",
  "host": "$1",
  "started_at": "2026-11-24T01:02:03.456Z",
  "purpose": "ui e2e stack 20261124-010203-abcd"
}
EOF
}
has_dir() { [[ -d "$dir" ]]; }
token_is() { grep -q "\"token\": \"$1\"" "$dir/owner.json"; }

expect_exit 0 "status of a free lock is 0" bash "$lock" status server-it
expect_exit 2 "an invalid lock name is a usage error" bash "$lock" status ../server-it
expect_exit 2 "run without --purpose is a usage error" bash "$lock" run server-it -- true

# run: owner file keys, the token in the environment, release, exit code passed through.
out=$(bash "$lock" run server-it --purpose "unit test" -- bash -c '
  f="$ARGOS_LOCK_DIR/server-it/owner.json"
  for k in version name token pid pid_kind host started_at purpose; do grep -q "^  \"$k\": " "$f" || echo "missing $k"; done
  grep -q "\"token\": \"$ARGOS_LOCK_TOKEN_SERVER_IT\"" "$f" && echo token-exported
  grep -q "\"purpose\": \"unit test\"" "$f" && echo purpose-written
  grep -qE "\"started_at\": \"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\"" "$f" && echo utc-start
  exit 7' 2>/dev/null) && rc=0 || rc=$?
expect_exit 0 "run: owner file has every key, the purpose, an ISO-8601 UTC start and the exported token" \
  test "$out" == $'token-exported\npurpose-written\nutc-start'
expect_exit 0 "run passes the command's exit code (7)" test "$rc" -eq 7
expect_exit 1 "run releases the lock when the command ends" has_dir

# Nesting: an inner run, acquire and release of the same name use the held lock.
expect_output "nested-ok" "a nested run of the same name runs under the enclosing lock" \
  bash "$lock" run server-it --purpose outer -- bash "$lock" run server-it --purpose inner --wait-seconds 0 -- echo nested-ok
expect_exit 0 "a nested release leaves the enclosing lock" \
  bash "$lock" run server-it --purpose outer -- bash -c "bash '$lock' release server-it && test -d '$dir'"

# A live owner: never taken, never deleted; the bounded wait names the owner and the manual removal.
plant "$host" "$kind" "$live"
expect_exit 1 "status of a lock with a live owner is 1" bash "$lock" status server-it
expect_exit 1 "acquire gives up on a live owner after the wait" bash "$lock" acquire server-it --purpose t --pid $$ --wait-seconds 1
expect_output "PID $live ($kind) on $host since 2026-11-24T01:02:03.456Z, purpose \"ui e2e stack 20261124-010203-abcd\"" \
  "the message names the owner of a lock written by lock.ts" bash "$lock" acquire server-it --purpose t --pid $$ --wait-seconds 0
expect_output "remove the lock by hand: rm -rf \"$dir\"" "the message tells how to remove the lock by hand" \
  bash "$lock" acquire server-it --purpose t --pid $$ --wait-seconds 0
expect_exit 1 "release with another token refuses a foreign lock" bash "$lock" release server-it --token mine
expect_exit 1 "release by PID refuses a lock of another PID" bash "$lock" release server-it --pid $$
expect_exit 0 "the live owner's lock is still there" token_is other-token

# A dead owner PID on this host: stale, reaped and taken.
plant "$host" "$kind" "$gone"
expect_exit 3 "status of a lock whose owner PID is gone is 3 (stale)" bash "$lock" status server-it
token=$(bash "$lock" acquire server-it --purpose "takes the stale lock" --pid $$ --wait-seconds 0 2>/dev/null) || token=
expect_exit 0 "acquire reaps the stale lock and prints its own token" token_is "${token:-none}"
expect_exit 0 "release with the own token deletes the lock" bash "$lock" release server-it --token "${token:-none}"
expect_exit 1 "the lock is gone after the release" has_dir
expect_exit 0 "release of a lock that is not held is a no-op" bash "$lock" release server-it --token x

# Never stale: no owner file, another host, another PID kind (even with a dead PID).
rm -rf "$dir" && mkdir -p "$dir"
expect_exit 1 "a lock without owner file is held (not stale)" bash "$lock" status server-it
expect_output "has no readable owner.json" "acquire on a lock without owner file says so" \
  bash "$lock" acquire server-it --purpose t --pid $$ --wait-seconds 0
expect_exit 1 "release never deletes a lock without owner file" bash "$lock" release server-it --token x
expect_exit 0 "the lock without owner file is still there" has_dir
plant "another-host.example" "$kind" "$gone"
expect_exit 1 "a dead PID of another host is not stale" bash "$lock" acquire server-it --purpose t --pid $$ --wait-seconds 0
expect_exit 0 "the other host's lock is still there" token_is other-token
other_kind=posix
[[ "$kind" == posix ]] && other_kind=windows
plant "$host" "$other_kind" "$gone"
expect_exit 1 "a PID of another PID kind is not stale" bash "$lock" acquire server-it --purpose t --pid $$ --wait-seconds 0
expect_exit 0 "the other PID kind's lock is still there" token_is other-token

# Waiting: the lock is taken once its live owner releases it.
plant "$host" "$kind" "$live" waiting-owner
(sleep 2 && rm -rf "$dir") >/dev/null 2>&1 </dev/null &
releaser=$!
expect_exit 0 "acquire waits for the owner's release and then takes the lock" \
  bash "$lock" run server-it --purpose waiter --wait-seconds 30 -- true
wait "$releaser" 2>/dev/null || true

finish
