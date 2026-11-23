# Sample applications

Realistic target applications shared by the agent integration tests, the compatibility matrix,
the overhead benchmarks, E2E and security tests (docs/18 §4, C-18). They are development-only:
their dependencies are checked by `checkDevOnlyLicenses` against
[docs/licenses/dev-only.md](../docs/licenses/dev-only.md), and nothing here is shipped, with two
exceptions:

- `load` is the published demo load generator image `argos-demo-load` (ADR-0003 C-33): its runtime
  uses allow-listed licenses only, checked by `checkDemoLoadDependencies`.
- The published demo app image `argos-demo-app` is to be built from `shop-boot3` (docs/17 §3.9),
  but the `shop-boot3` stack of docs/18 §4.1 (Hibernate, Logback, H2, MySQL Connector/J, ...) is
  not allow-listed (dev-only.md condition 2). The image must not be built from this configuration;
  the conflict is open for a maintainer decision (WO-41).

## Projects

| Project | Stack | Compiled for | Runs on (02 §9) | Packaging | Catalog |
|---|---|---|---|---|---|
| `common` | Framework-neutral core: endpoint catalog, switches, schema and data, JDBC operations, pools and routing data source, outgoing calls, seeds | Java 8 | every app | library | – |
| `ext-stub` | Outgoing-call target (JDK `HttpServer`) | Java 17 | 17+ | `java -jar` | – |
| `smoke` | Smoke runner behind every `sampleSmoke` task | Java 17 | 21 (build JDK) | library + `java -jar` | – |
| `shop-boot2` | Spring Boot 2.7, Framework 5.3, javax, Tomcat 9, Hibernate 5, HikariCP, RestTemplate, HC4, OkHttp 3 | Java 8 | 8, 11, 17, 21 | Spring Boot jar | shop |
| `shop-boot3` | Spring Boot 3.5, Framework 6.2, jakarta, Tomcat 10.1, Hibernate 6, HikariCP, RestTemplate, WebClient, HC5, OkHttp 4, Java 11 HttpClient (reference app) | Java 17 | 17, 21, 25 | Spring Boot jar | shop |
| `shop-boot4` | Spring Boot 4.1, Framework 7, jakarta 6.1, Tomcat 11, Hibernate 7 | Java 17 (built and smoked on 21) | 17, 21, 25 | Spring Boot jar | shop |
| `shop-heavy` | `shop-boot3` + Spring Cloud, Hibernate and Jackson modules, QueryDSL, springdoc, POI, AWS SDK (≥ 200 jars, ≥ 30,000 classes) | Java 17 | 17, 21, 25 | Spring Boot jar | shop |
| `shop-batch` | Spring Boot 3.5, `@Scheduled` ×3, Quartz ×2, Spring Batch (Tasklet + chunk of 5,000), one shared DataSource | Java 17 | 17, 21, 25 | Spring Boot jar | batch |
| `shop-war-javax` | `shop-boot2` code as a WAR on a standalone Tomcat 9 | Java 8 | 8, 11, 17, 21 | WAR | shop |
| `shop-war-jakarta` | `shop-boot3` code as a WAR on a standalone Tomcat 10.1 or 11, deployed twice (`/shop-a`, `/shop-b`) | Java 17 | 17, 21, 25 | WAR | shop |
| `load` | Demo load generator (fixed arrival rate, own code, allow-listed licenses only) and the k6 benchmark scenario `bench.js` with its runner `run-bench.sh` | Java 17 | 17+ | `java -jar` | – |

The WAR apps use the names of docs/18 §4.1 (`shop-war-*`; docs/04 says `war-*`).

Each app has its own README with the stack details, its smoke variants and what was measured:
[shop-boot2](shop-boot2/README.md), [shop-boot4](shop-boot4/README.md),
[shop-heavy](shop-heavy/README.md), [shop-batch](shop-batch/README.md),
[shop-war-javax](shop-war-javax/README.md), [shop-war-jakarta](shop-war-jakarta/README.md),
[load](load/README.md); the seed traffic script is described in [test/seed](../test/seed/README.md).
`shop-boot3` is the reference app and is described in this file.

Every module is compiled by the JDK 21 compiler with `--release <Java version>` (as the agent is);
tests and smoke runs start the app on its own JDK through Gradle toolchains (a missing JDK 8, 11,
17 or 25 is downloaded automatically).

## Build and run one app

```sh
./gradlew :sample-apps:ext-stub:sampleAppJar :sample-apps:shop-boot3:sampleAppJar
java -jar sample-apps/ext-stub/build/sample-app/ext-stub.jar &          # http://127.0.0.1:18080
java -jar sample-apps/shop-boot3/build/sample-app/shop-boot3.jar --server.address=127.0.0.1   # http://127.0.0.1:8080
curl http://127.0.0.1:8080/api/orders/1
curl http://127.0.0.1:8080/api/runtime
```

Use a JDK the app supports (`java -version`): `shop-boot2` needs Java 8+, `shop-boot3` 17+, `shop-boot4`
17+. Stop the apps with Ctrl+C (or `kill %1` for the background ext-stub). Each packaged app comes
with `build/sample-app/<app>.properties` (kind, artifact, main class, JDK, catalog, WAR contexts),
which the agent test harness reads.

A WAR app runs in the Tomcat distribution that Gradle stages:

```sh
./gradlew :sample-apps:shop-war-jakarta:war :sample-apps:shop-war-jakarta:sampleStageTomcatTomcat10
# CATALINA_HOME = sample-apps/shop-war-jakarta/build/tomcat/tomcat10; copy the WAR to webapps/
```

The `sampleSmoke` tasks below do all of this (port, CATALINA_BASE, ext-stub) automatically.

## Switches (environment)

| Variable | Values | Default | Effect |
|---|---|---|---|
| `SAMPLE_DB` | `h2`, `postgres`, `mysql`; `mariadb`, `mssql` (also `sqlserver`), `oracle` in `shop-boot3` only (its drivers, WO-13) | `h2` | Database. H2 is in memory (one database per app). The schema and seed script is per dialect (`schema-<db>.sql` of `sample-apps/common`); default URLs point to `127.0.0.1` with the database `shop` (SQL Server: `databaseName=shop`, Oracle: service `FREEPDB1`). |
| `SAMPLE_DB_URL`, `SAMPLE_DB_USER`, `SAMPLE_DB_PASSWORD` | JDBC URL and account | per database (user `shop`, SQL Server `sa`) | Testcontainers inject these (the agent-testing `jdbc` suite: a random password per run, never logged). |
| `SAMPLE_POOL` | `hikari`, `tomcat`, `dbcp2` | `hikari` | Connection pool. |
| `SAMPLE_POOL_MAX` | 1–200 | 10 | Pool size (`/api/pool/exhaust` takes size + 1). |
| `SAMPLE_ROUTING_DS` | `true`/`false` | `false` | `AbstractRoutingDataSource → TransactionAwareDataSourceProxy → pool → driver` (D-24 fixture, the four levels of docs/18 §4.4). `/api/runtime` reports the chain by walking the objects (`dataSourceChain`) and the connections routed so far (`routingLookups`); every smoke fails when `routingDataSource` is `true` and `routingLookups` is 0. |
| `SAMPLE_DRIVER_WRAPPER` | `true`/`false` | `false` | The fifth level of ADR-0004 C-40: the pool connects through `example.jdbcwrapper.WrappingDriver`, a P6Spy/log4jdbc style `java.sql.Driver` that wraps `Connection`, `Statement`, `PreparedStatement` and `CallableStatement` by delegation (package outside `io.argos` on purpose, so the agent instruments it like a third-party wrapper). Combinable with `SAMPLE_ROUTING_DS`; `/api/runtime` reports `driverWrapper` and `driverWrapperConnections`. |
| `SAMPLE_SECOND_POOL` | `true`/`false` | `false` | A second pool of the `SAMPLE_POOL` kind on the same database that sets up the schema, is closed and is created again under the same name with another size (pool registry fixture of the agent tests, 10 §6.4). HikariCP and Tomcat JDBC pools are named `shop-primary` (the app's) and `shop-secondary`; DBCP2 pools have no name. |
| `SAMPLE_VT` | `true`/`false` | `false` | Virtual threads (Boot 3.2+ on JDK 21+); `/api/runtime` shows `virtualThreads: true` when the request ran on one. |
| `SAMPLE_CUSTOM_EXECUTOR` | `true`/`false` | `false` | `ThreadPoolExecutor` subclass with thread names `seed-ARGOS_SEED-N`. |
| `SAMPLE_EXT_URL` | URL | `http://127.0.0.1:18080` | ext-stub. |
| `SAMPLE_EXT_REFUSED_PORT` | port | `1` | Port of the ext-stub host where nothing listens (`refuse=1`). |
| `SAMPLE_EXT_TIMEOUT_MS`, `SAMPLE_EXT_CONNECT_TIMEOUT_MS` | ms | 1000, 5000 | Read and connect timeout of outgoing calls. Calls to the next hop of `GET /api/hop/{id}` use the read timeout + 10 s (at most 70 s). |
| `SAMPLE_HOP_URL` | http(s) base URL without query | the app itself (`http://127.0.0.1:<local port><context path>`) | Next hop of `GET /api/hop/{id}` (another sample app for a 2-hop or 3-hop distributed trace). Read by `HopService` (common). |
| `-Dsample.fixed-clock` (or `SAMPLE_FIXED_CLOCK`) | ISO instant, e.g. `2026-01-15T00:00:00Z` | system clock | Fixed business time (E2E data). |
| `SAMPLE_APP_NAME`, `SAMPLE_APP_VERSION` | text | build info | Name and version reported by `/api/version`. |
| `SERVER_PORT` | port | 8080 | HTTP port of the Spring Boot apps. |
| `SAMPLE_HEAVY_WARMUP` | `true`/`false` | `true` | `shop-heavy` only: the startup steps that load 30,000+ classes before the port is bound ([shop-heavy](shop-heavy/README.md)). |
| `SAMPLE_BATCH_*` | ms or cron | see [shop-batch](shop-batch/README.md) | `shop-batch` only: periods of the `@Scheduled`, Quartz and long-running jobs. |

An invalid value stops the app at startup with the variable name in the message. `SAMPLE_VT=true`
where virtual threads are not available (`shop-boot2` on any JDK, a Boot 3/4 app on JDK 17) keeps
platform threads and says so in `/api/runtime` (`virtualThreadsRequested: true`,
`virtualThreads: false`; smoke variants `vt-not-applicable` and `vt-jdk17`). In the WAR apps the
request threads belong to the standalone Tomcat (`server.xml`), so `SAMPLE_VT` is not part of
their variants.

## Endpoint catalog

The shop apps implement one catalog: `common/src/main/java/io/argos/sample/common/catalog/ShopCatalog.java`
(docs/18 §4.2 and the seeds of §4.3); `shop-batch` implements `BatchCatalog.java`. Each entry has the
request (method, path, headers), the route (the expected service name pattern), the expected status,
checks, and the purpose column of docs/18. The smoke report (`smoke.md`) lists every entry with its
result. Highlights:

- `GET /api/orders/{id}` 1 SQL, `GET /api/orders?page=` 3 SQL and 100 rows, `GET /api/orders/{id}/items`
  N+1 = 1 + 20 SQL (JPA lazy loading in the Boot apps). The two JPA answers carry `sqlStatements`,
  the statements Hibernate prepared for the request (a `StatementInspector` of the app,
  `hibernate.session_factory.statement_inspector`); the smoke checks 1 and 21. `ShopJdbc` in
  common has a JDBC variant of the same counts for tests (shop-batch serves its JDBC
  `/api/orders/{id}`).
- `GET /api/pay/{id}?client=hurl|hc4|hc5|okhttp|jdk11|webclient|resttemplate`: one call to ext-stub.
  `timeout=1` → 504, `refuse=1` → 503, `status=500` → 502, `redirect=1` → 200. Each app supports the
  clients of its stack and reports them in `GET /api/runtime` (`clients`, `capabilities`). The
  build declares the stack (`smoke { expectClients(...) }`, docs/18 §4.1): every smoke variant fails
  when the reported set differs, so a lost client is a failure, not a skipped pay entry.
  `mode=` picks the client call style: `sync` (default, every client), `async` (OkHttp `enqueue`,
  java.net.http `sendAsync`; docs/10 U-10, U-11), `block|future|subscribe` (WebClient `block()`,
  `toFuture().get()`, `subscribe(...)`); in the asynchronous modes the request thread waits for the
  result. An unsupported mode answers 400. `preset=1` makes the app set its own `traceparent` (random,
  reported as `presetTraceparent`) and `tracestate: shop=preset1` on the request (the agent keeps
  them, docs/10 §8).
- `GET /api/hop/{id}?depth=N&client=X[&fail=timeout|refuse|status500][&mode=M][&preset=1]` (WO-14,
  `common/.../http/HopService.java`): `depth` > 0 calls `SAMPLE_HOP_URL` (default: the app itself)
  `/api/hop/{id}?depth=N-1` with the same client, `fail` and `mode`; `depth` 0 is the `/api/pay/{id}`
  call to ext-stub with `fail` selecting `timeout=1`, `refuse=1` or `status=500`. `depth` ≤ 5
  (default 1). The answer follows `/api/pay`: 200 when the next hop answered 2xx, 502 with
  `upstreamStatus` when it answered an error (a failure at the end of the chain reaches the first app
  this way), 503/504 when the call itself failed. Two apps (A with `SAMPLE_HOP_URL` = B) give the
  2-hop traces of T-ACC-05; `depth=2` on one app gives a 3-hop trace through itself. For the agent's
  `caller_app_id` the internal host must be in `propagation.argos-state.hosts` (SEC-38 default: no
  Argos `tracestate` entry).
- WebFlux fixture (shop-boot3 jar, `io.argos.sample.boot3.http.webflux.WebFluxFixture`): a
  functional Spring WebFlux server on Reactor Netty without servlets and without Spring Boot
  (`GET /`, `GET /api/flux/hello`, `GET /api/flux/call/{id}` = a WebClient call to
  `<SAMPLE_EXT_URL>/flux/{id}`; `SERVER_PORT`, `SERVER_ADDRESS`). It is not part of the shop app's
  context; the agent harness starts it from the unpacked jar (T-AG-055). By hand:
  `java -Dloader.main=io.argos.sample.boot3.http.webflux.WebFluxFixture -cp shop-boot3.jar
  org.springframework.boot.loader.launch.PropertiesLauncher`.
- `GET /api/pool/exhaust?hold=5000`: the pool size + 1 connections, the last one waits for a holder.
  The answer counts the holders that really took a connection (`held`, `failedHolders`); `allHeld`
  is true only when all of them did.
- `GET /api/threads/dump-me` blocks 10 s in `ThreadDumpTarget.blockHere`; `GET /api/mdc-log` logs a
  line with the MDC (`logging.pattern.level=%5p [%X]`).
- `GET /api/not-found-x` has no handler at all: the apps turn Spring Boot's default `/**` resource
  handler off (`spring.web.resources.add-mappings=false`) and map only `/favicon.ico` and
  `/static/**`; `GET /static/missing.js` is the 404 of the static resource handler.
- `GET /seed/*`: PII, secret and XSS seeds. Every seed carries the sentinel `ARGOS_SEED`
  (`common/.../seed/Seeds.java`). Documented exceptions: the `*_PATTERN` values, and the access key
  shape `AKIAARGOSSEED0000001` (docs/18 §4.3 value, `X-Api-Key` header), which carries `ARGOSSEED`
  without the underscore, so leak scans must also search for `ARGOSSEED`.

## ext-stub

| Path | Behaviour |
|---|---|
| any other path | 200, JSON echo (method, path, query, `traceparent`, `tracestate`) |
| `/delay?ms=N` | 200 after N ms (≤ 60,000) |
| `/timeout?ms=N` | no answer; the connection closes after N ms (default 30,000, ≤ 120,000) |
| `/status/{code}` | that status (200–599) |
| `/redirect?to=/path` | 302 to a path on ext-stub (absolute targets are rejected) |
| `/close` | connection closed without an answer |
| `/_stub/requests` | GET: the last 1,000 requests with headers; DELETE: clear |
| `/_stub/health` | 200 |

Connection refused cannot come from a listening socket: the apps call port `SAMPLE_EXT_REFUSED_PORT`
(default 1) of the ext-stub host, where nothing listens. Options: `--port` (18080), `--bind`
(127.0.0.1; the image binds 0.0.0.0), `--threads` (64), or `EXT_STUB_PORT`, `EXT_STUB_BIND`.
ext-stub turns on TCP_NODELAY of the JDK HTTP server (`sun.net.httpserver.nodelay=true` unless
set with `-D`): the server writes headers and body separately, and a Linux client's delayed ACK
would otherwise add about 40 ms to every answer.

## Smoke tests

```sh
./gradlew :sample-apps:shop-boot3:sampleSmoke              # every variant of one app
./gradlew :sample-apps:shop-boot3:sampleSmokeVt            # one variant
./gradlew sampleSmoke                                      # every app
./gradlew :sample-apps:shop-boot3:sampleSmokeDefault -Psample.startupRuns=5   # startup median of 5
```

A smoke variant starts ext-stub in-process, starts the packaged app without an agent on a free port
of 127.0.0.1 only (Spring Boot apps get `SERVER_ADDRESS=127.0.0.1`, the Tomcat connector of the WAR
apps binds 127.0.0.1) with the JDK and environment of the variant, waits for the port and
`/actuator/health`, calls every catalog entry the app supports, checks status codes and
expectations, checks the variant's expected `/api/runtime` fields (and, for every app, that a
reported routing data source really routed connections), and stops the app. Results:
`build/reports/sample-smoke/<variant>/smoke.md` and `smoke.json` (jar count, class count, startup
times, machine CPU load at that moment, runtime facts, every entry), and the app log in
`run-1/app.log`. One smoke runs at a time across the whole build so that startup times are not
disturbed by another app.

| Property | Effect |
|---|---|
| `-Psample.startupRuns=N` | Start the app N times, catalog on the first start, report the median startup. |
| `-Psample.extStubUrl=<url>` | Use a running ext-stub instead of the in-process one. |

Variants (task `sampleSmoke<Variant>`, for example `sampleSmokeRoutingDs`; the first one runs on
the build JDK of the app):

| App | Variants |
|---|---|
| `shop-boot2` | `default` (JDK 8), `jdk11`, `jdk17`, `jdk21`, `routing-ds`, `pool-tomcat`, `pool-dbcp2`, `seeds` (with the fixed clock), `vt-not-applicable` (JDK 21) |
| `shop-boot3` | `default` (JDK 17), `jdk25` (with `--sun-misc-unsafe-memory-access=deny`), `vt` (JDK 21), `routing-ds`, `pool-tomcat`, `pool-dbcp2`, `seeds` |
| `shop-boot4` | `default` (JDK 21), `jdk17`, `jdk25`, `vt`, `vt-jdk17`, `vt-jdk25`, `routing-ds`, `pool-tomcat`, `pool-dbcp2`, `seeds` (JDK 25 with `--sun-misc-unsafe-memory-access=deny`) |
| `shop-heavy` | `default` (JDK 17), `jdk21`, `jdk25` (with `--sun-misc-unsafe-memory-access=deny`), `no-warmup` |
| `shop-batch` | `default` (JDK 17), `jdk21`, `jdk25` (with `--sun-misc-unsafe-memory-access=deny`), `vt`, `routing-ds` |
| `shop-war-javax` | standalone Tomcat 9, context `/shop-war-javax`: `default` (JDK 8), `jdk11`, `jdk17`, `jdk21`, `routing-ds` (JDK 21), `seeds` (JDK 8) |
| `shop-war-jakarta` | two web apps `/shop-a` and `/shop-b`: `tomcat10` (Tomcat 10.1, JDK 17), `tomcat10-jdk21`, `tomcat10-jdk25`, `tomcat11` (Tomcat 11, JDK 21), `tomcat11-jdk17`, `tomcat11-jdk25`, `seeds`, `routing-ds`, `pool-tomcat` |

Where they run (docs/18 §2.1, §2.4):

| Task | Runs | Where |
|---|---|---|
| `./gradlew build` / `check` | unit tests of the sample modules; no app is started | every pull request (`build-java`) |
| `./gradlew integrationTest` | `shop-boot3` variants `default`, `vt` and `routing-ds` (one sample app in the integration layer); ext-stub over HTTP, in-process and as the packaged `java -jar ext-stub.jar` (options, `EXT_STUB_PORT`, unknown option); `load`: the generator over HTTP, then `shop-boot3` with the seed environment (the `ARGOS_SEED` sentinel in every seed answer, `test/seed/seed-traffic.sh`, both mixes of `load.jar`); `shop-heavy` report rendering; the tests over 1 s (`@Tag("slow")`, docs/18 §2.2) of `common`, `shop-boot2` and `shop-batch` | every pull request (`integration`) |
| `./gradlew sampleSmoke` | every variant of every app (50) | by hand before changing a sample app; no workflow runs it yet (its JDK, virtual thread and pool combinations belong to the nightly matrix layer of docs/18 §2.1) |

The smokes are not part of `check` (PR time budget, docs/18 §2.4: the pull request gate takes at
most 25 minutes, the integration layer at most 12). Measured on 2026-10-24 on a Linux container
limited to 4 CPUs with empty Gradle caches (like a GitHub-hosted runner): the `build-java` command
`./gradlew build -x integrationTest` took 7 min 14 s to 8 min 28 s (six runs, `--cpus=4` and
`--cpuset-cpus=0-3`, on an otherwise idle host) with the unit tests of the sample modules, and the
`integration` job (`./gradlew integrationTest`) 6 min 47 s and 6 min 48 s (two runs; 6 min 18 s on
2026-10-23).

In a build that also runs the UI unit tests (`:ui:test`, Vitest), every sample-app task except
`clean` runs after them (`mustRunAfter`, set by the `argos.sample-app` convention): with the sample
modules building next to it on 4 CPUs, Vitest exceeded its per-test timeouts. This only orders
tasks; it adds no dependency, and with `-Pargos.ui.skip=true` `:ui:test` is skipped at once. The
Vitest timing margin stays small: on a host where another program used 2 to 4.5 of the 12 CPUs
(machine CPU median 68%), one such run still failed with a per-test timeout in
`ui/src/xlog/renderer.test.ts` although no sample-app task ran during `:ui:test` (2026-10-24 10:42).

`./gradlew sampleSmoke` took 28 min 17 s for the 50 variants on the 12-thread Windows development
machine (all PASS; machine CPU load sampled every 10 s: median 18%, 0–88%; an earlier run the same
day took 28 min 21 s), too long for a pull request job.

The runner also checks an app that is already running (for example a container):

```sh
./gradlew :sample-apps:smoke:sampleAppJar
java -jar sample-apps/smoke/build/sample-app/smoke.jar --app shop-boot3 --kind url \
  --url http://127.0.0.1:8080 --catalog shop --report-dir build/smoke-manual
```

In this mode there is no startup measurement and the log checks are not evaluated. `--only <entry id
prefix>` and `--repeat N` turn the runner into a traffic generator from the same catalog, for example
every seed endpoint 100 times: `--only seed. --repeat 100` (the seed traffic of docs/18 §4.3).

## Container images

Each app has a `Dockerfile` with a `JDK_IMAGE` build argument; the default is the build JDK of the
table above, pinned by the digest of its multi-arch index:

| JDK | Image |
|---|---|
| 8 | `eclipse-temurin:8-jre@sha256:1fc1cdba9c1e68173bc553b95438206b0b995f9f763cfc77feb45b27f5774d9b` |
| 11 | `eclipse-temurin:11-jre@sha256:d218907a19a41f9036cf547a0a84a408cfb1bc56aeb2be090ae867563e086458` |
| 17 | `eclipse-temurin:17-jre@sha256:e04fa72be13b257a59fdec6dc14af78c8f80f035d3c1ed4e03e45552a231d4c8` |
| 21 | `eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99` |
| 25 | `eclipse-temurin:25-jre@sha256:dbb43d81099eec91e0800226ed2296d03146ee7857165b95112372ad0d67e87f` |

```sh
./gradlew :sample-apps:shop-boot3:sampleImage                                  # argos-sample/shop-boot3:dev
./gradlew :sample-apps:shop-boot3:sampleImage -Psample.jdkImage=eclipse-temurin:21-jre@sha256:2271...
docker network create argos-sample
docker run -d --rm --name ext-stub --network argos-sample argos-sample/ext-stub:dev
docker run --rm --network argos-sample -p 127.0.0.1:8080:8080 argos-sample/shop-boot3:dev
```

The images run as user 10001. `JAVA_OPTS` adds JVM options (for example `-javaagent:...`). The
digests were taken on 2026-10-23; refresh them by hand (`docker buildx imagetools inspect <image>`).

## Load

`load/bench.js` is the k6 scenario of docs/18 §7.2 (constant arrival rate 1,000/s; mix: orders 40%,
page 20%, pay 20%, N+1 10%, slow 5%, error 5%). k6 (AGPL-3.0) runs as a separate tool, from the
`grafana/k6` image pinned by digest, and is never part of an Argos artifact. The demo load generator
(`load`, image `argos-demo-load`) is Argos code on `java.net.http` without third-party dependencies
(ADR-0003 C-33). Details, options and outputs: [load/README.md](load/README.md).

```sh
# k6 at 1,000 requests/s for 1 minute (after a 30 s warm-up) against shop-boot3 + ext-stub started
# by the script (Docker needed); JSON summary and machine load records next to the summary
sample-apps/load/run-bench.sh --summary build/bench/summary.json --rate 1000 --duration 1m

# the same mix with the demo load generator against an app that is already running
java -jar sample-apps/load/build/sample-app/load.jar --target-url http://127.0.0.1:8080 \
  --mix bench --rate 1000 --warmup 30s --duration 1m --summary build/load/summary.json

# seed traffic (PII, secret and XSS seeds with the ARGOS_SEED sentinel, docs/18 §4.3)
test/seed/seed-traffic.sh --url http://127.0.0.1:8080 --repeat 10
```

## Startup baseline without an agent

This is a provisional baseline that does not follow the measurement definition of docs/18 §7.4 and
docs/01 §7.3 item 8 (JVM start to port binding and the Spring `Started … in` line, median of 10
starts, default and heavy app, both `-Xshare:off` and the default CDS). Here: median of 5 starts,
the default CDS only (one `-Xshare:off` set for `shop-boot3` and `shop-heavy` below), "Port" =
process start to the open HTTP port, "Ready" = `/actuator/health` UP, on a shared development
machine. N-04 and N-27 are judged with the WO-02 `benchTest` measurement, not with this table.

The reference for N-04 (startup overhead) and N-27 (transformation cost): every app on its build
JDK, no agent, five starts each (`./gradlew :sample-apps:<app>:sampleSmoke<Variant>
-Psample.startupRuns=5`), H2, on a shared Windows 11 development machine (12 logical CPUs,
15.7 GB). The table is set 3, the current code, measured on 2026-10-24 from 05:15 to 05:34 on a
mostly idle machine (machine CPU load sampled every 10 s: median 33%, 1–73%, the app under test
included). "Port" is the time from process start to the open HTTP port, "Ready" to
`/actuator/health` UP (of both web apps for `shop-war-jakarta`), "`Started … in`" Spring Boot's
own line in seconds with its "process running for" in brackets (for the WAR apps the first web
app's line); "CPU before start" is the system CPU load just before each of the five starts. The
last column is the ready median of the two earlier sets of 2026-10-23, taken before the r1 fixes
while other work was running (set 1 21:19–21:40, machine CPU median 43%, 4–85%; set 2
23:03–23:25, median 40%, 5–100%). Repeat the measurement on the benchmark machine before using it
as a gate.

| App | JDK | Jars | Classes (packaged) | Loaded classes | Port, median | Ready, median | `Started … in` (process running), median | CPU before start | Ready, sets 1 / 2 |
|---|---|---|---|---|---|---|---|---|---|
| `shop-boot2` | 8 | 80 | 28,162 | 13,322 | 5,438 ms | 5,654 ms | 4.95 s (5.43 s) | 3–15% | 7,160 / 6,452 ms |
| `shop-boot2` | 21 | 80 | 28,162 | 14,159 | 5,507 ms | 5,721 ms | 4.99 s (5.49 s) | 1–5% | 7,137 / 6,688 ms |
| `shop-boot3` | 17 | 112 | 42,194 | 19,411 | 7,320 ms | 7,559 ms | 6.71 s (7.31 s) | 0–13% | 9,677 / 8,964 ms |
| `shop-boot3` (`SAMPLE_VT=true`) | 21 | 112 | 42,194 | 19,764 | 6,999 ms | 7,270 ms | 6.36 s (6.98 s) | 3–31% | – / 8,455 ms |
| `shop-boot4` | 21 | 141 | 40,679 | 21,210 | 7,432 ms | 7,717 ms | 6.72 s (7.42 s) | 2–14% | 9,561 / 9,019 ms |
| `shop-heavy` | 17 | 267 | 76,978 | 33,890 (31,917 at startup) | 29,706 ms | 29,945 ms | 29.26 s (30.11 s) | 3–22% | 32,364 / 37,458 ms |
| `shop-batch` | 17 | 64 | 18,993 | 10,749 | 4,840 ms | 5,111 ms | 4.30 s (4.86 s) | 3–40% | 6,557 / 5,952 ms |
| `shop-war-javax` (Tomcat 9.0.123) | 8 | 76 + Tomcat 35 | 26,253 + 3,098 | 13,937 | – | 17,669 ms | 9.46 s (17.22 s) | 0–17% | 22,186 / 22,326 ms |
| `shop-war-jakarta` (Tomcat 10.1.61, 2 web apps) | 17 | 108 + Tomcat 37 | 40,316 + 3,606 | 34,134 | – | 44,830 ms | 12.43 s (23.18 s) | 5–8% | 55,468 / 57,288 ms |
| `shop-war-jakarta` (Tomcat 11.0.27, 2 web apps) | 21 | 108 + Tomcat 37 | 40,316 + 3,508 | 34,440 | – | 44,256 ms | 12.07 s (23.02 s) | 5–17% | 53,492 / 53,033 ms |

`ext-stub` (4 classes) and `load` (26 classes) have no third-party jars. Since sets 1 and 2 the
shop apps have one packaged class more (r1 fixes), and the staged Tomcat no longer contains the
Eclipse JDT compiler (`lib/ecj-*.jar`, EPL, needed only for JSPs; `docs/licenses/dev-only.md`
`org.apache.tomcat:tomcat`): sets 1 and 2 counted one container jar more (Tomcat 9: 36 jars, 3,865
classes; 10.1: 38, 4,375; 11: 38, 4,319).

Loaded classes are counted after the catalog ran; `shop-heavy` also reports the count when Spring
Boot has started, before any request (`startupLoadedClasses` in `/api/runtime`, the N-27 figure).
A standalone Tomcat opens its port while it initializes, before it deploys the web apps, so only
"ready" is meaningful for the WAR apps; it includes unpacking and scanning the WARs. On Linux
containers the same apps start faster (for example `shop-war-jakarta` about 20 s instead of
45–55 s). Between the three sets `shop-heavy` ready varied from 30 s to 37 s and every app was
slower on the busier machine, so compare agent and no-agent starts in alternation on the same
machine (01 §7.3) rather than against this table.

`-Xshare:off` against the default CDS (JDK 17, five starts per row, 2026-10-24 06:49 to 06:56
on a mostly idle machine, machine CPU load sampled every 10 s: median 47%, 1–71%, the app under
test included; per app the `-Xshare:off` row first, then the default row). Smoke runner jar with
`--startup-runs 5 --only version` (plus `--jvm-arg -Xshare:off`); "Started" is the median of
Spring Boot's `Started … in` seconds (n = runs whose line was in the log when the runner read it,
200 ms after "ready").

| App | CDS | Port, median | Ready, median | `Started … in`, median | CPU before start |
|---|---|---|---|---|---|
| `shop-boot3` | `-Xshare:off` | 7,674 ms (7,451–8,053) | 7,934 ms | 6.95 s (n = 5) | 12–16% |
| `shop-boot3` | default | 7,605 ms (7,299–7,667) | 7,889 ms | 6.90 s (n = 5) | 10–18% |
| `shop-heavy` | `-Xshare:off` | 30,337 ms (29,078–31,307) | 30,582 ms | 29.73 s (n = 5) | 1–26% |
| `shop-heavy` | default | 29,005 ms (28,665–29,325) | 29,230 ms | 28.48 s (n = 5) | 3–18% |

An earlier set (04:08 to 04:16, while other builds and a CPU burner ran, 5–61% before start) gave
10,150 / 8,157 ms for `shop-boot3` and 33,876 / 32,850 ms for `shop-heavy` (`-Xshare:off` /
default) and found the `Started … in` line in only 2 of 5 `shop-heavy` runs: under load the
difference between the two modes is dominated by the machine, not by CDS.

Repeat with the same command and code (set 4, 2026-10-24 11:12 to 11:31, idle machine, CPU before
start 0–20%, machine CPU sampled every 10 s: median 31%, 0–82%, the app under test included): the
same jar and class counts, and every ready median within 3% of set 3 in the baseline table (for example
`shop-boot3` 7,595 ms, `shop-heavy` 29,385 ms, `shop-war-jakarta` 44,437 / 44,050 ms; `shop-boot3`
with `SAMPLE_ROUTING_DS=true` 7,640 ms). The same set taken earlier that morning (10:00 to 10:24)
while another program used 2 to 4.5 of the 12 CPUs (machine CPU median 67%, 19–72% before start)
gave 1.4 to 1.7 times the ready times (`shop-boot3` 12,168 ms, `shop-heavy` 48,423 ms,
`shop-war-jakarta` 67,448 / 69,934 ms) and no `Started … in` line for `shop-heavy`.

## Adding or changing an app

- Apply `id("argos.sample-app")` and set `sampleApp { javaRelease; runJdk; catalog; bootJar(...) |
  war(...) | executableJar(...); dockerImage(); smoke { expectClients(...); variant(...) } }` (see
  `shop-boot3/build.gradle.kts`). A shop app declares the outgoing-call clients of its docs/18 §4.1
  stack with `expectClients`.
- Keep business logic in `common`; the app adds the web layer (controllers, servlet details) only.
- New dependencies: add them to `gradle/libs.versions.toml`, write the lock state
  (`./gradlew :sample-apps:<app>:resolveAndLockAll --write-locks --no-configuration-cache`),
  regenerate `gradle/verification-metadata.xml` with the command of
  [docs/dev-setup.md §7](../docs/dev-setup.md#7-dependency-changes) in a clean Linux container with
  an empty Gradle cache (never from a warm Windows cache; dependency verification is strict and the
  build fails without the new entries), and add a row to `docs/licenses/dev-only.md` when
  `checkDevOnlyLicenses` asks for one.
- `checkDevOnlyLicenses` sees `*RuntimeClasspath` configurations only. A sample configuration that
  is not one (for example `sampleTomcat*`, the Tomcat distribution zips of the WAR apps) is not
  checked: read the distribution's own LICENSE and add a row for it to `docs/licenses/dev-only.md`
  by hand (as `org.apache.tomcat:tomcat`).
- The versions pinned for the legacy stacks of docs/18 §4.1 (catalog keys `sample-boot2`,
  `sample-okhttp3`, `sample-tomcat9`, `sample-tomcat10`) do not take major-version update pull
  requests: they are the stack under test, not dependencies to keep current (close such a pull
  request; a Dependabot ignore rule for them is pending, WO-47).
