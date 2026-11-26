#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: SEC-47, SEC-55, SEC-73, SEC-74, SEC-75, SEC-76
#
# Tests of the logic of smoke.sh without a running stack: the file-mode and secret checks on a
# container-local file system, and the published-port check on recorded `docker compose ps
# --format json` output, each with the mutations that must fail (T-SEC-74, T-SEC-75, SEC-55); the
# image, network, user, health, Actuator and setup checks on the answers of the docker and curl
# test doubles (SEC-73, SEC-76, T-SEC-55, T-SEC-47; by default no sign-in), and the interrupts.
# Runs container/smoke-cases.sh on Ubuntu (dash, mawk) and Alpine (BusyBox). Requires Docker
# (skipped without it unless REQUIRE_DOCKER=1).
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/compose/test/lib.sh
source "$here/lib.sh"
require_docker

expect_container_suite "smoke.sh logic on Ubuntu 22.04 (dash, mawk)" \
  "$UBUNTU_IMAGE" -- sh /t/container/smoke-cases.sh
expect_container_suite "smoke.sh logic on Alpine (BusyBox ash and awk)" \
  "$ALPINE_IMAGE" -- sh /t/container/smoke-cases.sh

finish
