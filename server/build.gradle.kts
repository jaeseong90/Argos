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
//                    testing.querydata (WO-23): generator data + PostgreSQL seed for the query API
//                    tests, the bench and the dev task loadQueryTestData (WO-30's E2E seed)
//   integrationTest  real PostgreSQL and ClickHouse only (Testcontainers), plus the storage
//                    generator for the 1/100 data of T-ST-052 path 2 and T-SV-002
// test/storage/* projects are development-only: they appear in testFixtures and test suites, never
// in the main (shipped) classpath.
// Release metadata (WO-44, 19 §3.5, §5.5, O-L04, SEC-70, SEC-77, ADR-0010 C-153): the boot jar's
// META-INF carries LICENSE, NOTICE, THIRD-PARTY-NOTICES, argos-deps.txt, sbom/server.cdx.json and
// the About lists argos/licenses/{server,agent,ui}.json; verifyServerJar (T-BLD-003a) finalizes
// bootJar. The image carries the same files (/opt/argos and the class path). These tasks depend on
// checkLicenses (the jk1 report is not configuration-cache compatible), so they are attached only
// to bootJar, the Jib tasks and integrationTest: test and contractTest keep the configuration cache.

import io.argos.buildlogic.ArgosProject
import io.argos.buildlogic.NormalizeSbom
import io.argos.buildlogic.argosSourceDateInstant
import io.argos.buildlogic.release.notices.GenerateLicenseList
import io.argos.buildlogic.release.notices.LicenseLists
import io.argos.buildlogic.release.notices.UpstreamText
import io.argos.buildlogic.release.notices.VerifyServerJar
import io.argos.buildlogic.release.notices.registerBundledNotices
import io.argos.buildlogic.server.ServerImage
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.cyclonedx.model.ExternalReference

plugins {
  id("argos.java-server")
  id("argos.integration-test")
  `java-test-fixtures`
}

// CycloneDX comes with the build-logic classpath (as in :agent:agent-dist).
apply(plugin = "org.cyclonedx.bom")

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
  // WO-22: realtime bus and WebSocket (12 §3.7, 14 §5). Spring WebSocket on the embedded Tomcat
  // (raw WebSocketHandler, no STOMP broker) and Reactor sinks per topic key; both from
  // spring-boot-bom. reactive-streams (MIT-0, ADR-0004 C-42) comes with reactor-core.
  implementation(libs.server.starter.websocket)
  implementation(libs.server.reactor.core)
  // WO-25: alert windows (12 §5.2, DSN-12-07): per-second HdrHistogram buckets merged for the
  // percentiles of a rule window. Already on the runtime classpath through Micrometer (same version).
  implementation(libs.server.hdrhistogram)

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
  // testing.querydata (WO-23): the storage generator's data for the query API tests, the bench and
  // the development command loadQueryTestData below. A development-only project (never shipped);
  // no new external artifact.
  testFixturesApi(project(":test:storage:generator"))
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
      dependencies {
        implementation(testFixtures(project()))
        // T-NFR-10c: the seed values and leak sentinels exist once (Seeds of the sample apps,
        // ADR-0010 C-250 (1)); a Java 8 library without run-time dependencies.
        implementation(project(":sample-apps:common"))
      }
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

tasks.named<Test>("test") {
  // AuditEventTest reads the audit row of the Compose token command (ADR-0010 C-199 (3)).
  inputs
    .file(layout.settingsDirectory.file("deploy/compose/argos-compose.sh"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("composeTokenCreateScript")
  // AgentKeySchemaDocTest compares the server-side remote config key schema with the agent key
  // reference (T-SV-042k, WO-26).
  inputs
    .file(layout.settingsDirectory.file("docs/agent-config.md"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("agentConfigReference")
}

tasks.named<Test>("integrationTest") {
  // One Docker-heavy class at a time: the machine is shared (each test class starts PostgreSQL and
  // ClickHouse, <= 5 GiB per container).
  maxParallelForks = 1
  // One JVM runs every class: Gradle's default 512 MiB ran out of heap once the WO-26 classes were
  // added, so the test JVM gets 1.5 GiB. WO-28 (C-231 (13)) found no leak: contexts of finished
  // classes are held only softly (cleared under memory pressure) except the last server, which Log4j
  // keeps through Spring Boot's SpringEnvironmentLookup until the next one starts.
  maxHeapSize = "1536m"
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
  // QueryApiBenchIT (WO-23, N-07/N-24 call set, cold and warm) runs only with -Pserver.apiBench=true,
  // on generator data -Pserver.apiBench.profile=ci (1/100, default), local (1/20) or full (30 days);
  // its targets are asserted only with -Pserver.apiBench.enforce=true, which requires
  // -Pserver.apiBench.profile=full (target hardware; any other profile fails). Server heap
  // -Pserver.apiBench.serverXmx and ClickHouse container memory -Pserver.apiBench.chMemory (e.g.
  // 7.5g); defaults -Xmx4g / 7.5 GiB with enforce (13 §7.1), else -Xmx1g / 4 GiB. Run it alone
  // (--tests '*QueryApiBench*'): its containers do not fit next to the shared query data.
  // Report: build/reports/api-bench/report.md.
  systemProperty("argos.test.apiBench", providers.gradleProperty("server.apiBench").getOrElse("false"))
  systemProperty(
    "argos.test.apiBenchProfile",
    providers.gradleProperty("server.apiBench.profile").getOrElse("ci"),
  )
  systemProperty(
    "argos.test.apiBenchEnforce",
    providers.gradleProperty("server.apiBench.enforce").getOrElse("false"),
  )
  systemProperty(
    "argos.test.apiBenchServerXmx",
    providers.gradleProperty("server.apiBench.serverXmx").getOrElse(""),
  )
  systemProperty(
    "argos.test.apiBenchChMemory",
    providers.gradleProperty("server.apiBench.chMemory").getOrElse(""),
  )
  systemProperty(
    "argos.test.apiBenchReportDir",
    layout.buildDirectory.dir("reports/api-bench").get().asFile.absolutePath,
  )
}

// T-NFR-10c (WO-46, PiiSeedGrepSecurityTest): the PII seed grep runs the real agent jar on shop-boot3
// and sends the seed traffic with the smoke runner. One resolvable configuration per artifact, the
// same variants the agent harness consumes (argos-agent-jar, argos-sample-app).
val securityTestJars =
  mapOf(
      "agentJar" to Pair(":agent:agent-dist", "argos-agent-jar"),
      "shopBoot3Jar" to Pair(":sample-apps:shop-boot3", "argos-sample-app"),
      "smokeJar" to Pair(":sample-apps:smoke", "argos-sample-app"),
    )
    .mapValues { (key, source) ->
      val scope = configurations.dependencyScope("securityTest${key.replaceFirstChar(Char::uppercaseChar)}Scope")
      dependencies { add(scope.name, project(source.first)) }
      configurations.resolvable("securityTest${key.replaceFirstChar(Char::uppercaseChar)}") {
        extendsFrom(scope.get())
        attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named(source.second)) }
      }
    }

// T-SEC-11 (GzipBombSecurityTest) measures the resident set of the server started from the boot jar.
tasks.named<Test>("securityTest") {
  val bootJarFile = serverBootJar.flatMap { it.archiveFile }
  inputs.file(bootJarFile).withPropertyName("serverBootJar").withPathSensitivity(PathSensitivity.NONE)
  jvmArgumentProviders.add(
    CommandLineArgumentProvider { listOf("-Dargos.test.bootJar=" + bootJarFile.get().asFile.absolutePath) }
  )
  // T-NFR-10c: -Dargos.test.<agentJar|shopBoot3Jar|smokeJar>=<file>; seed requests of the PII grep
  // -Psecurity.pii.requests (default 10,000 of N-10c for nightly and the release gate; the PR job
  // security-light passes 2,000, ADR-0010 C-250 (2)).
  val jarFiles: Map<String, FileCollection> = securityTestJars.mapValues { it.value.get() }
  jarFiles.forEach { (key, files) ->
    inputs.files(files).withPropertyName("securityTest-$key").withNormalizer(ClasspathNormalizer::class.java)
  }
  jvmArgumentProviders.add(
    CommandLineArgumentProvider { jarFiles.map { (key, files) -> "-Dargos.test.$key=" + files.singleFile.absolutePath } }
  )
  systemProperty("argos.test.piiRequests", providers.gradleProperty("security.pii.requests").getOrElse("10000"))
}

// --- Query test data for a running installation (WO-23, development only) -------------------------
// Loads the generator data of the query API tests (testing.querydata.QueryTestData) into ClickHouse
// and PostgreSQL of a running server whose schema exists, and writes the manifest
// query-test-data.json (time range, ids, samples) for WO-30's E2E stack. Passwords come from the
// environment only (ARGOS_CH_WRITER_PASSWORD, ARGOS_PG_PASSWORD), never from -P properties.
//   ./gradlew :server:loadQueryTestData -Pquerydata.clickhouse=http://127.0.0.1:8123 \
//       -Pquerydata.postgres=jdbc:postgresql://127.0.0.1:5432/argos [-Pquerydata.profile=tiny|ci|local|full]
//       [-Pquerydata.placement=recent|around_midnight] [-Pquerydata.out=DIR]
tasks.register<JavaExec>("loadQueryTestData") {
  group = "argos dev"
  description =
    "Loads the query test data into a running installation (development only; " +
      "-Pquerydata.clickhouse, -Pquerydata.postgres, passwords from the environment)."
  classpath = sourceSets["testFixtures"].runtimeClasspath
  mainClass = "io.argos.server.testing.querydata.LoadQueryTestData"
  maxHeapSize = "2g"
  systemProperty("argos.repositoryRoot", layout.settingsDirectory.asFile.absolutePath)
  val defaultOut = layout.buildDirectory.dir("query-test-data").get().asFile.absolutePath
  val keys = listOf("clickhouse", "postgres", "profile", "placement", "database", "prefix")
  args(
    keys.mapNotNull { key ->
      providers.gradleProperty("querydata.$key").orNull?.let { "--$key=$it" }
    } + "--out=${providers.gradleProperty("querydata.out").getOrElse(defaultOut)}"
  )
}

// --- Active-transaction feed (WO-35, development only, never shipped) ------------------------------
// build/active-feed/active-feed.jar runs io.argos.server.testing.fakeagent.ActiveFeed: FakeAgent
// sessions that send the deterministic snapshots of ActiveFeedScenario to a running collector
// (plaintext) for the E2E stack (ui/e2e/stack/seed/activeFeed.ts), until the real agent produces
// active snapshots (WO-16). The class directories of the testFixtures runtime classpath go into the
// jar; its manifest Class-Path names the jars of that classpath, copied to build/active-feed/lib.
//   java -jar server/build/active-feed/active-feed.jar --target 127.0.0.1:<grpc port> --token-file F
val activeFeedDir = layout.buildDirectory.dir("active-feed")
val activeFeedClasspath: FileCollection = sourceSets["testFixtures"].runtimeClasspath
val activeFeedLibraries: FileCollection =
  activeFeedClasspath.filter { it.isFile && it.name.endsWith(".jar") }
val activeFeedLibs =
  tasks.register<Sync>("activeFeedLibs") {
    group = "argos dev"
    description = "Runtime libraries of the active-transaction feed (development only)."
    from(activeFeedLibraries)
    into(activeFeedDir.map { it.dir("lib") })
  }
tasks.register<Jar>("activeFeedJar") {
  group = "argos dev"
  description =
    "Active-transaction feed for the E2E stack: build/active-feed/active-feed.jar (development only)."
  dependsOn(activeFeedLibs)
  archiveFileName = "active-feed.jar"
  destinationDirectory = activeFeedDir
  from(activeFeedClasspath.filter { it.isDirectory })
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  val libraries = activeFeedLibraries
  inputs.files(libraries).withPropertyName("activeFeedLibraries").withNormalizer(ClasspathNormalizer::class.java)
  manifest { attributes("Main-Class" to "io.argos.server.testing.fakeagent.ActiveFeed") }
  doFirst { manifest.attributes("Class-Path" to libraries.files.joinToString(" ") { "lib/" + it.name }) }
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

// --- Release metadata: notices, dependency manifest, SBOM, About lists (WO-44) -----------------
// Same generators as the agent jar (:agent:agent-dist), on the server's runtimeClasspath, which the
// boot jar ships unshaded (BOOT-INF/lib). Upstream texts the bundled jars do not carry are in
// src/notices (README.md there): a version change fails generateThirdPartyNotices until the text is
// replaced from the new release tag.

/** `argos-server`. */
val serverArtifactId: String = base.archivesName.get()

/**
 * Modules the Spring Boot plugin copies into the boot jar from its own resources (not from the
 * dependency graph): the launcher at the jar root and the jarmode tools (layers, extract) in
 * BOOT-INF/lib. Same version as the plugin.
 */
val bootPackagedModules =
  libs.plugins.spring.boot.get().version.requiredVersion.let { boot ->
    mapOf(
      "org.springframework.boot:spring-boot-loader:$boot" to "Apache-2.0",
      "org.springframework.boot:spring-boot-jarmode-tools:$boot" to "Apache-2.0",
    )
  }

val serverNotices =
  registerBundledNotices("$serverArtifactId-$version.jar", layout.buildDirectory.dir("server-meta-inf")) {
    // Dependencies-only starter jars (no classes) are not packaged by the Boot plugin.
    excludeSpringBootStarters = true
    bootPackagedModules.forEach { (coordinates, license) -> extraModule(coordinates, license) }
    licenseText(
      "com.google.protobuf:protobuf-java",
      "4.36.2",
      UpstreamText("protobuf-java-LICENSE.txt", "https://github.com/protocolbuffers/protobuf/blob/v36.2/LICENSE"),
    )
    licenseText(
      "org.ow2.asm:asm",
      "9.7",
      UpstreamText("asm-LICENSE.txt", "https://gitlab.ow2.org/asm/asm/-/blob/ASM_9_7/LICENSE.txt"),
    )
    licenseText(
      "org.reactivestreams:reactive-streams",
      "1.0.4",
      UpstreamText(
        "reactive-streams-LICENSE.txt",
        "https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.4/LICENSE",
      ),
    )
    licenseText(
      "com.github.luben:zstd-jni",
      "1.5.7-23",
      UpstreamText("zstd-jni-LICENSE.txt", "https://github.com/luben/zstd-jni/blob/v1.5.7-23/LICENSE"),
      UpstreamText(
        "zstd-LICENSE.txt",
        "https://github.com/facebook/zstd/blob/v1.5.7/LICENSE",
        heading = "Embedded component license (Zstandard 1.5.7, compiled into the native library of zstd-jni)",
      ),
    )
    val netty = "https://github.com/netty/netty/blob/netty-4.2.19.Final"
    val nettyEmbedded =
      listOf(
          "slf4j" to "SLF4J, MIT; netty-common io.netty.util.internal.logging",
          "webbit" to "Webbit, BSD-3-Clause; netty-codec-http WebSocket",
          "jbzip2" to "jbzip2, MIT; netty-codec-compression Bzip2",
          "libdivsufsort" to "libdivsufsort, MIT; netty-codec-compression Bzip2",
          "jfastlz" to "jfastlz, MIT; netty-codec-compression FastLz",
          "hyper-hpack" to "hyper-hpack, MIT; netty-codec-http2 HPACK",
          "nghttp2-hpack" to "nghttp2, MIT; netty-codec-http2 HPACK",
        )
        .map { (name, label) ->
          UpstreamText(
            "netty-LICENSE.$name.txt",
            "$netty/license/LICENSE.$name.txt",
            heading = "Embedded component license ($label)",
          )
        }
    noticeText(
      "io.netty",
      "4.2.19.Final",
      UpstreamText("netty-NOTICE.txt", "$netty/NOTICE.txt"),
      *nettyEmbedded.toTypedArray(),
    )
    noticeText(
      "io.grpc",
      "1.85.1",
      UpstreamText("grpc-java-NOTICE.txt", "https://github.com/grpc/grpc-java/blob/v1.85.1/NOTICE.txt"),
    )
    noticeText(
      "io.perfmark:perfmark-api",
      "0.27.0",
      UpstreamText("perfmark-NOTICE.txt", "https://github.com/perfmark/perfmark/blob/v0.27.0/NOTICE"),
    )
    noticeText(
      "io.prometheus",
      "1.7.0",
      UpstreamText(
        "prometheus-client_java-NOTICE.txt",
        "https://github.com/prometheus/client_java/blob/v1.7.0/NOTICE",
      ),
    )
  }

/** Bundled projects whose upstream NOTICE must be in THIRD-PARTY-NOTICES (verifyServerJar). */
val serverNoticeRequired = listOf("io.netty", "io.grpc", "io.perfmark:perfmark-api", "io.prometheus")

// SBOM (SEC-70, 16 §10.1): every module of the runtime classpath, with the Argos protocol project.
tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
  includeConfigs = listOf("runtimeClasspath")
  testConfigs = emptyList()
  projectType = Component.Type.APPLICATION
  componentGroup = ArgosProject.MAVEN_GROUP
  componentName = serverArtifactId
  componentDescription = "Argos APM collector server"
  // Reproducible output (DSN-19-05) and no build-environment leakage.
  includeBomSerialNumber = false
  includeBuildSystem = false
  includeLicenseText = false
  externalReferences =
    listOf(
      ExternalReference().apply {
        type = ExternalReference.Type.VCS
        url = ArgosProject.REPOSITORY_URL
      }
    )
  jsonOutput = layout.buildDirectory.file("sbom/raw/server.cdx.json")
  xmlOutput.convention(null as RegularFile?)
}

// The Spring Boot plugin reacts to the CycloneDX plugin: it copies the output of the aggregate task
// cyclonedxBom (random serial number, current time) into the main resources as
// META-INF/sbom/application.cdx.json and names it in the manifest (Sbom-Location). The boot jar
// carries the normalized META-INF/sbom/server.cdx.json instead (reproducible, T-BLD-009), so the
// aggregate task is disabled and Sbom-Location names server.cdx.json (Boot's actuator sbom endpoint
// reads it from there).
tasks.named("cyclonedxBom") {
  enabled = false
  // Without its own dependency on cyclonedxDirectBom, processResources (and so :server:test) does
  // not resolve the SBOM inputs.
  setDependsOn(emptyList<Any>())
}

tasks.named<ProcessResources>("processResources") { exclude("META-INF/sbom/application.cdx.json") }

// Release asset argos-server-<version>.cdx.json (WO-44 contract): timestamps pinned to
// SOURCE_DATE_EPOCH (T-BLD-009), :proto must be a component (T-BLD-008).
val serverSbom =
  tasks.register<NormalizeSbom>("serverSbom") {
    inputFile = tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom").flatMap { it.jsonOutput }
    timestamp = argosSourceDateInstant()
    requiredProjects = listOf(":proto")
    extraComponents = bootPackagedModules
    outputFile = layout.buildDirectory.file("sbom/server.cdx.json")
  }

// About lists (C-153): server.json from the server's argos-deps.txt, agent.json from the agent
// jar's argos-deps.txt (:agent:agent-dist agentDepsElements), ui.json from the UI build
// (check-licenses.mjs --about-json); without the UI (-Pargos.ui.skip=true) ui stays not_generated.
val skipUiBundle = providers.gradleProperty("argos.ui.skip").map { it.toBoolean() }.getOrElse(false)

val agentDeps = configurations.dependencyScope("agentDeps")
val agentDepsFile =
  configurations.resolvable("agentDepsFile") {
    extendsFrom(agentDeps.get())
    attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named("argos-agent-deps")) }
  }

dependencies { "agentDeps"(project(":agent:agent-dist")) }

val generateServerLicenseList =
  tasks.register<GenerateLicenseList>("generateServerLicenseList") {
    depsFile.from(serverNotices.depsFile)
    outputFile = layout.buildDirectory.file("about-lists/server.json")
  }

val generateAgentLicenseList =
  tasks.register<GenerateLicenseList>("generateAgentLicenseList") {
    depsFile.from(agentDepsFile)
    outputFile = layout.buildDirectory.file("about-lists/agent.json")
  }

/** The class path root that holds META-INF/argos/licenses/<part>.json. */
val aboutLicenseLists =
  tasks.register<Sync>("aboutLicenseLists") {
    group = "build"
    description = "Stages the About lists META-INF/argos/licenses/{server,agent,ui}.json (C-153)."
    into(layout.buildDirectory.dir("about-licenses"))
    into(LicenseLists.DIRECTORY) {
      from(generateServerLicenseList, generateAgentLicenseList)
      if (!skipUiBundle) {
        from(files(layout.settingsDirectory.file("ui/build/licenses/ui.json")) { builtBy(":ui:pnpmBuild") })
      }
    }
  }

val rootLicenseFiles = files(layout.settingsDirectory.file("LICENSE"), layout.settingsDirectory.file("NOTICE"))

serverBootJar {
  // Root META-INF, where the Boot plugin also moves build-info.properties.
  metaInf {
    from(rootLicenseFiles)
    from(serverNotices.noticesFile, serverNotices.depsFile)
    into("sbom") { from(serverSbom) }
  }
  from(aboutLicenseLists)
  exclude("META-INF/sbom/application.cdx.json")
  manifest { attributes("Sbom-Format" to "CycloneDX", "Sbom-Location" to "META-INF/sbom/server.cdx.json") }
}

val verifyServerJar =
  tasks.register<VerifyServerJar>("verifyServerJar") {
    jar = serverBootJar.flatMap { it.archiveFile }
    expectedVersion = version.toString()
    uiExpected = !skipUiBundle
    noticeRequired = serverNoticeRequired
    reportFile = layout.buildDirectory.file("reports/server-jar/verify-server-jar.txt")
  }

serverBootJar { finalizedBy(verifyServerJar) }

// T-API-013a: the in-process server of the integration tests sees the same lists; AboutApiIT
// compares them with the argos-deps.txt of the server and of the agent jar.
tasks.named<Test>("integrationTest") {
  classpath += files(aboutLicenseLists)
  val serverDeps = serverNotices.depsFile
  val agentDeps: FileCollection = agentDepsFile.get()
  inputs.file(serverDeps).withPropertyName("serverDepsManifest").withPathSensitivity(PathSensitivity.NONE)
  inputs.files(agentDeps).withPropertyName("agentDepsManifest").withPathSensitivity(PathSensitivity.NONE)
  systemProperty("argos.test.uiBundled", (!skipUiBundle).toString())
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-Dargos.test.serverDepsFile=" + serverDeps.get().asFile.absolutePath,
        "-Dargos.test.agentDepsFile=" + agentDeps.singleFile.absolutePath,
      )
    }
  )
}

// Server image: the notices in the working directory /opt/argos, the About lists on the class path
// (Jib's <appRoot>/resources).
val serverImageFiles = layout.buildDirectory.dir("jib/server-image-files")

val prepareServerImageFiles =
  tasks.register<Sync>("prepareServerImageFiles") {
    group = "argos"
    description = "Stages the notices, dependency manifest, SBOM and About lists of the server image."
    into(serverImageFiles)
    into(ServerImage.WORKING_DIRECTORY.removePrefix("/")) {
      from(rootLicenseFiles)
      from(serverNotices.noticesFile, serverNotices.depsFile)
      into("sbom") { from(serverSbom) }
    }
    into("${ServerImage.APP_ROOT.removePrefix("/")}/resources") { from(aboutLicenseLists) }
  }

jib {
  extraDirectories {
    paths {
      path {
        setFrom(serverImageFiles.get().asFile)
        into = "/"
      }
    }
  }
}

tasks.named { it.startsWith("jib") }.configureEach { dependsOn(prepareServerImageFiles) }
