#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Runs every *.test.sh in this directory (release script tests: check-sbom, check-image-arch,
# trivy-scan, verify-reproducible) and fails if any of them fails. No Docker, no network. The Gradle cases of
# verify-reproducible.test.sh need a JDK; ARGOS_RELEASE_TEST_GRADLE=0 skips them.
#   bash test/release/run-all.sh
# Each script's PASS/FAIL/SKIP lines are also written to build/test-results/shell/release-tools/
# <script>.tap for the traceability report (test/traceability/shell-results.sh, test/README.md 6).
set -uo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=test/traceability/shell-results.sh
source "$here/../../test/traceability/shell-results.sh"
failed=()
for t in "$here"/*.test.sh; do
  name=$(basename "$t")
  echo "=== ${name}"
  if ! shell_results_run release-tools "$t"; then
    failed+=("$name")
  fi
done

if [[ ${#failed[@]} -gt 0 ]]; then
  echo "FAILED: ${failed[*]}" >&2
  exit 1
fi
echo "All release script tests passed."
