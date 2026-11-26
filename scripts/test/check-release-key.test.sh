#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: SEC-72, O-R06
# test-ids: T-BLD-038
#
# Tests for scripts/check-release-key.sh (docs/19 section 7.3): the repository state (placeholder
# key, copies in step) and fixture repositories with a throwaway key generated at test time.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$here/../.." && pwd)
script="$repo_root/scripts/check-release-key.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$repo_root/.github/scripts/test/lib.sh"
# shellcheck source=scripts/test/test-lib.sh
source "$here/test-lib.sh"
# shellcheck source=deploy/test/gpg-test-key.sh
source "$repo_root/deploy/test/gpg-test-key.sh"

work=$(mktemp -d)
cleanup() {
  gpg_test_cleanup
  rm -rf "$work"
}
trap cleanup EXIT
gpg_test_init

# The repository: a placeholder until the organization key exists, consistent in every copy.
if grep -qx PLACEHOLDER "$repo_root/keys/ARGOS-RELEASE-KEY.fingerprint"; then
  expect_exit 0 "repository: the placeholder key is consistent (--allow-placeholder, pull request lint)" \
    bash "$script" --allow-placeholder
  expect_run 1 "placeholder" "repository: without --allow-placeholder the placeholder stops a release" bash "$script"
else
  expect_exit 0 "repository: the release key is installed and consistent" bash "$script"
fi

home=$(gpg_test_home key)
FPR=$(gpg_test_gen_key "$home" "Argos Test Release Signing <release@test.invalid>")
other_home=$(gpg_test_home other)
OTHER=$(gpg_test_gen_key "$other_home" "Someone Else <other@test.invalid>")
grouped=$(printf '%s' "$FPR" | sed 's/\(....\)/\1 /g; s/ $//')

# fixture <name> <fingerprint value> <embedded value>: a repository root with the three copies.
fixture() {
  local r=$work/$1
  mkdir -p "$r/keys" "$r/deploy"
  printf '# fingerprint\n%s\n' "$2" >"$r/keys/ARGOS-RELEASE-KEY.fingerprint"
  sed "s/^RELEASE_KEY_FINGERPRINT=.*/RELEASE_KEY_FINGERPRINT=$3/" "$repo_root/deploy/verify-release.sh" >"$r/deploy/verify-release.sh"
  gpg_test_export "$home" "$FPR" "$r/keys/ARGOS-RELEASE-KEY.asc"
  # shellcheck disable=SC2016 # backquotes are Markdown
  printf '# Security Policy\n\n## Release signing\n\nFingerprint: `%s`\n' "$grouped" >"$r/SECURITY.md"
  printf '%s\n' "$r"
}

r=$(fixture good "$FPR" "$FPR")
expect_exit 0 "a real key consistent in keys/, verify-release.sh and SECURITY.md passes" bash "$script" "$r"
expect_run 0 "$FPR" "--print prints the fingerprint" bash "$script" --print "$r"

r=$(fixture lower "$(printf '%s' "$grouped" | tr 'A-F' 'a-f')" "$FPR")
expect_exit 0 "the fingerprint file may use lower case and groups of four" bash "$script" "$r"

r=$(fixture embedded-differs "$FPR" "$OTHER")
expect_run 1 "deploy/verify-release.sh has RELEASE_KEY_FINGERPRINT=$OTHER" "verify-release.sh with another fingerprint fails" \
  bash "$script" "$r"

r=$(fixture placeholder-mismatch PLACEHOLDER "$FPR")
expect_exit 1 "a placeholder fingerprint file with a real constant in verify-release.sh fails, even with --allow-placeholder" \
  bash "$script" --allow-placeholder "$r"

r=$(fixture no-security "$FPR" "$FPR")
printf '# Security Policy\n\nNo fingerprint here.\n' >"$r/SECURITY.md"
expect_run 1 "SECURITY.md does not show" "SECURITY.md without the fingerprint fails" bash "$script" "$r"

r=$(fixture other-key "$FPR" "$FPR")
gpg_test_export "$other_home" "$OTHER" "$r/keys/ARGOS-RELEASE-KEY.asc"
expect_run 1 "does not contain the primary key" "a key file with another key fails" bash "$script" "$r"

r=$(fixture not-a-key "$FPR" "$FPR")
printf 'PLACEHOLDER: not a key\n' >"$r/keys/ARGOS-RELEASE-KEY.asc"
expect_run 1 "is not an OpenPGP public key" "a fingerprint without a real key file fails" bash "$script" "$r"

r=$(fixture private "$FPR" "$FPR")
printf -- '-----BEGIN PGP %s KEY BLOCK-----\n' PRIVATE >>"$r/keys/ARGOS-RELEASE-KEY.asc"
expect_run 1 "contains a private key" "a key file with a private key block fails" bash "$script" "$r"

r=$(fixture malformed "1234" "1234")
expect_exit 1 "a malformed fingerprint fails" bash "$script" "$r"

r=$(fixture two-lines "$FPR" "$FPR")
printf '%s\n%s\n' "$FPR" "$OTHER" >"$r/keys/ARGOS-RELEASE-KEY.fingerprint"
expect_exit 1 "a fingerprint file with two values fails" bash "$script" "$r"

expect_exit 2 "usage: unknown option" bash "$script" --bogus

finish
