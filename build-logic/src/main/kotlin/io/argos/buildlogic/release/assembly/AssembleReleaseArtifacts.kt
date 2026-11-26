// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.release.assembly

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Copies the release assets into [outputDirectory] under their release names and writes
 * `SHA256SUMS` ([ReleaseAssets.assemble]). [assetNames] and [assetSources] are parallel lists (the
 * n-th name is the release name of the n-th source path); [sources] carries the same files for
 * up-to-date checks and task dependencies.
 */
@DisableCachingByDefault(because = "Copies files; caching gains nothing")
abstract class AssembleReleaseArtifacts : DefaultTask() {

  /** Release file names, in asset order. */
  @get:Input abstract val assetNames: ListProperty<String>

  /** Absolute source paths, parallel to [assetNames]. */
  @get:Input abstract val assetSources: ListProperty<String>

  /** Release name to the task path that builds its source (for the missing-input message). */
  @get:Input abstract val assetBuiltBy: MapProperty<String, String>

  /** The source files (dependencies and change detection). */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val sources: ConfigurableFileCollection

  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun assemble() {
    val names = assetNames.get()
    val paths = assetSources.get()
    if (names.size != paths.size) throw GradleException("assetNames and assetSources differ in size")
    val target = outputDirectory.get().asFile
    try {
      ReleaseAssets.assemble(target, names.zip(paths.map { java.io.File(it) }), assetBuiltBy.get())
    } catch (e: IllegalStateException) {
      throw GradleException(e.message ?: "release assembly failed", e)
    }
    logger.lifecycle("Release assets in $target:")
    java.io.File(target, ReleaseAssets.SHA256SUMS).readLines().forEach { logger.lifecycle("  $it") }
  }
}
