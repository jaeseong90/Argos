// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import groovy.json.JsonException
import groovy.json.JsonSlurper
import io.argos.buildlogic.traceability.JUnitXmlResults.Status
import java.io.File

/**
 * Reads Playwright JSON reporter files (`results*.json` at any depth below ui/test-results, ADR-0010 C-250 (6)):
 * one case per test and project. The name is the describe titles and the test title joined with
 * " > " (the file suite is left out); the file is `<ui dir>/` + the spec's file, which Playwright
 * writes relative to its config directory (ui/). The status is the last result's: `skipped` ->
 * SKIPPED (reason from a `fixme`/`skip` annotation), equal to the test's `expectedStatus` ->
 * PASSED, anything else (failed, timedOut, interrupted, an unexpected pass of `test.fail`) ->
 * FAILED; a test without results -> SKIPPED. `req` annotations (test or result level) are the
 * test's requirements (18 §3). Suite `playwright:<project>`.
 */
object PlaywrightResults {

  const val SOURCE: String = "playwright"
  const val SUITE_PREFIX: String = "playwright:"

  private val resultsFile = Regex("""results.*\.json""")

  /** True for a repository-relative path of a Playwright JSON results file. */
  fun isResultsFile(relativePath: String): Boolean =
    relativePath.contains("/test-results/") && !relativePath.contains("/node_modules/") && resultsFile.matches(relativePath.substringAfterLast('/'))

  /**
   * The cases of one JSON file, or null when it is not a Playwright JSON report.
   *
   * @param uiDir repository-relative directory the spec files are relative to (default: the part of
   *   [relativePath] before `/test-results/`)
   */
  fun read(file: File, relativePath: String = file.invariantSeparatorsPath, uiDir: String = relativePath.substringBefore("/test-results/")): List<ScriptCase>? {
    val root =
      try {
        JsonSlurper().parse(file) as? Map<*, *>
      } catch (e: JsonException) {
        null
      } catch (e: IllegalArgumentException) {
        null
      } ?: return null
    val suites = root["suites"] as? List<*> ?: return null
    val cases = mutableListOf<ScriptCase>()
    fun walk(suite: Map<*, *>, titles: List<String>, depth: Int) {
      // depth 0 is the file suite: its title is the file path.
      val title = suite["title"]?.toString().orEmpty()
      val path = if (depth > 0 && title.isNotEmpty()) titles + title else titles
      for (spec in (suite["specs"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) {
        val name = (path + spec["title"]?.toString().orEmpty()).joinToString(" > ")
        val specFile = (spec["file"] ?: suite["file"])?.toString().orEmpty().replace('\\', '/')
        val sourceFile = if (uiDir.isEmpty()) specFile else "$uiDir/$specFile"
        for (test in (spec["tests"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) cases += case(test, name, sourceFile, relativePath)
      }
      for (child in (suite["suites"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) walk(child, path, depth + 1)
    }
    suites.filterIsInstance<Map<*, *>>().forEach { walk(it, emptyList(), 0) }
    return cases
  }

  private fun case(test: Map<*, *>, name: String, sourceFile: String, resultFile: String): ScriptCase {
    val results = (test["results"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
    val last = results.lastOrNull()
    val annotations =
      ((test["annotations"] as? List<*>).orEmpty() + (last?.get("annotations") as? List<*>).orEmpty())
        .filterIsInstance<Map<*, *>>()
        .map { it["type"]?.toString().orEmpty() to it["description"]?.toString().orEmpty() }
        .distinct()
    val requirements =
      annotations.filter { it.first == "req" }.flatMap { (_, d) -> d.split(',', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() } }.distinct()
    val skipNote = annotations.firstOrNull { it.first == "fixme" || it.first == "skip" }?.let { (t, d) -> if (d.isEmpty()) t else "$t: $d" }
    val expected = test["expectedStatus"]?.toString() ?: "passed"
    val actual = last?.get("status")?.toString()
    val (status, reason) =
      when {
        actual == null -> Status.SKIPPED to (skipNote ?: "not run")
        actual == "skipped" -> Status.SKIPPED to (skipNote ?: "skipped")
        actual == expected -> Status.PASSED to null
        else -> Status.FAILED to "$actual (expected $expected)"
      }
    val project = test["projectName"]?.toString().orEmpty()
    return ScriptCase(
      source = SOURCE,
      file = sourceFile,
      name = name,
      status = status,
      timestamp = last?.get("startTime")?.toString(),
      suite = if (project.isEmpty()) SOURCE else SUITE_PREFIX + project,
      resultFile = resultFile,
      requirements = requirements,
      reason = reason,
    )
  }
}
