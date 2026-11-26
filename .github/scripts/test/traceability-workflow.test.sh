#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: DSN-18-01, O-Q02
#
# Structure test of the `traceability` job of .github/workflows/pr.yml (T-BLD-014j, WO-53; docs/18
# section 3: the CI job fails on a catalogue or mapping difference). The job runs on a GitHub-hosted
# runner with a time limit, checks out without persisted credentials, runs checkTraceability,
# traceabilityReport and gate1Report without the UI build, downloads no test results (the clean
# checkout reports NOT RUN, never FAILED), keeps the reports 90 days and writes nothing; the workflow
# never uses pull_request_target; branch-protection.sh requires the check. Each assertion also runs
# on mutated copies that must fail. No Docker, no network.
#
#   bash .github/scripts/test/traceability-workflow.test.sh
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/../../.." && pwd)
pr="$repo/.github/workflows/pr.yml"
bp="$repo/.github/scripts/branch-protection.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# job_block <workflow> <job id>: the lines of one job (from "  <id>:" to the next job key).
job_block() {
  awk -v id="$2" '
    $0 == "  " id ":" { on = 1; print; next }
    on && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { exit }
    on && /^[A-Za-z]/ { exit }
    on { print }
  ' "$1"
}

# check_job <workflow>: the traceability job contract; prints the first broken rule.
check_job() {
  local wf=$1 block
  block=$(job_block "$wf" traceability)
  if [[ -z "$block" ]]; then
    echo "no job 'traceability'"
    return 1
  fi
  grep -qxE '    name: traceability' <<<"$block" || { echo "job name is not 'traceability'"; return 1; }
  grep -qxE '    runs-on: ubuntu-latest' <<<"$block" || { echo "not on the GitHub-hosted ubuntu-latest runner"; return 1; }
  if grep -q 'self-hosted' <<<"$block"; then echo "mentions self-hosted"; return 1; fi
  grep -qE '^    timeout-minutes: [0-9]+$' <<<"$block" || { echo "no timeout-minutes"; return 1; }
  if grep -qE '^    permissions:' <<<"$block"; then echo "job-level permissions (the workflow's contents: read is enough)"; return 1; fi
  grep -qE 'uses: actions/checkout@[0-9a-f]{40} # v' <<<"$block" || { echo "no SHA-pinned checkout"; return 1; }
  grep -qxE '          persist-credentials: false' <<<"$block" || { echo "checkout persists credentials"; return 1; }
  grep -qE 'uses: actions/setup-java@[0-9a-f]{40} # v' <<<"$block" || { echo "no SHA-pinned setup-java"; return 1; }
  grep -qE '^            21$' <<<"$block" || { echo "JDK 21 (runs Gradle) is not set up"; return 1; }
  grep -qE 'uses: gradle/actions/setup-gradle@[0-9a-f]{40} # v' <<<"$block" || { echo "no SHA-pinned setup-gradle"; return 1; }
  local cmd
  cmd=$(grep -E '^        run: \./gradlew ' <<<"$block" || true)
  [[ -n "$cmd" ]] || { echo "no ./gradlew run step"; return 1; }
  local task
  for task in checkTraceability traceabilityReport gate1Report -Pargos.ui.skip=true; do
    grep -qE "(^| )${task}( |$)" <<<"$cmd" || { echo "the Gradle command lacks $task"; return 1; }
  done
  if grep -qE '(^| )-x |--exclude-task|-Ptraceability\.results=' <<<"$cmd"; then
    echo "the Gradle command excludes tasks or changes the result input"
    return 1
  fi
  if grep -qE 'download-artifact|actions/cache@|gh run download|test-results' <<<"$block"; then
    echo "the job fetches test results (the clean checkout must report NOT RUN)"
    return 1
  fi
  grep -qE 'uses: actions/upload-artifact@[0-9a-f]{40} # v' <<<"$block" || { echo "no SHA-pinned upload-artifact"; return 1; }
  grep -qxE '        if: always\(\)' <<<"$block" || { echo "the reports are not uploaded when the check fails"; return 1; }
  grep -qxE '            build/reports/traceability\*' <<<"$block" || { echo "the artifact lacks build/reports/traceability*"; return 1; }
  grep -qxE '            build/reports/gate1\.md' <<<"$block" || { echo "the artifact lacks build/reports/gate1.md"; return 1; }
  grep -qxE '          retention-days: 90' <<<"$block" || { echo "the artifact is not kept 90 days"; return 1; }
  return 0
}

# check_workflow <workflow>: workflow-level rules that keep the job safe.
check_workflow() {
  local wf=$1
  if grep -vE '^[[:space:]]*#' "$wf" | grep -q 'pull_request_target'; then
    echo "pull_request_target is used"
    return 1
  fi
  grep -qxE '  pull_request:' "$wf" || { echo "the workflow does not run on pull requests"; return 1; }
  awk '/^permissions:/ { on = 1; next } on && /^[^ ]/ { exit } on { print }' "$wf" >"$work/perm"
  grep -qxE '  contents: read' "$work/perm" || { echo "top-level permissions are not contents: read"; return 1; }
  if grep -q 'write' "$work/perm"; then echo "top-level write permission"; return 1; fi
  return 0
}

# check_required <branch-protection.sh>: the job is a required status check.
check_required() {
  local listing
  listing=$(bash "$1" --list-checks 2>/dev/null) || return 1
  grep -qxF $'required\ttraceability' <<<"$listing"
}

# --- the repository -----------------------------------------------------------------------------------
expect_exit 0 "T-BLD-014j pr.yml has the traceability job (GitHub-hosted, timeout, pinned actions, command, 90-day artifact, no result download)" \
  check_job "$pr"
expect_exit 0 "T-BLD-014j pr.yml runs on pull_request without pull_request_target, read-only top-level permissions" \
  check_workflow "$pr"
expect_exit 0 "T-BLD-014j branch-protection.sh requires the traceability check" check_required "$bp"
expect_exit 0 "T-BLD-014j the lint job records verify-release.test.sh results through shell-results.sh (suite deploy-test)" \
  grep -qE '^          bash test/traceability/shell-results\.sh deploy-test deploy/test/verify-release\.test\.sh$' "$pr"

# --- mutated copies: each assertion bites ---------------------------------------------------------------
m="$work/m"
mkdir -p "$m"
mutate() { # mutate <name> <sed program>
  sed -e "$2" "$pr" >"$m/$1.yml"
  if cmp -s "$pr" "$m/$1.yml"; then
    echo "mutation $1 changed nothing" >&2
    return 1
  fi
}
mutate no-job 's/^  traceability:$/  traceability-old:/'
mutate self-hosted '/^  traceability:$/,/^  agent-dist-checks:$/s/runs-on: ubuntu-latest/runs-on: [self-hosted, argos-bench]/'
mutate no-timeout '/^  traceability:$/,/^  agent-dist-checks:$/{/timeout-minutes:/d;}'
mutate no-gate1 '/^  traceability:$/,/^  agent-dist-checks:$/s/ gate1Report//'
mutate no-check '/^  traceability:$/,/^  agent-dist-checks:$/s/ checkTraceability//'
mutate excluded '/^  traceability:$/,/^  agent-dist-checks:$/s/-Pargos.ui.skip=true/-Pargos.ui.skip=true -x checkTraceability/'
mutate retention '/^  traceability:$/,/^  agent-dist-checks:$/s/retention-days: 90/retention-days: 14/'
mutate no-always '/^  traceability:$/,/^  agent-dist-checks:$/{/^        if: always()$/d;}'
mutate no-gate1-path '/^  traceability:$/,/^  agent-dist-checks:$/{/build\/reports\/gate1.md$/d;}'
mutate download '/^  traceability:$/,/^  agent-dist-checks:$/s|^      - name: Catalogue and mapping check|      - name: Fetch results\n        uses: actions/download-artifact@0123456789abcdef0123456789abcdef01234567 # v5.0.0\n      - name: Catalogue and mapping check|'
mutate unpinned '/^  traceability:$/,/^  agent-dist-checks:$/s|actions/upload-artifact@[0-9a-f]*|actions/upload-artifact@v7|'
mutate persist '/^  traceability:$/,/^  agent-dist-checks:$/{/persist-credentials: false/d;}'
mutate job-write '/^  traceability:$/,/^  agent-dist-checks:$/s/^    timeout-minutes: 15$/    timeout-minutes: 15\n    permissions:\n      contents: write/'
mutate prt 's/^  pull_request:$/  pull_request_target:/'
mutate top-write '/^permissions:$/,/^concurrency:$/s/contents: read/contents: write/'

expect_exit 1 "T-BLD-014j a missing traceability job is caught" check_job "$m/no-job.yml"
expect_exit 1 "T-BLD-014j a self-hosted runner is caught" check_job "$m/self-hosted.yml"
expect_exit 1 "T-BLD-014j a job without timeout-minutes is caught" check_job "$m/no-timeout.yml"
expect_exit 1 "T-BLD-014j a command without gate1Report is caught" check_job "$m/no-gate1.yml"
expect_exit 1 "T-BLD-014j a command without checkTraceability is caught" check_job "$m/no-check.yml"
expect_exit 1 "T-BLD-014j a command that excludes the check is caught" check_job "$m/excluded.yml"
expect_exit 1 "T-BLD-014j an artifact kept 14 days is caught" check_job "$m/retention.yml"
expect_exit 1 "T-BLD-014j an upload that skips failed checks is caught" check_job "$m/no-always.yml"
expect_exit 1 "T-BLD-014j an artifact without gate1.md is caught" check_job "$m/no-gate1-path.yml"
expect_exit 1 "T-BLD-014j downloading test results is caught" check_job "$m/download.yml"
expect_exit 1 "T-BLD-014j an action pinned by tag is caught" check_job "$m/unpinned.yml"
expect_exit 1 "T-BLD-014j a checkout that persists credentials is caught" check_job "$m/persist.yml"
expect_exit 1 "T-BLD-014j a job-level write permission is caught" check_job "$m/job-write.yml"
expect_exit 1 "T-BLD-014j pull_request_target is caught" check_workflow "$m/prt.yml"
expect_exit 1 "T-BLD-014j a top-level write permission is caught" check_workflow "$m/top-write.yml"

sed -e '/^  "traceability"$/d' "$bp" >"$work/bp-mutated.sh"
expect_exit 1 "T-BLD-014j branch protection without the traceability check is caught" check_required "$work/bp-mutated.sh"

finish
