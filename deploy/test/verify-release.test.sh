#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: SEC-72, O-R06
#
# Tests for deploy/verify-release.sh and scripts/release-sign.sh (T-BLD-018 proposal, SEC-72
# ST-72-2): a fixture release is signed with a throwaway key generated at test time (never
# committed, deleted at the end), then verified, tampered with and verified again. cosign and gh are
# stubs on PATH that record their arguments: the test makes no network call.
#
#   bash deploy/test/verify-release.test.sh
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$here/../.." && pwd)
verify="$repo_root/deploy/verify-release.sh"
sign="$repo_root/scripts/release-sign.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$repo_root/.github/scripts/test/lib.sh"
# shellcheck source=deploy/test/gpg-test-key.sh
source "$here/gpg-test-key.sh"
# shellcheck source=scripts/test/test-lib.sh
source "$repo_root/scripts/test/test-lib.sh"

command -v gpg >/dev/null 2>&1 || { echo "verify-release.test: gpg is required" >&2; exit 1; }

work=$(mktemp -d)
cleanup() {
  gpg_test_cleanup
  rm -rf "$work"
}
trap cleanup EXIT
gpg_test_init

# --- keys and fixture release -----------------------------------------------------------------
release_home=$(gpg_test_home release)
other_home=$(gpg_test_home other)
FPR=$(gpg_test_gen_key "$release_home" "Argos Test Release Signing <release@test.invalid>")
OTHER=$(gpg_test_gen_key "$other_home" "Someone Else <other@test.invalid>")
[[ ${#FPR} -eq 40 && ${#OTHER} -eq 40 ]] || { echo "key generation failed" >&2; exit 1; }
gpg_test_export "$release_home" "$FPR" "$work/release.pub.asc"
gpg_test_export "$other_home" "$OTHER" "$work/other.pub.asc"

V=0.1.0
DIGEST=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
rel=$work/release
mkdir "$rel"
head -c 4096 /dev/urandom >"$rel/argos-agent-$V.jar"
head -c 8192 /dev/urandom >"$rel/argos-server-$V.jar"
printf '{"bomFormat":"CycloneDX","components":[]}\n' >"$rel/argos-agent-$V.cdx.json"
printf 'argos-server=ghcr.io/argos-apm/argos-server:%s@sha256:%s\n' "$V" "$DIGEST" >"$rel/argos-images-$V.txt"
cp "$work/release.pub.asc" "$rel/ARGOS-RELEASE-KEY.asc"
# The build's SHA256SUMS (releaseArtifacts) covers the jars only; release-sign.sh rewrites it.
(cd "$rel" && sha256sum "argos-agent-$V.jar" "argos-server-$V.jar" >SHA256SUMS)

# release-sign.sh: refusals first, on copies.
bad=$work/bad-build
cp -R "$rel" "$bad"
printf 'x' >>"$bad/argos-agent-$V.jar"
expect_exit 1 "release-sign: refuses when the build's SHA256SUMS no longer matches (changed in transit)" \
  env GNUPGHOME="$release_home" bash "$sign" --fingerprint "$FPR" "$bad"
expect_exit 1 "release-sign: refuses a fingerprint without a secret key in GNUPGHOME" \
  env GNUPGHOME="$release_home" bash "$sign" --fingerprint "$OTHER" "$rel"
expect_exit 2 "release-sign: malformed fingerprint is a usage error" \
  env GNUPGHOME="$release_home" bash "$sign" --fingerprint 1234 "$rel"

expect_exit 0 "release-sign: signs the fixture release with the throwaway key" \
  env GNUPGHOME="$release_home" bash "$sign" --fingerprint "$FPR" "$rel"
expect_exit 0 "release-sign: SHA256SUMS covers every asset, including the files added after the build" \
  bash -c "cd '$rel' && grep -q ' ARGOS-RELEASE-KEY.asc\$' SHA256SUMS && grep -q ' argos-images-$V.txt\$' SHA256SUMS && [ \$(wc -l <SHA256SUMS) -eq 5 ]"
expect_exit 0 "release-sign: one .asc per asset plus SHA256SUMS.asc" \
  bash -c "cd '$rel' && for f in SHA256SUMS argos-agent-$V.jar argos-server-$V.jar argos-agent-$V.cdx.json argos-images-$V.txt ARGOS-RELEASE-KEY.asc; do [ -f \"\$f.asc\" ] || exit 1; done"
expect_exit 1 "release-sign: refuses to sign a directory twice" \
  env GNUPGHOME="$release_home" bash "$sign" --fingerprint "$FPR" "$rel"

# Copies of verify-release.sh with the embedded fingerprint set (as a release would ship it) and
# with the placeholder (as the repository has it until the organization key exists).
sed "s/^RELEASE_KEY_FINGERPRINT=.*/RELEASE_KEY_FINGERPRINT=$FPR/" "$verify" >"$work/verify-embedded.sh"
sed "s/^RELEASE_KEY_FINGERPRINT=.*/RELEASE_KEY_FINGERPRINT=PLACEHOLDER/" "$verify" >"$work/verify-placeholder.sh"
grep -q "^RELEASE_KEY_FINGERPRINT=$FPR\$" "$work/verify-embedded.sh"

# fresh <name>: a pristine copy of the signed release.
fresh() {
  rm -rf "${work:?}/$1"
  cp -R "$rel" "$work/$1"
  printf '%s\n' "$work/$1"
}

# --- offline verification ---------------------------------------------------------------------
vr=$work/verify-embedded.sh
expect_run 0 "verify-release: PASSED" "T-BLD-018: a correctly signed release passes (embedded fingerprint, --offline)" \
  bash "$vr" --offline "$V" "$rel"
expect_run 0 "signature argos-server-$V.jar.asc" "T-BLD-018: per-asset signatures are checked" \
  bash "$vr" --offline "$V" "$rel"
expect_run 0 "PASSED" "T-BLD-018: --fingerprint with spaces and lower case is accepted" \
  bash "$verify" --offline --fingerprint "$(printf '%s' "$FPR" | tr 'A-F' 'a-f' | sed 's/\(....\)/\1 /g')" "$V" "$rel"
expect_run 0 "PASSED" "T-BLD-018: version is optional" bash "$vr" --offline "$rel"

d=$(fresh tampered)
printf 'x' >>"$d/argos-server-$V.jar"
expect_run 1 "checksum argos-server-$V.jar" "T-BLD-018: a tampered asset fails and is named" bash "$vr" --offline "$V" "$d"

d=$(fresh tampered-sums)
printf 'x' >>"$d/argos-server-$V.jar"
(cd "$d" && for f in ARGOS-RELEASE-KEY.asc "argos-agent-$V.cdx.json" "argos-agent-$V.jar" "argos-images-$V.txt" "argos-server-$V.jar"; do
  printf '%s  %s\n' "$(sha256sum <"$f" | cut -c1-64)" "$f"
done >SHA256SUMS)
expect_run 1 "bad signature" "T-BLD-018: a rewritten SHA256SUMS fails the signature check" bash "$vr" --offline "$V" "$d"

d=$(fresh other-signer)
rm -f "$d"/*.asc
cp "$work/release.pub.asc" "$d/ARGOS-RELEASE-KEY.asc"
GNUPGHOME=$other_home gpg --batch --yes --armor --detach-sign --output "$d/SHA256SUMS.asc" "$d/SHA256SUMS" 2>/dev/null
expect_run 1 "not signed by the release key" "T-BLD-018: SHA256SUMS signed by another key fails" bash "$vr" --offline "$V" "$d"

d=$(fresh other-key-file)
expect_run 1 "does not contain the primary key" "T-BLD-018: a key file without the expected primary key fails" \
  bash "$vr" --offline --key "$work/other.pub.asc" "$V" "$d"

d=$(fresh other-signer-key)
cp "$work/other.pub.asc" "$d/ARGOS-RELEASE-KEY.asc"
expect_run 1 "does not contain the primary key" \
  "T-BLD-018: a substituted key file in the download fails against the expected fingerprint" bash "$vr" --offline "$V" "$d"

d=$(fresh missing-asc)
rm -f "$d/SHA256SUMS.asc"
expect_run 1 "SHA256SUMS.asc is missing" "T-BLD-018: a missing SHA256SUMS.asc fails" bash "$vr" --offline "$V" "$d"

d=$(fresh missing-sums)
rm -f "$d/SHA256SUMS"
expect_run 1 "SHA256SUMS is missing" "T-BLD-018: a missing SHA256SUMS fails" bash "$vr" --offline "$V" "$d"

d=$(fresh missing-asset)
rm -f "$d/argos-agent-$V.cdx.json"
expect_run 1 "file is missing" "T-BLD-018: an asset listed in SHA256SUMS but missing fails" bash "$vr" --offline "$V" "$d"

d=$(fresh bad-asset-sig)
cp "$d/argos-agent-$V.jar.asc" "$d/argos-server-$V.jar.asc"
expect_run 1 "signature argos-server-$V.jar.asc" "T-BLD-018: a per-asset signature that belongs to another file fails" \
  bash "$vr" --offline "$V" "$d"

expect_run 1 "does not contain the primary key" "T-BLD-018: fingerprint mismatch fails" \
  bash "$verify" --offline --fingerprint "$OTHER" "$V" "$rel"
expect_run 1 "argos-agent-0.2.0.jar is not listed" "T-BLD-018: the release must contain the jars of the requested version" \
  bash "$vr" --offline 0.2.0 "$rel"

expect_run 1 "placeholder" "T-BLD-018: the placeholder fingerprint is refused without --fingerprint, with the reason" \
  bash "$work/verify-placeholder.sh" --offline "$V" "$rel"
expect_run 0 "PASSED" "T-BLD-018: the placeholder copy verifies with an explicit --fingerprint" \
  bash "$work/verify-placeholder.sh" --offline --fingerprint "$FPR" "$V" "$rel"

d=$(fresh traversal)
printf '%s  ../outside.txt\n' "$DIGEST" >>"$d/SHA256SUMS"
expect_run 1 "file name not allowed" "T-BLD-018: a SHA256SUMS line with a path is refused" bash "$vr" --offline "$V" "$d"

d=$(fresh extra-file)
printf 'not part of the release\n' >"$d/install-me.sh"
expect_run 0 "not covered: install-me.sh" "T-BLD-018: a file outside SHA256SUMS is reported as WARN and does not fail" \
  bash "$vr" --offline "$V" "$d"

# Revoked key: revoke a third key with its generated revocation certificate.
revoked_home=$(gpg_test_home revoked)
REVOKED=$(gpg_test_gen_key "$revoked_home" "Revoked Test Key <revoked@test.invalid>")
d=$work/revoked-release
mkdir "$d"
head -c 1024 /dev/urandom >"$d/argos-agent-$V.jar"
head -c 1024 /dev/urandom >"$d/argos-server-$V.jar"
GNUPGHOME=$revoked_home bash "$sign" --fingerprint "$REVOKED" "$d" >/dev/null
sed 's/^://' "$revoked_home/openpgp-revocs.d/$REVOKED.rev" | GNUPGHOME=$revoked_home gpg --batch --import >/dev/null 2>&1
gpg_test_export "$revoked_home" "$REVOKED" "$d/ARGOS-RELEASE-KEY.asc"
expect_run 1 "is revoked" "T-BLD-018: a revoked release key fails" bash "$verify" --offline --fingerprint "$REVOKED" "$V" "$d"

# --- online verification with stubs -----------------------------------------------------------
stubs=$work/bin
mkdir "$stubs"
cat >"$stubs/cosign" <<'EOF'
#!/usr/bin/env bash
printf 'cosign %s\n' "$*" >>"$STUB_LOG"
exit "${COSIGN_STUB_EXIT:-0}"
EOF
cat >"$stubs/gh" <<'EOF'
#!/usr/bin/env bash
printf 'gh %s\n' "$*" >>"$STUB_LOG"
if [ "${1:-} ${2:-}" = "auth status" ]; then exit "${GH_AUTH_EXIT:-0}"; fi
exit "${GH_STUB_EXIT:-0}"
EOF
chmod +x "$stubs/cosign" "$stubs/gh"
export STUB_LOG=$work/stub.log

online=$(fresh online)
for f in "argos-agent-$V.jar" "argos-server-$V.jar" "argos-agent-$V.cdx.json" "argos-images-$V.txt" ARGOS-RELEASE-KEY.asc; do
  printf '{"stub":"bundle"}\n' >"$online/$f.sigstore.json"
done

: >"$STUB_LOG"
expect_exit 0 "T-BLD-018: --offline passes with cosign and gh on PATH" \
  env PATH="$stubs:$PATH" bash "$work/verify-embedded.sh" --offline "$V" "$online"
expect_exit 0 "T-BLD-018: --offline never runs cosign or gh" test ! -s "$STUB_LOG"

: >"$STUB_LOG"
expect_exit 0 "T-BLD-018: online verification passes when cosign and gh accept" \
  env PATH="$stubs:$PATH" bash "$work/verify-embedded.sh" "$V" "$online"
identity="https://github.com/argos-apm/argos/.github/workflows/release.yml@refs/tags/v$V"
expect_output "cosign verify-blob --bundle argos-agent-$V.jar.sigstore.json --certificate-identity $identity --certificate-oidc-issuer https://token.actions.githubusercontent.com argos-agent-$V.jar" \
  "T-BLD-018: cosign verify-blob gets the bundle, the tag identity of release.yml and the GitHub OIDC issuer" cat "$STUB_LOG"
expect_output "cosign verify --certificate-identity $identity --certificate-oidc-issuer https://token.actions.githubusercontent.com ghcr.io/argos-apm/argos-server@sha256:$DIGEST" \
  "T-BLD-018: cosign verify checks the server image by digest" cat "$STUB_LOG"
expect_output "gh attestation verify argos-server-$V.jar --repo argos-apm/argos --signer-workflow argos-apm/argos/.github/workflows/release.yml --source-ref refs/tags/v$V --deny-self-hosted-runners" \
  "T-BLD-018: gh attestation verify gets the repository, the signer workflow and the tag" cat "$STUB_LOG"

: >"$STUB_LOG"
expect_exit 0 "T-BLD-018: without a version the signer identity is a regular expression on the tag prefix" \
  env PATH="$stubs:$PATH" bash "$work/verify-embedded.sh" --repo my-org/argos.fork "$online"
expect_output "--certificate-identity-regexp ^https://github\\.com/my-org/argos\\.fork/\\.github/workflows/release\\.yml@refs/tags/v" \
  "T-BLD-018: --repo changes the identity regexp (dots escaped)" cat "$STUB_LOG"

expect_exit 1 "T-BLD-018: a rejected cosign signature fails" \
  env PATH="$stubs:$PATH" COSIGN_STUB_EXIT=1 bash "$work/verify-embedded.sh" "$V" "$online"
expect_exit 1 "T-BLD-018: a rejected attestation fails" \
  env PATH="$stubs:$PATH" GH_STUB_EXIT=1 bash "$work/verify-embedded.sh" "$V" "$online"
expect_run 0 "gh is not logged in" "T-BLD-018: gh without a login is SKIPPED with the reason, not failed" \
  env PATH="$stubs:$PATH" GH_AUTH_EXIT=1 bash "$work/verify-embedded.sh" "$V" "$online"
expect_no_output "not covered" "T-BLD-018: signatures and cosign bundles are not reported as uncovered files" \
  env PATH="$stubs:$PATH" bash "$work/verify-embedded.sh" "$V" "$online"
if ! command -v cosign >/dev/null 2>&1; then
  expect_output "cosign is not installed" "T-BLD-018: without cosign the keyless check is SKIPPED" \
    bash "$work/verify-embedded.sh" "$V" "$online"
fi

# --- usage ------------------------------------------------------------------------------------
expect_exit 2 "usage: no directory" bash "$verify"
expect_exit 2 "usage: unknown option" bash "$verify" --bogus "$rel"
expect_exit 2 "usage: malformed version" bash "$verify" --fingerprint "$FPR" 1.2 "$rel"
expect_exit 2 "usage: malformed fingerprint" bash "$verify" --fingerprint XYZ "$rel"
expect_exit 2 "usage: directory does not exist" bash "$verify" --fingerprint "$FPR" "$work/nope"

finish
