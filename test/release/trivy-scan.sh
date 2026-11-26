#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# trivy-scan.sh: the scanner of test/release/check-sbom.sh --scanner (T-BLD-008, 16 §10.2) and the
# container image scan of SEC-71 (T-SEC-71d, proposed). Runs the digest-pinned Trivy container:
# findings of severity Critical or High that have a fix (--ignore-unfixed) and are not in the
# ignore file fail the scan.
#
#   SBOM:   docker run --rm aquasec/trivy@sha256:... sbom --scanners vuln
#             --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 --ignorefile <ignorefile> <sbom>
#   image:  docker save <ref> -o <tmp>/image.tar, then
#           docker run --rm aquasec/trivy@sha256:... image --input <tmp>/image.tar --scanners vuln
#             --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 --ignorefile <ignorefile>
#
# The image mode scans an image of the local Docker daemon (for example argos-server:<version> from
# ./gradlew :server:jibDockerBuild): the image is saved to a tar file that Trivy reads from a
# read-only mount, so the Docker socket is never given to the scanner container. The SBOM, the tar
# file and the ignore file are mounted read-only. Trivy downloads its vulnerability databases
# (ghcr.io/aquasecurity/trivy-db, and trivy-java-db for the jars of an image) into the named volume
# argos-trivy-cache; this is the only network access, and only of this development/CI tool (never
# of an Argos artifact).
#
# Usage:  trivy-scan.sh <sbom.cdx.json> <ignorefile>
#         trivy-scan.sh --image <image-ref> <ignorefile>
# Env:    ARGOS_TRIVY_IMAGE  another image, must be pinned by digest (name@sha256:<64 hex>)
#         ARGOS_TRIVY_CACHE  cache volume name (default argos-trivy-cache)
# Exit:   0 no finding, 1 findings, a Trivy error or an image missing from the local daemon,
#         2 usage error.

set -euo pipefail

# Trivy 0.76.2 (2026-11-05), multi-arch OCI index. Verify: docker buildx imagetools inspect aquasec/trivy:0.76.2
readonly DEFAULT_IMAGE="aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb"

image="${ARGOS_TRIVY_IMAGE:-$DEFAULT_IMAGE}"
cache="${ARGOS_TRIVY_CACHE:-argos-trivy-cache}"

usage() {
  echo "usage: $0 <sbom.cdx.json> <ignorefile>" >&2
  echo "       $0 --image <image-ref> <ignorefile>" >&2
  exit 2
}

mode=sbom
if [[ $# -ge 1 && "$1" == "--image" ]]; then
  mode=image
  shift
  [[ $# -eq 2 && -f "$2" ]] || usage
  target="$1"
  # A Docker reference: no option, no whitespace, no shell or path tricks.
  if [[ ! "$target" =~ ^[A-Za-z0-9][A-Za-z0-9._/:@-]*$ ]]; then
    echo "trivy-scan: not an image reference: $target" >&2
    usage
  fi
else
  [[ $# -eq 2 && -f "$1" && -f "$2" ]] || usage
  target="$1"
fi
ignorefile="$2"
if [[ ! "$image" =~ @sha256:[0-9a-f]{64}$ ]]; then
  echo "trivy-scan: ARGOS_TRIVY_IMAGE must be pinned by digest (name@sha256:<64 hex>): $image" >&2
  exit 2
fi
command -v docker >/dev/null 2>&1 || { echo "trivy-scan: docker is not available" >&2; exit 1; }

host_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s\n' "$1"; fi
}
host_dir() {
  host_path "$(cd "$(dirname "$1")" && pwd)"
}

ignore_dir="$(host_dir "$ignorefile")"
ignore_name="$(basename "$ignorefile")"

if [[ "$mode" == "sbom" ]]; then
  sbom_dir="$(host_dir "$target")"
  sbom_name="$(basename "$target")"
  # Git Bash would rewrite the container paths below into Windows paths.
  MSYS_NO_PATHCONV=1 docker run --rm \
    --label io.argos.tool=trivy-scan \
    -v "$cache:/root/.cache/trivy" \
    -v "$sbom_dir:/work/sbom:ro" \
    -v "$ignore_dir:/work/ignore:ro" \
    "$image" \
    sbom --quiet --scanners vuln --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 \
    --ignorefile "/work/ignore/$ignore_name" "/work/sbom/$sbom_name"
  exit $?
fi

if ! docker image inspect "$target" >/dev/null 2>&1; then
  echo "trivy-scan: image not found in the local Docker daemon: $target (build it first, e.g. ./gradlew :server:jibDockerBuild)" >&2
  exit 1
fi
work="$(mktemp -d "${TMPDIR:-/tmp}/argos-trivy-image.XXXXXX")"
trap 'rm -rf "$work"' EXIT
echo "trivy-scan: saving $target from the local Docker daemon"
docker save -o "$work/image.tar" "$target"
[[ -s "$work/image.tar" ]] || { echo "trivy-scan: docker save wrote no image tar for $target" >&2; exit 1; }
image_dir="$(host_path "$work")"

status=0
MSYS_NO_PATHCONV=1 docker run --rm \
  --label io.argos.tool=trivy-scan \
  -v "$cache:/root/.cache/trivy" \
  -v "$image_dir:/work/image:ro" \
  -v "$ignore_dir:/work/ignore:ro" \
  "$image" \
  image --quiet --input /work/image/image.tar --scanners vuln --severity CRITICAL,HIGH \
  --ignore-unfixed --exit-code 1 --ignorefile "/work/ignore/$ignore_name" || status=$?
if ((status == 0)); then
  echo "trivy-scan: $target: no fixable Critical or High finding outside the ignore file"
fi
exit "$status"
