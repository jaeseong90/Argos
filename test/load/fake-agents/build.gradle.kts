// SPDX-License-Identifier: Apache-2.0
//
// Server load generator and runner (18 §8, DSN-18-04, WO-29): fake agents that drive the whole
// server at protocol level (11 proto stubs through the server's testFixtures FakeAgent, not the
// agent code), the N-06 one-hour 5,000 TPS run and its measurements (N-06, N-18, D-13), and the
// report docs/bench/server-load.md. Development-only (ArgosProject.isDevelopmentOnly), never
// shipped.
//   main   generator (18 §8 parameters), runner (Testcontainers PostgreSQL + ClickHouse, the server
//          boot jar as a child JVM, 10 virtual dashboard users, 5 s samplers), verdicts, report
//   test   no Docker: parsers, histogram quantiles, verdict rules (T-NFR-06a), ClickHouse settings
//          rule (T-NFR-18a), options and profiles, report template (part of check)
//
// serverLoadTest (not part of check or build; 18 §2.2) requires -Pbench.machine-profile=<id> (the
// root guard of argos.test-layers and the task itself):
//   ./gradlew serverLoadTest -Pbench.machine-profile=<id> [-Pserver.load.profile=full|smoke|mini]
//       [-Pserver.load.publish=true] [-Pserver.load.args="--tps=2000 --duration=10m"]
// full = N-06 (5,000 TPS, 1 h, 50 instances, 13 §7.1 limits) and the only profile with PASS/FAIL;
// smoke = 1,000 TPS for 5 min on a shared machine; mini = a short self-check. Every timing and
// resource verdict of smoke and mini is INVALID. The report goes to build/reports/server-load/;
// -Pserver.load.publish=true copies it to docs/bench/server-load.md. See README.md.

import io.argos.buildlogic.testing.MachineProfile
import io.argos.buildlogic.testing.TestLayers

plugins { id("argos.java-common") }

description = "Argos server load generator and runner: fake agents at protocol level (18 §8, WO-29)."

dependencies {
  // FakeAgent, Records, AgentTokens, AgentChannels (testing.fakeagent), ServerTestEnvironment,
  // TestUsers and ApiTestClient (testing.auth) and the load probes (testing.load). No new external
  // artifact: everything comes with the server's test fixtures.
  implementation(testFixtures(project(":server")))
  // The fixtures' Spring, Jackson, gRPC and Netty artifacts carry no version of their own: the
  // server's BOMs (testFixturesImplementation platforms, not exported) align them here too.
  implementation(platform(libs.spring.boot.bom))
  implementation(platform(libs.grpc.bom))
  implementation(platform(libs.netty.bom))
}

val machineProfile = providers.gradleProperty(TestLayers.MACHINE_PROFILE_PROPERTY).orNull
val loadProfile = providers.gradleProperty("server.load.profile").getOrElse("smoke")
val publish = providers.gradleProperty("server.load.publish").map { it.toBoolean() }.getOrElse(false)
val extraArgs = providers.gradleProperty("server.load.args").getOrElse("")
val serverBootJar =
  layout.settingsDirectory.file("server/build/libs/argos-server-${project.version}.jar").asFile

tasks.register<JavaExec>("serverLoadTest") {
  group = "verification"
  description =
    "Server load test (18 §8, N-06, N-18, D-13): fake agents against the boot jar, PostgreSQL and " +
      "ClickHouse. Requires -P${TestLayers.MACHINE_PROFILE_PROPERTY}=<id>; " +
      "-Pserver.load.profile=full|smoke|mini, -Pserver.load.publish=true."
  dependsOn(":server:bootJar")
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass = "io.argos.load.ServerLoadMain"
  // The generator holds 50 fake agents, their dictionaries and the late-arrival buffer.
  maxHeapSize = if (loadProfile == "full") "3g" else "1g"
  val root = layout.settingsDirectory.asFile
  val reportDir = layout.buildDirectory.dir("reports/server-load").get().asFile
  val tmp = layout.buildDirectory.dir("tmp/serverLoadTest").get().asFile
  systemProperty("argos.repositoryRoot", root.absolutePath)
  systemProperty("java.io.tmpdir", tmp.absolutePath)
  val taskPath = path
  val profileId = machineProfile
  args(
    buildList {
      add("--profile=$loadProfile")
      add("--machine-profile=${profileId ?: ""}")
      add("--boot-jar=${serverBootJar.absolutePath}")
      add("--out=${reportDir.absolutePath}")
      if (publish) add("--publish=${root.resolve("docs/bench/server-load.md").absolutePath}")
      addAll(extraArgs.split(' ').filter { it.isNotBlank() })
    }
  )
  // Measurements are never up to date.
  outputs.upToDateWhen { false }
  doFirst {
    // The root guard (argos.test-layers) fails at graph time already; this covers a task graph that
    // reaches the task by another route.
    if (!MachineProfile.isValid(profileId)) {
      throw GradleException(MachineProfile.missingMessage(listOf(taskPath), profileId))
    }
    tmp.mkdirs()
    reportDir.mkdirs()
  }
}
