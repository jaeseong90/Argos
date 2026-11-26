// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.notices

import io.argos.buildlogic.CheckLicenses
import io.argos.buildlogic.GenerateDepsManifest
import io.argos.buildlogic.GenerateThirdPartyNotices
import java.io.File
import java.util.jar.JarFile
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/**
 * An upstream text kept verbatim in a project's `src/notices` (copied once, at development time,
 * from the release tag of the bundled version; nothing is downloaded at build or run time).
 *
 * @property file file name below the notices directory
 * @property source URL of the upstream file at that tag (written above the text)
 * @property heading heading of a text appended to another one (e.g. the license of code a module
 *   embeds); null for the first text of a key
 */
data class UpstreamText(val file: String, val source: String, val heading: String? = null)

/** Texts of [BundledNoticesSpec], per `group:artifact` or `group`, with the version they belong to. */
class BundledNoticesSpec {
  internal val licenseTexts = linkedMapOf<String, Pair<String, List<UpstreamText>>>()
  internal val noticeTexts = linkedMapOf<String, Pair<String, List<UpstreamText>>>()

  internal val extraModules = linkedMapOf<String, String>()

  /** Directory of the upstream texts, relative to the project directory. */
  var noticesDirectory: String = "src/notices"

  /**
   * Leaves out the modules whose jar is a Spring Boot dependencies-only starter (manifest
   * `Spring-Boot-Jar-Type: dependencies-starter`, no classes): the Boot plugin does not package
   * them into the boot jar.
   */
  var excludeSpringBootStarters: Boolean = false

  /**
   * A module the packaging adds from outside the dependency graph (`group:artifact:version`), e.g.
   * the Spring Boot loader and jarmode tools that the Boot plugin copies into the boot jar.
   */
  fun extraModule(coordinates: String, license: String) {
    extraModules[coordinates] = license
  }

  /**
   * License text of a module (`group:artifact`) whose jar carries none, copied for [version]; more
   * [texts] are appended under their heading.
   */
  fun licenseText(key: String, version: String, vararg texts: UpstreamText) {
    licenseTexts[key] = version to texts.toList()
  }

  /** Original NOTICE of a module (`group:artifact`) or of every module of a group (`group`). */
  fun noticeText(key: String, version: String, vararg texts: UpstreamText) {
    noticeTexts[key] = version to texts.toList()
  }
}

/** The tasks [registerBundledNotices] registers. */
class BundledNotices(
  val depsManifest: TaskProvider<GenerateDepsManifest>,
  val thirdPartyNotices: TaskProvider<GenerateThirdPartyNotices>,
) {
  /** META-INF/argos-deps.txt. */
  val depsFile = depsManifest.flatMap { it.outputFile }

  /** META-INF/THIRD-PARTY-NOTICES. */
  val noticesFile = thirdPartyNotices.flatMap { it.outputFile }
}

/**
 * Registers `generateDepsManifest` and `generateThirdPartyNotices` (19 §5.5, O-L04, SEC-77) for an
 * artifact that bundles the `runtimeClasspath` of this project unshaded (the server jar and image,
 * the demo app image), as the agent jar does for its shaded classes. Licenses come from
 * `checkLicenses` (the same report as the allow-list check), so the files cannot be generated with
 * a dependency outside the allow-list. License texts of modules whose license is not Apache-2.0
 * come from the module's own jar (`META-INF/LICENSE*`) or, when the jar has none, from [configure]
 * ([BundledNoticesSpec.licenseText]); NOTICE files of the jars are copied, and NOTICE texts of
 * projects whose jars carry none are given with [BundledNoticesSpec.noticeText].
 *
 * @param artifactName the artifact named in the file headers (e.g. `argos-server-1.0.0.jar`)
 * @param outputDirectory where both files are written
 */
fun Project.registerBundledNotices(
  artifactName: String,
  outputDirectory: Provider<Directory>,
  configure: BundledNoticesSpec.() -> Unit = {},
): BundledNotices {
  val spec = BundledNoticesSpec().apply(configure)
  val runtime = configurations.named("runtimeClasspath")
  val runtimeRoot = runtime.flatMap { it.incoming.resolutionResult.rootComponent }
  val shippedLicenses =
    tasks.named<CheckLicenses>("checkLicenses").flatMap { it.resolvedLicensesFile }.map {
      CheckLicenses.readResolved(it.asFile)
    }
  val noticesDir = layout.projectDirectory.dir(spec.noticesDirectory)

  fun text(texts: List<UpstreamText>): Provider<String> =
    texts
      .map { upstream ->
        providers.fileContents(noticesDir.file(upstream.file)).asText.map { body ->
          val block = "Source: ${upstream.source}\n\n${body.replace("\r\n", "\n").trim('\n')}"
          if (upstream.heading == null) block else "${upstream.heading}:\n$block"
        }
      }
      .reduce { left, right -> left.zip(right) { a, b -> "$a\n\n$b" } }

  val bundledArtifacts = runtime.get().incoming.artifacts
  val excludeStarters = spec.excludeSpringBootStarters
  val excluded =
    bundledArtifacts.resolvedArtifacts.map { artifacts ->
      if (!excludeStarters) emptySet()
      else
        artifacts
          .filter { isSpringBootStarter(it.file) }
          .mapNotNull { artifact ->
            (artifact.id.componentIdentifier as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module}" }
          }
          .toSet()
    }
  val extra = spec.extraModules.toMap()

  val depsManifest =
    tasks.register<GenerateDepsManifest>("generateDepsManifest") {
      this.runtimeRoot.set(runtimeRoot)
      this.artifactName.set(artifactName)
      licenses.set(shippedLicenses)
      excludedModules.set(excluded)
      extraModules.set(extra)
      outputFile.set(outputDirectory.map { it.file("argos-deps.txt") })
    }

  val thirdPartyNotices =
    tasks.register<GenerateThirdPartyNotices>("generateThirdPartyNotices") {
      this.runtimeRoot.set(runtimeRoot)
      this.artifactName.set(artifactName)
      licenses.set(shippedLicenses)
      relocated.set(false)
      licenseTextsFromJars.set(true)
      excludedModules.set(excluded)
      extraModules.set(extra)
      noticeSources.from(bundledArtifacts.artifactFiles)
      noticeSourceKeys.set(
        bundledArtifacts.resolvedArtifacts.map { artifacts ->
          artifacts.mapNotNull { artifact ->
            val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
            id?.let { "${artifact.file.name}=${it.group}:${it.module}" }
          }
        }
      )
      spec.licenseTexts.forEach { (key, value) -> licenseTexts.put(key, text(value.second)) }
      spec.noticeTexts.forEach { (key, value) -> noticeTexts.put(key, text(value.second)) }
      textVersions.set((spec.licenseTexts + spec.noticeTexts).mapValues { it.value.first })
      outputFile.set(outputDirectory.map { it.file("THIRD-PARTY-NOTICES") })
    }
  return BundledNotices(depsManifest, thirdPartyNotices)
}

/** Whether [jar] is a Spring Boot dependencies-only starter (the Boot plugin leaves it out). */
internal fun isSpringBootStarter(jar: File): Boolean =
  jar.isFile &&
    jar.name.endsWith(".jar") &&
    JarFile(jar).use { it.manifest?.mainAttributes?.getValue("Spring-Boot-Jar-Type") == "dependencies-starter" }
