#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: SEC-84, SEC-52, SEC-73, N-20, DSN-17-14
#
# Tests of backup.sh and restore.sh without a stack in pinned Linux containers
# (container/backup-cases.sh; docs/17 sections 4.4 and 10, SEC-84, SEC-52; WO-43): T-SEC-84a..c,
# T-NFR-20c, T-SEC-52e and the restore order, on Ubuntu 22.04 (dash), Alpine (BusyBox ash) and
# the PostgreSQL image (Debian dash with gpg: the GnuPG cases must run there). The real round trip
# against PostgreSQL and ClickHouse is ./gradlew :deploy:backupRoundTrip. Requires Docker (skipped
# without it unless REQUIRE_DOCKER=1).
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/compose/test/lib.sh
source "$here/lib.sh"
require_docker

expect_container_suite "backup and restore cases on Ubuntu 22.04 (dash)" \
  "$UBUNTU_IMAGE" -- sh /t/container/backup-cases.sh
expect_container_suite "backup and restore cases on Alpine (BusyBox ash)" \
  "$ALPINE_IMAGE" -- sh /t/container/backup-cases.sh
expect_container_suite "backup and restore cases on Debian with gpg (PostgreSQL image)" \
  "$POSTGRES_IMAGE" -e REQUIRE_GPG=1 --entrypoint sh -- /t/container/backup-cases.sh

finish
