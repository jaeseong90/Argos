#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# argos-lock.sh: working-tree locks under .gradle/argos-locks/<name> (ADR-0010 C-235 (8)). Several
# engineers and agents share one working tree and serialise heavy or conflicting work with them:
#   server-it  anything that runs the real server or rebuilds its boot jar (:server:integrationTest,
#              :server:securityTest, :server:bootJar / generateOpenApi / jibDockerBuild,
#              :deploy:composeSmoke, pnpm -C ui e2e:stack / e2e:docker / perf in route mode)
#   agent-it   agent integration runs;  quiet  measurements;  deps  dependency lock edits
# The TypeScript side of the same protocol is ui/e2e/stack/lock.ts (the E2E stack takes server-it).
#
# Usage:
#   argos-lock.sh run <name> --purpose <text> [--wait-seconds N] -- <command> [args...]
#       Takes the lock (owner = this helper process), runs the command, releases the lock when the
#       command ends (also on failure, Ctrl-C or TERM) and exits with the command's exit code. The
#       command gets ARGOS_LOCK_TOKEN_<NAME> (e.g. ARGOS_LOCK_TOKEN_SERVER_IT): a nested `run`, or the
#       E2E stack, of the same name inside it uses the held lock instead of waiting for it.
#       Prefer this form; it is the only one that works from a shell that exits right after the
#       command (an AI agent's tool call).
#   argos-lock.sh acquire <name> --purpose <text> [--pid PID] [--wait-seconds N]
#       Takes the lock for a long-lived caller (default owner: the calling shell, $PPID; --pid names
#       another process of this shell environment, e.g. $$) and prints its token on stdout. Prints
#       nothing when an enclosing `run` already holds it (then do not release it).
#   argos-lock.sh release <name> [--token T | --pid PID]
#       Deletes the lock only when its owner file has that token (or, without --token, names that
#       PID on this host). A lock of another run, or without owner file, is never deleted (exit 1).
#   argos-lock.sh status <name>      exit 0 free, 1 held, 3 held by a dead owner (stale)
#   argos-lock.sh pid-info [PID]     prints "<pid_kind> <pid>" of a shell PID (default $PPID)
#
# Protocol (the same in ui/e2e/stack/lock.ts):
# - The lock is the directory .gradle/argos-locks/<name>, created atomically with mkdir. Right
#   after creating it the holder writes owner.json inside it (temporary file + rename), one key per
#   line: version, name, token, pid, pid_kind, host, started_at (ISO-8601 UTC), purpose.
# - pid is the process that holds the lock, in the host's own numbering: on Windows (Git Bash,
#   Cygwin) the Windows PID (pid_kind "windows", from /proc/<pid>/winpid; MSYS PIDs are not used,
#   Node's process.pid is the Windows PID too), elsewhere the POSIX PID (pid_kind "posix").
#   Liveness: Windows `tasklist /FI "PID eq <pid>"`, POSIX `kill -0` (EPERM = alive).
# - Stale: only a lock whose owner file names this host (case-insensitive), this host's pid_kind and
#   a PID that is not alive. Missing or unreadable owner file, another host, another PID kind or an
#   unknown liveness: not stale. The acquirer then waits (bounded) and fails with a message naming
#   the owner and the manual removal (rm -rf <dir>, after checking that the owner is gone).
# - A stale lock is renamed to <name>.reap.<random> first and deleted only when the renamed owner
#   file still has the judged token (otherwise it is moved back).
#
# Environment: ARGOS_LOCK_DIR (lock root, tests), ARGOS_LOCK_WAIT_SECONDS (default wait 1800),
# ARGOS_LOCK_POLL_SECONDS (default 5).
# Exit codes: 0 success, 1 not taken / not released / failure, 2 usage error; `run` passes the
# command's exit code through.
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$script_dir/../.." && pwd)
LOCK_ROOT=${ARGOS_LOCK_DIR:-$repo_root/.gradle/argos-locks}
POLL_SECONDS=${ARGOS_LOCK_POLL_SECONDS:-5}
REPORT_SECONDS=60
OWNER_FILE=owner.json

say() { printf 'argos-lock: %s\n' "$*" >&2; }
usage() {
  sed -n '/^# Usage:/,/^# Protocol/p' "${BASH_SOURCE[0]}" | sed -e '$d' -e 's/^# \{0,1\}//' >&2
  exit 2
}

lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }
host_name() {
  local h=${HOSTNAME:-}
  [[ -n "$h" ]] || h=$(hostname 2>/dev/null || uname -n)
  printf '%s' "$h"
}
is_windows() { [[ -r /proc/$$/winpid ]]; }
if is_windows; then PID_KIND=windows; else PID_KIND=posix; fi
HOST_NAME=$(host_name)
HOST_LOWER=$(lower "$HOST_NAME")

# native_pid <shell pid>: the PID in this host's own numbering (Windows PID under Git Bash).
native_pid() {
  local p=$1
  [[ "$p" =~ ^[0-9]+$ && "$p" -gt 0 ]] || return 1
  if is_windows; then
    # PID 1 is what MSYS reports for a parent that is not an MSYS process.
    [[ "$p" -ne 1 && -r /proc/$p/winpid ]] || return 1
    tr -d '\r\n' </proc/"$p"/winpid
  else
    printf '%s' "$p"
  fi
}

# pid_alive <native pid>: 0 alive, 1 not alive, 2 unknown.
pid_alive() {
  local pid=$1 out
  if is_windows; then
    command -v tasklist >/dev/null 2>&1 || return 2
    out=$(MSYS_NO_PATHCONV=1 tasklist /FI "PID eq $pid" /NH /FO CSV 2>/dev/null | tr -d '\r') || return 2
    grep -q "^\"[^\"]*\",\"$pid\"," <<<"$out" && return 0
    return 1
  fi
  if out=$(export LC_ALL=C; kill -0 "$pid" 2>&1); then
    return 0
  fi
  case $out in
    *"No such process"*) return 1 ;;
    *"not permitted"*) return 0 ;;
  esac
  [[ -d /proc/$pid ]] && return 0
  return 2
}

new_token() { od -An -N8 -tx1 /dev/urandom | tr -d ' \n'; }

json_escape() {
  local s=$1
  s=${s//\\/\\\\}
  s=${s//\"/\\\"}
  printf '%s' "$s" | tr -d '\000-\037'
}

# json_get <file> <key>: the value of a one-key-per-line JSON file (owner.json), unquoted.
json_get() {
  sed -n "s/^[[:space:]]*\"$2\"[[:space:]]*:[[:space:]]*\(.*\)\$/\1/p" "$1" 2>/dev/null | head -n 1 |
    tr -d '\r' | sed -e 's/[[:space:]]*,[[:space:]]*$//' -e 's/^"\(.*\)"$/\1/'
}

# read_owner <lock dir>: sets O_TOKEN O_PID O_KIND O_HOST O_START O_PURPOSE; 1 when not usable.
read_owner() {
  local file=$1/$OWNER_FILE
  O_TOKEN='' O_PID='' O_KIND='' O_HOST='' O_START='' O_PURPOSE=''
  [[ -f "$file" ]] || return 1
  O_TOKEN=$(json_get "$file" token)
  O_PID=$(json_get "$file" pid)
  O_KIND=$(json_get "$file" pid_kind)
  O_HOST=$(json_get "$file" host)
  O_START=$(json_get "$file" started_at)
  O_PURPOSE=$(json_get "$file" purpose)
  [[ -n "$O_TOKEN" && "$O_PID" =~ ^[0-9]+$ && -n "$O_HOST" ]] || return 1
  [[ "$O_KIND" == windows || "$O_KIND" == posix ]] || return 1
}

# judge <lock dir>: sets VERDICT (free, held, stale), REASON and the O_* owner fields.
judge() {
  local dir=$1 rc
  O_TOKEN='' O_PID='' O_KIND='' O_HOST='' O_START='' O_PURPOSE=''
  if [[ ! -d "$dir" ]]; then
    VERDICT=free REASON=free
    return
  fi
  VERDICT=held
  if ! read_owner "$dir"; then
    REASON="it has no readable $OWNER_FILE (made by hand or by the old convention, or its owner is just starting)"
    O_PID=''
    return
  fi
  if [[ "$(lower "$O_HOST")" != "$HOST_LOWER" ]]; then
    REASON="its owner runs on another host ($O_HOST)"
    return
  fi
  if [[ "$O_KIND" != "$PID_KIND" ]]; then
    REASON="its owner PID is a $O_KIND PID, which this $PID_KIND host cannot check"
    return
  fi
  rc=0
  pid_alive "$O_PID" || rc=$?
  case $rc in
    0) REASON="its owner process $O_PID is alive" ;;
    1) VERDICT=stale REASON="its owner process $O_PID is not alive" ;;
    *) REASON="the liveness of PID $O_PID is unknown" ;;
  esac
}

describe_held() { # describe_held <name> <dir>, after judge
  local who check=''
  if [[ -n "$O_PID" ]]; then
    who="$2: PID $O_PID ($O_KIND) on $O_HOST since $O_START, purpose \"$O_PURPOSE\""
    if [[ "$O_KIND" == windows ]]; then
      check=" (check: tasklist /FI \"PID eq $O_PID\")"
    else
      check=" (check: ps -p $O_PID)"
    fi
  else
    who=$2
  fi
  printf 'lock %s is held by %s; %s. If that run is gone%s, remove the lock by hand: rm -rf "%s"' \
    "$1" "$who" "$REASON" "$check" "$2"
}

write_owner() { # write_owner <dir> <name> <token> <pid> <purpose>
  local tmp=$1/$OWNER_FILE.tmp-$3
  {
    printf '{\n'
    printf '  "version": 1,\n'
    printf '  "name": "%s",\n' "$2"
    printf '  "token": "%s",\n' "$3"
    printf '  "pid": %s,\n' "$4"
    printf '  "pid_kind": "%s",\n' "$PID_KIND"
    printf '  "host": "%s",\n' "$(json_escape "$HOST_NAME")"
    printf '  "started_at": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '  "purpose": "%s"\n' "$(json_escape "$5")"
    printf '}\n'
  } >"$tmp" && mv -f "$tmp" "$1/$OWNER_FILE"
}

reap() { # reap <dir> <judged token>
  local dir=$1 token=$2 reason=$REASON aside
  aside="$dir.reap.$(new_token)"
  mv "$dir" "$aside" 2>/dev/null || return 0
  if read_owner "$aside" && [[ "$O_TOKEN" == "$token" ]]; then
    rm -rf "$aside"
    say "removed the stale lock $dir ($reason)"
    return 0
  fi
  if [[ ! -e "$dir" ]] && mv "$aside" "$dir" 2>/dev/null; then
    return 0
  fi
  say "WARNING a fresh lock was moved to $aside while reaping a stale one and could not be moved back"
}

# try_acquire <name> <token> <pid> <purpose>: 0 taken; 1 held (judge globals describe the owner).
try_acquire() {
  local name=$1 dir=$LOCK_ROOT/$1 attempt
  mkdir -p "$LOCK_ROOT"
  for attempt in 0 1; do
    if mkdir "$dir" 2>/dev/null; then
      if ! write_owner "$dir" "$name" "$2" "$3" "$4"; then
        rm -rf "$dir"
        say "cannot write $dir/$OWNER_FILE"
        exit 1
      fi
      return 0
    fi
    [[ -d "$dir" ]] || { say "cannot create $dir"; exit 1; }
    judge "$dir"
    if [[ $attempt -eq 0 && "$VERDICT" == stale ]]; then
      reap "$dir" "$O_TOKEN"
      continue
    fi
    return 1
  done
  return 1
}

# acquire_wait <name> <token> <pid> <purpose> <wait seconds>
acquire_wait() {
  local name=$1 dir=$LOCK_ROOT/$1 wait=$5 start now waited last=-1000000
  start=$(date +%s)
  while :; do
    if try_acquire "$1" "$2" "$3" "$4"; then
      say "lock $name taken ($dir, PID $3, purpose \"$4\")"
      return 0
    fi
    now=$(date +%s)
    waited=$((now - start))
    if [[ $waited -ge $wait ]]; then
      say "gave up waiting $waited s for the $(describe_held "$name" "$dir")"
      return 1
    fi
    if [[ $((now - last)) -ge $REPORT_SECONDS ]]; then
      last=$now
      say "waiting up to $((wait - waited)) s more: $(describe_held "$name" "$dir")"
    fi
    sleep "$POLL_SECONDS"
  done
}

inherited_var() { printf 'ARGOS_LOCK_TOKEN_%s' "$(printf '%s' "$1" | tr 'abcdefghijklmnopqrstuvwxyz-' 'ABCDEFGHIJKLMNOPQRSTUVWXYZ_')"; }

# is_inherited <name>: an enclosing `run` of this process tree holds the lock.
is_inherited() {
  local var token
  var=$(inherited_var "$1")
  token=${!var:-}
  [[ -n "$token" ]] && read_owner "$LOCK_ROOT/$1" && [[ "$O_TOKEN" == "$token" ]]
}

check_name() {
  [[ "${1:-}" =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] || { say "invalid lock name '${1:-}' (lower-case letters, digits and '-')"; exit 2; }
}

# Options shared by the commands; sets NAME PURPOSE PID_ARG TOKEN_ARG WAIT and REST (after --).
parse() {
  NAME=${1:-}
  shift || true
  check_name "$NAME"
  PURPOSE='' PID_ARG='' TOKEN_ARG='' WAIT=${ARGOS_LOCK_WAIT_SECONDS:-1800}
  REST=()
  while [[ $# -gt 0 ]]; do
    case $1 in
      --purpose) PURPOSE=${2:?--purpose needs a value}; shift 2 ;;
      --pid) PID_ARG=${2:?--pid needs a value}; shift 2 ;;
      --token) TOKEN_ARG=${2:?--token needs a value}; shift 2 ;;
      --wait-seconds) WAIT=${2:?--wait-seconds needs a value}; shift 2 ;;
      --) shift; REST=("$@"); break ;;
      *) say "unknown argument: $1"; usage ;;
    esac
  done
  [[ "$WAIT" =~ ^[0-9]+$ ]] || { say "--wait-seconds must be a whole number of seconds"; exit 2; }
}

owner_pid() { # owner_pid <shell pid>: the native PID, or a usage error
  local native
  native=$(native_pid "$1") || {
    say "cannot tell the owner process from shell PID $1 (on Git Bash a non-MSYS parent shows as 1): use 'argos-lock.sh run', or pass --pid"
    exit 2
  }
  printf '%s' "$native"
}

cmd_acquire() {
  parse "$@"
  [[ -n "$PURPOSE" ]] || { say "--purpose is required"; exit 2; }
  if is_inherited "$NAME"; then
    say "lock $NAME is held by the enclosing run ($(inherited_var "$NAME")); not taken again"
    return 0
  fi
  local pid token
  pid=$(owner_pid "${PID_ARG:-$PPID}")
  token=$(new_token)
  acquire_wait "$NAME" "$token" "$pid" "$PURPOSE" "$WAIT" || exit 1
  printf '%s\n' "$token"
}

cmd_release() {
  parse "$@"
  local dir=$LOCK_ROOT/$NAME pid=''
  if [[ ! -d "$dir" ]]; then
    say "lock $NAME is not held"
    return 0
  fi
  if is_inherited "$NAME" && [[ -z "$TOKEN_ARG" || "$TOKEN_ARG" == "$O_TOKEN" ]]; then
    say "lock $NAME belongs to the enclosing run ($(inherited_var "$NAME")): left for it"
    return 0
  fi
  if ! read_owner "$dir"; then
    say "lock $NAME has no readable $OWNER_FILE: not ours, left as it is ($dir)"
    exit 1
  fi
  if [[ -n "$TOKEN_ARG" ]]; then
    [[ "$O_TOKEN" == "$TOKEN_ARG" ]] || {
      say "lock $NAME belongs to another run (PID $O_PID on $O_HOST, \"$O_PURPOSE\"): left as it is"
      exit 1
    }
  else
    pid=$(owner_pid "${PID_ARG:-$PPID}")
    [[ "$O_PID" == "$pid" && "$O_KIND" == "$PID_KIND" && "$(lower "$O_HOST")" == "$HOST_LOWER" ]] || {
      say "lock $NAME belongs to another run (PID $O_PID on $O_HOST, \"$O_PURPOSE\"): left as it is"
      exit 1
    }
  fi
  rm -rf "$dir"
  say "lock $NAME released"
}

cmd_status() {
  parse "$@"
  local dir=$LOCK_ROOT/$NAME
  judge "$dir"
  case $VERDICT in
    free) printf 'lock %s is free (%s)\n' "$NAME" "$dir"; return 0 ;;
    stale) printf '%s\n' "$(describe_held "$NAME" "$dir")"; exit 3 ;;
    *) printf '%s\n' "$(describe_held "$NAME" "$dir")"; exit 1 ;;
  esac
}

RUN_DIR='' RUN_TOKEN=''
release_run_lock() {
  if [[ -n "$RUN_TOKEN" ]] && read_owner "$RUN_DIR" && [[ "$O_TOKEN" == "$RUN_TOKEN" ]]; then
    rm -rf "$RUN_DIR"
    say "lock $(basename "$RUN_DIR") released"
  fi
  RUN_TOKEN=''
}

cmd_run() {
  parse "$@"
  [[ -n "$PURPOSE" ]] || { say "--purpose is required"; exit 2; }
  [[ ${#REST[@]} -gt 0 ]] || { say "run needs a command after --"; exit 2; }
  if is_inherited "$NAME"; then
    say "lock $NAME is held by the enclosing run ($(inherited_var "$NAME"))"
    exec "${REST[@]}"
  fi
  local pid token var rc=0
  pid=$(owner_pid "$$")
  token=$(new_token)
  RUN_DIR=$LOCK_ROOT/$NAME
  trap release_run_lock EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  acquire_wait "$NAME" "$token" "$pid" "$PURPOSE" "$WAIT" || exit 1
  RUN_TOKEN=$token
  var=$(inherited_var "$NAME")
  export "$var=$token"
  "${REST[@]}" || rc=$?
  exit "$rc"
}

cmd_pid_info() {
  local p=${1:-$PPID}
  printf '%s %s\n' "$PID_KIND" "$(owner_pid "$p")"
}

command=${1:-}
shift || true
case $command in
  run) cmd_run "$@" ;;
  acquire) cmd_acquire "$@" ;;
  release) cmd_release "$@" ;;
  status) cmd_status "$@" ;;
  pid-info) cmd_pid_info "$@" ;;
  -h | --help | help) usage ;;
  *) usage ;;
esac
