#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Tests for .github/scripts/check-workflows.sh: each fixture repository contains one workflow
# that violates (or satisfies) exactly one rule. The real repository must pass as well.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script="$here/../check-workflows.sh"
repo_root=$(cd "$here/../../.." && pwd)
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

SHA=0123456789abcdef0123456789abcdef01234567
DIGEST=sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef

# expect_rule <Wn> <description> <fixture-dir>: the checker must fail and report rule <Wn>.
expect_rule() {
  expect_exit 1 "$2" bash "$script" "$3"
  expect_output "[$1]" "$2 (reported as $1)" bash "$script" "$3"
}

# fixture <name> [file]: reads a workflow from stdin into $work/<name>/.github/workflows/<file>
# (default file name w.yml).
fixture() {
  mkdir -p "$work/$1/.github/workflows"
  cat >"$work/$1/.github/workflows/${2:-w.yml}"
}

fixture good <<EOF
name: good
on:
  pull_request:
  push:
    branches: [main]
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest   # comment with self-hosted is ignored
    steps:
      - uses: actions/checkout@$SHA # v7.0.1
      - uses: ./.github/actions/local
      - run: docker run --rm alpine:3.20@$DIGEST true
EOF
expect_exit 0 "compliant pull_request workflow passes" bash "$script" "$work/good"

fixture prt-block <<EOF
name: prt
on:
  pull_request_target:
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: true
EOF
expect_rule W1 "pull_request_target trigger (block form) is rejected" "$work/prt-block"

fixture prt-inline <<EOF
name: prt
on: [push, pull_request_target]
permissions: {}
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: true
EOF
expect_rule W1 "pull_request_target trigger (inline list) is rejected" "$work/prt-inline"

fixture selfhosted-pr <<EOF
name: sh
on:
  pull_request:
permissions:
  contents: read
jobs:
  a:
    runs-on: [self-hosted, argos-matrix]
    steps:
      - run: true
EOF
expect_rule W2 "self-hosted runner in a pull_request workflow is rejected" "$work/selfhosted-pr"

fixture selfhosted-block <<EOF
name: sh
on: pull_request
permissions:
  contents: read
jobs:
  a:
    runs-on:
      - self-hosted
      - linux
    steps:
      - run: true
EOF
expect_rule W2 "self-hosted runner in block form is rejected" "$work/selfhosted-block"

fixture selfhosted-group <<EOF
name: sh
on: pull_request
permissions:
  contents: read
jobs:
  a:
    runs-on:
      group: argos-trusted
    steps:
      - run: true
EOF
expect_rule W2 "runner group in a pull_request workflow is rejected" "$work/selfhosted-group"

fixture other-image <<EOF
name: img
on: pull_request
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-22.04
    steps:
      - run: true
EOF
expect_rule W2 "runner other than ubuntu-latest in a pull_request workflow is rejected" "$work/other-image"

fixture matrix-runner <<'EOF'
name: matrix
on: pull_request
permissions:
  contents: read
jobs:
  a:
    runs-on: ${{ matrix.os }}
    strategy:
      matrix:
        os: [ubuntu-latest]
    steps:
      - run: true
EOF
expect_rule W2 "expression runs-on in a pull_request workflow is rejected" "$work/matrix-runner"

fixture selfhosted-call <<EOF
name: reusable
on: workflow_call
permissions:
  contents: read
jobs:
  a:
    runs-on: [self-hosted]
    steps:
      - run: true
EOF
expect_rule W2 "self-hosted runner in a reusable (workflow_call) workflow is rejected" "$work/selfhosted-call"

fixture selfhosted-nightly <<EOF
name: nightly
on:
  schedule:
    - cron: "0 18 * * *"
  workflow_dispatch:
permissions:
  contents: read
jobs:
  matrix:
    runs-on: [self-hosted, argos-matrix]
    steps:
      - uses: actions/checkout@$SHA # v7.0.1
EOF
expect_exit 0 "self-hosted runner in a schedule/workflow_dispatch workflow is allowed" bash "$script" "$work/selfhosted-nightly"

fixture tag-ref <<EOF
name: tag
on: push
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
EOF
expect_rule W3 "action referenced by tag is rejected" "$work/tag-ref"

fixture short-sha <<EOF
name: short
on: push
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@0123456 # v7.0.1
EOF
expect_rule W3 "action pinned to a short SHA is rejected" "$work/short-sha"

fixture no-comment <<EOF
name: nocomment
on: push
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@$SHA
EOF
expect_rule W3 "SHA-pinned action without a version comment is rejected" "$work/no-comment"

fixture docker-action <<EOF
name: dockeraction
on: push
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - uses: docker://alpine:3.20
EOF
expect_rule W3 "docker:// action without a digest is rejected" "$work/docker-action"

fixture no-permissions <<EOF
name: noperm
on: push
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: true
EOF
expect_rule W4 "workflow without top-level permissions is rejected" "$work/no-permissions"

fixture workflow-run-checkout <<EOF
name: wr
on:
  workflow_run:
    workflows: [PR checks]
    types: [requested]
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@$SHA # v7.0.1
EOF
expect_rule W5 "actions/checkout in a workflow_run workflow is rejected" "$work/workflow-run-checkout"

fixture docker-run-tag <<EOF
name: dockerrun
on: push
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: docker run --rm alpine:3.20 true
EOF
expect_rule W6 "docker run with an unpinned image is rejected" "$work/docker-run-tag"

fixture image-var <<EOF
name: imagevar
on: push
permissions:
  contents: read
env:
  TOOL_IMAGE: alpine:3.20
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: docker run --rm "\$TOOL_IMAGE" true
EOF
expect_rule W6 "image variable without a digest is rejected" "$work/image-var"

fixture service-image <<EOF
name: services
on: push
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    services:
      db:
        image: postgres:16
    steps:
      - run: true
EOF
expect_rule W6 "service container image without a digest is rejected" "$work/service-image"

mkdir -p "$work/composite/.github/actions/setup" "$work/composite/.github/workflows"
cat >"$work/composite/.github/actions/setup/action.yml" <<EOF
name: setup
runs:
  using: composite
  steps:
    - uses: actions/setup-java@v5
EOF
expect_rule W3 "unpinned action inside a local composite action is rejected" "$work/composite"

# --- Bypasses closed after review (WO-00 security-4) ---------------------------------------------

# (a) W3: `uses:` inside flow mappings and with a quoted key.
fixture flow-uses-tag <<EOF
name: flowuses
on: push
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - { name: checkout, uses: actions/checkout@v4 }
EOF
expect_rule W3 "action referenced by tag inside a flow mapping is rejected" "$work/flow-uses-tag"

fixture flow-uses-no-comment <<EOF
name: flowuses
on: push
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - { name: checkout, uses: actions/checkout@$SHA, with: { fetch-depth: 1 } }
EOF
expect_rule W3 "SHA-pinned action in a flow mapping without a version comment is rejected" "$work/flow-uses-no-comment"

fixture quoted-uses-key <<EOF
name: quoteduses
on: push
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - "uses": actions/checkout@v4
EOF
expect_rule W3 "unpinned action with a quoted 'uses' key is rejected" "$work/quoted-uses-key"

fixture flow-uses-pinned <<EOF
name: flowuses
on: pull_request
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - { name: checkout, uses: actions/checkout@$SHA } # v7.0.1
      - { name: local, uses: ./.github/actions/local }
EOF
expect_exit 0 "pinned and local actions in flow mappings pass" bash "$script" "$work/flow-uses-pinned"

# W2 (same bypass as (a)): runs-on inside a flow-style job.
fixture flow-runs-on <<'EOF'
name: flowrunner
on: pull_request
permissions:
  contents: read
jobs:
  a: { runs-on: argos-matrix, steps: [{ run: "true" }] }
EOF
expect_rule W2 "custom runner label in a flow-style job of a pull_request workflow is rejected" "$work/flow-runs-on"

# (b) W4: top-level permissions are read-only; job-level write needs the allowlist.
fixture top-write-all <<EOF
name: topwriteall
on: push
permissions: write-all
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: true
EOF
expect_rule W4 "top-level 'permissions: write-all' is rejected" "$work/top-write-all"

fixture top-scope-write <<EOF
name: topwrite
on: push
permissions:
  contents: read
  packages: write
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: true
EOF
expect_rule W4 "top-level '<scope>: write' is rejected" "$work/top-scope-write"

fixture top-star-write <<EOF
name: topstar
on: push
permissions:
  *: write
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: true
EOF
expect_rule W4 "top-level '*: write' is rejected" "$work/top-star-write"

fixture top-flow-write <<EOF
name: topflow
on: push
permissions: { contents: read, pull-requests: write }
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: true
EOF
expect_rule W4 "top-level write in a flow mapping is rejected" "$work/top-flow-write"

fixture top-read-all <<EOF
name: topreadall
on: push
permissions: read-all
jobs:
  a:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      id-token: none
    steps:
      - run: true
EOF
expect_exit 0 "read-only top-level and job-level permissions pass" bash "$script" "$work/top-read-all"

fixture job-write-all <<EOF
name: jobwriteall
on: push
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    permissions: write-all
    steps:
      - run: true
EOF
expect_rule W4 "job-level 'permissions: write-all' is rejected" "$work/job-write-all"

fixture job-write-unlisted <<EOF
name: jobwrite
on: push
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - run: true
EOF
expect_rule W4 "job-level write that is not in the allowlist is rejected" "$work/job-write-unlisted"

fixture job-flow-write <<EOF
name: jobflow
on: push
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    permissions: { contents: read, issues: write }
    steps:
      - run: true
EOF
expect_rule W4 "job-level write in a flow mapping that is not in the allowlist is rejected" "$work/job-flow-write"

fixture flow-job-permissions <<'EOF'
name: flowjob
on: push
permissions:
  contents: read
jobs:
  a: { runs-on: ubuntu-latest, permissions: { contents: write }, steps: [{ run: "true" }] }
EOF
expect_rule W4 "permissions inside a flow-style job are rejected" "$work/flow-job-permissions"

fixture allowlisted codeql.yml <<EOF
name: CodeQL
on: push
permissions:
  contents: read
jobs:
  analyze:
    runs-on: ubuntu-latest
    permissions:
      actions: read
      contents: read
      security-events: write
    steps:
      - run: true
EOF
expect_exit 0 "allowlisted job-level write (codeql.yml, analyze, security-events) passes" bash "$script" "$work/allowlisted"

fixture allowlisted-other-scope codeql.yml <<EOF
name: CodeQL
on: push
permissions:
  contents: read
jobs:
  analyze:
    runs-on: ubuntu-latest
    permissions:
      contents: write
      security-events: write
    steps:
      - run: true
EOF
expect_rule W4 "allowlisted job with a scope outside the allowlist is rejected" "$work/allowlisted-other-scope"

fixture allowlisted-other-job codeql.yml <<EOF
name: CodeQL
on: push
permissions:
  contents: read
jobs:
  upload:
    runs-on: ubuntu-latest
    permissions:
      security-events: write
    steps:
      - run: true
EOF
expect_rule W4 "allowlisted scope in a job outside the allowlist is rejected" "$work/allowlisted-other-job"

# (c) W5: workflow_run run scripts must not fetch or check out code or use head_sha/head_branch.
workflow_run_fixture() { # workflow_run_fixture <name>: steps are read from stdin
  {
    cat <<'EOF'
name: wr
on:
  workflow_run:
    workflows: [PR checks]
    types: [requested]
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
EOF
    cat
  } | fixture "$1"
}

workflow_run_fixture wr-git-clone <<'EOF'
      - run: git clone https://github.com/example/fork.git
EOF
expect_rule W5 "git clone in a workflow_run run script is rejected" "$work/wr-git-clone"

workflow_run_fixture wr-git-fetch <<'EOF'
      - name: fetch
        run: |
          set -e
          git fetch origin "refs/pull/1/head"
EOF
expect_rule W5 "git fetch in a workflow_run run block is rejected" "$work/wr-git-fetch"

workflow_run_fixture wr-git-checkout <<'EOF'
      - name: checkout
        run: |
          git -C repo checkout "$SHA"
EOF
expect_rule W5 "git checkout (with options) in a workflow_run run block is rejected" "$work/wr-git-checkout"

workflow_run_fixture wr-gh-pr-checkout <<'EOF'
      - name: checkout
        env:
          GH_TOKEN: ${{ github.token }}
        run: gh pr checkout "$PR_NUMBER"
EOF
expect_rule W5 "gh pr checkout in a workflow_run run script is rejected" "$work/wr-gh-pr-checkout"

workflow_run_fixture wr-head-sha <<'EOF'
      - name: report
        run: |
          echo "head ${{ github.event.workflow_run.head_sha }}"
EOF
expect_rule W5 "head_sha in a workflow_run run script is rejected" "$work/wr-head-sha"

workflow_run_fixture wr-head-branch-flow <<'EOF'
      - { name: report, run: "echo ${{ github.event.workflow_run.head_branch }}" }
EOF
expect_rule W5 "head_branch in a flow-style workflow_run run step is rejected" "$work/wr-head-branch-flow"

workflow_run_fixture wr-env-only <<'EOF'
      - name: find
        env:
          HEAD_SHA: ${{ github.event.workflow_run.head_sha }}
        run: |
          if [[ ! "$HEAD_SHA" =~ ^[0-9a-f]{40}$ ]]; then exit 1; fi
          gh pr list --json number,headRefOid
EOF
expect_exit 0 "workflow_run that passes head_sha through env and never checks out passes" bash "$script" "$work/wr-env-only"

fixture push-git-fetch <<'EOF'
name: push
on: push
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: git fetch --tags origin
EOF
expect_exit 0 "git fetch outside workflow_run workflows passes" bash "$script" "$work/push-git-fetch"

# (d) W1: keys must be written literally.
fixture escaped-x <<'EOF'
name: escaped
on:
  "pull_request_\x74arget":
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: true
EOF
expect_rule W1 "trigger key spelled with a \\x escape is rejected" "$work/escaped-x"

# The key is "u<backslash>u0073es"; printf writes the backslash (octal 134) so that no tool on the
# way rewrites the escape sequence in this file.
{
  cat <<'EOF'
name: escaped
on: push
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
EOF
  printf '      - "u\134u0073es": actions/checkout@v4\n'
} | fixture escaped-u
expect_output "\"u$(printf '\134')u0073es\":" "fixture: the key contains a literal \\u escape" \
  cat "$work/escaped-u/.github/workflows/w.yml"
expect_rule W1 "key spelled with a \\u escape is rejected" "$work/escaped-u"

fixture escaped-U-flow <<'EOF'
name: escaped
on: { "pull_request_\U00000074arget": {} }
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: true
EOF
expect_rule W1 "key spelled with a \\U escape inside a flow mapping is rejected" "$work/escaped-U-flow"

fixture explicit-key <<'EOF'
name: explicit
on:
  ? pull_request
  : {}
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: true
EOF
expect_rule W1 "explicit YAML key is rejected" "$work/explicit-key"

mkdir -p "$work/composite-escaped/.github/actions/setup" "$work/composite-escaped/.github/workflows"
cat >"$work/composite-escaped/.github/actions/setup/action.yml" <<'EOF'
name: setup
runs:
  using: composite
  steps:
    - "u\x73es": actions/setup-java@v5
EOF
expect_rule W1 "escaped key inside a local composite action is rejected" "$work/composite-escaped"

expect_exit 2 "missing .github directory is a usage error" bash "$script" "$work/does-not-exist"

expect_exit 0 "repository workflows pass the policy" bash "$script" "$repo_root"

finish
