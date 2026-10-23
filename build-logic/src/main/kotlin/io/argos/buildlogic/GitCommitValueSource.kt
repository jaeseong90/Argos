// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

/** Runs `git rev-parse HEAD`; never fails the build (returns "unknown" instead). */
abstract class GitCommitValueSource : ValueSource<String, GitCommitValueSource.Parameters> {

  interface Parameters : ValueSourceParameters {
    val workingDirectory: DirectoryProperty
  }

  @get:Inject abstract val execOperations: ExecOperations

  override fun obtain(): String {
    val stdout = ByteArrayOutputStream()
    return try {
      val result =
        execOperations.exec {
          workingDir = parameters.workingDirectory.get().asFile
          commandLine("git", "rev-parse", "HEAD")
          standardOutput = stdout
          errorOutput = ByteArrayOutputStream()
          isIgnoreExitValue = true
        }
      val commit = stdout.toString(Charsets.UTF_8).trim()
      if (result.exitValue == 0 && commit.matches(Regex("[0-9a-f]{40,64}"))) commit else "unknown"
    } catch (e: Exception) {
      "unknown"
    }
  }
}
