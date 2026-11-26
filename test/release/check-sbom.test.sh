#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: SEC-70, SEC-71
# test-ids: T-BLD-008a, T-BLD-008b
#
# Tests for test/release/check-sbom.sh (T-BLD-008a; review dates T-BLD-008b, proposed): fixture
# SBOMs, exception files and fake scanners in a temporary directory; no network, no Docker.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
script="$here/check-sbom.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$root/.github/scripts/test/lib.sh"

work="$(mktemp -d "${TMPDIR:-/tmp}/argos-check-sbom-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

sbom() { # sbom <file> <component count> [bomFormat]
  local file=$1 count=$2 format=${3:-CycloneDX} components="" i
  for ((i = 0; i < count; i++)); do
    [[ -n "$components" ]] && components+=","
    components+="{\"type\":\"library\",\"name\":\"lib$i\",\"version\":\"1.$i\"}"
  done
  printf '{"bomFormat":"%s","specVersion":"1.6","version":1,"components":[%s]}\n' "$format" "$components" >"$file"
}

header='| CVE | Component:version | Impact assessment (reachability) | Mitigation | Approver | Approved on | Review by |
|---|---|---|---|---|---|---|'

exceptions() { # exceptions <file> [row...]
  local file=$1
  shift
  {
    printf '# CVE exceptions\n\nIntro text.\n\n%s\n' "$header"
    for row in "$@"; do printf '%s\n' "$row"; done
    printf '\nTrailing text | not a row\n'
  } >"$file"
}

row1='| CVE-2026-12345 | io.netty:netty-codec-http:4.2.1 | Not reachable: HTTP/2 server codec unused | None needed | maintainer-a | 2026-11-20 | 2027-02-18 |'
row2='| GHSA-abcd-efgh-ijkl | org.example:lib:1.0 | Reachable only with debug flag | Flag off by default | maintainer-b | 2026-11-21 | 2027-02-15 |'

release="$work/release"
mkdir -p "$release"
sbom "$release/argos-agent-0.1.0.cdx.json" 3
sbom "$release/argos-server-0.1.0.cdx.json" 120
sbom "$release/argos-ui-0.1.0.cdx.json" 40
printf 'not an sbom\n' >"$release/SHA256SUMS"

exceptions "$work/empty.md"
printf '# SPDX-License-Identifier: Apache-2.0\n# comment only\n\n' >"$work/empty.trivyignore"
exceptions "$work/two.md" "$row1" "$row2"
printf '# accepted, see docs/security/cve-exceptions.md\nGHSA-abcd-efgh-ijkl\r\nCVE-2026-12345  # trailing comment\n' >"$work/two.trivyignore"

ok_args=(--exceptions "$work/empty.md" --ignorefile "$work/empty.trivyignore")

# --- sbom-format and parts ---------------------------------------------------------------------
expect_exit 0 "three CycloneDX SBOMs with components, no exceptions: OK" bash "$script" "${ok_args[@]}" "$release"
expect_output "PASS  sbom-format: argos-server-0.1.0.cdx.json: CycloneDX 1.6, 120 components" \
  "component count is reported per SBOM" bash "$script" "${ok_args[@]}" "$release"
expect_output "SKIP  scan: no --scanner given" "without --scanner the scan is skipped, not passed" \
  bash "$script" "${ok_args[@]}" "$release"
expect_exit 0 "--require agent,server,ui with all three present" \
  bash "$script" "${ok_args[@]}" --require agent,server,ui "$release"

partial="$work/partial"
mkdir -p "$partial"
cp "$release/argos-agent-0.1.0.cdx.json" "$release/argos-server-0.1.0.cdx.json" "$partial/"
expect_exit 1 "--require ui fails when the UI SBOM is missing" bash "$script" "${ok_args[@]}" --require agent,server,ui "$partial"
expect_output "sbom-parts: ui: expected one argos-ui-*.cdx.json, found 0" "the missing part is named" \
  bash "$script" "${ok_args[@]}" --require agent,server,ui "$partial"

zero="$work/zero"
mkdir -p "$zero"
sbom "$zero/argos-agent-0.1.0.cdx.json" 0
expect_exit 1 "an SBOM with zero components fails (T-BLD-008 count > 0)" bash "$script" "${ok_args[@]}" "$zero"
expect_output "argos-agent-0.1.0.cdx.json: no components" "zero components is reported" bash "$script" "${ok_args[@]}" "$zero"

wrong="$work/wrong"
mkdir -p "$wrong"
sbom "$wrong/a.cdx.json" 2 SPDX
expect_output "bomFormat is \"SPDX\"" "a non-CycloneDX document fails" bash "$script" "${ok_args[@]}" "$wrong"
printf '{"bomFormat":"CycloneDX","specVersion":"1.6","components":[{"type":"library"}]}' >"$wrong/a.cdx.json"
expect_output "1 component(s) without a name" "an unnamed component fails" bash "$script" "${ok_args[@]}" "$wrong"
printf '{ not json' >"$wrong/a.cdx.json"
expect_exit 1 "malformed JSON fails" bash "$script" "${ok_args[@]}" "$wrong"
expect_output "not JSON" "malformed JSON is reported" bash "$script" "${ok_args[@]}" "$wrong"

empty_dir="$work/no-sbom"
mkdir -p "$empty_dir"
expect_exit 1 "a directory without SBOMs fails" bash "$script" "${ok_args[@]}" "$empty_dir"

# --- exceptions (ST-71-2: .trivyignore = exception table) --------------------------------------
expect_exit 0 "two exceptions in both files (CRLF, comments, GHSA) are consistent" \
  bash "$script" --exceptions "$work/two.md" --ignorefile "$work/two.trivyignore" "$release"
expect_output "PASS  exceptions: two.trivyignore = two.md (2 ID(s))" "the consistent ID count is reported" \
  bash "$script" --exceptions "$work/two.md" --ignorefile "$work/two.trivyignore" "$release"
expect_exit 1 ".trivyignore entry without an exception row fails" \
  bash "$script" --exceptions "$work/empty.md" --ignorefile "$work/two.trivyignore" "$release"
expect_output "in two.trivyignore but not in empty.md: CVE-2026-12345 GHSA-abcd-efgh-ijkl" \
  "the unapproved IDs are listed" bash "$script" --exceptions "$work/empty.md" --ignorefile "$work/two.trivyignore" "$release"
expect_output "in two.md but not in empty.trivyignore: CVE-2026-12345 GHSA-abcd-efgh-ijkl" \
  "an exception row missing from the ignore file fails too" \
  bash "$script" --exceptions "$work/two.md" --ignorefile "$work/empty.trivyignore" "$release"
exceptions "$work/incomplete.md" '| CVE-2026-12345 | io.netty:netty-codec-http:4.2.1 | Not reachable | None |  | 2026-11-20 | 2027-02-20 |'
printf 'CVE-2026-12345\n' >"$work/one.trivyignore"
expect_output "CVE-2026-12345: every column must be filled" "a row without an approver fails" \
  bash "$script" --exceptions "$work/incomplete.md" --ignorefile "$work/one.trivyignore" "$release"
printf 'CVE-2026-12345\nCVE-2026-12345 --skip-db\n' >"$work/bad.trivyignore"
expect_exit 1 "an ignore entry that is not a vulnerability ID fails" \
  bash "$script" --exceptions "$work/incomplete.md" --ignorefile "$work/bad.trivyignore" "$release"

# --- review dates (T-BLD-008b, proposed; approval procedure step 5) -----------------------------
two_args=(--exceptions "$work/two.md" --ignorefile "$work/two.trivyignore")
expect_exit 0 "review dates in the future: OK in fail mode" \
  bash "$script" "${two_args[@]}" --review-dates fail --today 2026-12-01 "$release"
expect_output "PASS  review-dates: 2 exception(s), none past its review date (today 2026-12-01, --review-dates fail)" \
  "the checked rows and the reference day are reported" \
  bash "$script" "${two_args[@]}" --review-dates fail --today 2026-12-01 "$release"
expect_exit 0 "a review date equal to today is not past (2027-02-15 on 2027-02-15)" \
  bash "$script" "${two_args[@]}" --review-dates fail --today 2027-02-15 "$release"
expect_exit 0 "warn mode (the default): a passed review date does not fail" \
  bash "$script" "${two_args[@]}" --today 2027-02-16 "$release"
expect_output "WARN  review-dates: GHSA-abcd-efgh-ijkl: review date 2027-02-15 has passed (today 2027-02-16)" \
  "warn mode names the expired row" bash "$script" "${two_args[@]}" --today 2027-02-16 "$release"
expect_exit 0 "warn mode prints exactly one WARN line per expired row" \
  bash -c "[[ \$(bash '$script' --exceptions '$work/two.md' --ignorefile '$work/two.trivyignore' --today 2027-02-16 '$release' | grep -c '^WARN') -eq 1 ]]"
expect_exit 1 "fail mode: a passed review date fails (weekly rescan)" \
  bash "$script" "${two_args[@]}" --review-dates fail --today 2027-02-16 "$release"
expect_output "FAIL  review-dates: GHSA-abcd-efgh-ijkl: review date 2027-02-15 has passed (today 2027-02-16); renew or remove the exception" \
  "fail mode names the expired row" bash "$script" "${two_args[@]}" --review-dates fail --today 2027-02-16 "$release"
expect_exit 0 "fail mode: only the expired row fails (one FAIL line)" \
  bash -c "[[ \$(bash '$script' --exceptions '$work/two.md' --ignorefile '$work/two.trivyignore' --review-dates fail --today 2027-02-17 '$release' | grep -c '^FAIL') -eq 1 ]]"
expect_exit 1 "fail mode: both rows expired" \
  bash "$script" "${two_args[@]}" --review-dates fail --today 2027-03-01 "$release"

date_case() { # date_case <name> <approved on> <review by>
  exceptions "$work/$1.md" "| CVE-2026-12345 | io.netty:netty-codec-http:4.2.1 | Not reachable | None | maintainer-a | $2 | $3 |"
}
date_case d90 2026-11-20 2027-02-18
expect_exit 0 "review date exactly 90 days after approval is accepted" \
  bash "$script" --exceptions "$work/d90.md" --ignorefile "$work/one.trivyignore" --today 2026-12-01 "$release"
date_case d91 2026-11-20 2027-02-19
expect_exit 1 "review date 91 days after approval fails, even in warn mode" \
  bash "$script" --exceptions "$work/d91.md" --ignorefile "$work/one.trivyignore" --today 2026-12-01 "$release"
expect_output "FAIL  review-dates: CVE-2026-12345: review date 2027-02-19 is 91 days after the approval date 2026-11-20 (at most 90)" \
  "the over-long review period is reported" \
  bash "$script" --exceptions "$work/d91.md" --ignorefile "$work/one.trivyignore" --today 2026-12-01 "$release"
date_case leap 2028-02-28 2028-02-29
expect_exit 0 "a leap day is a valid date (2028-02-29)" \
  bash "$script" --exceptions "$work/leap.md" --ignorefile "$work/one.trivyignore" --today 2028-02-01 "$release"
date_case noleap 2027-02-01 2027-02-29
expect_output "'Review by' is not a date YYYY-MM-DD: 2027-02-29" "2027-02-29 does not exist" \
  bash "$script" --exceptions "$work/noleap.md" --ignorefile "$work/one.trivyignore" --today 2027-01-01 "$release"
date_case slash 2026/11/20 2027-01-20
expect_exit 1 "a malformed approval date fails" \
  bash "$script" --exceptions "$work/slash.md" --ignorefile "$work/one.trivyignore" --today 2026-12-01 "$release"
expect_output "'Approved on' is not a date YYYY-MM-DD: 2026/11/20" "the malformed approval date is reported" \
  bash "$script" --exceptions "$work/slash.md" --ignorefile "$work/one.trivyignore" --today 2026-12-01 "$release"
date_case month13 2026-11-20 2026-13-01
expect_exit 1 "month 13 fails" \
  bash "$script" --exceptions "$work/month13.md" --ignorefile "$work/one.trivyignore" --today 2026-12-01 "$release"
date_case words 2026-11-20 "next quarter"
expect_exit 1 "a review date in words fails" \
  bash "$script" --exceptions "$work/words.md" --ignorefile "$work/one.trivyignore" --today 2026-12-01 "$release"
date_case backwards 2026-11-20 2026-11-19
expect_output "review date 2026-11-19 is before the approval date 2026-11-20" "a review date before the approval fails" \
  bash "$script" --exceptions "$work/backwards.md" --ignorefile "$work/one.trivyignore" --today 2026-11-01 "$release"
expect_exit 1 "a review date before the approval fails" \
  bash "$script" --exceptions "$work/backwards.md" --ignorefile "$work/one.trivyignore" --today 2026-11-01 "$release"
date_case yearend 2026-12-15 2027-03-15
expect_exit 0 "90 days across the year end (2026-12-15 to 2027-03-15)" \
  bash "$script" --exceptions "$work/yearend.md" --ignorefile "$work/one.trivyignore" --today 2026-12-20 "$release"
expect_exit 0 "an empty exception table passes in fail mode" \
  bash "$script" "${ok_args[@]}" --review-dates fail --today 2030-01-01 "$release"
expect_exit 2 "--review-dates with another value: usage error" \
  bash "$script" "${ok_args[@]}" --review-dates ignore "$release"
expect_exit 2 "--today that is not a date: usage error" \
  bash "$script" "${ok_args[@]}" --today 2026-02-30 "$release"
expect_exit 2 "--today without a value: usage error" bash "$script" "${ok_args[@]}" "$release" --today

# The repository's own files (docs/security/cve-exceptions.md, .trivyignore) are consistent.
expect_exit 0 "repository cve-exceptions.md and .trivyignore are consistent (defaults)" bash "$script" "$release"
# Expiry against the real date is the weekly rescan's job; here only the form of the repository
# table's dates and the 90-day limit (a reference day before any approval).
expect_exit 0 "repository exception dates are well-formed and within 90 days of approval" \
  bash "$script" --review-dates fail --today 2000-01-01 "$release"

# --- scan (fake scanners) ----------------------------------------------------------------------
cat >"$work/scanner-ok.sh" <<'EOF'
#!/usr/bin/env bash
printf '%s|%s\n' "$(basename "$1")" "$(basename "$2")" >>"$SCAN_LOG"
EOF
cat >"$work/scanner-findings.sh" <<'EOF'
#!/usr/bin/env bash
case "$1" in *server*) echo "CVE-2026-99999 CRITICAL io.example:bad:1.0"; exit 1 ;; esac
exit 0
EOF
export SCAN_LOG="$work/scan.log"
: >"$SCAN_LOG"
expect_exit 0 "a scanner without findings passes" \
  bash "$script" "${ok_args[@]}" --scanner "bash $work/scanner-ok.sh" "$release"
expect_output "argos-agent-0.1.0.cdx.json|empty.trivyignore" "the scanner gets each SBOM and the ignore file" cat "$SCAN_LOG"
expect_exit 0 "the scanner ran once per SBOM (3 SBOMs, SHA256SUMS not scanned)" \
  bash -c "[[ \$(wc -l <'$SCAN_LOG' | tr -d ' ') -eq 3 ]]"
expect_exit 1 "a Critical/High finding (scanner exit 1) fails the check" \
  bash "$script" "${ok_args[@]}" --scanner "bash $work/scanner-findings.sh" "$release"
expect_output "FAIL  scan: argos-server-0.1.0.cdx.json: scanner exit 1" "the failing SBOM and the finding are shown" \
  bash "$script" "${ok_args[@]}" --scanner "bash $work/scanner-findings.sh" "$release"
expect_output "CVE-2026-99999 CRITICAL" "the scanner output is shown" \
  bash "$script" "${ok_args[@]}" --scanner "bash $work/scanner-findings.sh" "$release"

# --- usage ---------------------------------------------------------------------------------------
expect_exit 2 "no directory: usage error" bash "$script" "${ok_args[@]}"
expect_exit 2 "unknown option: usage error" bash "$script" --severity HIGH "$release"
expect_exit 2 "missing exception file: usage error" bash "$script" --exceptions "$work/nope.md" "$release"
expect_exit 2 "--scanner without a value: usage error" bash "$script" "$release" --scanner

finish
