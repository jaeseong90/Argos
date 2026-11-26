#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: O-R05, C-171
# test-ids: T-BLD-039
#
# Tests for scripts/release-compose.sh (docs/19 section 7.2 step 7, ADR-0010 C-171 (1)): the
# Compose release asset of the real deploy/ directory pins the three images by digest, keeps the
# layout the Compose files expect, leaves the repository unchanged and is reproducible.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$here/../.." && pwd)
script="$repo_root/scripts/release-compose.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$repo_root/.github/scripts/test/lib.sh"
# shellcheck source=scripts/test/test-lib.sh
source "$here/test-lib.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

V=0.1.0
D1=1111111111111111111111111111111111111111111111111111111111111111
D2=2222222222222222222222222222222222222222222222222222222222222222
D3=3333333333333333333333333333333333333333333333333333333333333333
SERVER=ghcr.io/argos-apm/argos-server:$V@sha256:$D1
DEMO_APP=ghcr.io/argos-apm/argos-demo-app:$V@sha256:$D2
DEMO_LOAD=ghcr.io/argos-apm/argos-demo-load:$V@sha256:$D3
cat >"$work/digests.txt" <<EOF
argos-server=$SERVER
argos-demo-app=$DEMO_APP
argos-demo-load=$DEMO_LOAD
EOF

before=$(cd "$repo_root" && cat deploy/compose/compose.yaml deploy/compose/compose.demo.yaml | sha256sum)
out=$work/out/argos-compose-$V.tar.gz
expect_exit 0 "release-compose: renders the Compose asset of the repository" \
  env SOURCE_DATE_EPOCH=1764547200 bash "$script" "$V" "$work/digests.txt" "$out"
after=$(cd "$repo_root" && cat deploy/compose/compose.yaml deploy/compose/compose.demo.yaml | sha256sum)
expect_exit 0 "release-compose: the repository Compose files are unchanged" test "$before" = "$after"

mkdir "$work/x"
tar -xzf "$out" -C "$work/x"
top=$work/x/argos-compose-$V
expect_exit 0 "release-compose: one top directory with compose/, clickhouse/, postgres/, LICENSE and NOTICE" \
  bash -c "[ \$(ls '$work/x' | wc -l) -eq 1 ] && [ -f '$top/compose/compose.yaml' ] && [ -f '$top/compose/argos-env-init.sh' ] \
    && [ -f '$top/clickhouse/config.d/argos.xml' ] && [ -f '$top/clickhouse/users.d/argos.xml' ] && [ -f '$top/clickhouse/image.properties' ] \
    && [ -f '$top/postgres/roles.sql' ] && [ -f '$top/postgres/image.properties' ] && [ -f '$top/LICENSE' ] && [ -f '$top/NOTICE' ]"
expect_output "image: \${ARGOS_SERVER_IMAGE:-$SERVER}" "C-171 (1): the server image default is the GHCR image by digest" \
  cat "$top/compose/compose.yaml"
expect_output "image: \${ARGOS_DEMO_APP_IMAGE:-$DEMO_APP}" "C-171 (1): the demo app image default is pinned by digest" \
  cat "$top/compose/compose.demo.yaml"
expect_output "image: \${ARGOS_DEMO_LOAD_IMAGE:-$DEMO_LOAD}" "C-171 (1): the demo load image default is pinned by digest" \
  cat "$top/compose/compose.demo.yaml"
expect_exit 0 "SEC-73: every image line of the rendered Compose files has a digest" \
  bash -c "! grep -h '^[[:space:]]*image:' '$top'/compose/*.yaml | grep -v '@sha256:[0-9a-f]\{64\}'"
expect_exit 0 "release-compose: the rendered server image keeps the version tag (argos-env-init.sh reads it)" \
  bash -c "sed -n 's/^ *image: *\\\${ARGOS_SERVER_IMAGE:-\\([^}]*\\)}.*\$/\\1/p' '$top/compose/compose.yaml' | grep -qx '$SERVER'"
expect_exit 0 "release-compose: no tests, local state or dot files except .env.example" \
  bash -c "[ ! -e '$top/compose/test' ] && [ ! -e '$top/compose/.env' ] && [ ! -e '$top/compose/secrets' ] && [ ! -e '$top/compose/.gitignore' ] && [ -f '$top/compose/.env.example' ]"
expect_output "rwxr-xr-x 0/0" "release-compose: scripts are 0755 and owned by 0:0" \
  bash -c "tar -tvzf '$out' | grep 'compose/argos-env-init.sh'"
expect_output "rw-r--r-- 0/0" "release-compose: other files are 0644" \
  bash -c "tar -tvzf '$out' | grep 'compose/compose.yaml'"
expect_output "2025-12-01" "release-compose: entry times are SOURCE_DATE_EPOCH" \
  bash -c "tar -tvzf '$out' | grep 'compose/compose.yaml'"

out2=$work/out2/argos-compose-$V.tar.gz
env SOURCE_DATE_EPOCH=1764547200 bash "$script" "$V" "$work/digests.txt" "$out2" >/dev/null
expect_exit 0 "release-compose: two runs produce the same archive (reproducible)" \
  bash -c "[ \"\$(sha256sum <'$out' | cut -c1-64)\" = \"\$(sha256sum <'$out2' | cut -c1-64)\" ]"

bad() { # bad <name> <description>: the digests file from stdin must be refused.
  cat >"$work/$1.txt"
  expect_exit 1 "release-compose: $2" bash "$script" "$V" "$work/$1.txt" "$work/$1.tar.gz"
}
bad missing-demo "the three images are required" <<EOF
argos-server=$SERVER
argos-demo-app=$DEMO_APP
EOF
bad no-digest "an image without a digest is refused" <<EOF
argos-server=ghcr.io/argos-apm/argos-server:$V
argos-demo-app=$DEMO_APP
argos-demo-load=$DEMO_LOAD
EOF
bad other-version "the image tag must be the release version" <<EOF
argos-server=ghcr.io/argos-apm/argos-server:0.0.9@sha256:$D1
argos-demo-app=$DEMO_APP
argos-demo-load=$DEMO_LOAD
EOF
bad unknown "an unknown image name is refused" <<EOF
argos-server=$SERVER
argos-demo-app=$DEMO_APP
argos-demo-load=$DEMO_LOAD
shop-boot3=ghcr.io/argos-apm/shop-boot3:$V@sha256:$D1
EOF
bad short-digest "a short digest is refused" <<EOF
argos-server=ghcr.io/argos-apm/argos-server:$V@sha256:1234
argos-demo-app=$DEMO_APP
argos-demo-load=$DEMO_LOAD
EOF
expect_exit 2 "usage: the output must be .tar.gz" bash "$script" "$V" "$work/digests.txt" "$work/out.zip"
expect_exit 2 "usage: malformed version" bash "$script" 0.1 "$work/digests.txt" "$out"
expect_exit 2 "usage: missing arguments" bash "$script" "$V"

finish
