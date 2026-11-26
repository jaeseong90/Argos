#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# branch-protection.sh - apply the Argos branch protection and repository settings (docs/19 sections
# 6.2, 6.6 and 8, O-Q01, O-S05) with the GitHub CLI. Run by a maintainer, never by CI.
#
# Usage: branch-protection.sh [--repo OWNER/NAME] [--approvals N] [--include-e2e] [--include-sbom-scan]
#                             [--list-checks] [--dry-run]
#
#   --repo OWNER/NAME   target repository (default: $GH_REPO, otherwise argos-apm/argos)
#   --approvals N       required approving reviews, 0..10 (default 1, docs/19 section 8). With 0 the
#                       code owner review is not required either (see GOVERNANCE.md)
#   --include-e2e       also require the four `e2e (i/4)` shard checks of pr.yml
#   --include-sbom-scan also require the `sbom-scan` check of pr.yml (docs/19 section 6.2 lists the
#                       SBOM scan as required; pr.yml adds it after its first green run)
#   --list-checks       print how every pull request job is classified (required or excluded with
#                       the reason) and exit without calling gh
#   --dry-run           print the gh commands and their JSON bodies instead of running them
#
# What it applies:
# - repository ruleset `argos-protected-branches` (updated in place when it exists, created otherwise)
#   on the default branch and refs/heads/release/*: pull request required with N approvals, code owner
#   review, stale approvals dismissed on push, conversations resolved, required status checks below
#   (reported by GitHub Actions only), linear history, no force pushes, no deletion, no bypass actors
#   (repository administrators included);
# - GitHub Discussions enabled;
# - private vulnerability reporting enabled (SECURITY.md);
# - workflow runs from fork pull requests need approval for all outside contributors (docs/19 6.6);
# - Dependabot alerts and Dependabot security updates enabled: security updates open as soon as an
#   advisory affects a dependency, independent of the monthly schedule in .github/dependabot.yml
#   (16 section 10.2, ADR-0003 C-30);
# - secret scanning and secret scanning push protection enabled (O-S04, 16 section 10.5);
# - the default GITHUB_TOKEN permission of workflows is read-only (workflows grant more per job,
#   docs/19 6.6). The setting "Allow GitHub Actions to create and approve pull requests" is left as it
#   is: turning it on is a maintainer decision (docs/security/supply-chain.md).
# Runner groups, the `trusted` environment and the Discussions categories are set by hand (GOVERNANCE.md).
# Signed commits are recommended, not required: the required `dco` check enforces Signed-off-by.
# Requires `gh auth login` as a repository administrator.
set -euo pipefail

repo=${GH_REPO:-argos-apm/argos}
dry_run=0
list_checks=0
include_e2e=0
include_sbom_scan=0
approvals=1

while [[ $# -gt 0 ]]; do
  case $1 in
    --repo)
      [[ $# -ge 2 && $2 =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || {
        echo "branch-protection: --repo needs OWNER/NAME" >&2
        exit 2
      }
      repo=$2
      shift 2
      ;;
    --approvals)
      [[ $# -ge 2 && $2 =~ ^([0-9]|10)$ ]] || {
        echo "branch-protection: --approvals needs a number from 0 to 10" >&2
        exit 2
      }
      approvals=$2
      shift 2
      ;;
    --include-e2e)
      include_e2e=1
      shift
      ;;
    --include-sbom-scan)
      include_sbom_scan=1
      shift
      ;;
    --list-checks)
      list_checks=1
      shift
      ;;
    --dry-run)
      dry_run=1
      shift
      ;;
    -h | --help)
      sed -n '4,38p' "$0"
      exit 0
      ;;
    *)
      echo "branch-protection: unknown argument '$1'" >&2
      exit 2
      ;;
  esac
done
[[ $repo =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || {
  echo "branch-protection: GH_REPO must be OWNER/NAME, got '$repo'" >&2
  exit 2
}

ruleset_name=argos-protected-branches
# GitHub Actions app id: required checks are accepted only when GitHub Actions reports them, so a
# commit status posted by another integration or token cannot satisfy them.
actions_app_id=15368

# Required status checks (docs/19 section 6.2): check run names of the jobs in the workflows that run
# on pull_request. The test .github/scripts/test/branch-protection.test.sh compares this list with the
# job names (matrix expanded) of those workflows.
required_checks=(
  # pr.yml
  "build-java"
  "build-ui"
  "lint"
  "license"
  # WO-53: catalogue and docs/18 section 16 mapping check on the clean checkout (not yet in the
  # docs/19 section 6.2 table; deterministic without test results).
  "traceability"
  "agent-dist-checks"
  "integration"
  "security-light"
  "openapi-diff"
  "changelog"
  "dco"
  # codeql.yml
  "analyze (java-kotlin)"
  "analyze (javascript-typescript)"
  # secrets-scan.yml
  "gitleaks"
)
e2e_checks=("e2e (1/4)" "e2e (2/4)" "e2e (3/4)" "e2e (4/4)")

# Jobs of pull_request workflows that are deliberately not required: workflow|job id|reason.
# Every job of such a workflow is either listed in required_checks or here; the test fails otherwise,
# so a new job forces a decision.
e2e_exclusion="pr.yml|e2e|Not yet required (pr.yml): added with --include-e2e after the first green run of all four shards"
sbom_exclusion="pr.yml|sbom-scan|Not yet required (pr.yml header, docs/19 6.2): added with --include-sbom-scan after its first green run"
excluded_jobs=()

if [[ $include_e2e -eq 1 ]]; then
  required_checks+=("${e2e_checks[@]}")
else
  excluded_jobs+=("$e2e_exclusion")
fi
if [[ $include_sbom_scan -eq 1 ]]; then
  required_checks+=("sbom-scan")
else
  excluded_jobs+=("$sbom_exclusion")
fi

if [[ $list_checks -eq 1 ]]; then
  for c in "${required_checks[@]}"; do
    printf 'required\t%s\n' "$c"
  done
  for e in ${excluded_jobs[@]+"${excluded_jobs[@]}"}; do
    IFS='|' read -r wf job reason <<<"$e"
    printf 'excluded\t%s:%s\t%s\n' "$wf" "$job" "$reason"
  done
  exit 0
fi

# json_string <text>: the text as a JSON string literal (names here have no control characters).
json_string() {
  local s=${1//\\/\\\\}
  s=${s//\"/\\\"}
  printf '"%s"' "$s"
}

if [[ $approvals -gt 0 ]]; then
  code_owner_review=true
else
  code_owner_review=false
fi

checks_json=""
for c in "${required_checks[@]}"; do
  [[ -z $checks_json ]] || checks_json+=$',\n'
  checks_json+="          { \"context\": $(json_string "$c"), \"integration_id\": ${actions_app_id} }"
done

ruleset_json=$(
  cat <<EOF
{
  "name": $(json_string "$ruleset_name"),
  "target": "branch",
  "enforcement": "active",
  "bypass_actors": [],
  "conditions": {
    "ref_name": {
      "include": ["~DEFAULT_BRANCH", "refs/heads/release/*"],
      "exclude": []
    }
  },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "required_linear_history" },
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": ${approvals},
        "require_code_owner_review": ${code_owner_review},
        "dismiss_stale_reviews_on_push": true,
        "require_last_push_approval": false,
        "required_review_thread_resolution": true,
        "allowed_merge_methods": ["squash", "rebase"]
      }
    },
    {
      "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": false,
        "do_not_enforce_on_create": false,
        "required_status_checks": [
${checks_json}
        ]
      }
    }
  ]
}
EOF
)
discussions_json='{ "has_discussions": true }'
fork_approval_json='{ "approval_policy": "all_external_contributors" }'
security_analysis_json='{
  "security_and_analysis": {
    "secret_scanning": { "status": "enabled" },
    "secret_scanning_push_protection": { "status": "enabled" }
  }
}'
# Only the default token permission; can_approve_pull_request_reviews is deliberately not sent, so the
# repository keeps its current value.
workflow_permissions_json='{ "default_workflow_permissions": "read" }'
lookup_jq=".[] | select(.name == \"${ruleset_name}\" and .source_type == \"Repository\") | .id"

# shell_word <text>: the text as one shell word (single-quoted unless it is plain).
shell_word() {
  if [[ $1 =~ ^[A-Za-z0-9_./:=@%+,-]+$ ]]; then
    printf '%s' "$1"
  else
    printf "'%s'" "${1//\'/\'\\\'\'}"
  fi
}

# show <json|-> <gh args...>: prints the command (and its JSON body as a here-document).
show() {
  local body=$1 a
  shift
  printf 'gh'
  for a in "$@"; do
    printf ' %s' "$(shell_word "$a")"
  done
  if [[ $body == - ]]; then
    printf '\n'
  else
    printf " <<'JSON'\n%s\nJSON\n" "$body"
  fi
}

# call <json|-> <gh args...>: runs gh with the JSON body on stdin (or without input), or prints it.
call() {
  local body=$1
  shift
  if [[ $dry_run -eq 1 ]]; then
    if [[ $body == - ]]; then show - "$@"; else show "$body" "$@" --input -; fi
  elif [[ $body == - ]]; then
    gh "$@" >/dev/null
  else
    gh "$@" --input - <<<"$body" >/dev/null
  fi
}

if [[ $dry_run -eq 0 ]] && ! command -v gh >/dev/null 2>&1; then
  echo "branch-protection: the GitHub CLI (gh) is required" >&2
  exit 2
fi

if [[ $dry_run -eq 1 ]]; then
  show - api "repos/${repo}/rulesets" --paginate --jq "$lookup_jq"
  echo "# ruleset ${ruleset_name}: PUT repos/${repo}/rulesets/<id> when the lookup above prints an id, otherwise:"
  call "$ruleset_json" api --method POST "repos/${repo}/rulesets"
else
  ids=$(gh api "repos/${repo}/rulesets" --paginate --jq "$lookup_jq")
  ids=$(printf '%s\n' "$ids" | sed '/^[[:space:]]*$/d')
  if [[ -z $ids ]]; then
    call "$ruleset_json" api --method POST "repos/${repo}/rulesets"
    echo "branch-protection: created ruleset ${ruleset_name}"
  elif [[ $ids =~ ^[0-9]+$ ]]; then
    call "$ruleset_json" api --method PUT "repos/${repo}/rulesets/${ids}"
    echo "branch-protection: updated ruleset ${ruleset_name} (id ${ids})"
  else
    echo "branch-protection: more than one ruleset named ${ruleset_name} (ids: ${ids//$'\n'/ }); remove the extra ones first" >&2
    exit 1
  fi
fi
call "$discussions_json" api --method PATCH "repos/${repo}"
call - api --method PUT "repos/${repo}/private-vulnerability-reporting"
call "$fork_approval_json" api --method PUT "repos/${repo}/actions/permissions/fork-pr-contributor-approval"
# Dependabot alerts first: security updates need them.
call - api --method PUT "repos/${repo}/vulnerability-alerts"
call - api --method PUT "repos/${repo}/automated-security-fixes"
call "$security_analysis_json" api --method PATCH "repos/${repo}"
call "$workflow_permissions_json" api --method PUT "repos/${repo}/actions/permissions/workflow"

# In a dry run the output stays a runnable shell script, so the summary is a comment.
echo "$([[ $dry_run -eq 1 ]] && echo '# ')branch-protection: ${#required_checks[@]} required check(s), ${approvals} approval(s) for ${repo}$([[ $dry_run -eq 1 ]] && echo ' (dry run)')"
