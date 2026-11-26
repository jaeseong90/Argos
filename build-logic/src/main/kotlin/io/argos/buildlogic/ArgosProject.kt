// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.of

/**
 * Project identifiers decided for open item #14 (ADR-0002) and shared by every build script.
 * Changing one of these is a public-contract change.
 */
object ArgosProject {
  const val PRODUCT_NAME = "Argos APM"
  const val COPYRIGHT_HOLDER = "Argos APM contributors"
  const val MAVEN_GROUP = "io.github.argos-apm"
  const val AGENT_ARTIFACT_ID = "argos-agent"
  const val GITHUB_ORG_URL = "https://github.com/argos-apm"
  const val REPOSITORY_URL = "https://github.com/argos-apm/argos"
  const val INCEPTION_YEAR = "2026"

  /**
   * Agent entry point (10 §4.1). The class itself is added by WO-02; until then the manifest
   * names a class that does not exist yet.
   */
  const val AGENT_PREMAIN_CLASS = "io.argos.agent.AgentPremain"

  /** Path of the agent bootstrap project, the only agent module with zero dependencies (B-1). */
  const val AGENT_BOOTSTRAP_PATH = ":agent:agent-bootstrap"

  /** Java release targeted by every agent module (F-A31, DSN-19-02). */
  const val AGENT_JAVA_RELEASE = 8

  /** JDK that compiles every module and runs server and test-harness tests (DSN-19-02). */
  const val BUILD_JDK = 21

  /**
   * Sample-apps projects published as the demo images argos-demo-app and argos-demo-load (17 §3.9,
   * ADR-0003 C-33, ADR-0004 C-43, WO-41): shipped, so checkLicenses checks their runtime classpath.
   */
  val PUBLISHED_SAMPLE_APPS: Set<String> = setOf(":sample-apps:demo-app", ":sample-apps:load")

  /**
   * Projects that never feed a shipped artifact: the agent test harness, sample apps (except
   * [PUBLISHED_SAMPLE_APPS]) and test tools. Their runtime classpath is checked by
   * checkDevOnlyLicenses instead of checkLicenses (19 §5.5).
   */
  fun isDevelopmentOnly(path: String): Boolean =
    path == ":agent:agent-testing" ||
      (path.startsWith(":sample-apps:") && path !in PUBLISHED_SAMPLE_APPS) ||
      path.startsWith(":test:")
}

/** The root version catalog (gradle/libs.versions.toml). */
val Project.argosLibs: VersionCatalog
  get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
  findLibrary(alias).orElseThrow { GradleException("Missing library '$alias' in libs.versions.toml") }

fun VersionCatalog.versionOf(alias: String): String =
  findVersion(alias)
    .orElseThrow { GradleException("Missing version '$alias' in libs.versions.toml") }
    .requiredVersion

/**
 * The integer protocol version read from proto/PROTOCOL_VERSION (DSN-19-10). The file is the single
 * source; the build fails if it is missing or not a positive integer.
 */
fun Project.argosProtocolVersion(): Provider<String> {
  val file = layout.settingsDirectory.file("proto/PROTOCOL_VERSION")
  return providers.fileContents(file).asText.map { text ->
    val value = text.trim()
    if (!value.matches(Regex("[1-9][0-9]{0,8}"))) {
      throw GradleException(
        "proto/PROTOCOL_VERSION must contain a single positive integer, found '$value'"
      )
    }
    value
  }
}

/**
 * The commit the build was made from. Order: ARGOS_GIT_COMMIT, GITHUB_SHA, `git rev-parse HEAD`,
 * then "unknown" (for example a source archive without git metadata).
 */
fun Project.argosGitCommit(): Provider<String> =
  providers
    .environmentVariable("ARGOS_GIT_COMMIT")
    .orElse(providers.environmentVariable("GITHUB_SHA"))
    .orElse(
      providers.of(GitCommitValueSource::class) {
        parameters.workingDirectory.set(layout.settingsDirectory)
      }
    )

/** Archive base name derived from the project path, e.g. ":agent:agent-core" -> "argos-agent-core". */
fun Project.argosArchivesName(): String = argosArchivesNameOf(path)

/**
 * Archive base name of the project at [projectPath]: ":agent:agent-core" -> "argos-agent-core",
 * ":agent:agent-instrumentation:jdbc" -> "argos-agent-instrumentation-jdbc", ":server" ->
 * "argos-server". Also names the project's component in the agent SBOM (NormalizeSbom).
 */
fun argosArchivesNameOf(projectPath: String): String {
  val joined = projectPath.removePrefix(":").replace(':', '-').replace("agent-agent-", "agent-")
  return "argos-$joined"
}
