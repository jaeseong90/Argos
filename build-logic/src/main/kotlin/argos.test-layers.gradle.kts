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
//                      (18 §16).
//   checkTraceability  build/reports/traceability-check.md and .json: catalogue (R1), 18 §16.1
//                      diff (R2), required requirements (R3), Gate 1 evidence page IDs (R4); fails
//                      on any of them (WO-53, test/README.md section 6).
//   gate1Report        build/reports/gate1.md (docs/bench/gate1.md with -Pgate1.publish=true):
//                      Gate 1 NFR rows from approved dedicated-machine reports and CI results.

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

// `-Ptraceability.results=none` ignores result files in the traceability tasks (a clean checkout, what CI sees).
val traceabilityResults: Boolean = providers.gradleProperty("traceability.results").orNull != "none"

tasks.register<TraceabilityReport>("traceabilityReport") {
  repositoryRoot = layout.settingsDirectory
  includeResults = traceabilityResults
  markdownReport = layout.buildDirectory.file("reports/traceability.md")
  jsonReport = layout.buildDirectory.file("reports/traceability.json")
  // When requested together with test tasks (`./gradlew check traceabilityReport`), report their
  // fresh results. Never a dependency: the report alone runs no test.
  buildProjects.forEach { mustRunAfter(it.tasks.withType<Test>()) }
}

// WO-53: catalogue and 18 §16.1 check (fails on R1..R4) and the Gate 1 NFR page. Both read files
// only.
tasks.register<io.argos.buildlogic.traceability.CheckTraceability>("checkTraceability") {
  repositoryRoot = layout.settingsDirectory
  includeResults = traceabilityResults
  markdownReport = layout.buildDirectory.file("reports/traceability-check.md")
  jsonReport = layout.buildDirectory.file("reports/traceability-check.json")
  buildProjects.forEach { mustRunAfter(it.tasks.withType<Test>()) }
}

tasks.register<io.argos.buildlogic.traceability.Gate1ReportTask>("gate1Report") {
  repositoryRoot = layout.settingsDirectory
  includeResults = traceabilityResults
  if (providers.gradleProperty("gate1.publish").orNull == "true") report.set(layout.settingsDirectory.file("docs/bench/gate1.md"))
  else report.set(layout.buildDirectory.file("reports/gate1.md"))
  buildProjects.forEach { mustRunAfter(it.tasks.withType<Test>()) }
}
