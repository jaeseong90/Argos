#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# check-openapi-diff.sh - fail pull requests that break the public REST API without the
# `breaking-change` label (docs/19 section 6.2, O-R03).
#
# Usage: check-openapi-diff.sh <base>
#
# Compares the OpenAPI spec at <base> with the spec in the working tree (the pull request result)
# using `oasdiff breaking --fail-on ERR`.
#   - no spec at <base> and none in the working tree: nothing to compare yet, pass;
#   - spec added by the pull request: nothing to compare, pass;
#   - spec deleted by the pull request: breaking;
#   - breaking changes found: fail unless BREAKING_CHANGE=true (the PR has the `breaking-change` label);
#   - oasdiff cannot load a spec: fail (the label does not override invalid specs).
#
# Environment:
#   OPENAPI_SPEC=<path>       spec path relative to the repository root (default: docs/api/openapi.yaml).
#   BREAKING_CHANGE=true      the pull request carries the `breaking-change` label.
#   OASDIFF_IMAGE=<image>     oasdiff container image, pinned by digest (default below).
#   OASDIFF_BIN=<command>     use a local oasdiff binary instead of the container (tests, offline use).
#
# Exit status: 0 = compatible, nothing to compare, or breaking change acknowledged by the label;
#              1 = breaking change without label, spec deleted, or invalid spec; 2 = usage error.
set -euo pipefail

DEFAULT_OASDIFF_IMAGE="tufin/oasdiff:v1.33.0@sha256:6263a96dd2ef0726c54e21fea9b8e1607eac4841add0079324b424c1f52b819c"

if [[ $# -ne 1 ]]; then
  echo "usage: check-openapi-diff.sh <base>" >&2
  exit 2
fi
base=$1
spec=${OPENAPI_SPEC:-docs/api/openapi.yaml}
breaking_label=${BREAKING_CHANGE:-false}

if [[ -z "$base" ]] || ! git rev-parse --verify --quiet "${base}^{commit}" >/dev/null; then
  echo "check-openapi-diff: unknown commit '${base}' (in CI, check out with fetch-depth: 0)" >&2
  exit 2
fi

base_has_spec=0
git cat-file -e "${base}:${spec}" 2>/dev/null && base_has_spec=1
head_has_spec=0
[[ -f "$spec" ]] && head_has_spec=1

if [[ $base_has_spec -eq 0 && $head_has_spec -eq 0 ]]; then
  echo "check-openapi-diff: no OpenAPI spec at ${spec} yet; nothing to compare"
  exit 0
fi
if [[ $base_has_spec -eq 0 ]]; then
  echo "check-openapi-diff: ${spec} is new in this change; nothing to compare"
  exit 0
fi
if [[ $head_has_spec -eq 0 ]]; then
  if [[ "$breaking_label" == "true" ]]; then
    echo "check-openapi-diff: ${spec} was deleted; accepted because of the 'breaking-change' label"
    exit 0
  fi
  echo "check-openapi-diff: ${spec} was deleted; this breaks every API client (add the 'breaking-change' label if intended)" >&2
  exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
git show "${base}:${spec}" >"$work/base.yaml"
cp "$spec" "$work/head.yaml"

status=0
if [[ -n "${OASDIFF_BIN:-}" ]]; then
  "$OASDIFF_BIN" breaking "$work/base.yaml" "$work/head.yaml" --fail-on ERR || status=$?
else
  mount_dir=$work
  if command -v cygpath >/dev/null 2>&1; then
    # Git Bash on Windows: hand Docker a Windows path and stop MSYS from rewriting container paths.
    mount_dir=$(cygpath -w "$work")
    export MSYS_NO_PATHCONV=1
  fi
  docker run --rm --network none -v "${mount_dir}:/specs:ro" "${OASDIFF_IMAGE:-$DEFAULT_OASDIFF_IMAGE}" \
    breaking /specs/base.yaml /specs/head.yaml --fail-on ERR || status=$?
fi

case $status in
  0)
    echo "check-openapi-diff: no breaking changes in ${spec}"
    exit 0
    ;;
  1)
    if [[ "$breaking_label" == "true" ]]; then
      echo "check-openapi-diff: breaking changes accepted because of the 'breaking-change' label"
      exit 0
    fi
    echo "check-openapi-diff: breaking API changes found; fix them or add the 'breaking-change' label (and a CHANGELOG entry) if intended" >&2
    exit 1
    ;;
  *)
    echo "check-openapi-diff: oasdiff failed with exit code ${status} (invalid spec?)" >&2
    exit 1
    ;;
esac
