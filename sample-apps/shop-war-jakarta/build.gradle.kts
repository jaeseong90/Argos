// SPDX-License-Identifier: Apache-2.0
//
// shop-war-jakarta (18 §4.1; 04 calls it war-jakarta): the shop-boot3 code as a WAR in a standalone
// Tomcat 10.1 or 11 (jakarta), deployed twice (two web applications, /shop-a and /shop-b: context
// path prefix tests, F-A36). Built for Java 17, runs on 17, 21 and 25. The sources add only the
// SpringBootServletInitializer; the embedded Tomcat of the Boot starter is providedRuntime.
//
// Smoke variants: the 02 §9 / 18 §6.1 T-MX-090~095 grid, Tomcat 10.1 and 11 x JDK 17, 21 and 25;
// every variant runs the whole catalog under both context paths. "tomcat10" (JDK 17) and
// "tomcat11" (JDK 21) are the 18 §4.1 defaults. Not part of check or integrationTest.

plugins {
  id("argos.sample-app")
  war
}

description = "Sample app: shop-boot3 as a WAR on standalone Tomcat 10.1/11, two web apps."

val tomcat10Server = "Apache Tomcat/${libs.versions.sample.tomcat10.get()}"
val tomcat11Server = "Apache Tomcat/${libs.versions.sample.tomcat11.get()}"

sampleApp {
  javaRelease = 17
  runJdk = 17
  catalog = "shop"
  war("shop-a", "shop-b")
  tomcat("tomcat10", libs.sample.tomcat10.dist)
  tomcat("tomcat11", libs.sample.tomcat11.dist)
  dockerImage()
  smoke {
    // Clients of GET /api/pay/{id}: shop-boot3's stack.
    expectClients("hurl", "resttemplate", "hc5", "okhttp", "jdk11", "webclient")
    for ((key, server) in listOf("tomcat10" to tomcat10Server, "tomcat11" to tomcat11Server)) {
      for (jdk in listOf(17, 21, 25)) {
        // Default names: Tomcat 10.1 on JDK 17 and Tomcat 11 on JDK 21 (18 §4.1).
        val isDefault = (key == "tomcat10" && jdk == 17) || (key == "tomcat11" && jdk == 21)
        variant(if (isDefault) key else "$key-jdk$jdk") {
          tomcat = key
          runJdk = jdk
          // JDK 25 runs as the 02 §9 matrix does: sun.misc.Unsafe memory access denied.
          if (jdk == 25) jvmArg("--sun-misc-unsafe-memory-access=deny")
          expectRuntime("app", "shop-war-jakarta")
          expectRuntime("servletApi", "jakarta")
          expectRuntime("server", server)
          expectRuntime("javaFeatureVersion", jdk.toString())
        }
      }
    }
    // Thread-name seed (custom executor) and the secret-looking environment seeds (18 §4.3, §4.4)
    // in both web applications of one Tomcat.
    variant("seeds") {
      tomcat = "tomcat10"
      env("SAMPLE_CUSTOM_EXECUTOR", "true")
      seedEnvironment = true
      expectRuntime("customExecutor", "true")
      expectBody("async.executor", "seed-ARGOS_SEED-")
      expectBody("seed.xss.thread.0", "seed-ARGOS_SEED-")
      expectBody("seed.env", "seeded")
    }
    variant("routing-ds") {
      tomcat = "tomcat11"
      runJdk = 21
      env("SAMPLE_ROUTING_DS", "true")
      expectRuntime("routingDataSource", "true")
      expectRuntime(
        "dataSourceChain",
        "SampleRoutingDataSource (AbstractRoutingDataSource) -> TransactionAwareDataSourceProxy" +
          " -> HikariDataSource -> org.h2.Driver",
      )
    }
    // The web app's own Tomcat JDBC pool (WEB-INF/lib) in a standalone Tomcat whose lib/ also has
    // tomcat-jdbc.jar: the pool switch in the container that ships the same pool.
    variant("pool-tomcat") {
      tomcat = "tomcat11"
      runJdk = 17
      env("SAMPLE_POOL", "tomcat")
      expectRuntime("pool", "tomcat")
    }
  }
}

dependencies {
  implementation(platform(libs.sample.boot3.bom))
  implementation(project(":sample-apps:shop-boot3"))
  // ServletInitializer compiles against SpringBootServletInitializer (spring-boot) and the servlet
  // API; shop-boot3 brings them at run time but keeps them off its consumers' compile class path.
  compileOnly(libs.sample.starter.web)
  providedRuntime(platform(libs.sample.boot3.bom))
  providedRuntime(libs.sample.starter.tomcat)
}
