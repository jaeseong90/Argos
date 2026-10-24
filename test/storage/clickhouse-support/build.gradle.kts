// SPDX-License-Identifier: Apache-2.0
//
// ClickHouse support for the storage PoC and its tests (WO-03, 13 §15, 18 §9). Development-only
// (:test: projects never feed a shipped artifact; checkDevOnlyLicenses checks this classpath).
//   ArgosClickHouse   Testcontainers launcher: pinned image (deploy/clickhouse/image.properties),
//                     deploy/clickhouse config.d and users.d, generated passwords, memory budget
//   ClickHouseHttp    dependency-free HTTP client (java.net.http) for the three Argos accounts
//   SchemaRenderer    renders deploy/clickhouse/schema/V*.sql and rollups/*.sql (placeholders)
//   SchemaLint        idempotency rules of the ClickHouse migration runner (13 §13.2)
//   SchemaApplier     lint + apply as argos_migrator

plugins { id("argos.java-common") }

description = "Argos storage PoC: ClickHouse launcher, HTTP client and schema renderer (dev-only)."

dependencies { api(libs.testcontainers.core) }

tasks.withType<Test>().configureEach {
  // The unit tests read deploy/clickhouse (schema, rollups, config); rerun them when it changes.
  val root = layout.settingsDirectory.asFile
  systemProperty("argos.repositoryRoot", root.absolutePath)
  inputs
    .dir(layout.settingsDirectory.dir("deploy/clickhouse"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("clickhouseDeployFiles")
}
