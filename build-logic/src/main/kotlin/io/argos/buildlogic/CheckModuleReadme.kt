// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when the module README is missing or blank. Instrumentation modules document their
 * supported library version range there (03 §3.1).
 */
abstract class CheckModuleReadme : DefaultTask() {

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val readme: ConfigurableFileCollection

  init {
    group = "verification"
    description = "Checks that the module has a non-empty README.md."
  }

  @TaskAction
  fun check() {
    for (file in readme.files) {
      if (!file.isFile || file.readText().isBlank()) {
        throw GradleException("Module README is missing or empty: $file")
      }
    }
  }
}
