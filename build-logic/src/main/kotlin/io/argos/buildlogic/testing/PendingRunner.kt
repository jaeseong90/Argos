// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.testing

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault

/**
 * A performance or long-run task of 18 §2.2 whose runner belongs to a later work order. The task
 * exists now so that the task names, the machine-profile rule and CI wiring are fixed: it requires
 * -Pbench.machine-profile like the real runner, then fails and names the owning work order. It never
 * measures anything and never reports a result.
 */
@DisableCachingByDefault(because = "Placeholder for a runner of a later work order; produces nothing")
abstract class PendingRunner : DefaultTask() {

  /** Work order that implements the runner, for example "WO-52". */
  @get:Input abstract val owningWorkOrder: Property<String>

  /** Design reference, for example "docs/18-test-design.md §11.1". */
  @get:Input abstract val design: Property<String>

  /** Repository-relative path of the project README that describes the runner to come. */
  @get:Input abstract val readme: Property<String>

  /** Value of -Pbench.machine-profile, if given. */
  @get:Input @get:Optional abstract val machineProfile: Property<String>

  @TaskAction
  fun run() {
    val profile = machineProfile.orNull
    if (!MachineProfile.isValid(profile)) {
      throw GradleException(MachineProfile.missingMessage(listOf(path), profile))
    }
    throw GradleException(
      "$name has no runner yet: it is implemented by ${owningWorkOrder.get()} (${design.get()}). " +
        "Nothing was measured. See ${readme.get()}."
    )
  }
}

/**
 * Registers [taskName] as a [PendingRunner] of this project (test/load/fake-agents, test/soak,
 * test/chaos). The owning work order replaces it with the real runner.
 */
fun Project.registerPendingRunner(
  taskName: String,
  owningWorkOrder: String,
  design: String,
  summary: String,
): TaskProvider<PendingRunner> {
  require(taskName in TestLayers.MACHINE_PROFILE_TASKS) { "$taskName is not a machine-profile task (TestLayers)" }
  val readmePath = layout.projectDirectory.file("README.md").asFile.relativeTo(rootDir).invariantSeparatorsPath
  return tasks.register<PendingRunner>(taskName) {
    group = "verification"
    description =
      "$summary Requires -P${TestLayers.MACHINE_PROFILE_PROPERTY}=<id>; runner: $owningWorkOrder ($design)."
    this.owningWorkOrder.set(owningWorkOrder)
    this.design.set(design)
    readme.set(readmePath)
    machineProfile.set(providers.gradleProperty(TestLayers.MACHINE_PROFILE_PROPERTY))
  }
}
