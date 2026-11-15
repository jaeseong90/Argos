#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Tests for .github/scripts/check-proto.sh on copies of the real argos.v1 protos (proto/).
# Cases that run buf use the pinned buf container and are skipped (reported) when Docker is not
# available (unless REQUIRE_DOCKER=1, as in CI); the usage cases always run.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script="$here/../check-proto.sh"
repo_root=$(cd "$here/../../.." && pwd)
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

unset PROTO_DIR BUF_BIN

# copy_proto <dest>: buf.yaml and the proto sources of the repository, laid out like proto/.
copy_proto() {
  mkdir -p "$1/src/main"
  cp "$repo_root/proto/buf.yaml" "$1/buf.yaml"
  cp -R "$repo_root/proto/src/main/proto" "$1/src/main/proto"
}

# edit <file> <sed expression>: in-place edit that fails the test run if nothing changed.
edit() {
  local file=$1 expr=$2
  cp "$file" "$file.orig"
  sed -e "$expr" "$file.orig" >"$file"
  if cmp -s "$file" "$file.orig"; then
    echo "FAIL  test setup: '$expr' did not change $file"
    exit 1
  fi
  rm -f "$file.orig"
}

data=src/main/proto/argos/v1/data.proto

# --- usage errors (no Docker needed) --------------------------------------------------------------
mkdir -p "$work/empty"
expect_exit 2 "missing buf.yaml is a usage error" env PROTO_DIR="$work/empty" bash "$script"
expect_exit 2 "unknown option is a usage error" bash "$script" --base x
expect_exit 2 "--against without a value is a usage error" bash "$script" --against ""
copy_proto "$work/current"
expect_exit 2 "missing base directory is a usage error" \
  env PROTO_DIR="$work/current" bash "$script" --against "$work/does-not-exist"

if ! docker info >/dev/null 2>&1; then
  if [[ "${REQUIRE_DOCKER:-0}" == "1" ]]; then
    echo "FAIL  Docker is required (REQUIRE_DOCKER=1) but not available"
    exit 1
  fi
  echo "SKIP  buf lint and buf breaking cases (Docker is not available)"
  finish
  exit $?
fi

# --- lint ----------------------------------------------------------------------------------------
expect_exit 0 "the current argos.v1 protos pass buf lint" env PROTO_DIR="$repo_root/proto" bash "$script"

copy_proto "$work/badname"
printf '\nmessage bad_name {\n  string x = 1;\n}\n' >>"$work/badname/src/main/proto/argos/v1/common.proto"
expect_exit 1 "a message name that is not PascalCase fails buf lint" \
  env PROTO_DIR="$work/badname" bash "$script"

copy_proto "$work/badzero"
printf '\nenum NewKind {\n  NEW_KIND_FIRST = 0;\n}\n' >>"$work/badzero/src/main/proto/argos/v1/data.proto"
expect_exit 1 "a new enum without a *_UNSPECIFIED zero value fails buf lint (exception is scoped)" \
  env PROTO_DIR="$work/badzero" bash "$script"

# The only ENUM_ZERO_VALUE_SUFFIX exception is the comment ignore on FailureKind.FAILURE_NONE: a new
# enum in the same file (common.proto) is still checked.
copy_proto "$work/badzero-common"
printf '\nenum NewKind {\n  NEW_KIND_FIRST = 0;\n}\n' >>"$work/badzero-common/src/main/proto/argos/v1/common.proto"
expect_exit 1 "a new enum without a *_UNSPECIFIED zero value in common.proto fails buf lint (the FAILURE_NONE exception covers that value only)" \
  env PROTO_DIR="$work/badzero-common" bash "$script"

# --- breaking against a directory ------------------------------------------------------------------
expect_exit 0 "unchanged protos are compatible with themselves" \
  env PROTO_DIR="$work/current" bash "$script" --against "$repo_root/proto"
expect_exit 0 "a bare proto source root works as the base" \
  env PROTO_DIR="$work/current" bash "$script" --against "$repo_root/proto/src/main/proto"

copy_proto "$work/renumbered"
edit "$work/renumbered/$data" 's/string request_id = 35;/string request_id = 38;/'
expect_exit 1 "a renumbered field fails buf breaking" \
  env PROTO_DIR="$work/renumbered" bash "$script" --against "$repo_root/proto"

copy_proto "$work/removed"
edit "$work/removed/$data" '/uint32 dispatch_count = 31;/d'
expect_exit 1 "a removed field fails buf breaking" \
  env PROTO_DIR="$work/removed" bash "$script" --against "$repo_root/proto"

copy_proto "$work/reserved"
edit "$work/reserved/$data" 's/uint32 dispatch_count = 31;/reserved 31; reserved "dispatch_count";/'
expect_exit 1 "a removed field fails even when reserved (FILE rules: generated accessors disappear)" \
  env PROTO_DIR="$work/reserved" bash "$script" --against "$repo_root/proto"

copy_proto "$work/retyped"
edit "$work/retyped/$data" 's/string url_path = 13;/bytes url_path = 13;/'
expect_exit 1 "a changed field type fails buf breaking" \
  env PROTO_DIR="$work/retyped" bash "$script" --against "$repo_root/proto"

copy_proto "$work/renamed-value"
edit "$work/renamed-value/src/main/proto/argos/v1/common.proto" 's/  SUMMARY = 11;/  SUMMARY_STEP = 11;/'
expect_exit 1 "a renamed enum value fails buf breaking" \
  env PROTO_DIR="$work/renamed-value" bash "$script" --against "$repo_root/proto"

copy_proto "$work/additive"
edit "$work/additive/$data" 's/^  uint32 flags = 37;$/  uint32 flags = 37;\n  uint32 example_added = 38;/'
printf '\nmessage ExampleAdded {\n  uint64 id = 1;\n}\n' >>"$work/additive/$data"
expect_exit 0 "an additive change (new field number, new message) passes buf breaking" \
  env PROTO_DIR="$work/additive" bash "$script" --against "$repo_root/proto"

mkdir -p "$work/nobase"
expect_exit 0 "a base without protos leaves nothing to compare" \
  env PROTO_DIR="$work/current" bash "$script" --against "$work/nobase"

mkdir -p "$work/noprotos"
cp "$repo_root/proto/buf.yaml" "$work/noprotos/buf.yaml"
expect_exit 1 "removing every proto file is breaking" \
  env PROTO_DIR="$work/noprotos" bash "$script" --against "$repo_root/proto"

# --- breaking against a git commit (as in the pull request job) -----------------------------------
: >"$work/gitconfig"
export GIT_CONFIG_GLOBAL="$work/gitconfig"
export GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME="Jane Doe" GIT_AUTHOR_EMAIL="jane@example.org"
export GIT_COMMITTER_NAME="Jane Doe" GIT_COMMITTER_EMAIL="jane@example.org"

git init -q -b main "$work/repo"
cd "$work/repo"
echo "readme" >README.md
git add . && git commit -q -m "no protos yet"
no_protos=$(git rev-parse HEAD)
copy_proto proto
expect_exit 0 "--against-git: protos added by the pull request pass (nothing to compare)" \
  bash "$script" --against-git "$no_protos"
git add . && git commit -q -m "add protos"
with_protos=$(git rev-parse HEAD)
expect_exit 0 "--against-git: unchanged protos pass" bash "$script" --against-git "$with_protos"
edit "proto/$data" 's/string request_id = 35;/string request_id = 38;/'
expect_exit 1 "--against-git: a renumbered field against the base commit fails" \
  bash "$script" --against-git "$with_protos"
git checkout -q -- proto
edit "proto/$data" 's/^  uint32 flags = 37;$/  uint32 flags = 37;\n  uint32 example_added = 38;/'
expect_exit 0 "--against-git: an additive field against the base commit passes" \
  bash "$script" --against-git "$with_protos"
expect_exit 2 "--against-git: an unknown commit is a usage error" \
  bash "$script" --against-git deadbeefdeadbeef

finish
