#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Static and `docker compose config` tests of the Compose files (docs/17 section 3; SEC-73, SEC-75,
# SEC-55; ADR-0010 C-113, C-171): image references equal the single sources
# deploy/clickhouse/image.properties and deploy/postgres/image.properties, every other image is
# pinned by digest, the defaults equal the 16 GiB memory plan, the published ports are 8080 and 9100
# (+ 127.0.0.1:8081 with compose.mgmt.yaml), the databases publish nothing and sit on the internal
# network only, .env.example names every variable without a value, and the proxy example passes
# `docker compose config` and `nginx -t`. Requires Docker (skipped without it unless
# REQUIRE_DOCKER=1).
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
      # shellcheck disable=SC2016 # the literal ${ARGOS_SERVER_IMAGE:-...} of the Compose files
      case $img in
        '${ARGOS_SERVER_IMAGE:-'*) ;;
        *@sha256:????????????????????????????????????????????????????????????????) ;;
        *) echo "$f: $img is not tag@sha256"; return 1 ;;
      esac
    done < <(sed -n 's/^ *image: *//p' "$f")
  done
}
expect_exit 0 "SEC-73: every image except the local server image is tag@sha256" pinned
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
  compose_set=$(sed -n 's|^ *file: \./secrets/||p' compose.yaml compose.unattended.yaml | sort | tr '\n' ' ')
  init_set=$( (sed -n 's/^DB_SECRETS="\(.*\)"$/\1/p' argos-env-init.sh | tr ' ' '\n'; echo master_key; echo metrics_token; echo admin_password) | sort | tr '\n' ' ')
  [[ "$compose_set" == "$init_set" ]] || { echo "compose: $compose_set"; echo "env-init: $init_set"; return 1; }
}
expect_exit 0 "the secrets of the Compose files are exactly the files argos-env-init.sh generates" secret_names

finish
