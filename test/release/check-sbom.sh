#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# check-sbom.sh: release SBOM gate (T-BLD-008, SEC-70, SEC-71, ST-71-2; 16 §10.1-10.2).
#
#   sbom-format      every <dir>/*.cdx.json is CycloneDX JSON (bomFormat CycloneDX, specVersion 1.x)
#                    with at least one component, every component named
#   sbom-parts       with --require agent,server,ui: argos-<part>-*.cdx.json exists for each part
#   exceptions       the CVE/GHSA IDs of the scanner ignore file (default .trivyignore) are exactly
#                    the IDs of the table in docs/security/cve-exceptions.md (both directions), and
#                    every table row has all seven columns filled
#   review-dates     every table row has valid `Approved on` and `Review by` dates (YYYY-MM-DD),
#                    review on or after approval and at most 90 days after it (approval procedure,
#                    step 5); a row whose review date is before today (UTC, or --today) prints one
#                    WARN line with --review-dates warn (the default) and fails with
#                    --review-dates fail (the weekly rescan, .github/workflows/sbom-rescan.yml)
#   scan             with --scanner <command>: the command is run once per SBOM as
#                    `<command> <sbom> <ignorefile>`; a non-zero exit fails the check (findings of
#                    severity Critical or High that are not in the exception file, or a scanner
#                    error). test/release/trivy-scan.sh is the digest-pinned Trivy scanner.
#
# Usage:  check-sbom.sh [--scanner <command>] [--require <parts>] [--exceptions <md>]
#                       [--ignorefile <file>] [--review-dates warn|fail] [--today YYYY-MM-DD] <dir>
# Needs:  Node.js (JSON parsing).
# Exit:   0 all checks pass, 1 a check failed, 2 usage error.

set -euo pipefail
export LC_ALL=C

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$script_dir/../.." && pwd)"

usage() {
  sed -n '/^# Usage:/,/^# Exit:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

scanner=""
require=""
exceptions="$root/docs/security/cve-exceptions.md"
ignorefile="$root/.trivyignore"
review_dates="warn"
today=""
dir=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --scanner | --require | --exceptions | --ignorefile | --review-dates | --today)
      [[ $# -ge 2 && -n "$2" ]] || { echo "check-sbom: $1 needs a value" >&2; usage; }
      case "$1" in
        --scanner) scanner="$2" ;;
        --require) require="$2" ;;
        --exceptions) exceptions="$2" ;;
        --ignorefile) ignorefile="$2" ;;
        --review-dates) review_dates="$2" ;;
        --today) today="$2" ;;
      esac
      shift 2
      ;;
    -h | --help) usage ;;
    -*)
      echo "check-sbom: unknown option: $1" >&2
      usage
      ;;
    *)
      [[ -z "$dir" ]] || { echo "check-sbom: only one directory" >&2; usage; }
      dir="$1"
      shift
      ;;
  esac
done
[[ -n "$dir" ]] || { echo "check-sbom: missing <dir>" >&2; usage; }
[[ -d "$dir" ]] || { echo "check-sbom: not a directory: $dir" >&2; usage; }
[[ -f "$exceptions" ]] || { echo "check-sbom: exception file not found: $exceptions" >&2; usage; }
[[ -f "$ignorefile" ]] || { echo "check-sbom: ignore file not found: $ignorefile" >&2; usage; }
case "$review_dates" in
  warn | fail) ;;
  *) echo "check-sbom: --review-dates must be warn or fail: $review_dates" >&2; usage ;;
esac
command -v node >/dev/null 2>&1 || { echo "check-sbom: Node.js is needed to read the SBOMs" >&2; exit 2; }

# day_number <YYYY-MM-DD>: prints the day number of a valid calendar date (days since 1970-01-01,
# proleptic Gregorian) and returns 0; returns 1 for anything else. Pure bash: GNU and BSD date differ.
day_number() {
  local s=$1 y m d dim era yoe doy doe
  [[ "$s" =~ ^([0-9]{4})-([0-9]{2})-([0-9]{2})$ ]] || return 1
  y=$((10#${BASH_REMATCH[1]}))
  m=$((10#${BASH_REMATCH[2]}))
  d=$((10#${BASH_REMATCH[3]}))
  ((m >= 1 && m <= 12 && d >= 1)) || return 1
  case $m in
    4 | 6 | 9 | 11) dim=30 ;;
    2) if (((y % 4 == 0 && y % 100 != 0) || y % 400 == 0)); then dim=29; else dim=28; fi ;;
    *) dim=31 ;;
  esac
  ((d <= dim)) || return 1
  # days_from_civil (H. Hinnant)
  ((m <= 2)) && y=$((y - 1))
  era=$((y / 400))
  yoe=$((y - era * 400))
  if ((m > 2)); then doy=$(((153 * (m - 3) + 2) / 5 + d - 1)); else doy=$(((153 * (m + 9) + 2) / 5 + d - 1)); fi
  doe=$((yoe * 365 + yoe / 4 - yoe / 100 + doy))
  echo $((era * 146097 + doe - 719468))
}

if [[ -z "$today" ]]; then
  today="$(date -u +%Y-%m-%d)"
fi
today_day="$(day_number "$today")" || { echo "check-sbom: --today must be a date YYYY-MM-DD: $today" >&2; usage; }
readonly MAX_REVIEW_DAYS=90

failures=0
fail() {
  echo "FAIL  $*"
  failures=$((failures + 1))
}
pass() { echo "PASS  $*"; }

# --- sbom-format -------------------------------------------------------------------------------
shopt -s nullglob
sboms=("$dir"/*.cdx.json)
shopt -u nullglob
if [[ ${#sboms[@]} -eq 0 ]]; then
  fail "sbom-format: no *.cdx.json in $dir"
fi
for sbom in "${sboms[@]}"; do
  name="$(basename "$sbom")"
  # The SBOM is read from stdin: no path conversion between Git Bash and node on Windows.
  if verdict="$(node -e '
    let text = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", (d) => (text += d)).on("end", () => {
      let bom;
      try { bom = JSON.parse(text); } catch (e) { console.log("not JSON: " + e.message); process.exit(1); }
      if (bom === null || typeof bom !== "object") { console.log("not a JSON object"); process.exit(1); }
      if (bom.bomFormat !== "CycloneDX") { console.log("bomFormat is " + JSON.stringify(bom.bomFormat)); process.exit(1); }
      if (typeof bom.specVersion !== "string" || !/^1\.\d+$/.test(bom.specVersion)) {
        console.log("specVersion is " + JSON.stringify(bom.specVersion)); process.exit(1);
      }
      const components = Array.isArray(bom.components) ? bom.components : [];
      if (components.length === 0) { console.log("no components"); process.exit(1); }
      const unnamed = components.filter((c) => !c || typeof c.name !== "string" || c.name === "").length;
      if (unnamed > 0) { console.log(unnamed + " component(s) without a name"); process.exit(1); }
      console.log("CycloneDX " + bom.specVersion + ", " + components.length + " components");
    });' <"$sbom" 2>&1)"; then
    pass "sbom-format: $name: $verdict"
  else
    fail "sbom-format: $name: $verdict"
  fi
done

# --- sbom-parts --------------------------------------------------------------------------------
if [[ -n "$require" ]]; then
  IFS=',' read -r -a parts <<<"$require"
  for part in "${parts[@]}"; do
    part="${part// /}"
    [[ -n "$part" ]] || continue
    shopt -s nullglob
    matches=("$dir"/argos-"$part"-*.cdx.json)
    shopt -u nullglob
    if [[ ${#matches[@]} -eq 1 ]]; then
      pass "sbom-parts: $part: $(basename "${matches[0]}")"
    else
      fail "sbom-parts: $part: expected one argos-$part-*.cdx.json, found ${#matches[@]}"
    fi
  done
fi

# --- exceptions --------------------------------------------------------------------------------
id_re='^(CVE-[0-9]{4}-[0-9]{4,}|GHSA-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4})$'
ignore_base="$(basename "$ignorefile")"
exceptions_base="$(basename "$exceptions")"
work="$(mktemp -d "${TMPDIR:-/tmp}/argos-check-sbom.XXXXXX")"
trap 'rm -rf "$work"' EXIT
: >"$work/ignore.txt"
: >"$work/table.txt"
bad_ignore=0
date_rows=0
date_failures=0
expired=0
# check_review_dates <id> <approved on> <review by>: the review-dates check of one complete row.
check_review_dates() {
  local id=$1 approved=$2 review=$3 approved_day review_day
  date_rows=$((date_rows + 1))
  if ! approved_day="$(day_number "$approved")"; then
    fail "review-dates: $id: 'Approved on' is not a date YYYY-MM-DD: $approved"
    date_failures=$((date_failures + 1))
    return
  fi
  if ! review_day="$(day_number "$review")"; then
    fail "review-dates: $id: 'Review by' is not a date YYYY-MM-DD: $review"
    date_failures=$((date_failures + 1))
    return
  fi
  if ((review_day < approved_day)); then
    fail "review-dates: $id: review date $review is before the approval date $approved"
    date_failures=$((date_failures + 1))
  elif ((review_day - approved_day > MAX_REVIEW_DAYS)); then
    fail "review-dates: $id: review date $review is $((review_day - approved_day)) days after the approval date $approved (at most $MAX_REVIEW_DAYS)"
    date_failures=$((date_failures + 1))
  fi
  if ((review_day < today_day)); then
    expired=$((expired + 1))
    if [[ "$review_dates" == "fail" ]]; then
      fail "review-dates: $id: review date $review has passed (today $today); renew or remove the exception"
      date_failures=$((date_failures + 1))
    else
      echo "WARN  review-dates: $id: review date $review has passed (today $today); renew or remove the exception"
    fi
  fi
}
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%$'\r'}"
  line="${line%%#*}"
  line="$(printf '%s' "$line" | tr -d '[:space:]')"
  [[ -z "$line" ]] && continue
  if [[ "$line" =~ $id_re ]]; then
    echo "$line" >>"$work/ignore.txt"
  else
    fail "exceptions: $ignore_base: not a CVE or GHSA ID: $line"
    bad_ignore=1
  fi
done <"$ignorefile"
# Table rows: lines starting with '|' after the header separator; the first cell is the ID.
in_table=0
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%$'\r'}"
  if [[ "$line" =~ ^\|[[:space:]:-]+\|[[:space:]:|-]*$ ]]; then
    in_table=1
    continue
  fi
  if ((in_table)) && [[ "$line" == \|* ]]; then
    IFS='|' read -r -a cells <<<"${line#|}"
    id="$(printf '%s' "${cells[0]:-}" | tr -d '[:space:]')"
    filled=0
    for cell in "${cells[@]:0:7}"; do
      [[ -n "$(printf '%s' "$cell" | tr -d '[:space:]')" ]] && filled=$((filled + 1))
    done
    if [[ ! "$id" =~ $id_re ]]; then
      fail "exceptions: $exceptions_base: first column is not a CVE or GHSA ID: $line"
    elif ((filled < 7)); then
      fail "exceptions: $exceptions_base: $id: every column must be filled (7 columns)"
      echo "$id" >>"$work/table.txt"
    else
      echo "$id" >>"$work/table.txt"
      check_review_dates "$id" "$(printf '%s' "${cells[5]}" | tr -d '[:space:]')" \
        "$(printf '%s' "${cells[6]}" | tr -d '[:space:]')"
    fi
  elif ((in_table)); then
    in_table=0
  fi
done <"$exceptions"
sort -u "$work/ignore.txt" -o "$work/ignore.txt"
sort -u "$work/table.txt" -o "$work/table.txt"
only_ignore="$(comm -23 "$work/ignore.txt" "$work/table.txt" | tr '\n' ' ')"
only_table="$(comm -13 "$work/ignore.txt" "$work/table.txt" | tr '\n' ' ')"
if [[ -n "$only_ignore" ]]; then
  fail "exceptions: in $ignore_base but not in $exceptions_base: $only_ignore"
fi
if [[ -n "$only_table" ]]; then
  fail "exceptions: in $exceptions_base but not in $ignore_base: $only_table"
fi
if [[ -z "$only_ignore" && -z "$only_table" && $bad_ignore -eq 0 ]]; then
  pass "exceptions: $ignore_base = $exceptions_base ($(wc -l <"$work/ignore.txt" | tr -d ' ') ID(s))"
fi
if ((date_failures == 0 && expired == 0)); then
  pass "review-dates: $date_rows exception(s), none past its review date (today $today, --review-dates $review_dates)"
fi

# --- scan --------------------------------------------------------------------------------------
if [[ -n "$scanner" ]]; then
  # shellcheck disable=SC2206 # the scanner command is split into words on purpose
  scanner_cmd=($scanner)
  for sbom in "${sboms[@]}"; do
    name="$(basename "$sbom")"
    status=0
    "${scanner_cmd[@]}" "$sbom" "$ignorefile" >"$work/scan.out" 2>&1 || status=$?
    if ((status == 0)); then
      pass "scan: $name: no Critical or High finding outside the exception file"
    else
      fail "scan: $name: scanner exit $status (Critical or High findings, or a scanner error)"
      sed 's/^/      | /' "$work/scan.out" | tail -n 60
    fi
  done
else
  echo "SKIP  scan: no --scanner given"
fi

if ((failures > 0)); then
  echo "check-sbom: FAILED ($failures check(s))"
  exit 1
fi
echo "check-sbom: OK"
