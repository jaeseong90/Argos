#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# argos-env-init.sh: prepares the Docker Compose installation of Argos in this directory
# (docs/17 section 3.7 and section 7.3, ADR-0010 C-171): .env (0600) with the non-secret settings
# and secrets/ (0700) with one generated secret per file (0600). Run it once, then place
# tls/server.crt and tls/server.key and run ./argos-compose.sh up. It prints no secret and passes
# none as a command argument.
#
# Usage: ./argos-env-init.sh [options]
#   --public-url URL     address of the UI in the browser (ARGOS_WEB_PUBLIC_URL);
#                        default http://<host name>:8080. A plain http:// URL whose host is not
#                        localhost, 127.0.0.1 or [::1] gets a warning (exit code 0): the Secure
#                        session cookies make signing in from other computers fail there
#   --unattended         also generate secrets/admin_password: the server creates the first
#                        administrator at its first start, no setup token wizard (needs --ip-mode)
#   --ip-mode MODE       IP address collection mode for --unattended: masked, full or off
#   --admin-user NAME    administrator name for --unattended (default admin)
#   --http-bind ADDR     host IPv4 address on which port 8080 is published (default 0.0.0.0;
#                        127.0.0.1 with --demo)
#   --grpc-bind ADDR     host IPv4 address on which port 9100 is published (default 0.0.0.0;
#                        127.0.0.1 with --demo)
#   --ram-mb N           plan the memory for N MiB instead of the memory Docker reports
#   --server-image REF   server image (default: the default of compose.yaml)
#   --force              replace an existing .env and the existing secrets. Refused (exit 2, nothing
#                        changed) while Docker volumes of this Compose project exist: their
#                        databases keep the old passwords. Delete them first only if the data may
#                        go: ./argos-compose.sh down --volumes
#   --demo               the demo profile (docs/17 section 3.9; evaluation only, never production):
#                        also writes the agent tokens of the two demo applications and the demo
#                        database password to secrets/, and to .env ARGOS_DEMO_MODE=true with
#                        COMPOSE_FILE and COMPOSE_PROFILES, so that `docker compose --profile demo
#                        up -d` starts Argos with plaintext gRPC, two demo applications and a load
#                        generator. Implies --unattended and, unless given, --ip-mode masked,
#                        --http-bind 127.0.0.1, --grpc-bind 127.0.0.1 and --public-url
#                        http://localhost:8080; reserves 1536 MiB for the demo services
#   --self-signed-tls HOST[,HOST...]
#                        also write an evaluation-only self-signed gRPC certificate, tls/server.crt
#                        (0644) and its key tls/server.key (0600), valid 365 days, with the host
#                        names and IP addresses that agents connect to as subject alternative names.
#                        Agents must trust it with server.tls.ca-file (a copy of tls/server.crt).
#                        Needs openssl 1.1.1 or later. Never for production: use a certificate from
#                        your organization (docs/install/compose.md section 4). Existing tls/server.*
#                        files are refused unless --force is given
#   -h, --help           show this text
#
# Exit codes: 0 done (warnings included); 1 unexpected failure; 2 missing prerequisite (also too
# little memory for the container limits) or usage error; 3 a secret is empty, too short or on the
# forbidden list, or a kept demo token file is not an agent token (checked before anything is
# hashed).
#
# On a Linux Docker Engine run it as root (sudo): the secret files are given to uid 10001, the user
# of the server container. Docker Desktop (Windows, macOS) needs no root.
set -eu
umask 077
# Git Bash on Windows: keep MSYS from rewriting container paths in arguments.
MSYS_NO_PATHCONV=1
export MSYS_NO_PATHCONV

prog=argos-env-init.sh
here=$(cd "$(dirname "$0")" && pwd)
cd "$here"

SERVER_UID=10001
SERVER_GID=10001
# Memory plan bounds in MiB (docs/17 section 7.3, ADR-0010 C-198, C-201).
HEAP_MIN_MB=2048         # server heap; its container is heap + 1 GiB
HEAP_MAX_MB=16384
PG_MEM_MIN_MB=1024       # PostgreSQL container
CH_MEM_FLOOR_MB=3072     # ClickHouse container: it gives way first, down to this floor
CH_MEM_MAX_MB=72818      # ClickHouse container whose 0.9 is 64 GiB (larger: by hand)
# The smallest memory on which the container limits at their lower bounds fit in 0.9 x RAM:
# (server 2048 + 1024, PostgreSQL 1024, ClickHouse 3072) / 0.9 rounded up = 7965 MiB (C-198).
NORMAL_MIN_RAM_MB=$(( ((HEAP_MIN_MB + 1024 + PG_MEM_MIN_MB + CH_MEM_FLOOR_MB) * 100 + 89) / 90 ))
# Minimum-spec tier (C-201): an "8 GB" host shows Docker about 7.5 to 7.9 GiB, less than the
# above. From MIN_RAM_MB on, the ClickHouse container may go down to 2.5 GiB and then the server
# heap down to 1.5 GiB; below MIN_RAM_MB the host is refused. The tier always fits there:
# (1536 + 1024) + 1024 + 2560 = 6144 <= 0.9 x 7168.
MIN_RAM_MB=7168
CH_MEM_TIER_FLOOR_MB=2560
HEAP_TIER_MIN_MB=1536
EVALUATION_RAM_MB=15360  # below a 16 GB host: evaluation only (docs/17 section 7.3)
# Demo profile (--demo, compose.demo.yaml, WO-41): the mem_limit of demo-db 256, sample-app-a 512,
# sample-app-b 512 and load-generator 256 MiB, reserved inside 0.9 x RAM. The demo has its own tier
# floors, below the C-201 ones: a 50 requests/s demo needs less than a production host, and an
# "8 GB" Docker Desktop (about 7.4 to 7.9 GiB visible) must run it. The smallest memory for the
# demo: (server 1024 + 1024, PostgreSQL 1024, ClickHouse 2048, demo 1536) / 0.9 = 7396 MiB.
DEMO_RESERVE_MB=1536
DEMO_CH_TIER_FLOOR_MB=2048
DEMO_HEAP_TIER_MIN_MB=1024
MIN_DISK_KB=20971520     # 20 GiB
WARN_DISK_KB=104857600   # 100 GiB
MIN_SECRET_LENGTH=12     # the server and roles.sql refuse shorter database passwords (SEC-74)
# docs/17 section 3.7 step 6 and the server's credential policy (12 section 10, 16 section 10.6).
FORBIDDEN="changeme password argos admin postgres clickhouse 123456 qwerty default"
DB_SECRETS="pg_superuser_password pg_owner_password pg_app_password pg_audit_password pg_backup_password ch_writer_password ch_reader_password ch_migrator_password"
CH_SECRETS="ch_writer_password ch_reader_password ch_migrator_password"
# --demo: the agent tokens of the two demo applications (16 section 2.1 format, registered by the
# server from config/demo/bootstrap-apps.yaml) and the password of the demo database.
DEMO_SECRETS="demo_order_api_token demo_payment_api_token demo_db_password"
CR=$(printf '\r')

die() {
  code=$1
  shift
  printf '%s: %s\n' "$prog" "$*" >&2
  exit "$code"
}
warn() { printf '%s: WARNING: %s\n' "$prog" "$*" >&2; }
usage() { sed -n '/^# Usage:/,/^# Exit codes:/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'; }

# ---------------------------------------------------------------------------------------------
# Options

public_url=
unattended=false
ip_mode=
admin_user="admin"
http_bind=
grpc_bind=
ram_mb=
server_image=
force=false
demo=false
tls_hosts=

need_value() { [ "$2" -ge 2 ] || die 2 "$1 needs a value (see --help)"; }
while [ $# -gt 0 ]; do
  case $1 in
    --public-url) need_value "$1" $#; public_url=$2; shift 2 ;;
    --public-url=*) public_url=${1#*=}; shift ;;
    --unattended) unattended=true; shift ;;
    --ip-mode) need_value "$1" $#; ip_mode=$2; shift 2 ;;
    --ip-mode=*) ip_mode=${1#*=}; shift ;;
    --admin-user) need_value "$1" $#; admin_user=$2; shift 2 ;;
    --admin-user=*) admin_user=${1#*=}; shift ;;
    --http-bind) need_value "$1" $#; http_bind=$2; shift 2 ;;
    --http-bind=*) http_bind=${1#*=}; shift ;;
    --grpc-bind) need_value "$1" $#; grpc_bind=$2; shift 2 ;;
    --grpc-bind=*) grpc_bind=${1#*=}; shift ;;
    --ram-mb) need_value "$1" $#; ram_mb=$2; shift 2 ;;
    --ram-mb=*) ram_mb=${1#*=}; shift ;;
    --server-image) need_value "$1" $#; server_image=$2; shift 2 ;;
    --server-image=*) server_image=${1#*=}; shift ;;
    --force) force=true; shift ;;
    --demo) demo=true; shift ;;
    --self-signed-tls) need_value "$1" $#; tls_hosts=$2; [ -n "$tls_hosts" ] || die 2 "--self-signed-tls needs host names or IP addresses"; shift 2 ;;
    --self-signed-tls=*) tls_hosts=${1#*=}; [ -n "$tls_hosts" ] || die 2 "--self-signed-tls needs host names or IP addresses"; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die 2 "unknown option: $1 (see --help)" ;;
  esac
done

is_ipv4() {
  case $1 in
    '' | *[!0-9.]* | .* | *. | *..*) return 1 ;;
  esac
  old_ifs=$IFS
  IFS=.
  # shellcheck disable=SC2086 # split on dots on purpose
  set -- $1
  IFS=$old_ifs
  [ $# -eq 4 ] || return 1
  for part in "$@"; do
    [ "${#part}" -le 3 ] && [ "$part" -le 255 ] || return 1
  done
}

# --demo: unattended with masked IP addresses, published on this host only, UI at localhost
# (explicit options win).
if $demo; then
  unattended=true
  [ -n "$ip_mode" ] || ip_mode=masked
  [ -n "$http_bind" ] || http_bind=127.0.0.1
  [ -n "$grpc_bind" ] || grpc_bind=127.0.0.1
  [ -n "$public_url" ] || public_url=http://localhost:8080
fi
[ -n "$http_bind" ] || http_bind=0.0.0.0
[ -n "$grpc_bind" ] || grpc_bind=0.0.0.0

is_ipv4 "$http_bind" || die 2 "--http-bind must be an IPv4 address such as 0.0.0.0 or 127.0.0.1"
is_ipv4 "$grpc_bind" || die 2 "--grpc-bind must be an IPv4 address such as 0.0.0.0 or 127.0.0.1"
case $ram_mb in
  '') ;;
  *[!0-9]*) die 2 "--ram-mb must be a number of MiB" ;;
esac
if $unattended; then
  case $ip_mode in
    masked | full | off) ;;
    '') die 2 "--unattended needs --ip-mode masked, full or off (SEC-31: the IP collection mode is an explicit choice)" ;;
    *) die 2 "--ip-mode must be masked, full or off" ;;
  esac
  case $admin_user in
    '' | *[!A-Za-z0-9._@+-]* | [._@+-]*) die 2 "--admin-user: letters, digits and . _ @ + - only, starting with a letter or digit" ;;
  esac
  [ "${#admin_user}" -le 64 ] || die 2 "--admin-user: at most 64 characters"
elif [ -n "$ip_mode" ]; then
  die 2 "--ip-mode applies to --unattended only (the setup wizard asks for it)"
fi
if [ -z "$public_url" ]; then
  public_url="http://$(uname -n):8080"
fi
case $public_url in
  http://?* | https://?*) ;;
  *) die 2 "--public-url must be an absolute http or https URL, e.g. https://argos.example.com" ;;
esac
case $public_url in
  *@* | *[[:space:]]* | *'"'* | *"'"*) die 2 "--public-url must not contain user information, quotes or spaces" ;;
esac
# --self-signed-tls (ADR-0010 C-172 (1)): the subject alternative names, DNS or IP by the form of
# each entry.
tls_san=
tls_names=
if [ -n "$tls_hosts" ]; then
  ! $demo || die 2 "--self-signed-tls does not apply to --demo (the demo has no gRPC certificate)"
  tls_count=0
  set -f
  old_ifs=$IFS
  IFS=,
  for h in $tls_hosts; do
    IFS=$old_ifs
    h=$(printf '%s' "$h" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
    case $h in \[*\]) h=${h#\[}; h=${h%\]} ;; esac
    if [ -z "$h" ]; then
      die 2 "--self-signed-tls: an empty name in '$tls_hosts'"
    elif is_ipv4 "$h"; then
      entry="IP:$h"
    elif printf '%s\n' "$h" | grep -Eq '^[0-9A-Fa-f]*:[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*$'; then
      entry="IP:$h"
    elif [ "${#h}" -le 253 ] && printf '%s\n' "$h" | grep -Eq '^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$'; then
      entry="DNS:$(printf '%s' "$h" | tr '[:upper:]' '[:lower:]')"
    else
      die 2 "--self-signed-tls: '$h' is not a host name, an IPv4 or an IPv6 address"
    fi
    tls_count=$((tls_count + 1))
    tls_san="${tls_san:+$tls_san,}$entry"
    tls_names="${tls_names:+$tls_names, }$h"
    IFS=,
  done
  IFS=$old_ifs
  set +f
  [ "$tls_count" -le 16 ] || die 2 "--self-signed-tls: at most 16 names"
fi

if [ -z "$server_image" ]; then
  # shellcheck disable=SC2016 # matches the literal ${ARGOS_SERVER_IMAGE:-...} of compose.yaml
  server_image=$(sed -n 's/^ *image: *\${ARGOS_SERVER_IMAGE:-\([^}]*\)}.*$/\1/p' compose.yaml | head -n 1)
  [ -n "$server_image" ] || die 1 "cannot read the default server image from compose.yaml"
fi
case $server_image in
  '' | *[[:space:]]* | *'"'*) die 2 "--server-image is not an image reference" ;;
esac
image_ref=${server_image%%@*}
image_name=${image_ref##*/}
case $image_name in
  *:*) version=${image_name##*:} ;;
  *) version= ;;
esac

# --demo: the demo images. In the repository compose.demo.yaml defaults to local builds, and .env
# names the local builds of the server image's version. The release asset (scripts/release-compose.sh)
# pins the defaults to the published registry images by digest: then .env does not override them
# (ADR-0010 C-246 (11)). demo_image_lines holds the .env lines, demo_local_images whether any is local.
demo_image_lines=
demo_local_images=false
if $demo; then
  for pair in "ARGOS_DEMO_APP_IMAGE argos-demo-app" "ARGOS_DEMO_LOAD_IMAGE argos-demo-load"; do
    demo_var=${pair%% *}
    # The default of the line "image: ${<variable>:-<default>}" of compose.demo.yaml.
    demo_default=$(awk -v v="$demo_var" '{ i = index($0, "${" v ":-"); if (i > 0) { s = substr($0, i + length(v) + 4); print substr(s, 1, index(s, "}") - 1); exit } }' compose.demo.yaml 2>/dev/null || true)
    case $demo_default in
      */*) ;; # a registry image pinned by the release asset: keep the default
      *)
        demo_local_images=true
        demo_image_lines="$demo_image_lines$demo_var=${pair#* }${version:+:$version}
"
        ;;
    esac
  done
fi

# ---------------------------------------------------------------------------------------------
# Step 1: prerequisites (exit 2 with the list of what is missing)

missing=
add_missing() {
  missing="$missing
  - $*"
}

docker_os=
docker_mem=
docker_root=
docker_security=
if ! command -v docker >/dev/null 2>&1; then
  add_missing "docker: Docker Engine 24 or later"
else
  server_version=$(docker version --format '{{.Server.Version}}' 2>/dev/null || true)
  if [ -z "$server_version" ]; then
    add_missing "a running Docker daemon that this user may use ('docker version' failed)"
  else
    major=${server_version%%.*}
    case $major in '' | *[!0-9]*) major=0 ;; esac
    [ "$major" -ge 24 ] || add_missing "Docker Engine 24 or later (found $server_version)"
    info=$(docker info --format '{{.OperatingSystem}}|{{.MemTotal}}|{{.DockerRootDir}}|{{json .SecurityOptions}}' 2>/dev/null || true)
    docker_os=${info%%|*}
    rest=${info#*|}
    docker_mem=${rest%%|*}
    rest=${rest#*|}
    docker_root=${rest%%|*}
    docker_security=${rest#*|}
  fi
  compose_version=$(docker compose version --short 2>/dev/null || true)
  compose_version=${compose_version#v}
  c_major=${compose_version%%.*}
  c_rest=${compose_version#*.}
  c_minor=${c_rest%%.*}
  case $c_major in '' | *[!0-9]*) c_major=0 ;; esac
  case $c_minor in '' | *[!0-9]*) c_minor=0 ;; esac
  if [ "$c_major" -lt 2 ] || { [ "$c_major" -eq 2 ] && [ "$c_minor" -lt 24 ]; }; then
    add_missing "Docker Compose v2.24 or later ('docker compose version'${compose_version:+, found $compose_version})"
  fi
fi

rng=
if command -v openssl >/dev/null 2>&1; then
  rng=openssl
elif [ -r /dev/urandom ] && command -v base64 >/dev/null 2>&1 && command -v od >/dev/null 2>&1; then
  rng=urandom
else
  add_missing "a random generator: openssl, or /dev/urandom with base64 and od"
fi
sha=
if command -v sha256sum >/dev/null 2>&1; then
  sha=sha256sum
elif command -v shasum >/dev/null 2>&1; then
  sha=shasum
elif command -v openssl >/dev/null 2>&1; then
  sha=openssl
else
  add_missing "a SHA-256 tool: sha256sum, shasum or openssl"
fi
if [ -n "$tls_san" ]; then
  if ! command -v openssl >/dev/null 2>&1; then
    add_missing "openssl 1.1.1 or later for --self-signed-tls"
  elif ! openssl req -help 2>&1 | grep -q -- '-addext'; then
    add_missing "openssl 1.1.1 or later for --self-signed-tls (found $(openssl version 2>/dev/null || echo 'an older one'))"
  fi
fi

disk_dir=$here
if [ -n "$docker_root" ] && [ -d "$docker_root" ]; then
  disk_dir=$docker_root
fi
avail_kb=$(df -Pk "$disk_dir" 2>/dev/null | awk 'NR == 2 { print $4 }' || true)
case $avail_kb in
  '' | *[!0-9]*) avail_kb= ;;
esac
if [ -n "$avail_kb" ] && [ "$avail_kb" -lt "$MIN_DISK_KB" ]; then
  add_missing "disk space: $((avail_kb / 1048576)) GiB free under $disk_dir, at least 20 GiB needed"
fi

ram_source="--ram-mb"
ram=$ram_mb
if [ -z "$ram" ]; then
  case $docker_mem in
    '' | *[!0-9]*) ;;
    *) ram=$((docker_mem / 1048576)); ram_source="Docker (docker info)" ;;
  esac
fi
if [ -z "$ram" ] && [ -r /proc/meminfo ]; then
  ram=$(awk '/^MemTotal:/ { print int($2 / 1024) }' /proc/meminfo)
  ram_source="/proc/meminfo"
fi

# ---------------------------------------------------------------------------------------------
# Step 2: memory plan (docs/17 section 7.3, ADR-0010 C-198), computed with the prerequisites so
# that a host which is too small is listed with them. All values in MiB unless named *_bytes.
#   server heap = RAM x 0.25 within [2 GiB, 16 GiB]; server container = heap + 1 GiB
#   PostgreSQL shared_buffers = RAM x 0.03 within [256 MiB, 4 GiB]; container = 3 x that, >= 1 GiB
#   ClickHouse container = RAM x 0.47 / 0.9 (about 0.52 x RAM) within [3 GiB, 72818 MiB]
#   server container + PostgreSQL container + ClickHouse container <= RAM x 0.9: the ClickHouse
#   container gives way first, down to its 3 GiB floor (C-198)
#   minimum-spec tier (C-201, RAM from 7168 to 7964 MiB, where the floors above do not fit): the
#   ClickHouse container goes down to 2.5 GiB, then the server heap down to 1.5 GiB; with a
#   warning. Below 7168 MiB the host is refused (exit 2)
#   ClickHouse max_server_memory_usage = 0.9 x its container limit: the headroom inside the
#   container, and the value ClickHouse would cap a larger one to (WO-03 report E)
#   per-query limits: writer 4/15, reader 2/5 of max_server_memory_usage (deploy/clickhouse)

clamp() { # clamp <value> <min> <max>
  if [ "$1" -lt "$2" ]; then
    echo "$2"
  elif [ "$1" -gt "$3" ]; then
    echo "$3"
  else
    echo "$1"
  fi
}

# With --demo the demo services' DEMO_RESERVE_MB come off the budget first and the tier floors are
# the demo ones (DEMO_CH_TIER_FLOOR_MB, DEMO_HEAP_TIER_MIN_MB).
reserve_mb=0
ch_tier_floor=$CH_MEM_TIER_FLOOR_MB
heap_tier_min=$HEAP_TIER_MIN_MB
if $demo; then
  reserve_mb=$DEMO_RESERVE_MB
  ch_tier_floor=$DEMO_CH_TIER_FLOOR_MB
  heap_tier_min=$DEMO_HEAP_TIER_MIN_MB
fi
# The memory from which the plan needs no tier (C-198 floors, plus the demo reserve).
normal_min_ram=$(( ((HEAP_MIN_MB + 1024 + PG_MEM_MIN_MB + CH_MEM_FLOOR_MB + reserve_mb) * 100 + 89) / 90 ))

# plan_memory: sets the plan variables from $ram. min_tier=true when the minimum-spec tier was
# needed (C-201); plan_fits=false when even its floors leave the sum of the container limits
# above the budget (not reachable from MIN_RAM_MB on without --demo; a guard).
plan_memory() {
  heap=$(clamp $((ram / 4)) "$HEAP_MIN_MB" "$HEAP_MAX_MB")
  server_mem=$((heap + 1024))
  shared_buffers=$(clamp $((ram * 3 / 100)) 256 4096)
  pg_mem=$((shared_buffers * 3))
  if [ "$pg_mem" -lt "$PG_MEM_MIN_MB" ]; then
    pg_mem=$PG_MEM_MIN_MB
  fi
  ch_target=$((ram * 47 / 100)) # the max_server_memory_usage of docs/17 section 7.3, 0.47 x RAM
  ch_mem=$(clamp $(((ch_target * 10 + 8) / 9)) "$CH_MEM_FLOOR_MB" "$CH_MEM_MAX_MB")
  budget=$((ram * 90 / 100 - reserve_mb))
  over=$((server_mem + pg_mem + ch_mem - budget))
  plan_fits=true
  min_tier=false
  if [ "$over" -gt 0 ]; then
    ch_mem=$((ch_mem - over))
  fi
  if [ "$ch_mem" -lt "$CH_MEM_FLOOR_MB" ] && [ "$heap" -gt "$HEAP_MIN_MB" ]; then
    # Only with --demo (the reserve): a heap above its 2 GiB floor gives way before ClickHouse goes
    # below its 3 GiB floor. Without --demo the heap is 2 GiB wherever ClickHouse is below 3 GiB.
    ch_mem=$CH_MEM_FLOOR_MB
    over=$((server_mem + pg_mem + ch_mem - budget))
    cut=$((heap - HEAP_MIN_MB))
    if [ "$over" -lt "$cut" ]; then
      cut=$over
    fi
    if [ "$cut" -gt 0 ]; then
      heap=$((heap - cut))
      server_mem=$((heap + 1024))
      over=$((over - cut))
    fi
    if [ "$over" -gt 0 ]; then
      ch_mem=$((ch_mem - over))
    fi
  fi
  if [ "$ch_mem" -lt "$CH_MEM_FLOOR_MB" ]; then
    # C-201 minimum-spec tier: ClickHouse first, down to 2.5 GiB (2 GiB with --demo), then the
    # server heap.
    min_tier=true
    if [ "$ch_mem" -lt "$ch_tier_floor" ]; then
      ch_mem=$ch_tier_floor
    fi
    over=$((server_mem + pg_mem + ch_mem - budget))
    if [ "$over" -gt 0 ]; then
      heap=$((heap - over))
      if [ "$heap" -lt "$heap_tier_min" ]; then
        heap=$heap_tier_min
        plan_fits=false
      fi
      server_mem=$((heap + 1024))
    fi
  fi
  ch_max_bytes=$((ch_mem * 1048576 * 9 / 10))
  ch_max=$((ch_max_bytes / 1048576))
  ch_writer_bytes=$((ch_max_bytes * 4 / 15))
  ch_reader_bytes=$((ch_max_bytes * 2 / 5))
  effective_cache=$((shared_buffers * 2))
  total=$((server_mem + pg_mem + ch_mem))
}

demo_note=
if $demo; then
  demo_note=" + demo services $DEMO_RESERVE_MB"
fi

if [ -z "$ram" ]; then
  add_missing "the memory size: pass --ram-mb"
elif [ "$ram" -lt "$MIN_RAM_MB" ]; then
  add_missing "memory: $ram MiB from $ram_source; at least $MIN_RAM_MB MiB visible to Docker is needed (the 8 GB minimum; ADR-0010 C-201), $NORMAL_MIN_RAM_MB MiB or more for the memory plan without the minimum-spec tier, 16 GB or more for production. Give the host, or the Docker Desktop VM, more memory"
else
  plan_memory
  if ! $plan_fits; then
    add_missing "memory: $ram MiB from $ram_source is too small for the container limits: server $server_mem + PostgreSQL $pg_mem + ClickHouse $ch_mem$demo_note MiB = $((total + reserve_mb)) MiB exceed 0.9 x RAM = $((budget + reserve_mb)) MiB. Give the host, or the Docker Desktop VM, more memory"
  fi
fi

# On a Linux Docker Engine, bind-mounted secrets keep their host owner and mode: the server
# (uid 10001) reads them only if they belong to it. Docker Desktop maps file ownership itself.
give_secrets=false
if [ "$(uname -s)" = Linux ] && [ -n "$docker_os" ]; then
  case $docker_os in
    *"Docker Desktop"*) ;;
    *) give_secrets=true ;;
  esac
fi
case $docker_security in
  *rootless*)
    add_missing "a rootful Docker Engine: rootless Docker is not supported by this version (container users map to other host users)"
    give_secrets=false
    ;;
esac
if $give_secrets && [ "$(id -u)" != 0 ]; then
  add_missing "root: run 'sudo ./argos-env-init.sh' (on a Linux Docker Engine the secret files are given to uid $SERVER_UID, the server's user)"
fi

if [ -n "$missing" ]; then
  printf '%s: cannot prepare the installation, missing:%s\n' "$prog" "$missing" >&2
  exit 2
fi

# --self-signed-tls never replaces a certificate or key in place (an operator's certificate may be
# there), unless --force.
if [ -n "$tls_san" ] && ! $force; then
  for f in tls/server.crt tls/server.key; do
    if [ -e "$f" ]; then
      die 2 "--self-signed-tls refused, nothing was changed: $f exists. Remove tls/server.crt and tls/server.key to create new ones, or use --force on a new installation"
    fi
  done
fi

if [ -z "$avail_kb" ]; then
  warn "cannot measure the free disk space under $disk_dir; at least 20 GiB (100 GiB recommended) is needed"
elif [ "$avail_kb" -lt "$WARN_DISK_KB" ]; then
  warn "$((avail_kb / 1048576)) GiB free under $disk_dir; 100 GiB or more is recommended (docs/17 section 7.4)"
fi

# ---------------------------------------------------------------------------------------------
# Step 3 and 6: secrets. Kept files are checked first, generated ones too; nothing is written,
# hashed or given away before every secret passed (exit 3).

secret_names="$DB_SECRETS master_key metrics_token"
if $unattended; then
  secret_names="$secret_names admin_password"
fi
if $demo; then
  secret_names="$secret_names $DEMO_SECRETS"
fi

rand_b64url() { # rand_b64url <bytes>
  if [ "$rng" = openssl ]; then
    openssl rand -base64 "$1"
  else
    dd if=/dev/urandom bs="$1" count=1 2>/dev/null | base64
  fi | tr -d '\r\n=' | tr '+/' '-_'
}

rand_hex() { # rand_hex <bytes>
  if [ "$rng" = openssl ]; then
    openssl rand -hex "$1"
  else
    dd if=/dev/urandom bs="$1" count=1 2>/dev/null | od -An -v -tx1
  fi | tr -d ' \r\n'
}

sha256_hex() { # reads the value from stdin
  case $sha in
    sha256sum) sha256sum ;;
    shasum) shasum -a 256 ;;
    *) openssl dgst -sha256 -r ;;
  esac | awk '{ print $1 }'
}

# read_secret <file>: the first line of the file into secret_value (as the server reads it).
read_secret() {
  secret_value=
  IFS= read -r secret_value <"$1" || true
  case $secret_value in
    *"$CR") secret_value=${secret_value%"$CR"} ;;
  esac
}

lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

# secret_problem <name> <value>: prints why the value is refused, nothing when it is accepted.
secret_problem() {
  trimmed=$(printf '%s' "$2" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
  if [ -z "$trimmed" ]; then
    echo "is empty"
    return 0
  fi
  case $1 in
    master_key)
      case $trimmed in
        *[!0-9a-f]*) echo "is not 64 lower-case hex characters (32 bytes)"; return 0 ;;
      esac
      [ "${#trimmed}" -eq 64 ] || echo "is not 64 lower-case hex characters (32 bytes)"
      return 0
      ;;
    demo_*_token)
      # The agent token format of docs/16 section 2.1, which the server checks at registration.
      case $trimmed in
        argos_*) body=${trimmed#argos_} ;;
        *) body=! ;;
      esac
      case $body in
        *[!A-Za-z0-9_-]*) echo "is not an agent token (argos_ and 43 base64url characters)"; return 0 ;;
      esac
      [ "${#body}" -eq 43 ] || echo "is not an agent token (argos_ and 43 base64url characters)"
      return 0
      ;;
  esac
  low=$(lower "$trimmed")
  for word in $FORBIDDEN; do
    if [ "$low" = "$word" ]; then
      echo "is a forbidden default password"
      return 0
    fi
  done
  if [ "${#trimmed}" -lt "$MIN_SECRET_LENGTH" ]; then
    echo "is shorter than $MIN_SECRET_LENGTH characters"
    return 0
  fi
  case $1 in
    metrics_token)
      [ "${#trimmed}" -ge 32 ] || echo "is shorter than 32 characters"
      ;;
    admin_password)
      # The server's password policy (16 section 7.9): no "argos", not the user name.
      user_low=$(lower "$admin_user")
      case $low in
        *argos* | *"$user_low"*) echo "contains 'argos' or the administrator name" ;;
      esac
      ;;
  esac
  return 0
}

generate_secret() { # generate_secret <name>: prints a new value
  case $1 in
    master_key) rand_hex 32 ;;
    admin_password) rand_b64url 18 ;;
    demo_*_token) printf 'argos_%s' "$(rand_b64url 32)" ;;
    *) rand_b64url 32 ;;
  esac
}

expected_length() {
  case $1 in
    master_key) echo 64 ;;
    admin_password) echo 24 ;;
    demo_*_token) echo 49 ;;
    *) echo 43 ;;
  esac
}

if [ -e secrets ] && [ ! -d secrets ]; then
  die 2 "secrets exists and is not a directory"
fi

# The Compose project name as docker compose resolves it: the environment, then .env, then the
# top-level name of compose.yaml (argos).
compose_project() {
  p=${COMPOSE_PROJECT_NAME:-}
  if [ -z "$p" ] && [ -r .env ]; then
    p=$(grep -E '^[[:space:]]*(export[[:space:]]+)?COMPOSE_PROJECT_NAME=' .env | tail -n 1 | sed -e 's/^[^=]*=//' \
      -e 's/[[:space:]]*$//' -e 's/^[[:space:]]*//' -e 's/^"\(.*\)"$/\1/' -e "s/^'\\(.*\\)'\$/\\1/" || true)
  fi
  if [ -z "$p" ]; then
    p=$(sed -n 's/^name:[[:space:]]*\([A-Za-z0-9_-]*\).*$/\1/p' compose.yaml | head -n 1)
  fi
  printf '%s' "${p:-argos}"
}

# --force writes new database passwords, but PostgreSQL and ClickHouse keep the accounts of their
# first start in the volumes: with volumes left the server could not sign in any more.
if $force; then
  project=$(compose_project)
  volumes=$(docker volume ls -q --filter "label=com.docker.compose.project=$project" 2>/dev/null) \
    || die 2 "--force: cannot list the Docker volumes of the Compose project '$project' ('docker volume ls' failed); nothing was changed"
  volumes=$(printf '%s' "$volumes" | tr '\n' ' ' | sed 's/ *$//')
  if [ -n "$volumes" ]; then
    die 2 "--force refused, nothing was changed: the Compose project '$project' still has volumes ($volumes) whose databases keep the old passwords. If their data may be deleted, run ./argos-compose.sh down --volumes and then this command again."
  fi
fi

violations=
for name in $secret_names; do
  if [ -e "secrets/$name" ] && ! $force; then
    read_secret "secrets/$name"
    problem=$(secret_problem "$name" "$secret_value")
    if [ -n "$problem" ]; then
      violations="$violations
  - secrets/$name $problem"
    fi
  fi
done
secret_value=
if [ -n "$violations" ]; then
  printf '%s: refused (SEC-74), nothing was changed:%s\nReplace these files or remove them to have new values generated.\n' "$prog" "$violations" >&2
  exit 3
fi

mkdir -p secrets
chmod 0700 secrets

write_private() { # write_private <file> <content>
  tmp_file="$1.tmp.$$"
  printf '%s' "$2" >"$tmp_file"
  chmod 0600 "$tmp_file"
  mv -f "$tmp_file" "$1"
}

created=
kept=
for name in $secret_names; do
  if [ -e "secrets/$name" ] && ! $force; then
    kept="$kept $name"
    continue
  fi
  attempt=0
  while :; do
    attempt=$((attempt + 1))
    value=$(generate_secret "$name")
    if [ "${#value}" -ne "$(expected_length "$name")" ]; then
      die 1 "the random generator ($rng) returned a value of unexpected length for $name"
    fi
    problem=$(secret_problem "$name" "$value")
    [ -n "$problem" ] || break
    if [ "$attempt" -ge 20 ]; then
      die 3 "could not generate an acceptable value for $name ($problem)"
    fi
  done
  write_private "secrets/$name" "$value"
  value=
  created="$created $name"
done

# Step 4: SHA-256 of the ClickHouse passwords (users.d/argos.xml from_env). Derived from the files
# above, so it is rewritten every time.
ch_env="# Written by argos-env-init.sh: SHA-256 (hex) of the ClickHouse passwords in this directory.
# Passed to the ClickHouse container with env_file (compose.yaml). No password here."
for name in $CH_SECRETS; do
  read_secret "secrets/$name"
  hash=$(printf '%s' "$secret_value" | sha256_hex)
  secret_value=
  case $hash in
    *[!0-9a-f]* | '') die 1 "could not hash secrets/$name" ;;
  esac
  [ "${#hash}" -eq 64 ] || die 1 "could not hash secrets/$name"
  var=$(printf '%s' "$name" | sed -e 's/_password$//' | tr '[:lower:]' '[:upper:]')
  ch_env="$ch_env
ARGOS_${var}_PASSWORD_SHA256=$hash"
done
write_private secrets/clickhouse.env "$ch_env
"

if $give_secrets; then
  for name in $secret_names; do
    chown "$SERVER_UID:$SERVER_GID" "secrets/$name"
  done
fi

# ---------------------------------------------------------------------------------------------
# Step 5: .env (0600): version, image, addresses, memory, JVM options. No secret.

tier_note=
if $min_tier; then
  tier_note="; minimum-spec tier, C-201"
fi
if $demo; then
  tier_note="$tier_note; $DEMO_RESERVE_MB MiB reserved for the demo services of compose.demo.yaml"
fi
memory_block="# Memory plan for $ram MiB ($ram_source; docs/17 section 7.3, ADR-0010 C-198$tier_note)
ARGOS_SERVER_MEM=${server_mem}m
ARGOS_SERVER_JAVA_OPTS=\"-Xms${heap}m -Xmx${heap}m\"
ARGOS_CH_MEM=${ch_mem}m
ARGOS_CH_MAX_SERVER_MEMORY_USAGE=$ch_max_bytes
ARGOS_CH_WRITER_MAX_MEMORY_USAGE=$ch_writer_bytes
ARGOS_CH_READER_MAX_MEMORY_USAGE=$ch_reader_bytes
ARGOS_PG_MEM=${pg_mem}m
ARGOS_PG_SHARED_BUFFERS=${shared_buffers}MB
ARGOS_PG_EFFECTIVE_CACHE_SIZE=${effective_cache}MB"

env_written=false
if [ -e .env ] && ! $force; then
  existing_mode=$(sed -n 's/^ARGOS_UNATTENDED=//p' .env | tail -n 1 | tr -d '"')
  if [ "${existing_mode:-false}" != "$unattended" ]; then
    warn ".env is kept and has ARGOS_UNATTENDED=${existing_mode:-false}; edit it, or rerun with --force on a new installation"
  fi
  existing_demo=$(sed -n 's/^ARGOS_DEMO_MODE=//p' .env | tail -n 1 | tr -d '"')
  if [ "${existing_demo:-false}" != "$demo" ]; then
    warn ".env is kept and has ARGOS_DEMO_MODE=${existing_demo:-false}; rerun with --force on a new installation (after ./argos-compose.sh down --volumes) to change it"
  fi
else
  unattended_block="ARGOS_UNATTENDED=$unattended"
  if $unattended; then
    unattended_block="$unattended_block
ARGOS_IP_MODE=$ip_mode
ARGOS_ADMIN_USERNAME=$admin_user"
  fi
  # Only --demo writes the demo variables (docs/17 section 3.2: a production installation never
  # sets ARGOS_DEMO_MODE). COMPOSE_FILE and COMPOSE_PROFILES make plain `docker compose --profile
  # demo up -d` and `down -v` use compose.demo.yaml.
  demo_block=
  if $demo; then
    demo_block="# Demo profile (argos-env-init.sh --demo): evaluation only, plaintext gRPC, sample traffic.
ARGOS_DEMO_MODE=true
${demo_image_lines}COMPOSE_FILE=compose.yaml:compose.unattended.yaml:compose.demo.yaml
COMPOSE_PATH_SEPARATOR=:
COMPOSE_PROFILES=demo
"
  fi
  write_private .env "# Argos Docker Compose settings, written by argos-env-init.sh on $(date -u '+%Y-%m-%dT%H:%M:%SZ').
# No secrets: they are files in secrets/. Every variable is described in .env.example.
ARGOS_VERSION=$version
ARGOS_SERVER_IMAGE=$server_image
ARGOS_WEB_PUBLIC_URL=$public_url
ARGOS_HTTP_BIND=$http_bind
ARGOS_GRPC_BIND=$grpc_bind
ARGOS_MGMT_EXPOSE=false
ARGOS_PROXY=false
$unattended_block
${demo_block}ARGOS_START_PERIOD=60s
ARGOS_LOG_LEVEL=INFO
ARGOS_TZ_DISPLAY=UTC
$memory_block
# Retention in days (docs/13 defaults). RAW_STAT and METRIC must be equal in this version.
ARGOS_RETENTION_XLOG_DAYS=30
ARGOS_RETENTION_PROFILE_DAYS=7
ARGOS_RETENTION_RAW_STAT_DAYS=14
ARGOS_RETENTION_METRIC_DAYS=14
ARGOS_RETENTION_ROLLUP_1M_DAYS=90
ARGOS_RETENTION_ROLLUP_1H_DAYS=365
"
  env_written=true
fi

# ---------------------------------------------------------------------------------------------
# Step 5b (--self-signed-tls, ADR-0010 C-172 (1)): an evaluation-only self-signed gRPC certificate.
# The key is written under umask 077 and never printed.

tls_written=false
if [ -n "$tls_san" ]; then
  mkdir -p tls
  chmod 0700 tls
  tmp_key="tls/server.key.tmp.$$"
  tmp_crt="tls/server.crt.tmp.$$"
  if ! openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -sha256 -nodes -days 365 \
    -subj "/CN=Argos evaluation (self-signed)" -addext "subjectAltName=$tls_san" \
    -keyout "$tmp_key" -out "$tmp_crt" >"tls/.openssl.err.$$" 2>&1; then
    reason=$(grep -v '^[.+*]*$' "tls/.openssl.err.$$" | tail -n 1)
    rm -f "$tmp_key" "$tmp_crt" "tls/.openssl.err.$$"
    die 1 "openssl could not create the self-signed certificate for $tls_san ($reason); .env and secrets/ are ready, run this command again with a corrected --self-signed-tls"
  fi
  rm -f "tls/.openssl.err.$$"
  chmod 0600 "$tmp_key"
  chmod 0644 "$tmp_crt"
  if $give_secrets; then
    chown "$SERVER_UID:$SERVER_GID" "$tmp_key"
  fi
  mv -f "$tmp_key" tls/server.key
  mv -f "$tmp_crt" tls/server.crt
  tls_written=true
fi

# ---------------------------------------------------------------------------------------------
# Step 7: summary. No secret is printed.

# shellcheck disable=SC2086 # splits the word list on purpose
count_words() { set -- $1; echo $#; }

# env_file_value <NAME>: the last value of NAME in .env, without quotes (as docker compose reads it).
env_file_value() {
  [ -r .env ] || return 0
  grep -E "^[[:space:]]*(export[[:space:]]+)?$1=" .env | tail -n 1 | sed -e 's/^[^=]*=//' \
    -e 's/[[:space:]]*$//' -e 's/^[[:space:]]*//' -e 's/^"\(.*\)"$/\1/' -e "s/^'\\(.*\\)'\$/\\1/" || true
}

# url_host <url>: the host of an http(s) URL in lower case, an IPv6 address with its brackets.
url_host() {
  h=${1#*://}
  h=${h%%/*}
  h=${h%%\?*}
  h=${h%%#*}
  case $h in
    \[*\]*) h="${h%%]*}]" ;;
    *) h=${h%%:*} ;;
  esac
  lower "$h"
}

# The public URL in force: the one written now, or the one of the kept .env.
effective_url=$(env_file_value ARGOS_WEB_PUBLIC_URL)
if [ -z "$effective_url" ]; then
  effective_url=$public_url
fi

printf 'Argos Compose installation prepared in %s\n' "$here"
if $env_written; then
  printf '  .env            written (0600): addresses, memory, JVM options; no secrets\n'
else
  printf '  .env            kept (use --force to rewrite it)\n'
fi
printf '  secrets/        %s generated, %s kept (directory 0700, files 0600)%s\n' \
  "$(count_words "$created")" "$(count_words "$kept")" \
  "$(if $give_secrets; then printf '; owned by uid %s' "$SERVER_UID"; fi)"
printf '  secrets/clickhouse.env   SHA-256 of the ClickHouse passwords (0600)\n'
printf '\nMemory plan for %s MiB (%s), container limits:\n' "$ram" "$ram_source"
printf '  server       %6s MiB, heap %s MiB\n' "$server_mem" "$heap"
printf '  ClickHouse   %6s MiB, max_server_memory_usage %s MiB (0.9 x the container)\n' "$ch_mem" "$ch_max"
printf '  PostgreSQL   %6s MiB, shared_buffers %s MB\n' "$pg_mem" "$shared_buffers"
if $demo; then
  printf '  demo         %6s MiB (demo-db, sample-app-a, sample-app-b, load-generator)\n' "$reserve_mb"
fi
printf '  total        %6s MiB of %s MiB (budget 0.9 x RAM = %s MiB)\n' "$((total + reserve_mb))" "$ram" "$((budget + reserve_mb))"
if ! $env_written; then
  printf '  (not applied: .env was kept)\n'
fi
if [ "$ram" -lt "$EVALUATION_RAM_MB" ]; then
  warn "less than 16 GB of memory: an evaluation setup only; use 16 GB or more for production"
fi
if $min_tier; then
  heap_note=
  if [ "$heap" -lt "$HEAP_MIN_MB" ]; then
    heap_note=" and the server heap $heap MiB (normally at least $HEAP_MIN_MB)"
  fi
  warn "minimum-spec tier (ADR-0010 C-201): $ram MiB visible to Docker is below the $normal_min_ram MiB of the normal memory plan$(if $demo; then printf ' with the demo services'; fi), so ClickHouse gets a $ch_mem MiB container (normally at least $CH_MEM_FLOOR_MB)$heap_note. Use this host for an evaluation or a small load only, and add memory before a heavier load."
fi
# On a Linux Docker Engine the next commands run as root too (.env and secrets/ belong to root).
run=
if $give_secrets; then
  run="sudo "
fi
if $demo; then
  printf '\nDemo profile: evaluation only (plaintext gRPC between the demo agents and the server, sample\n'
  printf 'traffic); never put production data or agents on it.\n'
  printf '\nNext steps:\n'
  if $demo_local_images; then
    printf '  1. Until the images are published, build them once in the repository root:\n'
    printf '       ./gradlew :server:jibDockerBuild :sample-apps:demo-app:jibDockerBuild :sample-apps:load:jibDockerBuild\n'
  else
    printf '  1. The images are the published ones pinned by digest in the Compose files; the next step pulls them.\n'
  fi
  printf '  2. %sdocker compose --profile demo up -d\n' "$run"
  printf '  3. Open %s and sign in as %s with the password in secrets/admin_password;\n' "$effective_url" "$admin_user"
  printf '     the UI asks for a new one. XLog points of order-api and payment-api arrive within about a minute.\n'
  printf '  4. Remove the demo and all its data: %sdocker compose --profile demo down -v\n' "$run"
else
  printf '\nKeep a copy of secrets/ (above all secrets/master_key) and tls/ outside this host.\n'
  printf '\nNext steps:\n'
  printf '  1. Check ARGOS_WEB_PUBLIC_URL in .env (now %s): the address of the UI in the browser.\n' "$effective_url"
  if $tls_written; then
    printf '  2. The gRPC certificate tls/server.crt (key tls/server.key, 0600) is self-signed for an evaluation;\n'
    printf '     copy tls/server.crt to every agent host and name it in server.tls.ca-file.\n'
  else
    printf '  2. Place the gRPC certificate and key: tls/server.crt and tls/server.key (docs/install/compose.md).\n'
  fi
  printf '  3. %s./argos-compose.sh up\n' "$run"
  printf '  4. %s./smoke.sh\n' "$run"
  if $unattended; then
    printf '  5. Sign in as %s with the password in secrets/admin_password; the UI asks for a new one.\n' "$admin_user"
  else
    printf '  5. Open %s/setup and enter the setup token (valid 30 minutes; a restart issues a new one\n' "${effective_url%/}"
    printf '     and the previous one is invalid). Read the current token from its file in the argos-data volume:\n'
    printf '       %s./argos-compose.sh compose exec server cat /data/setup-token\n' "$run"
    printf '     or from the last "Setup token" line of the server log (the value after the colon at the end of "message"):\n'
    printf '       %s./argos-compose.sh logs --no-color server | grep "Setup token" | tail -n 1\n' "$run"
  fi
fi

if $tls_written; then
  warn "tls/server.crt is a SELF-SIGNED certificate for an evaluation only (valid 365 days; names: $tls_names).
  Agents trust it only with server.tls.ca-file set to a copy of tls/server.crt. For production use a
  certificate from your organization's CA (docs/install/compose.md section 4) and replace both files."
fi

# ADR-0010 C-197 (2): the session cookies are Secure (16 section 7.1); over plain HTTP a browser
# keeps them for localhost only. A warning, not an error: the exit code stays 0, so that a scripted
# installation that sets ARGOS_SECURITY_COOKIE_SECURE=false or a proxy afterwards still works.
case $effective_url in
  http://*)
    case $(url_host "$effective_url") in
      localhost | 127.0.0.1 | '[::1]') ;;
      *)
        if [ "$(lower "$(env_file_value ARGOS_SECURITY_COOKIE_SECURE)")" = false ]; then
          warn "ARGOS_SECURITY_COOKIE_SECURE=false in .env: signing in over plain HTTP at $effective_url works from other computers, but the session cookies cross the network readable. Use it for a short evaluation on a trusted network only."
        else
          warn "the public URL $effective_url is plain HTTP and its host is not localhost.
  The session cookies of the UI are Secure: over plain HTTP a browser keeps them for localhost only,
  so signing in from another computer at this address fails (the sign-in page then says that the
  browser did not keep the session cookie). Choose one of:
    - localhost: open the UI on this host, or from your workstation through an SSH tunnel
      (ssh -N -L 8080:127.0.0.1:8080 <user>@<this host>), at http://localhost:8080, and set
      ARGOS_WEB_PUBLIC_URL=http://localhost:8080 in .env;
    - HTTPS: put the UI behind the HTTPS reverse proxy (ARGOS_PROXY=true, docs/install/compose.md
      section 8) and set ARGOS_WEB_PUBLIC_URL to its https:// address in .env;
    - evaluation only: ARGOS_SECURITY_COOKIE_SECURE=false in .env sends the session cookies over
      plain HTTP to any host, readable on the network; for a short test on a trusted network only.
  The installation is prepared all the same (exit code 0); edit .env before or after
  ./argos-compose.sh up (run up again after a change)."
        fi
        ;;
    esac
    ;;
esac
