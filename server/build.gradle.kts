// SPDX-License-Identifier: Apache-2.0
//
// Collector server (02 §4.2, 12): one Spring Boot module, roles split by package (12 §1). The build
// rules (boot jar, UI bundle, Flyway file names, image, logging exclusions) come from
// argos.java-server; this file lists the dependencies (WO-20).
//   main             Spring MVC (embedded Tomcat), Actuator + Micrometer Prometheus, JDBC (HikariCP),
//                    Bean Validation, Log4j 2 (JSON template layout), Flyway + PostgreSQL driver,
//                    ClickHouse client-v2
//                    + (WO-24) Spring Security, Caffeine, springdoc WebMVC API (runtime spec), Bucket4j
//                    + (WO-21) the argos.v1 protocol (:proto) and the gRPC collector transport
//                    grpc-netty on the server's Netty 4.2 (JDK SSL provider only)
//   testFixtures     ServerTestEnvironment: PostgreSQL 16 and ClickHouse in Testcontainers (pinned
//                    images, C-11 roles, generated passwords); reused by WO-21, WO-23 and WO-24.
//                    testing.auth (WO-24): ApiTestClient (cookie jar, CSRF, Origin) and TestUsers
//                    testing.fakeagent (WO-21): protocol-level fake agent (18 §8; reused by WO-29)
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

/** The executable jar (child-JVM tests of WO-21: T-SV-010, IngestLagLoadIT, T-SEC-11). */
val serverBootJar = tasks.named<Jar>("bootJar")

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
  // WO-24: authentication, sessions, RBAC and rate limits (12 §7.2-7.4, 16 §7). Spring Security runs
  // stateless (io.argos.server.web.security.SecurityConfig); JWT signing uses the JDK's Ed25519
  // (16 §15 #11), no JOSE library.
  implementation(libs.server.starter.security)
  implementation(libs.server.caffeine)
  implementation(libs.server.springdoc.webmvc.api)
  implementation(libs.server.bucket4j)
  // WO-21: gRPC collector (12 §3.1, 11 §2). Messages and stubs come from :proto (ADR-0010 C-104);
  // the transport is grpc-netty (not grpc-netty-shaded, which bundles the netty-tcnative BoringSSL
  // native library) on the catalog Netty 4.2 BOM, used with the JDK SSL provider only (16 §3.1).
  // No grpc-services: no reflection and no gRPC health service (16 §4.1, 12 open item 9,
  // ADR-0010 C-130 (5)). Caffeine (token cache) is already above (WO-24).
  implementation(project(":proto"))
  implementation(platform(libs.grpc.bom))
  implementation(platform(libs.netty.bom))
  implementation(libs.grpc.netty)
  implementation(libs.grpc.stub)
  implementation(libs.grpc.protobuf)

  testFixturesImplementation(platform(libs.spring.boot.bom))
  testFixturesApi(project(":test:storage:clickhouse-support"))
  testFixturesApi(libs.testcontainers.core)
  testFixturesApi(libs.testcontainers.postgresql)
  testFixturesRuntimeOnly(libs.postgresql)
  // testing.auth (WO-24): JSON responses of ApiTestClient, bcrypt hashes of TestUsers, the server
  // start of ArgosTestServer (SpringApplication). No new artifacts: all are on the runtime classpath.
  testFixturesApi(libs.server.jackson.databind)
  testFixturesApi(libs.server.starter.webmvc)
  testFixturesImplementation(libs.server.security.crypto)
  // io.argos.servertest.security (WO-24): the permission-matrix generator and the test-only header
  // authentication use the Spring Security types of ArgosAuthentication. No new artifacts.
  testFixturesImplementation(libs.server.starter.security)
  // testing.fakeagent (WO-21): the fake agent speaks argos.v1 over grpc-netty (TLS with a CA
  // generated at test run time, or plaintext) or an in-process channel. Tests get the protocol
  // classes and both transports through the fixtures.
  testFixturesApi(project(":proto"))
  testFixturesImplementation(platform(libs.grpc.bom))
  testFixturesImplementation(platform(libs.netty.bom))
  testFixturesApi(libs.grpc.netty)
  testFixturesApi(libs.grpc.inprocess)
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
      // The storage generator (WO-21): BlockTokenTest checks the writer's C-61 tokens against the
      // generator's DedupToken (shared vectors).
      dependencies {
        implementation(project(":test:storage:clickhouse-support"))
        implementation(project(":test:storage:generator"))
      }
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
    named<JvmTestSuite>("contractTest") {
      // The permission-matrix generator (T-SEC-49, T-API-010) and ApiTestClient. The contract suite
      // starts no container: it is part of check, which needs no Docker.
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
  // T-ACC-13 (c) (WO-21): minutes of the ClickHouse pause of ClickHouseOutageIT; 3 by default,
  // -Pserver.chOutageMinutes=30 for the full outage variant of 03 §3.2 (run on a quiet machine).
  systemProperty(
    "argos.test.chOutageMinutes",
    providers.gradleProperty("server.chOutageMinutes").getOrElse("3"),
  )
  // End-to-end tests (WO-21, io.argos.server.e2e): T-SV-010 runs the server as a child JVM from the
  // boot jar. IngestLagLoadIT (1,000 TPS for 5 minutes, 03 §3.2 reference) runs only with
  // -Pserver.load=true; its p99 <= 5 s is asserted only with -Pserver.load.enforce=true (a shared
  // machine reports, a quiet machine enforces). Report: build/reports/ingest-load/report.md.
  val bootJarFile = serverBootJar.flatMap { it.archiveFile }
  inputs.file(bootJarFile).withPropertyName("serverBootJar").withPathSensitivity(PathSensitivity.NONE)
  jvmArgumentProviders.add(
    CommandLineArgumentProvider { listOf("-Dargos.test.bootJar=" + bootJarFile.get().asFile.absolutePath) }
  )
  systemProperty(
    "argos.test.serverLoad",
    providers.gradleProperty("server.load").getOrElse("false"),
  )
  systemProperty(
    "argos.test.serverLoadEnforce",
    providers.gradleProperty("server.load.enforce").getOrElse("false"),
  )
  systemProperty(
    "argos.test.ingestLoadReportDir",
    layout.buildDirectory.dir("reports/ingest-load").get().asFile.absolutePath,
  )
}

// T-SEC-11 (GzipBombSecurityTest) measures the resident set of the server started from the boot jar.
tasks.named<Test>("securityTest") {
  val bootJarFile = serverBootJar.flatMap { it.archiveFile }
  inputs.file(bootJarFile).withPropertyName("serverBootJar").withPathSensitivity(PathSensitivity.NONE)
  jvmArgumentProviders.add(
    CommandLineArgumentProvider { listOf("-Dargos.test.bootJar=" + bootJarFile.get().asFile.absolutePath) }
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
