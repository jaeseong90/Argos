// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.testing

/**
 * The Gradle test layers of 18 §2.2 (DSN-18-02) and 19 §3.6 (DSN-19-13): which tasks `check` runs,
 * which it never runs, and which need a measurement machine. Single source for the convention
 * plugins, the task-graph guards of argos.test-layers and test/README.md.
 */
object TestLayers {

  /** Tasks that `./gradlew check` runs in every project that has them (18 §2.2, last paragraph). */
  val CHECK_TASKS: List<String> = listOf("test", "archTest", "contractTest", "verifyAgentJar")

  /**
   * Task paths that must exist and be part of the check of their project. Removing one of them (for
   * example the server's contractTest suite) changes the composition of check and fails the build.
   */
  val CHECK_ANCHORS: List<String> =
    listOf(
      ":agent:agent-core:test",
      ":agent:agent-testing:archTest",
      ":server:contractTest",
      ":agent:agent-dist:verifyAgentJar",
    )

  /** Gradle property naming the measurement machine (18 §2.2: required by every task below). */
  const val MACHINE_PROFILE_PROPERTY: String = "bench.machine-profile"

  /** Performance and long-run tasks (18 §2.1, §2.2): separate projects below test/, machine profile. */
  val MACHINE_PROFILE_TASKS: List<String> =
    listOf("benchTest", "serverLoadTest", "storageBench", "soakTest", "chaosTest")

  /**
   * Tasks that never run in check: CI runs them as separate jobs (integrationTest, securityTest),
   * on self-hosted runners only (matrixTest, performance and long-run tasks), or by hand
   * (development tools of the sample apps, the storage PoC and the transport PoC).
   */
  val NOT_IN_CHECK: List<String> =
    listOf("integrationTest", "securityTest", "matrixTest") +
      MACHINE_PROFILE_TASKS +
      listOf("storageGenerate", "storageQueries", "sampleImage")

  /**
   * Name prefixes of tasks that never run in check: development tools (sampleSmoke*, transportPoc*)
   * and the compatibility matrix (one matrixTest-<ID> task and one matrixResult-<ID> finalizer per
   * combination, and matrixReport, which writes docs/compatibility-matrix.md; 18 §6.2, WO-50).
   */
  val NOT_IN_CHECK_PREFIXES: List<String> = listOf("sampleSmoke", "transportPoc", "matrixTest", "matrixResult", "matrixReport")

  /** Runner label of the trusted self-hosted matrix runner (DSN-19-06, O-S05, 18 §2.3). */
  const val MATRIX_RUNNER_LABEL: String = "argos-matrix"

  /** Environment variable that lists the labels of the CI runner (comma separated). */
  const val RUNNER_LABELS_ENV: String = "ARGOS_RUNNER_LABELS"

  /** JUnit tags that do not belong in src/test (18 §2.2: move the test to src/integrationTest). */
  val UNIT_FORBIDDEN_TAGS: List<String> = listOf("slow", "integration")

  /** A unit test slower than this is reported (18 §2.2: tests over 1 s move to integrationTest). */
  const val UNIT_TEST_SLOW_SECONDS: Double = 1.0

  fun isNotInCheck(taskName: String): Boolean =
    taskName in NOT_IN_CHECK || NOT_IN_CHECK_PREFIXES.any { taskName.startsWith(it) }

  /** ":a:b:task" -> ":a:b"; ":task" -> ":". */
  fun projectPathOf(taskPath: String): String =
    taskPath.substringBeforeLast(':').ifEmpty { ":" }

  /** ":a:b:task" -> "task". */
  fun nameOf(taskPath: String): String = taskPath.substringAfterLast(':')

  /** Path of [taskName] in the project [projectPath] (":" is the root project). */
  fun taskPath(projectPath: String, taskName: String): String =
    if (projectPath == ":") ":$taskName" else "$projectPath:$taskName"
}

/** One `check` task of the task graph and every task it depends on, directly or transitively. */
data class CheckClosure(val checkPath: String, val dependencies: Set<String>)

/**
 * Rules of the composition of `check` (18 §2.2): `check` includes test, archTest, contractTest and
 * verifyAgentJar, and never integrationTest, securityTest, matrixTest or the performance tasks.
 * Pure function of the task graph so that it can be unit-tested; argos.test-layers applies it to
 * the real graph of every build that runs check (dry runs included).
 */
object CheckComposition {

  /**
   * Returns the violations, empty when the composition is right.
   *
   * @param closures every `check` task of the graph with its dependency closure
   * @param projectTasks task names of every project of the build, by project path
   * @param excluded task names or paths excluded on the command line (`-x`), never required
   */
  fun verify(
    closures: List<CheckClosure>,
    projectTasks: Map<String, Set<String>>,
    excluded: Set<String> = emptySet(),
  ): List<String> {
    val violations = mutableListOf<String>()
    fun isExcluded(path: String) = path in excluded || TestLayers.nameOf(path) in excluded
    for (closure in closures.sortedBy { it.checkPath }) {
      // Never in check.
      closure.dependencies
        .filter { TestLayers.isNotInCheck(TestLayers.nameOf(it)) }
        .sorted()
        .forEach {
          violations +=
            "${closure.checkPath} depends on $it, which is not part of check " +
              "(18 §2.2: CI runs it as a separate job or on a self-hosted runner)"
        }
      // Always in check, where the project has the task.
      val project = TestLayers.projectPathOf(closure.checkPath)
      val names = projectTasks[project].orEmpty()
      TestLayers.CHECK_TASKS.filter { it in names }
        .map { TestLayers.taskPath(project, it) }
        .filter { it !in closure.dependencies && !isExcluded(it) }
        .forEach {
          violations +=
            "${closure.checkPath} does not run $it " +
              "(18 §2.2: check includes ${TestLayers.CHECK_TASKS.joinToString(", ")})"
        }
    }
    // The anchors exist whenever the full check runs (root check requested).
    if (closures.any { it.checkPath == ":check" }) {
      for (anchor in TestLayers.CHECK_ANCHORS) {
        val project = TestLayers.projectPathOf(anchor)
        val names = projectTasks[project]
        if (names == null || TestLayers.nameOf(anchor) !in names) {
          violations += "$anchor no longer exists; check must include it (18 §2.2, TestLayers.CHECK_ANCHORS)"
        }
      }
    }
    return violations
  }

  /** The message of the build failure for [violations]. */
  fun failureMessage(violations: List<String>): String =
    "The composition of check changed (18 §2.2, DSN-18-02):\n" +
      violations.joinToString("\n") { "  - $it" } +
      "\nFix the build scripts, or change docs/18 §2.2 and TestLayers first."
}

/** Parses the output of `gradle <tasks> --dry-run`: one "<task path> SKIPPED" line per task. */
object DryRunOutput {
  private val line = Regex("""^(:\S*) SKIPPED\s*$""")

  fun taskPaths(output: String): List<String> =
    output.lineSequence().mapNotNull { line.matchEntire(it.trimEnd('\r'))?.groupValues?.get(1) }.toList()
}

/** Machine-profile rule of 18 §2.2 for performance and long-run tasks. */
object MachineProfile {
  private val id = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

  fun isValid(profile: String?): Boolean = profile != null && id.matches(profile)

  /** Error for [taskPaths] requested without (or with an invalid) -Pbench.machine-profile. */
  fun missingMessage(taskPaths: List<String>, given: String?): String {
    val names = taskPaths.map { TestLayers.nameOf(it) }.distinct().sorted()
    val problem =
      when {
        given != null -> "got an invalid -P${TestLayers.MACHINE_PROFILE_PROPERTY}='$given' (letters, digits, '.', '_', '-')"
        names.size == 1 -> "requires -P${TestLayers.MACHINE_PROFILE_PROPERTY}=<id>"
        else -> "require -P${TestLayers.MACHINE_PROFILE_PROPERTY}=<id>"
      }
    return "${names.joinToString(", ")} $problem (18 §2.2). Performance and long-run tasks run only " +
      "on a measurement machine; the id names that machine in the report (for example " +
      "-P${TestLayers.MACHINE_PROFILE_PROPERTY}=argos-bench-app). Requested: ${taskPaths.sorted().joinToString(", ")}"
  }
}

/** Skip rule of matrixTest (18 §2.2, §6.2, DSN-18-11): trusted runner or an explicit selection. */
object MatrixGate {
  /** Runs on the self-hosted matrix runner, or locally when a combination or group is chosen. */
  fun shouldRun(runnerLabels: String?, combo: String?, group: String?): Boolean =
    TestLayers.MATRIX_RUNNER_LABEL in labels(runnerLabels) || !combo.isNullOrBlank() || !group.isNullOrBlank()

  fun labels(runnerLabels: String?): List<String> =
    runnerLabels.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

  fun skipWarning(taskPath: String): String =
    "WARNING: $taskPath SKIPPED: not on a self-hosted '${TestLayers.MATRIX_RUNNER_LABEL}' runner " +
      "(${TestLayers.RUNNER_LABELS_ENV}) and no -Pmatrix.combo=<id> or -Pmatrix.group=<group> given " +
      "(18 §2.2, §6.2). No matrix combination was run."
}
