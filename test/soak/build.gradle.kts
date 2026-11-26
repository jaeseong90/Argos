// SPDX-License-Identifier: Apache-2.0
//
// Soak test (18 §11.2, WO-52): shop-boot3 with the agent under a constant load (the sample-apps/load
// generator, 18 §7.2 bench mix), shop-batch with the agent and a detached Spring Batch run every
// hour, ext-stub, the real server (boot jar as a child JVM) and ClickHouse and PostgreSQL
// (Testcontainers). Agent heap after GC.run, NMT, threads, FD, server RSS and WAL, ClickHouse parts,
// merges and disk, the nightly consistency check, alert false positives and app p99 are sampled
// and judged (T-SOAK-001, T-NFR-18). Development-only (ArgosProject.isDevelopmentOnly), never
// shipped.
//   main   options, interval scaling, probes, runner, verdicts, report (io.argos.test.soak)
//   test   no Docker: regression and flatness, N-19 formula, interval scaling, INVALID rule, OOM
//          detection, report template (part of check)
//
// soakTest (not part of check or build; 18 §2.2) requires -Pbench.machine-profile=<id> (the root
// guard of argos.test-layers and the task itself):
//   ./gradlew soakTest -Pbench.machine-profile=<id> [-Psoak.duration=72h|15m] [-Psoak.tps=500]
//       [-Psoak.vt=true] [-Psoak.publish=true] [-Psoak.args="--app-heap=1g"]
// Time and resource verdicts are PASS/FAIL only for a run of at least 72 hours at 500 TPS or more on
// a dedicated machine profile (argos-bench-app); every other run reports them INVALID with the
// values. OOM 0, the consistency check and the ClickHouse settings rule are always PASS/FAIL. The
// report goes to build/reports/soak/; -Psoak.publish=true copies it to docs/bench/soak-<date>.md.
// See README.md.

import io.argos.buildlogic.testing.MachineProfile
import io.argos.buildlogic.testing.TestLayers

plugins { id("argos.java-common") }

description = "Argos soak test runner (18 §11.2, WO-52)."

dependencies {
  // ServerTestEnvironment, LoadServerJvm and the load probes (testing.load), AgentTokens, TestUsers
  // and ApiTestClient: the server's test fixtures. No new external artifact.
  implementation(testFixtures(project(":server")))
  // AppLauncher, SampleApp, Jdk, JcmdClient, ClassHistogram, NmtSummary, ThreadCounter and
  // SelfDiagnostics: the agent test harness.
  implementation(project(":agent:agent-testing"))
  // ClickHouseSettingsRule (T-NFR-18a) of the server load runner.
  implementation(project(":test:load:fake-agents"))
  // The fixtures' Spring, Jackson, gRPC and Netty artifacts carry no version of their own: the
  // server's BOMs align them here too (as in test/load/fake-agents).
  implementation(platform(libs.spring.boot.bom))
  implementation(platform(libs.grpc.bom))
  implementation(platform(libs.netty.bom))
}

val machineProfile = providers.gradleProperty(TestLayers.MACHINE_PROFILE_PROPERTY).orNull
val soakDuration = providers.gradleProperty("soak.duration").getOrElse("72h")
val soakTps = providers.gradleProperty("soak.tps").getOrElse("500")
val soakVt = providers.gradleProperty("soak.vt").map { it.toBoolean() }.getOrElse(false)
val publish = providers.gradleProperty("soak.publish").map { it.toBoolean() }.getOrElse(false)
val extraArgs = providers.gradleProperty("soak.args").getOrElse("")

/** The sample app jars the run launches ({@code sampleAppJar} of each project). */
val sampleApps = listOf("shop-boot3", "shop-batch", "load", "ext-stub")

tasks.register<JavaExec>("soakTest") {
  group = "verification"
  description =
    "Soak test (18 §11.2, T-SOAK-001, T-NFR-18): shop-boot3 and shop-batch with the agent against " +
      "the boot jar, PostgreSQL and ClickHouse. Requires -P${TestLayers.MACHINE_PROFILE_PROPERTY}=<id>; " +
      "-Psoak.duration=72h, -Psoak.tps=500, -Psoak.vt=true, -Psoak.publish=true, -Psoak.args."
  dependsOn(":server:bootJar", ":agent:agent-dist:agentJar")
  sampleApps.forEach { dependsOn(":sample-apps:$it:sampleAppJar") }
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass = "io.argos.test.soak.SoakMain"
  maxHeapSize = "512m"
  val root = layout.settingsDirectory.asFile
  val version = project.version.toString()
  val reportDir = layout.buildDirectory.dir("reports/soak").get().asFile
  val tmp = layout.buildDirectory.dir("tmp/soakTest").get().asFile
  systemProperty("argos.repositoryRoot", root.absolutePath)
  systemProperty("java.io.tmpdir", tmp.absolutePath)
  val taskPath = path
  val profileId = machineProfile
  args(
    buildList {
      add("--duration=$soakDuration")
      add("--tps=$soakTps")
      add("--vt=$soakVt")
      add("--machine-profile=${profileId ?: ""}")
      add("--boot-jar=${root.resolve("server/build/libs/argos-server-$version.jar").absolutePath}")
      add("--agent-jar=${root.resolve("agent/agent-dist/build/libs/argos-agent-$version.jar").absolutePath}")
      sampleApps.forEach {
        add("--$it-jar=${root.resolve("sample-apps/$it/build/sample-app/$it.jar").absolutePath}")
      }
      add("--out=${reportDir.absolutePath}")
      if (publish) add("--publish=${root.resolve("docs/bench").absolutePath}")
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
