# shop-heavy

`shop-boot3` with a large enterprise dependency set that is really used at startup: the startup
cost baseline of N-04 (startup delay, heavy app +10%) and N-27 (class transformation cost) in
docs/18 §4.1. It serves the whole shop catalog (`ShopCatalog`, shop-boot3's web layer unchanged)
plus endpoints of its own for every heavy library. Java 17; runs on 17, 21 and 25.

## Size: jars and classes

| What | Value | Where it comes from |
|---|---|---|
| Dependency jars (`BOOT-INF/lib`) | 267 | `smoke.json` `artifact.dependencyJars` |
| Classes in the packaged app | 76,977 (29 of the app itself) | `smoke.json` `artifact.totalClasses` |
| Classes loaded when the app has started, JDK 17 / 21 / 25 (H2) | 31,932 / 32,109 / 31,933 | `GET /api/runtime` `startupLoadedClasses` |
| The same on PostgreSQL 16 (JDK 17) | 31,645 | same |
| The same without the startup steps (`SAMPLE_HEAVY_WARMUP=false`, JDK 17) | 25,085 | same |

The size target is part of `check`: `verifyHeavyFootprint` fails when the runtime class path, which
is what goes into `BOOT-INF/lib`, has fewer than 200 jars or fewer than 30,000 classes in those jars
(counted like `smoke.json` `artifact.dependencyClasses`; the measured numbers go to
`build/reports/heavy-footprint/footprint.properties`).

`startupLoadedClasses` is the JVM's loaded class count (`ClassLoadingMXBean`) taken at Spring
Boot's `ApplicationStartedEvent`, the moment of the `Started ShopHeavyApplication in ...` log line,
before any request. The smoke fails when it is below 30,000 (`startupClassTargetMet`), and on
every JDK variant (build file: `expectRuntime("startupClassTargetMet", "true")`).

## How the classes load at startup

Most heavy libraries load lazily: a jar on the class path costs nothing until it is used. To be a
startup baseline, shop-heavy uses its libraries the way a large app does while it starts, before
the port is bound:

1. **Bean creation** (Spring context refresh): Spring Cloud (OpenFeign client, LoadBalancer with
   static discovery, Resilience4j circuit breaker and rate limiter, Gateway Server MVC route,
   Function catalog), Spring Data JPA repository, Hibernate with Envers and the JCache
   second-level cache (Caffeine), Bean Validation, Thymeleaf, HATEOAS, Spring Cache (Caffeine),
   AOP aspects and Micrometer observations, Jakarta Mail sender, AWS SDK S3 clients (sync on
   Apache HttpClient 5, async on Netty, presigner), Bouncy Castle provider, Prometheus registry.
2. **Startup steps** (`startup.StartupReport`): a `SmartLifecycle` in phase `DEFAULT_PHASE - 3072`,
   after every singleton and every `SmartInitializingSingleton` (the cache, AOP and meter
   infrastructure is complete) and before Spring Boot's web server lifecycle (`DEFAULT_PHASE -
   2048`) binds the port. Each step does real, offline work with its libraries and checks the
   result; a failing step stops the app.

| Step | Libraries | Work | Classes loaded (JDK 17) |
|---|---|---|---|
| `jackson-formats` | Jackson XML, YAML, CSV, CBOR, Smile; Guava, Joda, JSR-310, Hibernate 6, Blackbird, Kotlin modules | one row through every format and back | ~430 |
| `jpa-envers-jcache` | Hibernate ORM, Envers, JCache, Spring Data JPA, Hibernate Validator | repository queries (derived, count, example + specification page), audit query, cache lookup, validation | ~1,000 |
| `querydsl` | QueryDSL JPA | count + page | ~120 |
| `spring-cloud` | Discovery, LoadBalancer child context, Resilience4j, Function | resolve `ext-stub`, create its load balancer, guarded local call, invoke `uppercase` | ~150 |
| `aws-s3` | AWS SDK 2 | presigned GET URL (SigV4), object URL | ~380 |
| `thymeleaf` | Thymeleaf | parse and render `templates/heavy/order.html` | ~480 |
| `poi` | Apache POI XSSF + HSSF, XMLBeans | .xlsx and .xls with an evaluated SUM formula, written and read back | ~2,600 |
| `poi-word` | POI XWPF, Commons Text, Guava | .docx written and read back | ~250 |
| `mail-crypto` | Jakarta Mail, Bouncy Castle | MIME message with attachment; SHA3-256, ECDSA P-256 signature, AES-256-GCM; parsed back | ~510 |
| `hateoas` | Spring HATEOAS | links from controller methods | ~200 |
| `springdoc` | springdoc-openapi, swagger-core | OpenAPI description of every controller (cached for `/v3/api-docs`), JSON and YAML | ~1,060 |
| `micrometer-prometheus` | Micrometer Prometheus | one scrape | ~40 |
| `spring-cache` | Spring Cache, Caffeine | one product summary computed and cached | ~30 |

The steps take about 6-7 s of the startup. `GET /api/heavy/startup` (and `GET /api/runtime`) show
the per-step class counts and times of the running process. `SAMPLE_HEAVY_WARMUP=false` skips the
steps (smoke variant `no-warmup`): the libraries are then initialized on first use only, which shows
what the steps add.

On the class path only (not exercised): MapStruct (no annotation processor in the build), Commons
Collections 4, the rest of the AWS SDK service model and of the POI schemas.

## Endpoints of its own

| Endpoint | Libraries |
|---|---|
| `GET /api/heavy/startup` | startup steps, class counts, service call statistics (AOP) |
| `GET /api/heavy/inventory/{productId}` | OpenFeign → LoadBalancer → ext-stub, Resilience4j circuit breaker (fallback: 502/503/504 as `/api/pay`) |
| `GET /api/heavy/storage/{key}`, `GET /api/heavy/storage-async/{key}` | AWS SDK S3 GetObject against ext-stub (sync / async) |
| `GET /api/heavy/orders/search?status=&page=` | QueryDSL JPA |
| `GET /api/heavy/orders/{id}/export?format=json\|xml\|yaml\|csv\|cbor\|smile` | Jackson data formats |
| `GET /api/heavy/orders/report.xlsx?page=`, `.../report.xls?page=` | POI XSSF / HSSF, rate limited (Resilience4j), observed (Micrometer) |
| `GET /api/heavy/orders/{id}/report.docx` | POI XWPF |
| `GET /api/heavy/orders/{id}/mail` | Jakarta Mail + Bouncy Castle (composed, never sent) |
| `GET /api/heavy/orders/{id}/model` | Spring HATEOAS (HAL) |
| `GET /api/heavy/products/{id}/summary` | Spring Cache on Caffeine |
| `GET /api/heavy/products/{id}/reviews`, `GET /api/heavy/reviews?q=&minRating=&page=` | Spring Data JPA (derived query, example + specification) |
| `POST /api/heavy/reviews`, `PUT /api/heavy/reviews/{id}`, `GET /api/heavy/reviews/{id}`, `GET /api/heavy/reviews/{id}/revisions` | Bean Validation, JPA, Envers, second-level cache |
| `GET /heavy/orders/{id}` | Thymeleaf page |
| `POST /fn/uppercase`, `GET /fn/orderCount` | Spring Cloud Function web |
| `GET /gw/ext/**` | Spring Cloud Gateway Server MVC → ext-stub |
| `GET /v3/api-docs`, `/swagger-ui.html`, `/actuator/prometheus` | springdoc, Micrometer |

The smoke runs the shop catalog only; these endpoints have no catalog entries yet.

## Network and data

- Every outgoing call goes to ext-stub (`SAMPLE_EXT_URL`): Feign through the static discovery
  entry `ext-stub`, the gateway route, the S3 clients (endpoint override, path-style, fixed region,
  fake credentials `argos-sample`, `aws.disableEc2Metadata=true`). Mail is composed, never sent.
  Spring Cloud's host lookup ignores the network interfaces (`spring.cloud.inetutils.ignored-interfaces`),
  so no reverse DNS query leaves the machine at startup. A packet capture of the container's
  startup (no ext-stub, nothing published) showed no IP traffic from the app.
- Hibernate creates shop-heavy's own tables only (`product_reviews`, `product_reviews_aud`,
  `revinfo`; `ddl-auto=update` limited by `HeavySchemaFilterProvider`). The shop tables of common
  are never altered.

## Switches

All switches of `sample-apps/README.md` (`SAMPLE_DB`, `SAMPLE_POOL`, `SAMPLE_ROUTING_DS`, `SAMPLE_VT`,
...) plus `SAMPLE_HEAVY_WARMUP` (default `true`).

## Smoke variants

| Variant | JDK | Checks |
|---|---|---|
| `default` | 17 | shop catalog, `heavyWarmup=ok`, `startupClassTargetMet=true` |
| `jdk21`, `jdk25` | 21, 25 | the same (`jdk25` with `--sun-misc-unsafe-memory-access=deny`, as the 02 §9 matrix runs JDK 25) |
| `no-warmup` | 17 | shop catalog, `heavyWarmup=off` |

```sh
./gradlew :sample-apps:shop-heavy:sampleSmoke
./gradlew :sample-apps:shop-heavy:sampleSmokeDefault -Psample.startupRuns=5   # startup median
./gradlew :sample-apps:shop-heavy:sampleImage                                 # argos-sample/shop-heavy:dev
```
