// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.server

import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath
import com.google.cloud.tools.jib.api.buildplan.ContainerBuildPlan
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer
import com.google.cloud.tools.jib.api.buildplan.FileEntry
import com.google.cloud.tools.jib.api.buildplan.FilePermissions
import com.google.cloud.tools.jib.gradle.extension.JibGradlePluginExtension
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.ServiceLoader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The build-time owner rewrite of the server image's writable directories (ADR-0003 note on
 * 17 §16 #3, WO-40). The image itself is checked with docker image inspect and a container run (WO-40
 * report); these tests pin the build plan transformation and the discovery that Jib relies on.
 */
@Tag("req:DSN-19-05")
class ImageOwnershipExtensionTest {

  @TempDir lateinit var source: Path

  private val modified = Instant.ofEpochSecond(1)

  private fun directory(relative: String): Path = Files.createDirectories(source.resolve(relative))

  private fun entry(local: Path, inImage: String, mode: String = "700"): FileEntry =
    FileEntry(local, AbsoluteUnixPath.get(inImage), FilePermissions.fromOctalString(mode), modified)

  private fun plan(vararg layers: FileEntriesLayer): ContainerBuildPlan =
    ContainerBuildPlan.builder().setLayers(layers.toList()).build()

  private fun extraLayer(): FileEntriesLayer =
    FileEntriesLayer.builder()
      .setName("extra files")
      .addEntry(entry(directory("data"), "/data"))
      .addEntry(entry(directory("data/tmp"), "/data/tmp"))
      .addEntry(entry(directory("logs"), "/logs"))
      .build()

  private fun appLayer(): FileEntriesLayer {
    val jar = Files.write(source.resolve("app.jar"), byteArrayOf(1, 2, 3))
    return FileEntriesLayer.builder()
      .setName("dependencies")
      .addEntry(entry(jar, "/opt/argos/app/libs/app.jar", "644"))
      .build()
  }

  private fun ownerOf(plan: ContainerBuildPlan): Map<String, String> =
    plan.layers.filterIsInstance<FileEntriesLayer>().flatMap { it.entries }.associate {
      it.extractionPath.toString() to it.ownership
    }

  @Test
  @DisplayName("sets uid:gid on exactly the configured directories; permissions and other entries unchanged")
  fun setsOwnerOfConfiguredDirectories() {
    val before = plan(appLayer(), extraLayer())

    val after = ImageOwnershipExtension.withOwnership(before, setOf("/data", "/data/tmp", "/logs"), "10001:10001")

    assertThat(ownerOf(after))
      .containsEntry("/data", "10001:10001")
      .containsEntry("/data/tmp", "10001:10001")
      .containsEntry("/logs", "10001:10001")
      .containsEntry("/opt/argos/app/libs/app.jar", "")
    val entries = after.layers.filterIsInstance<FileEntriesLayer>().flatMap { it.entries }
    assertThat(entries.filter { it.extractionPath.toString() != "/opt/argos/app/libs/app.jar" })
      .allMatch { it.permissions.toOctalString() == "700" && it.modificationTime == modified }
    assertThat(after.layers.map { it.name }).containsExactly("dependencies", "extra files")
  }

  @Test
  @DisplayName("fails closed: a configured path without an image entry stops the build")
  fun missingPathFails() {
    val withoutLogs =
      plan(
        FileEntriesLayer.builder().setName("extra files").addEntry(entry(directory("data"), "/data")).build()
      )

    assertThatThrownBy { ImageOwnershipExtension.withOwnership(withoutLogs, setOf("/data", "/logs"), "10001:10001") }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("/logs")
  }

  @Test
  @DisplayName("only directories: a file at a configured path stops the build")
  fun fileEntryFails() {
    assertThatThrownBy {
        ImageOwnershipExtension.withOwnership(plan(appLayer()), setOf("/opt/argos/app/libs/app.jar"), "10001:10001")
      }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("not a directory")
  }

  @Test
  @DisplayName("properties: numeric uid:gid and normalized absolute paths only")
  fun propertyValidation() {
    assertThat(ImageOwnershipExtension.parsePaths(" /data , /data/tmp,/logs ")).containsExactly("/data", "/data/tmp", "/logs")
    listOf(null, "", " , ", "data", "/", "/data/", "/data//tmp", "/data/../etc", "/./data").forEach { value ->
      assertThatThrownBy { ImageOwnershipExtension.parsePaths(value) }.isInstanceOf(IllegalArgumentException::class.java)
    }
    assertThat(ImageOwnershipExtension.parseOwnership("10001:10001")).isEqualTo("10001:10001")
    listOf(null, "", "10001", "argos:argos", "10001:", ":10001", "-1:0", "0:0:0").forEach { value ->
      assertThatThrownBy { ImageOwnershipExtension.parseOwnership(value) }.isInstanceOf(IllegalArgumentException::class.java)
    }
  }

  @Test
  @DisplayName("discoverable by Jib: ServiceLoader finds the extension through META-INF/services")
  fun discoverableByServiceLoader() {
    val names = ServiceLoader.load(JibGradlePluginExtension::class.java).map { it.javaClass.name }

    assertThat(names).contains(ImageOwnershipExtension::class.java.name)
    assertThat(ImageOwnershipExtension().getExtraConfigType()).isEmpty()
  }
}
