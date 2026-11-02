// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.testing

import io.argos.buildlogic.traceability.JUnitXmlResults
import java.io.File
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction

/**
 * The unit-test rule of 18 §2.2 that can be checked statically: a test in src/test is a unit test.
 * `@Tag("slow")`, `@Tag("integration")` and Testcontainers' `@Testcontainers`/`@Container` belong in
 * src/integrationTest. Network access is not detectable cheaply and stays a review rule
 * (test/README.md); the 1 s budget is reported after each run by [SlowUnitTestWarning].
 */
object UnitTestSourceRules {

  data class Violation(val file: String, val line: Int, val text: String, val reason: String)

  private val forbiddenTag =
    Regex("""@Tag\(\s*(?:value\s*=\s*)?"(${TestLayers.UNIT_FORBIDDEN_TAGS.joinToString("|")})"\s*\)""")
  private val containerAnnotation = Regex("""@(?:org\.testcontainers\.junit\.jupiter\.)?(Testcontainers|Container)\b""")

  /** Scans one source file; [name] is the path shown in the message. */
  fun scan(name: String, text: String): List<Violation> {
    val violations = mutableListOf<Violation>()
    text.lineSequence().forEachIndexed { index, raw ->
      val line = raw.substringBefore("//")
      forbiddenTag.find(line)?.let {
        violations += Violation(name, index + 1, raw.trim(), "@Tag(\"${it.groupValues[1]}\") test in src/test")
      }
      containerAnnotation.find(line)?.let {
        violations += Violation(name, index + 1, raw.trim(), "@${it.groupValues[1]} (containers) in src/test")
      }
    }
    return violations
  }

  fun failureMessage(violations: List<Violation>): String =
    "Unit tests (src/test) must not use the network or containers and must run in under 1 s " +
      "(18 §2.2). Move these tests to src/integrationTest:\n" +
      violations.joinToString("\n") { "  ${it.file}:${it.line}: ${it.reason}: ${it.text}" }
}

/** Fails when a src/test source breaks [UnitTestSourceRules] (part of check). */
@CacheableTask
abstract class CheckUnitTestSources : DefaultTask() {

  @get:InputFiles
  @get:SkipWhenEmpty
  @get:IgnoreEmptyDirectories
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sources: ConfigurableFileCollection

  /** Project directory, for relative paths in the message. */
  @get:org.gradle.api.tasks.Internal abstract val projectDirectory: DirectoryProperty

  @get:OutputFile abstract val marker: RegularFileProperty

  init {
    group = "verification"
    description = "Checks that src/test holds unit tests only (no slow/integration tags, no containers; 18 §2.2)."
  }

  @TaskAction
  fun check() {
    val base = projectDirectory.get().asFile
    val violations =
      sources.files.sortedBy { it.path }.flatMap { file ->
        UnitTestSourceRules.scan(file.relativeTo(base).invariantSeparatorsPath, file.readText())
      }
    if (violations.isNotEmpty()) throw GradleException(UnitTestSourceRules.failureMessage(violations))
    marker.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
  }
}

/**
 * doLast action of every `test` task: lists the unit tests of this run that took longer than
 * [TestLayers.UNIT_TEST_SLOW_SECONDS] (18 §2.2). A warning, not a failure: durations depend on the
 * machine and on JVM warm-up, so a failing threshold would be flaky (DSN-18-13).
 */
class SlowUnitTestWarning(private val resultsDirectory: DirectoryProperty) : Action<Task> {

  override fun execute(task: Task) {
    val dir = resultsDirectory.get().asFile
    val slow = slowTests(dir, TestLayers.UNIT_TEST_SLOW_SECONDS)
    if (slow.isEmpty()) return
    val lines =
      slow.take(MAX_LISTED).joinToString("\n") { "  %.2f s  %s > %s".format(it.timeSeconds, it.className, it.name) }
    val more = if (slow.size > MAX_LISTED) "\n  ... and ${slow.size - MAX_LISTED} more" else ""
    task.logger.warn(
      "WARNING: ${task.path}: ${slow.size} unit test(s) took longer than " +
        "${TestLayers.UNIT_TEST_SLOW_SECONDS} s. 18 §2.2: tag them @Tag(\"slow\") and move them to " +
        "src/integrationTest (test/README.md).\n$lines$more"
    )
  }

  companion object {
    private const val MAX_LISTED = 10

    /** Test cases in the JUnit XML files of [dir] slower than [seconds], slowest first. */
    fun slowTests(dir: File, seconds: Double): List<JUnitXmlResults.Case> =
      JUnitXmlResults.readDirectory(dir).filter { it.timeSeconds > seconds }.sortedByDescending { it.timeSeconds }
  }
}
