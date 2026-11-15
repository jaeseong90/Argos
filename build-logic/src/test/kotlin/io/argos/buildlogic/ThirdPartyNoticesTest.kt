// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import io.argos.buildlogic.ThirdPartyNotices.Dependency
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * THIRD-PARTY-NOTICES rules (O-L04, T-BLD-003): modules whose license is not Apache-2.0 carry a
 * copyright line and their license text, projects with a NOTICE file carry it (Apache-2.0 §4(d)),
 * and the texts copied from upstream belong to the bundled version ([GenerateThirdPartyNotices],
 * [CheckThirdPartyNotices], agent/agent-dist/src/notices).
 */
@Tag("req:O-L04")
class ThirdPartyNoticesTest {
  @TempDir lateinit var dir: File

  /** The protobuf-java BSD-3-Clause text the agent jar ships (verbatim upstream copy). */
  private val protobufLicense: String =
    File(repositoryRoot, "agent/agent-dist/src/notices/protobuf-java-LICENSE.txt").readText()

  private val bsd = Dependency("com.google.protobuf", "protobuf-java", "4.36.2", "BSD-3-Clause")
  private val apache = Dependency("io.netty", "netty-common", "4.1.139.Final", "Apache-2.0")

  private fun section(dependency: Dependency, vararg body: String): String =
    buildString {
      append("${dependency.group}:${dependency.artifact}:${dependency.version}\n")
      append("  License: ${dependency.license}\n")
      body.forEach { append(it).append('\n') }
    }

  private fun indented(text: String) = text.trim().lines().joinToString("\n") { if (it.isBlank()) "" else "    $it" }

  private fun notices(vararg sections: String) = "Header\n" + "=".repeat(72) + "\n\n" + sections.joinToString("\n")

  @Test
  @DisplayName("T-BLD-003 O-L04: a BSD-3-Clause module passes only with its copyright line and the full license text")
  fun bsdModuleNeedsCopyrightAndText() {
    val complete = notices(section(bsd, "  License text:", indented(protobufLicense)))
    assertThat(ThirdPartyNotices.violations(complete, listOf(bsd), emptyList())).isEmpty()

    val licenseOnly = notices(section(bsd))
    assertThat(ThirdPartyNotices.violations(licenseOnly, listOf(bsd), emptyList()))
      .singleElement()
      .asString()
      .contains("com.google.protobuf:protobuf-java:4.36.2", "the BSD-3-Clause license text is missing")

    val withoutCopyright =
      notices(section(bsd, "  License text:", indented(protobufLicense.replace("Copyright 2008 Google Inc.", ""))))
    assertThat(ThirdPartyNotices.violations(withoutCopyright, listOf(bsd), emptyList()))
      .singleElement()
      .asString()
      .contains("no copyright line for BSD-3-Clause")

    // The disclaimer is part of what condition 2 requires to be reproduced.
    val withoutDisclaimer =
      notices(section(bsd, "  License text:", indented(protobufLicense.substringBefore("THIS SOFTWARE IS PROVIDED"))))
    assertThat(ThirdPartyNotices.violations(withoutDisclaimer, listOf(bsd), emptyList()))
      .singleElement()
      .asString()
      .contains("\"THIS SOFTWARE IS PROVIDED BY\"", "\"ARE DISCLAIMED\"")
  }

  @Test
  @DisplayName(
    "T-BLD-003 O-L04: Apache-2.0 and 'Apache-2.0 OR MIT' need no license text; 'Apache-2.0 AND MIT', MIT and unknown licenses do"
  )
  fun licenseExpressions() {
    val dual = Dependency("org.example", "dual", "1.0", "Apache-2.0 OR MIT")
    val both = Dependency("org.example", "both", "1.0", "Apache-2.0 AND MIT")
    val mit = Dependency("org.example", "mit", "1.0", "MIT")
    val unknown = Dependency("org.example", "unknown", "1.0", "UNKNOWN")
    val text = notices(section(apache), section(dual), section(both), section(mit), section(unknown))
    assertThat(ThirdPartyNotices.violations(text, listOf(apache, dual, both, mit, unknown), emptyList()))
      .containsExactly(
        "org.example:both:1.0 (Apache-2.0 AND MIT): the MIT license text is missing (expected: " +
          "\"Permission is hereby granted, free of charge, to any person obtaining a copy\"; " +
          "\"The above copyright notice and this permission notice shall be included\"; " +
          "\"THE SOFTWARE IS PROVIDED \"AS IS\"\") (O-L04)",
        "org.example:mit:1.0 (MIT): the MIT license text is missing (expected: " +
          "\"Permission is hereby granted, free of charge, to any person obtaining a copy\"; " +
          "\"The above copyright notice and this permission notice shall be included\"; " +
          "\"THE SOFTWARE IS PROVIDED \"AS IS\"\") (O-L04)",
        "org.example:unknown:1.0 (UNKNOWN): no license-text phrases are known for UNKNOWN " +
          "(add them to ThirdPartyNotices.LICENSE_BODY_MARKERS) (O-L04)",
      )

    // An MIT text written as a source comment (Netty's license/LICENSE.slf4j.txt) is recognised.
    val slf4j = File(repositoryRoot, "agent/agent-dist/src/notices/netty-LICENSE.slf4j.txt").readText()
    assertThat(
        ThirdPartyNotices.violations(notices(section(mit, "  License text:", indented(slf4j))), listOf(mit), emptyList())
      )
      .isEmpty()
  }

  @Test
  @DisplayName("T-BLD-003 O-L04: every allow-listed license other than Apache-2.0 has license-text phrases")
  fun everyAllowedLicenseHasMarkers() {
    val allowed =
      Regex("\"spdx\"\\s*:\\s*\"([^\"]+)\"")
        .findAll(File(repositoryRoot, "build-logic/allowed-licenses.json").readText())
        .map { it.groupValues[1] }
        .toList()
    assertThat(allowed).contains("Apache-2.0", "BSD-3-Clause", "MIT")
    assertThat(ThirdPartyNotices.LICENSE_BODY_MARKERS.keys).containsAll(allowed - "Apache-2.0")
  }

  @Test
  @DisplayName("T-BLD-003 O-L04 Apache-2.0 4(d): modules of a NOTICE project carry it inline or through the shared text")
  fun noticeRequirement() {
    val grpcApi = Dependency("io.grpc", "grpc-api", "1.85.1", "Apache-2.0")
    val grpcCore = Dependency("io.grpc", "grpc-core", "1.85.1", "Apache-2.0")
    val byteBuddy = Dependency("net.bytebuddy", "byte-buddy", "1.18.15", "Apache-2.0")
    val shared =
      ThirdPartyNotices.SEPARATOR + "\n" + ThirdPartyNotices.sharedNoticeHeading("io.grpc") + "\n\n" +
        "  Copyright 2014 The gRPC Authors\n"
    val complete =
      notices(
        section(grpcApi, ThirdPartyNotices.noticeReference("io.grpc")),
        section(grpcCore, ThirdPartyNotices.noticeReference("io.grpc")),
        section(byteBuddy, "  NOTICE:", "    Copyright 2014 - Present Rafael Winterhalter"),
        shared,
      )
    val required = listOf("io.grpc", "net.bytebuddy:byte-buddy")
    val all = listOf(grpcApi, grpcCore, byteBuddy)
    assertThat(ThirdPartyNotices.violations(complete, all, required)).isEmpty()

    // Reference without the shared text, module without any NOTICE, pattern matching nothing.
    val broken =
      notices(
        section(grpcApi, ThirdPartyNotices.noticeReference("io.grpc")),
        section(grpcCore, ThirdPartyNotices.noticeReference("io.grpc")),
        section(byteBuddy),
      )
    assertThat(ThirdPartyNotices.violations(broken, all, required + "io.perfmark:perfmark-api"))
      .containsExactly(
        "io.grpc:grpc-api:1.85.1 (Apache-2.0): the original NOTICE text is missing (Apache-2.0 4(d))",
        "io.grpc:grpc-core:1.85.1 (Apache-2.0): the original NOTICE text is missing (Apache-2.0 4(d))",
        "net.bytebuddy:byte-buddy:1.18.15 (Apache-2.0): the original NOTICE text is missing (Apache-2.0 4(d))",
        "NOTICE required for 'io.perfmark:perfmark-api', but no bundled module matches it (stale entry)",
      )
  }

  @Test
  @DisplayName("T-BLD-003 O-L04: every module of argos-deps.txt has a section with its version and license")
  fun sectionsMatchDependencies() {
    val deps =
      ThirdPartyNotices.parseDependencies(
        "# comment\n" +
          "io.netty:netty-common:4.1.139.Final:Apache-2.0\n" +
          "io.netty:netty-buffer:4.1.139.Final:Apache-2.0\n" +
          "org.jctools:jctools-core:4.0.7:Apache-2.0\n"
      )
    val text =
      notices(
        section(apache),
        section(Dependency("io.netty", "netty-buffer", "4.1.140.Final", "Apache-2.0")),
        section(Dependency("org.jctools", "jctools-core", "4.0.7", "MIT")),
      )
    assertThat(ThirdPartyNotices.violations(text, deps, emptyList()))
      .containsExactly(
        "io.netty:netty-buffer:4.1.139.Final (Apache-2.0): THIRD-PARTY-NOTICES names version 4.1.140.Final",
        "org.jctools:jctools-core:4.0.7 (Apache-2.0): THIRD-PARTY-NOTICES states license 'MIT'",
      )
    assertThat(ThirdPartyNotices.violations(notices(section(apache)), deps.take(2), emptyList()))
      .containsExactly("io.netty:netty-buffer:4.1.139.Final (Apache-2.0): no section in THIRD-PARTY-NOTICES")
  }

  // --- generateThirdPartyNotices + checkThirdPartyNotices end to end (TestKit, local repository) ---

  private fun publish(group: String, artifact: String, version: String) {
    val base = File(dir, "repo/${group.replace('.', '/')}/$artifact/$version").apply { mkdirs() }
    File(base, "$artifact-$version.pom")
      .writeText(
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>" +
          "<groupId>$group</groupId><artifactId>$artifact</artifactId><version>$version</version></project>"
      )
    writeJar(File(base, "$artifact-$version.jar"), mapOf(), emptyMap())
  }

  /** A build that bundles a BSD-3-Clause module and two modules of a project with a NOTICE file. */
  private fun fixture(withLicenseText: Boolean, licenseTextVersion: String) {
    listOf("build-logic/allowed-licenses.json", "build-logic/license-overrides.json", "docs/licenses/dev-only.md")
      .forEach { path -> File(repositoryRoot, path).copyTo(File(dir, path), overwrite = true) }
    publish("org.bsd", "bsd-lib", "1.0")
    publish("org.shared", "a", "2.0")
    publish("org.shared", "b", "2.0")
    File(dir, "notices").mkdirs()
    File(dir, "notices/bsd-LICENSE.txt").writeText(protobufLicense)
    File(dir, "notices/shared-NOTICE.txt").writeText("Copyright 2020 The Shared Authors\n\nApache-2.0 NOTICE text.\n")
    File(dir, "settings.gradle")
      .writeText(
        "dependencyResolutionManagement { repositories { maven { url = uri('repo') } } }\n" +
          "rootProject.name = 'notices-fixture'\n"
      )
    val licenseText =
      if (!withLicenseText) ""
      else "licenseTexts.put('org.bsd:bsd-lib', providers.fileContents(layout.projectDirectory.file('notices/bsd-LICENSE.txt')).asText)"
    File(dir, "build.gradle")
      .writeText(
        """
        plugins {
          id 'java-library'
          id 'argos.license-check'
        }
        dependencies {
          implementation 'org.bsd:bsd-lib:1.0'
          implementation 'org.shared:a:2.0'
          implementation 'org.shared:b:2.0'
        }
        def root = configurations.runtimeClasspath.incoming.resolutionResult.rootComponent
        def licenseMap = ['org.bsd:bsd-lib': 'BSD-3-Clause', 'org.shared:a': 'Apache-2.0', 'org.shared:b': 'Apache-2.0']
        def notices = tasks.register('notices', io.argos.buildlogic.GenerateThirdPartyNotices) {
          runtimeRoot.set(root)
          artifactName.set('fixture.jar')
          licenses.set(licenseMap)
          $licenseText
          noticeTexts.put('org.shared', providers.fileContents(layout.projectDirectory.file('notices/shared-NOTICE.txt')).asText)
          textVersions.set(['org.bsd:bsd-lib': '$licenseTextVersion', 'org.shared': '2.0'])
          outputFile.set(layout.buildDirectory.file('meta/THIRD-PARTY-NOTICES'))
        }
        def deps = tasks.register('deps', io.argos.buildlogic.GenerateDepsManifest) {
          runtimeRoot.set(root)
          artifactName.set('fixture.jar')
          licenses.set(licenseMap)
          outputFile.set(layout.buildDirectory.file('meta/argos-deps.txt'))
        }
        def fixtureJar = tasks.register('fixtureJar', Jar) {
          archiveFileName.set('fixture.jar')
          destinationDirectory.set(layout.buildDirectory.dir('fixture'))
          into('META-INF') { from(notices, deps) }
        }
        tasks.register('checkNotices', io.argos.buildlogic.CheckThirdPartyNotices) {
          jar.set(fixtureJar.flatMap { it.archiveFile })
          noticeRequired.set(['org.shared'])
          reportFile.set(layout.buildDirectory.file('report.txt'))
        }
        """
          .trimIndent()
      )
  }

  private fun runner() =
    GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments("checkNotices", "--stacktrace")

  @Test
  @DisplayName(
    "T-BLD-003 O-L04: the generated THIRD-PARTY-NOTICES with the license text and a shared NOTICE passes checkThirdPartyNotices"
  )
  fun generatedNoticesPassTheCheck() {
    fixture(withLicenseText = true, licenseTextVersion = "1.0")
    val result = runner().build()
    assertThat(result.task(":checkNotices")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val generated = File(dir, "build/meta/THIRD-PARTY-NOTICES").readText()
    assertThat(generated)
      .contains(
        "org.bsd:bsd-lib:1.0\n  License: BSD-3-Clause\n  License text:\n    Copyright 2008 Google Inc.",
        "org.shared:a:2.0\n  License: Apache-2.0\n  NOTICE: see \"NOTICE of org.shared\" below",
        "NOTICE of org.shared (applies to every org.shared module listed above)\n\n  Copyright 2020 The Shared Authors",
      )
    // The shared NOTICE is written once.
    assertThat(generated.split("Copyright 2020 The Shared Authors")).hasSize(2)
    assertThat(File(dir, "build/report.txt").readText()).contains("result: ok")
  }

  @Test
  @DisplayName("T-BLD-003 O-L04: checkThirdPartyNotices fails when the BSD-3-Clause module has no license text")
  fun missingLicenseTextFailsTheCheck() {
    fixture(withLicenseText = false, licenseTextVersion = "1.0")
    val result = runner().buildAndFail()
    assertThat(result.task(":checkNotices")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output)
      .contains("org.bsd:bsd-lib:1.0 (BSD-3-Clause): the BSD-3-Clause license text is missing")
  }

  @Test
  @DisplayName("T-BLD-003 O-L04: generateThirdPartyNotices fails when a text was copied for another version")
  fun staleTextVersionFailsGeneration() {
    fixture(withLicenseText = true, licenseTextVersion = "0.9")
    val result = runner().buildAndFail()
    assertThat(result.task(":notices")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output)
      .contains("org.bsd:bsd-lib:1.0: the text for 'org.bsd:bsd-lib' was copied from version 0.9; replace it from the 1.0 release")
  }
}
