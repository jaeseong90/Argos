#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# check-release-key.sh - checks that the release key fingerprint agrees everywhere (docs/19 section
# 7.3: keys/, SECURITY.md, verify-release.sh, the release) and that the key exists.
#
# Usage: check-release-key.sh [--allow-placeholder] [--print] [<repository-root>]
#
#   keys/ARGOS-RELEASE-KEY.fingerprint   one non-comment line: 40 (or 64) hex digits or PLACEHOLDER
#   deploy/verify-release.sh             RELEASE_KEY_FINGERPRINT=<the same value>
#   keys/ARGOS-RELEASE-KEY.asc           (real key only) contains that primary key, not revoked
#   SECURITY.md                          (real key only) shows the fingerprint (plain or in groups of 4)
#
#   --allow-placeholder   exit 0 while the key is still the placeholder, if the copies agree
#                         (pull request lint). Without it a placeholder is an error: the release
#                         workflow stops before building anything.
#   --print               print only the fingerprint (or PLACEHOLDER) on stdout
#
# Exit status: 0 = consistent (and a real key unless --allow-placeholder), 1 = not, 2 = usage.
set -euo pipefail

allow_placeholder=false
print=false
root=
while [ $# -gt 0 ]; do
  case $1 in
    --allow-placeholder) allow_placeholder=true ;;
    --print) print=true ;;
    -*) echo "usage: check-release-key.sh [--allow-placeholder] [--print] [<repository-root>]" >&2; exit 2 ;;
    *)
      [ -z "$root" ] || { echo "check-release-key: only one repository root" >&2; exit 2; }
      root=$1
      ;;
  esac
  shift
done
root=${root:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
[ -d "$root" ] || { echo "check-release-key: '$root' is not a directory" >&2; exit 2; }

fpr_file=$root/keys/ARGOS-RELEASE-KEY.fingerprint
key_file=$root/keys/ARGOS-RELEASE-KEY.asc
script=$root/deploy/verify-release.sh
security=$root/SECURITY.md
errors=0
err() {
  echo "check-release-key: $*" >&2
  errors=$((errors + 1))
}

[ -f "$fpr_file" ] || { echo "check-release-key: $fpr_file is missing" >&2; exit 1; }
values=$(sed 's/\r$//' "$fpr_file" | grep -v '^[[:space:]]*#' | grep -v '^[[:space:]]*$' || true)
if [ "$(printf '%s\n' "$values" | grep -c .)" -ne 1 ]; then
  echo "check-release-key: $fpr_file must have exactly one non-comment line" >&2
  exit 1
fi
fpr=$(printf '%s' "$values" | tr -d ' \t' | tr 'abcdef' 'ABCDEF')
if [ "$fpr" != PLACEHOLDER ] && ! printf '%s\n' "$fpr" | grep -Eq '^([0-9A-F]{40}|[0-9A-F]{64})$'; then
  echo "check-release-key: $fpr_file: '$values' is neither a fingerprint (40 hex digits) nor PLACEHOLDER" >&2
  exit 1
fi

embedded=$(sed -n 's/^RELEASE_KEY_FINGERPRINT=\([^[:space:]]*\).*$/\1/p' "$script" 2>/dev/null | head -n 1)
[ "$embedded" = "$fpr" ] \
  || err "deploy/verify-release.sh has RELEASE_KEY_FINGERPRINT=${embedded:-<none>}, keys/ARGOS-RELEASE-KEY.fingerprint has $fpr"

if [ "$fpr" = PLACEHOLDER ]; then
  if [ "$errors" -gt 0 ]; then exit 1; fi
  if $allow_placeholder; then
    $print && echo PLACEHOLDER
    echo "check-release-key: the release key is still the placeholder (allowed: --allow-placeholder)" >&2
    exit 0
  fi
  echo "check-release-key: the release key is a placeholder: the organization key has not been installed" >&2
  echo "  (keys/README.md). No release can be signed until a maintainer installs it." >&2
  exit 1
fi

if grep -q "PRIVATE KEY" "$key_file" 2>/dev/null; then
  err "keys/ARGOS-RELEASE-KEY.asc contains a private key: only the public key belongs in the repository"
fi

grouped=$(printf '%s' "$fpr" | sed 's/\(....\)/\1 /g; s/ $//')
if [ ! -f "$security" ] || ! grep -Fq -e "$fpr" -e "$grouped" "$security"; then
  err "SECURITY.md does not show the release key fingerprint $grouped"
fi

if ! command -v gpg >/dev/null 2>&1; then
  err "gpg is required to check keys/ARGOS-RELEASE-KEY.asc"
else
  home=
  for base in "${ARGOS_GPG_TMPDIR:-}" /tmp "${TMPDIR:-}"; do
    [ -n "$base" ] && [ -d "$base" ] && [ -w "$base" ] || continue
    home=$(mktemp -d "$base/argos-key.XXXXXX" 2>/dev/null) && break
  done
  [ -n "$home" ] || { echo "check-release-key: no temporary directory" >&2; exit 1; }
  trap 'rm -rf "$home"' EXIT
  chmod 700 "$home" 2>/dev/null || true
  if ! gpg --homedir "$home" --batch --no-tty --no-autostart --import "$key_file" >/dev/null 2>&1; then
    err "keys/ARGOS-RELEASE-KEY.asc is not an OpenPGP public key"
  else
    validity=$(gpg --homedir "$home" --batch --no-autostart --with-colons --fingerprint 2>/dev/null | awk -F: -v want="$fpr" '
      $1 == "pub" { v = $2; take = 1; next }
      $1 == "fpr" && take { if ($10 == want) { print v; exit } take = 0; next }
      { take = 0 }')
    if [ -z "$validity" ]; then
      err "keys/ARGOS-RELEASE-KEY.asc does not contain the primary key $fpr"
    elif [ "$validity" = r ]; then
      err "the release key $fpr is revoked"
    fi
  fi
fi

if [ "$errors" -gt 0 ]; then exit 1; fi
$print && echo "$fpr"
echo "check-release-key: release key $grouped is consistent" >&2
exit 0
