// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Inputs shared by [CheckLicenses] and [CheckDevOnlyLicenses]. */
abstract class AbstractLicenseCheck : DefaultTask() {

  /** Raw license data written by [LicenseDataRenderer] in com.github.jk1.dependency-license-report. */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val reportFile: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val allowedLicensesFile: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val overridesFile: RegularFileProperty

  /** Checked configurations: name -> resolved graph root. */
  @get:Input abstract val roots: MapProperty<String, ResolvedComponentResult>

  /** Every external, non-platform module of [roots] with the configurations it comes from. */
  protected fun modules(): Map<ModuleComponentIdentifier, List<String>> {
    val result = sortedMapOf<String, Pair<ModuleComponentIdentifier, MutableList<String>>>()
    for ((configuration, root) in roots.get()) {
      for (component in DependencyGraph.reachable(root)) {
        val id = component.id as? ModuleComponentIdentifier ?: continue
        if (DependencyGraph.isPlatform(component)) continue
        result.getOrPut(id.displayName) { id to mutableListOf() }.second += configuration
      }
    }
    return result.values.associate { it.first to it.second.distinct() }
  }

  protected data class Finding(
    val module: ModuleComponentIdentifier,
    val configurations: List<String>,
    val license: ResolvedLicense?,
  ) {
    fun describe(): String {
      val where = configurations.joinToString(", ")
      val coordinates = "${module.group}:${module.module}:${module.version} [$where]"
      if (license == null) return "$coordinates: not in the license report"
      val declared =
        if (license.declared.isEmpty()) "no license metadata"
        else "declared: " + license.declared.joinToString("; ")
      val expression = license.expression ?: "UNKNOWN"
      return "$coordinates: $expression ($declared${if (license.origin == "override") ", override" else ""})"
    }
  }

  /** Resolves the license of every module; modules missing from the report get a null license. */
  protected fun evaluate(policy: LicensePolicy): List<Finding> {
    val report = LicensePolicy.readReport(reportFile.get().asFile)
    return modules().map { (module, configurations) ->
      val key = "${module.group}:${module.module}:${module.version}"
      val declared = report[key]
      Finding(
        module,
        configurations,
        declared?.let { policy.resolve("${module.group}:${module.module}", module.version, it) },
      )
    }
  }

  protected fun policy(): LicensePolicy =
    LicensePolicy.load(allowedLicensesFile.get().asFile, overridesFile.get().asFile)
}

/**
 * checkLicenses (19 §5.5, O-L03, T-BLD-007): every third-party module on a shipped configuration
 * (the runtime classpath of production modules, which the agent jar and the server package bundle)
 * must have an allowed license. Also writes `group:artifact:version=<SPDX>` for every checked
 * module, which feeds META-INF/argos-deps.txt and THIRD-PARTY-NOTICES.
 */
abstract class CheckLicenses : AbstractLicenseCheck() {

  @get:OutputFile abstract val resolvedLicensesFile: RegularFileProperty

  init {
    group = "verification"
    description = "Checks shipped dependencies against the license allow-list (O-L03, T-BLD-007)."
  }

  @TaskAction
  fun check() {
    val policy = policy()
    val findings = evaluate(policy)
    writeResolved(findings, resolvedLicensesFile.get().asFile)
    val violations = findings.filter { it.license?.allowed != true }
    if (violations.isNotEmpty()) {
      throw GradleException(
        buildString {
          append("checkLicenses: ${violations.size} shipped dependenc")
          append(if (violations.size == 1) "y has" else "ies have")
          append(" no allowed license (O-L03):\n")
          violations.forEach { append("  ").append(it.describe()).append('\n') }
          append("Allowed (build-logic/allowed-licenses.json): ")
          append(policy.allowed.joinToString(", ")).append('\n')
          append("Remove the dependency. If its metadata is wrong or missing, add a mapping with a ")
          append("source URL to build-logic/license-overrides.json (reviewed in the PR).")
        }
      )
    }
    logger.lifecycle(
      "checkLicenses: ${findings.size} shipped third-party module(s) checked in " +
        "${roots.get().keys.sorted().ifEmpty { listOf("no shipped configuration") }.joinToString()}; " +
        "all licenses allowed"
    )
  }

  private fun writeResolved(findings: List<Finding>, file: File) {
    file.parentFile.mkdirs()
    file.writeText(
      findings.joinToString("") {
        "${it.module.group}:${it.module.module}:${it.module.version}=" +
          "${it.license?.expression ?: "UNKNOWN"}\n"
      },
      Charsets.UTF_8,
    )
  }

  companion object {
    /** Reads [resolvedLicensesFile] into `group:artifact` -> SPDX expression. */
    fun readResolved(file: File): Map<String, String> =
      if (!file.isFile) emptyMap()
      else
        file.readLines().filter { it.contains('=') }.associate { line ->
          val coordinates = line.substringBefore('=')
          coordinates.substringBeforeLast(':') to line.substringAfter('=')
        }
  }
}

/**
 * checkDevOnlyLicenses (19 §5.5): test, harness and sample-app configurations may use components
 * outside the allow-list only when docs/licenses/dev-only.md lists them.
 */
abstract class CheckDevOnlyLicenses : AbstractLicenseCheck() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val devOnlyFile: RegularFileProperty

  init {
    group = "verification"
    description = "Checks test-only dependencies against docs/licenses/dev-only.md (O-L03)."
  }

  @TaskAction
  fun check() {
    val policy = policy()
    val devOnly = LicensePolicy.readDevOnly(devOnlyFile.get().asFile)
    val findings = evaluate(policy)
    val violations =
      findings.filter { finding ->
        finding.license?.allowed != true &&
          !LicensePolicy.devOnlyMatches(devOnly, "${finding.module.group}:${finding.module.module}")
      }
    if (violations.isNotEmpty()) {
      throw GradleException(
        buildString {
          append("checkDevOnlyLicenses: ${violations.size} test-only dependenc")
          append(if (violations.size == 1) "y has" else "ies have")
          append(" a license outside the allow-list and no entry in docs/licenses/dev-only.md:\n")
          violations.forEach { append("  ").append(it.describe()).append('\n') }
          append("Add a row to the machine-readable table of docs/licenses/dev-only.md with the ")
          append("reason it is never shipped, or use an allow-listed alternative.")
        }
      )
    }
    val listed = findings.count { it.license?.allowed != true }
    logger.lifecycle(
      "checkDevOnlyLicenses: ${findings.size} test-only third-party module(s) checked in " +
        "${roots.get().size} configuration(s); $listed listed in docs/licenses/dev-only.md"
    )
  }
}
