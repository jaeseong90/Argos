// SPDX-License-Identifier: Apache-2.0
//
// argos.java-server: the collector server (19 §3.2). Java 21 toolchain (inherited from
// argos.java-common), Spring Boot BOM, and the contractTest suite (18 §2.2) wired into check.
// Not yet implemented (no server main class, migrations or API exist in WO-00):
//   bootJar (layered) and Flyway file name validation -> WO-20 (server skeleton, migration runner)
//   generateOpenApi (docs/api/openapi.yaml)            -> WO-23 (query API, OpenAPI)
// 19 does not define the Flyway file name rule yet; WO-20 has to fix it first.

import io.argos.buildlogic.ArgosProject
import io.argos.buildlogic.argosLibs
import io.argos.buildlogic.library

plugins { id("argos.java-common") }

val catalog = argosLibs

tasks.withType<JavaCompile>().configureEach { options.release = ArgosProject.BUILD_JDK }

dependencies { "implementation"(platform(catalog.library("spring-boot-bom"))) }

testing {
  suites {
    // Every suite takes test libraries (JUnit 6, AssertJ, Testcontainers, ...) from the Boot BOM, so
    // compile and runtime classpaths agree. Agent modules stay on JUnit 5 (Java 8).
    withType<JvmTestSuite>().configureEach {
      dependencies { implementation(platform(catalog.library("spring-boot-bom"))) }
    }
    // OpenAPI lint/diff, permission matrix generation, schema and config-key snapshots.
    register<JvmTestSuite>("contractTest") {
      dependencies { implementation(project()) }
      targets.configureEach { testTask.configure { shouldRunAfter(tasks.named("test")) } }
    }
  }
}

tasks.named("check") { dependsOn(testing.suites.named("contractTest")) }
