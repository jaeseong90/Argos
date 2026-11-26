# sample-apps/load

Traffic for the sample apps, in two forms with the same request mix:

| | `bench.js` + `run-bench.sh` | demo load generator (`load.jar`, image `argos-demo-load`) |
|---|---|---|
| Purpose | overhead benchmark workload (docs/18 §7.2, docs/01 §7.3 item 3) | demo profile traffic (docs/17 §3.9), quick local load |
| Engine | k6 `constant-arrival-rate`, `grafana/k6:2.3.0` pinned by digest | own Java 17 code on `java.net.http`, fixed arrival rate |
| License | k6 is AGPL-3.0: development and benchmarks only, never shipped (`docs/licenses/dev-only.md`, ADR-0003 C-33) | Apache-2.0 Argos code only, no third-party dependency (`checkDemoLoadDependencies`, `checkLicenses`: this project is shipped, WO-41); `load.jar` and the image carry the project's `LICENSE` and `NOTICE`. The image's base JRE and OS packages keep their own licenses (their notices in the published image are WO-44's) |

Both are open-model generators: request *n* is due at `start + n / rate` whatever the app's
latency, so a slow app shows up as latency and queueing, not as a lower rate. A request that
cannot start (no free k6 VU, or the generator's in-flight limit) is dropped and counted.

## The mix

`MIX=bench` (and `bench.js`), docs/18 §7.2:

| Route | Share | Expected status | What the app does |
|---|---|---|---|
| `GET /api/orders/{id}` | 40% | 200 | 1 SQL |
| `GET /api/orders?page=` | 20% | 200 | 3 SQL, 100 rows |
| `GET /api/pay/{id}` | 20% | 200 | one ext-stub call |
| `GET /api/orders/{id}/items` | 10% | 200 | N+1: 1 + 20 SQL |
| `GET /api/slow?ms=300` | 5% | 200 | 300 ms sleep |
| `GET /api/error-500` | 5% | 500 | unhandled exception (intended) |

DB 1-3 SQL 60%, external HTTP 20%, N+1 10%, slow 5%, error 5%. The routes are interleaved by a
smooth weighted round robin: every 20 consecutive requests contain the routes exactly 8/4/4/2/1/1
times, and order ids walk through all 1,000 orders of the sample data. `bench.js` and the Java
generator compute the same sequence; `MixTest` checks that the table in `bench.js` (between
`// mix:begin` and `// mix:end`) matches `Mix.bench()` and the shared shop catalog.

`MIX=default` applies the shares of the demo profile of docs/17 §3.9 to the shop apps: normal 70%
(orders 45, page 25), slow 10% (`/api/slow?ms=` 300 ms to 3 s), errors 5% (`error-500` 3,
`send-error` 2), N+1 5%, external call 10% (`/api/pay/{id}` to ext-stub).

`MIX=demo` is the demo profile of docs/17 §3.9 against the demo app `argos-demo-app` (order-api,
[sample-apps/demo-app](../demo-app/README.md); routes and statuses = `DemoCatalog`, WO-41). It is the
default of the `argos-demo-load` image:

| Route (order-api) | Share | Expected status | What happens |
|---|---|---|---|
| `GET /api/orders/{id}` | 45% | 200 | 1 SQL |
| `GET /api/orders?page=` | 25% | 200 | 3 SQL |
| `GET /api/orders/{id}/items` | 5% | 200 | N+1: 1 + 20 SQL |
| `GET /api/slow?ms=` | 10% | 200 | sleep 300 ms to 3 s (100 ms steps) |
| `GET /api/error-500` | 3% | 500 | unhandled exception |
| `GET /api/send-error` | 2% | 503 | `response.sendError(503)` |
| `GET /api/orders/{id}/checkout` (paid orders) | 8% | 200 | 1 SQL, then payment-api `GET /api/payments/{id}` (2 SQL) |
| `GET /api/orders/{id}/checkout` (declined orders) | 2% | 502 | 1 SQL, payment-api fails (1 SQL, exception), order-api answers 502 |

Normal 70%, slow 10%, error 5%, N+1 5%, distributed call order-api to payment-api 10%. payment-api
declines every 5th order id (about 20% of the payments, `DemoCatalog.paymentDeclined`); the two
checkout routes pick their order ids so that each has one expected status. Seen from order-api
7% of the requests end with a 5xx, which keeps the recommended `error_rate` alert (WARN at 5%)
firing in the demo. One cycle of the schedule is 100 requests, and each block of 20 requests
(0-19, 20-39, ...) holds every route its share within one request.

An "unexpected" outcome is any status other than the route's expected one, or no answer (timeout,
connection failure). The intended 5% errors are reported separately (`http5xxRate`).

## k6 benchmark: `run-bench.sh`

```bash
# 1,000 requests/s for 1 minute after a 30 s warm-up (excluded), against shop-boot3 + ext-stub
# started by the script without an agent; JSON summary to the given path.
sample-apps/load/run-bench.sh --summary build/bench/summary.json --rate 1000 --duration 1m

# The full benchmark window of docs/18 §7.2 (3 min warm-up + 10 min):
sample-apps/load/run-bench.sh --summary build/bench/summary.json --duration 10m --warmup 3m
```

Requirements: bash (Git Bash on Windows), Docker, curl, Java 17+ (`JAVA_HOME`). The script builds
what it needs with Gradle unless `--no-build` is given, and stops everything it started.

Where the apps run (`--apps`):

- `process` (default on Linux): `java -jar` on 127.0.0.1, k6 on the host network.
- `container` (default with Docker Desktop): the sample images `argos-sample/shop-boot3:dev` and
  `argos-sample/ext-stub:dev` on a private Docker network shared with k6. Docker Desktop's
  container-to-host proxy left about 1% of requests unanswered for 10 s at 1,000/s even against
  ext-stub alone (measured); container-to-container traffic had no such loss.

Other options: `--target-url` (an app that is already running; with `--docker-network` for one in
containers), `--app-opts` (default `-Xms2g -Xmx2g -XX:+UseG1GC -XX:+AlwaysPreTouch`, docs/01 §7.3
item 2), `--app-env SAMPLE_VT=true`, `--jdk-image` (container mode: another JDK base image),
`--pre-vus`/`--max-vus` (300/600), `--gate off`, `--raw` (every k6 sample as
`k6-raw.json.gz`). `--help` lists them all.

Outputs next to the summary: `summary.json` (k6 summary: requests, achieved rate, unexpected and
5xx rates, dropped iterations, latency p50/p95/p99/p99.9/max overall and per route, thresholds, and
the full k6 data), `machine-before.json` and `machine-during.json` (system CPU load before and
during the run, with a per-second timeline: the machines are shared, a number without the load at
that moment is not comparable), `app-cpu.json` (container mode: shop-boot3 CPU from cgroup
`cpu.stat`), `app.log`, `gc.log`, `ext-stub.log`, `k6.log`, `run.env`.

Gate (exit code 99 when crossed, `--gate off` disables): at most 1% unexpected outcomes and at
least 99% passed checks in the measured window, at most 1% dropped iterations over the whole run.
Dropped iterations are counted for the whole run (k6 does not tag them by phase); in our runs they
all fell into the first seconds of a cold JVM, inside the warm-up.

`bench.js` can also run on its own:
`k6 run -e TARGET_URL=http://host:8080 -e RATE=1000 -e DURATION=1m -e WARMUP=30s -e SUMMARY_PATH=summary.json bench.js`.

## Demo load generator

```bash
./gradlew :sample-apps:load:sampleAppJar
java -jar sample-apps/load/build/sample-app/load.jar --target-url http://localhost:8080 \
  --mix bench --rate 1000 --warmup 30s --duration 1m --summary build/load/summary.json
```

Settings are environment variables (the container image) or options (the option wins):

| Variable | Option | Default | |
|---|---|---|---|
| `TARGET_URL` | `--target-url` | `http://localhost:8080` | base URL, a WAR context path included |
| `RATE` | `--rate` | `50` | requests per second |
| `MIX` | `--mix` | `default` (`demo` in the `argos-demo-load` image) | `default` (17 §3.9 shares, shop apps), `demo` (17 §3.9, demo app) or `bench` (18 §7.2) |
| `DURATION` | `--duration` | unset: until stopped | measured window |
| `WARMUP` | `--warmup` | `0s` | same rate and mix first, excluded from the summary |
| `MAX_IN_FLIGHT` | `--max-in-flight` | `max(64, rate)`, at most 4096 | due requests beyond it are dropped |
| `REQUEST_TIMEOUT` / `CONNECT_TIMEOUT` | `--request-timeout` / `--connect-timeout` | `10s` / `3s` | |
| `REPORT_INTERVAL` | `--report-interval` | `10s` (`0` disables) | progress line |
| `SUMMARY_PATH` | `--summary` | none | JSON summary at the end |
| `MAX_UNEXPECTED_RATE` | `--max-unexpected-rate` | no gate | exit code 1 above it |
| `HTTP_THREADS` | `--http-threads` | `4` | HTTP client completion threads |

Exit codes: 0 done, 1 the `MAX_UNEXPECTED_RATE` gate failed or the summary could not be written,
2 bad settings. `docker stop` (SIGTERM) ends the run, drains for up to 5 s and still writes the
summary. Memory stays constant however long it runs (fixed-size counters and histograms).

Images:

```bash
# The public demo image (Jib, local Docker daemon, linux/amd64; WO-41). Base = the server image's
# digest-pinned Temurin 21 JRE, user 10001:10001, LICENSE and NOTICE in /opt/argos-demo, defaults
# TARGET_URL=http://sample-app-a:8080, RATE=50, MIX=demo (the demo profile of deploy/compose).
./gradlew :sample-apps:load:jibDockerBuild                # argos-demo-load:<version>
docker run --rm --network <net> -e TARGET_URL=http://order-api:8080 argos-demo-load:0.0.0-SNAPSHOT

# The development image (Dockerfile, Java 17 JRE, user 10001), for the shop apps:
./gradlew :sample-apps:load:sampleImage                   # argos-sample/load:dev
docker run --rm --network <net> -e TARGET_URL=http://shop-boot3:8080 -e RATE=50 -e MIX=default argos-sample/load:dev
```

Multi-arch images, registry push and signing of `argos-demo-load` are WO-44.

## By hand: one app and one minute of load

1. Build: `./gradlew :sample-apps:ext-stub:sampleAppJar :sample-apps:shop-boot3:sampleAppJar :sample-apps:load:sampleAppJar`
2. Start ext-stub: `java -jar sample-apps/ext-stub/build/sample-app/ext-stub.jar --port 18080`
3. Start the app (another terminal; JDK 17+):
   `java -jar sample-apps/shop-boot3/build/sample-app/shop-boot3.jar --server.address=127.0.0.1` and wait for
   `curl http://localhost:8080/actuator/health` to answer `{"status":"UP"}`.
4. One minute of load (another terminal):
   `java -jar sample-apps/load/build/sample-app/load.jar --mix bench --rate 1000 --warmup 30s --duration 1m --summary build/load/summary.json`
   The last line shows the achieved rate, the unexpected share and the percentiles.
5. Stop the app and ext-stub with Ctrl+C.

Or let the script do steps 1 to 5 with k6: `sample-apps/load/run-bench.sh --summary build/bench/summary.json`.

## Tests

- `./gradlew :sample-apps:load:test`: schedule, mixes (also against `bench.js`, the shop catalog
  and, for `MIX=demo`, `DemoCatalog`: shares, 20-request windows, checkout ids, T-NFR-16b),
  configuration, histogram, generator timing on a simulated clock (open model, drops, late
  wake-ups, warm-up, stop), outcomes, summary.
- `./gradlew :sample-apps:load:integrationTest`: the generator over HTTP against an in-process
  server; then shop-boot3 + ext-stub with the seed environment: every seed endpoint answers with
  the `ARGOS_SEED` sentinel, `test/seed/seed-traffic.sh` runs, and the packaged `load.jar` runs
  both mixes without an unexpected answer (about 50 s). The `load.jar` runs start with a 3 s
  warm-up (a cold app is excluded from the measured window) and an in-flight cap of rate x request
  timeout; a failure message names the outcome counts (TIMEOUT and the others) and the machine CPU
  load sampled just before the run.
- `checkDemoLoadDependencies` (part of `check`): the generator keeps zero runtime dependencies;
  `checkLicenses` (part of `check`) checks the runtime classpath against the allow list.
