# shop-boot4

The latest-combination sample app of docs/18 §4.1: Spring Boot 4.x, Spring Framework 7.x, jakarta
Servlet 6.1, embedded Tomcat 11, JPA with Hibernate 7. It is built with the JDK 21 compiler for
Java 17 (`--release 17`, the Spring Boot 4 baseline) and runs on Java 17, 21 and 25 (docs/02 §9).
Development and tests only; nothing here is shipped.

It implements the whole shop catalog (`common/.../catalog/ShopCatalog.java`) with the same thin
web layer as `shop-boot3` over `sample-apps/common`.

| Component | Version (from the Spring Boot BOM, `gradle.lockfile`) |
|---|---|
| Spring Boot | 4.1.2 |
| Spring Framework | 7.0.10 |
| Tomcat (embedded) | 11.0.27 (Servlet 6.1) |
| Hibernate ORM | 7.4.13 (Jakarta Persistence 3.2) |
| Jackson | 3.1.7 (`tools.jackson`, the Boot 4 default) |
| HikariCP | 7.0.2 |
| Outgoing-call clients | HttpURLConnection, Apache HttpClient 5.6, OkHttp 4.12, `java.net.http`, WebClient (Reactor Netty 1.3), RestTemplate |

## What differs from shop-boot3

The controllers, entities and the wiring are those of `shop-boot3`; Spring Boot 4 needs only these
changes:

- Starters are split by technology: `spring-boot-starter-webmvc`, `spring-boot-starter-webclient`
  (WebClient) and `spring-boot-starter-restclient` (RestTemplate, RestClient).
- `RestTemplateBuilder` lives in `org.springframework.boot.restclient`; redirects are set with
  `HttpRedirects.FOLLOW`.
- `server.error.include-message` is now `spring.web.error.include-message`.
- Jackson 3 serializes the JSON answers (the `Map` bodies of the catalog look the same).

## Run

```sh
./gradlew :sample-apps:ext-stub:sampleAppJar :sample-apps:shop-boot4:sampleAppJar
java -jar sample-apps/ext-stub/build/sample-app/ext-stub.jar &        # Java 17+
java -jar sample-apps/shop-boot4/build/sample-app/shop-boot4.jar      # Java 17, 21 or 25
curl http://127.0.0.1:8080/api/orders/1
curl http://127.0.0.1:8080/api/runtime
```

On Java 25 the compatibility matrix adds `--sun-misc-unsafe-memory-access=deny` (docs/02 §9); the
app runs with it. The switches of `sample-apps/README.md` apply (`SAMPLE_DB`, `SAMPLE_POOL`,
`SAMPLE_ROUTING_DS`, `SAMPLE_VT`, `SAMPLE_CUSTOM_EXECUTOR`, `SAMPLE_EXT_URL`,
`-Dsample.fixed-clock`, ...).

`SAMPLE_VT=true` turns on `spring.threads.virtual.enabled`: on Java 21 and 25 Tomcat's request
threads, `@Async` and MVC async run on virtual threads and `GET /api/runtime` reports
`virtualThreads: true`. Java 17 has no virtual threads; Spring Boot ignores the switch there, the
app serves everything on platform threads and reports `virtualThreadsRequested: true`,
`virtualThreads: false`.

## Smoke variants

```sh
./gradlew :sample-apps:shop-boot4:sampleSmoke                 # every variant below
./gradlew :sample-apps:shop-boot4:sampleSmokeVt               # one variant
./gradlew :sample-apps:shop-boot4:sampleSmokeDefault -Psample.startupRuns=5
```

| Variant | JDK | Environment / JVM option | Checks besides the catalog |
|---|---|---|---|
| `default` | 21 | – | `framework=Spring Boot 4.1.2`, `servletApi=jakarta`, `virtualThreads=false` |
| `jdk17` | 17 | – | Java 17 |
| `jdk25` | 25 | `--sun-misc-unsafe-memory-access=deny` | Java 25 |
| `vt` | 21 | `SAMPLE_VT=true` | `virtualThreads=true` (request thread is virtual) |
| `vt-jdk25` | 25 | `SAMPLE_VT=true`, deny flag | `virtualThreads=true` |
| `vt-jdk17` | 17 | `SAMPLE_VT=true` | `virtualThreadsRequested=true`, `virtualThreads=false` |
| `routing-ds` | 21 | `SAMPLE_ROUTING_DS=true` | four-level data source chain |
| `pool-tomcat`, `pool-dbcp2` | 21 | `SAMPLE_POOL=tomcat` / `dbcp2` | pool |
| `seeds` | 21 | `SAMPLE_CUSTOM_EXECUTOR=true` + secret-looking variables | `seed-ARGOS_SEED-N` threads, `seeded` |

The first six are the docs/18 §6.1 rows T-MX-040~051 (JDK 17, 21, 25 x virtual threads off/on).
The `pay.hc4*` catalog entries are skipped (Apache HttpClient 4 is not part of this stack).

## Container image

```sh
./gradlew :sample-apps:shop-boot4:sampleImage                                     # Java 21 base image
./gradlew :sample-apps:shop-boot4:sampleImage -Psample.jdkImage=eclipse-temurin:25-jre@sha256:<digest>
docker run --rm --network argos-sample -p 127.0.0.1:8080:8080 argos-sample/shop-boot4:dev
```

The image runs as user 10001 and expects ext-stub at `http://ext-stub:18080` (see
`sample-apps/README.md` for the network, the digests and `JAVA_OPTS`).
