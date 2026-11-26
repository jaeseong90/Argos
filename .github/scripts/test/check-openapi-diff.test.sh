#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: O-R03
# test-ids: T-BLD-034
#
# Tests for .github/scripts/check-openapi-diff.sh using a throwaway git repository.
# Cases that compare two specs run the pinned oasdiff container and are skipped (reported) when
# Docker is not available (unless REQUIRE_DOCKER=1, as in CI); the "missing spec" cases always run.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script="$here/../check-openapi-diff.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

: >"$work/gitconfig"
export GIT_CONFIG_GLOBAL="$work/gitconfig"
export GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME="Jane Doe" GIT_AUTHOR_EMAIL="jane@example.org"
export GIT_COMMITTER_NAME="Jane Doe" GIT_COMMITTER_EMAIL="jane@example.org"
unset BREAKING_CHANGE OPENAPI_SPEC OASDIFF_BIN

git init -q -b main "$work/repo"
cd "$work/repo"
echo "readme" >README.md
git add . && git commit -q -m "no spec yet"
no_spec=$(git rev-parse HEAD)

# 1. No spec anywhere: tolerated (WO-00 state).
expect_exit 0 "missing spec on both sides is tolerated" bash "$script" "$no_spec"

# 2. Spec added by the pull request.
mkdir -p docs/api
cat >docs/api/openapi.yaml <<'EOF'
openapi: 3.0.3
info:
  title: Argos API
  version: "1"
paths:
  /api/v1/apps:
    get:
      responses:
        "200":
          description: ok
  /api/v1/instances:
    get:
      responses:
        "200":
          description: ok
EOF
expect_exit 0 "spec added by the pull request is accepted" bash "$script" "$no_spec"
git add . && git commit -q -m "add spec"
with_spec=$(git rev-parse HEAD)

# 3. Spec deleted by the pull request.
mv docs/api/openapi.yaml "$work/saved.yaml"
expect_exit 1 "deleting the spec is rejected" bash "$script" "$with_spec"
expect_exit 0 "deleting the spec is accepted with the breaking-change label" env BREAKING_CHANGE=true bash "$script" "$with_spec"
mv "$work/saved.yaml" docs/api/openapi.yaml

expect_exit 2 "unknown base commit is a usage error" bash "$script" deadbeefdeadbeef

if ! docker info >/dev/null 2>&1; then
  if [[ "${REQUIRE_DOCKER:-0}" == "1" ]]; then
    echo "FAIL  Docker is required (REQUIRE_DOCKER=1) but not available"
    exit 1
  fi
  echo "SKIP  oasdiff comparison cases (Docker is not available)"
  finish
  exit $?
fi

# 4. Unchanged spec.
expect_exit 0 "unchanged spec passes" bash "$script" "$with_spec"

# 5. Compatible change: a new endpoint.
cat >>docs/api/openapi.yaml <<'EOF'
  /api/v1/alerts:
    get:
      responses:
        "200":
          description: ok
EOF
expect_exit 0 "adding an endpoint is not breaking" bash "$script" "$with_spec"

# 6. Breaking change: an endpoint removed.
git show "${with_spec}:docs/api/openapi.yaml" | sed '/\/api\/v1\/instances:/,$d' >docs/api/openapi.yaml
expect_exit 1 "removing an endpoint without the label is rejected" bash "$script" "$with_spec"
expect_exit 0 "removing an endpoint with the breaking-change label is accepted" env BREAKING_CHANGE=true bash "$script" "$with_spec"

# 7. Invalid spec fails even with the label.
echo "openapi: [3.0" >docs/api/openapi.yaml
expect_exit 1 "invalid spec is rejected even with the label" env BREAKING_CHANGE=true bash "$script" "$with_spec"

finish
