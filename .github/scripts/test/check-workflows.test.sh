#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: SEC-70, SEC-71, SEC-73, SEC-93, O-S05, O-Q02
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
    if: github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/heads/release/')
    runs-on: [self-hosted, argos-matrix]
    steps:
      - uses: actions/checkout@$SHA # v7.0.1
        with:
          persist-credentials: false
EOF
expect_exit 0 "self-hosted runner in a schedule/workflow_dispatch workflow is allowed (with the W8 guard)" bash "$script" "$work/selfhosted-nightly"

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

# nightly.yml (ADR-0010 C-256 (4)): the GitHub-hosted publish job writes contents and pull requests.
fixture nightly-publish nightly.yml <<EOF
name: nightly
on:
  schedule:
    - cron: "0 17 * * *"
permissions:
  contents: read
jobs:
  publish:
    runs-on: ubuntu-latest
    permissions:
      contents: write
      pull-requests: write
    steps:
      - run: true
EOF
expect_exit 0 "nightly.yml: allowlisted writes of publish (contents, pull-requests) pass" bash "$script" "$work/nightly-publish"

fixture nightly-publish-packages nightly.yml <<EOF
name: nightly
on:
  schedule:
    - cron: "0 17 * * *"
permissions:
  contents: read
jobs:
  publish:
    runs-on: ubuntu-latest
    permissions:
      contents: write
      packages: write
    steps:
      - run: true
EOF
expect_rule W4 "nightly.yml: publish may not write packages" "$work/nightly-publish-packages"

fixture nightly-report-write nightly.yml <<EOF
name: nightly
on:
  schedule:
    - cron: "0 17 * * *"
permissions:
  contents: read
jobs:
  report:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - run: true
EOF
expect_rule W4 "nightly.yml: a write scope in another job (report: contents) is rejected" "$work/nightly-report-write"

# release.yml (WO-44): write scopes only in images, sign and draft, each with its own scopes.
fixture release-allowed release.yml <<EOF
name: Release
on:
  push:
    tags: ["v*"]
permissions:
  contents: read
jobs:
  images:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
      id-token: write
      attestations: write
    steps:
      - run: true
  sign:
    runs-on: ubuntu-latest
    environment: release
    permissions:
      contents: read
      id-token: write
      attestations: write
    steps:
      - run: true
  draft:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - run: true
EOF
expect_exit 0 "release.yml: allowlisted writes (images: packages, id-token, attestations; sign: id-token, attestations; draft: contents) pass" \
  bash "$script" "$work/release-allowed"

fixture release-build-write release.yml <<EOF
name: Release
on:
  push:
    tags: ["v*"]
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - run: true
EOF
expect_rule W4 "release.yml: a write scope in another job (build: contents) is rejected" "$work/release-build-write"

fixture release-draft-packages release.yml <<EOF
name: Release
on:
  push:
    tags: ["v*"]
permissions:
  contents: read
jobs:
  draft:
    runs-on: ubuntu-latest
    permissions:
      contents: write
      packages: write
    steps:
      - run: true
EOF
expect_rule W4 "release.yml: draft may write contents only (packages is rejected)" "$work/release-draft-packages"

fixture release-sign-contents release.yml <<EOF
name: Release
on:
  push:
    tags: ["v*"]
permissions:
  contents: read
jobs:
  sign:
    runs-on: ubuntu-latest
    permissions:
      contents: write
      id-token: write
    steps:
      - run: true
EOF
expect_rule W4 "release.yml: sign may not write contents" "$work/release-sign-contents"

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

# --- W7: security jobs of pr.yml (T-SEC-93a, T-SEC-70a, T-SEC-71a; WO-46) ------------------------
# pr_fixture <name> [file]: a pull_request workflow whose jobs are read from stdin (default file
# pr.yml, the only file W7 applies to).
pr_fixture() {
  {
    cat <<EOF
name: PR checks
on:
  pull_request:
permissions:
  contents: read
jobs:
EOF
    cat
  } | fixture "$1" "${2:-pr.yml}"
}

BUILD_JAVA_JOB='  build-java:
    runs-on: ubuntu-latest
    steps:
      - name: Build and unit test
        run: ./gradlew build -x integrationTest'
SECURITY_JOB='  security-light:
    runs-on: ubuntu-latest
    steps:
      - name: Security tests (PR subset)
        run: ./gradlew securityTest'
SBOM_JOB='  sbom-scan:
    runs-on: ubuntu-latest
    steps:
      - name: SBOMs
        run: ./gradlew :agent:agent-dist:agentSbom :server:serverSbom :ui:pnpmBuild
      - name: Scan
        run: >-
          bash test/release/check-sbom.sh --scanner "bash test/release/trivy-scan.sh"
          --require agent,server,ui build/sbom-scan'

printf '%s\n%s\n%s\n' "$BUILD_JAVA_JOB" "$SECURITY_JOB" "$SBOM_JOB" | pr_fixture pr-good
expect_exit 0 "T-SEC-93a/T-SEC-71a pr.yml with security-light (./gradlew securityTest) and sbom-scan (check-sbom.sh + Trivy) passes" \
  bash "$script" "$work/pr-good"

printf '%s\n%s\n' "$BUILD_JAVA_JOB" "$SBOM_JOB" | pr_fixture pr-no-security
expect_rule W7 "T-SEC-93a pr.yml without a job running ./gradlew securityTest is rejected" "$work/pr-no-security"

printf '%s\n%s\n%s\n' "$BUILD_JAVA_JOB" "$SECURITY_JOB" "$SBOM_JOB" |
  sed 's|run: ./gradlew securityTest|run: ./gradlew :server:securityTest --tests "*Csrf*"|' | pr_fixture pr-partial-security
expect_rule W7 "T-SEC-93a a job that runs only one project's securityTest does not count" "$work/pr-partial-security"

printf '%s\n%s\n' "$SECURITY_JOB" "$SBOM_JOB" | sed 's/security-light:/build-java:/' | pr_fixture pr-security-in-build
expect_rule W7 "T-SEC-93a securityTest run only by build-java is rejected" "$work/pr-security-in-build"
expect_output "build-java must not run securityTest" "T-SEC-93a securityTest in build-java is named" \
  bash "$script" "$work/pr-security-in-build"

printf '%s\n%s\n%s\n' "$BUILD_JAVA_JOB" "$SECURITY_JOB" "$SBOM_JOB" |
  sed 's|run: ./gradlew build -x integrationTest|run: ./gradlew build securityTest|' | pr_fixture pr-build-runs-security
expect_rule W7 "T-SEC-93a build-java running securityTest next to security-light is rejected" "$work/pr-build-runs-security"

printf '%s\n%s\n' "$BUILD_JAVA_JOB" "$SBOM_JOB" | {
  cat
  cat <<'EOF'
  security-light:
    runs-on: ubuntu-latest
    steps:
      # ./gradlew securityTest
      - name: Security tests (./gradlew securityTest in a step name only)
        run: echo skipped
EOF
} | pr_fixture pr-security-comment
expect_rule W7 "T-SEC-93a securityTest in a comment or step name only does not count" "$work/pr-security-comment"

printf '%s\n%s\n' "$BUILD_JAVA_JOB" "$SECURITY_JOB" | pr_fixture pr-no-sbom
expect_rule W7 "T-SEC-70a pr.yml without the sbom-scan job is rejected" "$work/pr-no-sbom"

printf '%s\n%s\n%s\n' "$BUILD_JAVA_JOB" "$SECURITY_JOB" "$SBOM_JOB" |
  sed 's|--scanner "bash test/release/trivy-scan.sh"||' | pr_fixture pr-sbom-no-scanner
expect_rule W7 "T-SEC-71a sbom-scan without the Trivy scanner (format and exception checks only) is rejected" \
  "$work/pr-sbom-no-scanner"

printf '%s\n%s\n%s\n' "$BUILD_JAVA_JOB" "$SECURITY_JOB" "$SBOM_JOB" |
  sed 's|--require agent,server,ui|--require agent,server|' | pr_fixture pr-sbom-two-parts
expect_rule W7 "T-SEC-70a sbom-scan that does not require the agent, server and UI SBOMs is rejected" \
  "$work/pr-sbom-two-parts"

printf '%s\n' "$BUILD_JAVA_JOB" | pr_fixture other-workflow w.yml
expect_exit 0 "W7 applies to pr.yml only (another pull_request workflow without security jobs passes)" \
  bash "$script" "$work/other-workflow"

expect_exit 0 "T-SEC-93a/T-SEC-70a/T-SEC-71a the repository pr.yml runs securityTest outside build-java and has the sbom-scan job" \
  bash "$script" "$repo_root"

# --- W8: self-hosted jobs (T-BLD-027 proposed, O-S05, docs/19 sections 6.3 and 6.6; WO-47) -------
# SH_OK is a compliant nightly-like workflow: schedule, workflow_dispatch and push to main and
# release/**, the branch guard, checkout without persisted credentials and a secret in the trusted
# environment. Each violating fixture below changes exactly one clause of it (sh_variant).
SH_OK=$(
  cat <<EOF
name: nightly
on:
  schedule:
    - cron: "0 18 * * *"
  workflow_dispatch:
  push:
    branches:
      - main
      - "release/**"
permissions:
  contents: read
jobs:
  matrix:
    if: github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/heads/release/')
    runs-on: [self-hosted, linux, argos-matrix]
    environment: trusted
    timeout-minutes: 300
    steps:
      - uses: actions/checkout@$SHA # v7.0.1
        with:
          persist-credentials: false
      - name: Matrix
        env:
          ARGOS_MATRIX_TOKEN: \${{ secrets.ARGOS_MATRIX_TOKEN }}
        run: ./gradlew matrixTest
EOF
)

# sh_variant <name> <sed program> [file]: SH_OK edited by sed, as fixture <name>.
sh_variant() {
  printf '%s\n' "$SH_OK" | sed "$2" | fixture "$1" "${3:-w.yml}"
  if cmp -s <(printf '%s\n' "$SH_OK") "$work/$1/.github/workflows/${3:-w.yml}"; then
    echo "test bug: the sed program of fixture '$1' changed nothing" >&2
    exit 1
  fi
}

# expect_w8 <description> <fixture> <message part>: W8 is reported with the given message.
# The checker runs once per fixture: exit 1, a [W8] line and the message are checked together.
expect_w8() {
  local out code=0
  out=$(bash "$script" "$work/$2" 2>&1) || code=$?
  TESTS_RUN=$((TESTS_RUN + 1))
  if [[ $code -eq 1 && "$out" == *"[W8]"* && "$out" == *"$3"* ]]; then
    echo "PASS  T-BLD-027 $1"
  else
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo "FAIL  T-BLD-027 $1 (exit $code; expected 1 with a [W8] line naming '$3')"
    printf '%s\n' "$out" | sed 's/^/      | /'
  fi
}

printf '%s\n' "$SH_OK" | fixture sh-ok
expect_exit 0 "T-BLD-027 nightly-like self-hosted workflow (schedule, dispatch, push main/release/**, guard, trusted environment) passes" \
  bash "$script" "$work/sh-ok"

# Triggers.
sh_variant sh-pull-request 's/^  workflow_dispatch:$/  workflow_dispatch:\n  pull_request:/'
expect_w8 "self-hosted job in a workflow with a pull_request trigger is rejected" sh-pull-request "triggered by 'pull_request'"
sh_variant sh-release 's/^  workflow_dispatch:$/  workflow_dispatch:\n  release:\n    types: [published]/'
expect_w8 "self-hosted job in a workflow with a release trigger is rejected" sh-release "triggered by 'release'"
sh_variant sh-workflow-call 's/^  workflow_dispatch:$/  workflow_dispatch:\n  workflow_call:/'
expect_w8 "self-hosted job in a reusable (workflow_call) workflow is rejected" sh-workflow-call "triggered by 'workflow_call'"
sh_variant sh-workflow-run 's/^  workflow_dispatch:$/  workflow_dispatch:\n  workflow_run:\n    workflows: [PR checks]/'
expect_w8 "self-hosted job in a workflow_run workflow is rejected" sh-workflow-run "triggered by 'workflow_run'"
sh_variant sh-push-unfiltered '/^    branches:$/,/"release\/\*\*"$/d'
expect_w8 "unfiltered push in a self-hosted workflow is rejected" sh-push-unfiltered "push must be filtered"
sh_variant sh-push-inline 's/^on:$/on: [schedule, workflow_dispatch, push]\nx-on:/'
expect_w8 "inline 'on: [.., push]' (no filters) in a self-hosted workflow is rejected" sh-push-inline "push must be filtered"
sh_variant sh-push-feature 's/^      - main$/      - main\n      - "feature\/**"/'
expect_w8 "push filter with a feature branch in a self-hosted workflow is rejected" sh-push-feature "'feature/**' is not a trusted branch"
sh_variant sh-push-wildcard 's/^      - main$/      - "**"/'
expect_w8 "push filter '**' in a self-hosted workflow is rejected" sh-push-wildcard "'**' is not a trusted branch"
sh_variant sh-push-negated 's/^      - main$/      - main\n      - "!main"/'
expect_w8 "negated push branch pattern in a self-hosted workflow is rejected" sh-push-negated "'!main' is not a trusted branch"
sh_variant sh-branches-ignore 's/^    branches:$/    branches-ignore:/'
expect_w8 "push branches-ignore in a self-hosted workflow is rejected" sh-branches-ignore "branches-ignore or tags-ignore"
sh_variant sh-tags-ignore 's/^    branches:$/    tags-ignore:/'
expect_w8 "push tags-ignore in a self-hosted workflow is rejected" sh-tags-ignore "branches-ignore or tags-ignore"
sh_variant sh-tags-other 's/^    branches:$/    tags: ["*"]\n    branches:/'
expect_w8 "push tag filter other than v* in a self-hosted workflow is rejected" sh-tags-other "'*' is not the release tag pattern"
sh_variant sh-on-flow 's/^on:$/on: { push: { branches: [main] }, schedule: [] }\nx-on:/'
expect_w8 "push filters inside an inline 'on:' flow mapping are rejected" sh-on-flow "in block form"

# Trusted-ref guard.
sh_variant sh-no-guard '/^    if: /d'
expect_w8 "self-hosted job without a job-level if: guard is rejected" sh-no-guard "needs a job-level 'if:' trusted-ref guard"
sh_variant sh-other-guard "s|^    if: .*|    if: github.event_name != 'pull_request'|"
expect_w8 "self-hosted job with a guard outside TRUSTED_REF_GUARDS is rejected" sh-other-guard "is not a trusted-ref guard"
sh_variant sh-weak-guard "s|^    if: .*|    if: startsWith(github.ref, 'refs/heads/')|"
expect_w8 "self-hosted job guarded by any branch is rejected" sh-weak-guard "is not a trusted-ref guard"
sh_variant sh-guard-or "s|^    if: \\(.*\\)|    if: \\1 \|\| github.event_name == 'workflow_dispatch'|"
expect_w8 "trusted guard widened with '|| ...' is rejected" sh-guard-or "is not a trusted-ref guard"
sh_variant sh-guard-step "s|^    if: .*|    steps-if: x|; s|^      - name: Matrix$|      - name: Matrix\n        if: github.ref == 'refs/heads/main' \|\| startsWith(github.ref, 'refs/heads/release/')|"
expect_w8 "a guard on a step instead of the job is rejected" sh-guard-step "needs a job-level 'if:'"

# Checkout.
sh_variant sh-persist-missing '/^        with:$/d; /persist-credentials/d'
expect_w8 "actions/checkout without persist-credentials in a self-hosted job is rejected" sh-persist-missing "persist-credentials: false"
sh_variant sh-persist-true 's/persist-credentials: false/persist-credentials: true/'
expect_w8 "actions/checkout with persist-credentials: true in a self-hosted job is rejected" sh-persist-true "persist-credentials: false"
# shellcheck disable=SC2016 # a literal ${{ }} GitHub expression written into the fixture
sh_variant sh-checkout-ref 's/^          persist-credentials: false$/          persist-credentials: false\n          ref: ${{ github.event.inputs.ref }}/'
expect_w8 "actions/checkout with ref: in a self-hosted job is rejected" sh-checkout-ref "must not set 'ref:'"
sh_variant sh-checkout-repository 's/^          persist-credentials: false$/          persist-credentials: false\n          repository: someone\/fork/'
expect_w8 "actions/checkout with repository: in a self-hosted job is rejected" sh-checkout-repository "must not set 'repository:'"
sh_variant sh-checkout-flow "s|^      - uses: actions/checkout@$SHA # v7.0.1$|      - { uses: actions/checkout@$SHA, with: { ref: main } } # v7.0.1|; /^        with:$/d; /persist-credentials/d"
expect_w8 "flow-style checkout step with ref: in a self-hosted job is rejected" sh-checkout-flow "must not set 'ref:'"

# Secrets and environment.
sh_variant sh-secret-no-env '/^    environment: trusted$/d'
expect_w8 "secret in a self-hosted job without environment: trusted is rejected" sh-secret-no-env "needs the job-level 'environment: trusted'"
sh_variant sh-secret-other-env 's/^    environment: trusted$/    environment: release/'
expect_w8 "secret in a self-hosted job with another environment is rejected" sh-secret-other-env "needs the job-level 'environment: trusted'"
sh_variant sh-secret-token '/^    environment: trusted$/d; s/secrets.ARGOS_MATRIX_TOKEN/secrets.GITHUB_TOKEN/'
expect_w8 "secrets.GITHUB_TOKEN in a self-hosted job needs environment: trusted as well" sh-secret-token "environment: trusted"
# shellcheck disable=SC2016 # a literal ${{ }} GitHub expression written into the fixture
sh_variant sh-secret-run '/^    environment: trusted$/d; /ARGOS_MATRIX_TOKEN/d; /^        env:$/d; s|run: ./gradlew matrixTest|run: echo "\${{ secrets.X }}" \| ./gradlew matrixTest|'
expect_w8 "secret interpolated in a run script of a self-hosted job is rejected without environment: trusted" sh-secret-run "environment: trusted"
# shellcheck disable=SC2016 # a literal ${{ }} GitHub expression written into the fixture
sh_variant sh-secret-toplevel '/^    environment: trusted$/d; /ARGOS_MATRIX_TOKEN/d; /^        env:$/d; s/^permissions:$/env:\n  TOKEN: ${{ secrets.ARGOS_TOKEN }}\npermissions:/'
expect_w8 "workflow-level secret with a self-hosted job without environment: trusted is rejected" sh-secret-toplevel "environment: trusted"

# Permissions: never write, not even when allowlisted (bench.yml publish is allowlisted).
sh_variant sh-write-allowlisted 's/^  matrix:$/  publish:/; s/^    timeout-minutes: 300$/    permissions:\n      contents: write/' bench.yml
expect_w8 "self-hosted job with an allowlisted write scope is rejected" sh-write-allowlisted "must not have write permissions"
sh_variant sh-write-all 's/^    timeout-minutes: 300$/    permissions: write-all/'
expect_w8 "self-hosted job with write-all is rejected" sh-write-all "must not have write permissions"

# runs-on forms: each one counts as self-hosted (shown by the missing guard).
sh_variant sh-runs-scalar "/^    if: /d; s/^    runs-on: .*/    runs-on: self-hosted/"
expect_w8 "scalar runs-on: self-hosted counts as self-hosted" sh-runs-scalar "self-hosted job 'matrix' needs"
sh_variant sh-runs-block "/^    if: /d; s/^    runs-on: .*/    runs-on:\n      - linux\n      - argos-matrix/"
expect_w8 "block-list runs-on with an argos-* label counts as self-hosted" sh-runs-block "self-hosted job 'matrix' needs"
sh_variant sh-runs-flow "/^    if: /d; s/^    runs-on: .*/    runs-on: [\"linux\", 'argos-bench-app']/"
expect_w8 "flow-list runs-on with quoted labels counts as self-hosted" sh-runs-flow "self-hosted job 'matrix' needs"
sh_variant sh-runs-group "/^    if: /d; s/^    runs-on: .*/    runs-on:\n      group: argos-trusted/"
expect_w8 "runner group (runs-on: group:) counts as self-hosted" sh-runs-group "self-hosted job 'matrix' needs"
sh_variant sh-runs-labels "/^    if: /d; s/^    runs-on: .*/    runs-on: { group: argos-trusted, labels: [linux] }/"
expect_w8 "runner group in a flow mapping counts as self-hosted" sh-runs-labels "self-hosted job 'matrix' needs"
sh_variant sh-runs-expression "/^    if: /d; s/^    runs-on: .*/    runs-on: \${{ vars.RUNNER }}/"
expect_w8 "runs-on expression counts as self-hosted" sh-runs-expression "self-hosted job 'matrix' needs"
{
  printf '%s\n' "$SH_OK" | sed '/^  matrix:$/,$d'
  echo "  matrix: { runs-on: [self-hosted], steps: [ { run: true } ] }"
} | fixture sh-flow-job
expect_w8 "flow-style self-hosted job is rejected" sh-flow-job "must be written in block form"
{
  printf '%s\n' "$SH_OK" | sed '/^    steps:$/,$d'
  echo "    steps: [ { uses: actions/checkout@$SHA } ]"
} | fixture sh-flow-steps
expect_w8 "self-hosted job with flow-style steps is rejected" sh-flow-steps "must list its steps in block form"

# Passing shapes: guard written as an expression, bench-like (schedule and dispatch, GitHub-hosted
# write follow-up), tag-gate with a runner group and a trusted environment mapping, push filters in
# a flow mapping, GitHub-hosted jobs in the same workflow without a guard.
sh_variant sh-guard-expression "s|^    if: \\(.*\\)|    if: \${{  \\1  }}|"
expect_exit 0 "T-BLD-027 trusted guard written as \${{ }} with extra spaces passes" bash "$script" "$work/sh-guard-expression"

fixture sh-bench-like bench.yml <<EOF
name: Weekly bench
on:
  schedule:
    - cron: "0 18 * * 6"
  workflow_dispatch:
    inputs:
      sets:
        type: choice
        options:
          - main
          - noop
permissions:
  contents: read
jobs:
  bench:
    if: \${{ github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/heads/release/') }}
    runs-on: [self-hosted, linux, argos-bench-app]
    steps:
      - uses: actions/checkout@$SHA # v7.0.1
        with:
          persist-credentials: false
      - run: test/bench/bench-protocol.sh --sets "\${{ inputs.sets }}"
  publish:
    needs: bench
    if: needs.bench.result == 'success'
    runs-on: ubuntu-latest
    permissions:
      contents: write
      pull-requests: write
    steps:
      - uses: actions/checkout@$SHA # v7.0.1
      - run: gh pr create --fill
        env:
          GH_TOKEN: \${{ secrets.GITHUB_TOKEN }}
EOF
expect_exit 0 "T-BLD-027 bench-like workflow (self-hosted bench, GitHub-hosted allowlisted publish) passes" \
  bash "$script" "$work/sh-bench-like"

fixture sh-tag-gate <<EOF
name: Release gate
on:
  push:
    tags:
      - "v*"
permissions:
  contents: read
jobs:
  prepare:
    runs-on: ubuntu-latest
    steps:
      - run: true
  gate:
    needs: prepare
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on:
      group: argos-release
      labels: [self-hosted, argos-matrix]
    environment:
      name: trusted
    steps:
      - uses: actions/checkout@$SHA # v7.0.1
        with:
          persist-credentials: false
      - run: ./gradlew releaseGate
        env:
          KEY: \${{ secrets.ARGOS_GATE_KEY }}
EOF
expect_exit 0 "T-BLD-027 tag-gate workflow (push tags v*, tag guard, runner group, environment mapping) passes" \
  bash "$script" "$work/sh-tag-gate"

sh_variant sh-push-flow 's/^  push:$/  push: { branches: [main, "release\/**"] }/; /^    branches:$/,/"release\/\*\*"$/d'
expect_exit 0 "T-BLD-027 push filters in a flow mapping (main, release/**) pass" bash "$script" "$work/sh-push-flow"

expect_exit 2 "missing .github directory is a usage error" bash "$script" "$work/does-not-exist"
expect_exit 2 "--ref without --simulate is a usage error" bash "$script" --ref refs/heads/main "$repo_root"
expect_exit 2 "unknown simulated event is a usage error" bash "$script" --simulate pull_request_target "$repo_root"
expect_exit 2 "--fork with an event other than pull_request is a usage error" bash "$script" --simulate push --fork "$repo_root"
expect_exit 2 "a ref outside refs/heads and refs/tags is a usage error" bash "$script" --simulate push --ref main "$repo_root"
expect_exit 2 "two repository roots are a usage error" bash "$script" "$repo_root" "$repo_root"

expect_exit 0 "repository workflows pass the policy" bash "$script" "$repo_root"

finish
