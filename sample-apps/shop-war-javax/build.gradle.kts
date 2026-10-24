// SPDX-License-Identifier: Apache-2.0
//
// shop-war-javax (18 §4.1; 04 calls it war-javax): the shop-boot2 code as a WAR in a standalone
// Tomcat 9 (javax). Built for Java 8, runs on 8, 11, 17 and 21. Redeploy leak and context path
// prefix tests (F-A36) use it. The sources add only the SpringBootServletInitializer; the embedded
// Tomcat of the Boot starter is providedRuntime (not in WEB-INF/lib). The smoke deploys the WAR
// under /shop-war-javax (a non-root context path) in a CATALINA_BASE over build/tomcat/tomcat9.

import io.argos.buildlogic.sample.SmokeVariant

plugins {
  id("argos.sample-app")
  war
}

description = "Sample app: shop-boot2 as a WAR on a standalone Tomcat 9 (javax)."

sampleApp {
  javaRelease = 8
  runJdk = 8
  catalog = "shop"
  war()
  tomcat("tomcat9", libs.sample.tomcat9.dist)
  dockerImage()
  smoke {
    // Clients of GET /api/pay/{id}: shop-boot2's stack (HttpURLConnection, RestTemplate, HC4,
    // OkHttp 3).
    expectClients("hurl", "resttemplate", "hc4", "okhttp")
    // Every variant: the WAR's own name, the javax API, the standalone Tomcat 9 (not the embedded
    // one of the Boot starter), the non-root context path (F-A36) and the JDK that really runs it.
    fun SmokeVariant.onStandaloneTomcat9(jdk: Int) {
      tomcat = "tomcat9"
      runJdk = jdk
      expectRuntime("app", "shop-war-javax")
      expectRuntime("servletApi", "javax")
      expectRuntime("server", "Apache Tomcat/${libs.versions.sample.tomcat9.get()}")
      expectRuntime("contextPath", "/shop-war-javax")
      expectRuntime("javaFeatureVersion", jdk.toString())
    }
    // Run JDKs of 02 §9 (○: 8, 11, 17, 21); default = the build JDK.
    variant("default") { onStandaloneTomcat9(8) }
    for (jdk in listOf(11, 17, 21)) {
      variant("jdk$jdk") { onStandaloneTomcat9(jdk) }
    }
    // The four-level data source chain (D-24 fixture) inside the web app class loader.
    variant("routing-ds") {
      onStandaloneTomcat9(21)
      env("SAMPLE_ROUTING_DS", "true")
      expectRuntime("routingDataSource", "true")
      expectRuntime(
        "dataSourceChain",
        "SampleRoutingDataSource (AbstractRoutingDataSource) -> TransactionAwareDataSourceProxy" +
          " -> HikariDataSource -> org.h2.Driver",
      )
    }
    // Thread-name seed (custom executor) and the secret-looking environment seeds (18 §4.3, §4.4)
    // through the Tomcat process environment.
    variant("seeds") {
      onStandaloneTomcat9(8)
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
  implementation(platform(libs.sample.boot2.bom))
  implementation(project(":sample-apps:shop-boot2"))
  // SpringBootServletInitializer and the javax servlet types for compiling; at run time they come
  // from shop-boot2 (spring-boot in WEB-INF/lib) and Tomcat (servlet-api.jar). Not providedCompile:
  // that would also drop spring-boot from the WAR.
  compileOnly(libs.sample.starter.web)
  providedRuntime(platform(libs.sample.boot2.bom))
  providedRuntime(libs.sample.starter.tomcat)
  constraints {
    // shop-boot2's strict OkHttp 3 meets the Boot 2.7 BOM's OkHttp 4.9.3 here too; a strict
    // version from a transitive dependency does not override the BOM, the consumer's own does.
    implementation(libs.sample.okhttp3) { version { strictly(libs.versions.sample.okhttp3.get()) } }
  }
}
