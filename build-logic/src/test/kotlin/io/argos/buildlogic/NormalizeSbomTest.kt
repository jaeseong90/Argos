// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** SbomNormalizer (agentSbom task): reproducible agent SBOM with archive names (T-BLD-009). */
@Tag("req:SEC-70")
class NormalizeSbomTest {
  private val group = "pkg:maven/io.github.argos-apm"
  private val corePurl = "$group/agent-core@0.0.0-SNAPSHOT?project_path=%3Aagent%3Aagent-core"
  private val jdbcPurl =
    "$group/jdbc@0.0.0-SNAPSHOT?project_path=%3Aagent%3Aagent-instrumentation%3Ajdbc"
  private val apiPurl = "$group/api@0.0.0-SNAPSHOT?project_path=%3Aagent%3Aagent-instrumentation%3Aapi"
  private val rootPurl = "$group/argos-agent@0.0.0-SNAPSHOT?project_path=%3Aagent%3Aagent-dist"
  private val byteBuddyPurl = "pkg:maven/net.bytebuddy/byte-buddy@1.18.15?type=jar"

  private fun component(name: String, purl: String) =
    """
    {
      "type" : "library",
      "bom-ref" : "$purl",
      "group" : "x",
      "name" : "$name",
      "version" : "1",
      "purl" : "$purl"
    }
    """

  /** Shaped like the CycloneDX Gradle plugin output, written on Windows (CRLF line ends). */
  private val raw =
    """
    {
      "bomFormat" : "CycloneDX",
      "specVersion" : "1.6",
      "version" : 1,
      "metadata" : {
        "timestamp" : "2026-10-23T01:02:03Z",
        "component" : {
          "type" : "library",
          "bom-ref" : "$rootPurl",
          "name" : "argos-agent",
          "description" : "line one\r\nline two",
          "purl" : "$rootPurl"
        }
      },
      "components" : [
        ${component("agent-core", corePurl)},
        ${component("api", apiPurl)},
        ${component("jdbc", jdbcPurl)},
        ${component("byte-buddy", byteBuddyPurl)}
      ],
      "dependencies" : [
        { "ref" : "$jdbcPurl", "dependsOn" : [ "$apiPurl" ] },
        { "ref" : "$rootPurl", "dependsOn" : [ "$jdbcPurl", "$corePurl", "$apiPurl" ] },
        { "ref" : "$corePurl", "dependsOn" : [ "$byteBuddyPurl" ] },
        { "ref" : "$apiPurl", "dependsOn" : [ ] },
        { "ref" : "$byteBuddyPurl", "dependsOn" : [ ] }
      ]
    }
    """
      .trimIndent()
      .replace("\n", "\r\n")

  private fun normalize(json: String = raw) = SbomNormalizer.normalize(json, "1980-02-01T00:00:00Z")

  private fun components(bom: JsonNode): Map<String, JsonNode> =
    bom.path("components").associateBy { it.path("bom-ref").asText() }

  @Test
  @DisplayName("T-BLD-009 Argos project components are named after their archives (name, purl, bom-ref)")
  fun namesProjectComponentsAfterArchives() {
    val bom = ObjectMapper().readTree(normalize())
    val names =
      bom.path("components").map { it.path("name").asText() to it.path("purl").asText() }.toMap()

    for (path in listOf(":agent:agent-core", ":agent:agent-instrumentation:api", ":agent:agent-instrumentation:jdbc")) {
      // The archive name of the project (base.archivesName, argos.java-common).
      val archive = argosArchivesNameOf(path)
      assertThat(names).containsKey(archive)
      val purl = names.getValue(archive)
      assertThat(purl).startsWith("$group/$archive@0.0.0-SNAPSHOT?")
      assertThat(SbomNormalizer.projectPathOf(purl)).isEqualTo(path)
    }
    assertThat(names.keys)
      .containsExactly(
        "argos-agent-core",
        "argos-agent-instrumentation-api",
        "argos-agent-instrumentation-jdbc",
        "byte-buddy",
      )
    for (component in bom.path("components")) {
      assertThat(component.path("bom-ref").asText()).isEqualTo(component.path("purl").asText())
    }
    // Third-party components and the root component keep their names.
    assertThat(names).containsEntry("byte-buddy", byteBuddyPurl)
    assertThat(bom.at("/metadata/component/name").asText()).isEqualTo("argos-agent")
    assertThat(bom.at("/metadata/component/bom-ref").asText()).isEqualTo(rootPurl)
  }

  @Test
  @DisplayName("T-BLD-009 dependency references follow the renamed bom-refs and are sorted")
  fun keepsReferentialIntegrity() {
    val bom = ObjectMapper().readTree(normalize())
    val refs = components(bom).keys + bom.at("/metadata/component/bom-ref").asText()
    val dependencies = bom.path("dependencies").toList()
    val used = dependencies.flatMap { d -> listOf(d.path("ref")) + d.path("dependsOn").toList() }
    assertThat(used.map { it.asText() }).isNotEmpty().allSatisfy { assertThat(refs).contains(it) }
    assertThat(dependencies.map { it.path("ref").asText() }).isSorted()
    for (dependency in dependencies) {
      assertThat(dependency.path("dependsOn").map { it.asText() }).isSorted()
    }
    assertThat(normalize()).doesNotContain("/agent-core@", "/jdbc@", "/api@")
  }

  @Test
  @DisplayName("T-BLD-009 CRLF input gives LF-only output with one final newline and fixed timestamps")
  fun writesLfOnly() {
    assertThat(raw).contains("\r\n")
    val output = normalize()
    assertThat(output.count { it == '\r' }).isZero()
    assertThat(output).endsWith("}\n").doesNotEndWith("\n\n")
    assertThat(output).contains("\"timestamp\" : \"1980-02-01T00:00:00Z\"").doesNotContain("2026-10-23")
    assertThat(output).contains("\"bomFormat\" : \"CycloneDX\"")
    // CRLF inside string values becomes LF as well (escaped in JSON).
    assertThat(ObjectMapper().readTree(output).at("/metadata/component/description").asText())
      .isEqualTo("line one\nline two")
  }

  @Test
  @DisplayName("T-BLD-008 missingProjects names the shipped modules that are not SBOM components")
  fun findsMissingProjects() {
    val required = listOf(":agent:agent-bootstrap", ":agent:agent-core")
    // The fixture lists agent-core but not agent-bootstrap, whose classes sit at the jar root.
    assertThat(SbomNormalizer.missingProjects(normalize(), required))
      .containsExactly(":agent:agent-bootstrap")

    val bootstrapPurl =
      "$group/agent-bootstrap@0.0.0-SNAPSHOT?project_path=%3Aagent%3Aagent-bootstrap"
    val withBootstrap =
      raw.replace(
        "\"components\" : [",
        "\"components\" : [ ${component("agent-bootstrap", bootstrapPurl)},",
      )
    assertThat(withBootstrap).isNotEqualTo(raw)
    assertThat(SbomNormalizer.missingProjects(normalize(withBootstrap), required)).isEmpty()
    assertThat(SbomNormalizer.missingProjects(normalize(withBootstrap), emptyList())).isEmpty()

    // The root component (metadata) is the jar itself, not a shipped module.
    assertThat(SbomNormalizer.missingProjects(normalize(), listOf(":agent:agent-dist")))
      .containsExactly(":agent:agent-dist")
  }

  @Test
  @DisplayName("T-BLD-009 the same input (CRLF or LF) always gives identical output")
  fun isDeterministic() {
    assertThat(normalize()).isEqualTo(normalize())
    assertThat(normalize(raw.replace("\r\n", "\n"))).isEqualTo(normalize())
  }
}
