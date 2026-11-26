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
//   contractTest      config-key snapshot (T-API-009), the permission matrix (18 §2.2), and (WO-23)
//                     the OpenAPI contract: committed docs/api/openapi.yaml = generated, the 14 §2.9
//                     lint (T-API-001, ADR-0010 C-151), the oasdiff check (T-API-002). Part of check
//                     (root guard anchor).
//   generateOpenApi   (WO-23, C-28, 19 §3.2) writes docs/api/openapi.yaml: the contract suite's
//                     io.argos.server.api.contract.GenerateOpenApi starts the server's web layer on
//                     loopback without databases (ContractServer), reads springdoc's document as a
//                     viewer and writes it as YAML with a fixed key order and info.version v1
//                     (OpenApiDocument). No Docker, no network beyond loopback.
//   Jib               image from the digest-pinned Temurin 21 JRE, uid:gid 10001:10001, ports 8080
//                     and 9100, OCI labels (19 §3.5; ServerImage). (WO-40) /data, /data/tmp and
//                     /logs ship owned by 10001:10001 with mode 0700 (extraDirectories +
//                     ImageOwnershipExtension), so new named volumes inherit them; HOME, the WAL,
//                     setup token, JWT key, temporary files and logs default to them; JVM flags of
//                     17 §7.2 (heap from JAVA_TOOL_OPTIONS). Locally jibDockerBuild (linux/amd64);
//                     the release workflow (WO-44, release.yml) builds linux/amd64 + linux/arm64
//                     with -Pargos.image.platforms and names the registry image with Jib's
//                     -Djib.to.image / -Djib.to.tags (no registry is configured here). The server
//                     project adds its notices, SBOM and About lists to the image (WO-44).

import io.argos.buildlogic.ArgosProject
import io.argos.buildlogic.argosGitCommit
import io.argos.buildlogic.argosLibs
import io.argos.buildlogic.argosProtocolVersion
import io.argos.buildlogic.library
import io.argos.buildlogic.server.CheckFlywayNames
import io.argos.buildlogic.server.FlywayNames
import io.argos.buildlogic.server.ImageOwnershipExtension
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
    // Config-key snapshot (T-API-009), permission matrix, OpenAPI document, lint and diff (WO-23).
    register<JvmTestSuite>("contractTest") {
      dependencies { implementation(project()) }
      targets.configureEach {
        testTask.configure {
          shouldRunAfter(tasks.named("test"))
          // The committed document and the CI diff script are read by the tests: a change to
          // either reruns the suite (instead of an up-to-date or cached result).
          inputs
            .file(layout.settingsDirectory.file("docs/api/openapi.yaml"))
            .withPropertyName("committedOpenApi")
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .optional()
          inputs
            .file(layout.settingsDirectory.file(".github/scripts/check-openapi-diff.sh"))
            .withPropertyName("openApiDiffScript")
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .optional()
        }
      }
    }
  }
}

tasks.named("check") { dependsOn(testing.suites.named("contractTest")) }

// --- OpenAPI document (WO-23; ADR-0003 C-28, ADR-0010 C-151, 19 §3.2) ---------------------------

val generateOpenApi =
  tasks.register<JavaExec>("generateOpenApi") {
    group = "argos"
    description =
      "Writes docs/api/openapi.yaml: starts the server's web layer on loopback without databases " +
        "(contract suite ContractServer), reads springdoc's document and writes it as YAML with " +
        "sorted keys and info.version v1; the contract test fails when the committed file differs."
    val contractTest = sourceSets.named("contractTest")
    classpath = files(contractTest.map { it.runtimeClasspath })
    mainClass = "io.argos.server.api.contract.GenerateOpenApi"
    val document = layout.settingsDirectory.file("docs/api/openapi.yaml").asFile
    args(document.absolutePath)
    systemProperty("argos.repositoryRoot", layout.settingsDirectory.asFile.absolutePath)
    // Temporary files and the web layer's log stay below build/ (as for the test suites).
    systemProperty("java.io.tmpdir", temporaryDir.absolutePath)
    systemProperty("LOG_PATH", layout.buildDirectory.dir("generate-openapi-logs").get().asFile.absolutePath)
    doFirst { temporaryDir.mkdirs() }
    outputs.file(document)
    // The document depends on every controller and type of the server: always regenerate.
    outputs.upToDateWhen { false }
  }

// A build that regenerates the document and runs the contract suite reads the new file.
tasks.named("contractTest") { mustRunAfter(generateOpenApi) }

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

// Writable directories of the image (ADR-0003 note on 17 §16 #3, WO-40): empty directories below
// build/ that extraDirectories adds with mode 0700 and ImageOwnershipExtension gives to 10001:10001.
val imageDirectories = layout.buildDirectory.dir("jib/image-directories")

val prepareImageDirectories =
  tasks.register("prepareImageDirectories") {
    group = "argos"
    description = "Creates the empty writable directories of the server image (/data, /data/tmp, /logs)."
    val root = imageDirectories
    val paths = ServerImage.WRITABLE_DIRECTORIES
    outputs.dir(root)
    doLast {
      val dir = root.get().asFile
      dir.deleteRecursively()
      paths.forEach { path ->
        val target = dir.resolve(path.removePrefix("/"))
        target.mkdirs()
        check(target.isDirectory) { "Cannot create $target" }
      }
    }
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
  // Local name; the release workflow overrides it with -Djib.to.image/-Djib.to.tags (WO-44).
  to { image = "${ServerImage.IMAGE_NAME}:${project.version}" }
  container {
    mainClass = serverMainClass
    user = ServerImage.USER
    ports = ServerImage.PORTS
    workingDirectory = ServerImage.WORKING_DIRECTORY
    appRoot = ServerImage.APP_ROOT
    environment = ServerImage.ENVIRONMENT
    jvmFlags = ServerImage.JVM_FLAGS
    creationTime = imageCreationTime
    filesModificationTime = if (imageCreationTime == "EPOCH") "EPOCH_PLUS_SECOND" else imageCreationTime
    labels = imageLabels
  }
  extraDirectories {
    paths {
      path {
        setFrom(imageDirectories.get().asFile)
        into = "/"
      }
    }
    permissions = ServerImage.WRITABLE_DIRECTORIES.associateWith { ServerImage.DIRECTORY_MODE }
  }
  pluginExtensions {
    pluginExtension {
      setImplementation(ImageOwnershipExtension::class.java.name)
      setProperties(
        mapOf(
          ImageOwnershipExtension.PATHS to ServerImage.WRITABLE_DIRECTORIES.joinToString(","),
          ImageOwnershipExtension.OWNERSHIP to ServerImage.USER,
        )
      )
    }
  }
}

// Jib 3.5.4 reads the Project model at execution time; builds that run a jib* task store no
// configuration cache entry (the other builds keep it).
tasks.named { it.startsWith("jib") }.configureEach {
  notCompatibleWithConfigurationCache("Jib tasks use the Project model at execution time")
  dependsOn(prepareImageDirectories)
}
