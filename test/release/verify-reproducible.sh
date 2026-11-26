#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# verify-reproducible.sh: two builds of the same commit produce the same release outputs
# (T-BLD-009, 19 §3.7, 16 §10.4, SEC-73). The comparison itself is the Gradle task
# compareReleaseOutputs (build-logic io.argos.buildlogic.release.assembly.CompareArchives): file
# SHA-256, then for differing jars, tars and image layers the entry names, order, times, modes,
# owners, storage and contents, each difference with its likely cause.
#
# Usage:
#   verify-reproducible.sh run [--ui-skip] [--agent-only] [--images] [--version X.Y.Z] [--out <dir>]
#       Builds twice with SOURCE_DATE_EPOCH (the environment's, else the HEAD commit time),
#       --no-build-cache --rerun-tasks, keeps the outputs apart in <out>/a and <out>/b (default
#       build/reproducibility) and compares them.
#         (default)     ./gradlew releaseArtifacts: agent and server jars, agent, server and UI SBOMs
#         --ui-skip     -Pargos.ui.skip=true, parts agent,server (server jar without the UI bundle)
#         --agent-only  -Pargos.ui.skip=true, part agent only (agent jar and SBOM)
#         --images      also :server:jibBuildTar, :sample-apps:demo-app:jibBuildTar and
#                       :sample-apps:load:jibBuildTar for linux/amd64 (no registry, nothing pushed);
#                       compares the image tars and their digests (images/<name>.tar|.digest|.id)
#   verify-reproducible.sh compare <dirA> <dirB>
#       Compares two existing outputs, e.g. build/release of two CI runners (release.yml).
#
# Environment: GRADLE_ARGS (extra Gradle arguments, word-split), SOURCE_DATE_EPOCH.
# Exit: 0 identical, 1 different (entry list and likely causes printed; report file kept) or a
#       build/comparison failure, 2 usage error.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$script_dir/../.." && pwd)"

usage() {
  sed -n '/^# Usage:/,/^# Exit:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

die_usage() {
  echo "verify-reproducible: $*" >&2
  usage
}

# Absolute, and in the form the JVM understands (C:/... under Git Bash).
native_path() {
  local path
  path="$(cd "$1" && pwd)"
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$path"; else printf '%s\n' "$path"; fi
}

gradlew() {
  local extra=()
  # shellcheck disable=SC2206 # GRADLE_ARGS is split into words on purpose
  [[ -n "${GRADLE_ARGS:-}" ]] && extra=(${GRADLE_ARGS})
  (cd "$root" && ./gradlew --console=plain "${extra[@]}" "$@")
}

# compare <dirA> <dirB> <report>: runs compareReleaseOutputs; prints the report.
compare_dirs() {
  local a b report status=0
  a="$(native_path "$1")"
  b="$(native_path "$2")"
  report="$3"
  rm -f "$report"
  gradlew -q compareReleaseOutputs \
    "-Pargos.release.compare.a=$a" "-Pargos.release.compare.b=$b" \
    "-Pargos.release.compare.report=$(native_path "$(dirname "$report")")/$(basename "$report")" \
    >/dev/null 2>"$report.stderr" || status=$?
  if [[ -s "$report" ]]; then
    cat "$report"
  fi
  if ((status == 0)); then
    rm -f "$report.stderr"
    echo "verify-reproducible: IDENTICAL (report: $report)"
    return 0
  fi
  if [[ -s "$report" ]] && head -n 1 "$report" | grep -q '^compare: DIFFERENT'; then
    rm -f "$report.stderr"
    echo "verify-reproducible: DIFFERENT (report: $report)" >&2
  else
    echo "verify-reproducible: the comparison did not run (Gradle exit $status):" >&2
    sed 's/^/  | /' "$report.stderr" >&2 || true
  fi
  return 1
}

cmd_compare() {
  [[ $# -eq 2 ]] || die_usage "compare needs exactly <dirA> <dirB>"
  [[ -d "$1" ]] || die_usage "not a directory: $1"
  [[ -d "$2" ]] || die_usage "not a directory: $2"
  local report_dir
  report_dir="$root/build/reproducibility"
  mkdir -p "$report_dir"
  compare_dirs "$1" "$2" "$report_dir/compare-report.txt"
}

cmd_run() {
  local ui_skip=0 agent_only=0 images=0 version="" out=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --ui-skip) ui_skip=1 ;;
      --agent-only) agent_only=1 ;;
      --images) images=1 ;;
      --version)
        [[ $# -ge 2 && -n "$2" ]] || die_usage "--version needs a value"
        version="$2"
        shift
        ;;
      --out)
        [[ $# -ge 2 && -n "$2" ]] || die_usage "--out needs a directory"
        out="$2"
        shift
        ;;
      -h | --help) usage ;;
      *) die_usage "unknown option: $1" ;;
    esac
    shift
  done
  if ((agent_only && images)); then
    die_usage "--agent-only and --images exclude each other (the images need the server and demo apps)"
  fi

  local epoch="${SOURCE_DATE_EPOCH:-}"
  if [[ -z "$epoch" ]]; then
    epoch="$(git -C "$root" log -1 --format=%ct HEAD)"
  fi
  [[ "$epoch" =~ ^[0-9]+$ ]] || die_usage "SOURCE_DATE_EPOCH must be seconds since the epoch, got '$epoch'"
  export SOURCE_DATE_EPOCH="$epoch"

  out="${out:-$root/build/reproducibility}"
  mkdir -p "$out"
  out="$(cd "$out" && pwd)"

  local args=(releaseArtifacts --no-build-cache --rerun-tasks)
  if ((agent_only)); then
    args+=(-Pargos.ui.skip=true -Pargos.release.parts=agent)
  elif ((ui_skip)); then
    args+=(-Pargos.ui.skip=true "-Pargos.release.parts=agent,server")
  fi
  [[ -n "$version" ]] && args+=("-Pversion=$version")
  local image_projects=(server sample-apps/demo-app sample-apps/load)
  local image_names=(argos-server argos-demo-app argos-demo-load)
  if ((images)); then
    args+=(:server:jibBuildTar :sample-apps:demo-app:jibBuildTar :sample-apps:load:jibBuildTar)
    args+=(-Pargos.image.platforms=linux/amd64)
  fi

  echo "verify-reproducible: SOURCE_DATE_EPOCH=$SOURCE_DATE_EPOCH, outputs in $out/{a,b}"
  echo "verify-reproducible: ./gradlew ${args[*]}"
  local side start i
  for side in a b; do
    rm -rf "${out:?}/$side" "$root/build/release"
    for i in "${!image_projects[@]}"; do
      rm -f "$root/${image_projects[$i]}/build/jib-image.tar" "$root/${image_projects[$i]}/build/jib-image.digest" \
        "$root/${image_projects[$i]}/build/jib-image.id"
    done
    mkdir -p "$out/$side"
    start=$SECONDS
    if ! gradlew "${args[@]}" >"$out/build-$side.log" 2>&1; then
      echo "verify-reproducible: build $side failed after $((SECONDS - start)) s; log: $out/build-$side.log" >&2
      tail -n 40 "$out/build-$side.log" | sed 's/^/  | /' >&2
      return 1
    fi
    cp -p "$root/build/release/"* "$out/$side/"
    if ((images)); then
      mkdir -p "$out/$side/images"
      for i in "${!image_projects[@]}"; do
        local build_dir="$root/${image_projects[$i]}/build"
        cp "$build_dir/jib-image.tar" "$out/$side/images/${image_names[$i]}.tar"
        cp "$build_dir/jib-image.digest" "$out/$side/images/${image_names[$i]}.digest"
        cp "$build_dir/jib-image.id" "$out/$side/images/${image_names[$i]}.id"
      done
    fi
    echo "verify-reproducible: build $side done in $((SECONDS - start)) s ($(find "$out/$side" -type f | wc -l | tr -d ' ') files)"
  done
  compare_dirs "$out/a" "$out/b" "$out/compare-report.txt"
}

[[ $# -ge 1 ]] || usage
command="$1"
shift
case "$command" in
  run) cmd_run "$@" ;;
  compare) cmd_compare "$@" ;;
  -h | --help) usage ;;
  *) die_usage "unknown command: $command" ;;
esac
