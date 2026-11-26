#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# release-notes.sh - release notes of version X.Y.Z (docs/19 section 7.7, DSN-19-11): the CHANGELOG
# section, the upgrade notes, the verification commands, the release key fingerprint, the assets
# and the known limitations of 0.x pre-releases. release-drafter is not used.
#
# Usage: release-notes.sh [--check] [--root <dir>] [--repo <owner/name>] [--assets <dir>] X.Y.Z[-pre]
#
#   --check    only validate (exit 1 with the reason): CHANGELOG.md has a non-empty "## [X.Y.Z]"
#              section, and the first release of a new minor that follows another minor has
#              docs/upgrade/X.Y.md. Used by the prepare job of release.yml before anything is built.
#   --root     repository root (default: the parent of this script's directory)
#   --repo     GitHub repository of the release (default: argos-apm/argos); the image owner is its
#              owner in lower case (ghcr.io/<owner>/argos-server)
#   --assets   list the files of this directory as the release assets (the draft job passes the
#              signed release directory); otherwise the expected asset names are listed
#
# Without --check the notes (Markdown) go to stdout. They need the real release key fingerprint
# (keys/ARGOS-RELEASE-KEY.fingerprint); a placeholder is an error.
#
# Exit status: 0 = ok, 1 = missing or empty section, missing upgrade notes or placeholder key,
# 2 = usage error.
set -euo pipefail
export LC_ALL=C

usage() {
  echo "usage: release-notes.sh [--check] [--root <dir>] [--repo <owner/name>] [--assets <dir>] X.Y.Z[-pre]" >&2
  [ -n "${1:-}" ] && echo "release-notes: $1" >&2
  exit 2
}
die() {
  echo "release-notes: $*" >&2
  exit 1
}

check=false
root=
repo=argos-apm/argos
assets=
version=
while [ $# -gt 0 ]; do
  case $1 in
    --check) check=true ;;
    --root | --repo | --assets)
      [ $# -ge 2 ] || usage "$1 needs a value"
      case $1 in
        --root) root=$2 ;;
        --repo) repo=$2 ;;
        --assets) assets=$2 ;;
      esac
      shift
      ;;
    -*) usage "unknown option $1" ;;
    *)
      [ -z "$version" ] || usage "only one version"
      version=$1
      ;;
  esac
  shift
done
[ -n "$version" ] || usage "missing version"
printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-(beta|rc)\.[0-9]+)?$' \
  || usage "version '$version' is not X.Y.Z, X.Y.Z-beta.N or X.Y.Z-rc.N"
printf '%s\n' "$repo" | grep -Eq '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' || usage "--repo must be owner/name"
root=${root:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
[ -f "$root/CHANGELOG.md" ] || die "$root/CHANGELOG.md not found"
if [ -n "$assets" ] && [ ! -d "$assets" ]; then usage "--assets '$assets' is not a directory"; fi

core=${version%%-*}
major=${core%%.*}
rest=${core#*.}
minor=${rest%%.*}
patch=${rest#*.}
xy=$major.$minor
owner=$(printf '%s' "${repo%%/*}" | tr '[:upper:]' '[:lower:]')

# --- CHANGELOG section ------------------------------------------------------------------------
changelog=$(sed 's/\r$//' "$root/CHANGELOG.md")
version_re=$(printf '%s' "$version" | sed 's/[.]/\\./g')
section=$(printf '%s\n' "$changelog" | SECTION_RE="^## \\[$version_re\\]( - .*)?$" awk '
  found && (/^## / || /^\[[^]]+\]: /) { exit }
  found { print }
  $0 ~ ENVIRON["SECTION_RE"] { found = 1 }')
if ! printf '%s\n' "$changelog" | grep -Eq "^## \\[$version_re\\]( - .*)?$"; then
  die "CHANGELOG.md has no '## [$version]' section: move the Unreleased entries into it (RELEASING.md)"
fi
# Trim leading and trailing blank lines.
section=$(printf '%s\n' "$section" | awk 'NF { last = NR } { line[NR] = $0 } END { s = 0; for (i = 1; i <= last; i++) { if (!s && line[i] ~ /^[[:space:]]*$/) continue; s = 1; print line[i] } }')
if ! printf '%s\n' "$section" | grep -v '^###' | grep -q '[^[:space:]]'; then
  die "the '## [$version]' section of CHANGELOG.md is empty"
fi

# --- upgrade notes ----------------------------------------------------------------------------
upgrade_file=$root/docs/upgrade/$xy.md
# Earlier release sections of another minor: the release line had a previous minor to upgrade from.
other_minor=$(printf '%s\n' "$changelog" | sed -n 's/^## \[\([0-9][0-9]*\.[0-9][0-9]*\)\.[0-9][0-9]*[^]]*\].*$/\1/p' | grep -Fxv "$xy" | head -n 1 || true)
if [ -f "$upgrade_file" ]; then
  upgrade=$(sed 's/\r$//' "$upgrade_file" | grep -v '^<!-- SPDX-License-Identifier' || true)
elif [ "$patch" = 0 ] && [ -n "$other_minor" ]; then
  die "docs/upgrade/$xy.md is missing: the first release of minor $xy needs upgrade notes (docs/19 section 7.7)"
elif [ "$patch" = 0 ]; then
  upgrade="No upgrade notes: first release of this minor."
else
  upgrade="No upgrade notes for this patch release. Upgrading from an earlier minor: read the upgrade notes of $xy."
fi

$check && { echo "release-notes: CHANGELOG section and upgrade notes of $version are present" >&2; exit 0; }

# --- fingerprint ------------------------------------------------------------------------------
fpr_file=$root/keys/ARGOS-RELEASE-KEY.fingerprint
[ -f "$fpr_file" ] || die "$fpr_file not found"
fpr=$(sed 's/\r$//' "$fpr_file" | grep -v '^[[:space:]]*#' | grep -v '^[[:space:]]*$' | head -n 1 | tr -d ' \t' | tr 'abcdef' 'ABCDEF')
printf '%s\n' "$fpr" | grep -Eq '^([0-9A-F]{40}|[0-9A-F]{64})$' \
  || die "keys/ARGOS-RELEASE-KEY.fingerprint holds no fingerprint (placeholder?): install the release key first (keys/README.md)"
grouped=$(printf '%s' "$fpr" | sed 's/\(....\)/\1 /g; s/ $//')

identity="https://github.com/$repo/.github/workflows/release.yml@refs/tags/v$version"
issuer=https://token.actions.githubusercontent.com

cat <<EOF
# Argos APM $version

This is a **pre-release**. Argos APM 0.x makes no compatibility promise; a migration from the
previous 0.x minor is provided.

$section

## Upgrade notes

$upgrade

## Verify the download

Release key fingerprint (primary key): \`$grouped\`

Compare it with the "Release signing" section of SECURITY.md in the repository before you trust
the key. Then, in the directory with the downloaded assets:

\`\`\`
# Offline (also on an air-gapped host): checksums and OpenPGP signatures
bash verify-release.sh --offline --fingerprint $fpr $version .

# Online, in addition: cosign keyless signatures and GitHub build provenance
bash verify-release.sh --fingerprint $fpr $version .
\`\`\`

The same checks by hand:

\`\`\`
gpg --import ARGOS-RELEASE-KEY.asc
sha256sum -c SHA256SUMS && gpg --verify SHA256SUMS.asc SHA256SUMS
cosign verify-blob --bundle argos-agent-$version.jar.sigstore.json \\
  --certificate-identity $identity \\
  --certificate-oidc-issuer $issuer argos-agent-$version.jar
gh attestation verify argos-agent-$version.jar --repo $repo
\`\`\`

## Container images

Pin images by digest. The digests are in \`argos-images-$version.txt\` and in the Compose files of
\`argos-compose-$version.tar.gz\`.

- \`ghcr.io/$owner/argos-server:$version\` (linux/amd64, linux/arm64)
- \`ghcr.io/$owner/argos-demo-app:$version\` and \`ghcr.io/$owner/argos-demo-load:$version\` (demo profile only)

\`\`\`
cosign verify ghcr.io/$owner/argos-server@sha256:<digest> \\
  --certificate-identity $identity \\
  --certificate-oidc-issuer $issuer
\`\`\`

## Assets

EOF

if [ -n "$assets" ]; then
  # shellcheck disable=SC2016 # backquotes are Markdown
  (cd "$assets" && for f in *; do if [ -f "$f" ]; then printf -- '- `%s`\n' "$f"; fi; done)
else
  cat <<EOF
- \`argos-agent-$version.jar\`: the Java agent (single jar)
- \`argos-server-$version.jar\`: the server (executable jar with the UI)
- \`argos-agent-$version.cdx.json\`, \`argos-server-$version.cdx.json\`, \`argos-ui-$version.cdx.json\`: CycloneDX SBOMs
- \`argos-compose-$version.tar.gz\`: Docker Compose installation files with the image digests
- \`argos-images-$version.txt\`: container image digests
- \`SHA256SUMS\`, \`SHA256SUMS.asc\`, \`<asset>.asc\`: checksums and OpenPGP signatures
- \`<asset>.sigstore.json\`: cosign keyless signature bundles
- \`ARGOS-RELEASE-KEY.asc\`, \`ARGOS-RELEASE-KEY.fingerprint\`, \`verify-release.sh\`: public key and verification script
EOF
fi

cat <<EOF

## Known limitations

- Release gates that are not run for 0.x pre-releases yet, because their dedicated runners do not
  exist: the nightly compatibility matrix, the overhead benchmark, the 72-hour soak test, chaos
  tests, the upgrade path test and the air-gap egress test. This release passed the pull request
  checks, the security test suite, the reproducible build comparison and the SBOM scan.
- Not published for 0.x: Maven Central artifacts, the air-gap bundle and the tar package.
EOF
