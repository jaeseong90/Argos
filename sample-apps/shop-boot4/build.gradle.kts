// SPDX-License-Identifier: Apache-2.0
//
// shop-boot4 (18 §4.1): latest combination. Spring Boot 4.x, Framework 7.x, jakarta 6.1, Tomcat 11,
// JPA (Hibernate 7). Built with JDK 21 (18 §4.1) but for Java 17 (--release 17: Boot 4's
// baseline), so it runs on 17, 21 and 25 (02 §9). Implements the shop catalog (common:
// ShopCatalog); shop-boot3 is the reference for the web layer.
//
// Smoke variants: the 02 §9 row (JDK 17, 21, 25 x virtual threads off/on; virtual threads need
// JDK 21+, so JDK 17 only checks that the switch is ignored) plus the switches of 18 §4.4. Not part
// of check or integrationTest (shop-boot3 is the integration-layer app, 18 §2.1).

plugins { id("argos.sample-app") }

description = "Sample app: Spring Boot 4 latest app (jakarta 6.1, Tomcat 11, Hibernate 7)."

val bootVersion = libs.versions.sample.boot4.get()

sampleApp {
  javaRelease = 17
  runJdk = 21
  catalog = "shop"
  bootJar("io.argos.sample.boot4.ShopBoot4Application", libs.sample.boot4.loader)
  dockerImage()
  smoke {
    // Clients of GET /api/pay/{id}: the shop-boot3 set on Boot 4 (web layer of shop-boot3).
    expectClients("hurl", "resttemplate", "hc5", "okhttp", "jdk11", "webclient")
    variant("default") {
      expectRuntime("framework", "Spring Boot $bootVersion")
      expectRuntime("servletApi", "jakarta")
      expectRuntime("javaFeatureVersion", "21")
      expectRuntime("virtualThreads", "false")
    }
    variant("jdk17") {
      runJdk = 17
      expectRuntime("javaFeatureVersion", "17")
    }
    // JDK 25 runs as the 02 §9 matrix does: sun.misc.Unsafe memory access denied.
    variant("jdk25") {
      runJdk = 25
      jvmArg("--sun-misc-unsafe-memory-access=deny")
      expectRuntime("javaFeatureVersion", "25")
    }
    variant("vt") {
      env("SAMPLE_VT", "true")
      expectRuntime("virtualThreads", "true")
      expectRuntime("javaFeatureVersion", "21")
    }
    variant("vt-jdk25") {
      runJdk = 25
      jvmArg("--sun-misc-unsafe-memory-access=deny")
      env("SAMPLE_VT", "true")
      expectRuntime("virtualThreads", "true")
      expectRuntime("javaFeatureVersion", "25")
    }
    // JDK 17 has no virtual threads: Boot leaves the switch off and the app still serves everything.
    variant("vt-jdk17") {
      runJdk = 17
      env("SAMPLE_VT", "true")
      expectRuntime("virtualThreadsRequested", "true")
      expectRuntime("virtualThreads", "false")
      expectRuntime("javaFeatureVersion", "17")
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
  }
}

dependencies {
  implementation(platform(libs.sample.boot4.bom))
  implementation(project(":sample-apps:common"))
  implementation(libs.sample.starter.webmvc)
  implementation(libs.sample.starter.data.jpa)
  implementation(libs.sample.starter.websocket)
  implementation(libs.sample.starter.actuator)
  // Boot 4 splits the HTTP clients into their own starters.
  implementation(libs.sample.starter.webclient)
  implementation(libs.sample.starter.restclient)
  implementation(libs.sample.hc5)
  implementation(libs.sample.okhttp4)
  implementation(libs.sample.tomcat.jdbc)
  implementation(libs.sample.dbcp2)
  runtimeOnly(libs.sample.h2)
  runtimeOnly(libs.sample.postgresql)
  runtimeOnly(libs.sample.mysql)
}
