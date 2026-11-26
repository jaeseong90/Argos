#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: O-G01, O-G02, O-G03, O-G04, O-G05, O-G06, O-S01, O-R11, SEC-90
# test-ids: T-BLD-026
#
# Project file set lint (test ID T-BLD-026, docs/18 appendix A; docs/19 sections 8-10, docs/16
# section 12):
#
#   file-set       every file of the docs/19 section 8 set exists and is not empty
#   templates      issue and discussion forms have the keys GitHub needs; security goes to PVR
#   headings       the heading contract of the WO-45 plan (CONTRIBUTING, SECURITY, GOVERNANCE,
#                  ROADMAP, RELEASING, CODE_OF_CONDUCT, docs/compatibility.md, docs/dev-setup.md
#                  sections 1-9 as CLAUDE.md cites them, docs/README.md English summary)
#   phrases        required statements (RELEASING: the ADR-0010 C-246 (13) maintainer steps;
#                  SECURITY: release key; GOVERNANCE: license will not change; CoC: Covenant 2.1)
#   code-anchors   "<FILE>.md#<anchor>" references in code resolve to a heading (AboutController
#                  links SECURITY.md#supported-versions)
#   personal-data  no e-mail address except example domains, noreply addresses and
#                  <project-domain> placeholders; no phone numbers
#   business       no business elements (pricing, editions, paid support, license keys, ...);
#                  the allow-list below names the legitimate contexts explicitly
#   links          relative Markdown links point to existing files (anchors are not checked)
#
# Usage: project-files.test.sh [<repository-root>]
#   Without an argument: checks this repository, then proves every guard on fixtures (a copy of
#   the project files with one injected violation each). With a root: only runs the checks against
#   it and prints one "ERROR [<check>] ..." line per problem (exit 0 = clean, 1 = problems).
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/../../.." && pwd)
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"
export LC_ALL=C

# docs/19 section 8 (plus the scripts that implement its label and branch protection rows).
required_files="LICENSE
NOTICE
README.md
README.ko.md
CONTRIBUTING.md
CODE_OF_CONDUCT.md
SECURITY.md
GOVERNANCE.md
ROADMAP.md
RELEASING.md
CLAUDE.md
CHANGELOG.md
.github/ISSUE_TEMPLATE/bug.yml
.github/ISSUE_TEMPLATE/feature.yml
.github/ISSUE_TEMPLATE/compat-report.yml
.github/ISSUE_TEMPLATE/config.yml
.github/DISCUSSION_TEMPLATE/q-a.yml
.github/DISCUSSION_TEMPLATE/ideas.yml
.github/DISCUSSION_TEMPLATE/show-and-tell.yml
.github/DISCUSSION_TEMPLATE/korean.yml
.github/PULL_REQUEST_TEMPLATE.md
.github/CODEOWNERS
.github/dependabot.yml
.github/labeler.yml
.github/scripts/create-labels.sh
.github/scripts/branch-protection.sh
docs/licenses/dev-only.md
docs/adr/0000-template.md
docs/adr/0001-license-apache-2.0.md
docs/compatibility.md
docs/dev-setup.md
keys/ARGOS-RELEASE-KEY.asc"

# Text files scanned by the personal-data, business and link guards (those that exist).
scanned_files="README.md
README.ko.md
CONTRIBUTING.md
CODE_OF_CONDUCT.md
SECURITY.md
GOVERNANCE.md
ROADMAP.md
RELEASING.md
CLAUDE.md
CHANGELOG.md
NOTICE
.github/ISSUE_TEMPLATE/bug.yml
.github/ISSUE_TEMPLATE/feature.yml
.github/ISSUE_TEMPLATE/compat-report.yml
.github/ISSUE_TEMPLATE/config.yml
.github/DISCUSSION_TEMPLATE/q-a.yml
.github/DISCUSSION_TEMPLATE/ideas.yml
.github/DISCUSSION_TEMPLATE/show-and-tell.yml
.github/DISCUSSION_TEMPLATE/korean.yml
.github/PULL_REQUEST_TEMPLATE.md
.github/CODEOWNERS
.github/dependabot.yml
.github/labeler.yml
docs/README.md
docs/compatibility.md
docs/dev-setup.md
docs/licenses/dev-only.md
docs/adr/0000-template.md
docs/adr/0001-license-apache-2.0.md
keys/README.md"

# Heading contract (WO-45 plan section 2): "<file>|<exact heading line>".
headings="CONTRIBUTING.md|## Before you start
CONTRIBUTING.md|## Developer Certificate of Origin (DCO)
CONTRIBUTING.md|## Building and testing
CONTRIBUTING.md|## Coding conventions
CONTRIBUTING.md|## Definition of done checklist
CONTRIBUTING.md|## Pull requests
CONTRIBUTING.md|## Changelog
CONTRIBUTING.md|## Adding an instrumentation module
CONTRIBUTING.md|## Dependencies and borrowed code
CONTRIBUTING.md|## AI-assisted contributions
CONTRIBUTING.md|## Core paths (\`needs-matrix\`)
CONTRIBUTING.md|## Response times
CONTRIBUTING.md|## License
SECURITY.md|## Supported versions
SECURITY.md|## Reporting a vulnerability
SECURITY.md|## What to expect
SECURITY.md|## Scope
SECURITY.md|## Hardening guidance
SECURITY.md|## Security release process
SECURITY.md|## Release signing
GOVERNANCE.md|## Maintainers
GOVERNANCE.md|## Decisions
GOVERNANCE.md|## How the project is developed
GOVERNANCE.md|## Repository settings
GOVERNANCE.md|## CI runners and secrets
GOVERNANCE.md|## Code of conduct
ROADMAP.md|## Phases
ROADMAP.md|## Milestones
ROADMAP.md|## Not planned for 1.0
RELEASING.md|## Versioning
RELEASING.md|## 0.x releases
RELEASING.md|## Before the release
RELEASING.md|## Release
RELEASING.md|## After the release
RELEASING.md|## Release key
RELEASING.md|## Backports and support
RELEASING.md|## Security releases
RELEASING.md|## Maintainer-only steps
CODE_OF_CONDUCT.md|## Enforcement
docs/compatibility.md|## Version policy
docs/compatibility.md|## Agent and server versions
docs/compatibility.md|## Public contracts
docs/compatibility.md|## Deprecation
docs/compatibility.md|## Database schema
docs/compatibility.md|## Supported environments
docs/compatibility.md|## How compatibility is checked
docs/dev-setup.md|## 1. Requirements
docs/dev-setup.md|## 2. Clone and build
docs/dev-setup.md|## 3. Tests
docs/dev-setup.md|## 4. Static checks
docs/dev-setup.md|## 5. CI checks you can run locally
docs/dev-setup.md|## 6. Running Argos locally
docs/dev-setup.md|## 7. Dependency changes
docs/dev-setup.md|## 8. IDE
docs/dev-setup.md|## 9. Before you open a pull request
docs/README.md|## English summary"

# Required statements: "<file>|<fixed string>". RELEASING carries the maintainer-only steps of
# ADR-0010 C-246 (13) (strict key check, 0.1.0 rehearsal, cosign and GHCR) and the real pipeline.
phrases="RELEASING.md|check-release-key.sh
RELEASING.md|0.1.0
RELEASING.md|cosign
RELEASING.md|GHCR
RELEASING.md|verify-release.sh
RELEASING.md|release.yml
SECURITY.md|Private Vulnerability Reporting
SECURITY.md|keys/ARGOS-RELEASE-KEY.asc
GOVERNANCE.md|will not change
CODE_OF_CONDUCT.md|Contributor Covenant
CODE_OF_CONDUCT.md|version 2.1
CONTRIBUTING.md|Signed-off-by"

# Code and configuration searched for "<FILE>.md#<anchor>" references to the root Markdown files.
anchor_sources="server/src/main
ui/src
agent
deploy
scripts"

email_re='[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}'
phone_re='(^|[^0-9A-Za-z.+/:_-])(\+[0-9]{1,3}[ .-]?)?(\(0?[0-9]{1,4}\)|0?[0-9]{2,3})[ .-][0-9]{3,4}[ .-][0-9]{4}([^0-9A-Za-z]|$)|tel:[^[:space:])>]*'
# OpenPGP fingerprints in groups of four hex digits are not phone numbers.
fingerprint_re='([0-9A-Fa-f]{4} +){4,}[0-9A-Fa-f]{4}'

# Business elements (CLAUDE.md: no sales, pricing, editions, license keys or paid features).
business_re='\bpric(e|es|ed|ing)\b|\bsales\b|\bresell(er|ers|ing)?\b|\benterprise (edition|tier|plan|version|licen[cs]e|support|features?)s?\b|\b(commercial|community|premium|pro|paid|business|standard) (edition|tier|plan)s?\b|\bpaid (support|features?|plans?|tiers?|versions?|add-ons?|licen[cs]es?|services?)\b|\blicen[cs]e[ -]?keys?\b|\bsubscriptions? (plans?|fees?|tiers?|licen[cs]es?|services?)\b|\bcommercial\b|\bsupport (contracts?|plans?|tiers?)\b|\bupgrade to (pro|premium|enterprise)\b|가격|유료|라이선스 ?키|에디션|구독료|영업|판로|요금제'
# Legitimate contexts, removed from a line before the business terms are searched:
#   "commercial products" / "commercial-product quality" / "commercial quality" (the quality goal
#   and the rule that code from commercial products is never copied), "commercial database(s)"
#   (test-only drivers in docs/licenses/dev-only.md), "영업일" (business days), and the CLAUDE.md
#   rule sentence that lists the forbidden elements.
business_allow_re='commercial[- ]products?|commercial[- ]quality|commercial (databases?|software|APMs?|tools?|vendors?)|영업일|영업·가격·판로·라이선스 키·에디션·유료 기능 같은 사업 요소'

# ---------------------------------------------------------------------------------------------
# Checks: each prints "ERROR [<check>] ..." lines and fails when it printed any.

check_file_set() {
  local root=$1 bad=0 f
  while IFS= read -r f; do
    if [[ ! -e $root/$f ]]; then
      echo "ERROR [file-set] $f is missing (docs/19 section 8)"
      bad=1
    elif [[ ! -s $root/$f ]]; then
      echo "ERROR [file-set] $f is empty"
      bad=1
    fi
  done <<<"$required_files"
  return "$bad"
}

check_templates() {
  local root=$1 bad=0 f key
  for f in bug feature compat-report; do
    f=.github/ISSUE_TEMPLATE/$f.yml
    [[ -f $root/$f ]] || continue
    for key in name description body; do
      grep -qE "^${key}:" "$root/$f" || { echo "ERROR [templates] $f: issue form has no top-level '${key}:'"; bad=1; }
    done
  done
  f=.github/ISSUE_TEMPLATE/config.yml
  if [[ -f $root/$f ]]; then
    grep -qE '^blank_issues_enabled:' "$root/$f" || { echo "ERROR [templates] $f: no blank_issues_enabled"; bad=1; }
    grep -qF '/security/advisories/new' "$root/$f" ||
      { echo "ERROR [templates] $f: no link to private vulnerability reporting (security/advisories/new)"; bad=1; }
  fi
  for f in "$root"/.github/DISCUSSION_TEMPLATE/*.yml; do
    [[ -f $f ]] || continue
    grep -qE '^body:' "$f" || { echo "ERROR [templates] ${f#"$root"/}: discussion form has no top-level 'body:'"; bad=1; }
  done
  return "$bad"
}

check_headings() {
  local root=$1 bad=0 f h cached='' found=''
  while IFS='|' read -r f h; do
    [[ -f $root/$f ]] || { echo "ERROR [headings] $f is missing (needs '$h')"; bad=1; continue; }
    if [[ $cached != "$f" ]]; then
      found=$'\n'$(grep -E '^#{1,6} ' "$root/$f" | tr -d '\r')$'\n'
      cached=$f
    fi
    if [[ $found != *$'\n'"$h"$'\n'* ]]; then
      echo "ERROR [headings] $f: missing heading '$h'"
      bad=1
    fi
  done <<<"$headings"
  return "$bad"
}

check_phrases() {
  local root=$1 bad=0 f p
  while IFS='|' read -r f p; do
    [[ -f $root/$f ]] || { echo "ERROR [phrases] $f is missing (needs '$p')"; bad=1; continue; }
    grep -qF -- "$p" "$root/$f" || { echo "ERROR [phrases] $f: does not mention '$p'"; bad=1; }
  done <<<"$phrases"
  return "$bad"
}

# slug <heading text>: GitHub anchor of a heading (REPLY).
slug() {
  local s=${1,,}
  s=${s//[^a-z0-9 _-]/}
  REPLY=${s// /-}
}

check_code_anchors() {
  local root=$1 bad=0 src hit loc ref file anchor found h
  for src in $anchor_sources; do
    [[ -d $root/$src ]] || continue
    while IFS= read -r hit; do
      loc=${hit%%:*}
      ref=${hit#*:}
      ref=${ref#*:}
      file=${ref%%#*}
      file=${file##*/}
      anchor=${ref#*#}
      [[ -f $root/$file ]] || continue
      found=false
      while IFS= read -r h; do
        slug "${h#\#* }"
        [[ $REPLY == "$anchor" ]] && found=true && break
      done < <(tr -d '\r' <"$root/$file" | grep -E '^#{1,6} ')
      if ! $found; then
        echo "ERROR [code-anchors] ${loc#"$root"/}: '$file#$anchor' has no matching heading in $file"
        bad=1
      fi
    done < <(grep -rnoE '[A-Za-z0-9_./-]*[A-Z][A-Z_]*\.md#[a-z0-9_-]+' "$root/$src" \
      --include='*.java' --include='*.ts' --include='*.tsx' --include='*.sh' --include='*.yaml' \
      --include='*.yml' --include='*.properties' --exclude-dir=node_modules --exclude-dir=build 2>/dev/null || true)
  done
  return "$bad"
}

# scanned_present <root>: SCANNED = the scanned files that exist under <root>.
scanned_present() {
  local f
  SCANNED=()
  while IFS= read -r f; do
    [[ -f $1/$f ]] && SCANNED+=("$f")
  done <<<"$scanned_files"
}

# grep_scanned <root> <grep options...>: grep over the scanned files, "<file>:<line>:<text>".
grep_scanned() {
  local root=$1
  shift
  scanned_present "$root"
  [[ ${#SCANNED[@]} -gt 0 ]] || return 0
  (cd "$root" && grep -H "$@" -- "${SCANNED[@]}") || true
}

check_personal_data() {
  local root=$1 bad=0 hit f rest ln m local_part domain
  while IFS= read -r hit; do
    [[ -n $hit ]] || continue
    f=${hit%%:*}
    rest=${hit#*:}
    ln=${rest%%:*}
    m=${rest#*:}
    local_part=${m%@*}
    domain=${m#*@}
    domain=${domain,,}
    case $domain in
      example.org | example.com | example.net | *.example.org | *.example.com | *.example.net) continue ;;
      users.noreply.github.com | noreply.github.com) continue ;;
    esac
    case ${local_part,,} in noreply | no-reply) continue ;; esac
    [[ $m == git@github.com ]] && continue
    echo "ERROR [personal-data] $f:$ln: e-mail address '$m' (use GitHub, a <project-domain> placeholder or example.org)"
    bad=1
  done < <(grep_scanned "$root" -noE "$email_re")
  # Phone numbers: candidate lines first (one grep), then the line without OpenPGP fingerprints.
  while IFS= read -r hit; do
    [[ -n $hit ]] || continue
    f=${hit%%:*}
    rest=${hit#*:}
    ln=${rest%%:*}
    while IFS= read -r m; do
      [[ -n $m ]] || continue
      [[ $m == tel:* ]] || m=$(sed -E 's/^[^0-9+(]+//; s/[^0-9]+$//' <<<"$m")
      echo "ERROR [personal-data] $f:$ln: phone number '$m'"
      bad=1
    done < <(sed -E "s/${fingerprint_re}//g" <<<"${rest#*:}" | grep -oE "$phone_re" || true)
  done < <(grep_scanned "$root" -nE "$phone_re")
  return "$bad"
}

check_business() {
  local root=$1 bad=0 hit f rest ln m
  # Candidate lines first (one grep), then the line without the allow-listed contexts.
  while IFS= read -r hit; do
    [[ -n $hit ]] || continue
    f=${hit%%:*}
    rest=${hit#*:}
    ln=${rest%%:*}
    while IFS= read -r m; do
      [[ -n $m ]] || continue
      echo "ERROR [business] $f:$ln: business element '$m' (CLAUDE.md: every feature is the same for every user)"
      bad=1
    done < <(sed -E "s/${business_allow_re}//gI" <<<"${rest#*:}" | grep -oiE "$business_re" || true)
  done < <(grep_scanned "$root" -niE "$business_re")
  return "$bad"
}

# norm_path <path>: lexical normalisation (REPLY); fails when it climbs above the root.
norm_path() {
  local IFS=/ p
  local -a parts out=()
  read -r -a parts <<<"$1"
  for p in "${parts[@]}"; do
    case $p in
      '' | .) ;;
      ..)
        [[ ${#out[@]} -gt 0 ]] || return 1
        unset 'out[${#out[@]}-1]'
        ;;
      *) out+=("$p") ;;
    esac
  done
  REPLY="${out[*]}"
}

# link_ok <root> <file> <target>: 0 when the relative link target exists (or is not checked).
link_ok() {
  local root=$1 file=$2 t=$3 dir
  t=${t#<}
  t=${t%>}
  t=${t%% *}
  [[ -z $t || $t == '#'* ]] && return 0
  [[ $t =~ ^[A-Za-z][A-Za-z0-9+.-]*: ]] && return 0
  t=${t%%#*}
  t=${t%%\?*}
  [[ -z $t ]] && return 0
  t=${t//%20/ }
  if [[ $t == /* ]]; then
    norm_path "$t" || return 1
  else
    dir=''
    [[ $file == */* ]] && dir=${file%/*}
    norm_path "${dir:+$dir/}$t" || return 1
  fi
  [[ -e $root/$REPLY ]]
}

check_links() {
  local root=$1 bad=0 f n line rest target
  while IFS= read -r f; do
    [[ $f == *.md && -f $root/$f ]] || continue
    # Candidate lines only ("<n>\t<line>"): outside fenced code blocks, code spans removed, with an
    # inline link or a reference definition. One awk per file keeps this fast on Git Bash.
    while IFS=$'\t' read -r n line; do
      rest=$line
      while [[ $rest =~ \]\(([^\)]*)\) ]]; do
        target=${BASH_REMATCH[1]}
        rest=${rest#*"${BASH_REMATCH[0]}"}
        if ! link_ok "$root" "$f" "$target"; then
          echo "ERROR [links] $f:$n: link target '$target' does not exist"
          bad=1
        fi
      done
      if [[ $line =~ ^\[[^]]+\]:[[:space:]]+([^[:space:]]+) ]]; then
        target=${BASH_REMATCH[1]}
        if ! link_ok "$root" "$f" "$target"; then
          echo "ERROR [links] $f:$n: link target '$target' does not exist"
          bad=1
        fi
      fi
    done < <(awk '
      { sub(/\r$/, "") }
      /^[ \t]*(```|~~~)/ { fence = !fence; next }
      fence { next }
      {
        gsub(/`[^`]*`/, "")
        if (index($0, "](") > 0 || $0 ~ /^\[[^]]+\]:[ \t]/) print NR "\t" $0
      }' "$root/$f")
  done <<<"$scanned_files"
  return "$bad"
}

all_checks="check_file_set check_templates check_headings check_phrases check_code_anchors check_personal_data check_business check_links"

run_checks() {
  local rc=0 c
  for c in $all_checks; do
    "$c" "$1" || rc=1
  done
  return "$rc"
}

if [[ $# -gt 0 ]]; then
  [[ -d $1 ]] || { echo "usage: project-files.test.sh [<repository-root>]" >&2; exit 2; }
  run_checks "$1"
  exit $?
fi

# ---------------------------------------------------------------------------------------------
# 1. This repository.
expect_exit 0 "file set: every docs/19 section 8 file exists and is not empty" check_file_set "$repo"
expect_exit 0 "templates: issue and discussion forms are complete, security goes to PVR" check_templates "$repo"
expect_exit 0 "headings: the WO-45 heading contract holds" check_headings "$repo"
expect_exit 0 "phrases: RELEASING (C-246 (13)), SECURITY, GOVERNANCE, CoC, CONTRIBUTING statements" check_phrases "$repo"
expect_exit 0 "code anchors: <FILE>.md#<anchor> references in code resolve" check_code_anchors "$repo"
expect_exit 0 "personal data: no e-mail address or phone number" check_personal_data "$repo"
expect_exit 0 "business: no pricing, editions, paid support or license keys" check_business "$repo"
expect_exit 0 "links: relative Markdown links resolve" check_links "$repo"

# 2. Fixtures: a copy of the project files, one injected violation per case.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

make_fixture() {
  local d=$work/$1 f
  while IFS= read -r f; do
    [[ -e $repo/$f ]] || continue
    mkdir -p "$(dirname "$d/$f")"
    cp "$repo/$f" "$d/$f"
  done < <(printf '%s\n%s\nserver/src/main/java/io/argos/server/api/about/AboutController.java\n' \
    "$required_files" "$scanned_files" | sort -u)
  printf '%s' "$d"
}

expect_no_output() {
  local needle=$1 desc=$2 out
  shift 2
  out=$("$@" 2>&1 || true)
  TESTS_RUN=$((TESTS_RUN + 1))
  if [[ "$out" != *"$needle"* ]]; then
    echo "PASS  $desc"
  else
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo "FAIL  $desc (output contains '$needle')"
    printf '%s\n' "$out" | grep -F -- "$needle" | sed 's/^/      | /'
  fi
}

self=$here/project-files.test.sh

# fx <fixture>: runs the checks against a fixture once and prints the cached output (the cases of
# one fixture assert on the same run).
fx() {
  [[ -f $1.out ]] || bash "$self" "$1" >"$1.out" 2>&1 || true
  cat "$1.out"
}

f=$(make_fixture missing)
rm "$f/CONTRIBUTING.md" "$f/keys/ARGOS-RELEASE-KEY.asc"
: >"$f/ROADMAP.md"
expect_output "ERROR [file-set] CONTRIBUTING.md is missing" "fixture: a missing project file is reported" fx "$f"
expect_output "ERROR [file-set] keys/ARGOS-RELEASE-KEY.asc is missing" "fixture: a missing release key is reported" fx "$f"
expect_output "ERROR [file-set] ROADMAP.md is empty" "fixture: an empty project file is reported" fx "$f"

f=$(make_fixture templates)
sed -i '/^body:/d' "$f/.github/ISSUE_TEMPLATE/bug.yml" "$f/.github/DISCUSSION_TEMPLATE/ideas.yml"
sed -i '/security\/advisories\/new/d' "$f/.github/ISSUE_TEMPLATE/config.yml"
expect_output "bug.yml: issue form has no top-level 'body:'" "fixture: an incomplete issue form is reported" fx "$f"
expect_output "ideas.yml: discussion form has no top-level 'body:'" "fixture: an incomplete discussion form is reported" fx "$f"
expect_output "config.yml: no link to private vulnerability reporting" "fixture: the PVR contact link is required" fx "$f"

f=$(make_fixture headings)
sed -i 's/^## Supported versions$/## Supported releases/' "$f/SECURITY.md"
sed -i 's/^## 5\. CI checks you can run locally$/## 5. CI/' "$f/docs/dev-setup.md"
sed -i '/^## English summary$/d' "$f/docs/README.md"
expect_output "SECURITY.md: missing heading '## Supported versions'" "fixture: a renamed SECURITY heading is reported" fx "$f"
expect_output "docs/dev-setup.md: missing heading '## 5. CI checks you can run locally'" \
  "fixture: a renamed dev-setup section (cited by CLAUDE.md) is reported" fx "$f"
expect_output "docs/README.md: missing heading '## English summary'" "fixture: the English summary is required" fx "$f"
expect_output "AboutController.java:" "fixture: code anchor SECURITY.md#supported-versions breaks with the heading" fx "$f"
expect_output "'SECURITY.md#supported-versions' has no matching heading" "fixture: the broken anchor is named" fx "$f"

f=$(make_fixture anchors-ok)
grep -qxF '## English summary' "$f/docs/README.md" || printf '\n## English summary\n' >>"$f/docs/README.md"
expect_no_output "ERROR [code-anchors]" "fixture: an unchanged SECURITY.md satisfies the code anchor" fx "$f"
expect_no_output "docs/README.md: missing heading" "fixture: '## English summary' satisfies the docs/README.md contract" fx "$f"

f=$(make_fixture phrases)
sed -i 's/cosign/c0sign/g; s/check-release-key\.sh/check-key.sh/g' "$f/RELEASING.md"
sed -i 's/will not change/stays/g' "$f/GOVERNANCE.md"
expect_output "RELEASING.md: does not mention 'cosign'" "fixture: RELEASING without the cosign step is reported" fx "$f"
expect_output "RELEASING.md: does not mention 'check-release-key.sh'" "fixture: RELEASING without the strict key check is reported" fx "$f"
expect_output "GOVERNANCE.md: does not mention 'will not change'" "fixture: GOVERNANCE without the license statement is reported" fx "$f"

f=$(make_fixture personal)
printf '\nContact jane.doe@gmail.com or call +82 10-1234-5678 or 010-1234-5678 or (555) 123-4567.\n' >>"$f/CONTRIBUTING.md"
printf '\nCall tel:+15551234567 for help.\n' >>"$f/README.md"
printf '\nOK: dev@example.org, noreply@anthropic.com, 1234+someone@users.noreply.github.com, security@<project-domain>.\n' >>"$f/SECURITY.md"
printf '\nFingerprint: 1234 5678 9012 3456 7890  1234 5678 9012 3456 7890\nDate 2026-11-26, port 8080, version 1.20.3\n' >>"$f/SECURITY.md"
expect_output "e-mail address 'jane.doe@gmail.com'" "fixture: a personal e-mail address is reported" fx "$f"
expect_output "phone number '+82 10-1234-5678" "fixture: an international phone number is reported" fx "$f"
expect_output "phone number '010-1234-5678" "fixture: a national phone number is reported" fx "$f"
expect_output "phone number '(555) 123-4567" "fixture: a phone number with an area code in parentheses is reported" fx "$f"
expect_output "phone number 'tel:+15551234567'" "fixture: a tel: link is reported" fx "$f"
expect_no_output "ERROR [personal-data] SECURITY.md" \
  "fixture: example.org, noreply, users.noreply.github.com, <project-domain>, fingerprints and dates pass" fx "$f"

f=$(make_fixture business)
printf '\nThe Enterprise Edition adds SSO.\nPaid support is available.\nEnter your license key.\nSee pricing.\nSubscription plans start soon.\n' >>"$f/GOVERNANCE.md"
printf '\n유료 기능은 별도입니다.\n' >>"$f/README.ko.md"
printf '\nA commercial edition exists.\n' >>"$f/ROADMAP.md"
printf '\nCode from commercial products is never copied. Acknowledged within 3 business days. 3영업일.\nUp to 20 subscriptions per connection. The MSSQL Developer edition image.\n' >>"$f/CONTRIBUTING.md"
expect_output "business element 'Enterprise Edition'" "fixture: an enterprise edition is reported" fx "$f"
expect_output "business element 'Paid support'" "fixture: paid support is reported" fx "$f"
expect_output "business element 'license key'" "fixture: a license key is reported" fx "$f"
expect_output "business element 'pricing'" "fixture: pricing is reported" fx "$f"
expect_output "business element 'Subscription plans'" "fixture: a subscription plan is reported" fx "$f"
expect_output "ERROR [business] README.ko.md:" "fixture: Korean business terms are reported" fx "$f"
expect_output "ERROR [business] ROADMAP.md:" "fixture: 'commercial' outside the allow-list is reported" fx "$f"
expect_no_output "ERROR [business] CONTRIBUTING.md" \
  "fixture: allow-listed contexts (commercial products, business days, subscriptions per connection) pass" fx "$f"

f=$(make_fixture links)
# shellcheck disable=SC2016 # literal Markdown with backticks
printf '%s\n' '' '[ok](CONTRIBUTING.md#license) and [dir](docs/) and [ext](https://example.org/x) and [mail](mailto:dev@example.org)' \
  '[bad](docs/does-not-exist.md)' '[root](/docs/root-missing.md)' '[up](../outside.md)' \
  '```' '[fenced](docs/fenced-missing.md)' '```' 'Code `[span](docs/span-missing.md)` only.' \
  '[ref]: docs/ref-missing.md' >>"$f/GOVERNANCE.md"
expect_output "link target 'docs/does-not-exist.md' does not exist" "fixture: a broken relative link is reported" fx "$f"
expect_output "link target '/docs/root-missing.md' does not exist" "fixture: a broken root-relative link is reported" fx "$f"
expect_output "link target '../outside.md' does not exist" "fixture: a link above the repository root is reported" fx "$f"
expect_output "link target 'docs/ref-missing.md' does not exist" "fixture: a broken reference-style link is reported" fx "$f"
expect_no_output "CONTRIBUTING.md#license" "fixture: anchors are ignored, the file exists" fx "$f"
expect_no_output "example.org/x" "fixture: external links are not checked" fx "$f"
expect_no_output "fenced-missing" "fixture: links in fenced code blocks are ignored" fx "$f"
expect_no_output "span-missing" "fixture: links in code spans are ignored" fx "$f"

finish
