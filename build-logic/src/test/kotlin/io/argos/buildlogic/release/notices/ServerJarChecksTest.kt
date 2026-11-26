// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.notices

import io.argos.buildlogic.ThirdPartyNotices
import io.argos.buildlogic.repositoryRoot
import io.argos.buildlogic.writeJar
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * verifyServerJar (T-BLD-003a, WO-44; O-L01, O-L04, SEC-70, SEC-77, ADR-0010 C-153): the rules of
 * [ServerJarChecks] on fixture boot jars, one violation at a time, and the directory form of
 * [NoticeChecks] used for the demo image (T-BLD-003b).
 */
@Tag("req:O-L04")
class ServerJarChecksTest {
  @TempDir lateinit var dir: File

  private val license = File(repositoryRoot, "LICENSE").readText()
  private val notice = File(repositoryRoot, "NOTICE").readText()
  private val bsdText = File(repositoryRoot, "server/src/notices/protobuf-java-LICENSE.txt").readText()

  private val deps =
    listOf(
      ThirdPartyNotices.Dependency("io.grpc", "grpc-api", "1.85.1", "Apache-2.0"),
      ThirdPartyNotices.Dependency("com.google.protobuf", "protobuf-java", "4.36.2", "BSD-3-Clause"),
      ThirdPartyNotices.Dependency("com.github.luben", "zstd-jni", "1.5.7-23", "BSD-2-Clause"),
    )

  private fun depsText(dependencies: List<ThirdPartyNotices.Dependency>) =
    "# header\n" + dependencies.joinToString("") { "${it.group}:${it.artifact}:${it.version}:${it.license}\n" }

  private fun indented(text: String) = text.trim().lines().joinToString("\n") { if (it.isBlank()) "" else "    $it" }

  private val zstdText =
    "Copyright (c) 2015-present, Luben Karavelov/ All rights reserved.\n\n" +
      "Redistribution and use in source and binary forms, with or without modification,\n" +
      "are permitted provided that the following conditions are met:\n" +
      "Redistributions in binary form must reproduce the above copyright notice.\n" +
      "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS \"AS IS\" ... ARE DISCLAIMED.\n"

  private fun thirdPartyNotices(withBsdText: Boolean = true, withGrpcNotice: Boolean = true) =
    buildString {
      append("Argos APM - third-party notices\n").append("=".repeat(72)).append("\n\n")
      append("com.github.luben:zstd-jni:1.5.7-23\n  License: BSD-2-Clause\n  License text:\n")
      append(indented(zstdText)).append("\n\n")
      append("com.google.protobuf:protobuf-java:4.36.2\n  License: BSD-3-Clause\n")
      if (withBsdText) append("  License text:\n").append(indented(bsdText)).append('\n')
      append("\nio.grpc:grpc-api:1.85.1\n  License: Apache-2.0\n")
      if (withGrpcNotice) append("  NOTICE:\n    Copyright 2014 The gRPC Authors\n")
    }

  private fun sbom(dependencies: List<ThirdPartyNotices.Dependency>) =
    """{"bomFormat":"CycloneDX","specVersion":"1.6","components":[""" +
      (dependencies.map { """{"group":"${it.group}","name":"${it.artifact}","version":"${it.version}"}""" } +
          """{"group":"io.github.argos-apm","name":"argos-proto","version":"0.1.0"}""")
        .joinToString(",") +
      "]}"

  private val listJson = LicenseLists.render(LicenseLists.fromDependencies(deps))
  private val agentJson = LicenseLists.render(listOf(LicenseLists.Entry("net.bytebuddy:byte-buddy", "1.17.0", "Apache-2.0")))
  private val uiJson = """[{"name":"react","version":"19.3.1","license":"MIT"}]"""

  /** A complete boot jar; [change] edits the entry map before writing (null value: remove). */
  private fun jar(uiBundled: Boolean = true, change: (MutableMap<String, String?>) -> Unit = {}): File {
    val entries =
      linkedMapOf<String, String?>(
        "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n",
        "META-INF/LICENSE" to license,
        "META-INF/NOTICE" to notice,
        "META-INF/THIRD-PARTY-NOTICES" to thirdPartyNotices(),
        "META-INF/argos-deps.txt" to depsText(deps),
        "META-INF/sbom/server.cdx.json" to sbom(deps),
        "META-INF/argos/licenses/server.json" to listJson,
        "META-INF/argos/licenses/agent.json" to agentJson,
        "META-INF/build-info.properties" to "build.version=0.1.0\nbuild.commit=abc1234\nbuild.protocol=1\n",
        "BOOT-INF/classes/io/argos/server/ServerApplication.class" to "x",
        "BOOT-INF/lib/grpc-api-1.85.1.jar" to "x",
        "BOOT-INF/lib/protobuf-java-4.36.2.jar" to "x",
        "BOOT-INF/lib/zstd-jni-1.5.7-23-cloud.jar" to "x",
        "BOOT-INF/lib/argos-proto-0.1.0.jar" to "x",
      )
    if (uiBundled) {
      entries["BOOT-INF/classes/static/index.html"] = "<!doctype html>"
      entries["META-INF/argos/licenses/ui.json"] = uiJson
    }
    change(entries)
    val content = entries.filterValues { it != null }.mapValues { it.value!!.toByteArray() }
    return writeJar(File(dir, "argos-server-0.1.0.jar").apply { delete() }, null, content)
  }

  private fun violations(jar: File, uiExpected: Boolean = true) =
    ServerJarChecks.violations(jar, "0.1.0", uiExpected, listOf("io.grpc"))

  @Test
  @DisplayName(
    "T-BLD-003a a complete boot jar passes, with the UI (ui.json + bundle) and without it (no ui.json)"
  )
  fun completeJarPasses() {
    assertThat(violations(jar())).isEmpty()
    assertThat(violations(jar(uiBundled = false), uiExpected = false)).isEmpty()
  }

  @Test
  @DisplayName("T-BLD-003a LICENSE and NOTICE are required and must be the Apache License 2.0 and the Argos NOTICE")
  fun licenseAndNotice() {
    assertThat(violations(jar { it["META-INF/LICENSE"] = null })).containsExactly("META-INF/LICENSE: missing")
    assertThat(violations(jar { it["META-INF/LICENSE"] = "MIT License" }))
      .containsExactly("META-INF/LICENSE: not the Apache License 2.0")
    assertThat(violations(jar { it["META-INF/NOTICE"] = " " })).containsExactly("META-INF/NOTICE: empty")
    assertThat(violations(jar { it["META-INF/NOTICE"] = "Other project" }))
      .containsExactly("META-INF/NOTICE: not the Argos NOTICE")
  }

  @Test
  @DisplayName(
    "T-BLD-003a argos-deps.txt names exactly the third-party jars of BOOT-INF/lib, with licenses; THIRD-PARTY-NOTICES is complete"
  )
  fun dependencyManifestAndNotices() {
    val extraLib = violations(jar { it["BOOT-INF/lib/guava-33.0.jar"] = "x" })
    assertThat(extraLib).containsExactly("BOOT-INF/lib/guava-33.0.jar: bundled but not listed in META-INF/argos-deps.txt")

    val missingJar = violations(jar { it.remove("BOOT-INF/lib/protobuf-java-4.36.2.jar") })
    assertThat(missingJar)
      .containsExactly("META-INF/argos-deps.txt: com.google.protobuf:protobuf-java:4.36.2 (BSD-3-Clause) has no jar in BOOT-INF/lib/")

    val unknown = deps + ThirdPartyNotices.Dependency("org.x", "x", "1", "UNKNOWN")
    assertThat(
        violations(
          jar {
            it["META-INF/argos-deps.txt"] = depsText(unknown)
            it["BOOT-INF/lib/x-1.jar"] = "x"
          }
        )
      )
      .contains("META-INF/argos-deps.txt: org.x:x:1 (UNKNOWN) has no license")

    assertThat(violations(jar { it["META-INF/argos-deps.txt"] = "a:b\n" }))
      .contains("META-INF/argos-deps.txt: Malformed argos-deps.txt line: a:b")

    assertThat(violations(jar { it["META-INF/THIRD-PARTY-NOTICES"] = thirdPartyNotices(withBsdText = false) }))
      .singleElement()
      .asString()
      .startsWith("META-INF/THIRD-PARTY-NOTICES: com.google.protobuf:protobuf-java:4.36.2 (BSD-3-Clause): the BSD-3-Clause license text is missing")
    assertThat(violations(jar { it["META-INF/THIRD-PARTY-NOTICES"] = thirdPartyNotices(withGrpcNotice = false) }))
      .containsExactly(
        "META-INF/THIRD-PARTY-NOTICES: io.grpc:grpc-api:1.85.1 (Apache-2.0): the original NOTICE text is missing (Apache-2.0 4(d))"
      )
  }

  @Test
  @DisplayName("T-BLD-003a T-BLD-008 the SBOM is CycloneDX with components and lists every bundled module")
  fun sbom() {
    assertThat(violations(jar { it["META-INF/sbom/server.cdx.json"] = null }))
      .containsExactly("META-INF/sbom/server.cdx.json: missing")
    assertThat(violations(jar { it["META-INF/sbom/server.cdx.json"] = """{"bomFormat":"SPDX"}""" }))
      .containsExactly("META-INF/sbom/server.cdx.json: bomFormat is not CycloneDX")
    assertThat(
        violations(jar { it["META-INF/sbom/server.cdx.json"] = """{"bomFormat":"CycloneDX","specVersion":"1.6","components":[]}""" })
      )
      .containsExactly("META-INF/sbom/server.cdx.json: no components")
    assertThat(violations(jar { it["META-INF/sbom/server.cdx.json"] = sbom(deps.drop(1)) }))
      .containsExactly("META-INF/sbom/server.cdx.json: io.grpc:grpc-api:1.85.1 is not a component")
    // Spring Boot's own SBOM (random serial number, build time) must not ship next to it.
    assertThat(
        violations(
          jar {
            it["META-INF/sbom/application.cdx.json"] = sbom(deps)
            it["META-INF/MANIFEST.MF"] = "Manifest-Version: 1.0\nSbom-Location: META-INF/sbom/application.cdx.json\n"
          }
        )
      )
      .containsExactly(
        "META-INF/sbom/application.cdx.json: a second SBOM (only META-INF/sbom/server.cdx.json is shipped)",
        "META-INF/MANIFEST.MF: Sbom-Location is 'META-INF/sbom/application.cdx.json', expected 'META-INF/sbom/server.cdx.json'",
      )
    assertThat(
        violations(jar { it["META-INF/MANIFEST.MF"] = "Manifest-Version: 1.0\nSbom-Location: META-INF/sbom/server.cdx.json\n" })
      )
      .isEmpty()
  }

  @Test
  @DisplayName(
    "T-BLD-003a C-153 About lists: server.json = argos-deps.txt, agent.json non-empty, ui.json exactly when the UI is bundled"
  )
  fun aboutLists() {
    assertThat(violations(jar { it["META-INF/argos/licenses/server.json"] = LicenseLists.render(LicenseLists.fromDependencies(deps.drop(1))) }))
      .containsExactly("META-INF/argos/licenses/server.json: differs from META-INF/argos-deps.txt (C-153)")
    assertThat(violations(jar { it["META-INF/argos/licenses/server.json"] = """{"components":[]}""" }))
      .containsExactly("META-INF/argos/licenses/server.json: not a JSON array of {name, version, license}")
    assertThat(violations(jar { it["META-INF/argos/licenses/agent.json"] = null }))
      .containsExactly("META-INF/argos/licenses/agent.json: missing")
    assertThat(violations(jar { it["META-INF/argos/licenses/agent.json"] = "[]\n" }))
      .containsExactly("META-INF/argos/licenses/agent.json: lists no component")
    assertThat(violations(jar { it["META-INF/argos/licenses/agent.json"] = """[{"name":"a","version":"1"}]""" }))
      .containsExactly("META-INF/argos/licenses/agent.json: entry 0 has no 'license'")
    assertThat(violations(jar { it["META-INF/argos/licenses/ui.json"] = null }))
      .containsExactly("META-INF/argos/licenses/ui.json: missing (the jar bundles the UI)")
    assertThat(violations(jar(uiBundled = false)))
      .containsExactly("BOOT-INF/classes/static/index.html: missing (the UI bundle was expected)")
    assertThat(violations(jar(), uiExpected = false))
      .containsExactly("META-INF/argos/licenses/ui.json: present in a build without the UI (stale list)")
  }

  @Test
  @DisplayName("T-BLD-003a build-info.properties carries the project version, a commit and the protocol version")
  fun buildInfo() {
    assertThat(violations(jar { it["META-INF/build-info.properties"] = "build.version=0.0.9\nbuild.protocol=1\n" }))
      .containsExactly(
        "META-INF/build-info.properties: build.version is '0.0.9', expected '0.1.0'",
        "META-INF/build-info.properties: no build.commit",
      )
    assertThat(violations(jar { it["META-INF/build-info.properties"] = null }))
      .containsExactly("META-INF/build-info.properties: missing")
  }

  @Test
  @DisplayName(
    "T-BLD-003b image notices: LICENSE and NOTICE always; argos-deps.txt and a complete THIRD-PARTY-NOTICES when the image has libraries"
  )
  fun imageDirectory() {
    val root = File(dir, "image")
    fun put(name: String, text: String) = File(root, "opt/argos-demo/$name").apply { parentFile.mkdirs() }.writeText(text)
    put("LICENSE", license)
    put("NOTICE", notice)
    assertThat(NoticeChecks.checkDirectory(root, "opt/argos-demo/", thirdParty = false, emptyList()).problems).isEmpty()
    assertThat(NoticeChecks.checkDirectory(root, "opt/argos-demo/", thirdParty = true, emptyList()).problems)
      .containsExactly("opt/argos-demo/argos-deps.txt: missing", "opt/argos-demo/THIRD-PARTY-NOTICES: missing")
    put("argos-deps.txt", depsText(deps))
    put("THIRD-PARTY-NOTICES", thirdPartyNotices(withBsdText = false))
    val result = NoticeChecks.checkDirectory(root, "opt/argos-demo/", thirdParty = true, listOf("io.grpc"))
    assertThat(result.dependencies).hasSize(3)
    assertThat(result.problems)
      .singleElement()
      .asString()
      .contains("com.google.protobuf:protobuf-java:4.36.2 (BSD-3-Clause): the BSD-3-Clause license text is missing")
    put("THIRD-PARTY-NOTICES", thirdPartyNotices())
    assertThat(NoticeChecks.checkDirectory(root, "opt/argos-demo/", thirdParty = true, listOf("io.grpc")).problems).isEmpty()
    File(root, "opt/argos-demo/LICENSE").delete()
    assertThat(NoticeChecks.checkDirectory(root, "opt/argos-demo/", thirdParty = true, listOf("io.grpc")).problems)
      .containsExactly("opt/argos-demo/LICENSE: missing")
  }

  @Test
  @DisplayName("T-BLD-003c About list format: sorted [{name, version, license}] rendered from argos-deps.txt, parsed back")
  fun licenseListFormat() {
    val text = LicenseLists.render(LicenseLists.fromDependencies(deps.reversed()))
    assertThat(text)
      .startsWith("[\n  {\n    \"name\" : \"com.github.luben:zstd-jni\",\n    \"version\" : \"1.5.7-23\",\n")
      .endsWith("]\n")
      .doesNotContain("\r")
    assertThat(LicenseLists.parse(text).map { it.name })
      .containsExactly("com.github.luben:zstd-jni", "com.google.protobuf:protobuf-java", "io.grpc:grpc-api")
    assertThat(LicenseLists.parse(text)).isEqualTo(LicenseLists.fromDependencies(deps))
    assertThat(LicenseLists.render(LicenseLists.fromDependencies(deps))).isEqualTo(text)
    assertThat(runCatching { LicenseLists.parse("not json") }.exceptionOrNull()).hasMessageStartingWith("not JSON")
    assertThat(LicenseLists.PARTS).containsExactly("server", "ui", "agent")
  }
}
