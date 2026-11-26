#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# seed-traffic.sh: seed traffic of docs/18 §4.3 (DSN-18-09). Calls every PII/XSS seed endpoint of
# the shop apps and of shop-batch (the batch job name XSS seeds) N times: the seed.* entries of the
# shared catalogs in sample-apps/common (ShopCatalog with --catalog shop, BatchCatalog with
# --catalog batch). Every answer must have its expected status and carry the ARGOS_SEED sentinel
# (GET /seed/env of the shop apps reports which secret-looking variables are seeded and never
# echoes them). After a run, the security tests assert that the sentinel appears nowhere it must
# not (ClickHouse, PostgreSQL, agent and server logs, alert inboxes, CSV exports).
#
# A thin wrapper around the smoke runner (sample-apps/smoke, --only seed. --repeat N): the seed
# list exists once, in the catalog. The apps call localhost targets only (ext-stub).
#
# usage: test/seed/seed-traffic.sh [options]
#   --catalog C        shop (seeds of the shop apps) or batch (shop-batch's job name XSS seeds)
#                                                                             (shop)
#   --url URL          base URL of a running app, WAR context path included  (http://localhost:8080)
#   --set S            all (every seed), pii (the PII and secret seeds: seed.email .. seed.literals
#                      and seed.env; shop catalog only) or xss (the XSS and CSV seeds)  (all)
#   --repeat N         calls of every seed endpoint                           (1)
#   --report-dir DIR   smoke.json and smoke.md of the run                      (build/reports/seed-traffic)
#   --start            start the app here instead of using --url (shop-boot3, or shop-batch with
#                      --catalog batch): ext-stub in process and the seed environment set, stopped
#                      at the end (smoke runner --kind boot-jar)
#   --app-jar JAR      app of --start      (sample-apps/<app>/build/sample-app/<app>.jar, <app> =
#                                           shop-boot3, or shop-batch with --catalog batch)
#   --app-java PATH    java of --start     (default: the --java one)
#   --smoke-jar JAR    smoke runner        (sample-apps/smoke/build/sample-app/smoke.jar)
#   --java PATH        java 17+ for the smoke runner (default: $JAVA_HOME/bin/java, else java)
#   --no-build         never run Gradle (by default missing jars are built)
#
# The environment seeds (DB_PASSWORD, AWS_SECRET_ACCESS_KEY, ARGOS_TOKEN, JAVA_TOOL_OPTIONS with the
# sentinel; common: Seeds.environment()) belong to the app process. --start sets them; for an app
# started elsewhere, start it with them (smoke runner --seed-env, the agent test harness). With
# --catalog shop the script reports what GET /seed/env sees; shop-batch has no /seed/env.
#
# Exit code: 0 every seed call as expected, 1 a seed call failed, 2 usage error or missing tool.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

catalog="shop"
seed_set="all"
url="http://localhost:8080"
repeat=1
report_dir="$repo_root/build/reports/seed-traffic"
start=0
app_jar=""
app_java=""
smoke_jar="$repo_root/sample-apps/smoke/build/sample-app/smoke.jar"
java_bin=""
build=1

usage() {
  sed -n '/^# usage:/,/^# Exit code:/p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

fail_usage() {
  echo "seed-traffic: $*" >&2
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --catalog) catalog="${2:?--catalog needs a value}"; shift 2 ;;
    --url) url="${2:?--url needs a value}"; shift 2 ;;
    --set) seed_set="${2:?--set needs a value}"; shift 2 ;;
    --repeat) repeat="${2:?--repeat needs a value}"; shift 2 ;;
    --report-dir) report_dir="${2:?--report-dir needs a value}"; shift 2 ;;
    --start) start=1; shift ;;
    --app-jar) app_jar="${2:?--app-jar needs a value}"; shift 2 ;;
    --app-java) app_java="${2:?--app-java needs a value}"; shift 2 ;;
    --smoke-jar) smoke_jar="${2:?--smoke-jar needs a value}"; shift 2 ;;
    --java) java_bin="${2:?--java needs a value}"; shift 2 ;;
    --no-build) build=0; shift ;;
    -h | --help) usage ;;
    *) echo "seed-traffic: unknown option $1" >&2; usage ;;
  esac
done
[[ "$repeat" =~ ^[1-9][0-9]{0,4}$ ]] || fail_usage "--repeat must be 1..99999: $repeat"
[[ "$url" =~ ^https?://[^[:space:]]+$ ]] || fail_usage "--url must be http(s)://...: $url"
url="${url%/}"
# The app of each catalog: its project (the --start build) and default jar.
case "$catalog" in
  shop) app_name="shop-boot3" ;;
  batch) app_name="shop-batch" ;;
  *) fail_usage "--catalog must be shop or batch: $catalog" ;;
esac
[[ -n "$app_jar" ]] || app_jar="$repo_root/sample-apps/$app_name/build/sample-app/$app_name.jar"

# Entry id prefixes of each seed set (smoke runner --only). The PII set is the one of the PII seed
# grep (T-NFR-10c, server PiiSeedGrepSecurityTest reads the next line): the XSS and CSV seeds carry
# the sentinel into stored names by design (service path, thread name, external path, job name)
# and are checked by T-SEC-62/63 instead.
pii_prefixes=(seed.email seed.phone seed.rrn seed.card seed.user seed.xff seed.token seed.literals seed.env)
xss_prefixes=(seed.xss. seed.csv.)
only_args=()
case "$seed_set" in
  all) only_args=(--only seed.) ;;
  pii)
    [[ "$catalog" == shop ]] || fail_usage "--set pii needs --catalog shop (shop-batch has only XSS seeds)"
    for p in "${pii_prefixes[@]}"; do only_args+=(--only "$p"); done
    ;;
  xss)
    if [[ "$catalog" == batch ]]; then
      only_args=(--only seed.)
    else
      for p in "${xss_prefixes[@]}"; do only_args+=(--only "$p"); done
    fi
    ;;
  *) fail_usage "--set must be all, pii or xss: $seed_set" ;;
esac

if [[ -z "$java_bin" ]]; then
  if [[ -n "${JAVA_HOME:-}" ]]; then java_bin="$JAVA_HOME/bin/java"; else java_bin="java"; fi
fi
[[ -n "$app_java" ]] || app_java="$java_bin"

# Major version of a java executable (java.specification.version: 17, 21, ...; 1.8 -> 8).
java_major() {
  local v
  v="$("$1" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p' | tr -d '\r')"
  v="${v#1.}"
  [[ "$v" =~ ^[0-9]+$ ]] && echo "$v" || echo 0
}
command -v "$java_bin" >/dev/null 2>&1 || [[ -x "$java_bin" ]] || fail_usage "java not found: $java_bin"
(($(java_major "$java_bin") >= 17)) || fail_usage "$java_bin is not Java 17+ (the smoke runner needs it)"

# --- jars ------------------------------------------------------------------------------------------
tasks=()
[[ -f "$smoke_jar" ]] || tasks+=(:sample-apps:smoke:sampleAppJar)
((start)) && [[ ! -f "$app_jar" ]] && tasks+=(":sample-apps:$app_name:sampleAppJar")
if ((${#tasks[@]})); then
  ((build)) || fail_usage "missing ${tasks[*]} output (run without --no-build or build it first)"
  echo "seed-traffic: building ${tasks[*]}"
  (cd "$repo_root" && ./gradlew -q -Pargos.ui.skip=true "${tasks[@]}") || fail_usage "Gradle build failed"
fi
[[ -f "$smoke_jar" ]] || fail_usage "missing smoke runner $smoke_jar"
mkdir -p "$report_dir"

smoke_args=(--catalog "$catalog" "${only_args[@]}" --repeat "$repeat" --report-dir "$report_dir")

if ((start)); then
  [[ -f "$app_jar" ]] || fail_usage "missing app $app_jar"
  app_major="$(java_major "$app_java")"
  ((app_major >= 17)) || fail_usage "$app_java is not Java 17+ ($app_name)"
  echo "seed-traffic: starting $(basename "$app_jar") on Java $app_major with the seed environment," \
    "calling every seed endpoint of set $seed_set $repeat time(s)"
  set +e
  "$java_bin" -jar "$smoke_jar" --app "$(basename "$app_jar" .jar)" --variant seed-traffic \
    --kind boot-jar --artifact "$app_jar" --app-java "$app_java" --app-jdk "$app_major" --seed-env \
    "${smoke_args[@]}"
  code=$?
  set -e
else
  # What the app process has of the environment seeds (informational: traffic seeds do not need it).
  if [[ "$catalog" == batch ]]; then
    echo "seed-traffic: --catalog batch: shop-batch has no /seed/env, environment seed check skipped"
  elif command -v curl >/dev/null 2>&1; then
    env_body="$(curl -s --max-time 10 "$url/seed/env" || true)"
    if [[ -z "$env_body" ]]; then
      echo "seed-traffic: $url/seed/env did not answer; is the app up?" >&2
    else
      # grep exits 1 without a match: count 0, not a script failure (set -e, pipefail).
      seeded="$( (grep -o '"seeded"' <<<"$env_body" || true) | wc -l | tr -d ' ')"
      echo "seed-traffic: seed environment of the app: $seeded of 4 secret-looking variables seeded"
      ((seeded == 4)) || echo "seed-traffic: start the app with the seed environment for the" \
        "environment-dump seeds (see the header of this script)"
    fi
  fi
  echo "seed-traffic: calling every seed endpoint of set $seed_set at $url $repeat time(s)"
  set +e
  "$java_bin" -jar "$smoke_jar" --app seed-traffic --variant seed-traffic --kind url --url "$url" \
    "${smoke_args[@]}"
  code=$?
  set -e
fi

echo "seed-traffic: report $report_dir/smoke.md (exit code $code)"
exit "$code"
