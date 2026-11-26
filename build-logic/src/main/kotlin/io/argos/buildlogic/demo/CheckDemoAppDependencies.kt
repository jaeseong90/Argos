// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.demo

import io.argos.buildlogic.DependencyGraph
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails when the runtime classpath of the public demo app (`argos-demo-app`) reaches a component of
 * [DemoImage.FORBIDDEN]: the shop apps, Hibernate, Logback, H2, MySQL, MariaDB, Oracle,
 * jakarta.annotation-api or AspectJ (ADR-0004 C-43, ADR-0010 C-186 (1)). checkLicenses covers the
 * licenses; this check keeps the demo app on its dedicated stack even where a license would pass.
 */
abstract class CheckDemoAppDependencies : DefaultTask() {

  @get:Input abstract val runtimeRoot: Property<ResolvedComponentResult>

  init {
    group = "verification"
    description = "Checks that the demo app runtime classpath holds no forbidden component (C-43)."
  }

  @TaskAction
  fun check() {
    val components =
      DependencyGraph.reachable(runtimeRoot.get())
        .filterNot { DependencyGraph.isPlatform(it) }
        .mapNotNull {
          when (val id = it.id) {
            is ModuleComponentIdentifier -> "${id.group}:${id.module}"
            is ProjectComponentIdentifier -> "project ${id.projectPath}"
            else -> null
          }
        }
    val violations = DemoImage.forbidden(components)
    if (violations.isNotEmpty()) {
      throw GradleException(
        "Forbidden components on the demo app runtime classpath (ADR-0004 C-43: the public " +
          "argos-demo-app image uses its own allow-listed stack):\n  " +
          violations.joinToString("\n  ")
      )
    }
  }
}
