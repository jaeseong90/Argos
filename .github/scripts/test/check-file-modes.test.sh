#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: O-Q02
# test-ids: T-BLD-033
#
# Tests for .github/scripts/check-file-modes.sh. Builds throwaway git repositories with
# core.filemode=false (the Windows default) in a temporary directory: a plain `git add` records
# gradlew and scripts as 100644 and must be rejected, `git add --chmod=+x` must be accepted.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script="$here/../check-file-modes.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Hermetic git: no user/system configuration leaks into the fixture.
: >"$work/gitconfig"
export GIT_CONFIG_GLOBAL="$work/gitconfig"
export GIT_CONFIG_NOSYSTEM=1
# Repository discovery never leaves the fixture directory (for the "not a git work tree" case).
export GIT_CEILING_DIRECTORIES="$work"
export GIT_AUTHOR_NAME="Jane Doe" GIT_AUTHOR_EMAIL="jane@example.org"
export GIT_COMMITTER_NAME="Jane Doe" GIT_COMMITTER_EMAIL="jane@example.org"

# new_repo <dir>: gradlew, a script with a shebang, a sourced helper without one, and a README.
# The files are executable in the working tree where the file system supports it; with
# core.filemode=false git ignores that, as on Windows.
new_repo() {
  git init -q -b main "$1"
  git -C "$1" config core.filemode false
  mkdir -p "$1/scripts/test"
  printf '#!/bin/sh\necho gradle\n' >"$1/gradlew"
  printf '#!/usr/bin/env bash\necho run\n' >"$1/scripts/run.sh"
  printf '#!/usr/bin/env bash\necho test\n' >"$1/scripts/test/run.test.sh"
  printf '# sourced helper\nhelper=1\n' >"$1/scripts/lib.sh"
  printf 'readme\n' >"$1/README.md"
  chmod +x "$1/gradlew" "$1/scripts/run.sh" "$1/scripts/test/run.test.sh"
}

# 1. Plain `git add` with core.filemode=false: gradlew and both scripts are 100644.
plain="$work/plain"
new_repo "$plain"
git -C "$plain" add .
expect_exit 1 "plain git add (core.filemode=false) is rejected" bash "$script" "$plain"
expect_output "gradlew: mode 100644, expected 100755 [M1]" "gradlew is reported" bash "$script" "$plain"
expect_output "scripts/test/run.test.sh: mode 100644, expected 100755 [M2]" \
  "nested script with a shebang is reported" bash "$script" "$plain"
expect_output "git add --chmod=+x -- gradlew scripts/run.sh scripts/test/run.test.sh" \
  "the fix command lists exactly the offending files" bash "$script" "$plain"

# 2. Same files added with --chmod=+x: accepted. The helper without a shebang may stay 100644.
fixed="$work/fixed"
new_repo "$fixed"
git -C "$fixed" add .
git -C "$fixed" add --chmod=+x gradlew scripts/run.sh scripts/test/run.test.sh
expect_exit 0 "git add --chmod=+x is accepted" bash "$script" "$fixed"
expect_output "3 file(s) checked, all 100755" "only gradlew and shebang scripts are checked" \
  bash "$script" "$fixed"

# 3. The fix command printed in case 1 repairs the index.
(cd "$plain" && git add --chmod=+x -- gradlew scripts/run.sh scripts/test/run.test.sh)
expect_exit 0 "the printed fix command makes the check pass" bash "$script" "$plain"

# 4. Committed and cloned: the clone keeps 100755, and a later regression is caught.
git -C "$fixed" commit -q -m "initial"
git clone -q "$fixed" "$work/clone"
expect_exit 0 "a clone of a correct commit passes" bash "$script" "$work/clone"
git -C "$fixed" update-index --chmod=-x scripts/run.sh
expect_exit 1 "a script that loses its executable bit is rejected" bash "$script" "$fixed"

# 5. gradlew missing from the index.
nogradlew="$work/nogradlew"
new_repo "$nogradlew"
git -C "$nogradlew" add --chmod=+x scripts/run.sh scripts/test/run.test.sh
expect_exit 1 "untracked gradlew is rejected" bash "$script" "$nogradlew"
expect_output "gradlew: not tracked" "untracked gradlew is reported" bash "$script" "$nogradlew"

# 6. Usage errors.
mkdir -p "$work/not-a-repo"
expect_exit 2 "a directory outside git is a usage error" bash "$script" "$work/not-a-repo"
expect_exit 2 "too many arguments is a usage error" bash "$script" a b

finish
