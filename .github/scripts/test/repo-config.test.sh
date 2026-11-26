#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: O-Q03, O-G09
# test-ids: T-BLD-025
#
# Repository configuration consistency (test ID T-BLD-025, docs/18 appendix A):
# .github/CODEOWNERS and .github/labeler.yml. GitHub is never contacted; the rules are evaluated with a local model of
# the two matchers.
#
#   CODEOWNERS   every rule is "pattern @owner..." in the syntax GitHub accepts, every path exists,
#                the docs/19 section 6.8 core paths and the supply-chain paths (CI, build logic,
#                licensing, release key and scripts) have an explicit rule owned by the maintainer
#                (the owners of the default "*" rule), and the last matching rule wins.
#   labeler.yml  only the changed-files / any-glob-to-any-file matchers, no max-files-changed or
#                changed-files-labels-limit (needs-matrix must never be skipped on a large PR), the
#                needs-matrix globs equal the CODEOWNERS "Core paths" block and contain every
#                docs/19 section 6.8 path, and sample file paths get exactly the expected labels.
#                Globs follow minimatch as actions/labeler uses it (dot: true): "**" spans
#                directories, "*" stays inside one path segment, so "*.md" matches root files only.
#
# Usage: repo-config.test.sh [<repository-root>]
#   Without an argument: checks this repository, then proves every check on fixtures (a copy of the
#   two files with one injected violation each). With a root: only runs the checks against it and
#   prints one "ERROR ..." line per problem (exit 0 = clean, 1 = problems).
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/../../.." && pwd)
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"
export LC_ALL=C

# docs/19 section 6.8: paths that carry needs-matrix and need a maintainer review (O-Q03). 6.8 names
# ".../security/**" and ".../command/**"; in this tree those areas are the packages auth/,
# web/security/, secrets/ and admin/command/, and the WAL of ".../storage/**" is wal/. The paths
# must exist (a glob for a missing package silently never labels anything).
core_globs="agent/agent-bootstrap/**
agent/agent-core/**
agent/agent-dist/**
proto/**
server/src/main/java/io/argos/server/collector/**
server/src/main/java/io/argos/server/storage/**
server/src/main/java/io/argos/server/wal/**
server/src/main/java/io/argos/server/auth/**
server/src/main/java/io/argos/server/web/security/**
server/src/main/java/io/argos/server/secrets/**
server/src/main/java/io/argos/server/admin/token/**
server/src/main/java/io/argos/server/admin/command/**
server/src/main/java/io/argos/server/privacy/**
server/src/main/java/io/argos/server/alert/channel/**
deploy/clickhouse/schema/**"

# Supply chain, CI, licensing and release signing (docs/19 sections 6.6, 7.2-7.4): an explicit
# maintainer rule each, not only the default "*" rule.
supply_paths="/.github/
/build-logic/
/gradle/
/LICENSE
/NOTICE
/docs/licenses/
/docs/adr/
/keys/
/scripts/
/deploy/verify-release.sh
/test/release/"

# Sample changed file -> labels that actions/labeler must apply (sorted, space separated).
label_samples="agent/agent-core/src/main/java/io/argos/agent/core/X.java|area/agent needs-matrix
agent/agent-bootstrap/src/main/java/X.java|area/agent needs-matrix
agent/agent-dist/build.gradle.kts|area/agent needs-matrix
agent/agent-core-extra/X.java|area/agent
agent/agent-instrumentation/jdbc/src/main/java/X.java|area/agent
sample-apps/shop-boot3/src/main/java/X.java|area/agent
test/agent/transport-poc/build.gradle.kts|area/agent
test/bench/bench-run.sh|area/agent
proto/argos/v1/agent.proto|area/agent area/server needs-matrix
server/src/main/java/io/argos/server/collector/X.java|area/server needs-matrix
server/src/main/java/io/argos/server/storage/clickhouse/X.java|area/server needs-matrix
server/src/main/java/io/argos/server/wal/FileWal.java|area/server needs-matrix
server/src/main/java/io/argos/server/auth/Role.java|area/server needs-matrix
server/src/main/java/io/argos/server/web/security/CspPolicy.java|area/server needs-matrix
server/src/main/java/io/argos/server/web/X.java|area/server
server/src/main/java/io/argos/server/secrets/MasterKey.java|area/server needs-matrix
server/src/main/java/io/argos/server/admin/command/CommandRouter.java|area/server needs-matrix
server/src/main/java/io/argos/server/privacy/deletion/X.java|area/server needs-matrix
server/src/main/java/io/argos/server/admin/token/X.java|area/server needs-matrix
server/src/main/java/io/argos/server/admin/X.java|area/server
server/src/main/java/io/argos/server/alert/channel/X.java|area/server needs-matrix
server/src/main/java/io/argos/server/alert/X.java|area/server
server/src/main/java/io/argos/server/collectorx/X.java|area/server
server/src/main/resources/db/pg/V001__init.sql|area/server needs-matrix
server/src/test/java/io/argos/server/collector/XTest.java|area/server
ui/src/a.ts|area/ui
ui/README.md|area/ui
test/e2e/x.spec.ts|area/ui
deploy/clickhouse/schema/x.sql|area/deploy needs-matrix
deploy/compose/compose.yaml|area/deploy
docs/x.md|area/docs
docs/adr/0001-license-apache-2.0.md|area/docs
README.md|area/docs
CONTRIBUTING.md|area/docs
.github/workflows/pr.yml|
build-logic/build.gradle.kts|
gradle/libs.versions.toml|"

# ---------------------------------------------------------------------------------------------
# Glob model

# The converters below return their result in REPLY (no subshell per glob: Git Bash forks slowly).

# glob_body <glob>: minimatch glob -> unanchored ERE (dot: true). Fails for syntax this model does
# not cover (negation, braces, classes, extglobs, "**" inside a segment).
glob_body() {
  local g=$1 out='' i=0 n=${#1} c
  REPLY=
  while ((i < n)); do
    c=${g:i:1}
    if [[ ${g:i:2} == '**' ]]; then
      if ((i > 0)) && [[ ${g:i-1:1} != / ]]; then return 1; fi
      if [[ ${g:i+2:1} == / ]]; then
        out+='(.*/)?'
        i=$((i + 3))
        continue
      fi
      [[ $((i + 2)) -eq $n ]] || return 1
      out+='.*'
      i=$((i + 2))
      continue
    fi
    case $c in
      '*') out+='[^/]*' ;;
      '?') out+='[^/]' ;;
      '.' | '+' | '^' | '$' | '|') out+="\\$c" ;;
      '[' | ']' | '{' | '}' | '(' | ')' | '!' | "\\" | ' ') return 1 ;;
      *) out+=$c ;;
    esac
    i=$((i + 1))
  done
  REPLY=$out
}

# labeler_ere <glob>: anchored ERE for one labeler glob.
labeler_ere() {
  glob_body "$1" || return 1
  REPLY="^${REPLY}\$"
}

# codeowners_ere <pattern>: anchored ERE for one CODEOWNERS pattern (gitignore rules: a leading or
# inner slash anchors to the root, a trailing slash matches a directory and everything below it,
# "*" alone matches every file).
codeowners_ere() {
  local p=$1 anchored=false dir=false prefix suffix
  if [[ $p == '*' ]]; then
    REPLY='^.*$'
    return 0
  fi
  if [[ $p == /* ]]; then anchored=true; p=${p#/}; fi
  if [[ $p == */ ]]; then dir=true; p=${p%/}; fi
  if [[ $p == */* ]]; then anchored=true; fi
  glob_body "$p" || return 1
  if $anchored; then prefix='^'; else prefix='^(.*/)?'; fi
  if $dir; then suffix='/.*$'; else suffix='(/.*)?$'; fi
  REPLY="${prefix}${REPLY}${suffix}"
}

# load_config <root>: parses CODEOWNERS and labeler.yml of <root> once into arrays.
#   CO_LINE[i] CO_PAT[i] CO_OWN[i] CO_RE[i] (CO_RE empty when the pattern is not supported)
#   LB_LABEL[j] LB_GLOB[j] LB_RE[j]         (LB_RE empty when the glob is not supported)
loaded_root=
load_config() {
  [[ $loaded_root == "$1" ]] && return 0
  CO_LINE=() CO_PAT=() CO_OWN=() CO_RE=() LB_LABEL=() LB_GLOB=() LB_RE=()
  local n p o l g
  if [[ -f $1/.github/CODEOWNERS ]]; then
    while IFS=$'\t' read -r n p o; do
      CO_LINE+=("$n") CO_PAT+=("$p") CO_OWN+=("$o")
      if codeowners_ere "$p"; then CO_RE+=("$REPLY"); else CO_RE+=(""); fi
    done < <(codeowners_rules "$1")
  fi
  if [[ -f $1/.github/labeler.yml ]]; then
    while IFS=$'\t' read -r l g; do
      LB_LABEL+=("$l") LB_GLOB+=("$g")
      if labeler_ere "$g"; then LB_RE+=("$REPLY"); else LB_RE+=(""); fi
    done < <(labeler_globs "$1")
  fi
  loaded_root=$1
}

# ---------------------------------------------------------------------------------------------
# Parsers

# codeowners_rules <root>: "<line>\t<pattern>\t<owners...>" per rule (comments and blank lines skipped).
codeowners_rules() {
  local line n=0 words
  while IFS= read -r line || [[ -n $line ]]; do
    n=$((n + 1))
    line=${line%$'\r'}
    [[ $line =~ ^[[:space:]]*(#.*)?$ ]] && continue
    read -r -a words <<<"$line"
    local owners=() w
    for w in "${words[@]:1}"; do
      [[ $w == \#* ]] && break
      owners+=("$w")
    done
    printf '%s\t%s\t%s\n' "$n" "${words[0]}" "${owners[*]:-}"
  done <"$1/.github/CODEOWNERS"
}

# codeowners_core <root>: the "# Core paths" block as globs (/dir/ -> dir/**, /file -> file).
codeowners_core() {
  local line in_block=false p
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line%$'\r'}
    if [[ $line == '# Core paths'* ]]; then
      in_block=true
      continue
    fi
    $in_block || continue
    [[ -z ${line//[[:space:]]/} ]] && break
    [[ $line =~ ^[[:space:]]*# ]] && continue
    p=${line%%[[:space:]]*}
    p=${p#/}
    if [[ $p == */ ]]; then printf '%s**\n' "$p"; else printf '%s\n' "$p"; fi
  done <"$1/.github/CODEOWNERS"
}

# labeler_globs <root>: "<label>\t<glob>" per glob.
labeler_globs() {
  local line label='' g
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line%$'\r'}
    [[ $line =~ ^[[:space:]]*(#.*)?$ ]] && continue
    if [[ $line =~ ^([^[:space:]#-][^:]*):[[:space:]]*$ ]]; then
      label=${BASH_REMATCH[1]}
      label=${label//\"/}
      label=${label//\'/}
      continue
    fi
    [[ $line =~ ^[[:space:]]+(-[[:space:]]+)?[A-Za-z-]+:([[:space:]]|$) ]] && continue
    if [[ $line =~ ^[[:space:]]+-[[:space:]]+(.*[^[:space:]])[[:space:]]*$ ]]; then
      g=${BASH_REMATCH[1]}
      g=${g#\"}
      g=${g%\"}
      g=${g#\'}
      g=${g%\'}
      printf '%s\t%s\n' "$label" "$g"
    fi
  done <"$1/.github/labeler.yml"
}

# labels_for <root> <path>: REPLY = sorted labels the labeler model applies to one changed file.
labels_for() {
  load_config "$1"
  local j k re l labels=()
  for j in "${!LB_RE[@]}"; do
    re=${LB_RE[j]}
    [[ -n $re && $2 =~ $re ]] || continue
    l=${LB_LABEL[j]}
    # insertion into the sorted, duplicate-free list (no sort(1) fork per sample)
    for k in "${!labels[@]}"; do
      [[ ${labels[k]} == "$l" ]] && continue 2
      if [[ ${labels[k]} > "$l" ]]; then
        labels=("${labels[@]:0:k}" "$l" "${labels[@]:k}")
        continue 2
      fi
    done
    labels+=("$l")
  done
  REPLY="${labels[*]:-}"
}

# owner_rule_for <root> <path>: sets HIT_PAT / HIT_OWN to the last matching CODEOWNERS rule
# (both empty when nothing matches).
owner_rule_for() {
  load_config "$1"
  local i re
  HIT_PAT='' HIT_OWN=''
  for i in "${!CO_RE[@]}"; do
    re=${CO_RE[i]}
    if [[ -n $re && $2 =~ $re ]]; then
      HIT_PAT=${CO_PAT[i]}
      HIT_OWN=${CO_OWN[i]}
    fi
  done
}

# ---------------------------------------------------------------------------------------------
# Checks: each prints "ERROR [<check>] ..." lines and fails when it printed any.

owner_re='^@[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(/[A-Za-z0-9._-]+)?$'

check_codeowners_syntax() {
  local root=$1 bad=0 i p w owners
  [[ -f $root/.github/CODEOWNERS ]] || { echo "ERROR [codeowners-syntax] .github/CODEOWNERS is missing"; return 1; }
  load_config "$root"
  for i in "${!CO_PAT[@]}"; do
    p=${CO_PAT[i]}
    read -r -a owners <<<"${CO_OWN[i]}"
    if [[ ${#owners[@]} -eq 0 ]]; then
      echo "ERROR [codeowners-syntax] CODEOWNERS:${CO_LINE[i]}: '$p' has no owner"
      bad=1
    fi
    for w in "${owners[@]}"; do
      if ! [[ $w =~ $owner_re ]]; then
        echo "ERROR [codeowners-syntax] CODEOWNERS:${CO_LINE[i]}: '$w' is not a GitHub @user or @org/team handle"
        bad=1
      fi
    done
    if [[ $p == '!'* || $p == *'['* || $p == *"\\"* || -z ${CO_RE[i]} ]]; then
      echo "ERROR [codeowners-syntax] CODEOWNERS:${CO_LINE[i]}: pattern '$p' uses syntax GitHub CODEOWNERS does not support"
      bad=1
    fi
  done
  return "$bad"
}

check_codeowners_paths() {
  local root=$1 bad=0 i p rel
  load_config "$root"
  for i in "${!CO_PAT[@]}"; do
    p=${CO_PAT[i]}
    [[ $p == '*' ]] && continue
    rel=${p#/}
    if [[ $rel == *[*?]* ]]; then
      # shellcheck disable=SC2206 # the pattern is expanded on purpose
      if ! (shopt -s globstar nullglob dotglob && m=("$root"/${rel%/}) && ((${#m[@]} > 0))); then
        echo "ERROR [codeowners-paths] CODEOWNERS:${CO_LINE[i]}: '$p' matches no file"
        bad=1
      fi
    elif [[ $rel == */ ]]; then
      [[ -d $root/$rel ]] || { echo "ERROR [codeowners-paths] CODEOWNERS:${CO_LINE[i]}: directory '$p' does not exist"; bad=1; }
    else
      [[ -e $root/$rel ]] || { echo "ERROR [codeowners-paths] CODEOWNERS:${CO_LINE[i]}: path '$p' does not exist"; bad=1; }
    fi
  done
  return "$bad"
}

# expect_maintainer <kind> <name> <sample path> <default owners>: one ownership verdict.
expect_maintainer() {
  owner_rule_for "$root" "$3"
  if [[ -z $HIT_PAT || $HIT_PAT == '*' ]]; then
    echo "ERROR [codeowners-owners] $1 path $2 has no explicit CODEOWNERS rule"
    return 1
  elif [[ $HIT_OWN != "$4" ]]; then
    echo "ERROR [codeowners-owners] $1 path $2 is owned by '$HIT_OWN' (rule '$HIT_PAT'), not the maintainer '$4'"
    return 1
  fi
}

check_codeowners_ownership() {
  local root=$1 bad=0 default='' i p sample g
  load_config "$root"
  for i in "${!CO_PAT[@]}"; do
    [[ ${CO_PAT[i]} == '*' ]] && default=${CO_OWN[i]}
  done
  if [[ -z $default ]]; then
    echo "ERROR [codeowners-owners] no default '*' rule: the maintainer owners are unknown"
    return 1
  fi
  while IFS= read -r g; do
    expect_maintainer "core (docs/19 6.8)" "$g" "${g%\*\*}x/Sample.java" "$default" || bad=1
  done <<<"$core_globs"
  while IFS= read -r p; do
    sample=${p#/}
    [[ $sample == */ ]] && sample+=x
    expect_maintainer supply-chain "$p" "$sample" "$default" || bad=1
  done <<<"$supply_paths"
  return "$bad"
}

check_labeler_format() {
  local root=$1 bad=0 line n=0 key j label found
  [[ -f $root/.github/labeler.yml ]] || { echo "ERROR [labeler-format] .github/labeler.yml is missing"; return 1; }
  load_config "$root"
  while IFS= read -r line || [[ -n $line ]]; do
    n=$((n + 1))
    line=${line%$'\r'}
    [[ $line =~ ^[[:space:]]*(#.*)?$ ]] && continue
    if [[ $line =~ ^([^[:space:]#-][^:]*):[[:space:]]*$ ]]; then
      label=${BASH_REMATCH[1]}
      label=${label//\"/}
      label=${label//\'/}
      found=false
      for j in "${!LB_LABEL[@]}"; do
        [[ ${LB_LABEL[j]} == "$label" ]] && found=true && break
      done
      $found || { echo "ERROR [labeler-format] labeler.yml:$n: label '$label' has no glob"; bad=1; }
      continue
    fi
    if [[ $line =~ ^[[:space:]]*(-[[:space:]]+)?([A-Za-z-]+):([[:space:]]|$) ]]; then
      key=${BASH_REMATCH[2]}
      case $key in
        changed-files | any-glob-to-any-file) ;;
        max-files-changed | changed-files-labels-limit)
          echo "ERROR [labeler-format] labeler.yml:$n: '$key' would skip labels (needs-matrix) on large pull requests"
          bad=1
          ;;
        *)
          echo "ERROR [labeler-format] labeler.yml:$n: matcher '$key' is not modelled by this test (use any-glob-to-any-file)"
          bad=1
          ;;
      esac
    fi
  done <"$root/.github/labeler.yml"
  for j in "${!LB_GLOB[@]}"; do
    if [[ -z ${LB_RE[j]} ]]; then
      echo "ERROR [labeler-format] label '${LB_LABEL[j]}': glob '${LB_GLOB[j]}' uses syntax this test does not model"
      bad=1
    fi
  done
  return "$bad"
}

check_needs_matrix_sets() {
  local root=$1 bad=0 labeler_set owners_set g j
  load_config "$root"
  labeler_set=$(for j in "${!LB_LABEL[@]}"; do
    [[ ${LB_LABEL[j]} == needs-matrix ]] && printf '%s\n' "${LB_GLOB[j]}"
  done | sort -u)
  owners_set=$(codeowners_core "$root" | sort -u)
  [[ -n $owners_set ]] || { echo "ERROR [needs-matrix] CODEOWNERS has no '# Core paths' block"; bad=1; }
  [[ -n $labeler_set ]] || { echo "ERROR [needs-matrix] labeler.yml has no needs-matrix globs"; bad=1; }
  while IFS= read -r g; do
    [[ -n $g ]] || continue
    echo "ERROR [needs-matrix] labeler needs-matrix glob '$g' is not in the CODEOWNERS Core paths block"
    bad=1
  done < <(comm -23 <(printf '%s\n' "$labeler_set") <(printf '%s\n' "$owners_set"))
  while IFS= read -r g; do
    [[ -n $g ]] || continue
    echo "ERROR [needs-matrix] CODEOWNERS core path '$g' is not a labeler needs-matrix glob"
    bad=1
  done < <(comm -13 <(printf '%s\n' "$labeler_set") <(printf '%s\n' "$owners_set"))
  while IFS= read -r g; do
    if ! grep -qxF -- "$g" <<<"$labeler_set"; then
      echo "ERROR [needs-matrix] docs/19 6.8 path '$g' is missing from labeler needs-matrix"
      bad=1
    fi
    if ! grep -qxF -- "$g" <<<"$owners_set"; then
      echo "ERROR [needs-matrix] docs/19 6.8 path '$g' is missing from the CODEOWNERS Core paths block"
      bad=1
    fi
  done <<<"$core_globs"
  return "$bad"
}

check_label_samples() {
  local root=$1 bad=0 path want got
  load_config "$root"
  while IFS='|' read -r path want; do
    labels_for "$root" "$path"
    got=$REPLY
    if [[ $got != "$want" ]]; then
      echo "ERROR [labeler-samples] $path -> '${got}', expected '${want}'"
      bad=1
    fi
  done <<<"$label_samples"
  return "$bad"
}

all_checks="check_codeowners_syntax check_codeowners_paths check_codeowners_ownership check_labeler_format check_needs_matrix_sets check_label_samples"

run_checks() {
  local rc=0 c
  for c in $all_checks; do
    "$c" "$1" || rc=1
  done
  return "$rc"
}

if [[ $# -gt 0 ]]; then
  [[ -d $1 ]] || { echo "usage: repo-config.test.sh [<repository-root>]" >&2; exit 2; }
  run_checks "$1"
  exit $?
fi

# ---------------------------------------------------------------------------------------------
# 1. This repository.
expect_exit 0 "CODEOWNERS: every rule is a pattern with @owners in supported syntax" check_codeowners_syntax "$repo"
expect_exit 0 "CODEOWNERS: every path exists" check_codeowners_paths "$repo"
expect_exit 0 "CODEOWNERS: core (docs/19 6.8) and supply-chain paths have explicit maintainer rules" \
  check_codeowners_ownership "$repo"
expect_exit 0 "labeler: supported matchers only, no max-files-changed / changed-files-labels-limit" \
  check_labeler_format "$repo"
expect_exit 0 "needs-matrix globs = CODEOWNERS Core paths, docs/19 6.8 paths in both" check_needs_matrix_sets "$repo"
expect_exit 0 "labeler: sample changed files get exactly the expected labels" check_label_samples "$repo"

# 2. The glob model itself (minimatch dot:true and gitignore-style CODEOWNERS).
glob_matches() { labeler_ere "$1" && [[ $2 =~ $REPLY ]]; }
owner_matches() { codeowners_ere "$1" && [[ $2 =~ $REPLY ]]; }
expect_exit 0 "model: '*.md' matches a root file" glob_matches '*.md' README.md
expect_exit 1 "model: '*.md' does not match a nested file" glob_matches '*.md' ui/README.md
expect_exit 0 "model: 'a/**' matches any depth below a/" glob_matches 'a/**' a/b/c/d.txt
expect_exit 1 "model: 'a/**' does not match a sibling prefix" glob_matches 'a/**' ab/c.txt
expect_exit 0 "model: '**/x.md' matches at the root" glob_matches '**/x.md' x.md
expect_exit 0 "model: '**/x.md' matches nested" glob_matches '**/x.md' a/b/x.md
expect_exit 0 "model: '*' matches dot files (dot: true)" glob_matches 'docs/*' docs/.hidden
expect_exit 1 "model: brace expansion is rejected, not guessed" labeler_ere 'a/{b,c}/**'
expect_exit 1 "model: '**' inside a segment is rejected" labeler_ere 'a/b**/c'
expect_exit 0 "model: CODEOWNERS '/dir/' owns files below it" owner_matches /agent/agent-core/ agent/agent-core/src/X.java
expect_exit 1 "model: CODEOWNERS '/dir/' does not own a sibling prefix" owner_matches /agent/agent-core/ agent/agent-core-x/X.java
expect_exit 1 "model: CODEOWNERS '/dir/' is anchored at the root" owner_matches /keys/ docs/keys/x
expect_exit 0 "model: CODEOWNERS '/file' owns that file" owner_matches /deploy/verify-release.sh deploy/verify-release.sh
expect_exit 0 "model: CODEOWNERS unanchored name matches anywhere" owner_matches Makefile a/b/Makefile
expect_exit 0 "model: CODEOWNERS '*' owns everything" owner_matches '*' a/b/c

# 3. Fixtures: a copy of the two files plus the paths they name, one injected violation each.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

make_fixture() {
  local d=$work/$1 n p o rel
  mkdir -p "$d/.github"
  cp "$repo/.github/CODEOWNERS" "$repo/.github/labeler.yml" "$d/.github/"
  while IFS=$'\t' read -r n p o; do
    [[ $p == '*' || $p == *[*?]* ]] && continue
    rel=${p#/}
    if [[ $rel == */ ]]; then mkdir -p "$d/$rel"; else mkdir -p "$(dirname "$d/$rel")" && : >"$d/$rel"; fi
  done < <(codeowners_rules "$repo")
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
    printf '%s\n' "$out" | sed 's/^/      | /'
  fi
}

self=$here/repo-config.test.sh

# fx <fixture>: runs the checks against a fixture once and prints the cached output (the cases of
# one fixture assert on the same run).
fx() {
  [[ -f $1.out ]] || bash "$self" "$1" >"$1.out" 2>&1 || true
  cat "$1.out"
}
base=$(make_fixture base)
expect_exit 0 "fixture: an unchanged copy passes every check" bash "$self" "$base"

f=$(make_fixture no-owner)
printf '/docs/\n' >>"$f/.github/CODEOWNERS"
expect_output "ERROR [codeowners-syntax] CODEOWNERS:" "fixture: a rule without an owner is rejected" fx "$f"
expect_output "'/docs/' has no owner" "fixture: the ownerless rule is named" fx "$f"

f=$(make_fixture bad-owner)
printf '/docs/ jaeseong90\n/ui/ someone@example.org\n' >>"$f/.github/CODEOWNERS"
expect_output "'jaeseong90' is not a GitHub @user" "fixture: an owner without @ is rejected" fx "$f"
expect_output "'someone@example.org' is not a GitHub @user" "fixture: e-mail owners are rejected" fx "$f"

f=$(make_fixture negation)
printf '!/docs/ @jaeseong90\n' >>"$f/.github/CODEOWNERS"
expect_output "pattern '!/docs/' uses syntax GitHub CODEOWNERS does not support" "fixture: negation is rejected" fx "$f"

f=$(make_fixture missing-path)
printf '/agent/agent-gone/ @jaeseong90\n/docs/gone.md @jaeseong90\n' >>"$f/.github/CODEOWNERS"
expect_output "directory '/agent/agent-gone/' does not exist" "fixture: a missing directory is reported" fx "$f"
expect_output "path '/docs/gone.md' does not exist" "fixture: a missing file is reported" fx "$f"

f=$(make_fixture no-keys)
sed -i '\#^/keys/ #d' "$f/.github/CODEOWNERS"
expect_output "supply-chain path /keys/ has no explicit CODEOWNERS rule" \
  "fixture: a release supply-chain path covered only by '*' is reported" fx "$f"

f=$(make_fixture override)
printf '/agent/ @someone-else\n' >>"$f/.github/CODEOWNERS"
expect_output "path agent/agent-core/** is owned by '@someone-else' (rule '/agent/')" \
  "fixture: a later rule taking over a core path is reported (last match wins)" fx "$f"

f=$(make_fixture core-removed)
sed -i '\#^/proto/ #d' "$f/.github/CODEOWNERS"
expect_output "labeler needs-matrix glob 'proto/**' is not in the CODEOWNERS Core paths block" \
  "fixture: needs-matrix and CODEOWNERS core sets must be equal" fx "$f"
expect_output "docs/19 6.8 path 'proto/**' is missing from the CODEOWNERS Core paths block" \
  "fixture: a docs/19 6.8 path missing from CODEOWNERS is reported" fx "$f"

f=$(make_fixture labeler-extra)
sed -i 's#^          - deploy/clickhouse/schema/\*\*$#          - deploy/clickhouse/schema/**\n          - ui/**#' "$f/.github/labeler.yml"
expect_output "labeler needs-matrix glob 'ui/**' is not in the CODEOWNERS Core paths block" \
  "fixture: an extra needs-matrix glob is reported" fx "$f"
expect_output "ERROR [labeler-samples] ui/src/a.ts -> 'area/ui needs-matrix', expected 'area/ui'" \
  "fixture: the sample table catches the extra label" fx "$f"

f=$(make_fixture labeler-6.8)
sed -i '\#^          - server/src/main/java/io/argos/server/privacy/\*\*$#d' "$f/.github/labeler.yml"
expect_output "docs/19 6.8 path 'server/src/main/java/io/argos/server/privacy/**' is missing from labeler needs-matrix" \
  "fixture: a docs/19 6.8 path missing from labeler is reported" fx "$f"

f=$(make_fixture limit)
printf '\narea/agent-big:\n  - changed-files:\n      - any-glob-to-any-file:\n          - agent/**\n  - max-files-changed: 50\nchanged-files-labels-limit: 3\n' >>"$f/.github/labeler.yml"
expect_output "'max-files-changed' would skip labels" "fixture: max-files-changed is rejected" fx "$f"
expect_output "'changed-files-labels-limit' would skip labels" "fixture: changed-files-labels-limit is rejected" fx "$f"

f=$(make_fixture matcher)
sed -i 's#any-glob-to-any-file:#all-globs-to-all-files:#' "$f/.github/labeler.yml"
expect_output "matcher 'all-globs-to-all-files' is not modelled" "fixture: other labeler matchers are rejected" fx "$f"

f=$(make_fixture nested-md)
sed -i 's#^          - "\*\.md"$#          - "**/*.md"#' "$f/.github/labeler.yml"
expect_output "ERROR [labeler-samples] ui/README.md -> 'area/docs area/ui', expected 'area/ui'" \
  "fixture: '**/*.md' instead of root-only '*.md' is caught by the samples" fx "$f"
expect_no_output "ERROR [labeler-samples] README.md" "fixture: root README.md still gets area/docs" fx "$f"

finish
