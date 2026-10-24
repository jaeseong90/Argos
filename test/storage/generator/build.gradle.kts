// SPDX-License-Identifier: Apache-2.0
//
// Storage PoC data generator (WO-03, P-4; 13 §15, 18 §9). Development-only.
//   main             topology and distributions of 13 §15, tick-by-tick simulation with the
//                    server receive path (late batches, clock skew guard, late flag), bulk mode
//                    (large date-ordered blocks) and replay mode (WAL -> block -> dedup token,
//                    2 s / 50,000 rows, TPS steps), manifest of expected counts, report, CLI
//   test             no Docker: distributions, invariants (D-12 by construction, midnight, late,
//                    skew), determinism, dedup-token contract, RowBinary encoding, V001 columns
//   integrationTest  loads into a Testcontainers ClickHouse (deploy/clickhouse configuration):
//                    counts = manifest, Q12/Q13-style checks, re-sent blocks deduplicated, replay
// Tasks (not part of check):
//   storageGenerate  runs the generator: -Pscale=0.01 -Pstorage.<key>=<value> (GeneratorConfig keys,
//                    e.g. -Pstorage.mode=replay -Pstorage.target=docker -Pstorage.tps-steps=100,500)
//   storageBench     generation phase of storageBench (18 §9): same, requires
//                    -Pbench.machine-profile=<id>, target docker by default
// Reports go to build/reports/storage-generator/ and build/reports/storage-bench/.

plugins { id("argos.integration-test") }

description = "Argos storage PoC: synthetic telemetry generator, bulk and replay loads (dev-only)."

dependencies { api(project(":test:storage:clickhouse-support")) }

tasks.withType<Test>().configureEach {
  val root = layout.settingsDirectory.asFile
  systemProperty("argos.repositoryRoot", root.absolutePath)
  systemProperty(
    "argos.reportDir",
    layout.buildDirectory.dir("reports/storage-generator-tests").get().asFile.absolutePath,
  )
  inputs
    .dir(layout.settingsDirectory.dir("deploy/clickhouse"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("clickhouseDeployFiles")
}

tasks.named<Test>("integrationTest") {
  // One Docker-heavy class at a time: the machine is shared with other benchmarks.
  maxParallelForks = 1
}

/** `-Pstorage.<key>=<value>` as generator arguments, plus `-Pscale` (18 §9). */
fun generatorArgs(defaults: Map<String, String>): List<String> {
  val values = LinkedHashMap(defaults)
  providers.gradleProperty("scale").orNull?.let { values["scale"] = it }
  providers.gradlePropertiesPrefixedBy("storage.").get().forEach { (key, value) ->
    values[key.removePrefix("storage.")] = value
  }
  return values.map { (k, v) -> "--$k=$v" }
}

val generatorHeap = providers.gradleProperty("storageXmx").getOrElse("2g")

tasks.register<JavaExec>("storageGenerate") {
  group = "storage bench"
  description = "Runs the storage PoC generator (-Pscale, -Pstorage.<key>=<value>)."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass = "io.argos.storage.generator.GeneratorMain"
  maxHeapSize = generatorHeap
  systemProperty("argos.repositoryRoot", layout.settingsDirectory.asFile.absolutePath)
  val out = layout.buildDirectory.dir("reports/storage-generator").get().asFile.absolutePath
  args(generatorArgs(mapOf("out" to out)))
}

tasks.register<JavaExec>("storageBench") {
  group = "storage bench"
  description =
    "Generation phase of storageBench (18 §9): requires -Pbench.machine-profile=<id>; " +
      "-Pscale (default 1), -Pstorage.<key>=<value>."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass = "io.argos.storage.generator.GeneratorMain"
  maxHeapSize = generatorHeap
  systemProperty("argos.repositoryRoot", layout.settingsDirectory.asFile.absolutePath)
  val profile = providers.gradleProperty("bench.machine-profile").orNull
  val out = layout.buildDirectory.dir("reports/storage-bench").get().asFile.absolutePath
  args(
    generatorArgs(
      mapOf("out" to out, "target" to "docker", "machine-profile" to (profile ?: ""))
    )
  )
  doFirst {
    if (profile == null) {
      throw GradleException("storageBench requires -Pbench.machine-profile=<id> (18 §2.2)")
    }
  }
}
