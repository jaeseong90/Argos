// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * checkLicenses / checkDevOnlyLicenses end to end (WO-00 acceptance: a GPL dependency makes
 * checkLicenses fail). Each test is a throwaway build that applies the real argos.license-check
 * plugin, the real build-logic/allowed-licenses.json, license-overrides.json and
 * docs/licenses/dev-only.md, and resolves fake modules from a local file repository (no network).
 */
@Tag("req:O-L03")
class LicenseCheckFunctionalTest {
  @TempDir lateinit var project: File

  private val repo: File
    get() = File(project, "repo")

  @BeforeEach
  fun setUp() {
    listOf("build-logic/allowed-licenses.json", "build-logic/license-overrides.json", "docs/licenses/dev-only.md")
      .forEach { path -> File(repositoryRoot, path).copyTo(File(project, path)) }
    File(project, "settings.gradle")
      .writeText(
        """
        dependencyResolutionManagement {
          repositories { maven { url = uri('repo') } }
        }
        rootProject.name = 'license-fixture'
        """
          .trimIndent()
      )
    publish("com.example", "gpl-lib", "1.0", license("GNU General Public License v3.0", "https://www.gnu.org/licenses/gpl-3.0.txt"))
    publish("com.example", "apache-lib", "1.0", license("The Apache Software License, Version 2.0", null))
    publish("com.example", "mit-lib", "1.0", license(null, "https://opensource.org/licenses/MIT"))
    publish(
      "com.example",
      "dual-lib",
      "1.0",
      license("Eclipse Public License - v 2.0", null) + license("Apache License, Version 2.0", null),
    )
    publish("com.example", "parent", "1.0", license("BSD-3-Clause", null), packaging = "pom")
    publish("com.example", "inherits-lib", "1.0", "", parent = "com.example:parent:1.0")
    publish("com.example", "apache-parent", "1.0", license("Apache-2.0", null), packaging = "pom")
    publish("com.example", "gpl-child", "1.0", license("GPL-3.0-only", null), parent = "com.example:apache-parent:1.0")
    publish("com.example", "nolicense-lib", "1.0", "")
    publish("com.h2database", "h2", "2.0", license("MPL 2.0", null) + license("EPL 1.0", null))
  }

  private fun license(name: String?, url: String?): String =
    "<license>" + (name?.let { "<name>$it</name>" } ?: "") + (url?.let { "<url>$it</url>" } ?: "") + "</license>"

  private fun publish(
    group: String,
    artifact: String,
    version: String,
    licenses: String,
    parent: String? = null,
    packaging: String = "jar",
  ) {
    val dir = File(repo, "${group.replace('.', '/')}/$artifact/$version").apply { mkdirs() }
    val parentXml =
      parent?.split(':')?.let { (g, a, v) ->
        "<parent><groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version></parent>"
      } ?: ""
    File(dir, "$artifact-$version.pom")
      .writeText(
        """<?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>$parentXml
          <groupId>$group</groupId><artifactId>$artifact</artifactId><version>$version</version>
          <packaging>$packaging</packaging>
          ${if (licenses.isEmpty()) "" else "<licenses>$licenses</licenses>"}
        </project>
        """
          .trimIndent()
      )
    if (packaging == "jar") writeJar(File(dir, "$artifact-$version.jar"), mapOf(), emptyMap())
  }

  private fun buildFile(dependencies: String) {
    File(project, "build.gradle")
      .writeText(
        """
        plugins {
          id 'java-library'
          id 'argos.license-check'
        }
        group = 'org.example.fixture'
        dependencies {
        $dependencies
        }
        """
          .trimIndent()
      )
  }

  private fun runner(vararg tasks: String): GradleRunner =
    GradleRunner.create()
      .withProjectDir(project)
      .withPluginClasspath()
      .withArguments(tasks.toList() + "--stacktrace")

  private fun BuildResult.outcome(task: String) = task(task)?.outcome

  @Test
  @DisplayName("T-BLD-007 checkLicenses fails when a shipped (runtime) dependency is GPL-3.0")
  fun failsOnGplRuntimeDependency() {
    buildFile("implementation 'com.example:gpl-lib:1.0'")
    val result = runner("checkLicenses").buildAndFail()
    assertThat(result.outcome(":checkLicenses")).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output)
      .contains("checkLicenses: 1 shipped dependency has no allowed license (O-L03):")
      .contains("com.example:gpl-lib:1.0 [runtimeClasspath]: GPL-3.0-only")
      .contains("declared: 'GNU General Public License v3.0' <https://www.gnu.org/licenses/gpl-3.0.txt> from pom")
  }

  @Test
  @DisplayName("T-BLD-007 checkLicenses passes Apache-2.0, MIT (URL only), dual-licensed and parent-inherited modules")
  fun passesAllowedLicenses() {
    buildFile(
      """
      implementation 'com.example:apache-lib:1.0'
      implementation 'com.example:mit-lib:1.0'
      implementation 'com.example:dual-lib:1.0'
      implementation 'com.example:inherits-lib:1.0'
      """
        .trimIndent()
    )
    val result = runner("checkLicenses").build()
    assertThat(result.outcome(":checkLicenses")).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).contains("checkLicenses: 4 shipped third-party module(s) checked in runtimeClasspath")
    assertThat(File(project, "build/reports/argos-licenses/runtimeClasspath.properties").readText())
      .contains("com.example:apache-lib:1.0=Apache-2.0")
      .contains("com.example:mit-lib:1.0=MIT")
      .contains("com.example:dual-lib:1.0=(Apache-2.0) OR (EPL-2.0)")
      .contains("com.example:inherits-lib:1.0=BSD-3-Clause")
  }

  @Test
  @DisplayName("T-BLD-007 a child POM license replaces the parent's (GPL child of an Apache parent fails)")
  fun childLicenseWins() {
    buildFile("implementation 'com.example:gpl-child:1.0'")
    val result = runner("checkLicenses").buildAndFail()
    assertThat(result.output).contains("com.example:gpl-child:1.0 [runtimeClasspath]: GPL-3.0-only")
  }

  @Test
  @DisplayName("T-BLD-007 an unknown license fails until an override with a source URL maps it")
  fun unknownLicenseNeedsOverride() {
    buildFile("implementation 'com.example:nolicense-lib:1.0'")
    val unknown = runner("checkLicenses").buildAndFail()
    assertThat(unknown.output).contains("com.example:nolicense-lib:1.0 [runtimeClasspath]: UNKNOWN (no license metadata)")

    File(project, "build-logic/license-overrides.json")
      .writeText(
        """{"overrides":[{"module":"com.example:nolicense-lib","license":"MIT",""" +
          """"source":"https://example.org/nolicense-lib/LICENSE"}]}"""
      )
    val overridden = runner("checkLicenses").build()
    assertThat(overridden.outcome(":checkLicenses")).isEqualTo(TaskOutcome.SUCCESS)

    File(project, "build-logic/license-overrides.json")
      .writeText("""{"overrides":[{"module":"com.example:nolicense-lib","license":"MIT"}]}""")
    val noSource = runner("checkLicenses").buildAndFail()
    assertThat(noSource.output).contains("needs a 'source' URL")
  }

  @Test
  @DisplayName("T-BLD-007 checkDevOnlyLicenses fails on an unlisted GPL test dependency, passes a listed one")
  fun devOnlyList() {
    buildFile("testImplementation 'com.example:gpl-lib:1.0'")
    val unlisted = runner("checkDevOnlyLicenses").buildAndFail()
    assertThat(unlisted.output)
      .contains("checkDevOnlyLicenses: 1 test-only dependency has a license outside the allow-list")
      .contains("com.example:gpl-lib:1.0 [testRuntimeClasspath]: GPL-3.0-only")

    buildFile("testImplementation 'com.h2database:h2:2.0'")
    val listed = runner("checkDevOnlyLicenses", "checkLicenses").build()
    assertThat(listed.outcome(":checkDevOnlyLicenses")).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(listed.output).contains("1 listed in docs/licenses/dev-only.md")
  }

  @Test
  @DisplayName("T-BLD-007 a dev-only listing does not allow the component in a shipped configuration")
  fun devOnlyDoesNotExemptShipped() {
    buildFile("implementation 'com.h2database:h2:2.0'")
    val result = runner("checkLicenses").buildAndFail()
    assertThat(result.output).contains("com.h2database:h2:2.0 [runtimeClasspath]: (EPL-1.0) OR (MPL-2.0)")
  }
}
