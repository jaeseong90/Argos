#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: O-Q01, O-Q02, SEC-71, O-S04
# test-ids: T-BLD-024
#
# Tests for .github/scripts/branch-protection.sh (test ID T-BLD-024, docs/18 appendix A). GitHub is
# never contacted: a fake `gh` placed first on PATH records every call and its JSON body and answers the
# ruleset lookup from a fixture, and GH_TOKEN / GITHUB_TOKEN are removed from the environment.
# Checks: --dry-run output and JSON bodies, create (POST) versus update (PUT), the ruleset contents
# (required checks, approvals, code owner review, linear history, no bypass actors), --approvals 0,
# --include-e2e, the repository security settings (Dependabot alerts and security updates, secret
# scanning with push protection, read-only default workflow token without touching the "create and
# approve pull requests" setting; proposed T-SEC-71b, SEC-71, SEC-73, O-S04), invalid arguments
# (exit 2), and that the required checks match the job names
# (matrix expanded) of the workflows that run on pull_request, with every job of those workflows
# either required or excluded with a reason in the script.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script="$here/../branch-protection.sh"
workflows_dir=$(cd "$here/../../workflows" && pwd)
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"

unset GH_TOKEN GITHUB_TOKEN GH_ENTERPRISE_TOKEN GITHUB_ENTERPRISE_TOKEN GH_REPO

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

command -v node >/dev/null 2>&1 || {
  echo "FAIL  node is required to parse the JSON bodies (docs/dev-setup.md prerequisites)"
  exit 1
}

# Required checks expected by docs/19 section 6.2 (pr.yml jobs, CodeQL, gitleaks).
default_checks="build-java
build-ui
lint
license
traceability
agent-dist-checks
integration
security-light
openapi-diff
changelog
dco
analyze (java-kotlin)
analyze (javascript-typescript)
gitleaks"
e2e_checks="e2e (1/4)
e2e (2/4)
e2e (3/4)
e2e (4/4)"

# ---------------------------------------------------------------------------------------------
# Fake gh: logs "$*" per call to $FAKE_GH_DIR/calls.log, stores stdin of `--input -` calls as
# body.<n>.json, and answers `api repos/<r>/rulesets --paginate --jq <filter>` from rulesets.json
# (real jq when installed, otherwise an emulation of exactly the filter the script must use).
mkdir -p "$work/bin"
cat >"$work/bin/gh" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
d=${FAKE_GH_DIR:?FAKE_GH_DIR is not set}
n=$(($(cat "$d/count" 2>/dev/null || echo 0) + 1))
echo "$n" >"$d/count"
printf '%s\n' "$*" >>"$d/calls.log"
if [[ " $* " == *" --input - "* ]]; then
  cat >"$d/body.$n.json"
fi
if [[ ${1:-} == api && ${2:-} == repos/*/rulesets && " $* " == *" --paginate "* ]]; then
  filter=""
  prev=""
  for a in "$@"; do
    [[ $prev == --jq ]] && filter=$a
    prev=$a
  done
  if command -v jq >/dev/null 2>&1; then
    jq -r "$filter" <"$d/rulesets.json"
  else
    want='.[] | select(.name == "argos-protected-branches" and .source_type == "Repository") | .id'
    [[ $filter == "$want" ]] || { echo "fake gh: unexpected --jq filter: $filter" >&2; exit 3; }
    node -e 'const r = JSON.parse(require("fs").readFileSync(0, "utf8"));
      for (const x of r) if (x.name === "argos-protected-branches" && x.source_type === "Repository") console.log(x.id);' \
      <"$d/rulesets.json"
  fi
fi
FAKE
chmod +x "$work/bin/gh"

# new_fake <name> <rulesets-json>: a fresh fake gh state directory with the lookup fixture.
new_fake() {
  mkdir -p "$work/$1"
  printf '%s\n' "$2" >"$work/$1/rulesets.json"
  : >"$work/$1/calls.log"
}

# run_fake <state> <script args...>: runs the script against the fake gh.
run_fake() {
  local state=$1
  shift
  PATH="$work/bin:$PATH" FAKE_GH_DIR="$work/$state" bash "$script" "$@"
}

# check_ruleset <approvals> <code-owner true|false> <expected checks> <json-file>
check_ruleset() {
  # shellcheck disable=SC2016 # JavaScript template literals, not shell expansions
  node -e '
const [wantApprovals, wantCodeOwner, wantChecks] = process.argv.slice(1);
const r = JSON.parse(require("fs").readFileSync(0, "utf8"));
const errs = [];
const eq = (a, b, what) => {
  if (JSON.stringify(a) !== JSON.stringify(b)) errs.push(`${what}: ${JSON.stringify(a)} != ${JSON.stringify(b)}`);
};
eq(r.name, "argos-protected-branches", "name");
eq(r.target, "branch", "target");
eq(r.enforcement, "active", "enforcement");
eq(r.bypass_actors, [], "bypass_actors (admins included)");
eq(r.conditions && r.conditions.ref_name && r.conditions.ref_name.include, ["~DEFAULT_BRANCH", "refs/heads/release/*"], "branches");
eq(r.conditions && r.conditions.ref_name && r.conditions.ref_name.exclude, [], "excluded branches");
const rules = (t) => (r.rules || []).filter((x) => x.type === t);
for (const t of ["deletion", "non_fast_forward", "required_linear_history", "pull_request", "required_status_checks"]) {
  if (rules(t).length !== 1) errs.push(`rule ${t}: ${rules(t).length} entries`);
}
const pr = (rules("pull_request")[0] || {}).parameters || {};
eq(pr.required_approving_review_count, Number(wantApprovals), "approvals");
eq(pr.require_code_owner_review, wantCodeOwner === "true", "code owner review");
eq(pr.dismiss_stale_reviews_on_push, true, "dismiss stale approvals");
eq(pr.required_review_thread_resolution, true, "conversation resolution");
if ((pr.allowed_merge_methods || []).includes("merge")) errs.push("merge commits allowed despite linear history");
const sc = ((rules("required_status_checks")[0] || {}).parameters || {}).required_status_checks || [];
eq(sc.map((c) => c.context).sort(), wantChecks.split("\n").filter(Boolean).sort(), "required checks");
for (const c of sc) if (c.integration_id !== 15368) errs.push(`${c.context}: integration_id ${c.integration_id} (GitHub Actions is 15368)`);
if (errs.length) { console.log(errs.join("\n")); process.exit(1); }
' "$1" "$2" "$3" <"$4"
}

# extract_bodies <output-file> <dir>: writes the here-document bodies of the dry-run output to
# <dir>/1.json, 2.json, ... and the command line of each to <dir>/<n>.cmd.
extract_bodies() {
  mkdir -p "$2"
  awk -v dir="$2" '
    inbody && $0 == "JSON" { inbody = 0; close(file); next }
    inbody { print > file; next }
    /<<.JSON.$/ { n++; file = dir "/" n ".json"; cmd = dir "/" n ".cmd"; print > cmd; close(cmd); inbody = 1 }
  ' "$1"
}

# ---------------------------------------------------------------------------------------------
# Dry run: prints the commands and bodies, never runs gh.
new_fake dry '[]'
dry_out="$work/dry.out"
run_fake dry --dry-run --repo example/repo >"$dry_out"
expect_exit 0 "dry run exits 0" run_fake dry --dry-run --repo example/repo
expect_exit 0 "dry run does not call gh" test ! -s "$work/dry/calls.log"
expect_output "gh api repos/example/repo/rulesets --paginate --jq" "dry run shows the ruleset lookup" cat "$dry_out"
expect_output "gh api --method POST repos/example/repo/rulesets --input - <<'JSON'" "dry run shows the ruleset create" cat "$dry_out"
expect_output "PUT repos/example/repo/rulesets/<id> when the lookup above prints an id" "dry run explains the update path" cat "$dry_out"
expect_output "gh api --method PATCH repos/example/repo --input - <<'JSON'" "dry run enables Discussions" cat "$dry_out"
expect_output "gh api --method PUT repos/example/repo/private-vulnerability-reporting" "dry run enables private vulnerability reporting" cat "$dry_out"
expect_output "gh api --method PUT repos/example/repo/actions/permissions/fork-pr-contributor-approval --input - <<'JSON'" \
  "dry run requires approval of fork pull request workflows" cat "$dry_out"
expect_output "14 required check(s), 1 approval(s) for example/repo (dry run)" "dry run summary" cat "$dry_out"

expect_output "--jq '.[] | select(.name == \"argos-protected-branches\" and .source_type == \"Repository\") | .id'" \
  "dry run quotes the jq filter as one shell word" cat "$dry_out"
# The dry-run output is a runnable shell script: run it against the fake gh.
new_fake replay '[]'
expect_exit 0 "dry run output runs as a shell script" env PATH="$work/bin:$PATH" FAKE_GH_DIR="$work/replay" bash "$dry_out"
expect_exit 0 "replayed dry run makes the same nine calls" test "$(wc -l <"$work/replay/calls.log")" -eq 9
expect_exit 0 "replayed ruleset body is valid JSON with the same contents" \
  check_ruleset 1 true "$default_checks" "$work/replay/body.2.json"

extract_bodies "$dry_out" "$work/dry-bodies"
expect_exit 0 "dry run ruleset: 1 approval, code owner review, docs/19 6.2 checks, linear history, no bypass" \
  check_ruleset 1 true "$default_checks" "$work/dry-bodies/1.json"
expect_exit 1 "the ruleset checker rejects a wrong approval count (self-test)" \
  check_ruleset 2 true "$default_checks" "$work/dry-bodies/1.json"
expect_exit 1 "the ruleset checker rejects a missing required check (self-test)" \
  check_ruleset 1 true "$default_checks"$'\n'"not-a-check" "$work/dry-bodies/1.json"
expect_exit 0 "dry run Discussions body is has_discussions=true" \
  node -e 'const b = JSON.parse(require("fs").readFileSync(0, "utf8")); process.exit(b.has_discussions === true && Object.keys(b).length === 1 ? 0 : 1)' \
  <"$work/dry-bodies/2.json"
expect_exit 0 "dry run fork approval body is all_external_contributors" \
  node -e 'const b = JSON.parse(require("fs").readFileSync(0, "utf8")); process.exit(b.approval_policy === "all_external_contributors" ? 0 : 1)' \
  <"$work/dry-bodies/3.json"

# Repository security settings (16 section 10.2 and 10.5, ADR-0003 C-30: security updates immediately).
expect_output "gh api --method PUT repos/example/repo/vulnerability-alerts" "T-SEC-71b dry run enables Dependabot alerts" \
  cat "$dry_out"
expect_output "gh api --method PUT repos/example/repo/automated-security-fixes" \
  "T-SEC-71b dry run enables Dependabot security updates" cat "$dry_out"
expect_exit 0 "Dependabot alerts are enabled before security updates (they depend on the alerts)" \
  test "$(grep -n 'vulnerability-alerts' "$dry_out" | cut -d: -f1)" -lt "$(grep -n 'automated-security-fixes' "$dry_out" | cut -d: -f1)"
expect_output "gh api --method PATCH repos/example/repo --input - <<'JSON'" "dry run patches security_and_analysis" \
  cat "$work/dry-bodies/4.cmd"
expect_exit 0 "dry run security_and_analysis body enables secret scanning and push protection only" \
  node -e 'const b = JSON.parse(require("fs").readFileSync(0, "utf8"));
const s = b.security_and_analysis || {};
const ok = Object.keys(b).length === 1 && Object.keys(s).sort().join(",") === "secret_scanning,secret_scanning_push_protection"
  && s.secret_scanning.status === "enabled" && s.secret_scanning_push_protection.status === "enabled";
process.exit(ok ? 0 : 1)' <"$work/dry-bodies/4.json"
expect_output "gh api --method PUT repos/example/repo/actions/permissions/workflow --input - <<'JSON'" \
  "dry run sets the default workflow token permission" cat "$work/dry-bodies/5.cmd"
expect_exit 0 "default workflow token is read-only; can_approve_pull_request_reviews is not sent" \
  node -e 'const b = JSON.parse(require("fs").readFileSync(0, "utf8"));
process.exit(b.default_workflow_permissions === "read" && Object.keys(b).length === 1 ? 0 : 1)' <"$work/dry-bodies/5.json"
expect_exit 1 "no dry-run call turns on Actions pull request creation or approval" \
  grep -q 'can_approve_pull_request_reviews' "$dry_out"
expect_exit 0 "dry run has exactly five JSON bodies (ruleset, Discussions, fork approval, security, token)" \
  test ! -e "$work/dry-bodies/6.json"

GH_REPO=other/name bash "$script" --dry-run >"$work/ghrepo.out"
expect_output "repos/other/name/rulesets" "GH_REPO is the default repository" cat "$work/ghrepo.out"
env -u GH_REPO bash "$script" --dry-run >"$work/default.out"
expect_output "repos/argos-apm/argos/rulesets" "argos-apm/argos without --repo or GH_REPO" cat "$work/default.out"

# ---------------------------------------------------------------------------------------------
# Fake gh: no ruleset with the name yet (an organization ruleset with the same name is ignored).
new_fake create '[
  {"id": 7, "name": "argos-protected-branches", "source_type": "Organization", "source": "argos-apm"},
  {"id": 11, "name": "tags", "source_type": "Repository", "source": "example/repo"}
]'
expect_exit 0 "creates the ruleset when none exists" run_fake create --repo example/repo
expect_output "api --method POST repos/example/repo/rulesets --input -" "POST when the ruleset is missing" cat "$work/create/calls.log"
expect_exit 1 "no PUT of a ruleset when it is missing" grep -q 'rulesets/[0-9]' "$work/create/calls.log"
post_n=$(grep -n 'method POST repos/example/repo/rulesets' "$work/create/calls.log" | cut -d: -f1)
expect_exit 0 "POST body is the full ruleset" check_ruleset 1 true "$default_checks" "$work/create/body.${post_n}.json"
expect_output "api --method PATCH repos/example/repo --input -" "Discussions enabled" cat "$work/create/calls.log"
expect_output "api --method PUT repos/example/repo/private-vulnerability-reporting" "private vulnerability reporting enabled" cat "$work/create/calls.log"
expect_output "api --method PUT repos/example/repo/actions/permissions/fork-pr-contributor-approval --input -" \
  "fork pull request workflow approval set" cat "$work/create/calls.log"
expect_output "api --method PUT repos/example/repo/vulnerability-alerts" "Dependabot alerts enabled" cat "$work/create/calls.log"
expect_output "api --method PUT repos/example/repo/automated-security-fixes" "Dependabot security updates enabled" \
  cat "$work/create/calls.log"
expect_output "api --method PUT repos/example/repo/actions/permissions/workflow --input -" \
  "default workflow token permission set" cat "$work/create/calls.log"
sa_n=$(grep -n 'method PATCH repos/example/repo --input' "$work/create/calls.log" | tail -1 | cut -d: -f1)
expect_output '"secret_scanning_push_protection": { "status": "enabled" }' "push protection body sent to gh" \
  cat "$work/create/body.${sa_n}.json"
expect_exit 0 "exactly nine gh calls (lookup, ruleset, Discussions, reporting, fork approval, alerts, security updates, security and analysis, token)" \
  test "$(wc -l <"$work/create/calls.log")" -eq 9

# Fake gh: the ruleset exists, so it is updated in place.
new_fake update '[
  {"id": 7, "name": "argos-protected-branches", "source_type": "Organization", "source": "argos-apm"},
  {"id": 4242, "name": "argos-protected-branches", "source_type": "Repository", "source": "example/repo"}
]'
expect_exit 0 "updates the existing ruleset" run_fake update --repo example/repo
expect_output "api --method PUT repos/example/repo/rulesets/4242 --input -" "PUT to the existing ruleset id" cat "$work/update/calls.log"
expect_exit 1 "no POST when the ruleset exists" grep -q 'method POST' "$work/update/calls.log"
put_n=$(grep -n 'rulesets/4242' "$work/update/calls.log" | cut -d: -f1)
expect_exit 0 "PUT body is the full ruleset" check_ruleset 1 true "$default_checks" "$work/update/body.${put_n}.json"

# Two repository rulesets with the name: refuse without writing anything.
new_fake dup '[
  {"id": 1, "name": "argos-protected-branches", "source_type": "Repository"},
  {"id": 2, "name": "argos-protected-branches", "source_type": "Repository"}
]'
expect_exit 1 "duplicate rulesets stop the script" run_fake dup --repo example/repo
expect_exit 0 "nothing written after the duplicate lookup" test "$(wc -l <"$work/dup/calls.log")" -eq 1

# --approvals 0 drops the code owner review (a sole maintainer cannot approve their own pull request).
new_fake zero '[]'
expect_exit 0 "--approvals 0 runs" run_fake zero --repo example/repo --approvals 0
zero_n=$(grep -n 'method POST' "$work/zero/calls.log" | cut -d: -f1)
expect_exit 0 "--approvals 0: no approval and no code owner review, other rules unchanged" \
  check_ruleset 0 false "$default_checks" "$work/zero/body.${zero_n}.json"
run_fake zero --dry-run --approvals 3 >"$work/three.out"
extract_bodies "$work/three.out" "$work/three-bodies"
expect_exit 0 "--approvals 3 with code owner review" check_ruleset 3 true "$default_checks" "$work/three-bodies/1.json"

# --include-e2e adds the four e2e shard checks.
run_fake dry --dry-run --include-e2e >"$work/e2e.out"
extract_bodies "$work/e2e.out" "$work/e2e-bodies"
expect_exit 0 "--include-e2e requires the four e2e shards" \
  check_ruleset 1 true "$default_checks"$'\n'"$e2e_checks" "$work/e2e-bodies/1.json"
expect_output "18 required check(s)" "--include-e2e summary" cat "$work/e2e.out"

# --include-sbom-scan adds the SBOM scan of pr.yml (docs/19 6.2), alone or with --include-e2e.
run_fake dry --dry-run --include-sbom-scan >"$work/sbom.out"
extract_bodies "$work/sbom.out" "$work/sbom-bodies"
expect_exit 0 "--include-sbom-scan requires sbom-scan" \
  check_ruleset 1 true "$default_checks"$'\n'"sbom-scan" "$work/sbom-bodies/1.json"
expect_output "15 required check(s)" "--include-sbom-scan summary" cat "$work/sbom.out"
run_fake dry --dry-run --include-e2e --include-sbom-scan >"$work/both.out"
extract_bodies "$work/both.out" "$work/both-bodies"
expect_exit 0 "--include-e2e --include-sbom-scan requires both" \
  check_ruleset 1 true "$default_checks"$'\n'"$e2e_checks"$'\n'"sbom-scan" "$work/both-bodies/1.json"
expect_output "19 required check(s)" "--include-e2e --include-sbom-scan summary" cat "$work/both.out"

# Invalid arguments: exit 2, nothing called.
new_fake bad '[]'
expect_exit 2 "--approvals 11 is rejected" run_fake bad --approvals 11
expect_exit 2 "--approvals -1 is rejected" run_fake bad --approvals -1
expect_exit 2 "--approvals x is rejected" run_fake bad --approvals x
expect_exit 2 "--approvals without a value is rejected" run_fake bad --approvals
expect_exit 2 "--repo without OWNER/NAME is rejected" run_fake bad --repo argos
expect_exit 2 "--repo without a value is rejected" run_fake bad --repo
expect_exit 2 "an invalid GH_REPO is rejected" env GH_REPO='a b/c' PATH="$work/bin:$PATH" FAKE_GH_DIR="$work/bad" bash "$script"
expect_exit 2 "unknown argument is rejected" run_fake bad --bogus
expect_exit 0 "rejected arguments call no gh" test ! -s "$work/bad/calls.log"
expect_exit 0 "--help exits 0" bash "$script" --help

# ---------------------------------------------------------------------------------------------
# Required checks versus the workflows that run on pull_request.

# pr_workflows <dir>: workflow files whose `on:` includes pull_request (not pull_request_target).
pr_workflows() {
  local f
  for f in "$1"/*.yml "$1"/*.yaml; do
    [[ -f $f ]] || continue
    if awk '
      /^on:[ \t]*pull_request[ \t]*(#.*)?$/ { found = 1 }
      /^on:[ \t]*\[/ && /[[ ,]pull_request[],]/ { found = 1 }
      /^on:[ \t]*(#.*)?$/ { inon = 1; next }
      inon && /^[^ \t#]/ { inon = 0 }
      inon && /^  pull_request:/ { found = 1 }
      END { exit found ? 0 : 1 }
    ' "$f"; then
      basename "$f"
    fi
  done
}

# workflow_jobs <file>: one line per job: <job id>\t<name or job id>\t<key=v1|v2>... (matrix values).
workflow_jobs() {
  awk '
    function unq(s) {
      sub(/[ \t]+#.*$/, "", s); gsub(/^[ \t]+|[ \t]+$/, "", s)
      if (s ~ /^".*"$/ || s ~ /^\047.*\047$/) s = substr(s, 2, length(s) - 2)
      return s
    }
    function flush(   k, line) {
      if (job == "") return
      line = job "\t" (name == "" ? job : name)
      for (k in vals) line = line "\t" k "=" vals[k]
      print line
      job = ""; name = ""; split("", vals)
    }
    /^jobs:/ { injobs = 1; next }
    injobs && /^[^ \t#]/ { flush(); injobs = 0 }
    !injobs { next }
    /^  [A-Za-z0-9_-]+:[ \t]*(#.*)?$/ { flush(); job = $0; sub(/^  /, "", job); sub(/:.*/, "", job); instrat = 0; next }
    /^    name:/ { v = $0; sub(/^    name:/, "", v); name = unq(v); next }
    /^    strategy:/ { instrat = 1; next }
    /^    [^ ]/ { instrat = 0 }
    instrat && /^ +(- )?[A-Za-z0-9_-]+:[ \t]*\[.*\][ \t]*(#.*)?$/ {
      line = $0; sub(/^ +(- )?/, "", line); k = line; sub(/:.*/, "", k)
      v = line; sub(/^[^:]*:[ \t]*\[/, "", v); sub(/\].*$/, "", v); gsub(/[ \t"\047]/, "", v); gsub(/,/, "|", v)
      vals[k] = (k in vals) ? vals[k] "|" v : v; next
    }
    instrat && /^ +- [A-Za-z0-9_-]+:[ \t]*[^ \t\[]/ {
      line = $0; sub(/^ +- /, "", line); k = line; sub(/:.*/, "", k)
      v = line; sub(/^[^:]*:/, "", v); v = unq(v)
      vals[k] = (k in vals) ? vals[k] "|" v : v; next
    }
    END { flush() }
  ' "$1"
}

# expand_names <name> <key=v1|v2>...: the check run names after matrix expansion.
expand_names() {
  local names=("$1")
  shift
  local kv key re n v out vals
  for kv in "$@"; do
    key=${kv%%=*}
    IFS='|' read -r -a vals <<<"${kv#*=}"
    re='\$\{\{ *matrix\.'"$key"' *\}\}'
    out=()
    for n in "${names[@]}"; do
      if [[ $n =~ $re ]]; then
        local m=${BASH_REMATCH[0]}
        for v in "${vals[@]}"; do out+=("${n//"$m"/$v}"); done
      else
        out+=("$n")
      fi
    done
    names=("${out[@]}")
  done
  printf '%s\n' "${names[@]}"
}

# pr_checks <dir>: <workflow>\t<job id>\t<check name> for every job of the pull_request workflows.
pr_checks() {
  local wf job name rest kvs
  while IFS= read -r wf; do
    while IFS=$'\t' read -r job name rest; do
      kvs=()
      [[ -z $rest ]] || IFS=$'\t' read -r -a kvs <<<"$rest"
      while IFS= read -r n; do
        printf '%s\t%s\t%s\n' "$wf" "$job" "$n"
      done < <(expand_names "$name" ${kvs[@]+"${kvs[@]}"})
    done < <(workflow_jobs "$1/$wf")
  done < <(pr_workflows "$1")
}

# classify <workflows dir> <script args...>: every required check is a pull_request job name, every
# such job is required or excluded with a reason, and every exclusion names an existing job.
classify() {
  local dir=$1
  shift
  local listing checks problems=0 wf job name kind what reason
  listing=$(bash "$script" --list-checks "$@")
  checks=$(pr_checks "$dir")
  # shellcheck disable=SC2016 # a literal ${{ (GitHub expression) in an unexpanded job name
  if grep -qF -- '${{' <<<"$checks"; then
    echo "unexpanded job name: $(grep -F -- '${{' <<<"$checks" | head -1)"
    problems=1
  fi
  while IFS=$'\t' read -r kind what reason; do
    case $kind in
      required)
        if ! awk -F'\t' -v c="$what" '$3 == c { f = 1 } END { exit f ? 0 : 1 }' <<<"$checks"; then
          echo "required check '$what' is not a job of a pull_request workflow"
          problems=1
        fi
        ;;
      excluded)
        [[ -n $reason ]] || { echo "exclusion $what has no reason"; problems=1; }
        if ! awk -F'\t' -v w="$what" '$1 ":" $2 == w { f = 1 } END { exit f ? 0 : 1 }' <<<"$checks"; then
          echo "excluded job $what does not exist in a pull_request workflow"
          problems=1
        fi
        ;;
    esac
  done <<<"$listing"
  while IFS=$'\t' read -r wf job name; do
    if ! grep -qxF -- "required"$'\t'"$name" <<<"$listing" &&
      ! grep -qF -- "excluded"$'\t'"$wf:$job"$'\t' <<<"$listing"; then
      echo "job $wf:$job ('$name') is neither required nor excluded with a reason in branch-protection.sh"
      problems=1
    fi
  done <<<"$checks"
  return $problems
}

prs=$(pr_workflows "$workflows_dir" | tr '\n' ' ')
for wf in pr.yml codeql.yml secrets-scan.yml; do
  expect_output "$wf" "$wf runs on pull_request" printf '%s\n' "$prs"
done
expect_exit 1 "release.yml is not a pull_request workflow" grep -qw 'release.yml' <<<"$prs"
expect_exit 1 "pr-labeler.yml (workflow_run) is not a pull_request workflow" grep -qw 'pr-labeler.yml' <<<"$prs"
expect_output $'codeql.yml\tanalyze\tanalyze (java-kotlin)' "matrix include values are expanded" pr_checks "$workflows_dir"
expect_output $'pr.yml\te2e\te2e (4/4)' "inline matrix values are expanded" pr_checks "$workflows_dir"

expect_exit 0 "required checks are real pull_request job names; every job is classified" classify "$workflows_dir"
expect_exit 0 "same with --include-e2e" classify "$workflows_dir" --include-e2e
expect_exit 0 "same with --include-e2e --include-sbom-scan (nothing excluded)" \
  classify "$workflows_dir" --include-e2e --include-sbom-scan
expect_exit 1 "nothing is excluded with both flags" \
  grep -q '^excluded' < <(bash "$script" --list-checks --include-e2e --include-sbom-scan)
expect_output $'excluded\tpr.yml:e2e\tNot yet required' "e2e is excluded with a reason by default" bash "$script" --list-checks
expect_output $'excluded\tpr.yml:sbom-scan\tNot yet required' "sbom-scan is excluded with a reason by default" \
  bash "$script" --list-checks

# Self-tests of the classification on modified copies of the workflows.
# drop_job <file> <job id>: removes the job block from a workflow file.
drop_job() {
  awk -v j="$2" '
    $0 ~ "^  " j ":" { skip = 1; next }
    skip && /^  [A-Za-z0-9_-]+:/ { skip = 0 }
    skip && /^[^ \t]/ { skip = 0 }
    !skip
  ' "$1" >"$1.tmp" && mv "$1.tmp" "$1"
}
copy_workflows() {
  mkdir -p "$work/$1"
  cp "$workflows_dir"/*.yml "$work/$1/"
}

copy_workflows wf-new-job
printf '%s\n' '' '  new-scan:' '    name: new-scan' '    runs-on: ubuntu-latest' '    steps:' '      - run: "true"' \
  >>"$work/wf-new-job/pr.yml"
expect_exit 1 "a new pr.yml job that is not classified fails" classify "$work/wf-new-job"
expect_output "job pr.yml:new-scan ('new-scan') is neither required nor excluded" "the unclassified job is named" \
  classify "$work/wf-new-job"

copy_workflows wf-release-only
drop_job "$work/wf-release-only/pr.yml" dco
printf '%s\n' '' '  dco:' '    name: dco' '    runs-on: ubuntu-latest' '    steps:' '      - run: "true"' \
  >>"$work/wf-release-only/release.yml"
expect_exit 1 "a required check that only a non-pull_request workflow (release.yml) has fails" \
  classify "$work/wf-release-only"
expect_output "required check 'dco' is not a job of a pull_request workflow" "the missing check is named" \
  classify "$work/wf-release-only"

copy_workflows wf-stale-exclusion
drop_job "$work/wf-stale-exclusion/pr.yml" e2e
expect_output "excluded job pr.yml:e2e does not exist" "a stale exclusion fails" classify "$work/wf-stale-exclusion"

copy_workflows wf-renamed-matrix
sed -i 's/javascript-typescript/javascript/' "$work/wf-renamed-matrix/codeql.yml"
expect_output "required check 'analyze (javascript-typescript)' is not a job" "a renamed matrix value fails" \
  classify "$work/wf-renamed-matrix"

finish
