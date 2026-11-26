# sample-apps/demo-app

The demo app of the demo profile (docs/17 §3.2, §3.9, WO-41), published as the image
`argos-demo-app` with the Argos agent attached. One app, two roles: **order-api**
(`DEMO_ROLE=order`) receives the demo load and calls **payment-api** (`DEMO_ROLE=payment`) at
checkout, so the demo shows a distributed trace. Both roles run a `@Scheduled` settlement batch.

It is a dedicated build on allow-listed licenses only (ADR-0004 C-43, ADR-0010 C-186 (1)); the
development reference app `shop-boot3` is not shipped:

| | demo-app (shipped) | shop-boot3 (development only) |
|---|---|---|
| Web | Spring Boot 4.1 web MVC, embedded Tomcat 11 (the `sample-boot4` BOM of `shop-boot4`) | Spring Boot 3.5 web, embedded Tomcat 10.1, WebFlux, WebSocket, actuator |
| Data | Spring JDBC, HikariCP, PostgreSQL driver | JPA (Hibernate), H2, MySQL, MariaDB, SQL Server, Oracle drivers |
| Logging | Log4j 2 | Logback |
| `jakarta.annotation` | `tomcat-annotations-api` (Apache-2.0) | `jakarta.annotation-api` (EPL/GPL) |
| License check | `checkLicenses` (allow list) and `checkDemoAppDependencies` | `checkDevOnlyLicenses` |

## Endpoints

The demo catalog (`sample-apps/common`, `DemoCatalog`; the load generator's `MIX=demo` uses the same
routes and statuses):

| Request | Role | Status | SQL | Purpose |
|---|---|---|---|---|
| `GET /healthz` | both | 200 (503 before the data is ready) | 0 | container health check; also reports `batchRuns` |
| `GET /api/orders/{id}` | both | 200 | 1 | normal |
| `GET /api/orders?page=` | both | 200 | 3 | normal |
| `GET /api/orders/{id}/items` | both | 200 | 21 | N+1 (1 + 20) |
| `GET /api/slow?ms=` | both | 200 (400 above 10 s) | 0 | slow (the load uses 300 ms to 3 s) |
| `GET /api/error-500` | both | 500 | 0 | unhandled `IllegalStateException` |
| `GET /api/send-error` | both | 503 | 0 | `response.sendError(503)` |
| `GET /api/orders/{id}/checkout` | order | 200, or 502 when payment-api fails or is unreachable | 1 | distributed call to `PEER_URL/api/payments/{id}` (JDK `HttpClient`, 5 s timeout) |
| `GET /api/payments/{id}` | payment | 200, or 500 for every 5th order id (about 20%) | 2 (1 when declined) | payment; a declined one throws an unhandled exception |

Settlement batch (`@Scheduled`, an Argos BATCH transaction): first run about 30 s after the start,
then every 2 minutes; one database transaction of 27 SQL (count, a moving window of 25 orders, one
update per order). A run before the data is ready is skipped.

Tomcat's per-request stack trace of the intended exceptions is switched off in the container log
(`logging.level.org.apache.catalina.core.ContainerBase=off`): Argos records them, and several per
second under the demo load would only fill the disk.

## Settings

Environment variables (Spring relaxed binding):

| Variable | Default | |
|---|---|---|
| `DEMO_ROLE` | `order` | `order` or `payment` |
| `PEER_URL` | `http://sample-app-b:8080` | payment-api base URL (order role only) |
| `DEMO_DB_URL` | `jdbc:postgresql://demo-db:5432/demo` | PostgreSQL only; each role uses its own schema (`demo_order`, `demo_payment`) |
| `DEMO_DB_USER` | `demo` | |
| `DEMO_DB_PASSWORD_FILE` | required | file with the password (one trailing line break is ignored, at most 4 KiB). `DEMO_DB_PASSWORD` is refused: the secret never sits in the environment, `docker inspect` or an image layer |
| `DEMO_DB_WAIT` | `120s` | how long the app retries the database at start (every 2 s plus the 5 s connection timeout); then it exits |
| `DEMO_BATCH_PERIOD` | `120s` | settlement batch period (`30s`, `2m`, `PT2M`; a plain number means milliseconds) |
| `DEMO_BATCH_INITIAL_DELAY` | `30s` | first batch run after the start |

The app creates its schema, tables and data (1,000 orders with 20 items each, deterministic)
idempotently at every start. The agent is configured by its own `ARGOS_*` variables
(docs/agent-config.md), set by the demo Compose file.

## Image

```bash
./gradlew :sample-apps:demo-app:jibDockerBuild    # argos-demo-app:<version> in the local Docker daemon (linux/amd64)
```

Built by Jib through the `argos.demo-image` convention (`build-logic`, `DemoImage`): the server
image's digest-pinned Temurin 21 JRE base, user `10001:10001`, OCI labels (`licenses` Apache-2.0,
version, revision, source, base image), classes and libraries in `/opt/argos-demo/app`, `LICENSE` and
`NOTICE` in `/opt/argos-demo`, the agent jar of `:agent:agent-dist:agentJar` at
`/opt/argos-agent/argos-agent.jar`. Entrypoint flags: `-javaagent:/opt/argos-agent/argos-agent.jar
-Xms64m -Xmx256m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError`, UTF-8, UTC. Environment:
`HOME=/tmp`, `ARGOS_LOG_DIR=/tmp/argos-agent` (the container runs with a read-only root and a tmpfs
on `/tmp`). The image holds no secret and no server setting. Multi-arch images, registry push and
signing are WO-44.

Run it by hand (the demo Compose profile does this for you, `deploy/compose`):

```bash
docker network create demo-net
docker run -d --name demo-db --network demo-net -e POSTGRES_USER=demo -e POSTGRES_DB=demo \
  -e POSTGRES_PASSWORD_FILE=/run/secrets/pw -v "$PWD/secrets:/run/secrets:ro" <postgres image of deploy/postgres/image.properties>
docker run -d --name order-api --network demo-net -p 127.0.0.1:8081:8080 \
  -e DEMO_ROLE=order -e PEER_URL=http://payment-api:8080 -e DEMO_DB_PASSWORD_FILE=/run/secrets/pw \
  -e ARGOS_APP_NAME=order-api -e ARGOS_APP_ENV=demo -e ARGOS_SERVER_ADDRESS=<collector host:9100> \
  -e ARGOS_SERVER_TOKEN_FILE=/run/secrets/<token file> -v "$PWD/secrets:/run/secrets:ro" argos-demo-app:0.0.0-SNAPSHOT
```

## Tests

- `./gradlew :sample-apps:demo-app:test`: settings (roles, defaults, password file only, durations)
  and the database wait.
- `./gradlew :sample-apps:demo-app:integrationTest` (Docker, about 1 minute): both roles started
  together on one real PostgreSQL (Testcontainers, the image of `deploy/postgres/image.properties`)
  with a test-only SQL counter around the DataSource: every `DemoCatalog` entry with its status,
  checks and SQL count (N+1 = 21), the checkout reaching payment-api (2 or 1 payment SQL), 502 when
  payment-api is unreachable, the batch with a 2 s period (27 SQL per run), health 503 while the
  database is unreachable and the start failing after `DEMO_DB_WAIT`, `DEMO_DB_PASSWORD` refused
  (T-NFR-16e, T-SEC-74).
- `check` also runs `checkLicenses` (T-BLD-007) and `checkDemoAppDependencies`: the runtime
  classpath holds none of the shop apps, Hibernate, Logback, H2, MySQL, MariaDB, Oracle,
  `jakarta.annotation-api` or AspectJ.
