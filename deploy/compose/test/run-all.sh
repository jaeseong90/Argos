#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Runs every *.test.sh of the Compose installation tests and fails if any of them fails:
#   bash deploy/compose/test/run-all.sh
# They need Docker (pinned Linux containers, no network) and are skipped without it unless
# REQUIRE_DOCKER=1. The end-to-end run with a real stack is ./gradlew :deploy:composeSmoke
# (compose-smoke.sh), not part of this suite.
# Each script's PASS/FAIL/SKIP lines (with the relayed container-side cases) are also written to
# build/test-results/shell/deploy-compose/<script>.tap for the traceability report
# (test/traceability/shell-results.sh, test/README.md section 6).
set -uo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=test/traceability/shell-results.sh
source "$here/../../../test/traceability/shell-results.sh"
failed=()
for t in "$here"/*.test.sh; do
  name=$(basename "$t")
  echo "=== ${name}"
  if ! shell_results_run deploy-compose "$t"; then
    failed+=("$name")
  fi
done

if [[ ${#failed[@]} -gt 0 ]]; then
  echo "FAILED: ${failed[*]}" >&2
  exit 1
fi
echo "All Compose script tests passed."
