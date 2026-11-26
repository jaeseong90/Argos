// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** A third-party module that an artifact bundles. */
data class BundledModule(val group: String, val module: String, val version: String) {
  val key: String
    get() = "$group:$module"
}

/**
 * The bundled modules of an artifact: the external modules of [root] (platforms excluded) minus
 * [excluded] (`group:artifact`, modules the packaging leaves out, such as Spring Boot's
 * dependencies-only starter jars), plus [extra] (`group:artifact:version`, modules the packaging
 * adds from outside the graph, such as the Spring Boot loader and jarmode tools); sorted.
 */
internal fun bundledModules(
  root: ResolvedComponentResult,
  excluded: Set<String>,
  extra: Collection<String>,
): List<BundledModule> {
  val graph =
    DependencyGraph.externalModules(root)
      .map { BundledModule(it.group, it.module, it.version) }
      .filter { it.key !in excluded }
  val added =
    extra.map { coordinates ->
      val parts = coordinates.split(':')
      if (parts.size != 3 || parts.any { it.isBlank() }) {
        throw GradleException("extraModules: expected group:artifact:version, got '$coordinates'")
      }
      BundledModule(parts[0], parts[1], parts[2])
    }
  return (graph + added).distinct().sortedWith(compareBy({ it.group }, { it.module }, { it.version }))
}

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

  /** `group:artifact` of graph modules the artifact does not bundle ([bundledModules]). */
  @get:Input abstract val excludedModules: SetProperty<String>

  /** `group:artifact:version` to SPDX license of modules bundled from outside the graph. */
  @get:Input abstract val extraModules: MapProperty<String, String>

  @get:OutputFile abstract val outputFile: RegularFileProperty

  init {
    group = "build"
    description = "Generates the bundled dependency manifest (argos-deps.txt)."
    licenses.convention(emptyMap())
    excludedModules.convention(emptySet())
    extraModules.convention(emptyMap())
  }

  @TaskAction
  fun generate() {
    val extra = extraModules.get()
    val licenseMap = licenses.get() + extra.mapKeys { it.key.substringBeforeLast(':') }
    val modules = bundledModules(runtimeRoot.get(), excludedModules.get(), extra.keys)
    val text = buildString {
      append("# ${ArgosProject.PRODUCT_NAME} - bundled third-party modules of ${artifactName.get()}\n")
      append("# Format: group:artifact:version:license (SPDX identifier)\n")
      for (module in modules) {
        val license = licenseMap[module.key] ?: "UNKNOWN"
        append("${module.group}:${module.module}:${module.version}:$license\n")
      }
    }
    outputFile.get().asFile.writeText(text, Charsets.UTF_8)
  }
}
