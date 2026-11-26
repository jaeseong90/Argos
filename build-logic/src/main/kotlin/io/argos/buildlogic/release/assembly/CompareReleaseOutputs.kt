// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.assembly

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * Compares two release outputs ([CompareArchives]) and writes the report to [report]; fails when
 * they differ. Used by test/release/verify-reproducible.sh (`run` and `compare`).
 */
@UntrackedTask(because = "Compares directories given on the command line; always runs")
abstract class CompareReleaseOutputs : DefaultTask() {

  /** Absolute path of output A. */
  @get:Input abstract val directoryA: Property<String>

  /** Absolute path of output B. */
  @get:Input abstract val directoryB: Property<String>

  @get:OutputFile abstract val report: RegularFileProperty

  @TaskAction
  fun compare() {
    if (directoryA.get().isBlank() || directoryB.get().isBlank()) {
      throw GradleException(
        "compareReleaseOutputs: set -P${PROPERTY_A}=<dir> and -P${PROPERTY_B}=<dir> " +
          "(or run test/release/verify-reproducible.sh compare <dirA> <dirB>)"
      )
    }
    val a = java.io.File(directoryA.get())
    val b = java.io.File(directoryB.get())
    if (!a.isDirectory || !b.isDirectory) {
      throw GradleException("compareReleaseOutputs: both A ($a) and B ($b) must be directories")
    }
    val result = CompareArchives.compareDirectories(a, b)
    val text = CompareArchives.render(result, a, b)
    val file = report.get().asFile
    file.parentFile.mkdirs()
    file.writeText(text, Charsets.UTF_8)
    logger.lifecycle(text.trimEnd())
    if (!result.identical) {
      throw GradleException("Release outputs differ (${result.differences.size} difference(s)); report: $file")
    }
  }

  companion object {
    /** Gradle property: directory A. */
    const val PROPERTY_A: String = "argos.release.compare.a"

    /** Gradle property: directory B. */
    const val PROPERTY_B: String = "argos.release.compare.b"

    /** Gradle property: report file (default build/reproducibility/compare-report.txt). */
    const val PROPERTY_REPORT: String = "argos.release.compare.report"
  }
}
