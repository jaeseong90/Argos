// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import java.net.URLDecoder
import java.time.Instant
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Normalizes the CycloneDX JSON SBOM of a shipped artifact (agent jar, server jar; DSN-19-05,
 * T-BLD-009, SEC-70); see [SbomNormalizer]. Fails when a project in [requiredProjects] is not a
 * component of the SBOM (T-BLD-008).
 */
abstract class NormalizeSbom : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val inputFile: RegularFileProperty

  /** ISO-8601 instant written into every timestamp field. */
  @get:Input abstract val timestamp: Property<String>

  /**
   * Gradle paths of the Argos projects whose classes the jar ships (`:agent:agent-bootstrap`, ...);
   * each must be a component of the SBOM.
   */
  @get:Input abstract val requiredProjects: ListProperty<String>

  /**
   * Modules the artifact ships from outside the dependency graph (`group:artifact:version` to SPDX
   * license; e.g. the Spring Boot loader and jarmode tools of a boot jar), added as library
   * components when the SBOM lacks them.
   */
  @get:Input abstract val extraComponents: MapProperty<String, String>

  @get:OutputFile abstract val outputFile: RegularFileProperty

  init {
    extraComponents.convention(emptyMap())
    group = "build"
    description =
      "Normalizes a CycloneDX SBOM (fixed timestamps, archive names, LF line endings) and checks " +
        "that it lists every shipped Argos module."
  }

  @TaskAction
  fun normalize() {
    val text = inputFile.get().asFile.readText(Charsets.UTF_8)
    val normalized =
      SbomNormalizer.withExtraComponents(SbomNormalizer.normalize(text, timestamp.get()), extraComponents.get())
    val missing = SbomNormalizer.missingProjects(normalized, requiredProjects.get())
    if (missing.isNotEmpty()) {
      throw GradleException(
        "The SBOM ${inputFile.get().asFile} lacks components that the artifact ships: " +
          missing.joinToString { "$it (${argosArchivesNameOf(it)})" } +
          ". Add the configuration that resolves them to includeConfigs of cyclonedxDirectBom " +
          "(SEC-70)."
      )
    }
    outputFile.get().asFile.writeText(normalized, Charsets.UTF_8)
  }
}

/**
 * Makes the SBOM written by the CycloneDX Gradle plugin reproducible and accurate, so that two
 * builds of the same commit produce identical artifacts on any OS:
 * - every `timestamp` value becomes [normalize]'s `timestamp` (the plugin writes the current time);
 * - components of Argos projects (purl qualifier `project_path`) are named after their archives
 *   ([argosArchivesNameOf]: `argos-agent-core`, `argos-agent-instrumentation-jdbc`) instead of the
 *   bare Gradle project name (`agent-core`, `jdbc`); `name`, `purl` and `bom-ref` change together and
 *   every reference to an old `bom-ref` (dependencies, ...) follows;
 * - `dependsOn` lists and the `dependencies` array are sorted (they are sets);
 * - the output uses LF line ends only (the plugin uses the platform line separator) and ends with
 *   exactly one newline; CRLF inside string values becomes LF.
 *
 * Jackson comes with the CycloneDX plugin on the build-logic classpath.
 */
object SbomNormalizer {
  private const val PROJECT_PATH = "project_path"
  private val purlName = Regex("^(pkg:[^/]+/[^/]+/)([^@?#]+)(.*)$")
  private val projectPathQualifier = Regex("[?&]$PROJECT_PATH=([^&#]+)")

  fun normalize(json: String, timestamp: String): String {
    val mapper = ObjectMapper()
    val root = mapper.readTree(json) as? ObjectNode ?: error("SBOM is not a JSON object")
    val renamed = mutableMapOf<String, String>()
    renameProjectComponents(root.get("components"), renamed)
    val normalized = rewrite(root, timestamp, renamed) as ObjectNode
    sortDependencies(normalized)
    val printer =
      DefaultPrettyPrinter().apply {
        indentObjectsWith(DefaultIndenter("  ", "\n"))
        indentArraysWith(DefaultIndenter("  ", "\n"))
      }
    return mapper.writer(printer).writeValueAsString(normalized).trimEnd() + "\n"
  }

  /**
   * [json] with a library component for every `group:artifact:version` of [extra] (to its SPDX
   * license) that no component has yet, appended in key order; unchanged when [extra] is empty.
   */
  fun withExtraComponents(json: String, extra: Map<String, String>): String {
    if (extra.isEmpty()) return json
    val mapper = ObjectMapper()
    val root = mapper.readTree(json) as? ObjectNode ?: error("SBOM is not a JSON object")
    val components = root.get("components") as? ArrayNode ?: root.putArray("components")
    val present =
      components.map { "${it.path("group").asText()}:${it.path("name").asText()}:${it.path("version").asText()}" }.toSet()
    for ((coordinates, license) in extra.toSortedMap()) {
      if (coordinates in present) continue
      val (group, name, version) = coordinates.split(':').also { require(it.size == 3) { "not group:artifact:version: $coordinates" } }
      val purl = "pkg:maven/$group/$name@$version?type=jar"
      components.addObject().apply {
        put("type", "library")
        put("bom-ref", purl)
        put("group", group)
        put("name", name)
        put("version", version)
        putArray("licenses").addObject().putObject("license").put("id", license)
        put("purl", purl)
      }
    }
    val printer =
      DefaultPrettyPrinter().apply {
        indentObjectsWith(DefaultIndenter("  ", "\n"))
        indentArraysWith(DefaultIndenter("  ", "\n"))
      }
    return mapper.writer(printer).writeValueAsString(root).trimEnd() + "\n"
  }

  /** The Gradle project path in a purl's `project_path` qualifier, or null. */
  fun projectPathOf(purl: String): String? =
    projectPathQualifier.find(purl)?.let { URLDecoder.decode(it.groupValues[1], Charsets.UTF_8) }

  /**
   * The project paths of [required] that no component of the SBOM [json] (at any nesting depth)
   * carries in its purl `project_path` qualifier, in the order of [required].
   */
  fun missingProjects(json: String, required: Collection<String>): List<String> {
    val present = mutableSetOf<String>()
    collectProjectPaths(ObjectMapper().readTree(json).get("components"), present)
    return required.filter { it !in present }
  }

  private fun collectProjectPaths(components: JsonNode?, into: MutableSet<String>) {
    if (components !is ArrayNode) return
    for (component in components) {
      component.path("purl").textValue()?.let(::projectPathOf)?.let(into::add)
      collectProjectPaths(component.get("components"), into)
    }
  }

  private fun renameProjectComponents(components: JsonNode?, renamed: MutableMap<String, String>) {
    if (components !is ArrayNode) return
    for (component in components) {
      if (component !is ObjectNode) continue
      val purl = component.path("purl").textValue()
      val projectPath = purl?.let(::projectPathOf)
      if (purl != null && projectPath != null) {
        val name = argosArchivesNameOf(projectPath)
        val newPurl = withName(purl, name)
        component.put("name", name)
        component.put("purl", newPurl)
        renamed[purl] = newPurl
        component.path("bom-ref").textValue()?.let { bomRef ->
          val newRef = if (bomRef == purl) newPurl else withName(bomRef, name)
          component.put("bom-ref", newRef)
          renamed[bomRef] = newRef
        }
      }
      renameProjectComponents(component.get("components"), renamed)
    }
  }

  private fun withName(purl: String, name: String): String {
    val match = purlName.matchEntire(purl) ?: error("Unexpected purl in the SBOM: $purl")
    return match.groupValues[1] + name + match.groupValues[3]
  }

  /** Copies [node], pinning timestamps, following renamed references and dropping CR. */
  private fun rewrite(node: JsonNode, timestamp: String, renamed: Map<String, String>): JsonNode =
    when (node) {
      is ObjectNode -> {
        val copy = node.objectNode()
        for ((key, value) in node.properties()) {
          copy.set<JsonNode>(
            key,
            if (key == "timestamp" && value.isTextual) TextNode(timestamp)
            else rewrite(value, timestamp, renamed),
          )
        }
        copy
      }
      is ArrayNode -> node.arrayNode().apply { node.forEach { add(rewrite(it, timestamp, renamed)) } }
      is TextNode -> {
        val text = node.textValue()
        TextNode(renamed[text] ?: text.replace("\r\n", "\n"))
      }
      else -> node
    }

  private fun sortDependencies(root: ObjectNode) {
    val dependencies = root.get("dependencies") as? ArrayNode ?: return
    for (dependency in dependencies) {
      val dependsOn = (dependency as? ObjectNode)?.get("dependsOn") as? ArrayNode ?: continue
      val sorted = dependsOn.map { it.asText() }.sorted()
      dependsOn.removeAll()
      sorted.forEach { dependsOn.add(it) }
    }
    val sortedDependencies = dependencies.sortedBy { it.path("ref").asText() }
    dependencies.removeAll()
    sortedDependencies.forEach { dependencies.add(it) }
  }
}

/**
 * Build timestamp for generated metadata: SOURCE_DATE_EPOCH (seconds, set by CI to the commit
 * time, 19 §3.7) or, when unset, the fixed instant Gradle also uses for archive entries.
 */
fun Project.argosSourceDateInstant(): Provider<String> =
  providers
    .environmentVariable("SOURCE_DATE_EPOCH")
    .map { Instant.ofEpochSecond(it.trim().toLong()).toString() }
    .orElse("1980-02-01T00:00:00Z")
