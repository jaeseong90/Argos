// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes META-INF/THIRD-PARTY-NOTICES (O-L04, 19 §5.5): the list of bundled third-party modules
 * with their licenses. The license-report integration (checkLicenses) adds license texts and the
 * original NOTICE contents through [licenses] and [noticeTexts].
 */
abstract class GenerateThirdPartyNotices : DefaultTask() {

  @get:Input abstract val runtimeRoot: Property<ResolvedComponentResult>

  @get:Input abstract val artifactName: Property<String>

  /** SPDX license identifier per `group:artifact`. */
  @get:Input abstract val licenses: MapProperty<String, String>

  /** Original NOTICE text per `group:artifact` (Apache-2.0 §4(d)), given explicitly. */
  @get:Input abstract val noticeTexts: MapProperty<String, String>

  /**
   * Jars of the bundled modules; their `META-INF/NOTICE*` files are copied into the output (O-L04
   * "원 NOTICE 본문"). Mapped to modules by [noticeSourceKeys].
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val noticeSources: ConfigurableFileCollection

  /** `<jar file name>=<group:artifact>` for every file of [noticeSources]. */
  @get:Input abstract val noticeSourceKeys: ListProperty<String>

  @get:OutputFile abstract val outputFile: RegularFileProperty

  init {
    group = "build"
    description = "Generates THIRD-PARTY-NOTICES for the bundled dependencies."
    licenses.convention(emptyMap())
    noticeTexts.convention(emptyMap())
    noticeSourceKeys.convention(emptyList())
  }

  /** NOTICE texts read from the jars of [noticeSources], per `group:artifact`. */
  private fun noticesFromJars(): Map<String, String> {
    val keys =
      noticeSourceKeys.get().associate { entry ->
        val separator = entry.indexOf('=')
        entry.substring(0, separator) to entry.substring(separator + 1)
      }
    val result = sortedMapOf<String, String>()
    for (file in noticeSources.files) {
      val key = keys[file.name] ?: continue
      if (!file.isFile) continue
      ZipFile(file).use { zip ->
        val entries = zip.entries().toList().filter { !it.isDirectory }.sortedBy { it.name }
        fun read(entry: java.util.zip.ZipEntry) =
          zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }.trim().replace("\r\n", "\n")
        val texts =
          entries
            .filter { it.name.matches(Regex("META-INF/NOTICE(\\.txt|\\.md)?", RegexOption.IGNORE_CASE)) }
            .map { read(it) }
            .filter { it.isNotEmpty() }
            .toMutableList()
        // Licenses of code embedded in the module's jar (e.g. ByteBuddy's repackaged ASM,
        // META-INF/licenses/ASM, BSD-3-Clause): reproduced for binary redistribution.
        entries
          .filter { it.name.startsWith("META-INF/licenses/") }
          .forEach { entry ->
            val text = read(entry)
            if (text.isNotEmpty()) {
              texts += "Embedded component license (${entry.name}):\n$text"
            }
          }
        if (texts.isNotEmpty()) {
          result[key] = texts.joinToString("\n\n")
        }
      }
    }
    return result
  }

  @TaskAction
  fun generate() {
    val modules = DependencyGraph.externalModules(runtimeRoot.get())
    val licenseMap = licenses.get()
    val notices = noticesFromJars() + noticeTexts.get()
    val text = buildString {
      append("${ArgosProject.PRODUCT_NAME} - third-party notices for ${artifactName.get()}\n")
      append("=".repeat(72)).append('\n')
      append('\n')
      if (modules.isEmpty()) {
        append("This artifact bundles no third-party components.\n")
      } else {
        append("This artifact bundles the following third-party components.\n")
        append("Bundled classes are relocated below ${AgentShading.PREFIX}.\n")
        for (module in modules) {
          val key = "${module.group}:${module.module}"
          append('\n')
          append("${module.group}:${module.module}:${module.version}\n")
          append("  License: ${licenseMap[key] ?: "UNKNOWN"}\n")
          notices[key]?.let { notice ->
            append("  NOTICE:\n")
            notice.lines().forEach { append("    ").append(it).append('\n') }
          }
        }
      }
    }
    outputFile.get().asFile.writeText(text, Charsets.UTF_8)
  }
}
