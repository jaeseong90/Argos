#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: C-255
#
# Tests of ops-report.sh (WO-42, proposed T-BLD-030; docs/dogfooding/ops-checklist.md) without a
# stack in pinned Linux containers (container/ops-report-cases.sh): the daily row and sections from
# the answers of the docker test double and from backup sets on disk, the thresholds of the daily
# checklist, failed sections (exit 1), usage errors (exit 2), no secret, name, token or address in
# the output and no docker call or SQL that writes. Ubuntu 22.04 (dash, mawk, GNU tools) and Alpine
# (BusyBox ash, awk, sed). Requires Docker (skipped without it unless REQUIRE_DOCKER=1). The real
# stack is covered by compose-smoke.sh (one ops-report.sh run against the real schema).
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/compose/test/lib.sh
source "$here/lib.sh"
require_docker

expect_container_suite "ops-report cases on Ubuntu 22.04 (dash, mawk)" \
  "$UBUNTU_IMAGE" -- sh /t/container/ops-report-cases.sh
expect_container_suite "ops-report cases on Alpine (BusyBox ash, awk, sed)" \
  "$ALPINE_IMAGE" -- sh /t/container/ops-report-cases.sh

finish
