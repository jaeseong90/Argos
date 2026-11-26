#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: SEC-71
# test-ids: T-SEC-71d
#
# Tests for test/release/trivy-scan.sh (T-BLD-008 scanner contract, image mode T-SEC-71d proposed):
# argument checks, and the Docker command lines through a fake `docker` on PATH that records its
# arguments. No Docker, no network.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
script="$here/trivy-scan.sh"
# shellcheck source=.github/scripts/test/lib.sh
source "$root/.github/scripts/test/lib.sh"

work="$(mktemp -d "${TMPDIR:-/tmp}/argos-trivy-scan-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

printf '{"bomFormat":"CycloneDX","specVersion":"1.6","components":[{"name":"a"}]}\n' >"$work/argos-server-0.1.0.cdx.json"
printf '# none\n' >"$work/.trivyignore"

# Fake docker: logs one line per call; `image inspect` fails for FAKE_MISSING, `save -o <f>` writes
# a tar placeholder and records its directory, `run` checks the mounted tar and exits FAKE_RUN_EXIT.
mkdir -p "$work/bin"
cat >"$work/bin/docker" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$FAKE_LOG"
case "$1 ${2:-}" in
  "image inspect")
    [[ "$3" == "${FAKE_MISSING:-}" ]] && exit 1
    exit 0
    ;;
  "save -o")
    printf 'tar' >"$3"
    dirname "$3" >"$FAKE_SAVE_DIR"
    exit 0
    ;;
esac
if [[ "$1" == "run" ]]; then
  for a in "$@"; do
    case "$a" in
      *:/work/image:ro)
        dir="${a%:/work/image:ro}"
        command -v cygpath >/dev/null 2>&1 && dir="$(cygpath -u "$dir")"
        [[ -s "$dir/image.tar" ]] && echo "mounted tar present" >>"$FAKE_LOG"
        ;;
    esac
  done
  echo "CVE-2026-11111 HIGH fake finding"
  exit "${FAKE_RUN_EXIT:-0}"
fi
exit 0
EOF
chmod +x "$work/bin/docker"
export FAKE_LOG="$work/docker.log" FAKE_SAVE_DIR="$work/save-dir"
fake_path="$work/bin:$PATH"

run_fake() { PATH="$fake_path" bash "$script" "$@"; }

# --- usage (no Docker needed) -------------------------------------------------------------------
expect_exit 2 "no arguments: usage error" bash "$script"
expect_exit 2 "one argument: usage error" bash "$script" "$work/argos-server-0.1.0.cdx.json"
expect_exit 2 "missing SBOM file: usage error" bash "$script" "$work/nope.cdx.json" "$work/.trivyignore"
expect_exit 2 "missing ignore file: usage error" bash "$script" "$work/argos-server-0.1.0.cdx.json" "$work/nope"
expect_exit 2 "three arguments: usage error" \
  bash "$script" "$work/argos-server-0.1.0.cdx.json" "$work/.trivyignore" extra
expect_exit 2 "--image without a reference and ignore file: usage error" bash "$script" --image
expect_exit 2 "--image without an ignore file: usage error" bash "$script" --image argos-server:0.1.0
expect_exit 2 "--image with a missing ignore file: usage error" \
  bash "$script" --image argos-server:0.1.0 "$work/nope"
expect_exit 2 "--image with an option as the reference: usage error" \
  bash "$script" --image --quiet "$work/.trivyignore"
expect_output "not an image reference: argos server" "--image rejects a reference with a space" \
  bash "$script" --image "argos server" "$work/.trivyignore"
expect_exit 2 "--image with a shell metacharacter in the reference: usage error" \
  bash "$script" --image 'argos-server:0.1.0;id' "$work/.trivyignore"
expect_exit 2 "ARGOS_TRIVY_IMAGE without a digest: usage error (SBOM mode)" \
  env ARGOS_TRIVY_IMAGE=aquasec/trivy:latest bash "$script" "$work/argos-server-0.1.0.cdx.json" "$work/.trivyignore"
expect_exit 2 "ARGOS_TRIVY_IMAGE without a digest: usage error (image mode)" \
  env ARGOS_TRIVY_IMAGE=aquasec/trivy:latest bash "$script" --image argos-server:0.1.0 "$work/.trivyignore"
expect_output "usage: " "the usage names both forms" bash "$script"
expect_output "--image <image-ref> <ignorefile>" "the usage names the image form" bash "$script"

# --- SBOM mode: the Docker command (contract of check-sbom.sh --scanner) ------------------------
: >"$FAKE_LOG"
expect_exit 0 "SBOM mode: clean scan exits 0" run_fake "$work/argos-server-0.1.0.cdx.json" "$work/.trivyignore"
expect_output "--severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 --ignorefile /work/ignore/.trivyignore /work/sbom/argos-server-0.1.0.cdx.json" \
  "SBOM mode runs trivy sbom with the gate flags" cat "$FAKE_LOG"
expect_output "@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb sbom --quiet --scanners vuln" \
  "SBOM mode uses the digest-pinned Trivy image" cat "$FAKE_LOG"
expect_output ":/work/sbom:ro" "SBOM mode mounts the SBOM read-only" cat "$FAKE_LOG"
expect_exit 1 "SBOM mode: findings (Trivy exit 1) exit 1" \
  env FAKE_RUN_EXIT=1 PATH="$fake_path" bash "$script" "$work/argos-server-0.1.0.cdx.json" "$work/.trivyignore"

# --- image mode -----------------------------------------------------------------------------------
: >"$FAKE_LOG"
rm -f "$FAKE_SAVE_DIR"
expect_exit 0 "image mode: clean scan exits 0" run_fake --image argos-server:0.1.0 "$work/.trivyignore"
expect_output "image inspect argos-server:0.1.0" "image mode checks that the image exists locally" cat "$FAKE_LOG"
expect_output "save -o " "image mode saves the image with docker save" cat "$FAKE_LOG"
expect_output "image --quiet --input /work/image/image.tar --scanners vuln --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 --ignorefile /work/ignore/.trivyignore" \
  "image mode runs trivy image --input with the gate flags" cat "$FAKE_LOG"
expect_output "@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb image" \
  "image mode uses the same digest-pinned Trivy image" cat "$FAKE_LOG"
expect_output ":/work/image:ro" "image mode mounts the image tar read-only" cat "$FAKE_LOG"
expect_output "mounted tar present" "the saved tar is what the scanner container sees" cat "$FAKE_LOG"
expect_exit 1 "image mode never mounts the Docker socket" grep -q "docker.sock" "$FAKE_LOG"
expect_exit 0 "the saved image tar is removed afterwards" bash -c "[[ -s '$FAKE_SAVE_DIR' && ! -e \$(cat '$FAKE_SAVE_DIR') ]]"
expect_output "argos-server:0.1.0: no fixable Critical or High finding outside the ignore file" \
  "a clean image scan says so" run_fake --image argos-server:0.1.0 "$work/.trivyignore"
expect_exit 1 "image mode: findings (Trivy exit 1) exit 1" \
  env FAKE_RUN_EXIT=1 PATH="$fake_path" bash "$script" --image argos-server:0.1.0 "$work/.trivyignore"
expect_output "CVE-2026-11111 HIGH" "image mode shows the scanner output" \
  env FAKE_RUN_EXIT=1 PATH="$fake_path" bash "$script" --image argos-server:0.1.0 "$work/.trivyignore"
expect_exit 0 "the tar is removed after a failing scan too" bash -c "[[ ! -e \$(cat '$FAKE_SAVE_DIR') ]]"
: >"$FAKE_LOG"
expect_exit 1 "image mode: an image missing from the local daemon exits 1" \
  env FAKE_MISSING=argos-server:0.1.0 PATH="$fake_path" bash "$script" --image argos-server:0.1.0 "$work/.trivyignore"
expect_exit 1 "a missing image is not scanned (no docker run)" grep -q "^run " "$FAKE_LOG"
expect_output "image not found in the local Docker daemon: argos-server:0.1.0" "the missing image is named" \
  env FAKE_MISSING=argos-server:0.1.0 PATH="$fake_path" bash "$script" --image argos-server:0.1.0 "$work/.trivyignore"

finish
