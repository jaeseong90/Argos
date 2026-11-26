// SPDX-License-Identifier: Apache-2.0
//
// Agent overhead bench harness (18 §7, DSN-18-07, 01 §7.3, ADR-0004 C-46 and C-49). Formalised by
// WO-05 from the WO-02 P-2 harness (formerly test/agent/overhead). Development-only
// (ArgosProject.isDevelopmentOnly): nothing here is shipped.
//
//   check      compiles the harness and runs its unit tests (statistics, HDR re-aggregation,
//              bootstrap, parsers of /proc, cgroup v2, jcmd and k6 output, verdicts, report
//              template, regression rule). No measurement runs in a build.
//   benchJar   the runnable harness jar build/bench/bench-harness.jar. The measurement runs this
//              jar without a Gradle daemon (bench.sh, bench-run.sh, bench-protocol.sh).
//   benchTest  the full 01 §7.3 protocol on a dedicated bench machine (bench-protocol.sh):
//              ./gradlew :test:bench:benchTest -Pbench.machine-profile=<id> [-Pbench.sets=...]
//              Never on a shared or CI machine; see test/bench/README.md.

import io.argos.buildlogic.argosBash

plugins { id("argos.java-common") }

description = "Agent overhead bench harness, 18 §7 (development only, never shipped)."

tasks.named<Jar>("jar") {
  manifest { attributes("Main-Class" to "io.argos.test.bench.OverheadBench") }
}

// Unit tests read repository files (sample-apps/load/run-bench.sh pins the k6 image; the bench
// scripts' option tables; the weekly workflow .github/workflows/bench.yml, BenchWorkflowTest)
// relative to the root.
tasks.withType<Test>().configureEach {
  systemProperty("argos.repositoryRoot", layout.settingsDirectory.asFile.absolutePath)
  inputs.file(layout.settingsDirectory.file("sample-apps/load/run-bench.sh"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("runBenchScript")
  inputs.file(layout.settingsDirectory.file(".github/workflows/bench.yml"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("benchWorkflow")
  // OtlpBlackHoleTest: the fetch script pins the same OTel version and SHA-256 as the harness.
  inputs.file(layout.settingsDirectory.file("test/bench/fetch-otel-agent.sh"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("fetchOtelAgentScript")
  // NoiseCalibrationTest: the committed WO-02 sets are the noise-calibration evidence.
  inputs.dir(layout.settingsDirectory.dir("docs/bench/wo02-overhead-2026-10-24"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("wo02Sets")
}

val benchJar =
  tasks.register<Copy>("benchJar") {
    group = "bench"
    description = "Copies the runnable harness jar to build/bench/bench-harness.jar."
    from(tasks.named("jar"))
    into(layout.buildDirectory.dir("bench"))
    rename { "bench-harness.jar" }
  }

// Inputs of a measurement: the release agent jar and the sample apps of 18 §7 (bench-protocol.sh
// runs with --no-build, so Gradle builds them first).
val measuredJars =
  listOf(
    ":agent:agent-dist:agentJar",
    ":sample-apps:ext-stub:sampleAppJar",
    ":sample-apps:shop-boot3:sampleAppJar",
    ":sample-apps:shop-heavy:sampleAppJar",
  )

tasks.register<Exec>("benchTest") {
  group = "verification"
  description =
    "Full overhead protocol (01 §7.3, 18 §7) on a dedicated bench machine: requires " +
      "-Pbench.machine-profile=<id>; optional -Pbench.sets=main,startup,... and -Pbench.args=..."
  // Local copies only: the configuration cache serializes the task.
  val profile = providers.gradleProperty("bench.machine-profile").orNull
  // Without a machine profile nothing is built: the task fails at once (doFirst below).
  if (!profile.isNullOrBlank()) {
    dependsOn(benchJar)
    dependsOn(measuredJars)
  }
  val sets = providers.gradleProperty("bench.sets").orNull
  val extra = providers.gradleProperty("bench.args").orNull
  val root = layout.settingsDirectory.asFile
  workingDir = root
  executable = argosBash().get()
  val arguments =
    mutableListOf(
      "test/bench/bench-protocol.sh",
      "--no-build",
      "--machine-profile",
      profile ?: "",
    )
  if (sets != null) arguments += listOf("--sets", sets)
  if (extra != null) arguments += extra.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
  args(arguments)
  // The protocol takes hours and measures this machine: never up to date, never cached.
  outputs.upToDateWhen { false }
  doFirst {
    if (profile.isNullOrBlank()) {
      throw GradleException("benchTest requires -Pbench.machine-profile=<id> (18 §2.2)")
    }
  }
}
