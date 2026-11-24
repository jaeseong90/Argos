// SPDX-License-Identifier: Apache-2.0
//
// shop-batch (18 §4.1): Spring Boot 3.x batch app. @Scheduled x 3 (fixed rate, cron, long running),
// Quartz jobs x 2, Spring Batch job (Tasklet step + chunk step of 5,000 items), one DataSource
// shared by the batch metadata and the business data (common: SampleDataSources). Endpoints: the
// batch catalog (common: BatchCatalog, POST /batch/run/{job}). Java 17, runs on 17, 21 and 25.
// The smoke triggers every job (Spring Batch, both Quartz jobs) and checks its completion, and
// waits until each @Scheduled kind has run.

plugins {
  id("argos.sample-app")
  // Tests over 1 s (@Tag("slow")) live in src/integrationTest (18 §2.2).
  id("argos.integration-test")
}

description = "Sample app: Spring Batch, Quartz and @Scheduled jobs on a shared DataSource."

sampleApp {
  javaRelease = 17
  runJdk = 17
  catalog = "batch"
  bootJar("io.argos.sample.batch.ShopBatchApplication", libs.sample.boot3.loader)
  dockerImage()
  smoke {
    variant("default") {
      expectRuntime("quartzJobStore", "LocalDataSourceJobStore")
      expectRuntime("quartzStoreInAppDataSource", "true")
      expectRuntime("batchMetadataInAppDataSource", "true")
    }
    variant("jdk21") { runJdk = 21 }
    // JDK 25 (02 §9 and 18 §6.1 T-MX-070~075; 18 §4.1 lists 17 and 21) as the matrix runs it:
    // sun.misc.Unsafe memory access denied.
    variant("jdk25") {
      runJdk = 25
      jvmArg("--sun-misc-unsafe-memory-access=deny")
      expectRuntime("javaFeatureVersion", "25")
    }
    // @Scheduled, Tomcat and the task executor on virtual threads (Boot 3.2+, JDK 21).
    variant("vt") {
      runJdk = 21
      env("SAMPLE_VT", "true")
      expectRuntime("virtualThreads", "true")
      expectRuntime("scheduledVirtualThreads", "true")
    }
    // settleStep prices every item with its own query (20,000 SQL per run, WO-18 summary mode).
    variant("per-item-sql") {
      env("SAMPLE_BATCH_PER_ITEM_SQL", "true")
      expectRuntime("batchPerItemSql", "true")
    }
    // Batch metadata, Quartz store and shop data behind the 4-level routing chain (D-24).
    variant("routing-ds") {
      env("SAMPLE_ROUTING_DS", "true")
      expectRuntime("routingDataSource", "true")
      expectRuntime(
        "dataSourceChain",
        "SampleRoutingDataSource (AbstractRoutingDataSource) -> TransactionAwareDataSourceProxy" +
          " -> HikariDataSource -> org.h2.Driver",
      )
      expectRuntime("quartzStoreInAppDataSource", "true")
    }
  }
}

dependencies {
  implementation(platform(libs.sample.boot3.bom))
  implementation(project(":sample-apps:common"))
  implementation(libs.sample.starter.web)
  implementation(libs.sample.starter.batch)
  implementation(libs.sample.starter.quartz)
  implementation(libs.sample.starter.jdbc)
  implementation(libs.sample.starter.actuator)
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
