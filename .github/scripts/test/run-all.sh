#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Runs every *.test.sh in this directory and fails if any of them fails.
# Used by the `lint` job in .github/workflows/pr.yml; run it locally the same way:
#   bash .github/scripts/test/run-all.sh
# Each script's PASS/FAIL/SKIP lines are also written to build/test-results/shell/github-scripts/
# <script>.tap for the traceability report (test/traceability/shell-results.sh, test/README.md 6).
set -uo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=test/traceability/shell-results.sh
source "$here/../../../test/traceability/shell-results.sh"
failed=()
for t in "$here"/*.test.sh; do
  name=$(basename "$t")
  echo "=== ${name}"
  if ! shell_results_run github-scripts "$t"; then
    failed+=("$name")
  fi
done

if [[ ${#failed[@]} -gt 0 ]]; then
  echo "FAILED: ${failed[*]}" >&2
  exit 1
fi
echo "All script tests passed."
