#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# check-spdx.sh: every source file carries the SPDX header in its first 5 lines (19 §5.1, O-L07,
# T-BLD-007). Spotless enforces the exact Java header; this script covers every language.
#
#   Files checked: *.java *.kt *.kts *.ts *.tsx *.mts *.cts *.js *.jsx *.mjs *.cjs *.proto *.sql
#                  *.sh *.bash *.py *.ps1 *.bat *.cmd *.yml *.yaml *.toml *.properties *.css *.html
#   Required:      "SPDX-License-Identifier: Apache-2.0" in one of the first 5 lines, in the
#                  comment syntax of the language (//, /* */, #, --, <!-- -->, REM or ::; a shebang
#                  line may come first). The whole expression after the colon must be exactly
#                  Apache-2.0: "Apache-2.0 AND ...", "Apache-2.0 WITH ..." or "Apache-2.0 OR ..." fail
#   File set:      in a git work tree, tracked files plus untracked files that are not ignored
#                  (git ls-files); otherwise every file below the root except build outputs,
#                  dependencies and VCS/IDE directories
#   Exclusions:    test/lint/spdx-exclusions.txt below the root (glob per line, reason required)
#
# Usage:  test/lint/check-spdx.sh [root]   (default: the repository root, two levels up)
# Exit:   0 when every file has the header, 1 otherwise (usage errors included).

set -euo pipefail
export LC_ALL=C

readonly EXPECTED="Apache-2.0"
readonly HEADER_LINES=5
readonly EXTENSIONS_RE='\.(java|kt|kts|ts|tsx|mts|cts|js|jsx|mjs|cjs|proto|sql|sh|bash|py|ps1|bat|cmd|yml|yaml|toml|properties|css|html)$'

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="${1:-$script_dir/../..}"
if [[ $# -gt 1 || ! -d "$root" ]]; then
  echo "usage: $0 [root]" >&2
  exit 1
fi
root="$(cd "$root" && pwd)"
cd "$root"

work="$(mktemp -d "${TMPDIR:-/tmp}/argos-check-spdx.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# --- file set --------------------------------------------------------------------------------
if git -C "$root" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  mode="git ls-files"
  git -C "$root" ls-files -z --cached --others --exclude-standard | tr '\0' '\n' >"$work/all.txt"
else
  mode="find"
  # Mirrors .gitignore: ui/dist is the only "dist" output (agent-dist has a source package "dist").
  find . \( -name .git -o -name build -o -name node_modules -o -name .gradle -o -name .kotlin \
    -o -path ./ui/dist -o -name coverage -o -name test-results -o -name playwright-report \
    -o -name .idea -o -name .vscode \) -prune -o -type f -print |
    sed 's#^\./##' >"$work/all.txt"
fi
# Tracked files deleted from the work tree are skipped (the -f test below).
grep -E "$EXTENSIONS_RE" "$work/all.txt" | sort -u >"$work/candidates.txt" || true

# --- exclusions ------------------------------------------------------------------------------
exclusions="$root/test/lint/spdx-exclusions.txt"
: >"$work/excluded.txt"
cp "$work/candidates.txt" "$work/files.txt"
if [[ -f "$exclusions" ]]; then
  bad_exclusions=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    pattern="${line%%#*}"
    pattern="${pattern%"${pattern##*[![:space:]]}"}"
    if [[ "$line" != *"#"* || -z "${line#*#}" ]]; then
      echo "check-spdx: exclusion without a reason (\"<glob>  # reason\"): $line" >&2
      bad_exclusions=1
      continue
    fi
    : >"$work/next.txt"
    while IFS= read -r file; do
      # shellcheck disable=SC2053 # glob match on purpose
      if [[ "$file" == $pattern ]]; then echo "$file" >>"$work/excluded.txt"; else echo "$file" >>"$work/next.txt"; fi
    done <"$work/files.txt"
    mv "$work/next.txt" "$work/files.txt"
  done <"$exclusions"
  ((bad_exclusions == 0)) || exit 1
fi

# --- header check ----------------------------------------------------------------------------
: >"$work/missing.txt"
: >"$work/nonempty.txt"
: >"$work/present.txt"
while IFS= read -r file; do
  if [[ ! -f "$file" ]]; then
    continue
  elif [[ -s "$file" ]]; then
    echo "$file" >>"$work/nonempty.txt"
  else
    echo "$file  (empty file)" >>"$work/missing.txt"
  fi
  echo "$file" >>"$work/present.txt"
done <"$work/files.txt"
mv "$work/present.txt" "$work/files.txt"

if [[ -s "$work/nonempty.txt" ]]; then
  # One awk process per xargs batch: prints "<file>\t<license expression or empty>" for each file.
  # The expression is the rest of the line after the identifier, without a comment terminator
  # (*/, -->), trailing whitespace or CR, so "Apache-2.0 AND GPL-3.0-only" is compared as a whole.
  # shellcheck disable=SC2016 # the single-quoted text is an awk program
  tr '\n' '\0' <"$work/nonempty.txt" |
    xargs -0 awk -v max="$HEADER_LINES" '
      function flush() { if (current != "") printf "%s\t%s\n", current, id }
      FNR == 1 { flush(); current = FILENAME; id = "" }
      FNR <= max && id == "" && match($0, /SPDX-License-Identifier:/) {
        expression = substr($0, RSTART + RLENGTH)
        sub(/\r$/, "", expression)
        sub(/\*\/.*$/, "", expression)
        sub(/-->.*$/, "", expression)
        gsub(/\t/, " ", expression)
        sub(/^ +/, "", expression)
        sub(/ +$/, "", expression)
        id = expression
      }
      END { flush() }' >"$work/ids.tsv"
  awk -F '\t' -v expected="$EXPECTED" -v max="$HEADER_LINES" '
    $2 == "" { printf "%s  (no SPDX-License-Identifier in the first %d lines)\n", $1, max; next }
    $2 != expected { printf "%s  (SPDX-License-Identifier: %s, expected %s)\n", $1, $2, expected }
  ' "$work/ids.tsv" >>"$work/missing.txt"
fi

checked="$(wc -l <"$work/files.txt" | tr -d ' ')"
excluded="$(wc -l <"$work/excluded.txt" | tr -d ' ')"
if [[ -s "$work/missing.txt" ]]; then
  echo "check-spdx: FAILED: files without the header '// SPDX-License-Identifier: $EXPECTED'" \
    "(or the comment form of the language):"
  sort "$work/missing.txt" | sed 's/^/    /'
  echo "check-spdx: $(wc -l <"$work/missing.txt" | tr -d ' ') of $checked files failed ($mode, $excluded excluded)"
  exit 1
fi
echo "check-spdx: OK ($checked files, $mode, $excluded excluded)"
