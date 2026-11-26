#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: SEC-74, N-16
#
# Tests of argos-env-init.sh in pinned Linux containers on a container-local file system (POSIX
# modes): Ubuntu 22.04 (dash, no openssl: the /dev/urandom path), Alpine (BusyBox ash) and the
# PostgreSQL image (Debian dash with openssl). The cases are container/env-init-cases.sh; the
# non-root case runs as uid 1000. Requires Docker (skipped without it unless REQUIRE_DOCKER=1).
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/compose/test/lib.sh
source "$here/lib.sh"
require_docker

expect_container_suite "env-init cases on Ubuntu 22.04 (dash, /dev/urandom)" \
  "$UBUNTU_IMAGE" -- sh /t/container/env-init-cases.sh
expect_container_suite "env-init cases on Alpine (BusyBox ash)" \
  "$ALPINE_IMAGE" -- sh /t/container/env-init-cases.sh
expect_container_suite "env-init cases on Debian with openssl (PostgreSQL image)" \
  "$POSTGRES_IMAGE" --entrypoint sh -- /t/container/env-init-cases.sh

# A Linux Docker Engine needs root: the secrets are given to uid 10001.
non_root() {
  # shellcheck disable=SC2016 # the script runs in the container's shell, which expands it
  in_container "$UBUNTU_IMAGE" --user 1000:1000 -e FAKE_DOCKER_OS="Ubuntu 22.04.5 LTS" -- sh -c '
    mkdir -p /tmp/fake-bin /tmp/w && cp /t/container/fake-bin/docker /tmp/fake-bin/ && chmod 0755 /tmp/fake-bin/docker
    cp -R /src/. /tmp/w/ && rm -rf /tmp/w/secrets /tmp/w/.env && cd /tmp/w
    PATH=/tmp/fake-bin:$PATH sh ./argos-env-init.sh --ram-mb 16384 --public-url http://argos.test:8080
    rc=$?
    [ ! -e /tmp/w/secrets ] || { echo "secrets/ was created"; exit 9; }
    exit $rc'
}
expect_exit 2 "env-init: a non-root user on a Linux Docker Engine exits 2 and creates nothing" non_root
non_root_message() {
  local out
  out=$(non_root 2>&1) || true
  grep -q "run 'sudo ./argos-env-init.sh'" <<<"$out"
}
expect_exit 0 "env-init: the non-root refusal says to run it with sudo" non_root_message

finish
