#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Tests for .gitleaks.toml through .github/scripts/run-gitleaks.sh: a generated Argos token and a
# generated GitHub token (default rule set) must be detected; metric names and masked tokens must
# not. Fake secrets are generated at run time, so none is stored in the repository.
# Requires Docker; the test is skipped (reported) when Docker is not available, unless
# REQUIRE_DOCKER=1 (as in CI), in which case a missing Docker daemon fails the test.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script="$here/../run-gitleaks.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"

if ! docker info >/dev/null 2>&1; then
  if [[ "${REQUIRE_DOCKER:-0}" == "1" ]]; then
    echo "FAIL  Docker is required (REQUIRE_DOCKER=1) but not available"
    exit 1
  fi
  echo "SKIP  gitleaks configuration tests (Docker is not available)"
  exit 0
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

random_chars() { # random_chars <alphabet> <count>
  LC_ALL=C tr -dc "$1" </dev/urandom | head -c "$2"
}

mkdir -p "$work/clean" "$work/argos-token" "$work/github-token"

cat >"$work/clean/notes.txt" <<'EOF'
Metric names are not secrets: argos_agent_stack_capture_seconds_total argos_collector_rejected_total
Masked tokens are fine: argos_******** and argos_AbCdEfGh...
EOF
# A 43-character lowercase snake_case name has the token's length and high entropy; only the
# allowlist keeps it from being reported.
printf 'metric: argos_%s\n' "$(random_chars 'a-z0-9_' 43)" >>"$work/clean/notes.txt"
expect_exit 0 "metric names and masked tokens are not reported" bash "$script" dir "$work/clean"

printf 'server:\n  token: argos_%s\n' "$(random_chars 'A-Za-z0-9_-' 43)" >"$work/argos-token/argos-agent.yml"
expect_exit 1 "a generated Argos token is reported" bash "$script" dir "$work/argos-token"
expect_output "argos-token" "the finding names the argos-token rule" bash "$script" dir "$work/argos-token"

printf 'GITHUB_TOKEN=ghp_%s\n' "$(random_chars 'A-Za-z0-9' 36)" >"$work/github-token/ci.env"
expect_exit 1 "a generated GitHub token is reported by the default rules" bash "$script" dir "$work/github-token"

# A secret that only a merge commit adds is in no commit diff that a range scan reads, but it is in
# the checked-out tree; the tree (dir) scan of secrets-scan.yml must report it.
merge_repo="$work/merge-repo"
: >"$work/gitconfig"
(
  export GIT_CONFIG_GLOBAL="$work/gitconfig" GIT_CONFIG_NOSYSTEM=1
  export GIT_AUTHOR_NAME="Jane Doe" GIT_AUTHOR_EMAIL="jane@example.org"
  export GIT_COMMITTER_NAME="Jane Doe" GIT_COMMITTER_EMAIL="jane@example.org"
  git init -q -b main "$merge_repo"
  cd "$merge_repo"
  echo "base" >README.txt
  git add README.txt
  git commit -q -s -m "base"
  git checkout -q -b side
  echo "side" >side.txt
  git add side.txt
  git commit -q -s -m "side"
  git checkout -q main
  echo "main" >main.txt
  git add main.txt
  git commit -q -s -m "main"
  git merge -q --no-ff --no-commit side >/dev/null 2>&1
  printf 'server:\n  token: argos_%s\n' "$(random_chars 'A-Za-z0-9_-' 43)" >merge-only.yml
  git add merge-only.yml
  git commit -q -s --no-edit
)
merge_token=$(sed -n 's/^  token: //p' "$merge_repo/merge-only.yml")
fixture_log() { # fixture_log <git log options...>: the log of the fixture repository, hermetic config
  GIT_CONFIG_GLOBAL="$work/gitconfig" GIT_CONFIG_NOSYSTEM=1 git -C "$merge_repo" log --all "$@"
}
token_in() { # token_in <git log options...>: does that log contain the generated token?
  local out
  out=$(fixture_log "$@") || return 2
  [[ -n "$merge_token" && "$out" == *"$merge_token"* ]]
}
expect_exit 0 "fixture: the token is in the merge commit's own (combined) diff" token_in -p --cc --merges
expect_exit 1 "fixture: the token is in no non-merge commit diff" token_in -p --no-merges
expect_exit 1 "a secret added only by a merge commit is reported by the dir scan" bash "$script" dir "$merge_repo"
expect_output "merge-only.yml" "the dir scan finding names the file the merge added" bash "$script" dir "$merge_repo"

finish
