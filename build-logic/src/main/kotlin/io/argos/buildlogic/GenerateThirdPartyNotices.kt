// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
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

  /** Original NOTICE text per `group:artifact` (Apache-2.0 §4(d)). */
  @get:Input abstract val noticeTexts: MapProperty<String, String>

  @get:OutputFile abstract val outputFile: RegularFileProperty

  init {
    group = "build"
    description = "Generates THIRD-PARTY-NOTICES for the bundled dependencies."
    licenses.convention(emptyMap())
    noticeTexts.convention(emptyMap())
  }

  @TaskAction
  fun generate() {
    val modules = DependencyGraph.externalModules(runtimeRoot.get())
    val licenseMap = licenses.get()
    val notices = noticeTexts.get()
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
