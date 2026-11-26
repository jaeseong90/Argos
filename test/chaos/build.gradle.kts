// SPDX-License-Identifier: Apache-2.0
//
// Chaos tests (18 §11.1, DSN-18-08, WO-52): shop-boot3 with the agent <-> toxiproxy <-> the real
// server (boot jar as a child JVM) <-> ClickHouse and PostgreSQL (Testcontainers), the demo load
// generator (sample-apps/load, mix bench) at a fixed rate. Development-only
// (ArgosProject.isDevelopmentOnly), never shipped.
//   main       the run: stack, toxiproxy REST client (JDK HttpClient, the container image pinned by
//              digest in src/main/resources), load driver, probes, verdicts, report
//   test       no Docker: noise band, recovery, back-off gaps, drop accounting, duplicates, INVALID
//              rule, report template, toxiproxy JSON, options (part of check, T-CH-020)
//   chaosTest  one JUnit class per scenario (src/chaosTest), one stack per run shared in a fixed
//              order (T-CH-008 last). Not part of check or build (18 §2.2).
//
//   ./gradlew chaosTest -Pbench.machine-profile=<id> [-Pchaos.profile=short|full]
//       [-Pchaos.scenarios=T-CH-001,T-CH-009] [-Pchaos.rate=200] [-Pchaos.publish=true]
//       [-Pchaos.netem.image=<image@sha256:...>]
// short (default) proves the runner and the verdict logic in minutes; full is 18 §11.1 (server down
// 1 h, ClickHouse down 30 min). Timing and p99 verdicts are PASS/FAIL only with profile full on a
// dedicated runner (argos-matrix), INVALID otherwise; invariants are always PASS/FAIL. The report
// goes to build/reports/chaos/<run>/; -Pchaos.publish=true copies it to docs/bench/chaos-<date>.md.
// See README.md.

import io.argos.buildlogic.testing.MachineProfile
import io.argos.buildlogic.testing.TestLayers

plugins { id("argos.java-common") }

description = "Argos chaos tests with toxiproxy and docker (18 §11.1, WO-52)."

dependencies {
  // ServerTestEnvironment, LoadServerJvm and the load probes (testing.load), AgentTokens,
  // TestUsers and ApiTestClient: everything comes with the server's test fixtures.
  implementation(testFixtures(project(":server")))
  // The fixtures' Spring, Jackson, gRPC and Netty artifacts carry no version of their own: the
  // server's BOMs align them here too (same as test/load/fake-agents).
  implementation(platform(libs.spring.boot.bom))
  implementation(platform(libs.grpc.bom))
  implementation(platform(libs.netty.bom))
  // AppLauncher (sample app with the agent), SelfDiagnostics, JcmdClient (agent-testing harness).
  implementation(project(":agent:agent-testing"))
  // The in-process target of the sample app's outgoing calls (GET /api/pay/{id}).
  implementation(project(":sample-apps:ext-stub"))
}

testing {
  suites {
    register<JvmTestSuite>("chaosTest") { dependencies { implementation(project()) } }
  }
}

// --- Artifacts of a run -----------------------------------------------------------------------------
val agentJarScope = configurations.dependencyScope("chaosAgentJarScope")
val chaosAgentJar =
  configurations.resolvable("chaosAgentJar") {
    extendsFrom(agentJarScope.get())
    attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named("argos-agent-jar")) }
  }
val sampleAppScope = configurations.dependencyScope("chaosSampleAppScope")
val chaosShopApp =
  configurations.resolvable("chaosShopApp") {
    extendsFrom(sampleAppScope.get())
    attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named("argos-sample-app")) }
  }
val loadAppScope = configurations.dependencyScope("chaosLoadAppScope")
val chaosLoadApp =
  configurations.resolvable("chaosLoadApp") {
    extendsFrom(loadAppScope.get())
    attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named("argos-sample-app")) }
  }

dependencies {
  agentJarScope(project(":agent:agent-dist"))
  sampleAppScope(project(":sample-apps:shop-boot3"))
  loadAppScope(project(":sample-apps:load"))
}

// --- chaosTest ------------------------------------------------------------------------------------
val machineProfile = providers.gradleProperty(TestLayers.MACHINE_PROFILE_PROPERTY).orNull
val chaosProfile = providers.gradleProperty("chaos.profile").getOrElse("short")
val chaosScenarios = providers.gradleProperty("chaos.scenarios").getOrElse("")
val chaosRate = providers.gradleProperty("chaos.rate").getOrElse("")
val chaosPublish = providers.gradleProperty("chaos.publish").map { it.toBoolean() }.getOrElse(false)
val netemImage = providers.gradleProperty("chaos.netem.image").getOrElse("")
val serverBootJar =
  layout.settingsDirectory.file("server/build/libs/argos-server-${project.version}.jar").asFile

tasks.named<Test>("chaosTest") {
  group = "verification"
  description =
    "Chaos scenarios T-CH (18 §11.1). Requires -P${TestLayers.MACHINE_PROFILE_PROPERTY}=<id>; " +
      "-Pchaos.profile=short|full, -Pchaos.scenarios=T-CH-001,..., -Pchaos.rate, -Pchaos.publish=true."
  dependsOn(
    ":server:bootJar",
    ":sample-apps:shop-boot3:sampleLaunchDescriptor",
    ":sample-apps:load:sampleLaunchDescriptor",
  )
  // One run, one stack: the scenario classes share it in a fixed order.
  maxParallelForks = 1
  maxHeapSize = "768m"
  val root = layout.settingsDirectory.asFile
  val reportDir = layout.buildDirectory.dir("reports/chaos").get().asFile
  val tmp = layout.buildDirectory.dir("tmp/chaosTest").get().asFile
  val agentFiles: FileCollection = chaosAgentJar.get()
  val shopFiles: FileCollection = chaosShopApp.get()
  val loadFiles: FileCollection = chaosLoadApp.get()
  // A local copy: the configuration cache cannot keep script objects in the provider below.
  val java21 = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
  inputs.files(agentFiles).withPropertyName("agentJar").withNormalizer(ClasspathNormalizer::class.java)
  inputs.files(shopFiles).withPropertyName("shopApp").withNormalizer(ClasspathNormalizer::class.java)
  inputs.files(loadFiles).withPropertyName("loadApp").withNormalizer(ClasspathNormalizer::class.java)
  systemProperty("argos.repositoryRoot", root.absolutePath)
  systemProperty("java.io.tmpdir", tmp.absolutePath)
  systemProperty("argos.chaos.profile", chaosProfile)
  systemProperty("argos.chaos.scenarios", chaosScenarios)
  systemProperty("argos.chaos.rate", chaosRate)
  systemProperty("argos.chaos.machineProfile", machineProfile ?: "")
  systemProperty("argos.chaos.out", reportDir.absolutePath)
  systemProperty("argos.chaos.netemImage", netemImage)
  systemProperty("argos.chaos.bootJar", serverBootJar.absolutePath)
  if (chaosPublish) {
    systemProperty("argos.chaos.publishDir", root.resolve("docs/bench").absolutePath)
  }
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-Dargos.chaos.agentJar=" + agentFiles.singleFile.absolutePath,
        "-Dargos.chaos.loadJar=" + loadFiles.singleFile.absolutePath,
        // AppLauncher's SampleApp.named and Jdk.of read these (agent-testing harness).
        "-Dargos.test.sampleApp.shop-boot3=" + shopFiles.singleFile.absolutePath,
        "-Dargos.test.java21=" + java21.get().executablePath.asFile.absolutePath,
      )
    }
  )
  // Measurements are never up to date.
  outputs.upToDateWhen { false }
  val taskPath = path
  val profileId = machineProfile
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
