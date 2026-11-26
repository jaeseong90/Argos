#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: SEC-71, SEC-73, O-S04, O-Q02
#
# Structure tests of the supply-chain scan workflows (WO-47; SEC-71, SEC-73, O-S04, docs/16 section
# 10.2 and 10.5, docs/19 section 6.1). No Docker, no network, no GitHub API: each check reads the
# workflow files line by line, runs on the repository files (must pass) and on mutated copies
# (must fail, so every assertion is shown to bite).
#
#   T-BLD-028 (proposed)  codeql.yml: java-kotlin and javascript-typescript, pull_request + push
#                         (main, release/**) + weekly schedule, security-events: write only in the
#                         analyze job, config file with security-extended whose paths-ignore lists
#                         only existing fixture, seed or generated directories
#   T-SEC-73b (proposed)  secrets-scan.yml: schedule and workflow_dispatch run an unranged
#                         `run-gitleaks.sh git` (full history, fetch-depth 0), a pull request scans
#                         its commit range and the checked-out tree
#   T-SEC-71c (proposed)  sbom-rescan.yml: schedule + workflow_dispatch only, SBOM gate with
#                         --review-dates fail, image scans of the server and both demo images, latest
#                         release SBOMs, report job on failure of main only (C-253 (3)) with
#                         issues: write and the kind/security label, no checkout in the report job
#   T-SEC-71d (proposed)  pr.yml sbom-scan: the server image is built and scanned with
#                         trivy-scan.sh --image after the SBOM gate
#
#   bash .github/scripts/test/supply-chain-workflows.test.sh
set -eu

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"

work="$(mktemp -d "${TMPDIR:-/tmp}/argos-supply-chain-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# --- line-based readers (block-form YAML as the repository writes it) -------------------------
# Comment lines are dropped everywhere, so a commented-out command never satisfies a check.

# triggers <file>: the keys of the top-level `on:` block.
triggers() {
  awk '/^on:/ { on = 1; next }
    on && /^[^ \t#]/ { on = 0 }
    on && /^  [A-Za-z_]+:/ { t = $0; sub(/^  /, "", t); sub(/:.*/, "", t); print t }' "$1"
}
# on_block <file> <trigger>: the lines under one trigger of the `on:` block.
on_block() {
  awk -v want="$2" '/^on:/ { on = 1; next }
    on && /^[^ \t#]/ { on = 0 }
    on && /^  [A-Za-z_]+:/ { t = $0; sub(/^  /, "", t); sub(/:.*/, "", t); cur = t; next }
    on && cur == want && $0 !~ /^[ \t]*#/ { print }' "$1"
}
# top_permissions <file>: the lines of the top-level `permissions:` block (or its inline value).
top_permissions() {
  awk '/^permissions:/ { p = 1; v = $0; sub(/^permissions:[ \t]*/, "", v); if (v != "") print v; next }
    p && /^[^ \t#]/ { p = 0 }
    p && $0 !~ /^[ \t]*#/ { print }' "$1"
}
# job_block <file> <job>: the lines of one job (jobs at two spaces of indentation).
job_block() {
  awk -v job="$2" '/^jobs:/ { in_jobs = 1; next }
    in_jobs && /^[^ \t#]/ { in_jobs = 0 }
    in_jobs && /^  [A-Za-z0-9_-]+:/ { cur = substr($0, 3); sub(/:.*/, "", cur); in_job = (cur == job) }
    in_jobs && in_job && $0 !~ /^[ \t]*#/ { print }' "$1"
}
# steps_flat <file> <job>: one line per step of the job, its lines joined with spaces.
steps_flat() {
  job_block "$1" "$2" | awk '/^      - / { if (s != "") print s; s = $0; sub(/^ +/, "", s); on = 1; next }
    /^    [^ ]/ { if (s != "") print s; s = ""; on = 0; next }
    on { t = $0; sub(/^ +/, "", t); s = s " " t }
    END { if (s != "") print s }'
}
# job_keys <file> <job>: the job-level lines (four spaces of indentation) and their nested lines
# outside the steps list.
job_keys() {
  job_block "$1" "$2" | awk '/^    [^ ]/ { in_steps = ($0 ~ /^    steps:/) }
    !in_steps { print }'
}
has_line() { grep -E -- "$1" >/dev/null; }
tab=$'\t'
count_lines() { grep -Ec -- "$1" || true; }

problems=0
problem() {
  echo "  - $*"
  problems=$((problems + 1))
}

# --- T-BLD-028: CodeQL ---------------------------------------------------------------------------
# check_codeql <workflow> <config> <repository root>
check_codeql() {
  local wf=$1 cfg=$2 base=$3 trig entries entry
  problems=0
  trig="$(triggers "$wf")"
  for t in pull_request push schedule; do
    printf '%s\n' "$trig" | has_line "^${t}\$" || problem "codeql: trigger '$t' missing"
  done
  on_block "$wf" push | has_line '^ +- +"?main"?$' || problem "codeql: push must cover main"
  on_block "$wf" push | has_line "^ +- +\"?release/\\*\\*\"?\$" || problem "codeql: push must cover release/**"
  on_block "$wf" push | has_line '(branches-ignore|paths|paths-ignore):' && problem "codeql: push must not be narrowed by paths or branches-ignore"
  on_block "$wf" pull_request | has_line '(branches|branches-ignore|paths|paths-ignore):' && problem "codeql: every pull request must be analysed"
  on_block "$wf" schedule | has_line '^ +- +cron: ' || problem "codeql: weekly schedule (cron) missing"
  job_block "$wf" analyze | has_line '^ +- +language: java-kotlin$' || problem "codeql: language java-kotlin missing"
  job_block "$wf" analyze | has_line '^ +- +language: javascript-typescript$' || problem "codeql: language javascript-typescript missing"
  job_block "$wf" analyze | has_line 'runs-on: ubuntu-latest$' || problem "codeql: analyze must run on ubuntu-latest"
  top_permissions "$wf" | has_line 'contents: read' || problem "codeql: top-level permissions must be contents: read"
  top_permissions "$wf" | has_line 'write' && problem "codeql: top-level permissions must be read-only"
  [[ "$(count_lines 'security-events: write' <"$wf")" -eq 1 ]] || problem "codeql: security-events: write must appear exactly once"
  job_keys "$wf" analyze | has_line '^      security-events: write$' || problem "codeql: security-events: write belongs to the analyze job permissions"
  steps_flat "$wf" analyze | grep -F 'github/codeql-action/init@' | has_line 'config-file: \./\.github/codeql/codeql-config\.yml' ||
    problem "codeql: init must use config-file ./.github/codeql/codeql-config.yml"
  steps_flat "$wf" analyze | has_line 'github/codeql-action/analyze@' || problem "codeql: analyze step missing"

  if [[ ! -f "$cfg" ]]; then
    problem "codeql: config file missing: $cfg"
  else
    grep -v '^[ \t]*#' "$cfg" | has_line '^ +- +uses: +security-extended$' || problem "codeql config: queries must use security-extended"
    grep -v '^[ \t]*#' "$cfg" | has_line '^paths:' && problem "codeql config: 'paths:' would restrict the analysis; use paths-ignore for fixtures only"
    grep -v '^[ \t]*#' "$cfg" | has_line '^disable-default-queries:[ \t]*true' && problem "codeql config: the default queries must stay on"
    entries="$(awk '/^paths-ignore:/ { p = 1; next } p && /^[^ \t#]/ { p = 0 }
      p && /^[ \t]*- / { e = $0; sub(/^[ \t]*- +/, "", e); sub(/[ \t]+#.*$/, "", e); gsub(/["'\'']/, "", e); print e }' "$cfg")"
    while IFS= read -r entry; do
      [[ -n "$entry" ]] || continue
      if [[ "$entry" == *"*"* || "$entry" == *".."* || "$entry" == /* ]]; then
        problem "codeql config: paths-ignore entry must be a plain directory, no glob: $entry"
      elif [[ ! "$entry" =~ (^|/)(archFixtures|fixture|generated|seed)$ ]]; then
        problem "codeql config: paths-ignore may list fixture, seed or generated directories only: $entry"
      elif [[ ! -d "$base/$entry" ]]; then
        problem "codeql config: paths-ignore entry does not exist: $entry"
      fi
    done <<<"$entries"
  fi
  ((problems == 0))
}

# --- T-SEC-73b: gitleaks ------------------------------------------------------------------------
# gitleaks_branches <file>: "<case label>\t<command>" for each run-gitleaks.sh git line of the
# `case "$EVENT_NAME"` in the gitleaks job.
gitleaks_branches() {
  job_block "$1" gitleaks | awk '{ t = $0; sub(/^[ \t]+/, "", t); sub(/[ \t]+$/, "", t) }
    t ~ /^[A-Za-z_*|]+\)$/ { br = substr(t, 1, length(t) - 1); next }
    t == ";;" { br = ""; next }
    br != "" && t ~ /run-gitleaks\.sh git/ { print br "\t" t }'
}
check_secrets() {
  local wf=$1 trig branches full
  problems=0
  trig="$(triggers "$wf")"
  for t in pull_request push schedule workflow_dispatch; do
    printf '%s\n' "$trig" | has_line "^${t}\$" || problem "secrets-scan: trigger '$t' missing"
  done
  on_block "$wf" schedule | has_line '^ +- +cron: ' || problem "secrets-scan: weekly schedule (cron) missing"
  steps_flat "$wf" gitleaks | grep -F 'actions/checkout@' | has_line 'fetch-depth: 0( |$)' ||
    problem "secrets-scan: checkout needs fetch-depth: 0 (full history)"
  job_block "$wf" gitleaks | has_line 'runs-on: ubuntu-latest$' || problem "secrets-scan: gitleaks must run on ubuntu-latest"
  branches="$(gitleaks_branches "$wf")"
  full='bash \.github/scripts/run-gitleaks\.sh git$'
  # Schedule and manual runs take the catch-all branch, or a branch that names them.
  printf '%s\n' "$branches" | has_line "^\\*${tab}${full}" || problem "secrets-scan: the '*)' branch (schedule, workflow_dispatch) must run an unranged 'run-gitleaks.sh git'"
  if printf '%s\n' "$branches" | awk -F'\t' '$1 == "*" || $1 ~ /schedule|workflow_dispatch/' | grep -Ev "${tab}${full}" | grep -q .; then
    problem "secrets-scan: schedule and workflow_dispatch must scan the full history (no commit range)"
  fi
  # shellcheck disable=SC2016 # literal ${PR_BASE_SHA} in the workflow text
  printf '%s\n' "$branches" | grep -F "pull_request${tab}" | grep -Fq 'run-gitleaks.sh git "${PR_BASE_SHA}..${PR_HEAD_SHA}"' ||
    problem "secrets-scan: a pull request must scan its commit range"
  steps_flat "$wf" gitleaks | grep -F 'run-gitleaks.sh dir' | has_line "if: .*github\\.event_name == 'pull_request'" ||
    problem "secrets-scan: a pull request must also scan the checked-out tree (run-gitleaks.sh dir)"
  ((problems == 0))
}

# --- T-SEC-71c: weekly rescan -------------------------------------------------------------------
check_rescan() {
  local wf=$1 trig steps
  problems=0
  if [[ ! -f "$wf" ]]; then
    problem "sbom-rescan: workflow missing: $wf"
    return 1
  fi
  trig="$(triggers "$wf" | LC_ALL=C sort | tr '\n' ' ')"
  [[ "$trig" == "schedule workflow_dispatch " ]] || problem "sbom-rescan: triggers must be exactly schedule and workflow_dispatch (found: $trig)"
  on_block "$wf" schedule | has_line '^ +- +cron: ' || problem "sbom-rescan: weekly schedule (cron) missing"
  top_permissions "$wf" | has_line 'contents: read' || problem "sbom-rescan: top-level permissions must be contents: read"
  top_permissions "$wf" | has_line 'write' && problem "sbom-rescan: top-level permissions must be read-only"
  grep -v '^[ \t]*#' "$wf" | grep -E 'runs-on:' | grep -Evq 'runs-on: ubuntu-latest$' && problem "sbom-rescan: every job runs on ubuntu-latest"
  grep -v '^[ \t]*#' "$wf" | has_line 'self-hosted' && problem "sbom-rescan: no self-hosted runner"

  steps="$(steps_flat "$wf" rescan)"
  printf '%s\n' "$steps" | grep -F 'test/release/check-sbom.sh' | grep -F -- '--scanner "bash test/release/trivy-scan.sh"' |
    grep -F -- '--require agent,server,ui' | grep -Fq -- '--review-dates fail' ||
    problem "sbom-rescan: rescan must run check-sbom.sh --scanner \"bash test/release/trivy-scan.sh\" --require agent,server,ui --review-dates fail"
  for task in :server:jibDockerBuild :sample-apps:demo-app:jibDockerBuild :sample-apps:load:jibDockerBuild; do
    printf '%s\n' "$steps" | grep -Fq -- "$task" || problem "sbom-rescan: image build $task missing"
  done
  for image in argos-server argos-demo-app argos-demo-load; do
    printf '%s\n' "$steps" | grep -F 'test/release/trivy-scan.sh --image' | grep -E "(^|[ \"])${image}([ ;\":]|\$)" >/dev/null ||
      problem "sbom-rescan: image scan of $image missing"
  done
  printf '%s\n' "$steps" | grep -F 'gh release download' | grep -F -- "--pattern '*.cdx.json'" | grep -Fq 'test/release/check-sbom.sh' ||
    problem "sbom-rescan: the SBOM assets of the latest release must be downloaded and scanned"
  printf '%s\n' "$steps" | grep -F 'gh release list' | grep -Fq -- '--exclude-drafts' ||
    problem "sbom-rescan: the latest release must be a published (non-draft) one"

  job_keys "$wf" report | has_line '^    needs: +\[?rescan\]?$' || problem "sbom-rescan: report must need rescan"
  # ADR-0010 C-253 (3): an issue is opened or commented only for a failed rescan of main.
  job_keys "$wf" report | has_line "^    if: +(\\$\{\{ *)?failure\(\) && github\.ref == 'refs/heads/main'( *\}\})?\$" ||
    problem "sbom-rescan: report runs only on a failed rescan of main (if: failure() && github.ref == 'refs/heads/main')"
  job_keys "$wf" report | has_line '^      issues: write$' || problem "sbom-rescan: report needs job permission issues: write"
  [[ "$(grep -v '^[ \t]*#' "$wf" | count_lines ': write')" -eq 1 ]] || problem "sbom-rescan: issues: write in the report job is the only write permission"
  job_block "$wf" report | has_line 'actions/checkout@' && problem "sbom-rescan: report must not check out code"
  # Line by line here: the label must be on the gh command itself.
  steps="$(job_block "$wf" report)"
  printf '%s\n' "$steps" | grep -F 'gh issue create' | grep -Fq -- '--label kind/security' ||
    problem "sbom-rescan: report must create the issue with the kind/security label"
  printf '%s\n' "$steps" | grep -F 'gh issue list' | grep -F -- '--label kind/security' | grep -Fq -- '--state open' ||
    problem "sbom-rescan: report must look for an open kind/security issue first"
  printf '%s\n' "$steps" | grep -Fq 'gh issue comment' || problem "sbom-rescan: report must comment on an existing issue"
  ((problems == 0))
}

# --- T-SEC-71d: server image scan of the pull request sbom-scan job --------------------------------
check_pr_image_scan() {
  local wf=$1 steps sbom_at jib_at scan_at
  problems=0
  steps="$(steps_flat "$wf" sbom-scan)"
  job_block "$wf" sbom-scan | has_line 'runs-on: ubuntu-latest$' || problem "pr.yml sbom-scan: runs on ubuntu-latest"
  sbom_at="$(printf '%s\n' "$steps" | grep -nF 'test/release/check-sbom.sh' | head -n 1 | cut -d: -f1)"
  jib_at="$(printf '%s\n' "$steps" | grep -nF './gradlew :server:jibDockerBuild' | head -n 1 | cut -d: -f1)"
  scan_at="$(printf '%s\n' "$steps" | grep -nE 'test/release/trivy-scan\.sh --image "?argos-server:[^ ]+"? \.trivyignore' | head -n 1 | cut -d: -f1)"
  [[ -n "$sbom_at" ]] || problem "pr.yml sbom-scan: SBOM gate (check-sbom.sh) missing"
  [[ -n "$jib_at" ]] || problem "pr.yml sbom-scan: ./gradlew :server:jibDockerBuild missing"
  [[ -n "$scan_at" ]] || problem "pr.yml sbom-scan: trivy-scan.sh --image argos-server:<version> .trivyignore missing"
  if [[ -n "$sbom_at" && -n "$jib_at" && -n "$scan_at" ]]; then
    ((sbom_at < scan_at && jib_at < scan_at)) || problem "pr.yml sbom-scan: the image is built and scanned after the SBOM gate"
  fi
  printf '%s\n' "$steps" | grep -F 'trivy-scan.sh --image' | grep -Fq 'if: ' &&
    problem "pr.yml sbom-scan: the image scan must not be conditional"
  ((problems == 0))
}

# mutate <source> <target> <sed expression>...: a mutated copy that must differ from the source.
mutate() {
  local src=$1 dst=$2
  shift 2
  local args=() e
  for e in "$@"; do args+=(-e "$e"); done
  sed "${args[@]}" "$src" >"$dst"
  if cmp -s "$src" "$dst"; then
    echo "mutation did not change $(basename "$src"): $*" >&2
    TESTS_RUN=$((TESTS_RUN + 1))
    TESTS_FAILED=$((TESTS_FAILED + 1))
  fi
}

wf="$root/.github/workflows"
codeql="$wf/codeql.yml"
codeql_cfg="$root/.github/codeql/codeql-config.yml"
secrets="$wf/secrets-scan.yml"
rescan="$wf/sbom-rescan.yml"
pr="$wf/pr.yml"

# --- repository files pass -------------------------------------------------------------------------
expect_exit 0 "T-BLD-028 codeql.yml and codeql-config.yml" check_codeql "$codeql" "$codeql_cfg" "$root"
expect_exit 0 "T-SEC-73b secrets-scan.yml: weekly and manual runs scan the full history" check_secrets "$secrets"
expect_exit 0 "T-SEC-71c sbom-rescan.yml: weekly rescan, review dates, images, kind/security issue" check_rescan "$rescan"
expect_exit 0 "T-SEC-71d pr.yml sbom-scan scans the server image" check_pr_image_scan "$pr"

# --- CodeQL mutations --------------------------------------------------------------------------
m="$work/codeql"
mkdir -p "$m"
mutate "$codeql" "$m/no-ts.yml" '/language: javascript-typescript/d'
expect_exit 1 "codeql: dropping javascript-typescript is caught" check_codeql "$m/no-ts.yml" "$codeql_cfg" "$root"
mutate "$codeql" "$m/no-java.yml" 's/language: java-kotlin/language: python/'
expect_exit 1 "codeql: replacing java-kotlin is caught" check_codeql "$m/no-java.yml" "$codeql_cfg" "$root"
mutate "$codeql" "$m/no-schedule.yml" '/^  schedule:/d' '/cron:/d'
expect_exit 1 "codeql: no weekly schedule is caught" check_codeql "$m/no-schedule.yml" "$codeql_cfg" "$root"
mutate "$codeql" "$m/no-pr.yml" '/^  pull_request:/d'
expect_exit 1 "codeql: no pull_request trigger is caught" check_codeql "$m/no-pr.yml" "$codeql_cfg" "$root"
mutate "$codeql" "$m/pr-paths.yml" 's/^  pull_request:$/  pull_request:\n    paths:\n      - "server\/**"/'
expect_exit 1 "codeql: pull requests narrowed by paths is caught" check_codeql "$m/pr-paths.yml" "$codeql_cfg" "$root"
mutate "$codeql" "$m/no-release.yml" '/"release\/\*\*"/d'
expect_exit 1 "codeql: push without release/** is caught" check_codeql "$m/no-release.yml" "$codeql_cfg" "$root"
mutate "$codeql" "$m/top-write.yml" 's/^  contents: read$/  contents: read\n  security-events: write/'
expect_exit 1 "codeql: security-events: write at the top level is caught" check_codeql "$m/top-write.yml" "$codeql_cfg" "$root"
mutate "$codeql" "$m/no-config.yml" '/config-file:/d'
expect_exit 1 "codeql: init without the config file is caught" check_codeql "$m/no-config.yml" "$codeql_cfg" "$root"
mutate "$codeql" "$m/commented-config.yml" 's/^          config-file:/          # config-file:/'
expect_exit 1 "codeql: a commented-out config file is caught" check_codeql "$m/commented-config.yml" "$codeql_cfg" "$root"
mutate "$codeql_cfg" "$m/cfg-main.yml" 's#^  - ui/src/api/generated$#  - ui/src/api/generated\n  - server/src/main#'
expect_exit 1 "codeql config: excluding production code (server/src/main) is caught" check_codeql "$codeql" "$m/cfg-main.yml" "$root"
expect_output "paths-ignore may list fixture, seed or generated directories only: server/src/main" \
  "the excluded production path is named" check_codeql "$codeql" "$m/cfg-main.yml" "$root"
mutate "$codeql_cfg" "$m/cfg-glob.yml" 's#^  - ui/src/api/generated$#  - "**/generated"#'
expect_exit 1 "codeql config: a glob in paths-ignore is caught" check_codeql "$codeql" "$m/cfg-glob.yml" "$root"
mutate "$codeql_cfg" "$m/cfg-missing.yml" 's#^  - ui/src/api/generated$#  - ui/src/api/no-such/generated#'
expect_exit 1 "codeql config: a paths-ignore entry that does not exist is caught" check_codeql "$codeql" "$m/cfg-missing.yml" "$root"
mutate "$codeql_cfg" "$m/cfg-paths.yml" 's/^queries:$/paths:\n  - server\nqueries:/'
expect_exit 1 "codeql config: a 'paths:' allow list is caught" check_codeql "$codeql" "$m/cfg-paths.yml" "$root"
mutate "$codeql_cfg" "$m/cfg-noext.yml" 's/uses: security-extended/uses: security-and-quality-off/'
expect_exit 1 "codeql config: without security-extended is caught" check_codeql "$codeql" "$m/cfg-noext.yml" "$root"
expect_exit 1 "codeql config: a missing config file is caught" check_codeql "$codeql" "$m/none.yml" "$root"

# --- secrets-scan mutations ----------------------------------------------------------------------
m="$work/secrets"
mkdir -p "$m"
mutate "$secrets" "$m/ranged-weekly.yml" '/^            \*)$/,/;;/s#run-gitleaks.sh git$#run-gitleaks.sh git "HEAD~50..HEAD"#'
expect_exit 1 "secrets-scan: a ranged scan for schedule/dispatch is caught" check_secrets "$m/ranged-weekly.yml"
mutate "$secrets" "$m/schedule-branch.yml" 's/^            \*)$/            schedule)\n              bash .github\/scripts\/run-gitleaks.sh git "HEAD~50..HEAD"\n              ;;\n            *)/'
expect_exit 1 "secrets-scan: an explicit ranged schedule branch is caught" check_secrets "$m/schedule-branch.yml"
mutate "$secrets" "$m/no-schedule.yml" '/^  schedule:/d' '/cron:/d'
expect_exit 1 "secrets-scan: no weekly schedule is caught" check_secrets "$m/no-schedule.yml"
mutate "$secrets" "$m/no-dispatch.yml" '/^  workflow_dispatch:/d'
expect_exit 1 "secrets-scan: no workflow_dispatch is caught" check_secrets "$m/no-dispatch.yml"
mutate "$secrets" "$m/shallow.yml" '/fetch-depth: 0/d'
expect_exit 1 "secrets-scan: a shallow checkout is caught" check_secrets "$m/shallow.yml"
# shellcheck disable=SC2016 # literal ${PR_BASE_SHA} in the sed program
mutate "$secrets" "$m/no-pr-range.yml" 's#run-gitleaks.sh git "${PR_BASE_SHA}..${PR_HEAD_SHA}"#run-gitleaks.sh dir#'
expect_exit 1 "secrets-scan: a pull request without its commit range is caught" check_secrets "$m/no-pr-range.yml"
mutate "$secrets" "$m/no-dir.yml" "s/github.event_name == 'pull_request' || //"
expect_exit 1 "secrets-scan: no tree scan on pull requests is caught" check_secrets "$m/no-dir.yml"

# --- sbom-rescan mutations -----------------------------------------------------------------------
m="$work/rescan"
mkdir -p "$m"
mutate "$rescan" "$m/no-review.yml" 's/ --review-dates fail//'
expect_exit 1 "sbom-rescan: without --review-dates fail is caught" check_rescan "$m/no-review.yml"
mutate "$rescan" "$m/review-warn.yml" 's/--review-dates fail/--review-dates warn/'
expect_exit 1 "sbom-rescan: --review-dates warn is caught" check_rescan "$m/review-warn.yml"
mutate "$rescan" "$m/pr-trigger.yml" 's/^  workflow_dispatch:$/  workflow_dispatch:\n  pull_request:/'
expect_exit 1 "sbom-rescan: a pull_request trigger is caught" check_rescan "$m/pr-trigger.yml"
mutate "$rescan" "$m/no-schedule.yml" '/^  schedule:/d' '/cron:/d'
expect_exit 1 "sbom-rescan: no schedule is caught" check_rescan "$m/no-schedule.yml"
mutate "$rescan" "$m/no-load.yml" 's/ argos-demo-load; do/; do/'
expect_exit 1 "sbom-rescan: a demo image left out of the scan is caught" check_rescan "$m/no-load.yml"
mutate "$rescan" "$m/no-demo-build.yml" 's/ :sample-apps:demo-app:jibDockerBuild//'
expect_exit 1 "sbom-rescan: a demo image left out of the build is caught" check_rescan "$m/no-demo-build.yml"
mutate "$rescan" "$m/no-release.yml" '/gh release download/d'
expect_exit 1 "sbom-rescan: no release SBOM rescan is caught" check_rescan "$m/no-release.yml"
# shellcheck disable=SC2016 # literal ${{ }} expressions in the sed program
mutate "$rescan" "$m/always.yml" 's/^    if: \${{ failure() \&\& github.ref == .refs\/heads\/main. }}$/    if: ${{ always() \&\& github.ref == '"'"'refs\/heads\/main'"'"' }}/'
expect_exit 1 "sbom-rescan: a report that runs always is caught" check_rescan "$m/always.yml"
# shellcheck disable=SC2016 # literal ${{ }} expressions in the sed program
mutate "$rescan" "$m/any-ref.yml" 's/^    if: \${{ failure() \&\& github.ref == .refs\/heads\/main. }}$/    if: ${{ failure() }}/'
expect_exit 1 "sbom-rescan: a report on any branch (no main ref condition) is caught" check_rescan "$m/any-ref.yml"
# shellcheck disable=SC2016 # literal ${{ }} expressions in the sed program
mutate "$rescan" "$m/other-ref.yml" 's/refs\/heads\/main. }}$/refs\/heads\/feature-x'"'"' }}/'
expect_exit 1 "sbom-rescan: a report on another branch than main is caught" check_rescan "$m/other-ref.yml"
# shellcheck disable=SC2016 # literal $title in the sed program
mutate "$rescan" "$m/no-label.yml" 's/--title "$title" --label kind\/security/--title "$title"/'
expect_exit 1 "sbom-rescan: an issue without kind/security is caught" check_rescan "$m/no-label.yml"
mutate "$rescan" "$m/checkout.yml" 's/^      - name: Open or update the kind\/security issue$/      - name: Check out\n        uses: actions\/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1\n      - name: Open or update the kind\/security issue/'
expect_exit 1 "sbom-rescan: a checkout in the report job is caught" check_rescan "$m/checkout.yml"
mutate "$rescan" "$m/rescan-write.yml" 's/^    timeout-minutes: 75$/    timeout-minutes: 75\n    permissions:\n      contents: write/'
expect_exit 1 "sbom-rescan: a write permission in the rescan job is caught" check_rescan "$m/rescan-write.yml"
mutate "$rescan" "$m/self-hosted.yml" '0,/runs-on: ubuntu-latest/s/runs-on: ubuntu-latest/runs-on: [self-hosted, argos-matrix]/'
expect_exit 1 "sbom-rescan: a self-hosted runner is caught" check_rescan "$m/self-hosted.yml"
expect_exit 1 "sbom-rescan: a missing workflow is caught" check_rescan "$m/none.yml"

# --- pr.yml image scan mutations -------------------------------------------------------------------
m="$work/pr"
mkdir -p "$m"
mutate "$pr" "$m/no-image-scan.yml" '/trivy-scan.sh --image/d'
expect_exit 1 "pr.yml: the server image scan left out is caught" check_pr_image_scan "$m/no-image-scan.yml"
mutate "$pr" "$m/no-jib.yml" '/run: .\/gradlew :server:jibDockerBuild/d'
expect_exit 1 "pr.yml: the server image build left out is caught" check_pr_image_scan "$m/no-jib.yml"
mutate "$pr" "$m/other-image.yml" 's/--image "argos-server:/--image "argos-demo-load:/'
expect_exit 1 "pr.yml: scanning another image instead of the server is caught" check_pr_image_scan "$m/other-image.yml"
mutate "$pr" "$m/conditional.yml" 's/^      - name: Server image vulnerability scan (Critical and High, fixable, outside the exception list)$/&\n        if: github.event_name == '"'"'push'"'"'/'
expect_exit 1 "pr.yml: a conditional image scan is caught" check_pr_image_scan "$m/conditional.yml"

finish
