#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# check-dco.sh - Developer Certificate of Origin check (O-L06).
#
# Usage: check-dco.sh <base> <head>
#
# Every commit in <base>..<head> (commits reachable from <head> but not from <base>) must contain
# a line
#
#     Signed-off-by: Full Name <email@example.com>
#
# whose name equals the commit author name and whose email equals the commit author email
# (email compared case-insensitively). The key "Signed-off-by" is matched case-insensitively,
# anywhere in the message, like the DCO GitHub App does.
#
# Merge commits: a merge whose dense combined diff (`git diff-tree --cc`) is empty only joins
# commits that are checked on their own, so it is skipped. A merge that carries content of its
# own (a conflict resolution, or any change that is in none of its parents) is checked like every
# other commit.
#
# Environment:
#   DCO_INCLUDE_MERGES=1     check every merge commit, including merges without content of their own.
#   DCO_EXEMPT_DEPENDABOT=1  exempt the commits Dependabot creates: author email
#                            49699333+dependabot[bot]@users.noreply.github.com and committer email
#                            either the same address or noreply@github.com (GitHub creates and signs
#                            Dependabot's commits with the committer "GitHub <noreply@github.com>").
#                            Every other commit in the range still needs a sign-off, including a
#                            Dependabot commit that a person rewrote (the committer is then that
#                            person). Commit metadata can be forged, so set this only for pull
#                            requests that GitHub reports as opened by Dependabot (.github/workflows/pr.yml).
#
# Exit status: 0 = every commit is signed off or exempt (or the range is empty),
#              1 = at least one commit lacks a matching sign-off,
#              2 = usage error or unknown commit.
set -euo pipefail

usage() {
  echo "usage: check-dco.sh <base> <head>" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage
base=$1
head=$2

for ref in "$base" "$head"; do
  if [[ -z "$ref" ]] || ! git rev-parse --verify --quiet "${ref}^{commit}" >/dev/null; then
    echo "check-dco: unknown commit '${ref}' (in CI, check out with fetch-depth: 0)" >&2
    exit 2
  fi
done

to_lower() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

include_merges=0
[[ "${DCO_INCLUDE_MERGES:-0}" == "1" ]] && include_merges=1
exempt_dependabot=0
[[ "${DCO_EXEMPT_DEPENDABOT:-0}" == "1" ]] && exempt_dependabot=1

dependabot_email='49699333+dependabot[bot]@users.noreply.github.com'
github_committer_email='noreply@github.com'

signoff_re='^[Ss][Ii][Gg][Nn][Ee][Dd]-[Oo][Ff][Ff]-[Bb][Yy]:[[:space:]]*(.*[^[:space:]])[[:space:]]*<([^<>]+)>[[:space:]]*$'

checked=0
failed=0
skipped_merges=0
exempted=0
while IFS= read -r line; do
  [[ -n "$line" ]] || continue
  # `git rev-list --parents` prints "<commit> <parent>...".
  read -r -a ids <<<"$line"
  commit=${ids[0]}
  short=$(git rev-parse --short "$commit")
  subject=$(git show -s --format=%s "$commit")

  kind="commit"
  if [[ ${#ids[@]} -gt 2 ]]; then
    kind="merge"
    if [[ $include_merges -eq 0 && -z "$(git diff-tree --cc --no-commit-id -r "$commit")" ]]; then
      skipped_merges=$((skipped_merges + 1))
      echo "skip    ${short} ${subject} (merge without content of its own)"
      continue
    fi
  fi

  author_name=$(git show -s --format=%an "$commit")
  author_email=$(git show -s --format=%ae "$commit")
  author_email_lc=$(to_lower "$author_email")

  if [[ $exempt_dependabot -eq 1 && "$author_email_lc" == "$dependabot_email" ]]; then
    committer_email_lc=$(to_lower "$(git show -s --format=%ce "$commit")")
    if [[ "$committer_email_lc" == "$dependabot_email" || "$committer_email_lc" == "$github_committer_email" ]]; then
      exempted=$((exempted + 1))
      echo "exempt  ${short} ${subject} (created by Dependabot)"
      continue
    fi
  fi

  checked=$((checked + 1))
  message=$(git show -s --format=%B "$commit")

  matched=0
  found=()
  while IFS= read -r msg_line; do
    msg_line=${msg_line%$'\r'}
    if [[ "$msg_line" =~ $signoff_re ]]; then
      so_name=${BASH_REMATCH[1]}
      so_email=${BASH_REMATCH[2]}
      found+=("${so_name} <${so_email}>")
      if [[ "$so_name" == "$author_name" && "$(to_lower "$so_email")" == "$author_email_lc" ]]; then
        matched=1
      fi
    fi
  done <<<"$message"

  if [[ $matched -eq 1 ]]; then
    echo "ok      ${short} ${subject}"
  else
    failed=$((failed + 1))
    if [[ $kind == merge ]]; then
      echo "MISSING ${short} ${subject} (merge with content of its own)"
    else
      echo "MISSING ${short} ${subject}"
    fi
    echo "        author:  ${author_name} <${author_email}>"
    if [[ ${#found[@]} -eq 0 ]]; then
      echo "        sign-off: none"
    else
      for so in "${found[@]}"; do
        echo "        sign-off: ${so} (does not match the author)"
      done
    fi
  fi
done < <(git rev-list --reverse --parents "${base}..${head}")

summary="${checked} checked, ${skipped_merges} merge(s) without content skipped, ${exempted} Dependabot commit(s) exempt"

if [[ $checked -eq 0 ]]; then
  echo "check-dco: no commits to check in ${base}..${head} (${summary})"
  exit 0
fi

if [[ $failed -gt 0 ]]; then
  cat >&2 <<EOF

check-dco: ${failed} of ${checked} commit(s) lack a Signed-off-by line matching the author (${summary}).
Sign off your commits to certify the Developer Certificate of Origin (https://developercertificate.org/):
  git commit --amend -s --no-edit        # last commit
  git rebase --signoff <base-branch>     # every commit on the branch
  git push --force-with-lease
A merge commit with content of its own (for example a conflict resolution) needs a sign-off too:
amend it with \`git commit --amend -s --no-edit\`, or rebase onto the base branch instead of merging.
EOF
  exit 1
fi

echo "check-dco: all ${checked} commit(s) are signed off (${summary})"
