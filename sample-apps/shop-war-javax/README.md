# shop-war-javax

The `shop-boot2` application packaged as a WAR and deployed to a standalone Apache Tomcat 9 (javax
Servlet 4.0), docs/18 §4.1 (docs/04 calls it `war-javax`). It is compiled for Java 8 and runs on
Java 8, 11, 17 and 21. Development and tests only; nothing here is shipped.

- The WAR contains `shop-boot2` and its libraries in `WEB-INF/lib`; the embedded Tomcat of the
  Spring Boot starter is left out (`providedRuntime`). The only class of its own is
  `ServletInitializer` (`SpringBootServletInitializer`).
- It is deployed under the non-root context path `/shop-war-javax`, so every catalog path is
  prefixed: `GET /shop-war-javax/api/orders/1`. The context path prefix of the service name (F-A36)
  and the redeploy leak tests (docs/18 §6.1) use this app.
- `GET /shop-war-javax/api/runtime` reports `app: shop-war-javax`, `contextPath: /shop-war-javax`
  and the standalone Tomcat version in `server`.
- On undeploy the Spring context closes the app's executors, HTTP clients and pool and shuts the
  in-memory H2 database down (H2 would otherwise keep a JVM shutdown hook that holds the web app
  class loader). Spring Boot deregisters the JDBC drivers. See "Undeploy baseline" for what the
  redeploy leak tests (docs/18 §5.9) must take into account.

## Run

Gradle stages the Tomcat distribution (`build/tomcat/tomcat9`, the `CATALINA_HOME`):

```sh
./gradlew :sample-apps:shop-war-javax:war :sample-apps:shop-war-javax:sampleStageTomcatTomcat9
cp sample-apps/shop-war-javax/build/sample-app/shop-war-javax.war \
   sample-apps/shop-war-javax/build/tomcat/tomcat9/webapps/
sample-apps/shop-war-javax/build/tomcat/tomcat9/bin/catalina.sh run    # catalina.bat run on Windows
curl http://127.0.0.1:8080/shop-war-javax/api/orders/1
```

Start ext-stub first for the outgoing calls (see `sample-apps/README.md`); the switches of that
README apply to the Tomcat process environment. `SAMPLE_VT` does not apply (see
`shop-boot2/README.md`).

## Smoke variants

The smoke creates a fresh `CATALINA_BASE` per run (generated `server.xml` on 127.0.0.1 and a free
port, the WAR copied to `webapps/shop-war-javax.war`) and starts Tomcat with the variant's JDK.
Every variant checks `app=shop-war-javax`, `servletApi=javax`, `server=Apache Tomcat/9.0.x` (the
standalone Tomcat, not the embedded one), `contextPath=/shop-war-javax` and `javaFeatureVersion`.

| Variant | JDK | Environment |
|---|---|---|
| `default` | 8 | – |
| `jdk11`, `jdk17`, `jdk21` | 11, 17, 21 | – |
| `routing-ds` | 21 | `SAMPLE_ROUTING_DS=true` (four-level data source chain) |
| `seeds` | 8 | `SAMPLE_CUSTOM_EXECUTOR=true` and the secret-looking variables |

```sh
./gradlew :sample-apps:shop-war-javax:sampleSmoke
```

## Undeploy baseline (without an agent)

Measured on Tomcat 9.0.123 with JDK 8 and JDK 21: deploy, call the catalog's database, outgoing-call,
async, SSE, seed and pool endpoints, remove the WAR, then `jcmd <pid> GC.run` twice and count
`ParallelWebappClassLoader` in `jcmd <pid> GC.class_histogram`. Tomcat logs no ThreadLocal or JDBC
driver leak warnings. The web app class loader is collected (count 0) when these conditions hold:

- **Soft references are cleared.** JDK caches keep the class loader softly reachable, and
  `GC.run` keeps recently used soft references. Start Tomcat with `-XX:SoftRefLRUPolicyMSPerMB=0`.
- **OkHttp 3 and Okio threads have ended.** Tomcat warns at undeploy that the web app started
  `OkHttp ConnectionPool` and `Okio Watchdog` and did not stop them. These are the libraries'
  static daemon threads. The app ends the pool's cleanup task on close (`OkHttp3Client.close()`;
  without that, the thread waited for the 5-minute keep-alive). The idle threads end by themselves,
  observed within 130 seconds after the undeploy. Wait at least that long before the GC check.
- **The app has not grown the JVM-wide `ForkJoinPool.commonPool()`.** `GET /api/async/executor`
  (common `ExecutorProbe`: parallel stream, and `supplyAsync` without `SAMPLE_CUSTOM_EXECUTOR`)
  runs on the common pool. A common-pool worker created during that request inherits the request's
  `AccessControlContext`, which contains the web app's `ProtectionDomain` and therefore its class
  loader. Observed with Tomcat's factory on JDK 8 and with the JDK's default factory on JDK 21
  (no security manager). On JDK 8 such workers live for the rest of the JVM; on JDK 21 they were
  still alive 130 seconds later. The app cannot prevent this, because any web app frame on the stack
  adds its domain. A leak test must either leave that entry out of the redeploy traffic or start
  Tomcat with a common-pool thread factory from `$CATALINA_HOME/lib` (system property
  `java.util.concurrent.ForkJoinPool.common.threadFactory`) that creates workers inside its own
  `AccessController.doPrivileged` block.

## Container image

```sh
./gradlew :sample-apps:shop-war-javax:sampleImage           # Tomcat 9 on the Java 8 base image
docker run --rm -p 127.0.0.1:8080:8080 argos-sample/shop-war-javax:dev
curl http://127.0.0.1:8080/shop-war-javax/actuator/health
```

`-Psample.jdkImage=<image@digest>` selects another JDK (digests in `sample-apps/README.md`).
