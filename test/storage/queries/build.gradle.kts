// SPDX-License-Identifier: Apache-2.0
//
// Storage PoC queries (WO-03, P-4; 13 §6.3, §15; 18 §9). Development-only.
//   Q01.sql..Q20.sql, params.sql   the queries and procedure statements (this directory)
//   main             catalog, parameter probes, runner (cold 1 + warm 3, timeouts,
//                    system.query_log), procedures Q14-Q20, CI dataset, Markdown report, CLI
//   test             no Docker: catalog rules, criteria, bucket estimate, row encoding, report
//   integrationTest  real ClickHouse (Testcontainers): T-ST-030..033 (Q12, Q13, Q15, Q16
//                    consistency 0), T-ST-053 (argos_reader limits, system.query_log, DDL refused),
//                    every query and procedure on the small CI dataset
//   storageQueries   runs the catalog against a server (-Pqueries.args="..."; see README.md)
// Reports go to build/reports/storage-queries/.

plugins { id("argos.integration-test") }

description = "Argos storage PoC: Q01-Q20, runner, report generator and consistency tests (dev-only)."

dependencies { implementation(project(":test:storage:clickhouse-support")) }

testing {
  suites {
    named<JvmTestSuite>("integrationTest") {
      dependencies {
        implementation(project(":test:storage:clickhouse-support"))
        // The PoC generator (13 §15) loads the CI dataset; the runner's own deterministic fixture
        // (CiDataset) remains selectable with -Pqueries.dataset=fixture.
        implementation(project(":test:storage:generator"))
      }
    }
  }
}

tasks.withType<Test>().configureEach {
  val root = layout.settingsDirectory.asFile
  systemProperty("argos.repositoryRoot", root.absolutePath)
  systemProperty(
    "argos.reportDir",
    layout.buildDirectory.dir("reports/storage-queries").get().asFile.absolutePath,
  )
  inputs
    .dir(layout.settingsDirectory.dir("deploy/clickhouse"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("clickhouseDeployFiles")
  inputs
    .files(fileTree(layout.projectDirectory) { include("*.sql") })
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("querySqlFiles")
}

tasks.named<Test>("integrationTest") {
  // One Docker-heavy class at a time: the machine is shared with other benchmarks.
  maxParallelForks = 1
  // The CI dataset is generated in the test JVM (2 days of rows encoded per table).
  maxHeapSize = "1g"
  // -Pqueries.dataset=fixture: the runner's own deterministic dataset instead of the generator.
  providers.gradleProperty("queries.dataset").orNull?.let {
    systemProperty("argos.queries.dataset", it)
  }
  // -Pqueries.profile=ci: the generator's 1/100 profile (18 §9, WO-03 automatic criterion) instead
  // of the small default (2 days at 1 TPS); needs a few GiB of Docker memory and several minutes.
  providers.gradleProperty("queries.profile").orNull?.let {
    systemProperty("argos.queries.profile", it)
    maxHeapSize = "2g" // the generator encodes bulk blocks of up to 500,000 rows in memory
  }
}

// Runs the query catalog against a ClickHouse server and writes the Markdown report
// (docs/poc/clickhouse-benchmark.md input). Not part of check. Example:
//   ./gradlew :test:storage:queries:storageQueries -Pqueries.args="--volume argos-wo03-bench --memory-gib 5"
tasks.register<JavaExec>("storageQueries") {
  group = "verification"
  description = "Runs Q01-Q20 against a ClickHouse server and writes build/reports/storage-queries."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass = "io.argos.storage.queries.QueryBench"
  val root = layout.settingsDirectory.asFile
  systemProperty("argos.repositoryRoot", root.absolutePath)
  val out = layout.buildDirectory.dir("reports/storage-queries/bench").get().asFile.absolutePath
  val extra = providers.gradleProperty("queries.args").getOrElse("")
  // -Pqueries.cold="command:<shell>": the cold strategy as one argument (it may contain spaces).
  val cold = providers.gradleProperty("queries.cold").orNull
  args(
    listOf("--out", out) +
      extra.split(' ').filter { it.isNotBlank() } +
      (if (cold == null) listOf() else listOf("--cold", cold))
  )
}
