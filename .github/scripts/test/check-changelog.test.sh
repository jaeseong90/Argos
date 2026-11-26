#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: O-R10
# test-ids: T-BLD-031
#
# Tests for .github/scripts/check-changelog.sh using a throwaway git repository.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script="$here/../check-changelog.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

: >"$work/gitconfig"
export GIT_CONFIG_GLOBAL="$work/gitconfig"
export GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME="Jane Doe" GIT_AUTHOR_EMAIL="jane@example.org"
export GIT_COMMITTER_NAME="Jane Doe" GIT_COMMITTER_EMAIL="jane@example.org"
unset NO_CHANGELOG CHANGELOG_FILE

git init -q -b main "$work/repo"
cd "$work/repo"

cat >CHANGELOG.md <<'EOF'
# Changelog

## [Unreleased]

### Added

- Existing entry.

## [0.1.0] - 2026-01-01

### Added

- Old entry.
EOF
echo "code" >code.txt
git add . && git commit -q -m "base"
base=$(git rev-parse HEAD)

branch() { git checkout -q -b "$1" "$base"; }
save() { git add -A && git commit -q -m "$1"; }

# 1. No CHANGELOG change at all.
branch no-entry
echo "more code" >>code.txt
save "code only"
expect_exit 1 "pull request without a CHANGELOG entry is rejected" bash "$script" "$base" HEAD

# 2. Same change with the no-changelog label.
expect_exit 0 "no-changelog label skips the check" env NO_CHANGELOG=true bash "$script" "$base" HEAD

# 3. New entry under Unreleased.
branch unreleased-entry
sed -i.bak 's/^- Existing entry\.$/- Existing entry.\n- New entry (#42)./' CHANGELOG.md && rm -f CHANGELOG.md.bak
save "entry"
expect_exit 0 "new entry under Unreleased is accepted" bash "$script" "$base" HEAD

# 4. New subsection with an entry under Unreleased.
branch new-subsection
awk '{ print } /^- Existing entry\.$/ { print ""; print "### Fixed"; print ""; print "* Fixed a crash." }' CHANGELOG.md >c.tmp && mv c.tmp CHANGELOG.md
save "fixed"
expect_exit 0 "new subsection with an entry under Unreleased is accepted" bash "$script" "$base" HEAD

# 5. Entry added to a released version instead of Unreleased.
branch old-section
printf -- '- Sneaky entry in an old release.\n' >>CHANGELOG.md
save "old section"
expect_exit 1 "entry added only to a released section is rejected" bash "$script" "$base" HEAD

# 6. Only a heading or blank line added under Unreleased.
branch heading-only
awk '{ print } /^### Added$/ && !done { print ""; print "### Changed"; done = 1 }' CHANGELOG.md >c.tmp && mv c.tmp CHANGELOG.md
save "heading only"
expect_exit 1 "heading without a list entry is rejected" bash "$script" "$base" HEAD

# 7. Removing an entry is not a new entry.
branch removal
grep -v '^- Existing entry\.$' CHANGELOG.md >c.tmp && mv c.tmp CHANGELOG.md
save "removal"
expect_exit 1 "removing an entry is rejected" bash "$script" "$base" HEAD

# 8. Unreleased section removed.
branch no-unreleased
sed -i.bak 's/^## \[Unreleased\]$/## Upcoming/' CHANGELOG.md && rm -f CHANGELOG.md.bak
printf -- '- Entry.\n' >>CHANGELOG.md
save "no unreleased"
expect_exit 1 "CHANGELOG without an Unreleased section is rejected" bash "$script" "$base" HEAD

# 9. Base moved on after branching: only the pull request's own changes count.
git checkout -q main
sed -i.bak 's/^- Existing entry\.$/- Existing entry.\n- Entry merged on main./' CHANGELOG.md && rm -f CHANGELOG.md.bak
save "main entry"
main_tip=$(git rev-parse HEAD)
git checkout -q no-entry
expect_exit 1 "entries added on the base branch do not count for the pull request" bash "$script" "$main_tip" HEAD

# 10. Usage errors.
expect_exit 2 "unknown commit is a usage error" bash "$script" "$base" deadbeefdeadbeef
expect_exit 2 "missing arguments is a usage error" bash "$script" "$base"

finish
