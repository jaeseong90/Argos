// SPDX-License-Identifier: Apache-2.0
//
// shop-boot2 (18 §4.1): legacy combination. Spring Boot 2.7, Framework 5.3, javax Servlet 4.0,
// Tomcat 9, JPA (Hibernate 5), HikariCP, RestTemplate, Apache HC4, OkHttp 3. Built for Java 8
// (--release 8), runs on 8, 11, 17 and 21 (25: not verified, 02 §9). Implements the shop catalog
// (common: ShopCatalog) with the javax web layer; shop-war-javax reuses its classes.
// SAMPLE_VT does not apply (virtual threads need Boot 3.2+ on JDK 21+, 18 §4.4): the app logs a
// warning and GET /api/runtime reports virtualThreadsSupported=false (variant vt-not-applicable).

import io.argos.buildlogic.sample.BootLauncher

plugins {
  id("argos.sample-app")
  // Tests over 1 s (@Tag("slow")) live in src/integrationTest (18 §2.2).
  id("argos.integration-test")
}

description = "Sample app: Spring Boot 2.7 legacy app (Java 8, javax, Tomcat 9, Hibernate 5)."

/** Business instant of the fixed-clock check (18 §4.4 example value). */
val fixedClock = "2026-01-15T00:00:00Z"

sampleApp {
  javaRelease = 8
  runJdk = 8
  catalog = "shop"
  // Boot 2.x jars start with the classic loader entry point.
  bootJar("io.argos.sample.boot2.ShopBoot2Application", libs.sample.boot2.loader, BootLauncher.CLASSIC)
  dockerImage()
  smoke {
    // Clients of GET /api/pay/{id} (18 §4.1 stack): HttpURLConnection, RestTemplate, HC4, OkHttp 3.
    expectClients("hurl", "resttemplate", "hc4", "okhttp")
    variant("default") {
      expectRuntime("servletApi", "javax")
      expectRuntime("virtualThreads", "false")
      expectRuntime("javaFeatureVersion", "8")
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
    // Test data: thread-name seed (custom executor), the secret-looking environment seeds (18 §4.3,
    // §4.4) and the fixed business clock of E2E data (-Dsample.fixed-clock, 18 §4.4).
    variant("seeds") {
      env("SAMPLE_CUSTOM_EXECUTOR", "true")
      seedEnvironment = true
      jvmArg("-Dsample.fixed-clock=$fixedClock")
      expectRuntime("customExecutor", "true")
      expectRuntime("fixedClock", fixedClock)
      expectRuntime("businessTime", fixedClock)
      expectBody("async.executor", "seed-ARGOS_SEED-")
      expectBody("seed.xss.thread.0", "seed-ARGOS_SEED-")
      expectBody("seed.env", "seeded")
    }
    // Run JDKs of 02 §9 (○: 8, 11, 17, 21) besides the build JDK.
    for (jdk in listOf(11, 17, 21)) {
      variant("jdk$jdk") {
        runJdk = jdk
        expectRuntime("javaFeatureVersion", jdk.toString())
      }
    }
    // SAMPLE_VT=true on a JDK that has virtual threads: still platform threads, stated at runtime.
    variant("vt-not-applicable") {
      runJdk = 21
      env("SAMPLE_VT", "true")
      expectRuntime("virtualThreadsRequested", "true")
      expectRuntime("virtualThreadsSupported", "false")
      expectRuntime("virtualThreads", "false")
      expectRuntime("javaFeatureVersion", "21")
    }
  }
}

dependencies {
  implementation(platform(libs.sample.boot2.bom))
  implementation(project(":sample-apps:common"))
  implementation(libs.sample.starter.web)
  implementation(libs.sample.starter.data.jpa)
  implementation(libs.sample.starter.websocket)
  implementation(libs.sample.starter.actuator)
  implementation(libs.sample.hc4)
  // OkHttp 3 (18 §4.1). The Boot 2.7 BOM manages OkHttp 4.9.3, which would win the version
  // conflict; strictly keeps 3.x here and for shop-war-javax (StackInfoTest checks it).
  implementation(libs.sample.okhttp3) { version { strictly(libs.versions.sample.okhttp3.get()) } }
  implementation(libs.sample.tomcat.jdbc)
  implementation(libs.sample.dbcp2)
  runtimeOnly(libs.sample.h2)
  runtimeOnly(libs.sample.postgresql)
  runtimeOnly(libs.sample.mysql)
}

// The slow tests (src/integrationTest) use the app's own stack directly, as the unit tests do.
configurations.named("integrationTestImplementation") {
  extendsFrom(configurations.testImplementation.get())
}

configurations.named("integrationTestRuntimeOnly") {
  extendsFrom(configurations.testRuntimeOnly.get())
}
