// SPDX-License-Identifier: Apache-2.0
//
// argos.test-layers: root project only. Guards and reports of the test layers (18 §2.2, §3, §16;
// DSN-18-01, DSN-18-02, DSN-19-13). The rules themselves live in io.argos.buildlogic.testing
// (TestLayers, CheckComposition, MachineProfile) and are documented in test/README.md.
//
// Task-graph guards (run when the graph is ready, before any task executes, dry runs included):
//   check composition  every `check` in the graph runs test, archTest, contractTest and
//                      verifyAgentJar where its project has them, and never integrationTest,
//                      securityTest, matrixTest, the performance tasks or the PoC/sample tools;
//                      on the full `./gradlew check` the anchors of TestLayers.CHECK_ANCHORS exist.
//   machine profile    benchTest, serverLoadTest, storageBench, soakTest and chaosTest fail fast
//                      without a valid -Pbench.machine-profile=<id> (18 §2.2).
// Report:
//   traceabilityReport build/reports/traceability.md and .json: requirement -> tests -> last result
//                      (skeleton of 18 §16; the diff against 18 §16 and the CI job are WO-53).

import io.argos.buildlogic.testing.CheckClosure
import io.argos.buildlogic.testing.CheckComposition
import io.argos.buildlogic.testing.MachineProfile
import io.argos.buildlogic.testing.TestLayers
import io.argos.buildlogic.traceability.TraceabilityReport

check(project == rootProject) { "argos.test-layers is applied to the root project only" }

val machineProfile: String? = providers.gradleProperty(TestLayers.MACHINE_PROFILE_PROPERTY).orNull
val excludedTasks: Set<String> = gradle.startParameter.excludedTaskNames.toSet()
val buildProjects = allprojects

gradle.taskGraph.whenReady {
  val graph = this
  val tasksInGraph = graph.allTasks

  val measured = tasksInGraph.filter { it.name in TestLayers.MACHINE_PROFILE_TASKS }.map { it.path }
  if (measured.isNotEmpty() && !MachineProfile.isValid(machineProfile)) {
    throw GradleException(MachineProfile.missingMessage(measured, machineProfile))
  }

  val checks = tasksInGraph.filter { it.name == "check" }
  if (checks.isNotEmpty()) {
    val closures =
      checks.map { check ->
        val seen = LinkedHashSet<String>()
        val pending = ArrayDeque(graph.getDependencies(check))
        while (pending.isNotEmpty()) {
          val task = pending.removeLast()
          // Tasks of included builds (build-logic) are not part of this plan: leaves.
          if (seen.add(task.path) && graph.hasTask(task)) pending.addAll(graph.getDependencies(task))
        }
        CheckClosure(check.path, seen)
      }
    val projectTasks = buildProjects.associate { it.path to it.tasks.names.toSet() }
    val violations = CheckComposition.verify(closures, projectTasks, excludedTasks)
    if (violations.isNotEmpty()) throw GradleException(CheckComposition.failureMessage(violations))
  }
}

tasks.register<TraceabilityReport>("traceabilityReport") {
  repositoryRoot = layout.settingsDirectory
  markdownReport = layout.buildDirectory.file("reports/traceability.md")
  jsonReport = layout.buildDirectory.file("reports/traceability.json")
  // When requested together with test tasks (`./gradlew check traceabilityReport`), report their
  // fresh results. Never a dependency: the report alone runs no test.
  buildProjects.forEach { mustRunAfter(it.tasks.withType<Test>()) }
}
