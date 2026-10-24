// SPDX-License-Identifier: Apache-2.0
//
// Argos APM monorepo (docs/19-repo-build-release-design.md §2, §3.1).

pluginManagement {
  // Convention plugins (argos.java-common, argos.java-agent, ...). See build-logic/.
  includeBuild("build-logic")
  repositories {
    // Plugins only. Internal mirrors are configured per runner via init.gradle, never here.
    gradlePluginPortal()
  }
}

plugins {
  // JDK toolchain auto-provisioning (DSN-19-02). Version catalogs are not available in the
  // settings plugins block, so this version is pinned here (see gradle/libs.versions.toml).
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  // Dependencies come from Maven Central only (19 §3.1). Projects may not add repositories.
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories { mavenCentral() }
}

rootProject.name = "argos"

// Shared protocol definitions (argos.v1) and PROTOCOL_VERSION.
include(":proto")

// Agent (Java 8 target). One Gradle project per instrumentation package (02 §4.1, 10 §2).
include(":agent:agent-bootstrap")
include(":agent:agent-core")

val instrumentationModules =
  listOf(
    // Framework shared by all instrumentation modules (InstrumentationModule, SafeMatchers, ...).
    "api",
    "servlet",
    "spring-webmvc",
    "jdbc",
    "pool",
    "executor",
    "servlet-async",
    "scheduler",
    "logging",
    "custom",
    "httpclient-jdk",
    "httpclient-apache4",
    "httpclient-apache5",
    "httpclient-okhttp",
    "httpclient-jdk11",
    "httpclient-reactornetty",
  )

instrumentationModules.forEach { include(":agent:agent-instrumentation:$it") }

include(":agent:agent-dist")
include(":agent:agent-testing")

// Collector server (Java 21, Spring Boot single module).
include(":server")

// React UI, built by pnpm through Gradle Exec tasks.
include(":ui")

// Installation packages (tarDist, airgapBundle: WO-40).
include(":deploy")

// Sample apps and their tools (18 §4, C-18, WO-01). Development-only: never shipped
// (ArgosProject.isDevelopmentOnly), built with the argos.sample-app convention plugin.
//   common            framework-neutral endpoint catalog, data access, seeds, switches (Java 8)
//   ext-stub          outgoing-call target (normal, delay, no response, 5xx, redirect)
//   smoke             smoke runner behind every app's sampleSmoke task
//   shop-*            the apps of 18 §4.1 (WAR apps use the 18 §4.1 names shop-war-*)
//   load              demo load generator (allow-listed licenses only, ADR-0003 C-33) and bench.js
val sampleApps =
  listOf(
    "common",
    "ext-stub",
    "smoke",
    "shop-boot2",
    "shop-boot3",
    "shop-boot4",
    "shop-heavy",
    "shop-batch",
    "shop-war-javax",
    "shop-war-jakarta",
    "load",
  )

sampleApps.forEach { include(":sample-apps:$it") }
