# shop-boot2

The legacy sample app of docs/18 §4.1: Spring Boot 2.7, Spring Framework 5.3, javax Servlet 4.0,
embedded Tomcat 9, JPA with Hibernate 5, HikariCP, RestTemplate, Apache HttpClient 4 and OkHttp 3.
It is compiled for Java 8 (`--release 8`) and runs on Java 8, 11, 17 and 21 (Java 25 is not
verified, docs/02 §9). Development and tests only; nothing here is shipped.

It implements the whole shop catalog (`common/.../catalog/ShopCatalog.java`) with a thin javax web
layer over `sample-apps/common`. `shop-war-javax` runs the same classes as a WAR in a standalone
Tomcat 9.

## Run

```sh
./gradlew :sample-apps:ext-stub:sampleAppJar :sample-apps:shop-boot2:sampleAppJar
java -jar sample-apps/ext-stub/build/sample-app/ext-stub.jar &        # Java 17+
java -jar sample-apps/shop-boot2/build/sample-app/shop-boot2.jar      # Java 8, 11, 17 or 21
curl http://127.0.0.1:8080/api/orders/1
curl http://127.0.0.1:8080/api/runtime
```

The switches of `sample-apps/README.md` apply (`SAMPLE_DB`, `SAMPLE_POOL`, `SAMPLE_ROUTING_DS`,
`SAMPLE_CUSTOM_EXECUTOR`, `SAMPLE_EXT_URL`, `-Dsample.fixed-clock`, ...), with one exception:

- `SAMPLE_VT` is **not applicable**. Virtual threads need Spring Boot 3.2+ on JDK 21+, and this app
  is a Boot 2.7 app built for Java 8. With `SAMPLE_VT=true` the app starts normally, logs a warning,
  and keeps platform threads; `GET /api/runtime` reports `virtualThreadsRequested: true`,
  `virtualThreadsSupported: false`, `virtualThreads: false`.

## Outgoing-call clients

`GET /api/pay/{id}?client=` accepts `hurl` (HttpURLConnection), `hc4` (Apache HttpClient 4.5),
`okhttp` (OkHttp 3.14) and `resttemplate` (RestTemplate on the HC4 request factory that Spring Boot
detects). The catalog entries of `hc5`, `jdk11` and `webclient` are skipped for this app (the smoke
report lists them as SKIPPED). The pooled clients (HC4, OkHttp, the RestTemplate's HC4 client) are
closed with the Spring context.

The Spring Boot 2.7 BOM manages OkHttp 4.9.3; `build.gradle.kts` pins OkHttp 3 with a strict
version so that the stack stays the one of docs/18 §4.1. `GET /api/runtime` shows the versions on
the class path under `libraries` (`StackInfoTest` checks them).

## Smoke variants

```sh
./gradlew :sample-apps:shop-boot2:sampleSmoke                 # every variant below
./gradlew :sample-apps:shop-boot2:sampleSmokeDefault -Psample.startupRuns=5
```

| Variant | JDK | Environment | Checks besides the catalog |
|---|---|---|---|
| `default` | 8 | – | `servletApi=javax`, `virtualThreads=false`, `javaFeatureVersion=8` |
| `routing-ds` | 8 | `SAMPLE_ROUTING_DS=true` | four-level data source chain |
| `pool-tomcat`, `pool-dbcp2` | 8 | `SAMPLE_POOL=tomcat` / `dbcp2` | pool |
| `seeds` | 8 | `SAMPLE_CUSTOM_EXECUTOR=true`, secret-looking variables, `-Dsample.fixed-clock=2026-01-15T00:00:00Z` | `seed-ARGOS_SEED-N` threads, `seeded`, `fixedClock` and `businessTime` |
| `jdk11`, `jdk17`, `jdk21` | 11, 17, 21 | – | the app on the other supported JDKs (`javaFeatureVersion`) |
| `vt-not-applicable` | 21 | `SAMPLE_VT=true` | `virtualThreadsSupported=false`, platform threads |

Reports: `build/reports/sample-smoke/<variant>/smoke.md` and `smoke.json` (jar and class counts,
loaded classes, startup time without an agent, machine CPU load before the start).

## Container image

```sh
./gradlew :sample-apps:shop-boot2:sampleImage                                     # Java 8 base image
./gradlew :sample-apps:shop-boot2:sampleImage -Psample.jdkImage=eclipse-temurin:21-jre@sha256:<digest>
```

The digests of the Temurin images are in `sample-apps/README.md`.
