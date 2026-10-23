#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Tests for .github/scripts/check-dco.sh. Builds a throwaway git repository in a temporary
# directory (global and system git configuration are ignored) and checks that unsigned and
# mismatching commits are rejected while properly signed-off commits are accepted, that merges are
# checked when they carry content of their own, and that the Dependabot exemption covers only
# commits created by Dependabot.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script="$here/../check-dco.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Hermetic git: no user/system configuration (signing, hooks, templates) leaks into the fixture.
: >"$work/gitconfig"
export GIT_CONFIG_GLOBAL="$work/gitconfig"
export GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME="Jane Doe" GIT_AUTHOR_EMAIL="jane@example.org"
export GIT_COMMITTER_NAME="Jane Doe" GIT_COMMITTER_EMAIL="jane@example.org"

repo="$work/repo"
git init -q -b main "$repo"
cd "$repo"

n=0
commit() { # commit <message...>  (creates a new file so every commit is non-empty)
  n=$((n + 1))
  echo "$n" >"file$n.txt"
  git add "file$n.txt"
  git commit -q "$@"
}

# commit_as <author> <author-email> <committer> <committer-email> <git commit args...>
commit_as() {
  local an=$1 ae=$2 cn=$3 ce=$4
  shift 4
  n=$((n + 1))
  echo "$n" >"file$n.txt"
  git add "file$n.txt"
  GIT_AUTHOR_NAME=$an GIT_AUTHOR_EMAIL=$ae GIT_COMMITTER_NAME=$cn GIT_COMMITTER_EMAIL=$ce git commit -q "$@"
}

DEPENDABOT_NAME='dependabot[bot]'
DEPENDABOT_EMAIL='49699333+dependabot[bot]@users.noreply.github.com'

commit -m "base commit" -m "Signed-off-by: Jane Doe <jane@example.org>"
base=$(git rev-parse HEAD)

# 1. Unsigned commit is rejected.
git checkout -q -b unsigned "$base"
commit -m "unsigned change"
expect_exit 1 "unsigned commit is rejected" bash "$script" "$base" HEAD
expect_output "MISSING $(git rev-parse --short HEAD)" "failure names the unsigned commit" bash "$script" "$base" HEAD

# 2. Commit signed off with `git commit -s` is accepted.
git checkout -q -b signed "$base"
commit -s -m "signed change"
expect_exit 0 "signed-off commit is accepted" bash "$script" "$base" HEAD

# 3. Sign-off email differs from the author email.
git checkout -q -b wrong-email "$base"
commit -m "wrong email" -m "Signed-off-by: Jane Doe <someone-else@example.org>"
expect_exit 1 "sign-off with a different email is rejected" bash "$script" "$base" HEAD

# 4. Sign-off name differs from the author name.
git checkout -q -b wrong-name "$base"
commit -m "wrong name" -m "Signed-off-by: John Roe <jane@example.org>"
expect_exit 1 "sign-off with a different name is rejected" bash "$script" "$base" HEAD

# 5. Email comparison is case-insensitive; the trailer key is case-insensitive.
git checkout -q -b case "$base"
commit -m "case" -m "signed-off-by: Jane Doe <JANE@Example.ORG>"
expect_exit 0 "case differences in key and email are accepted" bash "$script" "$base" HEAD

# 6. One unsigned commit in the middle of a signed series fails the whole range.
git checkout -q -b series "$base"
commit -s -m "first"
commit -m "second, unsigned"
commit -s -m "third"
expect_exit 1 "unsigned commit inside a series is rejected" bash "$script" "$base" HEAD

# 7. Co-author sign-off alone is not enough; the author's sign-off is required.
git checkout -q -b coauthor "$base"
commit -m "co-authored" -m "Signed-off-by: John Roe <john@example.org>"
expect_exit 1 "sign-off by someone other than the author is rejected" bash "$script" "$base" HEAD

# 8. Additional sign-offs are fine as long as the author's is present.
git checkout -q -b multi "$base"
commit -m "two sign-offs" -m "Signed-off-by: John Roe <john@example.org>
Signed-off-by: Jane Doe <jane@example.org>"
expect_exit 0 "author sign-off among several sign-offs is accepted" bash "$script" "$base" HEAD

# 9. Merge commits without content of their own are skipped by default and checked with
#    DCO_INCLUDE_MERGES=1.
git checkout -q -b merge-a "$base"
commit -s -m "signed a"
git checkout -q -b merge-b "$base"
commit -s -m "signed b"
git merge -q --no-ff --no-edit merge-a
expect_exit 0 "unsigned merge without content of its own is skipped by default" bash "$script" "$base" HEAD
expect_output "skip    $(git rev-parse --short HEAD)" "the skipped merge is listed" bash "$script" "$base" HEAD
expect_exit 1 "unsigned merge commit fails with DCO_INCLUDE_MERGES=1" \
  env DCO_INCLUDE_MERGES=1 bash "$script" "$base" HEAD

# 9a. A merge that resolves a conflict with new content carries content of its own: unsigned it
#     fails by default; signed off by its author it passes.
git checkout -q -b conflict-a "$base"
echo "side a" >shared.txt
git add shared.txt
git commit -q -s -m "signed a (shared.txt)"
git checkout -q -b conflict-b "$base"
echo "side b" >shared.txt
git add shared.txt
git commit -q -s -m "signed b (shared.txt)"
git merge -q --no-edit conflict-a >/dev/null 2>&1 || true
echo "resolution that is in neither parent" >shared.txt
git add shared.txt
git commit -q --no-edit
expect_exit 1 "unsigned merge with a conflict resolution is rejected by default" bash "$script" "$base" HEAD
expect_output "MISSING $(git rev-parse --short HEAD)" "failure names the merge with content" bash "$script" "$base" HEAD
git commit -q --amend -s --no-edit
expect_exit 0 "signed-off merge with a conflict resolution is accepted" bash "$script" "$base" HEAD

# 9b. A merge that adds a file of its own without a conflict ("evil merge") is checked as well.
git checkout -q -b evil-a "$base"
commit -s -m "signed evil a"
git checkout -q -b evil-b "$base"
commit -s -m "signed evil b"
git merge -q --no-ff --no-commit evil-a >/dev/null 2>&1
echo "added only by the merge" >merge-only.txt
git add merge-only.txt
git commit -q --no-edit
expect_exit 1 "unsigned merge that adds a file of its own is rejected by default" bash "$script" "$base" HEAD

# 10a. DCO_EXEMPT_DEPENDABOT=1 exempts commits created by Dependabot, with GitHub as committer (how
#      GitHub creates them) or with Dependabot as committer.
git checkout -q -b dependabot "$base"
commit_as "$DEPENDABOT_NAME" "$DEPENDABOT_EMAIL" GitHub noreply@github.com -m "Bump foo from 1 to 2"
commit_as "$DEPENDABOT_NAME" "$DEPENDABOT_EMAIL" "$DEPENDABOT_NAME" "$DEPENDABOT_EMAIL" -m "Bump bar from 1 to 2"
expect_exit 0 "Dependabot commits are exempt with DCO_EXEMPT_DEPENDABOT=1" \
  env DCO_EXEMPT_DEPENDABOT=1 bash "$script" "$base" HEAD
expect_output "exempt  $(git rev-parse --short HEAD)" "exempt commits are listed" \
  env DCO_EXEMPT_DEPENDABOT=1 bash "$script" "$base" HEAD

# 10b. Without the option, Dependabot commits fail like any other unsigned commit.
expect_exit 1 "Dependabot commits fail without DCO_EXEMPT_DEPENDABOT" bash "$script" "$base" HEAD
expect_exit 1 "Dependabot commits fail with DCO_EXEMPT_DEPENDABOT=0" \
  env DCO_EXEMPT_DEPENDABOT=0 bash "$script" "$base" HEAD

# 10c. An unsigned commit that a person adds to the same pull request still fails with the option.
dependabot_head=$(git rev-parse HEAD)
commit -m "Regenerate verification metadata, unsigned"
expect_exit 1 "unsigned human commit next to Dependabot commits is rejected with DCO_EXEMPT_DEPENDABOT=1" \
  env DCO_EXEMPT_DEPENDABOT=1 bash "$script" "$base" HEAD
expect_output "MISSING $(git rev-parse --short HEAD)" "failure names the human commit" \
  env DCO_EXEMPT_DEPENDABOT=1 bash "$script" "$base" HEAD
git checkout -q -b dependabot-signed "$dependabot_head"
commit -s -m "Regenerate verification metadata, signed"
expect_exit 0 "signed-off human commit next to Dependabot commits is accepted with DCO_EXEMPT_DEPENDABOT=1" \
  env DCO_EXEMPT_DEPENDABOT=1 bash "$script" "$base" HEAD

# 10d. A Dependabot-authored commit that a person rewrote (the person is the committer) is not exempt.
git checkout -q -b dependabot-rewritten "$base"
commit_as "$DEPENDABOT_NAME" "$DEPENDABOT_EMAIL" "Jane Doe" jane@example.org -m "Bump baz from 1 to 2"
expect_exit 1 "Dependabot-authored commit with another committer is not exempt" \
  env DCO_EXEMPT_DEPENDABOT=1 bash "$script" "$base" HEAD

# 11. Empty range passes.
expect_exit 0 "empty range is accepted" bash "$script" HEAD HEAD

# 12. Usage errors.
expect_exit 2 "unknown commit is a usage error" bash "$script" "$base" deadbeefdeadbeef
expect_exit 2 "missing arguments is a usage error" bash "$script" "$base"

finish
