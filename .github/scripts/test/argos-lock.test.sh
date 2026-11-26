#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: C-235
# test-ids: T-BLD-040
#
# Runs the tests of the working-tree lock helper test/lock/argos-lock.sh (ADR-0010 C-235 (8)) with
# the other script tests (the `lint` job of .github/workflows/pr.yml).
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
exec bash "$here/../../../test/lock/argos-lock.test.sh"
