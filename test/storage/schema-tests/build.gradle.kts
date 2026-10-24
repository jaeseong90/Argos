// SPDX-License-Identifier: Apache-2.0
//
// Schema tests of the storage PoC (WO-03, 13 §3-§5, §7, §17). Development-only.
//   test             no Docker: repository files (credential literals, config variables)
//   integrationTest  Testcontainers ClickHouse with the deploy/clickhouse configuration:
//                    schema apply (empty server, twice), rollup views, dedup token with views,
//                    accounts and limits (SEC-74, SEC-76, T-ST-053), 13 §17 confirmation items,
//                    previous LTS -> bundled LTS upgrade (T-ST-051)
// Evidence values (sizes, settings, timings) go to build/reports/clickhouse-schema/.

plugins { id("argos.integration-test") }

description = "Argos storage PoC: ClickHouse schema, rollup and account tests (dev-only)."

testing {
  suites {
    named<JvmTestSuite>("test") {
      dependencies { implementation(project(":test:storage:clickhouse-support")) }
    }
    named<JvmTestSuite>("integrationTest") {
      dependencies { implementation(project(":test:storage:clickhouse-support")) }
    }
  }
}

tasks.withType<Test>().configureEach {
  val root = layout.settingsDirectory.asFile
  systemProperty("argos.repositoryRoot", root.absolutePath)
  systemProperty(
    "argos.reportDir",
    layout.buildDirectory.dir("reports/clickhouse-schema").get().asFile.absolutePath,
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
