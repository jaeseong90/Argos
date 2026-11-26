#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Shell-suite result files for the traceability report (WO-53; docs/18 section 3, ADR-0010 C-200 (1),
# C-250 (6); format in test/README.md section 6).
#
# shell_results_run <suite> <script> [args...] runs `bash <script> [args...]`, shows its output
# unchanged (stdout through tee, stderr untouched) and returns the script's own exit code. The case
# lines of the shell test libraries become a TAP file:
#   ${ARGOS_SHELL_RESULTS_DIR:-<repo>/build/test-results/shell}/<suite>/<script file name>.tap
#
#   "PASS  <description>"  ->  ok <n> - <description>
#   "FAIL  <description>"  ->  not ok <n> - <description>
#   "SKIP  <description>"  ->  ok <n> - <description> # SKIP <reason>   (reason = last "(...)", if any)
#
# A case line starts at column 0, or after exactly two spaces: the relay of the container-side suites
# by expect_container_suite in deploy/compose/test/lib.sh. Deeper indented lines (captured command
# output, "      | ...") are never cases. '\' and '#' in a description are escaped as '\\' and '\#'
# (TAP 13). When the script exits non-zero without any FAIL line, a last case
# "not ok <n> - <script> exited <code>" records the failure, so a crash is never a green file.
# The file is rewritten on every run of the same script and suite.
#
# Use it from a runner:   source test/traceability/shell-results.sh; shell_results_run <suite> <script>
# or directly:            bash test/traceability/shell-results.sh <suite> <script> [args...]

_shell_results_root() {
  (cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
}

# _shell_results_escape <text>: TAP 13 escaping of '\' and '#'.
_shell_results_escape() {
  local s=${1//\\/\\\\}
  printf '%s' "${s//#/\\#}"
}

# _shell_results_tap <suite> <script path relative to the repository> <started> <exit> <output file>
# prints the TAP document.
_shell_results_tap() {
  local suite=$1 rel=$2 started=$3 rc=$4 out=$5
  local n=0 failed=0 line kind desc reason
  printf 'TAP version 13\n'
  printf '# argos-shell-results 1\n'
  printf '# suite: %s\n' "$suite"
  printf '# script: %s\n' "$rel"
  printf '# started: %s\n' "$started"
  printf '# exit: %s\n' "$rc"
  while IFS= read -r line || [[ -n "$line" ]]; do
    line=${line%$'\r'}
    [[ "$line" == "  "[A-Z]* ]] && line=${line:2}
    case "$line" in
      "PASS  "*) kind=PASS ;;
      "FAIL  "*) kind=FAIL ;;
      "SKIP  "*) kind=SKIP ;;
      *) continue ;;
    esac
    desc=${line:6}
    n=$((n + 1))
    case "$kind" in
      PASS) printf 'ok %d - %s\n' "$n" "$(_shell_results_escape "$desc")" ;;
      FAIL)
        failed=$((failed + 1))
        printf 'not ok %d - %s\n' "$n" "$(_shell_results_escape "$desc")"
        ;;
      SKIP)
        reason=""
        if [[ "$desc" =~ \(([^()]*)\)$ ]]; then
          reason=${BASH_REMATCH[1]}
        fi
        printf 'ok %d - %s # SKIP%s\n' "$n" "$(_shell_results_escape "$desc")" "${reason:+ $(_shell_results_escape "$reason")}"
        ;;
    esac
  done <"$out"
  if [[ "$rc" -ne 0 && "$failed" -eq 0 ]]; then
    n=$((n + 1))
    printf 'not ok %d - %s exited %s\n' "$n" "$(_shell_results_escape "${rel##*/}")" "$rc"
  fi
  printf '1..%d\n' "$n"
}

# shell_results_run <suite> <script> [args...]
shell_results_run() {
  if [[ $# -lt 2 || -z "$1" || -z "$2" ]]; then
    echo "usage: shell_results_run <suite> <script> [args...]" >&2
    return 2
  fi
  local suite=$1 script=$2
  shift 2
  if [[ ! "$suite" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "shell_results_run: invalid suite name '$suite' (letters, digits, '.', '_', '-')" >&2
    return 2
  fi
  local root abs rel name dir started out rc
  root=$(_shell_results_root)
  if [[ -f "$script" ]]; then
    abs="$(cd "$(dirname "$script")" && pwd)/$(basename "$script")"
  else
    abs=$script
  fi
  rel=${abs#"$root"/}
  name=$(basename "$script")
  dir="${ARGOS_SHELL_RESULTS_DIR:-$root/build/test-results/shell}/$suite"
  started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  out=$(mktemp "${TMPDIR:-/tmp}/argos-shell-results.XXXXXX")

  bash "$script" "$@" | tee "$out"
  rc=${PIPESTATUS[0]}

  if mkdir -p "$dir" 2>/dev/null &&
    _shell_results_tap "$suite" "$rel" "$started" "$rc" "$out" >"$dir/$name.tap.tmp" &&
    mv -f "$dir/$name.tap.tmp" "$dir/$name.tap"; then
    :
  else
    echo "shell_results_run: could not write $dir/$name.tap" >&2
    rm -f "$dir/$name.tap.tmp" 2>/dev/null
  fi
  rm -f "$out"
  return "$rc"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  set -uo pipefail
  shell_results_run "$@"
  exit $?
fi
