#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# release-sign.sh - writes SHA256SUMS for a release directory and the OpenPGP detached signatures
# (docs/19 section 7.2 steps 5-6, docs/16 section 10.3, SEC-72). Used by the `sign` job of
# .github/workflows/release.yml and by the local signing rehearsal with a throwaway key
# (deploy/test/release-signing-rehearsal.sh); deploy/verify-release.sh checks the result.
#
# Usage: release-sign.sh --fingerprint <FPR> [--passphrase-file <file>] <dir>
#
#   <dir>               flat directory of release assets. An existing SHA256SUMS (written by
#                       ./gradlew releaseArtifacts) must still match its files; it is then rewritten
#                       to cover every file of <dir> (assets added later, such as the public key,
#                       the Compose archive and verify-release.sh, included), sorted by name.
#   --fingerprint       primary key fingerprint (keys/ARGOS-RELEASE-KEY.fingerprint). The secret
#                       signing (sub)key must be in $GNUPGHOME; gpg picks the signing subkey.
#   --passphrase-file   passphrase of the signing key (pinentry loopback); none for an unprotected key
#
# Writes <dir>/SHA256SUMS, <dir>/SHA256SUMS.asc and <asset>.asc for every listed asset, then
# verifies every signature against the fingerprint. Refuses a directory that already contains
# signatures (SHA256SUMS.asc, <asset>.asc or *.sigstore.json).
#
# Exit status: 0 = signed and verified, 1 = failure, 2 = usage error.
set -euo pipefail
export LC_ALL=C

usage() {
  echo "usage: release-sign.sh --fingerprint <FPR> [--passphrase-file <file>] <dir>" >&2
  [ -n "${1:-}" ] && echo "release-sign: $1" >&2
  exit 2
}
die() {
  echo "release-sign: $*" >&2
  exit 1
}

fingerprint=
passphrase_file=
dir=
while [ $# -gt 0 ]; do
  case $1 in
    --fingerprint)
      [ $# -ge 2 ] || usage "--fingerprint needs a value"
      fingerprint=$2
      shift
      ;;
    --passphrase-file)
      [ $# -ge 2 ] || usage "--passphrase-file needs a file"
      passphrase_file=$2
      shift
      ;;
    -*) usage "unknown option $1" ;;
    *)
      [ -z "$dir" ] || usage "only one directory"
      dir=$1
      ;;
  esac
  shift
done
[ -n "$dir" ] || usage "missing <dir>"
[ -d "$dir" ] || usage "'$dir' is not a directory"
fingerprint=$(printf '%s' "$fingerprint" | tr -d ' \t' | tr 'abcdef' 'ABCDEF')
printf '%s\n' "$fingerprint" | grep -Eq '^([0-9A-F]{40}|[0-9A-F]{64})$' \
  || usage "--fingerprint must be 40 (or 64) hexadecimal digits"
if [ -n "$passphrase_file" ]; then
  [ -f "$passphrase_file" ] || usage "passphrase file '$passphrase_file' not found"
  passphrase_file=$(cd "$(dirname "$passphrase_file")" && pwd)/$(basename "$passphrase_file")
fi
command -v gpg >/dev/null 2>&1 || die "gpg is not installed"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum is not installed"
cd "$dir"

# The secret key must belong to the expected primary key.
if ! gpg --batch --with-colons --list-secret-keys "$fingerprint" 2>/dev/null \
  | awk -F: -v want="$fingerprint" '$1 == "sec" { take = 1; next } $1 == "fpr" && take { if ($10 == want) ok = 1; take = 0 } END { exit !ok }'; then
  die "no secret key for the primary key $fingerprint in GNUPGHOME=${GNUPGHOME:-~/.gnupg}"
fi

[ ! -e SHA256SUMS.asc ] || die "$dir already contains SHA256SUMS.asc"
for f in *.sigstore.json; do
  if [ -e "$f" ]; then die "$dir already contains signature bundles ($f)"; fi
done

# The old SHA256SUMS (from the build) must still match: the assets did not change in transit.
if [ -f SHA256SUMS ]; then
  sha256sum --check --strict --quiet SHA256SUMS || die "the existing SHA256SUMS does not match the files"
fi

names=()
for f in *; do
  [ -f "$f" ] || continue
  [ "$f" = SHA256SUMS ] && continue
  case $f in
    -* | *[[:space:]]* | *[[:cntrl:]]* | *\\*) die "file name not allowed in a release: '$f'" ;;
  esac
  names+=("$f")
done
[ ${#names[@]} -gt 0 ] || die "$dir has no files to sign"
for f in "${names[@]}"; do
  case $f in
    *.asc) if [ -f "${f%.asc}" ]; then die "$dir already contains the signature $f"; fi ;;
  esac
done

# "<hex>  <name>" (text mode, two spaces) on every platform; Git Bash would write " *<name>".
: >SHA256SUMS.tmp
for f in "${names[@]}"; do
  printf '%s  %s\n' "$(sha256sum <"$f" | cut -c1-64)" "$f" >>SHA256SUMS.tmp
done
mv SHA256SUMS.tmp SHA256SUMS

sign_args=(--batch --yes --no-tty --armor --detach-sign --local-user "$fingerprint")
if [ -n "$passphrase_file" ]; then
  sign_args+=(--pinentry-mode loopback --passphrase-file "$passphrase_file")
fi
for f in SHA256SUMS "${names[@]}"; do
  gpg "${sign_args[@]}" --output "$f.asc" -- "$f" || die "signing $f failed"
done

# Verify what was written, against the primary fingerprint (VALIDSIG's last field).
status=$(mktemp)
trap 'rm -f "$status"' EXIT
for f in SHA256SUMS "${names[@]}"; do
  gpg --batch --no-tty --status-file "$status" --verify "$f.asc" "$f" >/dev/null 2>&1 || die "$f.asc does not verify"
  awk -v want="$fingerprint" '$2 == "VALIDSIG" && $NF == want { ok = 1 } END { exit !ok }' "$status" \
    || die "$f.asc is not a signature by the primary key $fingerprint"
done

echo "release-sign: signed SHA256SUMS and ${#names[@]} asset(s) in $dir with $fingerprint"
