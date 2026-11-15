// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
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
 * with their licenses, the license texts that a license other than Apache-2.0 requires
 * ([licenseTexts]) and the original NOTICE contents ([noticeSources] jars, [noticeTexts]). The
 * layout is [ThirdPartyNotices]; CheckThirdPartyNotices verifies the result in the shipped jar.
 */
abstract class GenerateThirdPartyNotices : DefaultTask() {

  @get:Input abstract val runtimeRoot: Property<ResolvedComponentResult>

  @get:Input abstract val artifactName: Property<String>

  /** SPDX license identifier per `group:artifact`. */
  @get:Input abstract val licenses: MapProperty<String, String>

  /**
   * Original NOTICE text (Apache-2.0 §4(d)), given explicitly, per `group:artifact`, or per `group`
   * for a NOTICE shared by every bundled module of the group (written once, after the modules).
   * Every key needs an entry in [textVersions].
   */
  @get:Input abstract val noticeTexts: MapProperty<String, String>

  /**
   * License text per `group:artifact` (copyright line, conditions, disclaimer), for licenses whose
   * binary redistribution requires it (BSD-3-Clause condition 2, MIT). Every key needs an entry in
   * [textVersions].
   */
  @get:Input abstract val licenseTexts: MapProperty<String, String>

  /**
   * The bundled version each key of [noticeTexts] and [licenseTexts] was copied for. Generation fails
   * when a module the key covers has another version (the text must be refreshed from the new
   * release) or when the key covers no bundled module.
   */
  @get:Input abstract val textVersions: MapProperty<String, String>

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
    licenseTexts.convention(emptyMap())
    textVersions.convention(emptyMap())
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

  /** Every explicit text belongs to the bundled version it was copied for. */
  private fun checkTextVersions(modules: List<ModuleComponentIdentifier>) {
    val versions = textVersions.get()
    val problems = mutableListOf<String>()
    for (key in (noticeTexts.get().keys + licenseTexts.get().keys).sorted()) {
      val expected = versions[key]
      if (expected == null) {
        problems += "$key: no entry in textVersions (the bundled version the text was copied for)"
        continue
      }
      val covered = modules.filter { if (key.contains(':')) "${it.group}:${it.module}" == key else it.group == key }
      if (covered.isEmpty()) problems += "$key: no bundled module matches (stale text)"
      covered
        .filter { it.version != expected }
        .forEach {
          problems +=
            "${it.group}:${it.module}:${it.version}: the text for '$key' was copied from version $expected; " +
              "replace it from the ${it.version} release"
        }
    }
    if (problems.isNotEmpty()) {
      throw GradleException(
        "generateThirdPartyNotices: license/NOTICE texts do not match the bundled modules (O-L04):\n" +
          problems.joinToString("\n") { "  $it" }
      )
    }
  }

  private fun StringBuilder.appendIndented(text: String, indent: String) {
    text.replace("\r\n", "\n").trim('\n').lines().forEach { line ->
      val trimmed = line.trimEnd()
      if (trimmed.isNotEmpty()) append(indent).append(trimmed)
      append('\n')
    }
  }

  @TaskAction
  fun generate() {
    val modules = DependencyGraph.externalModules(runtimeRoot.get())
    checkTextVersions(modules)
    val licenseMap = licenses.get()
    val explicitNotices = noticeTexts.get()
    val notices = noticesFromJars() + explicitNotices.filterKeys { it.contains(':') }
    val sharedNotices = explicitNotices.filterKeys { !it.contains(':') }
    val licenseTextMap = licenseTexts.get()
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
          append(ThirdPartyNotices.LICENSE_PREFIX).append(licenseMap[key] ?: "UNKNOWN").append('\n')
          licenseTextMap[key]?.let { license ->
            append(ThirdPartyNotices.LICENSE_TEXT_HEADING).append('\n')
            appendIndented(license, "    ")
          }
          notices[key]?.let { notice ->
            append(ThirdPartyNotices.NOTICE_HEADING).append('\n')
            appendIndented(notice, "    ")
          }
          if (module.group in sharedNotices) {
            append(ThirdPartyNotices.noticeReference(module.group)).append('\n')
          }
        }
        val usedShared = sharedNotices.filterKeys { group -> modules.any { it.group == group } }.toSortedMap()
        for ((group, notice) in usedShared) {
          append('\n')
          append(ThirdPartyNotices.SEPARATOR).append('\n')
          append(ThirdPartyNotices.sharedNoticeHeading(group)).append('\n')
          append('\n')
          appendIndented(notice, "  ")
        }
      }
    }
    outputFile.get().asFile.writeText(text, Charsets.UTF_8)
  }
}
