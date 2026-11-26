#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: SEC-06, SEC-84
#
# Tests of diag-bundle.sh (./argos-compose.sh diag; WO-43) without a stack in pinned Linux
# containers (container/diag-cases.sh): the 0600 bundle under umask 022 (T-SEC-84d), the server's
# argos-admin bundle copied out of the container and deleted there, .env and container log masking,
# no docker compose config and nothing of secrets/ or tls/ (T-SEC-06i logic), failed sections and
# usage errors. Ubuntu 22.04 (dash, GNU tar) and Alpine (BusyBox ash, tar and sed). Requires Docker
# (skipped without it unless REQUIRE_DOCKER=1). The real stack is covered by compose-smoke.sh.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/compose/test/lib.sh
source "$here/lib.sh"
require_docker

expect_container_suite "diag-bundle cases on Ubuntu 22.04 (dash, GNU tar)" \
  "$UBUNTU_IMAGE" -- sh /t/container/diag-cases.sh
expect_container_suite "diag-bundle cases on Alpine (BusyBox ash, tar, sed)" \
  "$ALPINE_IMAGE" -- sh /t/container/diag-cases.sh

finish
