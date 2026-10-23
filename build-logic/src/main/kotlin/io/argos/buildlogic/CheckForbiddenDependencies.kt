// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails when the agent runtime classpath (what ends up shaded into argos-agent.jar) contains a
 * forbidden module (19 §3.2 argos.java-agent, CLAUDE.md): logging frameworks that clash with the
 * application, and servlet API/implementation jars, which instrumentation may use as compileOnly
 * only.
 */
abstract class CheckForbiddenDependencies : DefaultTask() {

  @get:Input abstract val runtimeRoot: Property<ResolvedComponentResult>

  /** Regular expressions matched against the full Maven group. */
  @get:Input abstract val forbiddenGroups: ListProperty<String>

  init {
    group = "verification"
    description = "Checks that the agent runtime classpath contains no forbidden dependency."
    forbiddenGroups.convention(DEFAULT_FORBIDDEN_GROUPS)
  }

  @TaskAction
  fun check() {
    val patterns = forbiddenGroups.get().map { Regex(it) }
    val violations =
      DependencyGraph.externalModules(runtimeRoot.get())
        .filter { module -> patterns.any { it.matches(module.group) } }
        .map { it.displayName }
    if (violations.isNotEmpty()) {
      throw GradleException(
        "Forbidden dependencies on the agent runtime classpath (declare them compileOnly or " +
          "remove them):\n  " + violations.joinToString("\n  ")
      )
    }
  }

  companion object {
    val DEFAULT_FORBIDDEN_GROUPS =
      listOf(
        // Logging frameworks (the agent uses its own AgentLogger only).
        "org\\.slf4j",
        "log4j",
        "org\\.apache\\.logging\\.log4j",
        "commons-logging",
        "ch\\.qos\\.logback",
        "ch\\.qos\\.reload4j",
        "org\\.jboss\\.logging",
        // Servlet/Jakarta APIs and implementations: compileOnly only (the app provides them).
        "javax\\.servlet",
        "jakarta\\..*",
        "org\\.apache\\.tomcat.*",
      )
  }
}
