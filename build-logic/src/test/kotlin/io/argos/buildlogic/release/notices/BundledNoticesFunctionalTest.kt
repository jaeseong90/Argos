// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.notices

import io.argos.buildlogic.ThirdPartyNotices
import io.argos.buildlogic.repositoryRoot
import io.argos.buildlogic.writeJar
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * registerBundledNotices (WO-44; O-L04, SEC-77, ADR-0010 C-153) end to end with TestKit and a local
 * Maven repository: licenses from checkLicenses, license texts from the bundled jars or from
 * src/notices, no relocation line, and the About list rendered from argos-deps.txt.
 */
@Tag("req:O-L04")
class BundledNoticesFunctionalTest {
  @TempDir lateinit var dir: File

  // Assembled at run time: a literal copyright line here would make this file third-party code
  // for test/lint/check-notice.sh.
  private val mitCopyright = "Copy" + "right (" + "c) 2020 MIT Lib Authors"

  private val mitText =
    "$mitCopyright\n\n" +
      "Permission is hereby granted, free of charge, to any person obtaining a copy of this software, " +
      "subject to the following conditions:\n\n" +
      "The above copyright notice and this permission notice shall be included in all copies.\n\n" +
      "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND.\n"

  private fun publish(group: String, artifact: String, license: String, entries: Map<String, String>) {
    val base = File(dir, "repo/${group.replace('.', '/')}/$artifact/1.0").apply { mkdirs() }
    File(base, "$artifact-1.0.pom")
      .writeText(
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>" +
          "<groupId>$group</groupId><artifactId>$artifact</artifactId><version>1.0</version>" +
          "<licenses><license><name>$license</name></license></licenses></project>"
      )
    writeJar(File(base, "$artifact-1.0.jar"), mapOf(), entries.mapValues { it.value.toByteArray() })
  }

  private fun fixture(withBsdText: Boolean) {
    listOf("build-logic/allowed-licenses.json", "build-logic/license-overrides.json", "docs/licenses/dev-only.md")
      .forEach { path -> File(repositoryRoot, path).copyTo(File(dir, path), overwrite = true) }
    publish("org.mit", "mit-lib", "MIT", mapOf("META-INF/LICENSE.txt" to mitText, "org/mit/A.class" to "x"))
    publish(
      "org.apache",
      "apache-lib",
      "Apache-2.0",
      mapOf("META-INF/NOTICE" to "Apache Lib\nCopyright 2021 The Apache Lib Authors\n", "org/apache/B.class" to "x"),
    )
    publish("org.bsd", "bsd-lib", "BSD-3-Clause", mapOf("org/bsd/C.class" to "x"))
    File(dir, "src/notices").mkdirs()
    File(repositoryRoot, "server/src/notices/protobuf-java-LICENSE.txt").copyTo(File(dir, "src/notices/bsd-LICENSE.txt"))
    File(dir, "settings.gradle")
      .writeText(
        "dependencyResolutionManagement { repositories { maven { url = uri('repo') } } }\n" +
          "rootProject.name = 'bundled-fixture'\n"
      )
    val spec =
      if (!withBsdText) "{ s -> null }"
      else
        "{ s -> s.licenseText('org.bsd:bsd-lib', '1.0', " +
          "new io.argos.buildlogic.release.notices.UpstreamText('bsd-LICENSE.txt', 'https://example.org/bsd/v1.0/LICENSE', null)) }"
    File(dir, "build.gradle")
      .writeText(
        """
        plugins {
          id 'java-library'
          id 'argos.license-check'
        }
        dependencies {
          implementation 'org.mit:mit-lib:1.0'
          implementation 'org.apache:apache-lib:1.0'
          implementation 'org.bsd:bsd-lib:1.0'
        }
        def notices = io.argos.buildlogic.release.notices.BundledNoticesKt.registerBundledNotices(
          project, 'fixture-1.0.jar', layout.buildDirectory.dir('meta'), ($spec as kotlin.jvm.functions.Function1))
        tasks.register('licenseList', io.argos.buildlogic.release.notices.GenerateLicenseList) {
          depsFile.from(notices.depsFile)
          outputFile.set(layout.buildDirectory.file('about/fixture.json'))
        }
        """
          .trimIndent()
      )
  }

  private fun runner() =
    GradleRunner.create()
      .withProjectDir(dir)
      .withPluginClasspath()
      .withArguments("generateThirdPartyNotices", "generateDepsManifest", "licenseList", "--stacktrace")

  @Test
  @DisplayName(
    "T-BLD-003 license texts come from the bundled jar or src/notices, NOTICE files from the jars, no relocation line; the About list equals argos-deps.txt"
  )
  fun generatesCompleteNotices() {
    fixture(withBsdText = true)
    val result = runner().build()
    assertThat(result.task(":generateThirdPartyNotices")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val deps = File(dir, "build/meta/argos-deps.txt").readText()
    assertThat(deps)
      .contains(
        "org.apache:apache-lib:1.0:Apache-2.0\n",
        "org.bsd:bsd-lib:1.0:BSD-3-Clause\n",
        "org.mit:mit-lib:1.0:MIT\n",
      )
    val notices = File(dir, "build/meta/THIRD-PARTY-NOTICES").readText()
    assertThat(notices)
      .contains("third-party notices for fixture-1.0.jar")
      .doesNotContain("relocated")
      .contains("org.mit:mit-lib:1.0\n  License: MIT\n  License text:\n    $mitCopyright")
      .contains(
        "org.bsd:bsd-lib:1.0\n  License: BSD-3-Clause\n  License text:\n    Source: https://example.org/bsd/v1.0/LICENSE"
      )
      .contains("org.apache:apache-lib:1.0\n  License: Apache-2.0\n  NOTICE:\n    Apache Lib")
    val dependencies = ThirdPartyNotices.parseDependencies(deps)
    assertThat(ThirdPartyNotices.violations(notices, dependencies, listOf("org.apache:apache-lib"))).isEmpty()

    val list = File(dir, "build/about/fixture.json").readText()
    assertThat(LicenseLists.parse(list)).isEqualTo(LicenseLists.fromDependencies(dependencies))
  }

  @Test
  @DisplayName(
    "T-BLD-003 generation fails for a module with a license other than Apache-2.0 whose jar has no license text and no src/notices copy"
  )
  fun failsWithoutLicenseText() {
    fixture(withBsdText = false)
    val result = runner().buildAndFail()
    assertThat(result.task(":generateThirdPartyNotices")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output)
      .contains("whose jar carries no META-INF/LICENSE (O-L04)")
      .contains("org.bsd:bsd-lib:1.0 (BSD-3-Clause)")
      .doesNotContain("org.mit:mit-lib:1.0 (MIT)")
  }
}
