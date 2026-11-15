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
