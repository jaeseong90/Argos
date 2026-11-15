// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.server

import com.google.cloud.tools.jib.api.buildplan.ContainerBuildPlan
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer
import com.google.cloud.tools.jib.api.buildplan.FileEntry
import com.google.cloud.tools.jib.api.buildplan.LayerObject
import com.google.cloud.tools.jib.gradle.extension.GradleData
import com.google.cloud.tools.jib.gradle.extension.JibGradlePluginExtension
import com.google.cloud.tools.jib.plugins.extension.ExtensionLogger
import com.google.cloud.tools.jib.plugins.extension.JibPluginExtensionException
import java.nio.file.Files
import java.util.Optional

/**
 * Jib plugin extension that gives the writable directories of the server image to its non-root user
 * (ADR-0003 note on 17 §16 #3, WO-40). Build time only: it runs inside the Jib Gradle task and is
 * never part of the image.
 *
 * Jib adds the directories [ServerImage.WRITABLE_DIRECTORIES] from `extraDirectories` with mode
 * 0700, but its Gradle DSL has no owner setting, so every entry would be owned by root (0:0) and the
 * server (uid 10001) could not write there. This extension rewrites the owner of exactly those
 * entries in the container build plan. A Docker named volume mounted on such a path for the first
 * time copies the owner and mode of the image directory, so `argos-data` and `argos-logs` start as
 * 10001:10001, 0700 without an init container, a root entrypoint or a world-writable mode.
 *
 * Properties (set by argos.java-server):
 * - [PATHS]: comma-separated absolute paths in the image, matched exactly (no globs, no recursion).
 *   Each must be a directory entry of the build plan.
 * - [OWNERSHIP]: numeric `uid:gid`.
 *
 * Fail closed: a malformed property, a path without an entry or an entry that is not a directory
 * fails the image build, so an image never ships with a root-owned data directory unnoticed.
 *
 * Jib discovers the class with `ServiceLoader` (META-INF/services of build-logic) and runs it when
 * `jib.pluginExtensions` names it. Compiled against the extension API versions that
 * jib-gradle-plugin 3.5.4 loads (gradle/libs.versions.toml), so an API change shows at compile time.
 */
class ImageOwnershipExtension : JibGradlePluginExtension<Void> {

  override fun getExtraConfigType(): Optional<Class<Void>> = Optional.empty()

  override fun extendContainerBuildPlan(
    buildPlan: ContainerBuildPlan,
    properties: Map<String, String>,
    extraConfig: Optional<Void>,
    gradleData: GradleData,
    logger: ExtensionLogger,
  ): ContainerBuildPlan {
    try {
      val paths = parsePaths(properties[PATHS])
      val ownership = parseOwnership(properties[OWNERSHIP])
      val result = withOwnership(buildPlan, paths, ownership)
      logger.log(
        ExtensionLogger.LogLevel.INFO,
        "Image directories owned by $ownership: ${paths.joinToString(", ")}",
      )
      return result
    } catch (e: IllegalArgumentException) {
      throw JibPluginExtensionException(ImageOwnershipExtension::class.java, e.message ?: "invalid configuration", e)
    }
  }

  companion object {
    /** Property: comma-separated absolute directory paths in the image. */
    const val PATHS: String = "paths"

    /** Property: numeric `uid:gid` of the directories. */
    const val OWNERSHIP: String = "ownership"

    private val NUMERIC_OWNERSHIP = Regex("[0-9]+:[0-9]+")

    /** `"/data, /logs"` -> `[/data, /logs]`; absolute, normalized, not `/`, at least one. */
    fun parsePaths(value: String?): Set<String> {
      val paths = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
      require(paths.isNotEmpty()) { "Property '$PATHS' must list at least one absolute path" }
      paths.forEach {
        require(it.startsWith("/") && it != "/" && !it.endsWith("/") && !it.contains("//") &&
          it.split('/').none { segment -> segment == "." || segment == ".." }) {
          "Property '$PATHS': '$it' is not a normalized absolute path below /"
        }
      }
      return paths.toCollection(LinkedHashSet())
    }

    /** Numeric `uid:gid` only: names would depend on the passwd file of the base image. */
    fun parseOwnership(value: String?): String {
      val ownership = value.orEmpty().trim()
      require(NUMERIC_OWNERSHIP.matches(ownership)) {
        "Property '$OWNERSHIP' must be a numeric uid:gid, got '$ownership'"
      }
      return ownership
    }

    /**
     * Returns [plan] with [ownership] on the file entries whose extraction path is one of [paths];
     * every other entry and layer is unchanged.
     *
     * @throws IllegalArgumentException when a path has no entry or an entry is not a directory
     */
    fun withOwnership(plan: ContainerBuildPlan, paths: Set<String>, ownership: String): ContainerBuildPlan {
      val found = HashSet<String>()
      val layers: List<LayerObject> =
        plan.layers.map { layer ->
          if (layer !is FileEntriesLayer) {
            layer
          } else {
            val entries =
              layer.entries.map { entry ->
                val path = entry.extractionPath.toString()
                if (path !in paths) {
                  entry
                } else {
                  require(Files.isDirectory(entry.sourceFile)) {
                    "$path is not a directory in the image (source ${entry.sourceFile})"
                  }
                  found += path
                  FileEntry(entry.sourceFile, entry.extractionPath, entry.permissions, entry.modificationTime, ownership)
                }
              }
            layer.toBuilder().setEntries(entries).build()
          }
        }
      val missing = paths - found
      require(missing.isEmpty()) {
        "No image entry for ${missing.joinToString(", ")}: add the directories to jib.extraDirectories"
      }
      return plan.toBuilder().setLayers(layers).build()
    }
  }
}
