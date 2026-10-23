// SPDX-License-Identifier: Apache-2.0
//
// argos.license-check: license allow-list checks (19 §5.5, DSN-19-04, O-L03, T-BLD-007). Applied by
// argos.java-common to every Java module.
//
//   generateLicenseReport  com.github.jk1.dependency-license-report collects the raw license data
//                          (POM, manifest, license files) of every checked configuration
//   checkLicenses          shipped configurations (runtimeClasspath of production modules: what the
//                          agent jar and the server package bundle) must use allowed licenses only
//                          (build-logic/allowed-licenses.json, build-logic/license-overrides.json)
//   checkDevOnlyLicenses   test/harness/sample configurations (every other *RuntimeClasspath, and the
//                          runtimeClasspath of development-only projects) may also use components
//                          listed in docs/licenses/dev-only.md
//
// checkLicenses is part of check (19 §3.2); CI runs both (19 §6.2). The report task of the plugin is
// not configuration-cache compatible (declared below), so a build that runs these checks (build,
// check, :agent:agent-dist:agentJar) does not store a configuration cache entry.

import com.github.jk1.license.render.ReportRenderer
import io.argos.buildlogic.ArgosProject
import io.argos.buildlogic.CheckDevOnlyLicenses
import io.argos.buildlogic.CheckLicenses
import io.argos.buildlogic.LicenseDataRenderer

plugins {
  base
  id("com.github.jk1.dependency-license-report")
}

val reportDir = layout.buildDirectory.dir("reports/dependency-license")
val licenseDataFile = reportDir.map { it.file(LicenseDataRenderer.FILE_NAME) }
val developmentOnly = ArgosProject.isDevelopmentOnly(path)

licenseReport {
  outputDir = reportDir.get().asFile.path
  projects = arrayOf(project)
  // Raw data only: Argos maps license names itself (LicensePolicy), conservatively.
  filters = arrayOf()
  renderers = arrayOf<ReportRenderer>(LicenseDataRenderer(licenseDataFile.get().asFile))
  excludeOwnGroup = true
  excludeBoms = true
  // Maven semantics: a POM that declares licenses replaces those of its parents.
  unionParentPomLicenses = false
}

val generateLicenseReport =
  tasks.named("generateLicenseReport") {
    // The jk1 ReportTask holds the Project and reads it at execution time. Plugin 3.1.4 already
    // declares the task incompatible; it is declared here as well so that our builds do not depend
    // on that plugin detail. Gradle 9.8 still lists the task's serialization problems (warnings,
    // about 30 in `check`) and discards the entry: every build that runs checkLicenses (check,
    // build, :agent:agent-dist:agentJar) stores no configuration cache entry. Replacement: a
    // cache-compatible report task over ResolvedComponentResult and POM artifact views (WO-00
    // report, open issue).
    notCompatibleWithConfigurationCache(
      "com.github.jk1.dependency-license-report ReportTask uses the Project model at execution time"
    )
  }

val checkLicenses =
  tasks.register<CheckLicenses>("checkLicenses") {
    dependsOn(generateLicenseReport)
    reportFile = licenseDataFile
    allowedLicensesFile = layout.settingsDirectory.file("build-logic/allowed-licenses.json")
    overridesFile = layout.settingsDirectory.file("build-logic/license-overrides.json")
    resolvedLicensesFile = layout.buildDirectory.file("reports/argos-licenses/runtimeClasspath.properties")
  }

val checkDevOnlyLicenses =
  tasks.register<CheckDevOnlyLicenses>("checkDevOnlyLicenses") {
    dependsOn(generateLicenseReport)
    reportFile = licenseDataFile
    allowedLicensesFile = layout.settingsDirectory.file("build-logic/allowed-licenses.json")
    overridesFile = layout.settingsDirectory.file("build-logic/license-overrides.json")
    devOnlyFile = layout.settingsDirectory.file("docs/licenses/dev-only.md")
  }

// Test suites and their configurations are registered by other plugins after this one.
afterEvaluate {
  val runtimeClasspaths =
    configurations
      .filter { it.isCanBeResolved && it.name.endsWith("untimeClasspath") }
      .map { it.name }
      .filter { it == "runtimeClasspath" || it.endsWith("RuntimeClasspath") }
      .sorted()
  val shipped = if (developmentOnly) emptyList() else runtimeClasspaths.filter { it == "runtimeClasspath" }
  val devOnly = runtimeClasspaths - shipped.toSet()

  licenseReport.configurations = (shipped + devOnly).toTypedArray()

  fun rootOf(name: String) =
    configurations.named(name).flatMap { it.incoming.resolutionResult.rootComponent }

  checkLicenses.configure { shipped.forEach { roots.put(it, rootOf(it)) } }
  checkDevOnlyLicenses.configure { devOnly.forEach { roots.put(it, rootOf(it)) } }
}

// 19 §3.2: checkLicenses is part of check. checkDevOnlyLicenses is a separate PR-required check
// (19 §6.2, pr.yml license job: ./gradlew checkLicenses checkDevOnlyLicenses).
tasks.named("check") { dependsOn(checkLicenses) }
