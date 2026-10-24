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
    variant("default")
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
    variant("pool-tomcat") {
      env("SAMPLE_POOL", "tomcat")
      expectRuntime("pool", "tomcat")
    }
    variant("pool-dbcp2") {
      env("SAMPLE_POOL", "dbcp2")
      expectRuntime("pool", "dbcp2")
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
