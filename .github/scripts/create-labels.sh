#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# create-labels.sh - create or update the Argos label set (O-G09, docs/19 section 8) with the GitHub CLI.
#
# Usage: create-labels.sh [--repo OWNER/NAME] [--backport X.Y]... [--dry-run]
#
#   --repo OWNER/NAME   target repository (default: $GH_REPO, otherwise argos-apm/argos)
#   --backport X.Y      also create the backport/X.Y label (repeatable, one per release branch)
#   --dry-run           print the gh commands instead of running them
#
# Existing labels with the same name are updated (`gh label create --force`); other labels are left
# alone. Requires `gh auth login` with permission to manage labels on the repository.
set -euo pipefail

repo=${GH_REPO:-argos-apm/argos}
dry_run=0
backports=()

while [[ $# -gt 0 ]]; do
  case $1 in
    --repo)
      [[ $# -ge 2 ]] || { echo "create-labels: --repo needs a value" >&2; exit 2; }
      repo=$2
      shift 2
      ;;
    --backport)
      [[ $# -ge 2 && $2 =~ ^[0-9]+\.[0-9]+$ ]] || { echo "create-labels: --backport needs X.Y" >&2; exit 2; }
      backports+=("$2")
      shift 2
      ;;
    --dry-run)
      dry_run=1
      shift
      ;;
    -h | --help)
      sed -n '4,14p' "$0"
      exit 0
      ;;
    *)
      echo "create-labels: unknown argument '$1'" >&2
      exit 2
      ;;
  esac
done

# name|color|description
labels=(
  "area/agent|1d76db|Java agent, instrumentation, agent packaging"
  "area/server|0e8a16|Collector server, storage, API, alerting"
  "area/ui|5319e7|Web UI"
  "area/deploy|006b75|Compose, tar, air-gap packaging, operations scripts"
  "area/docs|0075ca|Documentation"
  "kind/bug|d73a4a|Something does not work as documented"
  "kind/feature|a2eeef|New capability or improvement"
  "kind/compat-report|c5def5|Compatibility report for a library, database, server or JDK"
  "kind/security|b60205|Security hardening or vulnerability follow-up (never undisclosed details)"
  "good first issue|7057ff|Good for newcomers"
  "help wanted|008672|Extra attention is welcome"
  "needs-matrix|e99695|Core path: nightly matrix and overhead benchmark must pass on a trusted branch before merge"
  "needs-info|fbca04|Waiting for information from the reporter; closed by a maintainer after 30 days"
  "breaking-change|b60205|Breaks a public contract; requires CHANGELOG and upgrade notes"
  "no-changelog|cfd3d7|No user-visible change; the changelog check is skipped"
  "dependencies|0366d6|Dependency update (Dependabot)"
)
for v in ${backports[@]+"${backports[@]}"}; do
  labels+=("backport/${v}|fef2c0|Backport to the release/${v} branch")
done

if [[ $dry_run -eq 0 ]] && ! command -v gh >/dev/null 2>&1; then
  echo "create-labels: the GitHub CLI (gh) is required" >&2
  exit 2
fi

for entry in "${labels[@]}"; do
  IFS='|' read -r name color description <<<"$entry"
  cmd=(gh label create "$name" --repo "$repo" --color "$color" --description "$description" --force)
  if [[ $dry_run -eq 1 ]]; then
    printf '%q ' "${cmd[@]}"
    printf '\n'
  else
    "${cmd[@]}"
  fi
done
echo "create-labels: ${#labels[@]} label(s) for ${repo}$([[ $dry_run -eq 1 ]] && echo ' (dry run)')"
