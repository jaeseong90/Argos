// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import io.argos.buildlogic.traceability.JUnitXmlResults.Status
import java.io.File

/**
 * Reads the shell suite results (ADR-0010 C-200 (1), C-250 (6); format in test/README.md §6):
 * `build/test-results/shell/<suite>/<script file name>.tap`, a TAP 13 subset that
 * test/traceability/shell-results.sh writes from the `PASS  `/`FAIL  `/`SKIP  ` lines of a script:
 * ```
 * TAP version 13
 * # argos-shell-results 1
 * # suite: deploy-compose
 * # script: deploy/compose/test/backup.test.sh
 * # started: 2026-11-26T10:00:00Z
 * # exit: 0
 * ok 1 - T-SEC-84a: backup.sh exits 0 (caller umask 022)
 * not ok 2 - <description>
 * ok 3 - <description> # SKIP <reason>
 * 1..3
 * ```
 * Each test line is one case of suite `shell:<suite>` for the script's file; its test IDs are those of
 * the description (the script's header `# test-ids:` applies through [ShellTestScanner]). A non-zero
 * `exit` without a `not ok` line is one failed case "<script> exited <code>".
 */
object ShellResults {

  const val FORMAT_MARKER: String = "# argos-shell-results 1"
  const val SUITE_PREFIX: String = "shell:"

  private val header = Regex("""^#\s*([a-z][a-z-]*)\s*:\s*(.*)$""")
  private val testLine = Regex("""^(not\s+)?ok\b\s*(\d+)?\s*(?:-\s*)?(.*)$""")
  private val directive = Regex("""\s#\s*(SKIP|TODO)\b\s*(.*)$""", RegexOption.IGNORE_CASE)

  /** The cases of one TAP file, or null when it is not a file of this format (reported as unreadable). */
  fun read(file: File, relativePath: String = file.invariantSeparatorsPath): List<ScriptCase>? {
    val lines = file.readLines(Charsets.UTF_8).map { it.removeSuffix("\r") }
    if (lines.none { it.trim() == FORMAT_MARKER }) return null
    val headers = HashMap<String, String>()
    val tests = mutableListOf<Triple<Status, String, String?>>()
    for (line in lines) {
      if (tests.isEmpty()) header.matchEntire(line.trim())?.let { headers.putIfAbsent(it.groupValues[1], it.groupValues[2].trim()) }
      val m = testLine.matchEntire(line) ?: continue
      var description = m.groupValues[3]
      var status = if (m.groupValues[1].isEmpty()) Status.PASSED else Status.FAILED
      var reason: String? = null
      directive.find(description)?.let { d ->
        description = description.substring(0, d.range.first)
        status = Status.SKIPPED
        reason = d.groupValues[2].trim().ifEmpty { d.groupValues[1].uppercase() }
      }
      tests += Triple(status, description.trim(), reason)
    }
    val suiteName = headers["suite"]?.takeIf { it.isNotEmpty() } ?: file.parentFile?.name.orEmpty()
    val script = headers["script"]?.takeIf { it.isNotEmpty() }?.replace('\\', '/') ?: file.name.removeSuffix(".tap")
    val exit = headers["exit"]?.toIntOrNull() ?: 0
    val started = headers["started"]?.takeIf { it.isNotEmpty() }
    val suite = SUITE_PREFIX + suiteName
    val cases =
      tests.mapTo(mutableListOf()) { (status, description, reason) ->
        ScriptCase(ShellTestScanner.SOURCE, script, description, status, started, suite, relativePath, reason = reason)
      }
    if (exit != 0 && cases.none { it.status == Status.FAILED }) {
      cases += ScriptCase(ShellTestScanner.SOURCE, script, "${script.substringAfterLast('/')} exited $exit", Status.FAILED, started, suite, relativePath)
    }
    return cases
  }
}
