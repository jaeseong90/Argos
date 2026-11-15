// SPDX-License-Identifier: Apache-2.0
//
// Collector server (02 §4.2, 12): one Spring Boot module, roles split by package (12 §1). The build
// rules (boot jar, UI bundle, Flyway file names, image, logging exclusions) come from
// argos.java-server; this file lists the dependencies (WO-20).
//   main             Spring MVC (embedded Tomcat), Actuator + Micrometer Prometheus, JDBC (HikariCP),
//                    Bean Validation, Log4j 2 (JSON template layout), Flyway + PostgreSQL driver,
//                    ClickHouse client-v2
//   testFixtures     ServerTestEnvironment: PostgreSQL 16 and ClickHouse in Testcontainers (pinned
//                    images, C-11 roles, generated passwords); reused by WO-21, WO-23 and WO-24
//   integrationTest  real PostgreSQL and ClickHouse only (Testcontainers), plus the storage
//                    generator for the 1/100 data of T-ST-052 path 2 and T-SV-002
// test/storage/* projects are development-only: they appear in testFixtures and test suites, never
// in the main (shipped) classpath.

plugins {
  id("argos.java-server")
  id("argos.integration-test")
  `java-test-fixtures`
}

description = "Argos collector server (gRPC ingest, REST API, alerting, administration)."

/** -Pserver.nightly=true enables the nightly integration tests (T-SV-002, ADR-0010 C-100). */
val serverNightly = providers.gradleProperty("server.nightly").map { it.toBoolean() }.getOrElse(false)

dependencies {
  implementation(libs.server.starter.webmvc)
  implementation(libs.server.starter.actuator)
  implementation(libs.server.starter.jdbc)
  implementation(libs.server.starter.validation)
  implementation(libs.server.starter.log4j2)
  implementation(libs.server.tomcat.annotations.api)
  runtimeOnly(libs.server.log4j.layout.template.json)
  implementation(libs.server.micrometer.prometheus)
  implementation(libs.flyway.core)
  runtimeOnly(libs.flyway.database.postgresql)
  implementation(libs.postgresql)
  implementation(libs.clickhouse.client)

  testFixturesImplementation(platform(libs.spring.boot.bom))
  testFixturesApi(project(":test:storage:clickhouse-support"))
  testFixturesApi(libs.testcontainers.core)
  testFixturesApi(libs.testcontainers.postgresql)
  testFixturesRuntimeOnly(libs.postgresql)
}

testing {
  suites {
    withType<JvmTestSuite>().configureEach {
      dependencies {
        implementation(libs.server.test.starter)
        implementation(libs.server.test.webmvc)
        implementation(libs.server.test.actuator)
        // ArgosProperties carries Bean Validation constraints: suites that see it (contract,
        // integration, security tests) need the annotations on their compile classpath.
        implementation(libs.server.starter.validation)
      }
    }
    named<JvmTestSuite>("test") {
      // The reference renderer of WO-03 (SchemaRenderer): the ClickHouse migration runner is a
      // port and SchemaTemplatesEquivalenceTest compares both on every schema file (C-56).
      dependencies { implementation(project(":test:storage:clickhouse-support")) }
    }
    named<JvmTestSuite>("integrationTest") {
      dependencies {
        implementation(testFixtures(project()))
        implementation(libs.testcontainers.junit.jupiter)
        implementation(project(":test:storage:generator"))
      }
    }
    named<JvmTestSuite>("securityTest") {
      dependencies { implementation(testFixtures(project())) }
    }
  }
}

tasks.withType<Test>().configureEach {
  // The fixtures read deploy/postgres and deploy/clickhouse (images, config, roles, schema).
  systemProperty("argos.repositoryRoot", layout.settingsDirectory.asFile.absolutePath)
  // Server log of tests that start the application (log4j2-spring.xml writes ${LOG_PATH}): below
  // build/, never server/logs of the source tree.
  systemProperty(
    "LOG_PATH",
    layout.buildDirectory.dir("test-logs/$name").get().asFile.absolutePath,
  )
  // Temporary files of the test JVMs (the secrets directories of ServerTestEnvironment, @TempDir,
  // Tomcat work directories) stay in build/tmp/<task>, not in the system temp directory: the root
  // integrationTest runs this task in parallel with the agent harness, whose attach runs fail on
  // any agent-like (argos*) entry that appears in the system temp directory meanwhile.
  systemProperty("java.io.tmpdir", temporaryDir.absolutePath)
  doFirst { temporaryDir.mkdirs() }
  inputs
    .files(
      layout.settingsDirectory.dir("deploy/postgres"),
      layout.settingsDirectory.dir("deploy/clickhouse"),
    )
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("deployFiles")
}

tasks.named<Test>("integrationTest") {
  // One Docker-heavy class at a time: the machine is shared (each test class starts PostgreSQL and
  // ClickHouse, <= 5 GiB per container).
  maxParallelForks = 1
  // T-SV-002 (V001 + 1/100 generator data, then the next-version fixture scripts) is a nightly test
  // (ADR-0010 C-100): it runs only with -Pserver.nightly=true and is reported as skipped otherwise.
  // The nightly job itself is WO-50.
  systemProperty("argos.server.nightly", serverNightly.toString())
  // Migration timing tables (T-ST-052, T-SV-002).
  systemProperty(
    "argos.migration.reportDir",
    layout.buildDirectory.dir("reports/migration").get().asFile.absolutePath,
  )
}

// --- ClickHouse migration files (13 §13.2, ADR-0007 C-56) ------------------------------------------
// deploy/clickhouse is the single source of the schema scripts, rollup SELECTs and procedures; the
// jar carries a copy under db/clickhouse that the migration runner reads at start
// (io.argos.server.ops.migration.MigrationSource). README files are documentation, not copied.
tasks.named<ProcessResources>("processResources") {
  from(layout.settingsDirectory.dir("deploy/clickhouse")) {
    include("schema/**", "rollups/**", "migrations/**")
    exclude("**/README.md")
    into("db/clickhouse")
  }
}
