# shop-war-jakarta

The `shop-boot3` application packaged as a WAR and deployed to a standalone Apache Tomcat 10.1 or
11 (jakarta Servlet 6.0 / 6.1), docs/18 §4.1 (docs/04 calls it `war-jakarta`). It is compiled for
Java 17 and runs on Java 17, 21 and 25. Development and tests only; nothing here is shipped.

- The WAR contains `shop-boot3` (Spring Boot 3.5, Framework 6.2, Hibernate 6) and its libraries in
  `WEB-INF/lib`; the embedded Tomcat of the Spring Boot starter is left out (`providedRuntime`).
  The only class of its own is `ServletInitializer` (`SpringBootServletInitializer`).
- The same WAR is deployed **twice** in one Tomcat, as `/shop-a` and `/shop-b` (the multi web app
  case of docs/18 §4.1 and the context path prefix of the service name, F-A36). Every catalog path
  is prefixed: `GET /shop-a/api/orders/1`, `GET /shop-b/api/orders/1`.
- Each web application has its own class loader, Spring context, connection pool, executors and
  in-memory H2 database; nothing is shared between them except the Tomcat process.
- The Spring application name is `shop-war-jakarta/<context path>` (unless `SAMPLE_APP_NAME` is set),
  so the log lines of the two web applications on the one Tomcat console can be told apart.
  `GET /shop-a/api/runtime` reports `app: shop-war-jakarta`, `contextPath: /shop-a` and the
  standalone Tomcat version in `server`.

| Tomcat | Version | Servlet | Staged by |
|---|---|---|---|
| `tomcat10` | 10.1.61 | 6.0 | `sampleStageTomcatTomcat10` -> `build/tomcat/tomcat10` |
| `tomcat11` | 11.0.27 | 6.1 | `sampleStageTomcatTomcat11` -> `build/tomcat/tomcat11` |

## Run

Gradle stages the Tomcat distributions (the `CATALINA_HOME` directories):

```sh
./gradlew :sample-apps:shop-war-jakarta:war :sample-apps:shop-war-jakarta:sampleStageTomcatTomcat11
T=sample-apps/shop-war-jakarta/build/tomcat/tomcat11
cp sample-apps/shop-war-jakarta/build/sample-app/shop-war-jakarta.war $T/webapps/shop-a.war
cp sample-apps/shop-war-jakarta/build/sample-app/shop-war-jakarta.war $T/webapps/shop-b.war
$T/bin/catalina.sh run                         # catalina.bat run on Windows; Java 17, 21 or 25
curl http://127.0.0.1:8080/shop-a/api/orders/1
curl http://127.0.0.1:8080/shop-b/api/runtime
```

Start ext-stub first for the outgoing calls (see `sample-apps/README.md`); the switches of that
README apply to the Tomcat process environment and so to both web applications. Tomcat deploys the
two web applications one after the other and opens the HTTP connector after both have started.

`SAMPLE_VT` is not part of the WAR grid: the request threads belong to the standalone Tomcat's
connector (configured in `server.xml`), not to Spring Boot.

## Smoke variants

The smoke creates a fresh `CATALINA_BASE` per run (generated `server.xml` on 127.0.0.1 and a free
port, the WAR copied to `webapps/shop-a.war` and `webapps/shop-b.war`), starts Tomcat with the
variant's JDK, waits until `/shop-a/actuator/health` and `/shop-b/actuator/health` answer, and runs
the whole catalog under both context paths.

| Variant | Tomcat | JDK | Environment / JVM option |
|---|---|---|---|
| `tomcat10` | 10.1 | 17 | – |
| `tomcat10-jdk21`, `tomcat10-jdk25` | 10.1 | 21, 25 | JDK 25: `--sun-misc-unsafe-memory-access=deny` |
| `tomcat11` | 11 | 21 | – |
| `tomcat11-jdk17`, `tomcat11-jdk25` | 11 | 17, 25 | JDK 25: `--sun-misc-unsafe-memory-access=deny` |
| `seeds` | 10.1 | 17 | `SAMPLE_CUSTOM_EXECUTOR=true` + secret-looking variables |
| `routing-ds` | 11 | 21 | `SAMPLE_ROUTING_DS=true` (four-level data source chain) |
| `pool-tomcat` | 11 | 17 | `SAMPLE_POOL=tomcat` (the web app's own Tomcat JDBC pool) |

The first six are the docs/18 §6.1 rows T-MX-090~095. Every variant checks `app`, `servletApi`,
`server` and the Java version in `GET /api/runtime` of both web applications.

```sh
./gradlew :sample-apps:shop-war-jakarta:sampleSmoke
./gradlew :sample-apps:shop-war-jakarta:sampleSmokeTomcat11 -Psample.startupRuns=5
```

The "port" time of a WAR smoke is not a readiness time: Tomcat binds the port while it initializes,
before it deploys the web applications. Use the "ready" time (both health checks answer).

## Container image

```sh
./gradlew :sample-apps:shop-war-jakarta:sampleImage                # Tomcat 10.1 on the Java 17 base image
./gradlew :sample-apps:shop-war-jakarta:war :sample-apps:shop-war-jakarta:sampleStageTomcatTomcat11
docker build --build-arg TOMCAT=tomcat11 \
  --build-arg JDK_IMAGE=eclipse-temurin:21-jre@sha256:<digest> \
  -t argos-sample/shop-war-jakarta:tomcat11 sample-apps/shop-war-jakarta
docker run --rm --network argos-sample -p 127.0.0.1:8080:8080 argos-sample/shop-war-jakarta:dev
curl http://127.0.0.1:8080/shop-a/actuator/health http://127.0.0.1:8080/shop-b/actuator/health
```

The image runs as user 10001, deploys `shop-a.war` and `shop-b.war`, and reads `JAVA_OPTS` and
`CATALINA_OPTS` (for example `-javaagent:...`). The digests of the Temurin images are in
`sample-apps/README.md`.

## Known log line

The second web application logs one warning at startup:
`This FunctionCounter has been already registered (MeterId{name='tomcat.servlet.error', ...})`.
Micrometer's `TomcatMetrics` (bound by Spring Boot Actuator) queries the servlet MBeans
(`Catalina:j2eeType=Servlet,name=*,*`) of the JVM-wide MBean server, where both web applications
register servlets with the same names (`default`, ...), so the second web application finds a
servlet name twice. It does not affect the app.
