// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.notices

import io.argos.buildlogic.ThirdPartyNotices
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * The notice files every shipped artifact carries in one directory (O-L01, O-L04, SEC-77, 19 §5.5):
 * `LICENSE` (the Apache License 2.0), `NOTICE` (the Argos NOTICE) and, when it bundles third-party
 * modules, `argos-deps.txt` (`group:artifact:version:license`, at least one module, no `UNKNOWN`
 * license) with a `THIRD-PARTY-NOTICES` that is complete for it ([ThirdPartyNotices.violations]).
 * Shared by the server jar check ([ServerJarChecks], directory `META-INF/`) and the demo image
 * check ([VerifyImageNotices], `/opt/argos-demo`).
 */
object NoticeChecks {

  /** Problems found, and the parsed argos-deps.txt (null when missing or malformed). */
  class Result(val problems: List<String>, val dependencies: List<ThirdPartyNotices.Dependency>?)

  /**
   * Checks the files `<directory>LICENSE`, `NOTICE`, and (with [thirdParty]) `argos-deps.txt` and
   * `THIRD-PARTY-NOTICES`, read through [read] (null: missing).
   */
  fun check(read: (String) -> String?, directory: String, thirdParty: Boolean, noticeRequired: List<String>): Result {
    val problems = mutableListOf<String>()
    fun required(name: String): String? {
      val path = directory + name
      val text = read(path)
      when {
        text == null -> problems += "$path: missing"
        text.isBlank() -> problems += "$path: empty"
        else -> return text
      }
      return null
    }
    required("LICENSE")?.let {
      if (!it.contains("Apache License") || !it.contains("Version 2.0")) {
        problems += "${directory}LICENSE: not the Apache License 2.0"
      }
    }
    required("NOTICE")?.let { if (!it.contains("Argos APM")) problems += "${directory}NOTICE: not the Argos NOTICE" }
    if (!thirdParty) return Result(problems, null)

    val dependencies =
      required("argos-deps.txt")?.let { text ->
        try {
          ThirdPartyNotices.parseDependencies(text).also { parsed ->
            if (parsed.isEmpty()) problems += "${directory}argos-deps.txt: lists no module"
            parsed
              .filter { it.license == "UNKNOWN" }
              .forEach { problems += "${directory}argos-deps.txt: $it has no license" }
          }
        } catch (e: GradleException) {
          problems += "${directory}argos-deps.txt: ${e.message}"
          null
        }
      }
    required("THIRD-PARTY-NOTICES")?.let { notices ->
      if (dependencies != null) {
        ThirdPartyNotices.violations(notices, dependencies, noticeRequired)
          .forEach { problems += "${directory}THIRD-PARTY-NOTICES: $it" }
      }
    }
    return Result(problems, dependencies)
  }

  /** [check] on a directory of files. */
  fun checkDirectory(root: File, directory: String, thirdParty: Boolean, noticeRequired: List<String>): Result =
    check(
      { path -> File(root, path).takeIf { it.isFile }?.readText(Charsets.UTF_8) },
      directory,
      thirdParty,
      noticeRequired,
    )
}

/**
 * checkDemoImageFiles (T-BLD-003b): the staged extra files of a demo image carry LICENSE, NOTICE
 * and, when the image bundles third-party libraries, argos-deps.txt and THIRD-PARTY-NOTICES
 * ([NoticeChecks]) in [directory].
 */
abstract class VerifyImageNotices : DefaultTask() {

  /** The staged root of the image's extra directory (Jib extraDirectories). */
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val imageFiles: DirectoryProperty

  /** Directory of the notices below [imageFiles], with a trailing slash (e.g. `opt/argos-demo/`). */
  @get:Input abstract val directory: Property<String>

  /** Whether the image bundles third-party libraries (argos-deps.txt and THIRD-PARTY-NOTICES). */
  @get:Input abstract val thirdParty: Property<Boolean>

  @get:Input abstract val noticeRequired: ListProperty<String>

  @get:OutputFile abstract val reportFile: RegularFileProperty

  init {
    group = "verification"
    description = "Checks LICENSE, NOTICE, argos-deps.txt and THIRD-PARTY-NOTICES of the image files (T-BLD-003b)."
    noticeRequired.convention(emptyList())
  }

  @TaskAction
  fun verify() {
    val result =
      NoticeChecks.checkDirectory(imageFiles.get().asFile, directory.get(), thirdParty.get(), noticeRequired.get())
    reportFile.get().asFile.apply { parentFile.mkdirs() }
      .writeText(
        buildString {
          append("image notices in /${directory.get()} (third-party: ${thirdParty.get()}), ")
          append("${result.dependencies?.size ?: 0} bundled module(s)\n")
          append(if (result.problems.isEmpty()) "result: ok\n" else "result: ${result.problems.size} violation(s)\n")
          result.problems.forEach { append("  ").append(it).append('\n') }
        },
        Charsets.UTF_8,
      )
    if (result.problems.isNotEmpty()) {
      throw GradleException(
        "checkDemoImageFiles: the image notices are incomplete (T-BLD-003b):\n" +
          result.problems.joinToString("\n") { "  $it" }
      )
    }
  }
}
