#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: N-16, N-20, SEC-55, SEC-73, SEC-74, SEC-75
#
# Static and `docker compose config` tests of the Compose files (docs/17 section 3; SEC-73, SEC-75,
# SEC-55; ADR-0010 C-113, C-171): image references equal the single sources
# deploy/clickhouse/image.properties and deploy/postgres/image.properties, every other image is
# pinned by digest, the defaults equal the 16 GiB memory plan, the published ports are 8080 and 9100
# (+ 127.0.0.1:8081 with compose.mgmt.yaml), the databases publish nothing and sit on the internal
# network only, .env.example names every variable without a value, and the proxy example passes
# `docker compose config` and `nginx -t`. The demo profile (compose.demo.yaml, WO-41; T-NFR-16d,
# T-SEC-74, T-SEC-75) is rendered with the .env that argos-env-init.sh --demo writes: 127.0.0.1:8080
# and 127.0.0.1:9100 only, demo services on the internal network without ports, hardened, secrets as
# files; and the production files can never turn on demo mode or plaintext gRPC. Requires Docker
# (skipped without it unless REQUIRE_DOCKER=1).
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/compose/test/lib.sh
source "$here/lib.sh"
require_docker

root=$(cd "$DEPLOY_DIR/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# A copy with the layout of deploy/ (compose/, clickhouse/, postgres/), without local .env or secrets.
mkdir -p "$work/clickhouse" "$work/postgres"
cp -R "$COMPOSE_DIR" "$work/compose"
rm -rf "$work/compose/.env" "$work/compose/secrets" "$work/compose/tls" "$work/compose/compose.override.yaml"
cp -R "$DEPLOY_DIR/clickhouse/config.d" "$DEPLOY_DIR/clickhouse/users.d" "$DEPLOY_DIR/clickhouse/image.properties" "$work/clickhouse/"
cp "$DEPLOY_DIR/postgres/roles.sql" "$DEPLOY_DIR/postgres/image.properties" "$work/postgres/"
cd "$work/compose"

image_of() { # image_of <file> <service>: the image line of a service in a Compose file
  awk -v svc="  $2:" '$0 == svc { found = 1; next } found && /^  [a-z]/ { exit } found && /^    image:/ { sub(/^    image: */, ""); print; exit }' "$1"
}

# --- images (SEC-73) -----------------------------------------------------------------------------
check_single_source() {
  local svc=$1 props=$2 want got
  want=$(sed -n 's/^image=//p' "$props")
  got=$(image_of compose.yaml "$svc")
  [[ -n "$want" && "$got" == "$want" ]] || { echo "compose.yaml $svc: '$got', $props: '$want'"; return 1; }
}
expect_exit 0 "SEC-73: the ClickHouse image is the one of deploy/clickhouse/image.properties" \
  check_single_source clickhouse "$DEPLOY_DIR/clickhouse/image.properties"
expect_exit 0 "SEC-73: the PostgreSQL image is the one of deploy/postgres/image.properties" \
  check_single_source postgres "$DEPLOY_DIR/postgres/image.properties"
pinned() {
  local f img
  for f in compose*.yaml; do
    while read -r img; do
      # shellcheck disable=SC2016 # the literal ${ARGOS_*_IMAGE:-...} of the Compose files
      case $img in
        '${ARGOS_SERVER_IMAGE:-'* | '${ARGOS_DEMO_APP_IMAGE:-'* | '${ARGOS_DEMO_LOAD_IMAGE:-'*) ;;
        *@sha256:????????????????????????????????????????????????????????????????) ;;
        *) echo "$f: $img is not tag@sha256"; return 1 ;;
      esac
    done < <(sed -n 's/^ *image: *//p' "$f")
  done
}
expect_exit 0 "SEC-73: every image except the local server and demo images is tag@sha256" pinned
demo_db_single_source() {
  local want got
  want=$(sed -n 's/^image=//p' "$DEPLOY_DIR/postgres/image.properties")
  got=$(image_of compose.demo.yaml demo-db)
  [[ -n "$want" && "$got" == "$want" ]] || { echo "compose.demo.yaml demo-db: '$got', deploy/postgres/image.properties: '$want'"; return 1; }
}
expect_exit 0 "T-NFR-16d SEC-73: demo-db runs the PostgreSQL image of deploy/postgres/image.properties" demo_db_single_source
demo_image_defaults() {
  local version
  version=$(sed -n 's/^version=//p' "$root/gradle.properties")
  [[ "$(image_of compose.demo.yaml sample-app-a)" == "\${ARGOS_DEMO_APP_IMAGE:-argos-demo-app:$version}" ]] || { echo "sample-app-a: $(image_of compose.demo.yaml sample-app-a)"; return 1; }
  [[ "$(image_of compose.demo.yaml sample-app-b)" == "\${ARGOS_DEMO_APP_IMAGE:-argos-demo-app:$version}" ]] || { echo "sample-app-b: $(image_of compose.demo.yaml sample-app-b)"; return 1; }
  [[ "$(image_of compose.demo.yaml load-generator)" == "\${ARGOS_DEMO_LOAD_IMAGE:-argos-demo-load:$version}" ]] || { echo "load-generator: $(image_of compose.demo.yaml load-generator)"; return 1; }
}
expect_exit 0 "T-NFR-16d: the demo images are \${ARGOS_DEMO_APP_IMAGE} and \${ARGOS_DEMO_LOAD_IMAGE} with the local builds argos-demo-app:<version> and argos-demo-load:<version> as defaults" demo_image_defaults
server_default() {
  local version want got
  version=$(sed -n 's/^version=//p' "$root/gradle.properties")
  want="\${ARGOS_SERVER_IMAGE:-argos-server:$version}"
  got=$(image_of compose.yaml server)
  [[ "$got" == "$want" ]] || { echo "compose.yaml server: '$got', expected '$want'"; return 1; }
}
expect_exit 0 "ADR-0010 C-171: the server image is \${ARGOS_SERVER_IMAGE} with the local argos-server:<version> as default" server_default

# --- defaults = memory plan of a 16 GiB host (env-init --ram-mb 16384) ------------------------------
defaults_match() {
  local line var value default
  while IFS= read -r line; do
    case $line in '#'* | '') continue ;; esac
    var=${line%%=*}
    value=${line#*=}
    value=${value#\"}
    value=${value%\"}
    default=$(grep -ho "\${$var:-[^}]*}" compose.yaml | head -n 1 | sed "s/^\${$var:-//; s/}\$//")
    [[ "$default" == "$value" ]] || { echo "$var: compose.yaml default '$default', plan '$value'"; return 1; }
  done <"$here/expected/ram-16384.env"
}
expect_exit 0 "compose.yaml defaults equal the memory plan of a 16 GiB host (expected/ram-16384.env)" defaults_match

# --- docker compose config (T-SEC-75, SEC-55) --------------------------------------------------------
config() { env -u ARGOS_MGMT_EXPOSE docker compose "$@" config 2>&1; }
section() { # section <config yaml> <service>
  awk -v svc="  $2:" '$0 == svc { found = 1; print; next } found && /^  [a-z]/ { exit } found { print }' <<<"$1"
}
published() { # published <service section>: host_ip:published->target per port
  awk '/^    ports:/ { p = 1; next } p && /^    [a-z]/ { p = 0 } p && /host_ip:/ { ip = $2 } p && /target:/ { t = $2 }
       p && /published:/ { gsub(/"/, "", $2); print ip ":" $2 "->" t }' <<<"$1" | sort | tr '\n' ' '
}

base_config() {
  local out s
  out=$(config -f compose.yaml) || { echo "$out"; return 1; }
  s=$(section "$out" server)
  [[ "$(published "$s")" == "0.0.0.0:8080->8080 0.0.0.0:9100->9100 " ]] || { echo "server ports: $(published "$s")"; return 1; }
  for db in clickhouse postgres; do
    s=$(section "$out" "$db")
    if grep -q '^    ports:' <<<"$s"; then echo "$db publishes ports"; return 1; fi
    [[ "$(awk '/^    networks:/ { n = 1; next } n && /^    [a-z]/ { n = 0 } n && /^      [a-z]/ { print $1 }' <<<"$s" | tr -d ':' | tr '\n' ' ')" == "back " ]] \
      || { echo "$db networks"; return 1; }
  done
  awk '/^networks:/ { n = 1 } n && /^  back:/ { b = 1 } b && /internal: true/ { ok = 1 } END { exit !ok }' <<<"$out" \
    || { echo "network back is not internal"; return 1; }
  s=$(section "$out" server)
  if grep -qE 'ARGOS_(HTTP|GRPC)_BIND' <<<"$s"; then echo "the server receives ARGOS_HTTP_BIND/ARGOS_GRPC_BIND (C-113)"; return 1; fi
  grep -q 'MANAGEMENT_SERVER_ADDRESS: 0.0.0.0' <<<"$s" || { echo "MANAGEMENT_SERVER_ADDRESS (C-114)"; return 1; }
  if grep -qE 'PASSWORD:|_TOKEN:|MASTER_KEY:' <<<"$out"; then echo "a secret value in the environment"; return 1; fi
}
expect_exit 0 "T-SEC-75: compose.yaml publishes 8080 and 9100 only; databases on the internal back network without ports; no bind variable or secret value reaches the server" base_config

mgmt_config() {
  local out
  out=$(config -f compose.yaml -f compose.mgmt.yaml) || { echo "$out"; return 1; }
  [[ "$(published "$(section "$out" server)")" == "0.0.0.0:8080->8080 0.0.0.0:9100->9100 127.0.0.1:8081->8081 " ]] \
    || { echo "server ports: $(published "$(section "$out" server)")"; return 1; }
}
expect_exit 0 "SEC-55: compose.mgmt.yaml adds 127.0.0.1:8081 only" mgmt_config

audit_account() {
  local out s
  out=$(config -f compose.yaml) || { echo "$out"; return 1; }
  s=$(section "$out" server)
  grep -q 'source: pg_audit_password' <<<"$s" || { echo "the server does not mount pg_audit_password"; return 1; }
  grep -q 'password-file: /run/secrets/pg_audit_password' config/server/application-compose.yaml \
    || { echo "application-compose.yaml does not name the audit password file"; return 1; }
  grep -q 'user: argos_audit' config/server/application-compose.yaml \
    || { echo "application-compose.yaml does not name argos_audit"; return 1; }
}
expect_exit 0 "SEC-82, ADR-0010 C-116: the server mounts pg_audit_password and uses it as argos.postgres.audit (audit partition job)" audit_account

bind_config() {
  local out
  out=$(ARGOS_HTTP_BIND=127.0.0.1 ARGOS_GRPC_BIND=10.0.0.5 config -f compose.yaml) || { echo "$out"; return 1; }
  [[ "$(published "$(section "$out" server)")" == "10.0.0.5:9100->9100 127.0.0.1:8080->8080 " ]] \
    || { echo "server ports: $(published "$(section "$out" server)")"; return 1; }
}
expect_exit 0 "ADR-0010 C-113: ARGOS_HTTP_BIND and ARGOS_GRPC_BIND are the host publish addresses" bind_config

unattended_config() {
  local out s
  out=$(ARGOS_IP_MODE=masked config -f compose.yaml -f compose.unattended.yaml) || { echo "$out"; return 1; }
  s=$(section "$out" server)
  if ! { grep -q 'ARGOS_ADMIN_PASSWORD_FILE: /run/secrets/admin_password' <<<"$s" && grep -q 'ARGOS_IP_MODE: masked' <<<"$s" \
    && grep -q 'source: admin_password' <<<"$s"; }; then
    echo "$s"
    return 1
  fi
}
expect_exit 0 "compose.unattended.yaml passes the admin password as a file and the IP mode" unattended_config
unattended_needs_mode() { ! config -f compose.yaml -f compose.unattended.yaml >/dev/null; }
expect_exit 0 "compose.unattended.yaml refuses to render without ARGOS_IP_MODE (SEC-31)" unattended_needs_mode

proxy_config() {
  local out s
  out=$(config -f compose.yaml -f compose.proxy.yaml) || { echo "$out"; return 1; }
  s=$(section "$out" proxy)
  [[ "$(published "$s")" == "0.0.0.0:443->443 " ]] || { echo "proxy ports: $(published "$s")"; return 1; }
  grep -q 'SERVER_FORWARD_HEADERS_STRATEGY: native' <<<"$(section "$out" server)" || { echo "forward headers"; return 1; }
}
expect_exit 0 "17 section 3.10: compose.yaml + compose.proxy.yaml passes docker compose config; the proxy publishes 443 only" proxy_config

# X-Forwarded-For (client address of rate limits and the audit log): the proxy overwrites it and the
# server trusts it from the proxy's fixed address only, never from the gateway of front or a range.
nginx_overwrites_xff() {
  local conf=config/proxy/nginx.conf code overwrite pass
  code=$(sed 's/#.*//' "$conf")
  if grep -n 'proxy_add_x_forwarded_for' <<<"$code"; then echo "nginx.conf appends to X-Forwarded-For"; return 1; fi
  # shellcheck disable=SC2016 # a regular expression: \$ is the literal $ of the nginx variable
  overwrite=$(grep -c 'proxy_set_header X-Forwarded-For \$remote_addr;' <<<"$code" || true)
  pass=$(grep -c 'proxy_pass ' <<<"$code" || true)
  [[ "$pass" -gt 0 && "$overwrite" == "$pass" ]] \
    || { echo "$overwrite X-Forwarded-For \$remote_addr lines for $pass proxy_pass locations"; return 1; }
}
expect_exit 0 "17 section 3.10: nginx.conf overwrites X-Forwarded-For with \$remote_addr in every location (no append)" nginx_overwrites_xff

# trusted_proxy_matches <config yaml> <expected address>: the proxy has that fixed address inside the
# front subnet, and the server's internal-proxies expression is exactly that address, escaped.
trusted_proxy_matches() {
  local out=$1 want=$2 ip regex subnet
  ip=$(section "$out" proxy | sed -n 's/^ *ipv4_address: *//p' | tr -d "\"'")
  regex=$(section "$out" server | sed -n 's/^ *SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES: *//p' | tr -d "\"'")
  subnet=$(awk '/^networks:/ { n = 1 } n && /^  front:/ { f = 1; next } f && /^  [a-z]/ { f = 0 } f && /subnet:/ { print $NF }' <<<"$out" | tr -d "\"'")
  [[ "$ip" == "$want" ]] || { echo "proxy ipv4_address '$ip', expected '$want'"; return 1; }
  [[ -n "$subnet" ]] || { echo "front has no fixed subnet"; return 1; }
  [[ "$regex" == "${ip//./\\.}" ]] || { echo "INTERNALPROXIES '$regex' is not the escaped proxy address '$ip'"; return 1; }
  grep -qxE "$regex" <<<"$ip" || { echo "'$regex' does not match $ip"; return 1; }
  local other
  for other in "${ip%.*}.1" "${ip//./x}" "1${ip}" "${ip}0"; do
    if grep -qxE "$regex" <<<"$other"; then echo "'$regex' also matches $other"; return 1; fi
  done
  grep -q 'SERVER_FORWARD_HEADERS_STRATEGY: native' <<<"$(section "$out" server)" || { echo "forward headers"; return 1; }
}
proxy_trust_default() {
  local out want
  out=$(config -f compose.yaml -f compose.proxy.yaml) || { echo "$out"; return 1; }
  want=$(sed -n 's/^DEFAULT_PROXY_IP=//p' argos-compose.sh)
  [[ -n "$want" ]] || { echo "DEFAULT_PROXY_IP is missing in argos-compose.sh"; return 1; }
  trusted_proxy_matches "$out" "$want"
}
expect_exit 0 "17 section 3.10: compose.proxy.yaml trusts X-Forwarded-* from the proxy's fixed default address only (INTERNALPROXIES = escaped ipv4_address = DEFAULT_PROXY_IP of argos-compose.sh)" proxy_trust_default
proxy_trust_follows_ip() {
  local out
  # A stray expression in the environment is replaced by the one derived from ARGOS_PROXY_IP.
  out=$(env -u ARGOS_MGMT_EXPOSE ARGOS_PROXY=true ARGOS_PROXY_SUBNET=10.123.45.0/24 ARGOS_PROXY_IP=10.123.45.67 \
    ARGOS_PROXY_IP_REGEX='.*' sh ./argos-compose.sh compose config 2>&1) || { echo "$out"; return 1; }
  trusted_proxy_matches "$out" 10.123.45.67
}
expect_exit 0 "17 section 3.10: argos-compose.sh derives INTERNALPROXIES from ARGOS_PROXY_IP (overrides a stray ARGOS_PROXY_IP_REGEX)" proxy_trust_follows_ip
proxy_ip_invalid() { env -u ARGOS_MGMT_EXPOSE ARGOS_PROXY=true ARGOS_PROXY_IP='10.0.0.*' sh ./argos-compose.sh compose config; }
expect_exit 2 "17 section 3.10: argos-compose.sh refuses an ARGOS_PROXY_IP that is not an IPv4 address (exit 2)" proxy_ip_invalid

nginx_test() {
  local tls
  tls=$(mktemp -d)
  docker run --rm --network none --label io.argos.wo40=1 --entrypoint sh -v "$(host_path "$tls"):/out" "$POSTGRES_IMAGE" -c \
    'openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=argos.test -keyout /out/proxy.key -out /out/proxy.crt 2>/dev/null && chmod 0644 /out/proxy.key' \
    || { rm -rf "$tls"; return 1; }
  local nginx_image rc=0
  nginx_image=$(image_of compose.proxy.yaml proxy)
  docker run --rm --network none --label io.argos.wo40=1 --add-host server:127.0.0.1 \
    -v "$(host_path "$work/compose/config/proxy/nginx.conf"):/etc/nginx/nginx.conf:ro" \
    -v "$(host_path "$tls"):/etc/nginx/tls:ro" "$nginx_image" nginx -t || rc=$?
  rm -rf "$tls"
  return "$rc"
}
expect_exit 0 "17 section 3.10: nginx -t accepts config/proxy/nginx.conf" nginx_test

# --- ClickHouse backup target (compose.chbackup.yaml, WO-43; docs/17 section 10) ---------------------
# mount_of <service section> <target>: "type|source|ro or rw" of the volume entry with that target.
mount_of() {
  awk -v want="$2" '
    function flush() { if (g == want) { print ty "|" src "|" (ro ? "ro" : "rw"); found = 1 } }
    /^      - type:/ { flush(); ty = $3; src = ""; g = ""; ro = 0; next }
    /^        source:/ { src = $2 } /^        target:/ { g = $2 } /^        read_only: true/ { ro = 1 }
    /^    [a-z]/ { flush(); ty = ""; g = "" }
    END { if (!found) flush() }' <<<"$1" | head -n 1
}
chbackup_config() {
  local out s base_s target
  target=$work/ch-backups
  mkdir -p "$target"
  out=$(ARGOS_CH_BACKUP_DIR="$target" config -f compose.yaml -f compose.chbackup.yaml) || { echo "$out"; return 1; }
  base_s=$(config -f compose.yaml) || { echo "$base_s"; return 1; }
  [[ "$(published "$(section "$out" server)")" == "$(published "$(section "$base_s" server)")" ]] \
    || { echo "server ports: $(published "$(section "$out" server)")"; return 1; }
  s=$(section "$out" clickhouse)
  if grep -q '^    ports:' <<<"$s"; then echo "clickhouse publishes ports"; return 1; fi
  if grep -q '^    ports:' <<<"$(section "$out" postgres)"; then echo "postgres publishes ports"; return 1; fi
  [[ "$(awk '/^    networks:/ { n = 1; next } n && /^    [a-z]/ { n = 0 } n && /^      [a-z]/ { print $1 }' <<<"$s" | tr -d ':' | tr '\n' ' ')" == "back " ]] \
    || { echo "clickhouse networks"; return 1; }
  # The mount of the operator's directory at /backups (writable: ClickHouse writes the backups) and
  # backups.xml as a single read-only file next to the image's own config.d files.
  local m
  m=$(mount_of "$s" /backups)
  [[ "$m" == "bind|$target|rw" || ( "$m" == bind\|*ch-backups\|rw ) ]] || { echo "/backups: '$m'"; echo "$s"; return 1; }
  m=$(mount_of "$s" /etc/clickhouse-server/config.d/backups.xml)
  [[ "$m" == bind\|*backups.xml\|ro ]] || { echo "backups.xml: '$m'"; echo "$s"; return 1; }
  for t in /var/lib/clickhouse /etc/clickhouse-server/config.d/argos.xml /etc/clickhouse-server/users.d/argos.xml; do
    grep -q "target: $t\$" <<<"$s" || { echo "the mount $t of compose.yaml is missing"; return 1; }
  done
}
expect_exit 0 "T-NFR-20c T-SEC-75: compose.chbackup.yaml binds ARGOS_CH_BACKUP_DIR to /backups and mounts backups.xml read-only; published ports and networks stay as in compose.yaml" chbackup_config
chbackup_needs_dir() { ! env -u ARGOS_CH_BACKUP_DIR docker compose -f compose.yaml -f compose.chbackup.yaml config >/dev/null 2>&1; }
expect_exit 0 "compose.chbackup.yaml refuses to render without ARGOS_CH_BACKUP_DIR" chbackup_needs_dir
chbackup_disk() {
  local f=config/clickhouse/backups.xml
  if [[ "$(grep -c '<allowed_disk>' "$f")" != 1 ]] || ! grep -q '<allowed_disk>backups</allowed_disk>' "$f"; then
    echo "backups.allowed_disk must be the disk backups only"
    return 1
  fi
  if ! grep -q '<path>/backups/</path>' "$f" || ! grep -q '<type>local</type>' "$f"; then
    echo "the disk backups is not local /backups/"
    return 1
  fi
  if sed '/<!--/,/-->/d' "$f" | grep -niE 's3|endpoint|access_key|secret|url|remote'; then
    echo "a remote target or a credential in $f"; return 1
  fi
}
expect_exit 0 "docs/17 section 10: config/clickhouse/backups.xml declares the local disk backups (/backups/) as the only backup disk, without a remote target or credential" chbackup_disk
backup_env_example() {
  local var missing=()
  while read -r var; do
    grep -q "^$var=$" .env.example || missing+=("$var")
  done < <(grep -ho 'env_value [A-Z_][A-Z0-9_]*' backup.sh restore.sh | sed 's/^env_value //' | sort -u)
  [[ ${#missing[@]} -eq 0 ]] || { echo "missing in .env.example: ${missing[*]}"; return 1; }
}
expect_exit 0 "T-SEC-74: .env.example names every variable of backup.sh and restore.sh, all values empty" backup_env_example

# --- .env.example -----------------------------------------------------------------------------------
env_example() {
  local var missing=()
  if grep -nE '^[A-Za-z_][A-Za-z0-9_]*=.+' .env.example; then echo "values in .env.example"; return 1; fi
  # shellcheck disable=SC2016 # a regular expression for the literal ${VAR of the Compose files
  for var in $( (grep -ho '\${[A-Z_][A-Z0-9_]*' compose*.yaml | sed 's/^\${//'
    grep -ho 'env_value [A-Z_][A-Z0-9_]*' argos-compose.sh smoke.sh | sed 's/^env_value //') | sort -u); do
    grep -q "^$var=$" .env.example || missing+=("$var")
  done
  [[ ${#missing[@]} -eq 0 ]] || { echo "missing in .env.example: ${missing[*]}"; return 1; }
}
expect_exit 0 "T-SEC-74: .env.example names every variable of the Compose files and scripts, all values empty" env_example

secret_names() {
  local compose_set init_set
  compose_set=$(sed -n 's|^ *file: \./secrets/||p' compose.yaml compose.unattended.yaml compose.demo.yaml | sort | tr '\n' ' ')
  init_set=$( (sed -n -e 's/^DB_SECRETS="\(.*\)"$/\1/p' -e 's/^DEMO_SECRETS="\(.*\)"$/\1/p' argos-env-init.sh | tr ' ' '\n'
    echo master_key; echo metrics_token; echo admin_password) | sort | tr '\n' ' ')
  [[ "$compose_set" == "$init_set" ]] || { echo "compose: $compose_set"; echo "env-init: $init_set"; return 1; }
}
expect_exit 0 "the secrets of the Compose files are exactly the files argos-env-init.sh generates" secret_names
demo_secret_lists() {
  local init compose
  init=$(sed -n 's/^DEMO_SECRETS="\(.*\)"$/\1/p' argos-env-init.sh)
  compose=$(sed -n 's/^DEMO_SECRETS="\(.*\)"$/\1/p' argos-compose.sh)
  [[ -n "$init" && "$init" == "$compose" ]] || { echo "argos-env-init.sh: '$init', argos-compose.sh: '$compose'"; return 1; }
}
expect_exit 0 "T-NFR-16d: argos-env-init.sh and argos-compose.sh name the same demo secrets" demo_secret_lists

# --- demo profile (docs/17 section 3.9, WO-41) -------------------------------------------------------
# A second copy with the layout of deploy/, prepared by argos-env-init.sh --demo itself (in the
# Ubuntu container with the docker test double: no root and no chown needed on the host), so that
# the target commands `docker compose --profile demo up -d` and `down -v` are checked with the .env
# that --demo writes (COMPOSE_FILE, COMPOSE_PROFILES), not with -f options of the test.
demo_root=$work/demo-root
mkdir -p "$demo_root/clickhouse" "$demo_root/postgres" "$demo_root/compose"
cp -R "$DEPLOY_DIR/clickhouse/config.d" "$DEPLOY_DIR/clickhouse/users.d" "$DEPLOY_DIR/clickhouse/image.properties" "$demo_root/clickhouse/"
cp "$DEPLOY_DIR/postgres/roles.sql" "$DEPLOY_DIR/postgres/image.properties" "$demo_root/postgres/"
demo_init() {
  # shellcheck disable=SC2016 # the script runs in the container's shell, which expands it
  in_container "$UBUNTU_IMAGE" -v "$(host_path "$demo_root/compose"):/w" -- sh -c '
    mkdir -p /tmp/fake-bin && cp /t/container/fake-bin/docker /tmp/fake-bin/ && chmod 0755 /tmp/fake-bin/docker
    cp -R /src/. /w/ && rm -rf /w/.env /w/secrets /w/tls /w/test /w/compose.override.yaml && cd /w
    PATH=/tmp/fake-bin:$PATH sh ./argos-env-init.sh --demo --ram-mb 16384 >/dev/null
    rc=$?
    chmod -R a+rwX /w
    exit $rc'
}
expect_exit 0 "T-NFR-16d: argos-env-init.sh --demo prepares a copy of deploy/compose" demo_init
demo_dir=$demo_root/compose
# demo_config [options]: plain docker compose in the prepared copy (only its .env selects the files).
demo_config() { (cd "$demo_dir" && env -u ARGOS_MGMT_EXPOSE -u COMPOSE_FILE -u COMPOSE_PROFILES docker compose "$@" config 2>&1); }
services_of() { awk '/^services:/ { s = 1; next } s && /^[a-z]/ { exit } s && /^  [a-z][a-z0-9_-]*:/ { sub(/:.*/, ""); sub(/^  /, ""); print }' <<<"$1" | sort | tr '\n' ' '; }
networks_of() { awk '/^    networks:/ { n = 1; next } n && /^    [a-z]/ { n = 0 } n && /^      [a-z]/ { print $1 }' <<<"$1" | tr -d ':' | tr '\n' ' '; }
DEMO_SERVICES="demo-db load-generator sample-app-a sample-app-b"

demo_profile_config() {
  local out s svc
  out=$(demo_config --profile demo) || { echo "$out"; return 1; }
  [[ "$(services_of "$out")" == "clickhouse demo-db load-generator postgres sample-app-a sample-app-b server " ]] \
    || { echo "services: $(services_of "$out")"; return 1; }
  s=$(section "$out" server)
  [[ "$(published "$s")" == "127.0.0.1:8080->8080 127.0.0.1:9100->9100 " ]] || { echo "server ports: $(published "$s")"; return 1; }
  for svc in $DEMO_SERVICES clickhouse postgres; do
    s=$(section "$out" "$svc")
    if grep -q '^    ports:' <<<"$s"; then echo "$svc publishes ports"; return 1; fi
    if grep -q '^    expose:' <<<"$s"; then echo "$svc exposes ports"; return 1; fi
    [[ "$(networks_of "$s")" == "back " ]] || { echo "$svc networks: $(networks_of "$s")"; return 1; }
  done
  awk '/^networks:/ { n = 1 } n && /^  back:/ { b = 1 } b && /internal: true/ { ok = 1 } END { exit !ok }' <<<"$out" \
    || { echo "network back is not internal"; return 1; }
}
expect_exit 0 "T-SEC-75 T-NFR-16d: with the .env of --demo, docker compose --profile demo publishes only 127.0.0.1:8080 and 127.0.0.1:9100; the four demo services publish and expose nothing and sit on the internal back network only" demo_profile_config

# shellcheck disable=SC2015 # 'A && B || fail' is intended: fail when any grep does not match
demo_server_config() {
  local out s
  out=$(demo_config --profile demo) || { echo "$out"; return 1; }
  s=$(section "$out" server)
  grep -q 'ARGOS_DEMO_MODE: "true"' <<<"$s" || { echo "ARGOS_DEMO_MODE"; return 1; }
  grep -q 'ARGOS_BOOTSTRAP_APPS_FILE: /conf/demo/bootstrap-apps.yaml' <<<"$s" || { echo "ARGOS_BOOTSTRAP_APPS_FILE"; return 1; }
  grep -q 'ARGOS_SECURITY_MASTERKEY_PERMISSIVE: "true"' <<<"$s" || { echo "ARGOS_SECURITY_MASTERKEY_PERMISSIVE"; return 1; }
  grep -q 'target: /conf/demo$' <<<"$s" || { echo "config/demo is not mounted at /conf/demo"; return 1; }
  if grep -q 'target: /conf/tls' <<<"$s"; then echo "the TLS files are still mounted"; return 1; fi
  grep -q 'target: /run/secrets/demo_order_api_token' <<<"$s" && grep -q 'target: /run/secrets/demo_payment_api_token' <<<"$s" \
    || { echo "the server does not mount both demo tokens"; return 1; }
  grep -q 'ARGOS_ADMIN_PASSWORD_FILE: /run/secrets/admin_password' <<<"$s" && grep -q 'ARGOS_IP_MODE: masked' <<<"$s" \
    || { echo "unattended setup with masked IP addresses"; return 1; }
  grep -q 'target: /data$' <<<"$s" && grep -q 'target: /logs$' <<<"$s" && grep -q 'target: /conf/server$' <<<"$s" \
    || { echo "the server volumes of compose.yaml are missing"; return 1; }
}
expect_exit 0 "T-NFR-16d: the demo server runs in demo mode with the bootstrap file, both demo tokens, the unattended administrator, its data volumes and no TLS mount" demo_server_config

# shellcheck disable=SC2015 # 'A && B || fail' is intended: fail when any grep does not match
demo_hardening() {
  local out s svc
  out=$(demo_config --profile demo) || { echo "$out"; return 1; }
  for svc in sample-app-a sample-app-b load-generator; do
    s=$(section "$out" "$svc")
    grep -q '^    read_only: true' <<<"$s" || { echo "$svc: not read_only"; return 1; }
    grep -qx '      - ALL' <<<"$(awk '/^    cap_drop:/ { c = 1; next } c && /^    [a-z]/ { c = 0 } c' <<<"$s")" || { echo "$svc: cap_drop ALL"; return 1; }
    grep -q 'no-new-privileges:true' <<<"$s" || { echo "$svc: no-new-privileges"; return 1; }
    grep -q '/tmp:size=.*uid=10001' <<<"$s" || { echo "$svc: private /tmp tmpfs for uid 10001"; return 1; }
  done
  grep -q 'no-new-privileges:true' <<<"$(section "$out" demo-db)" || { echo "demo-db: no-new-privileges"; return 1; }
  for svc in sample-app-a sample-app-b; do
    s=$(section "$out" "$svc")
    grep -q 'ARGOS_SERVER_ADDRESS: server:9100' <<<"$s" && grep -q 'ARGOS_SERVER_TLS: "false"' <<<"$s" \
      && grep -q 'ARGOS_APP_ENV: demo' <<<"$s" && grep -q 'ARGOS_CONFIG: /conf/demo/argos-agent.yml' <<<"$s" \
      && grep -q 'DEMO_DB_PASSWORD_FILE: /run/secrets/demo_db_password' <<<"$s" || { echo "$svc environment"; return 1; }
  done
  grep -q 'ARGOS_SERVER_TOKEN_FILE: /run/secrets/demo_order_api_token' <<<"$(section "$out" sample-app-a)" || { echo "order-api token file"; return 1; }
  grep -q 'ARGOS_SERVER_TOKEN_FILE: /run/secrets/demo_payment_api_token' <<<"$(section "$out" sample-app-b)" || { echo "payment-api token file"; return 1; }
  if grep -q 'demo_payment_api_token' <<<"$(section "$out" sample-app-a)"; then echo "order-api gets the payment-api token"; return 1; fi
  if grep -q 'demo_order_api_token' <<<"$(section "$out" sample-app-b)"; then echo "payment-api gets the order-api token"; return 1; fi
  grep -q 'PEER_URL: http://sample-app-b:8080' <<<"$(section "$out" sample-app-a)" || { echo "order-api PEER_URL"; return 1; }
  s=$(section "$out" load-generator)
  grep -q 'TARGET_URL: http://sample-app-a:8080' <<<"$s" && grep -q 'RATE: "50"' <<<"$s" && grep -q 'MIX: demo' <<<"$s" \
    || { echo "load-generator environment"; return 1; }
}
expect_exit 0 "T-NFR-16d: the demo apps and the load generator run read-only, without capabilities or new privileges; each app gets only its own token file, plaintext only to server:9100, the load generator 50/s with MIX=demo" demo_hardening

demo_no_secret_value() {
  local out f v
  out=$(demo_config --profile demo) || { echo "$out"; return 1; }
  if grep -qE '(PASSWORD|TOKEN|MASTER_KEY|SECRET)[A-Z_]*: ' <<<"$(grep -vE '_FILE: ' <<<"$out")"; then
    grep -E '(PASSWORD|TOKEN|MASTER_KEY|SECRET)[A-Z_]*: ' <<<"$(grep -vE '_FILE: ' <<<"$out")" | sed 's/:.*//'
    echo "a secret variable with a value"
    return 1
  fi
  for f in "$demo_dir"/secrets/*; do
    [[ "$(basename "$f")" == clickhouse.env ]] && continue
    v=$(head -n 1 "$f")
    if grep -qF -- "$v" <<<"$out" || grep -qF -- "$v" "$demo_dir/.env"; then echo "the value of $(basename "$f") is in the configuration or .env"; return 1; fi
  done
  v=
}
expect_exit 0 "T-SEC-74 T-NFR-16d: the rendered demo configuration and .env hold no secret value (tokens and passwords only as files)" demo_no_secret_value

demo_memory_reserve() {
  local out svc sum=0 m reserve
  out=$(demo_config --profile demo) || { echo "$out"; return 1; }
  for svc in $DEMO_SERVICES; do
    m=$(section "$out" "$svc" | sed -n 's/^    mem_limit: *"\{0,1\}\([0-9]*\)"\{0,1\}$/\1/p')
    [[ -n "$m" ]] || { echo "$svc has no mem_limit"; return 1; }
    sum=$((sum + m))
  done
  reserve=$(sed -n 's/^DEMO_RESERVE_MB=\([0-9]*\).*$/\1/p' argos-env-init.sh)
  [[ "$sum" -eq $((reserve * 1048576)) ]] || { echo "mem_limit sum $((sum / 1048576)) MiB, DEMO_RESERVE_MB $reserve"; return 1; }
}
expect_exit 0 "T-NFR-16d 17 section 14: the mem_limit of the demo services adds up to DEMO_RESERVE_MB of argos-env-init.sh" demo_memory_reserve

demo_down_covers_demo() {
  local out
  # Without --profile: COMPOSE_PROFILES=demo of .env, so `down -v` stops the demo services and
  # removes their volume too.
  out=$(demo_config) || { echo "$out"; return 1; }
  [[ "$(services_of "$out")" == "clickhouse demo-db load-generator postgres sample-app-a sample-app-b server " ]] \
    || { echo "services without --profile: $(services_of "$out")"; return 1; }
  awk '/^volumes:/ { v = 1 } v && /^  demo-db-data:/ { ok = 1 } END { exit !ok }' <<<"$out" || { echo "volume demo-db-data"; return 1; }
  out=$(cd "$demo_dir" && env -u ARGOS_MGMT_EXPOSE sh ./argos-compose.sh compose config --services 2>&1) || { echo "$out"; return 1; }
  [[ "$(sort <<<"$out" | tr '\n' ' ')" == "clickhouse demo-db load-generator postgres sample-app-a sample-app-b server " ]] \
    || { echo "argos-compose.sh compose: $out"; return 1; }
}
expect_exit 0 "T-NFR-16d: the .env of --demo selects the demo profile for plain docker compose (down -v included) and for argos-compose.sh" demo_down_covers_demo

# The production installation can never turn on demo mode or plaintext gRPC (docs/17 section 3.2).
production_no_demo() {
  local files out f
  for files in "-f compose.yaml" "-f compose.yaml -f compose.unattended.yaml" "-f compose.yaml -f compose.mgmt.yaml" \
    "-f compose.yaml -f compose.proxy.yaml" "-f compose.yaml -f compose.unattended.yaml -f compose.mgmt.yaml -f compose.proxy.yaml"; do
    # shellcheck disable=SC2086 # the file options are split on purpose
    out=$(ARGOS_IP_MODE=masked COMPOSE_PROFILES=demo config $files) || { echo "$files: $out"; return 1; }
    if grep -niE 'demo|plaintext|TLS_ENABLED|tls\.enabled' <<<"$out"; then echo "$files renders a demo or plaintext setting"; return 1; fi
    [[ "$(services_of "$out")" != *sample-app* && "$(services_of "$out")" != *demo* && "$(services_of "$out")" != *load-generator* ]] \
      || { echo "$files: services $(services_of "$out")"; return 1; }
    grep -q 'target: /conf/tls/server.crt' <<<"$(section "$out" server)" || { echo "$files: the TLS certificate is not mounted"; return 1; }
  done
  for f in compose.yaml compose.unattended.yaml compose.mgmt.yaml compose.proxy.yaml config/server/application-compose.yaml; do
    if grep -niE 'demo|plaintext|TLS_ENABLED|tls\.enabled' "$f"; then echo "$f mentions demo mode or plaintext"; return 1; fi
  done
}
expect_exit 0 "T-NFR-16d T-SEC-75: compose.yaml (+ unattended, mgmt, proxy) has no ARGOS_DEMO_MODE, no plaintext collector setting and no demo service, even with COMPOSE_PROFILES=demo, and always mounts the TLS files" production_no_demo

# The master key permission check is skipped only by the demo overlay (ADR-0010 C-243 (1)): Docker
# Desktop shows the bind-mounted secrets/ as 0777. No production file, rendered setup or .env
# template may skip it.
permissive_demo_only() {
  local files out f
  grep -q '^      ARGOS_SECURITY_MASTERKEY_PERMISSIVE: "true"$' compose.demo.yaml \
    || { echo "compose.demo.yaml does not set ARGOS_SECURITY_MASTERKEY_PERMISSIVE for the server"; return 1; }
  for files in "-f compose.yaml" "-f compose.yaml -f compose.unattended.yaml" "-f compose.yaml -f compose.mgmt.yaml" \
    "-f compose.yaml -f compose.proxy.yaml" "-f compose.yaml -f compose.unattended.yaml -f compose.mgmt.yaml -f compose.proxy.yaml"; do
    # shellcheck disable=SC2086 # the file options are split on purpose
    out=$(ARGOS_IP_MODE=masked COMPOSE_PROFILES=demo config $files) || { echo "$files: $out"; return 1; }
    if grep -niE 'permissive|MASTER_?KEY_PERMISSIVE' <<<"$out"; then echo "$files renders a permissive master key"; return 1; fi
  done
  for f in compose*.yaml .env.example argos-env-init.sh argos-compose.sh config/server/*.yaml; do
    [[ "$f" == compose.demo.yaml ]] && continue
    if grep -niE 'permissive|MASTER_?KEY_PERMISSIVE' "$f"; then echo "$f mentions the permissive master key"; return 1; fi
  done
}
expect_exit 0 "C-243 (1) T-SEC-75: only compose.demo.yaml skips the master key permission check (ARGOS_SECURITY_MASTERKEY_PERMISSIVE); compose.yaml, the other overlays, .env.example, the scripts and the server configuration never do" permissive_demo_only

finish
