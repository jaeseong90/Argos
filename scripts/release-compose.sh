#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# release-compose.sh - the Docker Compose release asset with images pinned by digest (docs/19
# section 7.2 step 7, ADR-0010 C-171 (1)). The repository files stay as they are (their defaults are
# local builds); only the copy in the archive points at the published GHCR images.
#
# Usage: release-compose.sh [--root <dir>] X.Y.Z[-pre] <digests-file> <out.tar.gz>
#
#   <digests-file>   one line per image, written by the images job of release.yml:
#                      argos-server=ghcr.io/<owner>/argos-server:X.Y.Z@sha256:<64 hex>
#                      argos-demo-app=ghcr.io/<owner>/argos-demo-app:X.Y.Z@sha256:<64 hex>
#                      argos-demo-load=ghcr.io/<owner>/argos-demo-load:X.Y.Z@sha256:<64 hex>
#                    (the tag must be the release version; argos-env-init.sh reads the version
#                    from the server image tag)
#   <out.tar.gz>     archive to write, with one top directory argos-compose-X.Y.Z/ in the layout
#                    the Compose files expect: compose/ (deploy/compose without its tests),
#                    clickhouse/ and postgres/ (the configuration mounted from ../clickhouse and
#                    ../postgres), LICENSE and NOTICE
#
# The defaults of ${ARGOS_SERVER_IMAGE:-...}, ${ARGOS_DEMO_APP_IMAGE:-...} and
# ${ARGOS_DEMO_LOAD_IMAGE:-...} in compose/*.yaml become the digest references. Every image default
# of the result must be pinned by digest, or the script fails. The archive is reproducible: sorted
# entries, mtime SOURCE_DATE_EPOCH (default: the HEAD commit time, else 0), owner 0:0, mode 0755
# for *.sh and 0644 otherwise, gzip without name and time (GNU tar required).
#
# Exit status: 0 = written, 1 = failure, 2 = usage error.
set -euo pipefail
export LC_ALL=C

usage() {
  echo "usage: release-compose.sh [--root <dir>] X.Y.Z[-pre] <digests-file> <out.tar.gz>" >&2
  [ -n "${1:-}" ] && echo "release-compose: $1" >&2
  exit 2
}
die() {
  echo "release-compose: $*" >&2
  exit 1
}

root=
args=()
while [ $# -gt 0 ]; do
  case $1 in
    --root)
      [ $# -ge 2 ] || usage "--root needs a directory"
      root=$2
      shift
      ;;
    -*) usage "unknown option $1" ;;
    *) args+=("$1") ;;
  esac
  shift
done
[ ${#args[@]} -eq 3 ] || usage
version=${args[0]}
digests=${args[1]}
out=${args[2]}
printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-(beta|rc)\.[0-9]+)?$' \
  || usage "version '$version' is not X.Y.Z, X.Y.Z-beta.N or X.Y.Z-rc.N"
[ -f "$digests" ] || usage "digests file '$digests' not found"
case $out in *.tar.gz) ;; *) usage "the output must be a .tar.gz file" ;; esac
root=${root:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
[ -d "$root/deploy/compose" ] || die "$root/deploy/compose not found"
tar --version 2>/dev/null | grep -q 'GNU tar' || die "GNU tar is required (reproducible archive options)"

# --- digests ----------------------------------------------------------------------------------
ref_server=
ref_demo_app=
ref_demo_load=
while IFS= read -r line || [ -n "$line" ]; do
  line=${line%$'\r'}
  case $line in '' | '#'*) continue ;; esac
  name=${line%%=*}
  ref=${line#*=}
  printf '%s\n' "$ref" | grep -Eq "^[a-z0-9.-]+(:[0-9]+)?/([a-z0-9._-]+/)*$name:$(printf '%s' "$version" | sed 's/[.]/\\./g')@sha256:[0-9a-f]{64}\$" \
    || die "'$line': expected $name=<registry>/<owner>/$name:$version@sha256:<64 hex>"
  case $name in
    argos-server) ref_server=$ref ;;
    argos-demo-app) ref_demo_app=$ref ;;
    argos-demo-load) ref_demo_load=$ref ;;
    *) die "unknown image '$name' in $digests" ;;
  esac
done <"$digests"
[ -n "$ref_server" ] && [ -n "$ref_demo_app" ] && [ -n "$ref_demo_load" ] \
  || die "$digests must list argos-server, argos-demo-app and argos-demo-load"

# --- staging ----------------------------------------------------------------------------------
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
top=argos-compose-$version
stage=$work/$top
mkdir -p "$stage/compose" "$stage/clickhouse" "$stage/postgres"

# Files of deploy/compose: tracked plus untracked-but-not-ignored (git), else everything; never the
# tests, local state (.env, secrets/, tls/, overrides) or dot files other than .env.example.
if git -C "$root" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  (cd "$root" && git ls-files -z --cached --others --exclude-standard -- deploy/compose | tr '\0' '\n') >"$work/files"
else
  (cd "$root" && find deploy/compose -type f) >"$work/files"
fi
count=0
while IFS= read -r f; do
  rel=${f#deploy/compose/}
  case $rel in
    test/* | .env | secrets/* | tls/* | compose.override.yaml | backups/* | data/*) continue ;;
    .env.example) ;;
    .* | */.*) continue ;;
  esac
  [ -f "$root/$f" ] || continue
  mkdir -p "$stage/compose/$(dirname "$rel")"
  cp "$root/$f" "$stage/compose/$rel"
  count=$((count + 1))
done <"$work/files"
[ "$count" -gt 0 ] || die "no files in $root/deploy/compose"

for d in config.d users.d; do
  [ -d "$root/deploy/clickhouse/$d" ] || die "$root/deploy/clickhouse/$d not found"
  cp -R "$root/deploy/clickhouse/$d" "$stage/clickhouse/"
done
cp "$root/deploy/clickhouse/image.properties" "$stage/clickhouse/"
cp "$root/deploy/postgres/roles.sql" "$root/deploy/postgres/image.properties" "$stage/postgres/"
cp "$root/LICENSE" "$root/NOTICE" "$stage/"

# --- pin the image defaults -------------------------------------------------------------------
# replace <variable> <reference>: "${<variable>:-<anything>}" -> "${<variable>:-<reference>}" in
# compose/*.yaml; prints the number of replaced lines.
replace() {
  local var=$1 ref=$2 n=0 f c
  for f in "$stage"/compose/*.yaml; do
    c=$(grep -c "\${$var:-" "$f" || true)
    [ "$c" -gt 0 ] || continue
    sed "s#\${$var:-[^}]*}#\${$var:-$ref}#g" "$f" >"$f.tmp"
    mv "$f.tmp" "$f"
    n=$((n + c))
  done
  echo "$n"
}
[ "$(replace ARGOS_SERVER_IMAGE "$ref_server")" -ge 1 ] || die "no \${ARGOS_SERVER_IMAGE:-...} in deploy/compose/*.yaml"
[ "$(replace ARGOS_DEMO_APP_IMAGE "$ref_demo_app")" -ge 1 ] || die "no \${ARGOS_DEMO_APP_IMAGE:-...} in deploy/compose/*.yaml"
[ "$(replace ARGOS_DEMO_LOAD_IMAGE "$ref_demo_load")" -ge 1 ] || die "no \${ARGOS_DEMO_LOAD_IMAGE:-...} in deploy/compose/*.yaml"

# Every image of the result is pinned by digest (literal or variable default).
unpinned=$(grep -h '^[[:space:]]*image:' "$stage"/compose/*.yaml | grep -v '@sha256:[0-9a-f]\{64\}' || true)
[ -z "$unpinned" ] || die "image references without a digest after rendering: $unpinned"

# --- reproducible archive ---------------------------------------------------------------------
if [ -z "${SOURCE_DATE_EPOCH:-}" ]; then
  SOURCE_DATE_EPOCH=$(git -C "$root" log -1 --format=%ct 2>/dev/null || echo 0)
fi
printf '%s\n' "$SOURCE_DATE_EPOCH" | grep -Eq '^[0-9]+$' || die "SOURCE_DATE_EPOCH must be a number of seconds"
find "$stage" -type d -exec chmod 0755 {} +
find "$stage" -type f -exec chmod 0644 {} +
find "$stage" -type f -name '*.sh' -exec chmod 0755 {} +
mkdir -p "$(dirname "$out")"
out_abs=$(cd "$(dirname "$out")" && pwd)/$(basename "$out")
(cd "$work" && tar --sort=name --format=gnu \
  --mtime="@$SOURCE_DATE_EPOCH" --owner=0 --group=0 --numeric-owner \
  --mode='go-w' -cf - "$top") | gzip -n -9 >"$out_abs.tmp"
mv "$out_abs.tmp" "$out_abs"
echo "release-compose: wrote $out ($count Compose file(s), images pinned by digest)"
