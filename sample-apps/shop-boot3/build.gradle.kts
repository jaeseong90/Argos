// SPDX-License-Identifier: Apache-2.0
//
// shop-boot3 (18 §4.1): the reference app. Spring Boot 3.x, Framework 6.x, jakarta, Tomcat 10.1,
// JPA (Hibernate 6), HikariCP, RestTemplate, WebClient (Reactor Netty), Apache HC5, OkHttp 4, Java
// 11 HttpClient. Built for Java 17, runs on 17, 21 and 25. Implements the whole shop catalog
// (common: ShopCatalog) and every switch of 18 §4.4. shop-heavy and shop-war-jakarta reuse its
// classes (plain jar) as a library.

plugins { id("argos.sample-app") }

description = "Sample app: Spring Boot 3 reference app (jakarta, Tomcat 10.1, Hibernate 6)."

sampleApp {
  javaRelease = 17
  runJdk = 17
  catalog = "shop"
  bootJar("io.argos.sample.boot3.ShopBoot3Application", libs.sample.boot3.loader)
  dockerImage()
  smoke {
    // Clients of GET /api/pay/{id} (18 §4.1 stack): HttpURLConnection, RestTemplate, HC5, OkHttp 4,
    // Java 11 HttpClient, WebClient.
    expectClients("hurl", "resttemplate", "hc5", "okhttp", "jdk11", "webclient")
    // Tomcat stays the default with Jetty and Undertow on the class path (SAMPLE_CONTAINER, WO-50).
    variant("default") { expectRuntime("container", "tomcat") }
    // JDK 25 runs as the 02 §9 matrix does: sun.misc.Unsafe memory access denied.
    variant("jdk25") {
      runJdk = 25
      jvmArg("--sun-misc-unsafe-memory-access=deny")
      expectRuntime("javaFeatureVersion", "25")
    }
    // Virtual threads need JDK 21 (Boot 3.2+ spring.threads.virtual.enabled).
    variant("vt") {
      runJdk = 21
      env("SAMPLE_VT", "true")
      expectRuntime("virtualThreads", "true")
    }
    variant("routing-ds") {
      env("SAMPLE_ROUTING_DS", "true")
      expectRuntime("routingDataSource", "true")
      expectRuntime(
        "dataSourceChain",
        "SampleRoutingDataSource (AbstractRoutingDataSource) -> TransactionAwareDataSourceProxy" +
          " -> HikariDataSource -> org.h2.Driver",
      )
    }
    // SAMPLE_DRIVER_WRAPPER (ADR-0004 C-40): the delegating driver wrapper alone, and as the fifth
    // level under the routing chain (the D-24 nesting fixture of T-AG-031).
    variant("driver-wrapper") {
      env("SAMPLE_DRIVER_WRAPPER", "true")
      expectRuntime("driverWrapper", "true")
      expectRuntime("dataSourceChain", "HikariDataSource -> WrappingDriver (java.sql.Driver wrapper) -> org.h2.Driver")
    }
    variant("routing-wrapper") {
      env("SAMPLE_ROUTING_DS", "true")
      env("SAMPLE_DRIVER_WRAPPER", "true")
      env("SAMPLE_POOL", "dbcp2")
      expectRuntime("routingDataSource", "true")
      expectRuntime("driverWrapper", "true")
      expectRuntime(
        "dataSourceChain",
        "SampleRoutingDataSource (AbstractRoutingDataSource) -> TransactionAwareDataSourceProxy" +
          " -> BasicDataSource -> WrappingDriver (java.sql.Driver wrapper) -> org.h2.Driver",
      )
    }
    variant("pool-tomcat") {
      env("SAMPLE_POOL", "tomcat")
      expectRuntime("pool", "tomcat")
    }
    variant("pool-dbcp2") {
      env("SAMPLE_POOL", "dbcp2")
      expectRuntime("pool", "dbcp2")
    }
    // SAMPLE_SECOND_POOL (WO-13 pool registry fixture): a second, restarted pool next to the app's.
    variant("second-pool") {
      env("SAMPLE_SECOND_POOL", "true")
      expectRuntime("secondPool", "true")
    }
    // Thread-name seed (custom executor) and the secret-looking environment seeds (18 §4.3, §4.4).
    variant("seeds") {
      env("SAMPLE_CUSTOM_EXECUTOR", "true")
      seedEnvironment = true
      expectRuntime("customExecutor", "true")
      expectBody("async.executor", "seed-ARGOS_SEED-")
      expectBody("seed.xss.thread.0", "seed-ARGOS_SEED-")
      expectBody("seed.env", "seeded")
    }
    // SAMPLE_CONTAINER (WO-50, ADR-0010 C-127): the whole catalog on Jetty 12 (ee10) and Undertow
    // 2.3 instead of the default Tomcat 10.1; GET /api/runtime `container` is read from the server.
    variant("jetty") {
      env("SAMPLE_CONTAINER", "jetty")
      expectRuntime("container", "jetty")
    }
    variant("undertow") {
      env("SAMPLE_CONTAINER", "undertow")
      expectRuntime("container", "undertow")
    }
    // The integration layer runs the reference app on its build JDK plus the two switches named in
    // the WO-01 acceptance criteria (18 §2.1: one sample app in integrationTest).
    integrationTest("default", "vt", "routing-ds")
  }
}

dependencies {
  implementation(platform(libs.sample.boot3.bom))
  implementation(project(":sample-apps:common"))
  implementation(libs.sample.starter.web)
  implementation(libs.sample.starter.data.jpa)
  implementation(libs.sample.starter.websocket)
  implementation(libs.sample.starter.actuator)
  // WebClient on Reactor Netty; with Spring MVC present the app stays a servlet app.
  implementation(libs.sample.starter.webflux)
  implementation(libs.sample.hc5)
  implementation(libs.sample.okhttp4)
  // SAMPLE_POOL=tomcat|dbcp2 (HikariCP comes with the JPA starter).
  implementation(libs.sample.tomcat.jdbc)
  implementation(libs.sample.dbcp2)
  runtimeOnly(libs.sample.h2)
  runtimeOnly(libs.sample.postgresql)
  runtimeOnly(libs.sample.mysql)
}

// JDBC matrix drivers (WO-13, 18 §6.3: SAMPLE_DB=mariadb, mssql, oracle; the agent-testing jdbc
// suite runs them against containers). On this app's own runtime class path only (boot jar, smoke,
// agent tests): runtimeOnly would also reach shop-heavy and shop-war-jakarta, which build on this
// project. MariaDB Connector/J and ojdbc11 are dev-only rows (docs/licenses/dev-only.md).
val jdbcMatrixDrivers = configurations.dependencyScope("jdbcMatrixDrivers")

configurations.runtimeClasspath { extendsFrom(jdbcMatrixDrivers.get()) }

dependencies {
  jdbcMatrixDrivers(platform(libs.sample.boot3.bom))
  jdbcMatrixDrivers(libs.sample.mariadb)
  jdbcMatrixDrivers(libs.sample.mssql)
  jdbcMatrixDrivers(libs.sample.ojdbc11)
}

// Servlet containers of the compatibility matrix (WO-50, ADR-0010 C-127): SAMPLE_CONTAINER=jetty
// (Jetty 12 ee10) or undertow (Undertow 2.3) starts the app on that container through a
// ServletWebServerFactory bean (ServletContainers); unset or tomcat keeps Spring Boot's Tomcat.
// Like the JDBC matrix drivers: on this app's own runtime class path only (compileOnly for the
// selection code), never on shop-heavy's or shop-war-jakarta's.
val servletContainers = configurations.dependencyScope("servletContainers")

configurations.runtimeClasspath { extendsFrom(servletContainers.get()) }

configurations.compileOnly { extendsFrom(servletContainers.get()) }

dependencies {
  servletContainers(platform(libs.sample.boot3.bom))
  servletContainers(libs.sample.starter.jetty)
  servletContainers(libs.sample.starter.undertow)
}

// shop-boot3-devtools.jar (WO-50, coexistence row devtools of 18 §6.1): the app jar plus
// spring-boot-devtools (only that jar; Spring Boot is in the app already). The default
// shop-boot3.jar, the benchmark and E2E reference, stays without it.
val devtoolsLibraries = configurations.dependencyScope("devtoolsLibraries")

val devtoolsClasspath =
  configurations.resolvable("devtoolsClasspath") {
    extendsFrom(devtoolsLibraries.get())
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
  }

dependencies {
  devtoolsLibraries(platform(libs.sample.boot3.bom))
  devtoolsLibraries(libs.sample.devtools) { isTransitive = false }
}

sampleApp { bootJarVariant("devtools", devtoolsClasspath.get()) }
