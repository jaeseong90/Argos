// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * argos.protobuf generates the argos.v1 code only in :proto (ADR-0010 C-104): a throwaway build
 * whose project :other applies the plugin fails its configuration with a pointer to :proto.
 */
@Tag("req:C-104")
class ProtobufPluginFunctionalTest {
  @TempDir lateinit var project: File

  @Test
  @DisplayName("argos.protobuf outside :proto fails the configuration (generated once, C-104)")
  fun refusedOutsideProto() {
    File(project, "settings.gradle").writeText("rootProject.name = 'proto-fixture'\ninclude 'other'\n")
    File(project, "other").mkdirs()
    File(project, "other/build.gradle").writeText("plugins { id 'argos.protobuf' }\n")
    val result =
      GradleRunner.create()
        .withProjectDir(project)
        .withPluginClasspath()
        .withArguments(":other:help", "--stacktrace")
        .buildAndFail()
    assertThat(result.output)
      .contains("argos.protobuf generates the argos.v1 code once, in :proto (ADR-0010 C-104)")
      .contains(":other must depend on project(\":proto\")")
  }
}
