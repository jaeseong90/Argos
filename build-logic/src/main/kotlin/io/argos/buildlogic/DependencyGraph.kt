// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

/** Helpers over a resolved dependency graph (configuration-cache safe task inputs). */
internal object DependencyGraph {

  /** Every component reachable from [root], excluding [root] itself, in a stable order. */
  fun reachable(root: ResolvedComponentResult): List<ResolvedComponentResult> {
    val seen = LinkedHashMap<String, ResolvedComponentResult>()
    val queue = ArrayDeque<ResolvedComponentResult>()
    queue.add(root)
    while (queue.isNotEmpty()) {
      val component = queue.removeFirst()
      for (dependency in component.dependencies) {
        when (dependency) {
          is ResolvedDependencyResult -> {
            val selected = dependency.selected
            val key = selected.id.displayName
            if (selected.id != root.id && !seen.containsKey(key)) {
              seen[key] = selected
              queue.add(selected)
            }
          }
          is UnresolvedDependencyResult ->
            throw GradleException(
              "Unresolved dependency ${dependency.attempted.displayName}: ${dependency.failure.message}"
            )
        }
      }
    }
    return seen.values.sortedBy { it.id.displayName }
  }

  /** External modules (group:name:version) reachable from [root], sorted. Platforms (BOMs) excluded. */
  fun externalModules(root: ResolvedComponentResult): List<ModuleComponentIdentifier> =
    reachable(root)
      .filterNot { isPlatform(it) }
      .mapNotNull { it.id as? ModuleComponentIdentifier }
      .sortedWith(compareBy({ it.group }, { it.module }, { it.version }))

  /** True for a platform/BOM component: it contributes constraints only, no code. */
  fun isPlatform(component: ResolvedComponentResult): Boolean =
    component.variants.isNotEmpty() &&
      component.variants.all { variant ->
        val attributes = variant.attributes
        val category = attributes.keySet().firstOrNull { it.name == "org.gradle.category" }
        val value = category?.let { attributes.getAttribute(it)?.toString() }
        value == "platform" || value == "enforced-platform"
      }
}
