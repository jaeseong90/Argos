#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: O-S05, SEC-73
#
# T-BLD-027 (proposed; O-S05, SEC-73, docs/19 sections 6.3 and 6.6, docs/16 section 10.5, WO-47):
# self-hosted jobs never run for fork pull requests, feature-branch pushes or manual runs on a
# feature branch. Uses `check-workflows.sh --simulate`, which evaluates triggers, branch/tag filters,
# needs and the trusted-ref guards of every workflow, on
#   - the real repository,
#   - a fixture repository with nightly-like, bench-like and tag-gate self-hosted workflows, a pull
#     request workflow and its workflow_run follow-up,
#   - mutated copies (pull_request trigger added, guard removed, unfiltered push, ...): each must
#     make check-workflows.sh fail and the simulation show the self-hosted job, which proves the
#     assertions catch what they claim to.
# No Docker, no network.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script="$here/../check-workflows.sh"
repo_root=$(cd "$here/../../.." && pwd)
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

SHA=0123456789abcdef0123456789abcdef01234567
T=$'\t'
# Simulation lines of a self-hosted job that starts (or may start) on the runner.
SH_STARTS="${T}self-hosted${T}(runs|may-run)\$"
# Any simulation line of a self-hosted job, also a skipped one.
SH_ANY="${T}self-hosted${T}"

# expect_sim <description> <has|lacks> <extended regex> <root> <simulate arguments...>
expect_sim() {
  local desc=$1 want=$2 re=$3 root=$4
  shift 4
  local out code=0 found=0
  out=$(bash "$script" --simulate "$@" "$root" 2>&1) || code=$?
  if grep -Eq -- "$re" <<<"$out"; then found=1; fi
  TESTS_RUN=$((TESTS_RUN + 1))
  if [[ $code -eq 0 && (($want == has && $found -eq 1) || ($want == lacks && $found -eq 0)) ]]; then
    echo "PASS  T-BLD-027 $desc"
  else
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo "FAIL  T-BLD-027 $desc (exit $code; expected the output to $want /$re/)"
    printf '%s\n' "$out" | sed 's/^/      | /'
  fi
}

# fixture <repository> <file>: reads a workflow from stdin into <repository>/.github/workflows/<file>.
fixture() {
  mkdir -p "$work/$1/.github/workflows"
  cat >"$work/$1/.github/workflows/$2"
}

# mutate <source repository> <new repository> <file> <sed program>: a copy with one file edited.
mutate() {
  rm -rf "${work:?}/$2"
  cp -R "$1" "$work/$2"
  sed "$4" "$1/.github/workflows/$3" >"$work/$2/.github/workflows/$3"
  if cmp -s "$1/.github/workflows/$3" "$work/$2/.github/workflows/$3"; then
    echo "test bug: the mutation '$2' changed nothing" >&2
    exit 1
  fi
}

# --- Fixture repository --------------------------------------------------------------------------
fx="$work/fx"
fixture fx nightly.yml <<EOF
name: Nightly
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
    steps:
      - uses: actions/checkout@$SHA # v7.0.1
        with:
          persist-credentials: false
      - run: ./gradlew matrixTest
        env:
          TOKEN: \${{ secrets.ARGOS_MATRIX_TOKEN }}
  report:
    needs: matrix
    runs-on: ubuntu-latest
    steps:
      - run: true
EOF
fixture fx bench.yml <<EOF
name: Weekly bench
on:
  schedule:
    - cron: "0 18 * * 6"
  workflow_dispatch:
permissions:
  contents: read
jobs:
  bench:
    if: \${{ github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/heads/release/') }}
    runs-on:
      - self-hosted
      - argos-bench-app
    steps:
      - uses: actions/checkout@$SHA # v7.0.1
        with:
          persist-credentials: false
  publish:
    needs: bench
    if: needs.bench.result == 'success'
    runs-on: ubuntu-latest
    permissions:
      contents: write
      pull-requests: write
    steps:
      - run: true
EOF
fixture fx tag-gate.yml <<EOF
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
    steps:
      - uses: actions/checkout@$SHA # v7.0.1
        with:
          persist-credentials: false
EOF
fixture fx checks.yml <<EOF
name: PR checks
on:
  pull_request:
  push:
    branches: [main, "release/**"]
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@$SHA # v7.0.1
EOF
fixture fx labeler.yml <<EOF
name: PR labeler
on:
  workflow_run:
    workflows:
      - PR checks
    types:
      - requested
permissions:
  contents: read
jobs:
  label:
    if: github.event.workflow_run.event == 'pull_request'
    runs-on: ubuntu-latest
    steps:
      - run: true
EOF

expect_exit 0 "fixture repository passes check-workflows.sh" bash "$script" "$fx"

# Untrusted events: no self-hosted job starts.
expect_sim "fixture: a fork pull request triggers the pull request workflow" has \
  "^\.github/workflows/checks\.yml${T}build${T}github-hosted${T}runs\$" "$fx" pull_request --fork
expect_sim "fixture: a fork pull request also lists the workflow_run follow-up (base privileges)" has \
  "^\.github/workflows/labeler\.yml${T}label${T}github-hosted${T}may-run\$" "$fx" pull_request --fork
expect_sim "fixture: a fork pull request triggers no self-hosted job at all" lacks "$SH_ANY" "$fx" pull_request --fork
expect_sim "fixture: a fork pull request to release/1.0 triggers no self-hosted job" lacks "$SH_ANY" \
  "$fx" pull_request --fork --ref refs/heads/release/1.0
expect_sim "fixture: a push to a feature branch starts no self-hosted job" lacks "$SH_STARTS" \
  "$fx" push --ref refs/heads/feature/x
expect_sim "fixture: a push of a non-release tag starts no self-hosted job" lacks "$SH_STARTS" \
  "$fx" push --ref refs/tags/x1
expect_sim "fixture: a manual run on a feature branch starts no self-hosted job" lacks "$SH_STARTS" \
  "$fx" workflow_dispatch --ref refs/heads/feature/x
expect_sim "fixture: a manual run on a feature branch skips the self-hosted job by its guard" has \
  "^\.github/workflows/nightly\.yml${T}matrix${T}self-hosted${T}skipped:ref-guard\$" "$fx" workflow_dispatch --ref refs/heads/feature/x
expect_sim "fixture: ... and the jobs that need it" has \
  "^\.github/workflows/nightly\.yml${T}report${T}github-hosted${T}skipped:needs-matrix\$" "$fx" workflow_dispatch --ref refs/heads/feature/x

# Trusted events: the self-hosted jobs do run (the simulation is not vacuous).
expect_sim "fixture: schedule runs the nightly self-hosted job" has \
  "^\.github/workflows/nightly\.yml${T}matrix${T}self-hosted${T}runs\$" "$fx" schedule
expect_sim "fixture: schedule runs the bench self-hosted job" has \
  "^\.github/workflows/bench\.yml${T}bench${T}self-hosted${T}runs\$" "$fx" schedule
expect_sim "fixture: push to main runs the nightly self-hosted job" has \
  "^\.github/workflows/nightly\.yml${T}matrix${T}self-hosted${T}runs\$" "$fx" push --ref refs/heads/main
expect_sim "fixture: push to release/1.0 runs the nightly self-hosted job" has \
  "^\.github/workflows/nightly\.yml${T}matrix${T}self-hosted${T}runs\$" "$fx" push --ref refs/heads/release/1.0
expect_sim "fixture: push to main does not start the tag gate" lacks "tag-gate\.yml" "$fx" push --ref refs/heads/main
expect_sim "fixture: push of tag v0.1.0 runs the tag gate on the runner group" has \
  "^\.github/workflows/tag-gate\.yml${T}gate${T}self-hosted${T}runs\$" "$fx" push --ref refs/tags/v0.1.0
expect_sim "fixture: a manual run on main runs the bench self-hosted job" has \
  "^\.github/workflows/bench\.yml${T}bench${T}self-hosted${T}runs\$" "$fx" workflow_dispatch --ref refs/heads/main

# --- Real repository -----------------------------------------------------------------------------
expect_exit 0 "repository workflows pass check-workflows.sh (W1-W8)" bash "$script" "$repo_root"
expect_sim "repository: a fork pull request triggers pr.yml" has \
  "^\.github/workflows/pr\.yml${T}build-java${T}github-hosted${T}runs\$" "$repo_root" pull_request --fork
expect_sim "repository: a fork pull request triggers no self-hosted job at all" lacks "$SH_ANY" "$repo_root" pull_request --fork
expect_sim "repository: a pull request to release/1.0 triggers no self-hosted job at all" lacks "$SH_ANY" \
  "$repo_root" pull_request --ref refs/heads/release/1.0
expect_sim "repository: a push to a feature branch starts no self-hosted job" lacks "$SH_STARTS" \
  "$repo_root" push --ref refs/heads/feature/x
expect_sim "repository: a manual run on a feature branch starts no self-hosted job" lacks "$SH_STARTS" \
  "$repo_root" workflow_dispatch --ref refs/heads/feature/x
# chaos.yml and soak.yml (WO-52): the self-hosted jobs run only for the trusted triggers and refs.
expect_sim "repository: schedule runs the chaos self-hosted job" has \
  "^\.github/workflows/chaos\.yml${T}chaos${T}self-hosted${T}runs\$" "$repo_root" schedule
expect_sim "repository: a push to release/1.0 runs the chaos self-hosted job" has \
  "^\.github/workflows/chaos\.yml${T}chaos${T}self-hosted${T}runs\$" "$repo_root" push --ref refs/heads/release/1.0
expect_sim "repository: a push to main does not trigger chaos.yml" lacks \
  "^\.github/workflows/chaos\.yml${T}" "$repo_root" push --ref refs/heads/main
expect_sim "repository: a manual run on a feature branch skips the chaos job by its guard" has \
  "^\.github/workflows/chaos\.yml${T}chaos${T}self-hosted${T}skipped:ref-guard\$" "$repo_root" workflow_dispatch --ref refs/heads/feature/x
expect_sim "repository: a manual run on main runs the soak self-hosted job" has \
  "^\.github/workflows/soak\.yml${T}soak${T}self-hosted${T}runs\$" "$repo_root" workflow_dispatch --ref refs/heads/main
expect_sim "repository: a manual run on a feature branch skips the soak job by its guard" has \
  "^\.github/workflows/soak\.yml${T}soak${T}self-hosted${T}skipped:ref-guard\$" "$repo_root" workflow_dispatch --ref refs/heads/feature/x
expect_sim "repository: schedule does not trigger soak.yml (workflow_dispatch only)" lacks \
  "^\.github/workflows/soak\.yml${T}" "$repo_root" schedule
expect_sim "repository: a push to release/1.0 does not trigger soak.yml" lacks \
  "^\.github/workflows/soak\.yml${T}" "$repo_root" push --ref refs/heads/release/1.0

# --- Mutated copies: check-workflows.sh fails and the simulation shows the self-hosted job ---------
mutate "$fx" m-pr nightly.yml 's/^  workflow_dispatch:$/  workflow_dispatch:\n  pull_request:/'
expect_exit 1 "mutation pull_request trigger added: check-workflows.sh fails" bash "$script" "$work/m-pr"
expect_sim "mutation pull_request trigger added: a fork pull request reaches the self-hosted job" has \
  "^\.github/workflows/nightly\.yml${T}matrix${T}self-hosted${T}" "$work/m-pr" pull_request --fork

mutate "$fx" m-prt nightly.yml 's/^  workflow_dispatch:$/  workflow_dispatch:\n  pull_request_target:/'
expect_exit 1 "mutation pull_request_target trigger added: check-workflows.sh fails" bash "$script" "$work/m-prt"
expect_sim "mutation pull_request_target trigger added: the self-hosted job runs for a fork pull request (base ref passes the guard)" has \
  "^\.github/workflows/nightly\.yml${T}matrix${T}self-hosted${T}runs\$" "$work/m-prt" pull_request --fork

mutate "$fx" m-pr-noguard nightly.yml 's/^  workflow_dispatch:$/  workflow_dispatch:\n  pull_request:/; /^    if: /d'
expect_exit 1 "mutation pull_request trigger added and guard removed: check-workflows.sh fails" bash "$script" "$work/m-pr-noguard"
expect_sim "mutation pull_request trigger added and guard removed: the self-hosted job runs for a fork pull request" has \
  "^\.github/workflows/nightly\.yml${T}matrix${T}self-hosted${T}runs\$" "$work/m-pr-noguard" pull_request --fork

# shellcheck disable=SC2016 # matches a literal ${{ (GitHub expression) in the fixture
mutate "$fx" m-noguard bench.yml '/^    if: \${{ github.ref/d'
expect_exit 1 "mutation guard removed: check-workflows.sh fails" bash "$script" "$work/m-noguard"
expect_sim "mutation guard removed: a manual run on a feature branch runs the self-hosted job" has \
  "^\.github/workflows/bench\.yml${T}bench${T}self-hosted${T}runs\$" "$work/m-noguard" workflow_dispatch --ref refs/heads/feature/x

mutate "$fx" m-push nightly.yml '/^    branches:$/,/"release\/\*\*"$/d'
expect_exit 1 "mutation unfiltered push: check-workflows.sh fails" bash "$script" "$work/m-push"
expect_sim "mutation unfiltered push: a feature push triggers the workflow and only the guard skips the job" has \
  "^\.github/workflows/nightly\.yml${T}matrix${T}self-hosted${T}skipped:ref-guard\$" "$work/m-push" push --ref refs/heads/feature/x

mutate "$fx" m-push-noguard nightly.yml '/^    branches:$/,/"release\/\*\*"$/d; /^    if: /d'
expect_exit 1 "mutation unfiltered push and guard removed: check-workflows.sh fails" bash "$script" "$work/m-push-noguard"
expect_sim "mutation unfiltered push and guard removed: a feature push runs the self-hosted job" has \
  "^\.github/workflows/nightly\.yml${T}matrix${T}self-hosted${T}runs\$" "$work/m-push-noguard" push --ref refs/heads/feature/x

mutate "$fx" m-followup labeler.yml "s/^    if: .*/    if: github.ref == 'refs\/heads\/main' || startsWith(github.ref, 'refs\/heads\/release\/')/; s/^    runs-on: ubuntu-latest\$/    runs-on: [self-hosted, argos-matrix]/"
expect_exit 1 "mutation self-hosted workflow_run follow-up: check-workflows.sh fails" bash "$script" "$work/m-followup"
expect_sim "mutation self-hosted workflow_run follow-up: a fork pull request runs it (the follow-up sees the default branch)" has \
  "^\.github/workflows/labeler\.yml${T}label${T}self-hosted${T}runs\$" "$work/m-followup" pull_request --fork

rm -rf "$work/m-repo"
mkdir -p "$work/m-repo/.github"
cp -R "$repo_root/.github/workflows" "$work/m-repo/.github/workflows"
cat >>"$work/m-repo/.github/workflows/pr.yml" <<EOF

  intruder:
    runs-on: [self-hosted, argos-matrix]
    steps:
      - run: true
EOF
expect_exit 1 "mutation of the repository (self-hosted job appended to pr.yml): check-workflows.sh fails" \
  bash "$script" "$work/m-repo"
expect_sim "mutation of the repository: a fork pull request runs the appended self-hosted job" has \
  "^\.github/workflows/pr\.yml${T}intruder${T}self-hosted${T}runs\$" "$work/m-repo" pull_request --fork

# --- Usage ---------------------------------------------------------------------------------------
expect_exit 2 "--simulate without an event is a usage error" bash "$script" --simulate
expect_exit 2 "--simulate with an unknown event is a usage error" bash "$script" --simulate release "$fx"
expect_exit 2 "--fork with push is a usage error" bash "$script" --simulate push --fork "$fx"
expect_exit 2 "a pull request base ref must be a branch" bash "$script" --simulate pull_request --ref refs/tags/v1 "$fx"
expect_exit 0 "a simulation without triggered workflows exits 0" bash "$script" --simulate push --ref refs/heads/feature/x "$fx"

finish
