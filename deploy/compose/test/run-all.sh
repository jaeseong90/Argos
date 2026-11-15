#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Runs every *.test.sh of the Compose installation tests and fails if any of them fails:
#   bash deploy/compose/test/run-all.sh
# They need Docker (pinned Linux containers, no network) and are skipped without it unless
# REQUIRE_DOCKER=1. The end-to-end run with a real stack is ./gradlew :deploy:composeSmoke
# (compose-smoke.sh), not part of this suite.
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
echo "All Compose script tests passed."
