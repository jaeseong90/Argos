// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import com.github.jk1.license.ModuleData
import com.github.jk1.license.ProjectData
import com.github.jk1.license.render.ReportRenderer
import groovy.json.JsonOutput
import java.io.File

/**
 * Renderer for com.github.jk1.dependency-license-report that writes the raw license data per module
 * with its source kept apart (POM, manifest, license files), so that [LicensePolicy] can prefer the
 * POM declaration. The plugin's own JSON renderer merges all sources into one list.
 */
class LicenseDataRenderer(private val outputFile: File) : ReportRenderer {

  override fun render(data: ProjectData) {
    val modules =
      data.allDependencies
        .sortedWith(compareBy<ModuleData>({ it.group }, { it.name }, { it.version }))
        .map { module ->
          mapOf(
            "module" to "${module.group}:${module.name}",
            "version" to module.version,
            "pom" to
              module.poms.orEmpty().flatMap { it.licenses.orEmpty() }.map { license(it.name, it.url) },
            "manifest" to
              module.manifests.orEmpty().flatMap { manifest ->
                manifest.licenses.orEmpty().map { license(it.name, it.url) }
              },
            "licenseFiles" to
              module.licenseFiles.orEmpty().flatMap { it.fileDetails.orEmpty() }.map {
                license(it.license, it.licenseUrl) + ("file" to it.file)
              },
          )
        }
    outputFile.parentFile.mkdirs()
    outputFile.writeText(
      JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("modules" to modules))) + "\n",
      Charsets.UTF_8,
    )
  }

  private fun license(name: String?, url: String?): Map<String, String?> =
    mapOf("name" to name?.trim(), "url" to url?.trim())

  companion object {
    /** Report file name below the plugin's output directory. */
    const val FILE_NAME = "argos-license-data.json"
  }
}
