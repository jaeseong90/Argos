// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a production classpath of the project contains any other component (project or
 * external module). Used for agent-bootstrap, which is injected into the bootstrap class loader
 * and must depend on the JDK only (10 §2 B-1, 19 §3.2). Test classpaths are not checked.
 */
abstract class CheckNoDependencies : DefaultTask() {

  /** Root of the compile classpath (includes compileOnly). */
  @get:Input abstract val compileRoot: Property<ResolvedComponentResult>

  /** Root of the runtime classpath. */
  @get:Input abstract val runtimeRoot: Property<ResolvedComponentResult>

  init {
    group = "verification"
    description = "Checks that the production classpaths contain no dependency (agent-bootstrap)."
  }

  @TaskAction
  fun check() {
    val violations =
      DependencyGraph.reachable(compileRoot.get()).map { "compileClasspath: ${it.id.displayName}" } +
        DependencyGraph.reachable(runtimeRoot.get()).map { "runtimeClasspath: ${it.id.displayName}" }
    if (violations.isNotEmpty()) {
      throw GradleException(
        "agent-bootstrap must have zero dependencies (B-1), found:\n  " +
          violations.joinToString("\n  ")
      )
    }
  }
}
