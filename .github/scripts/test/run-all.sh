#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Runs every *.test.sh in this directory and fails if any of them fails.
# Used by the `lint` job in .github/workflows/pr.yml; run it locally the same way:
#   bash .github/scripts/test/run-all.sh
set -uo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
failed=()
for t in "$here"/*.test.sh; do
  name=$(basename "$t")
  echo "=== ${name}"
  if ! bash "$t"; then
    failed+=("$name")
  fi
done

if [[ ${#failed[@]} -gt 0 ]]; then
  echo "FAILED: ${failed[*]}" >&2
  exit 1
fi
echo "All script tests passed."
