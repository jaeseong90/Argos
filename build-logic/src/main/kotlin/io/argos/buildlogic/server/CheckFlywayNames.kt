// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.server

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Checks the PostgreSQL migration file names of the server ([FlywayNames], ADR-0003 C-28). Part of
 * `check` (argos.java-server). A missing or empty directory passes: there is nothing to misnumber.
 */
@CacheableTask
abstract class CheckFlywayNames : DefaultTask() {

  /** The migration directory, `src/main/resources/db/pg`. */
  @get:Internal abstract val migrationsDirectory: DirectoryProperty

  /** Every file below [migrationsDirectory] (input fingerprint: names and contents). */
  @get:InputFiles
  @get:IgnoreEmptyDirectories
  @get:PathSensitive(PathSensitivity.RELATIVE)
  val migrationFiles: FileTree
    get() = migrationsDirectory.asFileTree

  @get:OutputFile abstract val marker: RegularFileProperty

  init {
    group = "verification"
    description =
      "Checks the PostgreSQL migration file names (V<NNN>__<lower_snake>.sql, contiguous from 001; ADR-0003 C-28)."
  }

  @TaskAction
  fun check() {
    val dir = migrationsDirectory.get().asFile
    val paths =
      if (dir.isDirectory) {
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toList()
      } else {
        emptyList()
      }
    val violations = FlywayNames.violations(paths)
    if (violations.isNotEmpty()) {
      throw GradleException(FlywayNames.failureMessage(dir.path, violations))
    }
    marker.get().asFile.apply { parentFile.mkdirs() }.writeText(paths.sorted().joinToString("\n", postfix = "\n"))
  }
}
