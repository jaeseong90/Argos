#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# check-dependency-verification.sh - the dependency verification and lock file policy stays enforced
# (SEC-73, 16 section 10.4, docs/19 section 3; proposed test ID T-SEC-73a).
#
# Usage: check-dependency-verification.sh [ROOT]     (ROOT defaults to the repository root)
#
# Checks:
#   V1  gradle/verification-metadata.xml exists, has <verify-metadata>true</verify-metadata>, and every
#       <artifact> entry carries a sha256 checksum;
#   V2  every <trust> entry of <trusted-artifacts> is in TRUSTED_ARTIFACTS below with a reason (a
#       trusted artifact is not verified at all);
#   V3  no file that runs or configures builds (gradle.properties, *.gradle(.kts), shell scripts,
#       workflow and action files) switches verification to lenient or off
#       (org.gradle.dependency.verification, --dependency-verification, -F, DependencyVerificationMode)
#       or regenerates the metadata (--write-verification-metadata, -M): regeneration is a maintainer
#       step with an empty cache in a Linux container (docs/dev-setup.md section 7), never part of CI
#       or of a script; documentation (docs/, *.md) is not checked;
#   V4  every `pnpm install` (or `pnpm i`) in workflows, actions and shell scripts and the pnpm install
#       of the Gradle build pass --frozen-lockfile, and --no-frozen-lockfile appears nowhere;
#   V5  no .npmrc or pnpm-workspace.yaml turns the frozen lockfile off.
# This script and its test (.github/scripts/test/dependency-verification.test.sh) contain the
# patterns as data and are not scanned.
#
# Exit codes: 0 no finding, 1 findings (one line each on stdout), 2 usage error.
set -euo pipefail

# Trusted artifacts that are allowed without verification: "<trust element attributes>|<reason>".
# The attributes are compared as written in the file.
TRUSTED_ARTIFACTS=(
  'file=".*-javadoc[.]jar" regex="true"|IDE documentation jars; never on a build or runtime classpath'
  'file=".*-sources[.]jar" regex="true"|IDE source jars; never on a build or runtime classpath'
)

self_files=(
  .github/scripts/check-dependency-verification.sh
  .github/scripts/test/dependency-verification.test.sh
)

usage() {
  echo "usage: check-dependency-verification.sh [ROOT]" >&2
  exit 2
}

case ${1:-} in
  -h | --help)
    sed -n '4,27p' "$0"
    exit 0
    ;;
  -*) usage ;;
esac
[[ $# -le 1 ]] || usage
if [[ $# -eq 1 ]]; then
  root=$1
else
  root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
fi
[[ -d $root ]] || {
  echo "check-dependency-verification: '$root' is not a directory" >&2
  exit 2
}
root=$(cd "$root" && pwd)

findings=0
finding() {
  echo "$1"
  findings=$((findings + 1))
}

# ---------------------------------------------------------------------------------------------
# V1, V2: verification metadata.
metadata="$root/gradle/verification-metadata.xml"
if [[ ! -f $metadata ]]; then
  finding "gradle/verification-metadata.xml: missing (V1)"
else
  grep -qE '<verify-metadata>[[:space:]]*true[[:space:]]*</verify-metadata>' "$metadata" ||
    finding "gradle/verification-metadata.xml: <verify-metadata> is not true (V1)"
  grep -q '<sha256 ' "$metadata" || finding "gradle/verification-metadata.xml: no sha256 checksums (V1)"
  while IFS= read -r line; do
    finding "gradle/verification-metadata.xml:${line} (V1)"
  done < <(awk '
    /<artifact[ >]/ {
      if ($0 ~ /\/>[ \t]*$/) { name = $0; sub(/.*name="/, "", name); sub(/".*/, "", name); print NR ": artifact " name " has no sha256 checksum"; next }
      inart = 1; has = 0; start = NR; name = $0; sub(/.*name="/, "", name); sub(/".*/, "", name)
    }
    inart && /<sha256 / { has = 1 }
    inart && /<\/artifact>/ { if (!has) print start ": artifact " name " has no sha256 checksum"; inart = 0 }
  ' "$metadata")
  while IFS=$'\t' read -r n attrs; do
    allowed=0
    for entry in "${TRUSTED_ARTIFACTS[@]}"; do
      if [[ ${entry%%|*} == "$attrs" && -n ${entry#*|} ]]; then allowed=1; fi
    done
    [[ $allowed -eq 1 ]] ||
      finding "gradle/verification-metadata.xml:${n}: trusted artifact <trust ${attrs}/> is not in TRUSTED_ARTIFACTS with a reason (V2)"
  done < <(awk '
    /<trusted-artifacts>/ { on = 1 }
    on && /<trust / { a = $0; sub(/^.*<trust[ \t]+/, "", a); sub(/[ \t]*\/?>.*$/, "", a); print NR "\t" a }
    /<\/trusted-artifacts>/ { on = 0 }
  ' "$metadata")
fi

# ---------------------------------------------------------------------------------------------
# Files to scan: tracked and untracked (not ignored) files in a Git work tree, otherwise all files
# outside build output and dependency directories.
list_files() {
  local top
  top=$(git -C "$root" rev-parse --show-toplevel 2>/dev/null || true)
  if [[ -n $top && $(cd "$top" && pwd) == "$root" ]]; then
    git -C "$root" ls-files --cached --others --exclude-standard
  else
    (cd "$root" && find . \( -name .git -o -name node_modules -o -name build -o -name .gradle \) -prune -o -type f -print |
      sed 's|^\./||')
  fi
}

build_files=()
npm_config_files=()
while IFS= read -r f; do
  [[ -f "$root/$f" ]] || continue
  skip=0
  for s in "${self_files[@]}"; do [[ $f != "$s" ]] || skip=1; done
  [[ $skip -eq 0 ]] || continue
  case $f in
    docs/* | *.md) continue ;;
  esac
  case $f in
    gradle.properties | */gradle.properties | *.gradle.kts | *.gradle | *.sh | *.bash | gradlew | \
      .github/workflows/*.yml | .github/workflows/*.yaml | .github/actions/*.yml | .github/actions/*.yaml)
      build_files+=("$f")
      ;;
  esac
  case $f in
    .npmrc | */.npmrc | pnpm-workspace.yaml | */pnpm-workspace.yaml) npm_config_files+=("$f") ;;
  esac
done < <(list_files | sort -u)

# scan <awk program> <files...>: runs the program per file (FILENAME relative to ROOT); each printed
# line is a finding.
scan() {
  local program=$1
  shift
  [[ $# -gt 0 ]] || return 0
  (cd "$root" && awk "$program" "$@")
}

if [[ ${#build_files[@]} -gt 0 ]]; then
  # shellcheck disable=SC2016 # an awk program, not shell expansions
  while IFS= read -r line; do finding "$line"; done < <(scan '
    { t = $0 }
    t ~ /org\.gradle\.dependency\.verification[ \t]*[=:][ \t]*"?(lenient|off)/ {
      print FILENAME ":" FNR ": dependency verification switched off or to lenient (V3)"; next
    }
    t ~ /--dependency-verification([ \t]*=|[ \t]+)"?(lenient|off)/ {
      print FILENAME ":" FNR ": --dependency-verification lenient/off (V3)"; next
    }
    t ~ /gradlew.*[ \t]-F[ \t]*"?(lenient|off)/ {
      print FILENAME ":" FNR ": gradlew -F lenient/off (V3)"; next
    }
    t ~ /DependencyVerificationMode\.(LENIENT|OFF)/ {
      print FILENAME ":" FNR ": DependencyVerificationMode.LENIENT/OFF (V3)"; next
    }
    t ~ /--write-verification-metadata/ || t ~ /gradlew.*[ \t]-M[ \t]+(sha256|sha512|pgp)/ {
      print FILENAME ":" FNR ": verification metadata regenerated by a script or workflow (V3; docs/dev-setup.md section 7)"; next
    }
    t ~ /--no-frozen-lockfile/ {
      print FILENAME ":" FNR ": --no-frozen-lockfile (V4)"; next
    }
    t ~ /(^|[^A-Za-z0-9_.-])pnpm([ \t]+(-C|--dir|--filter|-F|-w|--workspace-root|--prefix)[ \t]+[^ \t]+|[ \t]+(-r|--recursive|-w|--workspace-root|--silent|-s))*[ \t]+(install|i)([ \t;&|)]|$)/ && t !~ /--frozen-lockfile/ {
      print FILENAME ":" FNR ": pnpm install without --frozen-lockfile (V4)"; next
    }
    FILENAME ~ /\.gradle\.kts$/ && t ~ /[Pp]npm/ && t ~ /"install"/ && t !~ /"--frozen-lockfile"/ {
      print FILENAME ":" FNR ": Gradle pnpm install without --frozen-lockfile (V4)"; next
    }
  ' "${build_files[@]}")
fi

if [[ ${#npm_config_files[@]} -gt 0 ]]; then
  # shellcheck disable=SC2016 # an awk program, not shell expansions
  while IFS= read -r line; do finding "$line"; done < <(scan '
    { t = $0; sub(/[ \t]*[#;].*$/, "", t) }
    t ~ /^[ \t]*(prefer-)?frozen-lockfile[ \t]*=[ \t]*false/ ||
      t ~ /^[ \t]*(preferFrozenLockfile|frozenLockfile)[ \t]*:[ \t]*false/ {
      print FILENAME ":" FNR ": frozen lockfile turned off (V5)"
    }
  ' "${npm_config_files[@]}")
fi

if [[ $findings -gt 0 ]]; then
  echo "check-dependency-verification: ${findings} finding(s) (docs/security/supply-chain.md)"
  exit 1
fi
echo "check-dependency-verification: OK (metadata, ${#build_files[@]} build file(s), ${#npm_config_files[@]} npm config file(s))"
