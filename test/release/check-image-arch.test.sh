#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: O-R05, C-171
# test-ids: T-BLD-022
#
# Tests for test/release/check-image-arch.sh (T-BLD-022): fixture Jib tarballs (manifest.json,
# config, one layer) built with tar in a temporary directory; no Docker, no registry.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
script="$here/check-image-arch.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$root/.github/scripts/test/lib.sh"

work="$(mktemp -d "${TMPDIR:-/tmp}/argos-image-arch-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

base="$(bash "$script" --print-expected-base)"
base_name="${base%@*}"
base_digest="${base#*@}"
commit="0123456789abcdef0123456789abcdef01234567"

# image <name> [key=value...]: a Jib-style tarball with overridable config fields.
image() {
  local name=$1
  shift
  local arch=arm64 os=linux user=10001:10001 version=0.1.0 revision=$commit licenses=Apache-2.0
  local source=https://github.com/argos-apm/argos bname=$base_name bdigest=$base_digest
  local kv
  for kv in "$@"; do
    case "$kv" in
      arch=*) arch=${kv#arch=} ;;
      os=*) os=${kv#os=} ;;
      user=*) user=${kv#user=} ;;
      version=*) version=${kv#version=} ;;
      revision=*) revision=${kv#revision=} ;;
      licenses=*) licenses=${kv#licenses=} ;;
      source=*) source=${kv#source=} ;;
      bdigest=*) bdigest=${kv#bdigest=} ;;
    esac
  done
  local dir="$work/$name"
  mkdir -p "$dir"
  printf 'layer' | gzip >"$dir/aaaa.tar.gz"
  printf '[{"Config":"config.json","RepoTags":["argos-server:%s"],"Layers":["aaaa.tar.gz"]}]' "$version" >"$dir/manifest.json"
  printf '{"architecture":"%s","os":"%s","config":{"User":"%s","Labels":{"org.opencontainers.image.version":"%s","org.opencontainers.image.revision":"%s","org.opencontainers.image.licenses":"%s","org.opencontainers.image.source":"%s","org.opencontainers.image.base.name":"%s","org.opencontainers.image.base.digest":"%s"}},"rootfs":{"type":"layers","diff_ids":[]}}' \
    "$arch" "$os" "$user" "$version" "$revision" "$licenses" "$source" "$bname" "$bdigest" >"$dir/config.json"
  (cd "$dir" && tar -cf "../$name.tar" manifest.json config.json aaaa.tar.gz)
  printf '%s\n' "$work/$name.tar"
}

expect_exit 0 "--print-expected-base reads the pinned base of ServerImage.kt" \
  bash -c "[[ '$base' =~ ^eclipse-temurin:21-jre@sha256:[0-9a-f]{64}\$ ]]"
expect_exit 0 "the expected base is the BASE_IMAGE constant of ServerImage.kt" \
  grep -q "\"$base\"" "$root/build-logic/src/main/kotlin/io/argos/buildlogic/server/ServerImage.kt"

good="$(image good)"
expect_exit 0 "arm64 image with user, labels and pinned base passes" bash "$script" --version 0.1.0 "$good" arm64
expect_output "PASS  architecture: os/architecture linux/arm64" "the architecture is reported" bash "$script" "$good" arm64
expect_exit 1 "the same image checked as amd64 fails" bash "$script" "$good" amd64
expect_exit 1 "a version label other than --version fails" bash "$script" --version 0.2.0 "$good" arm64

expect_output "FAIL  user: User \"0:0\"" "a root user fails" bash "$script" "$(image root user=0:0)" arm64
expect_output "FAIL  architecture" "a non-linux image fails" bash "$script" "$(image win os=windows)" arm64
expect_output "FAIL  labels: revision \"unknown\"" "revision 'unknown' (no git) fails" \
  bash "$script" "$(image norev revision=unknown)" arm64
expect_output "FAIL  labels: licenses \"GPL-2.0\"" "a licenses label other than Apache-2.0 fails" \
  bash "$script" "$(image gpl licenses=GPL-2.0)" arm64
expect_output "FAIL  labels: source \"\"" "an empty source label fails" bash "$script" "$(image nosrc source=)" arm64
expect_output "FAIL  base:" "a different base digest fails" \
  bash "$script" "$(image otherbase bdigest=sha256:0000000000000000000000000000000000000000000000000000000000000000)" arm64
expect_exit 0 "--base-image overrides the expected base" \
  bash "$script" --base-image "$base_name@sha256:0000000000000000000000000000000000000000000000000000000000000000" \
  "$work/otherbase.tar" arm64

printf 'not a tar' >"$work/plain.tar"
expect_exit 1 "a file that is not a Jib tarball fails" bash "$script" "$work/plain.tar" arm64
expect_exit 2 "missing arguments: usage error" bash "$script" "$good"
expect_exit 2 "missing tarball: usage error" bash "$script" "$work/none.tar" arm64
expect_exit 2 "invalid architecture: usage error" bash "$script" "$good" "arm/64"

finish
