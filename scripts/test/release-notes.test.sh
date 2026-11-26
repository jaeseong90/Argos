#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: O-R10
#
# Tests for scripts/release-notes.sh (T-BLD-019 proposal, docs/19 section 7.7) with fixture
# repositories: CHANGELOG section extraction, upgrade notes, verification commands, assets,
# known limitations and the --check failures.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$here/../.." && pwd)
script="$repo_root/scripts/release-notes.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$repo_root/.github/scripts/test/lib.sh"
# shellcheck source=scripts/test/test-lib.sh
source "$here/test-lib.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

FPR=0123456789ABCDEF0123456789ABCDEF01234567

# fixture <name>: a repository root with the CHANGELOG from stdin and a real-looking fingerprint.
fixture() {
  mkdir -p "$work/$1/keys" "$work/$1/docs/upgrade"
  cat >"$work/$1/CHANGELOG.md"
  printf '# comment\n%s\n' "$FPR" >"$work/$1/keys/ARGOS-RELEASE-KEY.fingerprint"
  printf '%s\n' "$work/$1"
}

first=$(fixture first <<'EOF'
# Changelog

## [Unreleased]

### Added

- Something not released yet.

## [0.1.0] - 2026-12-01

### Added

- The Java agent and the server.
- Release pipeline with signatures.

### Security

- Signed checksums.

[Unreleased]: https://github.com/argos-apm/argos/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/argos-apm/argos/releases/tag/v0.1.0
EOF
)

expect_exit 0 "T-BLD-019: --check passes with a non-empty CHANGELOG section" bash "$script" --root "$first" --check 0.1.0
expect_run 1 "no '## [0.1.1]' section" "T-BLD-019: --check fails when the section is missing" \
  bash "$script" --root "$first" --check 0.1.1
expect_run 1 "no '## [0.1.0-rc.1]' section" "T-BLD-019: generation fails too when the section is missing" \
  bash "$script" --root "$first" 0.1.0-rc.1

notes=$work/first.md
bash "$script" --root "$first" 0.1.0 >"$notes"
expect_output "- The Java agent and the server." "T-BLD-019: the notes contain the CHANGELOG section" cat "$notes"
expect_output "- Signed checksums." "T-BLD-019: every subsection of the version is kept" cat "$notes"
expect_no_output "not released yet" "T-BLD-019: Unreleased entries are not in the notes" cat "$notes"
expect_no_output "releases/tag/v0.1.0" "T-BLD-019: link reference definitions are not part of the section" cat "$notes"
expect_output "No upgrade notes: first release of this minor." "T-BLD-019: first release without upgrade notes" cat "$notes"
expect_output "Release key fingerprint (primary key): \`0123 4567 89AB CDEF 0123 4567 89AB CDEF 0123 4567\`" \
  "T-BLD-019: the release key fingerprint is shown in groups of four" cat "$notes"
expect_output "bash verify-release.sh --offline --fingerprint $FPR 0.1.0 ." "T-BLD-019: the offline verification command" cat "$notes"
expect_output "sha256sum -c SHA256SUMS && gpg --verify SHA256SUMS.asc SHA256SUMS" "T-BLD-019: the manual checksum and signature command" cat "$notes"
expect_output "--certificate-identity https://github.com/argos-apm/argos/.github/workflows/release.yml@refs/tags/v0.1.0" \
  "T-BLD-019: the cosign identity is release.yml on the tag" cat "$notes"
expect_output "gh attestation verify argos-agent-0.1.0.jar --repo argos-apm/argos" "T-BLD-019: the attestation command" cat "$notes"
expect_output "ghcr.io/argos-apm/argos-server:0.1.0" "T-BLD-019: the server image" cat "$notes"
expect_output "\`argos-ui-0.1.0.cdx.json\`" "T-BLD-019: the expected assets are listed without --assets" cat "$notes"
expect_output "the nightly compatibility matrix, the overhead benchmark, the 72-hour soak test, chaos" \
  "T-BLD-019: known limitations list the gates that are not run" cat "$notes"
expect_output "This is a **pre-release**." "T-BLD-019: 0.x notes say pre-release" cat "$notes"

mkdir "$work/assets"
touch "$work/assets/argos-agent-0.1.0.jar" "$work/assets/SHA256SUMS" "$work/assets/SHA256SUMS.asc"
expect_output "- \`SHA256SUMS.asc\`" "T-BLD-019: --assets lists the files of the release directory" \
  bash "$script" --root "$first" --assets "$work/assets" 0.1.0
expect_output "ghcr.io/argos-apm/argos-server" "T-BLD-019: the image owner is the repository owner in lower case" \
  bash "$script" --root "$first" --repo Argos-APM/argos 0.1.0

empty=$(fixture empty <<'EOF'
# Changelog

## [0.1.0] - 2026-12-01

### Added

## [0.0.9] - 2026-11-01

- Older.
EOF
)
expect_run 1 "section of CHANGELOG.md is empty" "T-BLD-019: --check fails on a section with headings only" \
  bash "$script" --root "$empty" --check 0.1.0

crlf=$(fixture crlf </dev/null)
printf '# Changelog\r\n\r\n## [0.1.0]\r\n\r\n- Windows line endings.\r\n' >"$crlf/CHANGELOG.md"
expect_output "- Windows line endings." "T-BLD-019: a CHANGELOG with CRLF line endings works" bash "$script" --root "$crlf" 0.1.0

minor=$(fixture minor <<'EOF'
# Changelog

## [0.2.0] - 2027-01-10

### Changed

- New storage layout.

## [0.1.1] - 2026-12-20

### Fixed

- A fix.

## [0.1.0] - 2026-12-01

- First.
EOF
)
expect_run 1 "docs/upgrade/0.2.md is missing" "T-BLD-019: a new minor after another minor needs docs/upgrade/X.Y.md" \
  bash "$script" --root "$minor" --check 0.2.0
printf '<!-- SPDX-License-Identifier: Apache-2.0 -->\n\n# Upgrading to 0.2\n\nRun the migration (about 5 minutes).\n' >"$minor/docs/upgrade/0.2.md"
expect_exit 0 "T-BLD-019: --check passes once the upgrade notes exist" bash "$script" --root "$minor" --check 0.2.0
expect_output "Run the migration (about 5 minutes)." "T-BLD-019: the upgrade notes are included" bash "$script" --root "$minor" 0.2.0
expect_no_output "SPDX-License-Identifier" "T-BLD-019: the SPDX comment of the upgrade notes is dropped" bash "$script" --root "$minor" 0.2.0
expect_output "No upgrade notes for this patch release." "T-BLD-019: a patch release without upgrade notes" \
  bash "$script" --root "$minor" 0.1.1
expect_no_output "A fix." "T-BLD-019: the section ends at the next version" bash "$script" --root "$minor" 0.2.0

placeholder=$(fixture placeholder <"$first/CHANGELOG.md")
printf 'PLACEHOLDER\n' >"$placeholder/keys/ARGOS-RELEASE-KEY.fingerprint"
expect_exit 0 "T-BLD-019: --check does not depend on the key" bash "$script" --root "$placeholder" --check 0.1.0
expect_run 1 "placeholder" "T-BLD-019: notes are not generated with the placeholder key" bash "$script" --root "$placeholder" 0.1.0

expect_exit 2 "usage: malformed version" bash "$script" --root "$first" 0.1
expect_exit 2 "usage: v prefix is not a version" bash "$script" --root "$first" v0.1.0
expect_exit 2 "usage: missing version" bash "$script" --root "$first"
expect_exit 2 "usage: malformed repository" bash "$script" --root "$first" --repo nope 0.1.0

finish
