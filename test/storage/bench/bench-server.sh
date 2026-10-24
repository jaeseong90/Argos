#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# bench-server.sh: a standalone ClickHouse for the storage PoC benchmark (WO-03, P-4; docs/13 section 15,
# docs/18 section 9). The bundled image (deploy/clickhouse/image.properties) runs with the repository
# configuration (deploy/clickhouse/config.d, users.d) on a named volume, so that the generator (url target)
# and QueryBench (--url) use the same server at the same time (Q17: replay and concurrent queries).
#
#   start               start the server (generates the three account passwords on first use)
#   stop | rm           stop the container | remove the container (the volume is kept)
#   restart             restart on the same volume (empties mark, uncompressed and query caches); graceful
#                       (up to 120 s) so that system.query_log keeps the measured queries
#   restart-dropcache   restart + drop the page cache of the Docker host (privileged helper container;
#                       affects every container on the host). QueryBench: --cold "command:<this> restart-dropcache"
#   wait                wait until /ping answers
#   rm-volume           remove the container and the data volume
#
# Environment (defaults in brackets):
#   ARGOS_BENCH_NAME [argos-storage-bench]   container and volume name
#   ARGOS_BENCH_PORT [18123]                 HTTP port on 127.0.0.1
#   ARGOS_BENCH_MEMORY_GIB [8]               container memory limit; the server gets 0.9 of it, a read query
#                                            0.4 and an insert 4/15 of the server (docs/13 section 7.1)
#   ARGOS_BENCH_SECRETS [test/storage/bench/build/bench-secrets.env]
#                                            generated passwords (mode 600, never committed: build/ is ignored).
#                                            The tools read them from the environment:
#                                              set -a; . test/storage/bench/build/bench-secrets.env; set +a
#
# The default user does not exist (users.d/argos.xml); only argos_writer, argos_reader and argos_migrator.
# No credential is passed on a command line: the container receives SHA-256 hashes only.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$script_dir/../../.." && pwd)"
name="${ARGOS_BENCH_NAME:-argos-storage-bench}"
port="${ARGOS_BENCH_PORT:-18123}"
memory_gib="${ARGOS_BENCH_MEMORY_GIB:-8}"
secrets="${ARGOS_BENCH_SECRETS:-$script_dir/build/bench-secrets.env}"
image="$(grep '^image=' "$repo/deploy/clickhouse/image.properties" | cut -d= -f2-)"

limit=$((memory_gib * 1024 * 1024 * 1024))
server=$((limit / 10 * 9))
reader=$((server / 5 * 2))
writer=$((server / 15 * 4))

host_path() {
  # Docker Desktop on Windows (Git Bash) needs a Windows path for bind mounts.
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

sha256_hex() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }

load_secrets() {
  if [[ ! -f "$secrets" ]]; then
    mkdir -p "$(dirname "$secrets")"
    (
      umask 077
      {
        echo "ARGOS_CH_WRITER_PASSWORD=$(openssl rand -hex 24)"
        echo "ARGOS_CH_READER_PASSWORD=$(openssl rand -hex 24)"
        echo "ARGOS_CH_MIGRATOR_PASSWORD=$(openssl rand -hex 24)"
      } >"$secrets"
    )
  fi
  # shellcheck disable=SC1090
  . "$secrets"
}

wait_ready() {
  local _
  for _ in $(seq 1 180); do
    if curl -sf --max-time 2 "http://127.0.0.1:$port/ping" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "bench-server: ClickHouse on port $port did not answer /ping" >&2
  return 1
}

case "${1:-}" in
  start)
    load_secrets
    MSYS_NO_PATHCONV=1 docker run -d --name "$name" \
      --memory "${memory_gib}g" --memory-swap "${memory_gib}g" --ulimit nofile=262144:262144 \
      -p "127.0.0.1:$port:8123" \
      -v "$name:/var/lib/clickhouse" \
      -v "$(host_path "$repo/deploy/clickhouse/config.d/argos.xml"):/etc/clickhouse-server/config.d/argos.xml:ro" \
      -v "$(host_path "$repo/deploy/clickhouse/users.d/argos.xml"):/etc/clickhouse-server/users.d/argos.xml:ro" \
      -e CLICKHOUSE_SKIP_USER_SETUP=1 \
      -e "ARGOS_CH_MAX_SERVER_MEMORY_USAGE=$server" \
      -e "ARGOS_CH_READER_MAX_MEMORY_USAGE=$reader" \
      -e "ARGOS_CH_WRITER_MAX_MEMORY_USAGE=$writer" \
      -e "ARGOS_CH_WRITER_PASSWORD_SHA256=$(sha256_hex "$ARGOS_CH_WRITER_PASSWORD")" \
      -e "ARGOS_CH_READER_PASSWORD_SHA256=$(sha256_hex "$ARGOS_CH_READER_PASSWORD")" \
      -e "ARGOS_CH_MIGRATOR_PASSWORD_SHA256=$(sha256_hex "$ARGOS_CH_MIGRATOR_PASSWORD")" \
      "$image" >/dev/null
    wait_ready
    echo "bench-server: $name on http://127.0.0.1:$port (container ${memory_gib} GiB, server $server B," \
      "read query $reader B, insert $writer B); passwords in $secrets"
    ;;
  restart)
    docker restart -t 120 "$name" >/dev/null
    wait_ready
    ;;
  restart-dropcache)
    docker restart -t 120 "$name" >/dev/null
    MSYS_NO_PATHCONV=1 docker run --rm --privileged --entrypoint sh "$image" \
      -c 'sync && echo 3 > /proc/sys/vm/drop_caches' >/dev/null
    wait_ready
    ;;
  wait) wait_ready ;;
  stop) docker stop -t 120 "$name" >/dev/null ;;
  rm) docker rm -f "$name" >/dev/null ;;
  rm-volume)
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker volume rm "$name" >/dev/null
    ;;
  *)
    echo "usage: $0 start|stop|rm|restart|restart-dropcache|wait|rm-volume" >&2
    exit 2
    ;;
esac
