#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# fetch-k6.sh: downloads the k6 release binary of the version that sample-apps/load/run-bench.sh
# pins as a container image (grafana/k6:2.3.0) for this platform, checks its SHA-256 against the
# values below (from the release's k6-v2.3.0-checksums.txt) and installs it as
# test/agent/overhead/build/k6/k6[.exe], where the overhead harness finds it (--k6 auto).
#
# k6 is AGPL-3.0: a development and benchmark tool that runs as a separate process and is never
# part of an Argos artifact (docs/licenses/dev-only.md, tool:k6; ADR-0003 C-33). Use the native
# binary when the app runs as a process on Windows or macOS: Docker Desktop's container-to-host
# proxy loses requests at 1,000/s (sample-apps/load/README.md).
#
# usage: test/agent/overhead/fetch-k6.sh        (prints the installed path)

set -euo pipefail

readonly VERSION="2.3.0"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dest="$script_dir/build/k6"

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) asset="k6-v$VERSION-linux-amd64.tar.gz"; sha="39c3117b6af817592dcd0ce4242105c0a7af10948c2a425306f0be8f7a8a8ab1" ;;
  Linux-aarch64 | Linux-arm64) asset="k6-v$VERSION-linux-arm64.tar.gz"; sha="5ca3433e8201da72a284aaa241a1bb5fb47f4abb4e384d39410ddd8062f49b90" ;;
  Darwin-x86_64) asset="k6-v$VERSION-macos-amd64.zip"; sha="c83bb16f54f0676afa4ea235fd757f3892d307dbd1bd201450de83af31986654" ;;
  Darwin-arm64) asset="k6-v$VERSION-macos-arm64.zip"; sha="b2417a3038edc5fe81dc178a889237724b595c5c9cfed875822008e46e862c7d" ;;
  MINGW*-x86_64 | MSYS*-x86_64 | CYGWIN*-x86_64) asset="k6-v$VERSION-windows-amd64.zip"; sha="112276d495e5741c968e2bc09ea6196099c1275bd6db9ee0875d173c7148ce43" ;;
  *) echo "fetch-k6: no pinned k6 binary for $(uname -s)-$(uname -m); use --k6 docker" >&2; exit 1 ;;
esac

binary="k6"
[[ "$asset" == *windows* ]] && binary="k6.exe"
if [[ -x "$dest/$binary" ]] && "$dest/$binary" version 2>/dev/null | grep -q "v$VERSION "; then
  echo "$dest/$binary"
  exit 0
fi

work="$dest/download"
rm -rf "$work"
mkdir -p "$work"
url="https://github.com/grafana/k6/releases/download/v$VERSION/$asset"
echo "fetch-k6: downloading $url" >&2
curl -fsSL --retry 3 -o "$work/$asset" "$url"
if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "$work/$asset" | cut -d' ' -f1)"
else
  actual="$(shasum -a 256 "$work/$asset" | cut -d' ' -f1)"
fi
if [[ "$actual" != "$sha" ]]; then
  echo "fetch-k6: SHA-256 mismatch for $asset: $actual (expected $sha)" >&2
  exit 1
fi
case "$asset" in
  *.tar.gz) tar -xzf "$work/$asset" -C "$work" ;;
  *.zip) unzip -q -o "$work/$asset" -d "$work" ;;
esac
found="$(find "$work" -type f -name "$binary" | head -n 1)"
[[ -n "$found" ]] || { echo "fetch-k6: $binary not found in $asset" >&2; exit 1; }
cp "$found" "$dest/$binary"
chmod +x "$dest/$binary"
rm -rf "$work"
echo "$dest/$binary"
