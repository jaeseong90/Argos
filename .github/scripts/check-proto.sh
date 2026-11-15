#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# check-proto.sh - lint the argos.v1 protocol and reject wire- or code-incompatible changes
# (docs/11-protocol-design.md section 12, docs/19 section 6.2, proto/README.md).
#
# Usage: check-proto.sh [--against <dir> | --against-git <ref>]
#
#   (no option)          buf lint only.
#   --against <dir>      buf lint, then buf breaking (FILE rules, proto/buf.yaml) against <dir>:
#                        either a proto project laid out like proto/ (<dir>/src/main/proto) or a
#                        bare proto source root (<dir>/argos/...).
#   --against-git <ref>  buf lint, then buf breaking against the protos of commit <ref> (in CI the
#                        pull request base). Run it inside the repository.
#
# A base without any .proto file (the protos are new) leaves nothing to compare: lint only. Removing
# every proto file is breaking. There is no label override: argos.v1 only takes additive changes
# (ADR-0010 C-101); an incompatible protocol is a new package (argos.v2).
#
# Only the proto sources and buf.yaml are copied into a temporary directory, which is mounted
# read-only into the pinned buf container without network access.
#
# Environment:
#   PROTO_DIR=<path>   the proto project to check (default: proto, relative to the current directory).
#   BUF_IMAGE=<image>  buf container image, pinned by digest (default below).
#   BUF_BIN=<command>  use a local buf binary instead of the container (offline use).
#
# Exit status: 0 = lint clean and compatible (or nothing to compare); 1 = lint findings, breaking
#              changes or buf failure; 2 = usage error.
set -euo pipefail

DEFAULT_BUF_IMAGE="bufbuild/buf:1.74.0@sha256:621f108266b19236906aa328e64b7c50340738472c5da4d4b25104f01aa8d204"

usage() {
  echo "usage: check-proto.sh [--against <dir> | --against-git <ref>]" >&2
  exit 2
}

mode=lint
base_arg=
case $# in
  0) ;;
  2)
    case $1 in
      --against) mode=dir ;;
      --against-git) mode=git ;;
      *) usage ;;
    esac
    base_arg=$2
    [[ -n "$base_arg" ]] || usage
    ;;
  *) usage ;;
esac

proto_dir=${PROTO_DIR:-proto}
if [[ ! -f "$proto_dir/buf.yaml" ]]; then
  echo "check-proto: '$proto_dir/buf.yaml' not found (run from the repository root or set PROTO_DIR)" >&2
  exit 2
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/head/src/main" "$work/base/src/main/proto"
cp "$proto_dir/buf.yaml" "$work/head/buf.yaml"
# The base uses the head configuration too, so that both sides have the same module layout.
cp "$proto_dir/buf.yaml" "$work/base/buf.yaml"
if [[ -d "$proto_dir/src/main/proto" ]]; then
  cp -R "$proto_dir/src/main/proto" "$work/head/src/main/proto"
else
  mkdir -p "$work/head/src/main/proto"
fi

count_protos() {
  find "$1" -type f -name '*.proto' | wc -l | tr -d ' '
}

case $mode in
  dir)
    if [[ ! -d "$base_arg" ]]; then
      echo "check-proto: base directory '$base_arg' not found" >&2
      exit 2
    fi
    if [[ -d "$base_arg/src/main/proto" ]]; then
      cp -R "$base_arg/src/main/proto/." "$work/base/src/main/proto/"
    else
      cp -R "$base_arg/." "$work/base/src/main/proto/"
      rm -f "$work/base/src/main/proto/buf.yaml"
    fi
    ;;
  git)
    if ! git rev-parse --verify --quiet "${base_arg}^{commit}" >/dev/null; then
      echo "check-proto: unknown commit '${base_arg}' (in CI, fetch the base commit first)" >&2
      exit 2
    fi
    top=$(git rev-parse --show-toplevel)
    prefix=$(cd "$proto_dir" && git rev-parse --show-prefix)
    while IFS= read -r path; do
      [[ -n "$path" ]] || continue
      rel=${path#"${prefix}src/main/proto/"}
      mkdir -p "$work/base/src/main/proto/$(dirname "$rel")"
      git -C "$top" show "${base_arg}:${path}" >"$work/base/src/main/proto/$rel"
    done < <(git -C "$top" ls-tree -r --name-only "$base_arg" -- "${prefix}src/main/proto" | grep '\.proto$' || true)
    ;;
esac

head_count=$(count_protos "$work/head/src/main/proto")
base_count=$(count_protos "$work/base/src/main/proto")

if [[ "$head_count" -eq 0 ]]; then
  if [[ "$mode" != lint && "$base_count" -gt 0 ]]; then
    echo "check-proto: every .proto file was removed; this breaks every agent and server" >&2
    exit 1
  fi
  echo "check-proto: no .proto files under ${proto_dir}/src/main/proto; nothing to check"
  exit 0
fi

run_buf() {
  if [[ -n "${BUF_BIN:-}" ]]; then
    (cd "$work" && "$BUF_BIN" "$@")
    return
  fi
  local mount_dir=$work
  if command -v cygpath >/dev/null 2>&1; then
    # Git Bash on Windows: hand Docker a Windows path and stop MSYS from rewriting container paths.
    mount_dir=$(cygpath -w "$work")
    export MSYS_NO_PATHCONV=1
  fi
  docker run --rm --network none -v "${mount_dir}:/work:ro" -w /work "${BUF_IMAGE:-$DEFAULT_BUF_IMAGE}" "$@"
}

status=0
run_buf lint head || status=$?
if [[ $status -ne 0 ]]; then
  echo "check-proto: buf lint failed (exit ${status}); fix the findings above or, for a name fixed by docs/11, add a scoped exception with a reason to proto/buf.yaml" >&2
  exit 1
fi
echo "check-proto: buf lint passed (${head_count} files)"

if [[ "$mode" == lint ]]; then
  exit 0
fi
if [[ "$base_count" -eq 0 ]]; then
  echo "check-proto: the base has no .proto files; nothing to compare"
  exit 0
fi

run_buf breaking head --against base || status=$?
if [[ $status -ne 0 ]]; then
  echo "check-proto: buf breaking failed (exit ${status}): argos.v1 takes additive changes only (ADR-0010 C-101); never renumber, retype, rename or remove a field, enum value or RPC" >&2
  exit 1
fi
echo "check-proto: no breaking changes against ${base_arg} (${base_count} base files)"
