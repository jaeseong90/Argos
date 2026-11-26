// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.notices

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.argos.buildlogic.ThirdPartyNotices
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * What the server boot jar must carry (T-BLD-003a; O-L01, O-L04, SEC-70, SEC-77, ADR-0010 C-153,
 * 19 §3.5, §7.10):
 * - `META-INF/LICENSE` (the Apache License 2.0) and `META-INF/NOTICE` (the Argos NOTICE);
 * - `META-INF/argos-deps.txt` (`group:artifact:version:license` lines, at least one module) that
 *   names exactly the third-party jars of `BOOT-INF/lib` (every other jar there is an Argos module,
 *   `argos-*`) and the Spring Boot loader at the jar root ([ROOT_MODULES]), and
 *   `META-INF/THIRD-PARTY-NOTICES` that is complete for it
 *   ([ThirdPartyNotices.violations]);
 * - `META-INF/sbom/server.cdx.json`: CycloneDX JSON with at least one component, listing every
 *   module of argos-deps.txt with its version;
 * - the About lists `META-INF/argos/licenses/server.json` (= argos-deps.txt) and `agent.json`
 *   (non-empty), and `ui.json` exactly when the UI is expected (`uiExpected`), which also requires
 *   the bundle (`BOOT-INF/classes/static/index.html`);
 * - `META-INF/build-info.properties` with the expected version, a commit and the protocol version.
 *
 * META-INF entries are at the root of the boot jar: the Spring Boot plugin moves the `META-INF`
 * of the application's classes there (build-info.properties included), and the launcher's class
 * loader finds them (`LicenseCatalog`, `ServerBuild`).
 */
object ServerJarChecks {
  const val CLASSES = "BOOT-INF/classes/"
  const val LIB = "BOOT-INF/lib/"
  const val SBOM = "META-INF/sbom/server.cdx.json"
  const val BUILD_INFO = "META-INF/build-info.properties"
  const val UI_INDEX = "${CLASSES}static/index.html"

  /**
   * Modules whose classes the Spring Boot plugin copies to the jar root instead of BOOT-INF/lib:
   * `group:artifact` to the package directory that must be there.
   */
  val ROOT_MODULES: Map<String, String> =
    mapOf("org.springframework.boot:spring-boot-loader" to "org/springframework/boot/loader/")

  fun listEntry(part: String) = "${LicenseLists.DIRECTORY}/$part.json"

  /**
   * Violations of the rules above in [jar]. [noticeRequired] as in [ThirdPartyNotices.violations];
   * [expectedVersion] is the project version.
   */
  fun violations(jar: File, expectedVersion: String, uiExpected: Boolean, noticeRequired: List<String>): List<String> {
    val problems = mutableListOf<String>()
    ZipFile(jar).use { zip ->
      fun read(name: String): String? =
        zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } }

      fun required(name: String): String? =
        read(name).also {
          if (it == null) problems += "$name: missing" else if (it.isBlank()) problems += "$name: empty"
        }?.takeIf { it.isNotBlank() }

      val notices = NoticeChecks.check(::read, "META-INF/", thirdParty = true, noticeRequired = noticeRequired)
      problems += notices.problems
      val dependencies = notices.dependencies

      if (dependencies != null) {
        val libs =
          zip.entries().toList().map { it.name }.filter { it.startsWith(LIB) && it.endsWith(".jar") }
            .map { it.removePrefix(LIB) }
        fun jarOf(dependency: ThirdPartyNotices.Dependency) =
          libs.firstOrNull { it == "${dependency.artifact}-${dependency.version}.jar" }
            ?: libs.firstOrNull { it.startsWith("${dependency.artifact}-${dependency.version}-") }
        val matched = mutableSetOf<String>()
        for (dependency in dependencies) {
          val rootPackage = ROOT_MODULES[dependency.key]
          if (rootPackage != null) {
            if (zip.getEntry(rootPackage) == null) {
              problems += "META-INF/argos-deps.txt: $dependency is not at the jar root ($rootPackage)"
            }
            continue
          }
          val lib = jarOf(dependency)
          if (lib == null) problems += "META-INF/argos-deps.txt: $dependency has no jar in $LIB"
          else matched += lib
        }
        (libs - matched).filterNot { it.startsWith("argos-") }.sorted().forEach {
          problems += "$LIB$it: bundled but not listed in META-INF/argos-deps.txt"
        }
      }

      required(SBOM)?.let { text -> problems += sbomViolations(text, dependencies.orEmpty()).map { "$SBOM: $it" } }
      // One SBOM only (Spring Boot would add its own, non-reproducible application.cdx.json).
      zip.entries().toList().map { it.name }
        .filter { it.startsWith("META-INF/sbom/") && !it.endsWith("/") && it != SBOM }
        .sorted()
        .forEach { problems += "$it: a second SBOM (only $SBOM is shipped)" }
      read("META-INF/MANIFEST.MF")?.let { manifest ->
        val location =
          manifest.lines().firstOrNull { it.startsWith("Sbom-Location:") }?.substringAfter(':')?.trim()
        if (location != null && location != SBOM) {
          problems += "META-INF/MANIFEST.MF: Sbom-Location is '$location', expected '$SBOM'"
        }
      }

      required(listEntry("server"))?.let { text ->
        try {
          val listed = LicenseLists.parse(text)
          if (dependencies != null && listed != LicenseLists.fromDependencies(dependencies)) {
            problems += "${listEntry("server")}: differs from META-INF/argos-deps.txt (C-153)"
          }
        } catch (e: GradleException) {
          problems += "${listEntry("server")}: ${e.message}"
        }
      }
      required(listEntry("agent"))?.let { text -> listViolation(listEntry("agent"), text)?.let(problems::add) }
      val bundlesUi = zip.getEntry(UI_INDEX) != null
      val uiList = read(listEntry("ui"))
      when {
        uiExpected && !bundlesUi -> problems += "$UI_INDEX: missing (the UI bundle was expected)"
        uiExpected && uiList == null -> problems += "${listEntry("ui")}: missing (the jar bundles the UI)"
        uiExpected && uiList != null -> listViolation(listEntry("ui"), uiList)?.let(problems::add)
        !uiExpected && uiList != null ->
          problems += "${listEntry("ui")}: present in a build without the UI (stale list)"
      }

      required(BUILD_INFO)?.let { text ->
        val properties = Properties().apply { load(text.reader()) }
        val version = properties.getProperty("build.version")
        if (version != expectedVersion) {
          problems += "$BUILD_INFO: build.version is '$version', expected '$expectedVersion'"
        }
        listOf("build.commit", "build.protocol")
          .filter { properties.getProperty(it).isNullOrBlank() }
          .forEach { problems += "$BUILD_INFO: no $it" }
      }
    }
    return problems
  }

  private fun listViolation(name: String, text: String): String? =
    try {
      if (LicenseLists.parse(text).isEmpty()) "$name: lists no component" else null
    } catch (e: GradleException) {
      "$name: ${e.message}"
    }

  /**
   * A CycloneDX JSON document with at least one component, in which every module of
   * [dependencies] appears with its group, name and version (T-BLD-008).
   */
  fun sbomViolations(text: String, dependencies: List<ThirdPartyNotices.Dependency>): List<String> {
    val root =
      try {
        ObjectMapper().readTree(text)
      } catch (e: Exception) {
        return listOf("not JSON")
      }
    if (root.path("bomFormat").textValue() != "CycloneDX") return listOf("bomFormat is not CycloneDX")
    if (root.path("specVersion").textValue().isNullOrBlank()) return listOf("no specVersion")
    val present = mutableSetOf<String>()
    fun collect(components: JsonNode) {
      for (component in components) {
        present += "${component.path("group").asText()}:${component.path("name").asText()}:${component.path("version").asText()}"
        collect(component.path("components"))
      }
    }
    collect(root.path("components"))
    if (present.isEmpty()) return listOf("no components")
    return dependencies
      .filter { "${it.group}:${it.artifact}:${it.version}" !in present }
      .map { "${it.group}:${it.artifact}:${it.version} is not a component" }
  }
}

/** verifyServerJar (T-BLD-003a): [ServerJarChecks] on the boot jar; finalizes bootJar. */
abstract class VerifyServerJar : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val jar: RegularFileProperty

  @get:Input abstract val expectedVersion: Property<String>

  /** Whether the build bundles the UI (false with -Pargos.ui.skip=true). */
  @get:Input abstract val uiExpected: Property<Boolean>

  /** `group` or `group:artifact` of bundled modules whose upstream project has a NOTICE file. */
  @get:Input abstract val noticeRequired: ListProperty<String>

  @get:OutputFile abstract val reportFile: RegularFileProperty

  init {
    group = "verification"
    description =
      "Verifies the server boot jar: LICENSE, NOTICE, THIRD-PARTY-NOTICES, argos-deps.txt, SBOM, About lists, build info (T-BLD-003a)."
    noticeRequired.convention(emptyList())
  }

  @TaskAction
  fun verify() {
    val file = jar.get().asFile
    val problems = ServerJarChecks.violations(file, expectedVersion.get(), uiExpected.get(), noticeRequired.get())
    reportFile.get().asFile.apply { parentFile.mkdirs() }
      .writeText(
        buildString {
          append("verifyServerJar ${file.name} (UI expected: ${uiExpected.get()})\n")
          append(if (problems.isEmpty()) "result: ok\n" else "result: ${problems.size} violation(s)\n")
          problems.forEach { append("  ").append(it).append('\n') }
        },
        Charsets.UTF_8,
      )
    if (problems.isNotEmpty()) {
      throw GradleException(
        "verifyServerJar: ${file.name} is incomplete (T-BLD-003a):\n" + problems.joinToString("\n") { "  $it" }
      )
    }
    logger.lifecycle("verifyServerJar: ${file.name}: notices, dependency manifest, SBOM, About lists and build info complete")
  }
}
