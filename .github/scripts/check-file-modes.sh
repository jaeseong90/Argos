#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# check-file-modes.sh - executable bits of committed scripts in the git index.
#
# Usage: check-file-modes.sh [repository-root]
#
# CI and Linux/macOS clones take a file's mode from git, not from the file system it was written
# on. On Windows (core.filemode=false) `git add` records a new file as 100644 unless it is added
# with `git add --chmod=+x`; `./gradlew` then fails on every Linux runner with
# "Permission denied" (exit 126).
#
# Rules (checked on the index, so the script also works after `git add` and before committing):
#   M1  gradlew is tracked with mode 100755.
#   M2  every tracked *.sh file whose first line is a shebang (#!) has mode 100755.
#
# Exit status: 0 = every mode is correct, 1 = wrong or missing modes (the fix command is printed),
#              2 = usage error or not a git work tree.
set -euo pipefail

if [[ $# -gt 1 ]]; then
  echo "usage: check-file-modes.sh [repository-root]" >&2
  exit 2
fi
root=${1:-.}
if ! top=$(git -C "$root" rev-parse --show-toplevel 2>/dev/null); then
  echo "check-file-modes: '$root' is not inside a git work tree" >&2
  exit 2
fi

bad=()
problems=()
checked=0

# check_entry <mode> <path> <rule>
check_entry() {
  checked=$((checked + 1))
  if [[ "$1" != "100755" ]]; then
    bad+=("$2")
    problems+=("$2: mode $1, expected 100755 [$3]")
  fi
}

gradlew_entry=$(git -C "$top" ls-files -s -- gradlew)
if [[ -z "$gradlew_entry" ]]; then
  bad+=("gradlew")
  problems+=("gradlew: not tracked (CI runs ./gradlew directly) [M1]")
else
  read -r mode _ <<<"$gradlew_entry"
  check_entry "$mode" gradlew M1
fi

# `git ls-files -s -z` prints "<mode> <object> <stage>\t<path>\0"; the pathspec matches at any depth.
while IFS= read -r -d '' entry; do
  meta=${entry%%$'\t'*}
  path=${entry#*$'\t'}
  read -r mode object _ <<<"$meta"
  [[ "$mode" == "120000" ]] && continue # symbolic link
  first=$(git -C "$top" cat-file blob "$object" | head -c 2 || true)
  [[ "$first" == "#!" ]] || continue # sourced helper without a shebang
  check_entry "$mode" "$path" M2
done < <(git -C "$top" ls-files -s -z -- '*.sh')

if [[ ${#bad[@]} -gt 0 ]]; then
  echo "check-file-modes: ${#bad[@]} file(s) must be executable in git:" >&2
  printf '  %s\n' "${problems[@]}" >&2
  fix="git add --chmod=+x --"
  for f in "${bad[@]}"; do
    fix+=" $(printf '%q' "$f")"
  done
  {
    echo "Fix from the repository root, then commit (see docs/dev-setup.md, Windows):"
    echo "  $fix"
  } >&2
  exit 1
fi
echo "check-file-modes: ${checked} file(s) checked, all 100755"
