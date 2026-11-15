#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Tests of argos-compose.sh without a stack in pinned Linux containers (container/argos-compose-cases.sh):
# the refused commands, the up preflight, the input limits of token create, its agent configuration
# example and the interrupt handling. Ubuntu 22.04 (dash, no openssl), Alpine (BusyBox ash, no
# openssl) and the PostgreSQL image (Debian dash with openssl: the host taken from the certificate).
# Requires Docker (skipped without it unless REQUIRE_DOCKER=1).
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/compose/test/lib.sh
source "$here/lib.sh"
require_docker

# deploy/clickhouse and deploy/postgres next to the copy of deploy/compose, as in the repository.
mounts=(-v "$(host_path "$DEPLOY_DIR/clickhouse"):/deploy/clickhouse:ro" -v "$(host_path "$DEPLOY_DIR/postgres"):/deploy/postgres:ro")

expect_container_suite "argos-compose cases on Ubuntu 22.04 (dash, no openssl)" \
  "$UBUNTU_IMAGE" "${mounts[@]}" -- sh /t/container/argos-compose-cases.sh
expect_container_suite "argos-compose cases on Alpine (BusyBox ash, no openssl)" \
  "$ALPINE_IMAGE" "${mounts[@]}" -- sh /t/container/argos-compose-cases.sh
expect_container_suite "argos-compose cases on Debian with openssl (PostgreSQL image)" \
  "$POSTGRES_IMAGE" "${mounts[@]}" --entrypoint sh -- /t/container/argos-compose-cases.sh

finish
