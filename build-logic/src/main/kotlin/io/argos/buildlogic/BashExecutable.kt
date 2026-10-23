// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import java.io.File
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.kotlin.dsl.of

/**
 * Finds the bash that runs the repository scripts (test/agent/check-shading.sh,
 * test/lint/check-spdx.sh).
 *
 * On Windows, `bash` on the PATH is often the WSL launcher (C:\Windows\System32\bash.exe), which
 * cannot read Windows paths; Git Bash is used instead. Order: ARGOS_BASH, Git for Windows next to a
 * `git.exe` on the PATH, the default Git for Windows locations, then `bash`. Elsewhere: `bash`.
 */
object BashLocator {

  fun find(env: Map<String, String> = System.getenv(), os: String = System.getProperty("os.name")): String {
    env["ARGOS_BASH"]?.takeIf { it.isNotBlank() }?.let { return it }
    if (!os.startsWith("Windows")) return "bash"
    val candidates = mutableListOf<File>()
    env.entries
      .firstOrNull { it.key.equals("PATH", ignoreCase = true) }
      ?.value
      .orEmpty()
      .split(File.pathSeparatorChar)
      .filter { it.isNotBlank() }
      .forEach { dir ->
        val git = File(dir, "git.exe")
        if (git.isFile) {
          // <git>/cmd/git.exe, <git>/bin/git.exe or <git>/mingw64/bin/git.exe
          var root: File? = git.absoluteFile.parentFile?.parentFile
          repeat(2) {
            root?.let { candidates += File(it, "bin/bash.exe") }
            root = root?.parentFile
          }
        }
      }
    listOfNotNull(env["ProgramFiles"], env["ProgramW6432"], "C:\\Program Files").forEach {
      candidates += File(it, "Git/bin/bash.exe")
    }
    env["LOCALAPPDATA"]?.let { candidates += File(it, "Programs/Git/bin/bash.exe") }
    return candidates.firstOrNull { it.isFile }?.absolutePath ?: "bash"
  }
}

/** [BashLocator] as a configuration-cache input: re-evaluated for every build. */
abstract class BashExecutable : ValueSource<String, ValueSourceParameters.None> {
  override fun obtain(): String = BashLocator.find()
}

/** Provider of the bash executable for Exec tasks. */
fun Project.argosBash(): Provider<String> = providers.of(BashExecutable::class) {}
