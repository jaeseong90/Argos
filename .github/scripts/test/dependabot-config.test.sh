#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: SEC-71, O-S04
#
# Tests for .github/dependabot.yml (proposed test ID T-SEC-71b, requirement SEC-71; 01 O-S04, 16
# section 10.2, ADR-0003 C-30). GitHub is never contacted: the file is read by a small YAML reader
# below (block mappings and sequences, the subset Dependabot configurations use).
# Checks: the four ecosystems gradle, npm, github-actions and docker (Actions SHA pins are updated by
# Dependabot, DSN-19-07); monthly version updates with a cooldown; one security-updates group per
# ecosystem; minor and patch version updates grouped for Gradle and npm (majors stay separate); every
# label exists in create-labels.sh and every update is labelled "dependencies"; the docker ecosystem
# does not reach deploy/ (Compose image pins must equal deploy/*/image.properties). Mutated copies of
# the real file must fail, so the checks are shown to catch each defect.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
github_dir=$(cd "$here/../.." && pwd)
config="$github_dir/dependabot.yml"
labels_script="$github_dir/scripts/create-labels.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$here/lib.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# yaml_flat <file>: one line per scalar, "<path>=<value>", e.g. updates[0].schedule.interval=monthly or
# updates[1].labels[0]=dependencies. Comments and blank lines are ignored; quotes around scalars are
# removed. Supports block mappings, block sequences of scalars and of mappings, and "key: [a, b]" (kept
# as one scalar). Enough for dependabot.yml; not a general YAML parser.
yaml_flat() {
  awk '
    function unq(s) {
      gsub(/^[ \t]+|[ \t]+$/, "", s)
      if (s ~ /^".*"$/ || s ~ /^\047.*\047$/) s = substr(s, 2, length(s) - 2)
      return s
    }
    function strip_comment(s,   i, c, q) {
      q = ""
      for (i = 1; i <= length(s); i++) {
        c = substr(s, i, 1)
        if (q != "") { if (c == q) q = ""; continue }
        if (c == "\"" || c == "\047") { q = c; continue }
        if (c == "#" && (i == 1 || substr(s, i - 1, 1) ~ /[ \t]/)) return substr(s, 1, i - 1)
      }
      return s
    }
    function join(p, k) { return p == "" ? k : p "." k }
    # key_line <text> <indent> <parent path>: a "key: value" or "key:" entry.
    function key_line(t, i, parent,   k, v) {
      k = t; sub(/:.*/, "", k); k = unq(k)
      v = t; sub(/^[^:]*:/, "", v); v = unq(v)
      if (v == "") { depth++; ind[depth] = i; path[depth] = join(parent, k); kind[depth] = "map" }
      else print join(parent, k) "=" v
    }
    {
      line = strip_comment($0)
      sub(/[ \t]+$/, "", line)
      if (line ~ /^[ \t]*$/ || line == "---") next
      match(line, /^ */); i = RLENGTH; t = substr(line, i + 1)
      if (t ~ /^- / || t == "-") {
        while (depth > 0 && (ind[depth] > i || (ind[depth] == i && kind[depth] == "item"))) depth--
        parent = depth > 0 ? path[depth] : ""
        n = cnt[parent]++
        item = parent "[" n "]"
        rest = substr(t, 3)
        if (rest ~ /^[A-Za-z0-9_.\/-]+:([ \t]|$)/) {
          depth++; ind[depth] = i; path[depth] = item; kind[depth] = "item"
          key_line(rest, i + 2, item)
        } else {
          print item "=" unq(rest)
        }
        next
      }
      while (depth > 0 && ind[depth] >= i) depth--
      key_line(t, i, depth > 0 ? path[depth] : "")
    }
  ' "$1"
}

# known_labels: the label names that create-labels.sh creates ("name|color|description" entries).
known_labels() {
  awk '/^labels=\(/ { on = 1; next } on && /^\)/ { on = 0 } on && /^  "/ { s = $0; sub(/^  "/, "", s); sub(/\|.*/, "", s); print s }' \
    "$labels_script"
}

# check_config <file>: prints one line per problem and returns 1 when there is any. Lookups use an
# associative array of the flattened file (few processes: the test runs it on many mutated copies).
check_config() {
  local file=$1 problems=0 line count=0 n eco g sec ver types label d k
  local -A Y=() labels=() groups=()
  while IFS= read -r line; do
    Y[${line%%=*}]=${line#*=}
  done < <(yaml_flat "$file")
  while IFS= read -r line; do labels[$line]=1; done < <(known_labels)
  # val <path>: REPLY = the scalar at the path (empty when absent).
  val() { REPLY=${Y[$1]-}; }
  # lst <path>: LIST = the scalars of the sequence at the path.
  lst() {
    local i=0
    LIST=()
    while [[ -n ${Y["$1[$i]"]+x} ]]; do
      LIST+=("${Y["$1[$i]"]}")
      i=$((i + 1))
    done
  }
  problem() {
    echo "$1"
    problems=1
  }

  val version
  [[ $REPLY == 2 ]] || problem "version is not 2"
  if grep -q 'PM decision pending' "$file"; then problem "stale 'PM decision pending' comment"; fi

  while [[ -n ${Y["updates[$count].package-ecosystem"]+x} ]]; do count=$((count + 1)); done
  [[ $count -gt 0 ]] || problem "no updates entries"
  local -A per_eco=()
  for ((n = 0; n < count; n++)); do
    eco=${Y["updates[$n].package-ecosystem"]}
    per_eco[$eco]=$((${per_eco[$eco]-0} + 1))
  done
  for eco in gradle npm github-actions docker; do
    [[ ${per_eco[$eco]-0} -eq 1 ]] || problem "ecosystem ${eco}: ${per_eco[$eco]-0} entries (expected 1)"
  done

  for ((n = 0; n < count; n++)); do
    eco=${Y["updates[$n].package-ecosystem"]}
    case $eco in
      gradle | npm | github-actions | docker) ;;
      *) problem "unexpected ecosystem '${eco}' (updates[$n])" ;;
    esac
    val "updates[$n].schedule.interval"
    [[ $REPLY == monthly ]] || problem "${eco}: version updates interval '${REPLY}' (ADR-0003 C-30: monthly)"
    val "updates[$n].cooldown.default-days"
    [[ $REPLY =~ ^[0-9]+$ && $REPLY -ge 1 ]] || problem "${eco}: no cooldown.default-days for version updates"

    groups=()
    for k in "${!Y[@]}"; do
      if [[ $k == "updates[$n].groups."* ]]; then
        g=${k#"updates[$n].groups."}
        groups[${g%%.*}]=1
      fi
    done
    sec=0
    ver=0
    for g in "${!groups[@]}"; do
      val "updates[$n].groups.$g.applies-to"
      case $REPLY in
        security-updates)
          lst "updates[$n].groups.$g.patterns"
          for d in ${LIST[@]+"${LIST[@]}"}; do
            if [[ $d == '*' ]]; then sec=$((sec + 1)); fi
          done
          ;;
        version-updates)
          ver=$((ver + 1))
          if [[ $eco == gradle || $eco == npm ]]; then
            lst "updates[$n].groups.$g.update-types"
            types=$(printf '%s\n' ${LIST[@]+"${LIST[@]}"} | sort | tr '\n' ' ')
            [[ $types == "minor patch " ]] ||
              problem "${eco}: version group ${g} update-types '${types% }' (expected minor and patch; majors stay separate)"
          fi
          ;;
        *) problem "${eco}: group ${g} has no applies-to (version-updates or security-updates)" ;;
      esac
    done
    [[ $sec -eq 1 ]] || problem "${eco}: ${sec} security-updates group(s) with pattern '*' (expected 1)"
    [[ $ver -ge 1 ]] || problem "${eco}: no version-updates group"

    lst "updates[$n].labels"
    local has_dependencies=0
    for label in ${LIST[@]+"${LIST[@]}"}; do
      [[ $label != dependencies ]] || has_dependencies=1
      [[ -n ${labels[$label]+x} ]] || problem "${eco}: label '${label}' is not created by create-labels.sh"
    done
    [[ $has_dependencies -eq 1 ]] || problem "${eco}: not labelled 'dependencies'"

    lst "updates[$n].directories"
    val "updates[$n].directory"
    [[ -z $REPLY ]] || LIST+=("$REPLY")
    [[ ${#LIST[@]} -gt 0 ]] || problem "${eco}: no directory"
    if [[ $eco == github-actions ]]; then
      local actions_root=0
      for d in ${LIST[@]+"${LIST[@]}"}; do [[ $d != / ]] || actions_root=1; done
      [[ $actions_root -eq 1 ]] || problem "github-actions: directory is not / (workflows and SHA pins)"
    fi
    if [[ $eco == docker ]]; then
      for d in ${LIST[@]+"${LIST[@]}"}; do
        case $d in
          / | /\* | /\*\* | /deploy | /deploy/*)
            problem "docker: directory '${d}' reaches deploy/ (Compose pins must equal deploy/*/image.properties)"
            ;;
        esac
      done
    fi
  done
  return $problems
}

# mutate <name> <sed expression>: a copy of the real configuration with one change.
mutate() {
  sed -E "$2" "$config" >"$work/$1.yml"
  if cmp -s "$config" "$work/$1.yml"; then
    echo "mutation $1 changed nothing" >&2
    exit 1
  fi
  printf '%s\n' "$work/$1.yml"
}

# ---------------------------------------------------------------------------------------------
# The reader itself (self-test on a small document).
cat >"$work/reader.yml" <<'YAML'
# comment
version: 2
updates:
  - package-ecosystem: gradle # trailing comment
    directory: "/"
    labels:
      - dependencies
      - "area/ui"
    groups:
      g1:
        patterns: ["*"]
  - package-ecosystem: npm
    directories:
    - /a
    - /b
YAML
reader=$(yaml_flat "$work/reader.yml")
for want in 'version=2' 'updates[0].package-ecosystem=gradle' 'updates[0].directory=/' \
  'updates[0].labels[0]=dependencies' 'updates[0].labels[1]=area/ui' 'updates[0].groups.g1.patterns=["*"]' \
  'updates[1].package-ecosystem=npm' 'updates[1].directories[0]=/a' 'updates[1].directories[1]=/b'; do
  expect_exit 0 "YAML reader: ${want}" grep -qxF -- "$want" <<<"$reader"
done

expect_output "dependencies" "create-labels.sh label list is read" known_labels
expect_output "kind/security" "create-labels.sh label list includes kind/security" known_labels

# ---------------------------------------------------------------------------------------------
# The real configuration.
expect_exit 0 "T-SEC-71b dependabot.yml: four ecosystems, monthly grouped version updates, security groups, labels" \
  check_config "$config"
real=$(yaml_flat "$config")
expect_output "updates[3].directories[0]=/sample-apps/*" "docker covers the sample application Dockerfiles" \
  printf '%s\n' "$real"
expect_exit 1 "no docker-compose ecosystem (Compose pins are tied to image.properties)" \
  grep -q 'package-ecosystem=docker-compose' <<<"$real"
expect_output "Security updates do not follow this schedule" "the header says security updates open immediately" \
  cat "$config"

# ---------------------------------------------------------------------------------------------
# Mutated copies: each must fail with the matching message.
f=$(mutate weekly '0,/interval: monthly/s//interval: weekly/')
expect_exit 1 "mutation: weekly version updates fail" check_config "$f"
expect_output "gradle: version updates interval 'weekly'" "mutation: the weekly ecosystem is named" check_config "$f"

f=$(mutate no-npm-security '/ui-security-updates:/,/- "\*"/d')
expect_exit 1 "mutation: npm without a security-updates group fails" check_config "$f"
expect_output "npm: 0 security-updates group(s)" "mutation: the missing security group is named" check_config "$f"

f=$(mutate no-actions '/package-ecosystem: github-actions/s//package-ecosystem: pip/')
expect_exit 1 "mutation: no github-actions ecosystem fails" check_config "$f"
expect_output "ecosystem github-actions: 0 entries" "mutation: the missing ecosystem is named" check_config "$f"
expect_output "unexpected ecosystem 'pip'" "mutation: the unexpected ecosystem is named" check_config "$f"

f=$(mutate unknown-label '0,/      - dependencies/s//      - dependencies\n      - deps-bot/')
expect_exit 1 "mutation: a label missing from create-labels.sh fails" check_config "$f"
expect_output "gradle: label 'deps-bot' is not created by create-labels.sh" "mutation: the unknown label is named" \
  check_config "$f"

f=$(mutate no-dependencies-label '0,/      - dependencies/s//      - area\/server/')
expect_output "gradle: not labelled 'dependencies'" "mutation: an update without the dependencies label fails" \
  check_config "$f"

f=$(mutate docker-deploy 's#      - /sample-apps/\*#      - /deploy/**#')
expect_exit 1 "mutation: docker reaching deploy/ fails" check_config "$f"
expect_output "docker: directory '/deploy/**' reaches deploy/" "mutation: the deploy directory is named" check_config "$f"

f=$(mutate gradle-major '0,/          - patch/s//          - patch\n          - major/')
expect_output "gradle: version group gradle-version-updates update-types 'major minor patch'" \
  "mutation: grouped major Gradle updates fail" check_config "$f"

f=$(mutate no-cooldown '0,/      default-days: 7/s//      semver-major-days: 30/')
expect_output "gradle: no cooldown.default-days" "mutation: a missing cooldown fails" check_config "$f"

f=$(mutate no-applies-to '0,/        applies-to: version-updates/{/        applies-to: version-updates/d}')
expect_output "gradle: group gradle-version-updates has no applies-to" "mutation: a group without applies-to fails" \
  check_config "$f"

f=$(mutate version-1 's/^version: 2$/version: 1/')
expect_output "version is not 2" "mutation: version 1 fails" check_config "$f"

f=$(mutate stale-comment '1s/$/\n# weekly; PM decision pending (WO-47)/')
expect_output "stale 'PM decision pending' comment" "mutation: the stale comment fails" check_config "$f"

finish
