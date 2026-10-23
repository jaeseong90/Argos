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
 * Writes META-INF/argos-deps.txt (SEC-77, 19 §3.3 step 4): one line per bundled third-party module,
 * `group:artifact:version:license`, taken from the runtime classpath before shading.
 *
 * Licenses come from [licenses] (keyed by `group:artifact`), which the license-report tasks fill;
 * a module without an entry is written as `UNKNOWN` so that checkLicenses can reject it.
 */
abstract class GenerateDepsManifest : DefaultTask() {

  @get:Input abstract val runtimeRoot: Property<ResolvedComponentResult>

  @get:Input abstract val artifactName: Property<String>

  @get:Input abstract val licenses: MapProperty<String, String>

  @get:OutputFile abstract val outputFile: RegularFileProperty

  init {
    group = "build"
    description = "Generates the bundled dependency manifest (argos-deps.txt)."
    licenses.convention(emptyMap())
  }

  @TaskAction
  fun generate() {
    val licenseMap = licenses.get()
    val modules = DependencyGraph.externalModules(runtimeRoot.get())
    val text = buildString {
      append("# ${ArgosProject.PRODUCT_NAME} - bundled third-party modules of ${artifactName.get()}\n")
      append("# Format: group:artifact:version:license (SPDX identifier)\n")
      for (module in modules) {
        val license = licenseMap["${module.group}:${module.module}"] ?: "UNKNOWN"
        append("${module.group}:${module.module}:${module.version}:$license\n")
      }
    }
    outputFile.get().asFile.writeText(text, Charsets.UTF_8)
  }
}
