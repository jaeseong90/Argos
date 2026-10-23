#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# check-changelog.sh - require a CHANGELOG entry for every pull request (O-R10, DSN-19-11).
#
# Usage: check-changelog.sh <base> <head>
#
# Passes when the changes from merge-base(<base>, <head>) to <head> add at least one list entry
# ("- text" or "* text") inside the "## [Unreleased]" section of CHANGELOG.md, or when the pull
# request carries the `no-changelog` label (NO_CHANGELOG=true). The file must keep an
# "## [Unreleased]" section (Keep a Changelog 1.1).
#
# Environment:
#   NO_CHANGELOG=true        the pull request has the `no-changelog` label; skip the check.
#   CHANGELOG_FILE=<path>    file to check (default: CHANGELOG.md).
#
# Exit status: 0 = entry found or label present, 1 = no entry, 2 = usage error.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: check-changelog.sh <base> <head>" >&2
  exit 2
fi
base=$1
head=$2
file=${CHANGELOG_FILE:-CHANGELOG.md}

for ref in "$base" "$head"; do
  if [[ -z "$ref" ]] || ! git rev-parse --verify --quiet "${ref}^{commit}" >/dev/null; then
    echo "check-changelog: unknown commit '${ref}' (in CI, check out with fetch-depth: 0)" >&2
    exit 2
  fi
done

if ! git merge-base "$base" "$head" >/dev/null; then
  echo "check-changelog: ${base} and ${head} have no common ancestor" >&2
  exit 2
fi

if [[ "${NO_CHANGELOG:-false}" == "true" ]]; then
  echo "check-changelog: 'no-changelog' label present; skipping"
  exit 0
fi

new_file=$(mktemp)
trap 'rm -f "$new_file"' EXIT

if ! git show "${head}:${file}" >"$new_file" 2>/dev/null; then
  echo "check-changelog: ${file} does not exist at ${head}" >&2
  exit 1
fi

start=$(grep -n -E '^## \[Unreleased\]' "$new_file" | head -n 1 | cut -d: -f1 || true)
if [[ -z "$start" ]]; then
  echo "check-changelog: ${file} has no '## [Unreleased]' section (Keep a Changelog 1.1)" >&2
  exit 1
fi
end=$(awk -v s="$start" 'NR > s && /^## / { print NR - 1; found = 1; exit } END { if (!found) print NR }' "$new_file")

# Walk the zero-context diff and track line numbers in the new file; succeed if an added list
# entry lands inside [start, end].
if git diff --unified=0 --no-color --no-ext-diff "${base}...${head}" -- "$file" |
  awk -v s="$start" -v e="$end" '
    /^@@/ {
      in_hunk = 1
      split($3, p, ",")
      n = substr(p[1], 2) + 0
      next
    }
    !in_hunk { next }
    /^\+/ {
      if (n >= s && n <= e && $0 ~ /^\+[ \t]*[-*][ \t]+[^ \t]/) found = 1
      n++
      next
    }
    END { exit found ? 0 : 1 }
  '; then
  echo "check-changelog: found a new entry under '## [Unreleased]' in ${file}"
  exit 0
fi

cat >&2 <<EOF
check-changelog: no new entry under '## [Unreleased]' in ${file}.
Add one line describing the user-visible change to the matching subsection (Added, Changed,
Deprecated, Removed, Fixed, Security), for example:

  ### Fixed

  - Agent no longer records a second transaction for an async dispatch (#123).

If the change has no user impact (tests, CI, refactoring), ask a maintainer to add the
'no-changelog' label and re-run this check.
EOF
exit 1
