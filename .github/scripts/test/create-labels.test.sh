#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Tests for .github/scripts/create-labels.sh in --dry-run mode (never touches GitHub): the label set
# must match docs/19 section 8 / O-G09 and every label used by labeler.yml, the issue templates and
# dependabot.yml must be created by the script.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script="$here/../create-labels.sh"
github_dir=$(cd "$here/../.." && pwd)
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"

out=$(bash "$script" --dry-run --repo example/repo --backport 0.1)

for label in area/agent area/server area/ui area/deploy area/docs kind/bug kind/feature kind/compat-report \
  kind/security "good first issue" "help wanted" needs-matrix needs-info breaking-change no-changelog \
  dependencies backport/0.1; do
  quoted=$(printf '%q' "$label")
  expect_output "gh label create ${quoted} --repo example/repo" "label '${label}' is created" printf '%s\n' "$out"
done

# Every label referenced by repository configuration must be part of the label set.
referenced=$(
  {
    grep -E '^[a-z][a-z0-9/-]*:$' "$github_dir/labeler.yml" | tr -d ':'
    grep -hE '^labels: \[' "$github_dir"/ISSUE_TEMPLATE/*.yml | sed -E 's/^labels: \["([^"]+)"\]$/\1/'
    awk '/^ *labels:/ { inlist = 1; next } inlist && /^ *- / { sub(/^ *- */, ""); print; next } { inlist = 0 }' \
      "$github_dir/dependabot.yml"
  } | sort -u
)
while IFS= read -r label; do
  [[ -n "$label" ]] || continue
  quoted=$(printf '%q' "$label")
  expect_output "gh label create ${quoted} " "referenced label '${label}' is created" printf '%s\n' "$out"
done <<<"$referenced"

expect_exit 2 "invalid backport version is rejected" bash "$script" --dry-run --backport main
expect_exit 2 "unknown argument is rejected" bash "$script" --dry-run --bogus

finish
