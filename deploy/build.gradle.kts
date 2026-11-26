// SPDX-License-Identifier: Apache-2.0
//
// Installation packages (19 §3.5, 17 §3-§5). The Docker Compose installation is deploy/compose
// (WO-40; demo profile WO-41). The tar.gz + systemd package (:deploy:tarDist) is WO-76 and the
// air-gapped bundle (:deploy:airgapBundle) WO-76 with its verification in WO-63 (docs/04); until
// then this project has the lifecycle tasks and the Compose smoke run below.
//
//   composeSmoke  end-to-end run of deploy/compose (deploy/compose/test/compose-smoke.sh): builds
//                 the local server image (:server:jibDockerBuild), the agent jar and the shop-boot3
//                 sample app, then for the setup token wizard and the unattended setup runs
//                 argos-env-init.sh, argos-compose.sh up, smoke.sh, token create with a real agent,
//                 and down --volumes. Needs Docker and about 5 GiB of free memory; not part of
//                 check. On a Linux Docker Engine run Gradle as root (argos-env-init.sh gives the
//                 secrets to uid 10001). Script tests without a stack: bash deploy/compose/test/run-all.sh
//   demoSmoke     end-to-end run of the demo profile (deploy/compose/test/demo-smoke.sh, WO-41): builds
//                 the server image and the two demo images (demo app, load generator), then
//                 argos-env-init.sh --demo, `docker compose --profile demo up -d`, the time to the
//                 first XLog point through the API (T-NFR-16, build/reports/demo/first-xlog.md), what
//                 the demo shows, the leak and port checks and `down -v`. -Pdemo.smoke.enforce=true
//                 fails above 60 s (quiet machine), -Pdemo.smoke.long=true also waits for the
//                 error_rate alert (about 9 minutes more). Needs Docker, Node.js and about 7 GiB for
//                 Docker; takes the server-it lock itself; not part of check.
//   backupRoundTrip  backup, restore and recovery of the Compose installation on a real stack
//                 (deploy/compose/test/backup-roundtrip.sh, WO-43): builds the server image, the agent
//                 jar and the shop-boot3 sample app, then with a real agent: backup.sh -> restore.sh
//                 round trip with ClickHouse (T-NFR-20a), restore with another master key refused
//                 without a change (T-SEC-52e), PostgreSQL-only restore older than ClickHouse
//                 (T-NFR-20b), kill -9 and down/up of the server during agent traffic (T-ACC-13 (b)),
//                 argos-compose.sh upgrade and upgrade --rollback with a relabelled local image
//                 (T-NFR-11a), ClickHouse backup on the data disk refused (T-NFR-20c). Needs Docker and
//                 about 6 GiB of free memory; takes the server-it lock itself; not part of check.

import io.argos.buildlogic.argosBash

plugins { base }

description = "Argos APM installation packages (Compose, tar.gz, air-gapped bundle)."

tasks.register<Exec>("composeSmoke") {
  group = "verification"
  description =
    "End-to-end smoke of the Docker Compose installation (deploy/compose): image, env-init, up, " +
      "smoke.sh, agent token and a real agent, down -v; wizard and unattended. Needs Docker."
  dependsOn(":server:jibDockerBuild", ":agent:agent-dist:agentJar", ":sample-apps:shop-boot3:sampleAppJar")
  val root = layout.settingsDirectory.asFile
  val version = project.version.toString()
  workingDir = layout.projectDirectory.asFile
  executable = argosBash().get()
  args("compose/test/compose-smoke.sh")
  environment("ARGOS_SMOKE_AGENT_JAR", root.resolve("agent/agent-dist/build/libs/argos-agent-$version.jar").absolutePath)
  environment("ARGOS_SMOKE_SAMPLE_JAR", root.resolve("sample-apps/shop-boot3/build/sample-app/shop-boot3.jar").absolutePath)
  // The Gradle daemon runs on JDK 21 (gradle/gradle-daemon-jvm.properties); shop-boot3 needs 17+.
  environment("ARGOS_SMOKE_JAVA", File(System.getProperty("java.home"), "bin/java").absolutePath)
  providers.gradleProperty("compose.smoke.modes").orNull?.let { environment("ARGOS_SMOKE_MODES", it.replace(',', ' ')) }
  // Runs containers and measures nothing reusable: never up to date, never cached.
  outputs.upToDateWhen { false }
}

tasks.register<Exec>("backupRoundTrip") {
  group = "verification"
  description =
    "Backup, restore and recovery of the Docker Compose installation on a real stack " +
      "(deploy/compose/test/backup-roundtrip.sh): round trip, wrong master key, point-in-time mismatch, " +
      "kill -9 and down/up, upgrade and rollback, ClickHouse target on the data disk. Needs Docker."
  dependsOn(":server:jibDockerBuild", ":agent:agent-dist:agentJar", ":sample-apps:shop-boot3:sampleAppJar")
  val root = layout.settingsDirectory.asFile
  val version = project.version.toString()
  workingDir = layout.projectDirectory.asFile
  executable = argosBash().get()
  args("compose/test/backup-roundtrip.sh")
  environment("ARGOS_RT_AGENT_JAR", root.resolve("agent/agent-dist/build/libs/argos-agent-$version.jar").absolutePath)
  environment("ARGOS_RT_SAMPLE_JAR", root.resolve("sample-apps/shop-boot3/build/sample-app/shop-boot3.jar").absolutePath)
  // The Gradle daemon runs on JDK 21 (gradle/gradle-daemon-jvm.properties); shop-boot3 needs 17+.
  environment("ARGOS_RT_JAVA", File(System.getProperty("java.home"), "bin/java").absolutePath)
  // Runs containers and measures nothing reusable: never up to date, never cached.
  outputs.upToDateWhen { false }
}

tasks.register<Exec>("demoSmoke") {
  group = "verification"
  description =
    "End-to-end run of the demo profile (deploy/compose/test/demo-smoke.sh): images, env-init --demo, " +
      "docker compose --profile demo up -d, first XLog point (T-NFR-16), demo data checks, down -v. Needs Docker."
  dependsOn(":server:jibDockerBuild", ":sample-apps:demo-app:jibDockerBuild", ":sample-apps:load:jibDockerBuild")
  val root = layout.settingsDirectory.asFile
  workingDir = layout.projectDirectory.asFile
  executable = argosBash().get()
  args("compose/test/demo-smoke.sh")
  environment("ARGOS_DEMO_REPORT_DIR", root.resolve("build/reports/demo").absolutePath)
  if (providers.gradleProperty("demo.smoke.enforce").orNull == "true") environment("ARGOS_DEMO_ENFORCE", "1")
  if (providers.gradleProperty("demo.smoke.long").orNull == "true") environment("ARGOS_DEMO_LONG", "1")
  // Runs containers; the report is a reference value of the machine it ran on: never up to date.
  outputs.upToDateWhen { false }
}
