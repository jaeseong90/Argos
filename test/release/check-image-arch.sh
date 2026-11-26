#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# check-image-arch.sh: checks one platform image of a Jib tarball (jibBuildTar, Docker-loadable
# format) before the multi-arch push of the release pipeline (T-BLD-022, C-171 (1), 19 §3.5):
#
#   manifest       manifest.json names one image with a config and at least one layer
#   architecture   config architecture = <arch>, os = linux
#   user           config User = 10001:10001 (non-root, ServerImage.USER)
#   labels         org.opencontainers.image.version (= --version when given), .revision (git
#                  commit, 40 or 64 hex), .licenses = Apache-2.0, .source (https URL)
#   base           org.opencontainers.image.base.name@base.digest = the pinned base image
#                  (default: ServerImage.BASE_IMAGE read from build-logic, shared by the server and
#                  demo images; an arm64 image built from it shows that the pin is a multi-arch index)
#
# Usage:  check-image-arch.sh [--version X.Y.Z] [--base-image <name@sha256:...>] <jib-tar> <arch>
#         check-image-arch.sh --print-expected-base
# Needs:  tar, Node.js.
# Exit:   0 all checks pass, 1 a check failed, 2 usage error.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$script_dir/../.." && pwd)"
server_image_kt="$root/build-logic/src/main/kotlin/io/argos/buildlogic/server/ServerImage.kt"

usage() {
  sed -n '/^# Usage:/,/^# Exit:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

expected_base() {
  local refs
  refs="$(grep -oE '[a-z0-9./_-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}' "$server_image_kt" | sort -u)"
  if [[ -z "$refs" || "$(printf '%s\n' "$refs" | wc -l | tr -d ' ')" -ne 1 ]]; then
    echo "check-image-arch: cannot read one pinned base image from $server_image_kt" >&2
    exit 2
  fi
  printf '%s\n' "$refs"
}

version=""
base=""
positional=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --print-expected-base)
      expected_base
      exit 0
      ;;
    --version | --base-image)
      [[ $# -ge 2 && -n "$2" ]] || { echo "check-image-arch: $1 needs a value" >&2; usage; }
      if [[ "$1" == --version ]]; then version="$2"; else base="$2"; fi
      shift 2
      ;;
    -h | --help) usage ;;
    -*)
      echo "check-image-arch: unknown option: $1" >&2
      usage
      ;;
    *)
      positional+=("$1")
      shift
      ;;
  esac
done
[[ ${#positional[@]} -eq 2 ]] || usage
tarball="${positional[0]}"
arch="${positional[1]}"
[[ -f "$tarball" ]] || { echo "check-image-arch: not a file: $tarball" >&2; usage; }
[[ "$arch" =~ ^[a-z0-9]+$ ]] || { echo "check-image-arch: invalid architecture: $arch" >&2; usage; }
command -v node >/dev/null 2>&1 || { echo "check-image-arch: Node.js is needed" >&2; exit 2; }
[[ -n "$base" ]] || base="$(expected_base)"

tar_opts=()
if tar --version 2>/dev/null | grep -q 'GNU tar'; then
  tar_opts+=(--force-local) # C:/... is a local path, not host:path
fi

if ! manifest="$(tar "${tar_opts[@]}" -xOf "$tarball" manifest.json 2>/dev/null)"; then
  echo "FAIL  manifest: no manifest.json in $tarball (not a Jib tarball?)"
  exit 1
fi
if ! config_name="$(printf '%s' "$manifest" | node -e '
  let text = "";
  process.stdin.on("data", (d) => (text += d)).on("end", () => {
    const m = JSON.parse(text);
    if (!Array.isArray(m) || m.length !== 1 || typeof m[0].Config !== "string") process.exit(1);
    if (!Array.isArray(m[0].Layers) || m[0].Layers.length === 0) process.exit(1);
    console.log(m[0].Config);
  });' 2>/dev/null)"; then
  echo "FAIL  manifest: manifest.json must name one image with a config and layers"
  exit 1
fi
echo "PASS  manifest: config $config_name"
if ! config="$(tar "${tar_opts[@]}" -xOf "$tarball" "$config_name" 2>/dev/null)"; then
  echo "FAIL  manifest: config $config_name missing from the tarball"
  exit 1
fi

printf '%s' "$config" | node -e '
  const [arch, version, base] = process.argv.slice(1);
  let text = "";
  process.stdin.on("data", (d) => (text += d)).on("end", () => {
    const c = JSON.parse(text);
    const labels = (c.config && c.config.Labels) || {};
    const label = (k) => labels["org.opencontainers.image." + k];
    let failed = 0;
    const check = (name, ok, detail) => {
      console.log((ok ? "PASS  " : "FAIL  ") + name + ": " + detail);
      if (!ok) failed++;
    };
    check("architecture", c.architecture === arch && c.os === "linux",
      "os/architecture " + c.os + "/" + c.architecture + ", expected linux/" + arch);
    const user = c.config && c.config.User;
    check("user", user === "10001:10001", "User " + JSON.stringify(user) + ", expected \"10001:10001\"");
    const v = label("version");
    check("labels", typeof v === "string" && v !== "" && (version === "" || v === version),
      "version " + JSON.stringify(v) + (version === "" ? "" : ", expected " + JSON.stringify(version)));
    const r = label("revision");
    check("labels", typeof r === "string" && /^([0-9a-f]{40}|[0-9a-f]{64})$/.test(r), "revision " + JSON.stringify(r));
    check("labels", label("licenses") === "Apache-2.0", "licenses " + JSON.stringify(label("licenses")));
    const s = label("source");
    check("labels", typeof s === "string" && /^https:\/\/\S+$/.test(s), "source " + JSON.stringify(s));
    const b = label("base.name") + "@" + label("base.digest");
    check("base", b === base, "base " + b + (b === base ? "" : ", expected " + base));
    process.exit(failed === 0 ? 0 : 1);
  });' "$arch" "$version" "$base"
