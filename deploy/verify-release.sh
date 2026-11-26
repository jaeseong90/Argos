#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# verify-release.sh - verifies a downloaded Argos APM release before installation (docs/16 section
# 10.3, docs/17 section 4.4, docs/19 section 7.3, SEC-72).
#
# Usage: verify-release.sh [--offline] [--key <file.asc>] [--fingerprint <FPR>] [--repo <owner/name>]
#                          [<version>] <dir>
#
#   <dir>            directory with the release assets and SHA256SUMS
#   <version>        X.Y.Z[-pre]: also checks that the agent and server jars of that version are
#                    listed, and pins the signer identity to the tag vX.Y.Z[-pre]
#   --offline        checksums and OpenPGP signatures only; never runs cosign or gh (air gap)
#   --key <file>     public key to use (default: <dir>/ARGOS-RELEASE-KEY.asc, then the
#                    keys/ARGOS-RELEASE-KEY.asc next to this script's directory)
#   --fingerprint    expected fingerprint of the primary key (default: RELEASE_KEY_FINGERPRINT
#                    below). Compare it by eye with SECURITY.md and the release notes.
#   --repo           GitHub repository that built the release (default: argos-apm/argos), for the
#                    cosign signer identity and the attestation check
#
# Steps, in order:
#   1. every line of SHA256SUMS matches the file it names (sha256sum, or shasum -a 256 on macOS);
#   2. the public key is imported into a temporary GNUPGHOME (the user's keyring is never read or
#      changed) and must contain the primary key with the expected fingerprint, not revoked;
#   3. SHA256SUMS.asc is a good signature of SHA256SUMS by that key (required);
#   4. every <asset>.asc present is a good signature of <asset> by that key;
#   5. online only: cosign verify-blob for every <asset>.sigstore.json bundle and cosign verify for
#      the images of argos-images-*.txt (GitHub Actions OIDC identity of release.yml on the tag),
#      and gh attestation verify for every listed asset. Missing tools are reported as SKIPPED.
# Files in <dir> that SHA256SUMS does not cover (other than signatures and bundles) are reported as
# WARN: do not use them.
#
# Exit status: 0 = every check passed (SKIPPED and WARN allowed), 1 = a check failed, 2 = usage.
#
# Portable bash (3.2 and later: macOS, Linux, Git Bash), gpg 2.x; no network in --offline mode.
set -uo pipefail

# Fingerprint of the organization release key (keys/ARGOS-RELEASE-KEY.fingerprint, SECURITY.md).
# PLACEHOLDER until the organization key exists: the script then refuses to run without
# --fingerprint (keys/README.md, scripts/check-release-key.sh keeps the copies in step).
RELEASE_KEY_FINGERPRINT=PLACEHOLDER

DEFAULT_REPO=argos-apm/argos
OIDC_ISSUER=https://token.actions.githubusercontent.com
KEY_NAME=ARGOS-RELEASE-KEY.asc

usage() {
  echo "usage: verify-release.sh [--offline] [--key <file.asc>] [--fingerprint <FPR>] [--repo <owner/name>] [<version>] <dir>" >&2
  [ -n "${1:-}" ] && echo "verify-release: $1" >&2
  exit 2
}

offline=false
key_file=
fingerprint=
repo=${ARGOS_RELEASE_REPO:-$DEFAULT_REPO}
npos=0
pos1=
pos2=
positional() {
  npos=$((npos + 1))
  case $npos in
    1) pos1=$1 ;;
    2) pos2=$1 ;;
  esac
}
while [ $# -gt 0 ]; do
  case $1 in
    --offline) offline=true ;;
    --key)
      [ $# -ge 2 ] || usage "--key needs a file"
      key_file=$2
      shift
      ;;
    --fingerprint)
      [ $# -ge 2 ] || usage "--fingerprint needs a value"
      fingerprint=$2
      shift
      ;;
    --repo)
      [ $# -ge 2 ] || usage "--repo needs owner/name"
      repo=$2
      shift
      ;;
    -h | --help) usage ;;
    --)
      shift
      while [ $# -gt 0 ]; do
        positional "$1"
        shift
      done
      break
      ;;
    -*) usage "unknown option $1" ;;
    *) positional "$1" ;;
  esac
  shift
done

version=
case $npos in
  1) dir=$pos1 ;;
  2) version=$pos1; dir=$pos2 ;;
  *) usage "expected [<version>] <dir>" ;;
esac
if [ -n "$version" ] && ! printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-(beta|rc)\.[0-9]+)?$'; then
  usage "version '$version' is not X.Y.Z, X.Y.Z-beta.N or X.Y.Z-rc.N"
fi
if ! printf '%s\n' "$repo" | grep -Eq '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$'; then
  usage "--repo must be owner/name"
fi
[ -d "$dir" ] || usage "'$dir' is not a directory"

# --- expected fingerprint ---------------------------------------------------------------------
normalize_fpr() { printf '%s' "$1" | tr -d ' \t' | tr 'abcdef' 'ABCDEF'; }
valid_fpr() { printf '%s\n' "$1" | grep -Eq '^([0-9A-F]{40}|[0-9A-F]{64})$'; }

embedded=$(normalize_fpr "$RELEASE_KEY_FINGERPRINT")
if [ -n "$fingerprint" ]; then
  expected=$(normalize_fpr "$fingerprint")
  valid_fpr "$expected" || usage "--fingerprint must be 40 (or 64) hexadecimal digits"
elif [ "$embedded" = PLACEHOLDER ]; then
  echo "verify-release: this copy of verify-release.sh has no release key fingerprint (placeholder):" >&2
  echo "  the Argos organization release key has not been published with it yet. Pass the fingerprint" >&2
  echo "  that you compared with SECURITY.md and the release notes: --fingerprint <40 hex digits>." >&2
  exit 1
else
  expected=$embedded
  valid_fpr "$expected" || { echo "verify-release: RELEASE_KEY_FINGERPRINT in this script is malformed" >&2; exit 1; }
fi

# --- results ----------------------------------------------------------------------------------
RESULTS=
failures=0
add() { # add <PASS|FAIL|WARN|SKIPPED> <check> <detail>
  RESULTS="${RESULTS}$(printf '%-8s %-46s %s' "$1" "$2" "$3")
"
  [ "$1" = FAIL ] && failures=$((failures + 1))
  return 0
}
group_fpr() { printf '%s' "$1" | sed 's/\(....\)/\1 /g; s/ $//'; }
finish() {
  echo
  printf '%-8s %-46s %s\n' RESULT CHECK DETAIL
  printf '%s' "$RESULTS"
  echo
  echo "Release key fingerprint: $(group_fpr "$expected")"
  if [ "$failures" -gt 0 ]; then
    echo "verify-release: FAILED ($failures check(s)); do not install these files." >&2
    exit 1
  fi
  echo "verify-release: PASSED"
  exit 0
}

work=
# shellcheck disable=SC2329 # invoked by the EXIT trap
cleanup() {
  if [ -n "$work" ] && [ -d "$work" ]; then
    gpgconf --homedir "$work/gnupg" --kill all >/dev/null 2>&1 || true
    rm -rf "$work"
  fi
}
trap cleanup EXIT
trap 'exit 1' INT TERM

# Resolve paths before changing into the release directory.
script_dir=$(cd "$(dirname "$0")" && pwd)
if [ -n "$key_file" ]; then
  [ -f "$key_file" ] || usage "key file '$key_file' not found"
  key_file=$(cd "$(dirname "$key_file")" && pwd)/$(basename "$key_file")
elif [ -f "$dir/$KEY_NAME" ]; then
  key_file=$(cd "$dir" && pwd)/$KEY_NAME
elif [ -f "$script_dir/../keys/$KEY_NAME" ]; then
  key_file=$(cd "$script_dir/../keys" && pwd)/$KEY_NAME
fi
cd "$dir" || exit 2

if ! command -v gpg >/dev/null 2>&1; then
  add FAIL "gpg" "gpg (GnuPG 2) is not installed"
  finish
fi
if command -v sha256sum >/dev/null 2>&1; then
  hash_file() { sha256sum <"$1" | cut -c1-64; }
elif command -v shasum >/dev/null 2>&1; then
  hash_file() { shasum -a 256 <"$1" | cut -c1-64; }
else
  add FAIL "checksums" "neither sha256sum nor shasum is installed"
  finish
fi

# A short base directory: gpg-agent sockets live in GNUPGHOME and Unix socket paths are limited.
for base in "${ARGOS_GPG_TMPDIR:-}" /tmp "${TMPDIR:-}"; do
  [ -n "$base" ] && [ -d "$base" ] && [ -w "$base" ] || continue
  work=$(mktemp -d "$base/argos-verify.XXXXXX" 2>/dev/null) && break
done
if [ -z "$work" ] || [ ! -d "$work" ]; then
  echo "verify-release: cannot create a temporary directory" >&2
  exit 1
fi
mkdir "$work/gnupg"
chmod 700 "$work/gnupg" 2>/dev/null || true
listed=$work/listed

# --- 1. checksums -----------------------------------------------------------------------------
if [ ! -f SHA256SUMS ]; then
  add FAIL "checksums" "SHA256SUMS is missing"
  finish
fi
: >"$listed"
bad_lines=0
mismatches=0
count=0
while IFS= read -r line || [ -n "$line" ]; do
  line=${line%$'\r'}
  [ -z "$line" ] && continue
  sum=$(printf '%s' "$line" | cut -c1-64 | tr 'ABCDEF' 'abcdef')
  sep=$(printf '%s' "$line" | cut -c65-66)
  name=$(printf '%s' "$line" | cut -c67-)
  if ! printf '%s\n' "$sum" | grep -Eq '^[0-9a-f]{64}$' || { [ "$sep" != "  " ] && [ "$sep" != " *" ]; }; then
    add FAIL "checksums" "malformed line in SHA256SUMS: $line"
    bad_lines=$((bad_lines + 1))
    continue
  fi
  # Plain file names only: no directories, no option-like or hidden names, no control characters.
  case $name in
    '' | . | .. | -* | .* | */* | *\\* | *[[:cntrl:]]* | *[[:space:]]*)
      add FAIL "checksums" "file name not allowed in SHA256SUMS: '$name'"
      bad_lines=$((bad_lines + 1))
      continue
      ;;
  esac
  if grep -Fxq -- "$name" "$listed"; then
    add FAIL "checksums" "$name is listed twice"
    bad_lines=$((bad_lines + 1))
    continue
  fi
  printf '%s\n' "$name" >>"$listed"
  count=$((count + 1))
  if [ ! -f "$name" ]; then
    add FAIL "checksum $name" "file is missing"
    mismatches=$((mismatches + 1))
  elif [ "$(hash_file "$name")" != "$sum" ]; then
    add FAIL "checksum $name" "SHA-256 does not match SHA256SUMS"
    mismatches=$((mismatches + 1))
  fi
done <SHA256SUMS
if [ "$count" -eq 0 ] && [ "$bad_lines" -eq 0 ]; then
  add FAIL "checksums" "SHA256SUMS lists no files"
elif [ "$mismatches" -eq 0 ] && [ "$bad_lines" -eq 0 ]; then
  add PASS "checksums (SHA256SUMS)" "$count file(s) match"
fi
if [ -n "$version" ]; then
  for want in "argos-agent-$version.jar" "argos-server-$version.jar"; do
    grep -Fxq -- "$want" "$listed" || add FAIL "release $version" "$want is not listed in SHA256SUMS"
  done
fi
for f in * .[!.]*; do
  [ -f "$f" ] || continue
  case $f in
    SHA256SUMS | SHA256SUMS.asc) continue ;;
  esac
  grep -Fxq -- "$f" "$listed" && continue
  case $f in
    *.asc) grep -Fxq -- "${f%.asc}" "$listed" && continue ;;
    *.sigstore.json) grep -Fxq -- "${f%.sigstore.json}" "$listed" && continue ;;
  esac
  add WARN "not covered: $f" "not listed in SHA256SUMS; do not use it"
done

# --- 2. release key ---------------------------------------------------------------------------
gpg_run() { gpg --homedir "$work/gnupg" --batch --no-tty --no-autostart "$@"; }

if [ -z "$key_file" ]; then
  add FAIL "release key" "no $KEY_NAME in the release directory and no --key given"
  finish
fi
if ! gpg_run --import "$key_file" >"$work/import.log" 2>&1; then
  add FAIL "release key" "cannot import $(basename "$key_file") (not an OpenPGP public key?)"
  finish
fi
# Primary keys of the imported file: "pub" record followed by its "fpr" record.
gpg_run --with-colons --fingerprint >"$work/keys.txt" 2>/dev/null || true
validity=$(awk -F: -v want="$expected" '
  $1 == "pub" { v = $2; take = 1; next }
  $1 == "fpr" && take { if ($10 == want) { print v; exit } take = 0; next }
  { take = 0 }' "$work/keys.txt")
if [ -z "$validity" ]; then
  add FAIL "release key" "$(basename "$key_file") does not contain the primary key $(group_fpr "$expected")"
  finish
fi
case $validity in
  r) add FAIL "release key" "the key $(group_fpr "$expected") is revoked"; finish ;;
  e) add WARN "release key" "the key has expired; signatures made before the expiry still verify" ;;
  *) add PASS "release key" "primary key $expected in $(basename "$key_file")" ;;
esac

# verify_sig <signature> <file>: good signature by the expected primary key (or one of its subkeys).
verify_sig() {
  local status=$work/status
  rm -f "$status"
  gpg_run --status-file "$status" --verify "$1" "$2" >/dev/null 2>"$work/verify.log" || true
  [ -f "$status" ] || { sig_detail="gpg produced no status"; return 1; }
  if grep -Eq '^\[GNUPG:\] (BADSIG|ERRSIG|REVKEYSIG|NO_PUBKEY)' "$status"; then
    sig_detail="$(grep -Eo '^\[GNUPG:\] (BADSIG|ERRSIG|REVKEYSIG|NO_PUBKEY)' "$status" | head -n 1 | cut -d' ' -f2)"
    case $sig_detail in
      BADSIG) sig_detail="bad signature (file changed after signing)" ;;
      ERRSIG | NO_PUBKEY) sig_detail="not signed by the release key" ;;
      REVKEYSIG) sig_detail="signed by a revoked key" ;;
    esac
    return 1
  fi
  if ! grep -Eq '^\[GNUPG:\] (GOODSIG|EXPKEYSIG) ' "$status"; then
    sig_detail="no good signature"
    return 1
  fi
  # VALIDSIG <signing key fpr> ... <primary key fpr>: the last field is the primary key.
  if ! awk -v want="$expected" '$1 == "[GNUPG:]" && $2 == "VALIDSIG" && $NF == want { ok = 1 } END { exit !ok }' "$status"; then
    sig_detail="signed by another key"
    return 1
  fi
  sig_detail="good signature by $expected"
  grep -q '^\[GNUPG:\] EXPKEYSIG ' "$status" && sig_detail="$sig_detail (key now expired)"
  return 0
}

# --- 3. SHA256SUMS.asc ------------------------------------------------------------------------
if [ ! -f SHA256SUMS.asc ]; then
  add FAIL "signature SHA256SUMS.asc" "SHA256SUMS.asc is missing"
elif verify_sig SHA256SUMS.asc SHA256SUMS; then
  add PASS "signature SHA256SUMS.asc" "$sig_detail"
else
  add FAIL "signature SHA256SUMS.asc" "$sig_detail"
fi

# --- 4. per-asset signatures -------------------------------------------------------------------
while IFS= read -r name; do
  [ -f "$name.asc" ] && [ -f "$name" ] || continue
  if verify_sig "$name.asc" "$name"; then
    add PASS "signature $name.asc" "good signature"
  else
    add FAIL "signature $name.asc" "$sig_detail"
  fi
done <"$listed"

# --- 5. online checks -------------------------------------------------------------------------
if $offline; then
  add SKIPPED "cosign (keyless signatures)" "--offline"
  add SKIPPED "GitHub attestations" "--offline"
  finish
fi

repo_re=$(printf '%s' "$repo" | sed 's/[.]/\\./g')
if [ -n "$version" ]; then
  identity_args=(--certificate-identity "https://github.com/$repo/.github/workflows/release.yml@refs/tags/v$version")
else
  identity_args=(--certificate-identity-regexp "^https://github\\.com/$repo_re/\\.github/workflows/release\\.yml@refs/tags/v")
fi

if ! command -v cosign >/dev/null 2>&1; then
  add SKIPPED "cosign (keyless signatures)" "cosign is not installed"
else
  bundles=0
  while IFS= read -r name; do
    [ -f "$name.sigstore.json" ] || continue
    bundles=$((bundles + 1))
    if cosign verify-blob --bundle "$name.sigstore.json" "${identity_args[@]}" \
      --certificate-oidc-issuer "$OIDC_ISSUER" "$name" >"$work/cosign.log" 2>&1; then
      add PASS "cosign $name" "signed by $repo release.yml"
    else
      add FAIL "cosign $name" "$(tail -n 1 "$work/cosign.log")"
    fi
  done <"$listed"
  [ "$bundles" -eq 0 ] && add SKIPPED "cosign (keyless signatures)" "no .sigstore.json bundles in the directory"
  # Container images: argos-images-<version>.txt lines "<name>=<registry>/<repository>[:tag]@sha256:<hex>".
  while IFS= read -r name; do
    case $name in argos-images-*.txt) ;; *) continue ;; esac
    while IFS= read -r line || [ -n "$line" ]; do
      line=${line%$'\r'}
      case $line in '' | '#'*) continue ;; esac
      ref=${line#*=}
      digest=${ref##*@}
      repository=${ref%@*}
      repository_no_tag=${repository%:*}
      [ "${repository_no_tag#*/}" != "$repository_no_tag" ] || repository_no_tag=$repository
      if ! printf '%s\n' "$digest" | grep -Eq '^sha256:[0-9a-f]{64}$'; then
        add FAIL "cosign image" "no digest in '$line' ($name)"
        continue
      fi
      if cosign verify "${identity_args[@]}" --certificate-oidc-issuer "$OIDC_ISSUER" \
        "$repository_no_tag@$digest" >"$work/cosign.log" 2>&1; then
        add PASS "cosign image ${line%%=*}" "$repository_no_tag@$digest"
      else
        add FAIL "cosign image ${line%%=*}" "$(tail -n 1 "$work/cosign.log")"
      fi
    done <"$name"
  done <"$listed"
fi

if ! command -v gh >/dev/null 2>&1; then
  add SKIPPED "GitHub attestations" "gh is not installed"
elif ! gh auth status >/dev/null 2>&1; then
  add SKIPPED "GitHub attestations" "gh is not logged in (gh auth login)"
else
  source_ref=()
  [ -n "$version" ] && source_ref=(--source-ref "refs/tags/v$version")
  while IFS= read -r name; do
    if gh attestation verify "$name" --repo "$repo" --signer-workflow "$repo/.github/workflows/release.yml" \
      ${source_ref[@]+"${source_ref[@]}"} --deny-self-hosted-runners >"$work/gh.log" 2>&1; then
      add PASS "attestation $name" "build provenance from $repo"
    else
      add FAIL "attestation $name" "$(tail -n 1 "$work/gh.log")"
    fi
  done <"$listed"
fi

finish
