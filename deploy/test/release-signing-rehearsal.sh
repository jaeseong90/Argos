#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# release-signing-rehearsal.sh - local rehearsal of the signing and verification steps of
# .github/workflows/release.yml on a real build output, with a throwaway key (T-BLD-018 proposal).
#
# Usage: release-signing-rehearsal.sh [<version>] <release-dir>
#
#   <release-dir>   output of ./gradlew releaseArtifacts -Pversion=X.Y.Z (build/release); it is
#                   copied, never changed
#
# Steps: copy the directory; add the public key, its fingerprint and verify-release.sh as the
# `sign` job does; sign with scripts/release-sign.sh and a key generated now in a temporary
# GNUPGHOME (deleted at the end); run deploy/verify-release.sh --offline (must pass); change one byte
# of the first jar and verify again (must fail). The organization key and cosign keyless signing
# are not part of the rehearsal (they need the release environment and GitHub OIDC).
#
# Exit status: 0 = signed release verified and tampering detected, 1 = otherwise, 2 = usage.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$here/../.." && pwd)
# shellcheck source=deploy/test/gpg-test-key.sh
source "$here/gpg-test-key.sh"

case $# in
  1) version=; src=$1 ;;
  2) version=$1; src=$2 ;;
  *) echo "usage: release-signing-rehearsal.sh [<version>] <release-dir>" >&2; exit 2 ;;
esac
[ -d "$src" ] || { echo "release-signing-rehearsal: '$src' is not a directory" >&2; exit 2; }

work=$(mktemp -d)
cleanup() {
  gpg_test_cleanup
  rm -rf "$work"
}
trap cleanup EXIT
gpg_test_init

home=$(gpg_test_home rehearsal)
fpr=$(gpg_test_gen_key "$home" "Argos Rehearsal Key (throwaway) <rehearsal@test.invalid>")
rel=$work/release
cp -R "$src" "$rel"
gpg_test_export "$home" "$fpr" "$rel/ARGOS-RELEASE-KEY.asc"
printf '%s\n' "$fpr" >"$rel/ARGOS-RELEASE-KEY.fingerprint"
cp "$repo_root/deploy/verify-release.sh" "$rel/verify-release.sh"

echo "== sign (throwaway key $fpr)"
GNUPGHOME=$home bash "$repo_root/scripts/release-sign.sh" --fingerprint "$fpr" "$rel"

echo "== verify (must pass)"
args=(--offline --fingerprint "$fpr")
[ -n "$version" ] && args+=("$version")
if ! bash "$repo_root/deploy/verify-release.sh" "${args[@]}" "$rel"; then
  echo "release-signing-rehearsal: FAILED, the signed release does not verify" >&2
  exit 1
fi

jar=
for j in "$rel"/*.jar; do
  if [ -f "$j" ]; then
    jar=${j##*/}
    break
  fi
done
[ -n "$jar" ] || { echo "release-signing-rehearsal: no jar in $src" >&2; exit 1; }
echo "== tamper with $jar (one byte) and verify again (must fail)"
printf 'x' >>"$rel/$jar"
if bash "$repo_root/deploy/verify-release.sh" "${args[@]}" "$rel"; then
  echo "release-signing-rehearsal: FAILED, the tampered release was accepted" >&2
  exit 1
fi
echo "release-signing-rehearsal: PASSED (signed release verified, tampering with $jar detected)"
