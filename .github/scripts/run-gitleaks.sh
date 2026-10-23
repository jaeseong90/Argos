#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# run-gitleaks.sh - secret scan with the pinned gitleaks container (SEC-73, docs/16 section 10.5).
#
# Usage:
#   run-gitleaks.sh dir [path]          scan files under path (default: repository root)
#   run-gitleaks.sh git [log-opts...]   scan commits; log-opts are passed to `git log`
#                                       (for example "base..head"; no options = full history)
#
# The container runs without network access and with the scanned tree mounted read-only.
#
# Environment:
#   GITLEAKS_IMAGE=<image>   gitleaks container image pinned by digest (default below).
#   GITLEAKS_CONFIG=<path>   configuration file (default: <repository root>/.gitleaks.toml).
#
# Exit status: 0 = no leaks, 1 = leaks found or scan error, 2 = usage error.
set -euo pipefail

DEFAULT_GITLEAKS_IMAGE="zricethezav/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f"

usage() {
  echo "usage: run-gitleaks.sh dir [path] | git [log-opts...]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
mode=$1
shift

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$script_dir/../.." && pwd)
config=${GITLEAKS_CONFIG:-$repo_root/.gitleaks.toml}
image=${GITLEAKS_IMAGE:-$DEFAULT_GITLEAKS_IMAGE}

case $mode in
  dir)
    [[ $# -le 1 ]] || usage
    target=$(cd "${1:-$repo_root}" && pwd)
    ;;
  git)
    target=$repo_root
    ;;
  *)
    usage
    ;;
esac
[[ -f "$config" ]] || {
  echo "run-gitleaks: config file '$config' not found" >&2
  exit 2
}

to_host_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}
if command -v cygpath >/dev/null 2>&1; then
  # Git Bash on Windows: keep MSYS from rewriting the container paths below.
  export MSYS_NO_PATHCONV=1
fi

config_dir=$(cd "$(dirname "$config")" && pwd)
config_name=$(basename "$config")

docker_args=(
  run --rm --network none
  -v "$(to_host_path "$target"):/scan:ro"
  -v "$(to_host_path "$config_dir"):/gitleaks-config:ro"
  # The mounted repository belongs to another user; let git read it.
  -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0='*'
  "$image"
)
gitleaks_args=(--config "/gitleaks-config/$config_name" --redact --no-banner --no-color --verbose --exit-code 1)

if [[ $mode == dir ]]; then
  echo "run-gitleaks: scanning files under $target"
  docker "${docker_args[@]}" dir "${gitleaks_args[@]}" /scan
else
  if [[ $# -gt 0 ]]; then
    echo "run-gitleaks: scanning commits ($*) in $target"
    docker "${docker_args[@]}" git "${gitleaks_args[@]}" --log-opts "$*" /scan
  else
    echo "run-gitleaks: scanning the full history of $target"
    docker "${docker_args[@]}" git "${gitleaks_args[@]}" /scan
  fi
fi
