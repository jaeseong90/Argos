// SPDX-License-Identifier: Apache-2.0
//
// argos.java-server: the collector server (19 §3.2, §3.5; ADR-0003 C-28). Java 21 toolchain
// (inherited from argos.java-common), Spring Boot BOM and plugin, and:
//   logging/licenses  Logback (spring-boot-starter-logging, ch.qos.logback: EPL/LGPL) and
//                     jakarta.annotation-api (EPL/GPL) are excluded from every configuration
//                     (ADR-0004 C-43 precedent); the server logs through Log4j 2.
//   bootJar           layered: dependencies, spring-boot-loader, snapshot-dependencies,
//                     application (19 §3.5); build-info.properties for the version endpoints
//                     (19 §7.10) without a build time unless SOURCE_DATE_EPOCH is set (19 §3.7).
//   UI bundle         the :ui build output (ui/dist) is copied into the jar under static/ by
//                     processResources (19 §3.5). -Pargos.ui.skip=true leaves it out and keeps
//                     every :ui task out of the graph.
//   checkFlywayNames  src/main/resources/db/pg holds V<NNN>__<lower_snake>.sql only, contiguous
//                     from 001 (C-28; FlywayNames). Part of check.
//   contractTest      config-key snapshot (T-API-009) and, later, OpenAPI lint/diff and the
//                     permission matrix (18 §2.2). Part of check (root guard anchor).
//   Jib               image from the digest-pinned Temurin 21 JRE, uid:gid 10001:10001, ports 8080
//                     and 9100, OCI labels (19 §3.5; ServerImage). Log and WAL defaults point at
//                     the /logs and /data volumes of 17 §3.2, which must be mounted writable for
//                     uid 10001 (ServerImage KDoc). Only jibDockerBuild is used so far;
//                     multi-arch publishing and signing are WO-44.
// Not yet implemented: generateOpenApi (docs/api/openapi.yaml) -> WO-23.

import io.argos.buildlogic.ArgosProject
import io.argos.buildlogic.argosGitCommit
import io.argos.buildlogic.argosLibs
import io.argos.buildlogic.argosProtocolVersion
import io.argos.buildlogic.library
import io.argos.buildlogic.server.CheckFlywayNames
import io.argos.buildlogic.server.FlywayNames
import io.argos.buildlogic.server.ServerImage
import java.time.Instant
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
  id("argos.java-common")
  id("org.springframework.boot")
  id("com.google.cloud.tools.jib")
}

val catalog = argosLibs

/** Main class of the server (io.argos.server.ServerApplication, 12 §1). */
val serverMainClass = "io.argos.server.ServerApplication"

tasks.withType<JavaCompile>().configureEach { options.release = ArgosProject.BUILD_JDK }

dependencies { "implementation"(platform(catalog.library("spring-boot-bom"))) }

// Logback and jakarta.annotation-api never reach any classpath of the server (checkLicenses covers
// runtimeClasspath; excluding them everywhere keeps tests on the same logging backend).
configurations.configureEach {
  exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
  exclude(group = "ch.qos.logback")
  exclude(group = "jakarta.annotation", module = "jakarta.annotation-api")
}

testing {
  suites {
    // Every suite takes test libraries (JUnit 6, AssertJ, Testcontainers, ...) from the Boot BOM, so
    // compile and runtime classpaths agree. Agent modules stay on JUnit 5 (Java 8).
    withType<JvmTestSuite>().configureEach {
      dependencies { implementation(platform(catalog.library("spring-boot-bom"))) }
    }
    // Config-key snapshot (T-API-009); OpenAPI lint/diff and permission matrix generation later.
    register<JvmTestSuite>("contractTest") {
      dependencies { implementation(project()) }
      targets.configureEach { testTask.configure { shouldRunAfter(tasks.named("test")) } }
    }
  }
}

tasks.named("check") { dependsOn(testing.suites.named("contractTest")) }

// --- boot jar (19 §3.5) and build info (19 §7.10) ---------------------------------------------

val sourceDateEpoch = providers.environmentVariable("SOURCE_DATE_EPOCH").orNull

springBoot {
  mainClass = serverMainClass
  buildInfo {
    // build-info.properties: group, artifact, name, version, plus commit and protocol version. The
    // build time is the commit time from SOURCE_DATE_EPOCH, or left out (reproducible jars).
    excludes = if (sourceDateEpoch.isNullOrBlank()) setOf("time") else emptySet()
    properties {
      if (!sourceDateEpoch.isNullOrBlank()) {
        time = Instant.ofEpochSecond(sourceDateEpoch.trim().toLong()).toString()
      }
      additional.put("commit", argosGitCommit())
      additional.put("protocol", argosProtocolVersion())
    }
  }
}

tasks.named<BootJar>("bootJar") {
  // Default Boot layers, in this order (layers.idx): dependencies, spring-boot-loader,
  // snapshot-dependencies, application.
  layered { enabled = true }
}

// --- UI bundle (19 §3.5) ------------------------------------------------------------------------

val skipUi = providers.gradleProperty("argos.ui.skip").map { it.toBoolean() }.getOrElse(false)

if (!skipUi) {
  // A file collection built by :ui:pnpmBuild (task path, no access to the :ui project model).
  val uiDist = files(layout.settingsDirectory.dir("ui/dist")) { builtBy(":ui:pnpmBuild") }
  tasks.named<ProcessResources>("processResources") { from(uiDist) { into("static") } }
}

// --- PostgreSQL migration file names (C-28) -----------------------------------------------------

val checkFlywayNames =
  tasks.register<CheckFlywayNames>("checkFlywayNames") {
    migrationsDirectory = layout.projectDirectory.dir("src/main/resources/${FlywayNames.RESOURCE_DIRECTORY}")
    marker = layout.buildDirectory.file("flyway/checkFlywayNames.txt")
  }

tasks.named("check") { dependsOn(checkFlywayNames) }

// --- container image (19 §3.5) ------------------------------------------------------------------

val imagePlatforms =
  ServerImage.platforms(providers.gradleProperty("argos.image.platforms").getOrElse(ServerImage.DEFAULT_PLATFORMS))

val imageCreationTime = ServerImage.creationTime(sourceDateEpoch)

val imageLabels =
  argosGitCommit().map { commit ->
    ServerImage.labels(project.version.toString(), commit, ArgosProject.REPOSITORY_URL, imageCreationTime)
  }

jib {
  from {
    image = ServerImage.BASE_IMAGE
    platforms {
      imagePlatforms.forEach { (os, architecture) ->
        platform {
          this.os = os
          this.architecture = architecture
        }
      }
    }
  }
  to { image = "${ServerImage.IMAGE_NAME}:${project.version}" }
  container {
    mainClass = serverMainClass
    user = ServerImage.USER
    ports = ServerImage.PORTS
    workingDirectory = ServerImage.WORKING_DIRECTORY
    appRoot = ServerImage.APP_ROOT
    environment = ServerImage.ENVIRONMENT
    creationTime = imageCreationTime
    filesModificationTime = if (imageCreationTime == "EPOCH") "EPOCH_PLUS_SECOND" else imageCreationTime
    labels = imageLabels
  }
}

// Jib 3.5.4 reads the Project model at execution time; builds that run a jib* task store no
// configuration cache entry (the other builds keep it).
tasks.named { it.startsWith("jib") }.configureEach {
  notCompatibleWithConfigurationCache("Jib tasks use the Project model at execution time")
}
