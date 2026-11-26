// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.testing

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Finalizer of one `matrixTest-<ID>` task: writes `build/matrix/<ID>.json` (MatrixResults) from the
 * task's JUnit XML and the files its tests left in `build/matrix/<ID>/`. Runs after a failed test
 * task too, so a failing combination still has a result.
 */
@DisableCachingByDefault(because = "Reads the latest test results and the run environment")
abstract class MatrixResultTask : DefaultTask() {
  @get:Input abstract val combination: Property<String>

  @get:Input abstract val testTaskPath: Property<String>

  @get:Input abstract val requirements: ListProperty<String>

  /** Gradle properties the `prop:` requirements look at (resolved when the build was configured). */
  @get:Input abstract val gradleProperties: MapProperty<String, String>

  @get:Input abstract val commit: Property<String>

  @get:Internal abstract val junitXmlDirectory: DirectoryProperty

  @get:Internal abstract val resultDirectory: DirectoryProperty

  @get:OutputFile abstract val outputFile: RegularFileProperty

  init {
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun write() {
    val os = System.getProperty("os.name").orEmpty()
    val missing = MatrixResults.missingRequirements(requirements.get(), System::getenv, gradleProperties.get(), os)
    val result =
      MatrixResults.collect(
        id = combination.get(),
        task = testTaskPath.get(),
        xmlDir = junitXmlDirectory.get().asFile,
        resultDir = resultDirectory.get().asFile,
        commit = commit.get(),
        os = os,
        missingRequirements = missing,
      )
    val out = outputFile.get().asFile
    out.parentFile.mkdirs()
    out.writeText(result.toJson(), Charsets.UTF_8)
    logger.lifecycle(
      "[matrix] ${result.id}: ${result.passed} passed, ${result.failed} failed, ${result.skipped} skipped" +
        (if (missing.isEmpty()) "" else ", missing ${missing.joinToString(", ")}") + " -> ${out.path}"
    )
  }
}

/** `matrixReport`: generates docs/compatibility-matrix.md (MatrixReport) from the latest results. */
@DisableCachingByDefault(because = "Writes a file in the source tree")
abstract class MatrixReportTask : DefaultTask() {
  @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val catalogFile: RegularFileProperty

  @get:InputFile
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val communityFile: RegularFileProperty

  /** `build/matrix`, or the folder of the downloaded nightly artifacts (searched recursively). */
  @get:Internal abstract val resultsDirectory: DirectoryProperty

  @get:OutputFile abstract val reportFile: RegularFileProperty

  /**
   * One line per gate row whose latest result failed (empty file when none): the nightly report job
   * fails on a non-empty file after it has uploaded the page, so a failing combination is published
   * and still turns the run red.
   */
  @get:OutputFile abstract val gateFailuresFile: RegularFileProperty

  init {
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun generate() {
    val catalog = MatrixCatalog.load(catalogFile.get().asFile)
    val community =
      communityFile.orNull?.asFile?.takeIf(File::isFile)?.let {
        MatrixReport.parseCommunity(it.readText(Charsets.UTF_8), it.name)
      }.orEmpty()
    val dir = resultsDirectory.get().asFile
    val results = MatrixReport.readResults(dir)
    val unknown = results.keys.filter { catalog.find(it) == null }
    if (unknown.isNotEmpty()) logger.warn("WARNING: results for rows no longer in matrix.yaml are ignored: ${unknown.joinToString(", ")}")
    val text = MatrixReport.render(catalog, results, community)
    val out = reportFile.get().asFile
    out.parentFile.mkdirs()
    out.writeText(text, Charsets.UTF_8)
    val failures = MatrixReport.gateFailures(catalog, results)
    val failuresFile = gateFailuresFile.get().asFile
    failuresFile.parentFile.mkdirs()
    failuresFile.writeText(failures.joinToString("") { "$it\n" }, Charsets.UTF_8)
    logger.lifecycle("[matrix] ${results.size} results from ${dir.path} -> ${out.path}; ${failures.size} gate failures")
    failures.forEach { logger.lifecycle("[matrix] gate failure: $it") }
  }
}
