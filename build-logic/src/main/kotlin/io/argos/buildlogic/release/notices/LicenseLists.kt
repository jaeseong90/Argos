// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.notices

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import io.argos.buildlogic.ThirdPartyNotices
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * The open-source component lists that `GET /about/licenses` shows (O-L04, ADR-0010 C-153):
 * `META-INF/argos/licenses/<part>.json` on the server's class path, read by the server's
 * `io.argos.server.api.about.LicenseCatalog`. Each is a JSON array of `{name, version, license}`
 * objects sorted by name and version. The Java parts (server, agent) are rendered from the
 * `META-INF/argos-deps.txt` of the same artifact ([fromDependencies]), so the About screen and the
 * shipped manifest list the same modules with the same licenses; the UI part is written by
 * `ui/scripts/check-licenses.mjs --about-json` from the bundle's license manifest.
 */
object LicenseLists {

  /** Directory of the lists on the class path (LicenseCatalog.DIRECTORY). */
  const val DIRECTORY: String = "META-INF/argos/licenses"

  /** The parts the server shows, in screen order. */
  val PARTS: List<String> = listOf("server", "ui", "agent")

  /** One listed component. */
  data class Entry(val name: String, val version: String, val license: String)

  /** Entries of argos-deps.txt: name `group:artifact`, its version and SPDX expression. */
  fun fromDependencies(dependencies: List<ThirdPartyNotices.Dependency>): List<Entry> =
    dependencies.map { Entry(it.key, it.version, it.license) }.sortedWith(compareBy({ it.name }, { it.version }))

  /** The JSON array (two-space indentation, LF line ends, one trailing newline). */
  fun render(entries: List<Entry>): String {
    val mapper = ObjectMapper()
    val array = mapper.createArrayNode()
    for (entry in entries.sortedWith(compareBy({ it.name }, { it.version }))) {
      array.addObject().put("name", entry.name).put("version", entry.version).put("license", entry.license)
    }
    val printer =
      DefaultPrettyPrinter().apply {
        indentObjectsWith(DefaultIndenter("  ", "\n"))
        indentArraysWith(DefaultIndenter("  ", "\n"))
      }
    return mapper.writer(printer).writeValueAsString(array).trimEnd() + "\n"
  }

  /**
   * Parses a list as LicenseCatalog reads it; fails on anything else (not an array, an entry without
   * a non-blank name, version or license).
   */
  fun parse(json: String): List<Entry> {
    val root =
      try {
        ObjectMapper().readTree(json)
      } catch (e: Exception) {
        throw GradleException("not JSON: ${e.message}")
      }
    if (root !is ArrayNode) throw GradleException("not a JSON array of {name, version, license}")
    return root.mapIndexed { index, node ->
      fun text(field: String): String =
        node.path(field).textValue()?.takeIf { it.isNotBlank() }
          ?: throw GradleException("entry $index has no '$field'")
      Entry(text("name"), text("version"), text("license"))
    }
  }
}

/**
 * Writes the About-screen list of one Java part ([LicenseLists]) from the argos-deps.txt that the
 * same artifact ships (C-153).
 */
abstract class GenerateLicenseList : DefaultTask() {

  /**
   * META-INF/argos-deps.txt of the artifact (GenerateDepsManifest output, or the resolved
   * consumable configuration of another project); exactly one file.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val depsFile: ConfigurableFileCollection

  @get:OutputFile abstract val outputFile: RegularFileProperty

  init {
    group = "build"
    description = "Writes the open-source component list of the About screen from argos-deps.txt (C-153)."
  }

  @TaskAction
  fun generate() {
    val file =
      depsFile.files.singleOrNull()
        ?: throw GradleException("generateLicenseList: expected one argos-deps.txt, got ${depsFile.files}")
    val dependencies = ThirdPartyNotices.parseDependencies(file.readText(Charsets.UTF_8))
    outputFile.get().asFile.apply { parentFile.mkdirs() }
      .writeText(LicenseLists.render(LicenseLists.fromDependencies(dependencies)), Charsets.UTF_8)
  }
}
