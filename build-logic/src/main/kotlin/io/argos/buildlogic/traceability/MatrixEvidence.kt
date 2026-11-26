// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import io.argos.buildlogic.testing.MatrixCatalog
import io.argos.buildlogic.testing.MatrixRow
import io.argos.buildlogic.testing.MatrixRunResult
import io.argos.buildlogic.traceability.JUnitXmlResults.Status

/**
 * The compatibility matrix as traceability evidence (18 §6, WO-50): every row of
 * test/matrix/matrix.yaml is a test with its ID (T-MX-nnn) and requirement N-13 (the compatibility
 * matrix of 01); every `build/matrix/T-MX-*.json` of `matrixTest` is that row's result: failed > 0
 * -> FAILED, otherwise passed > 0 -> PASSED, otherwise SKIPPED (missing runner requirements or the
 * skip reasons as the reason). The rows of matrix.yaml have no requirement field of their own.
 */
object MatrixEvidence {

  const val SOURCE: String = "matrix"
  const val CATALOG_PATH: String = "test/matrix/matrix.yaml"
  const val REQUIREMENT: String = "N-13"
  const val RESULTS_DIR: String = "build/matrix"

  private val resultFile = Regex("""T-MX-\d{3}\.json""")

  /** True for a repository-relative path of a matrix result (build/matrix/T-MX-nnn.json). */
  fun isResultFile(relativePath: String): Boolean =
    relativePath.substringBeforeLast('/', "") == RESULTS_DIR && resultFile.matches(relativePath.substringAfterLast('/'))

  /** "T-MX-022 shop-boot3 JDK 21 VT"; Tomcat, coexisting agent and an unverified status are named too. */
  fun title(row: MatrixRow): String =
    buildString {
      append(row.id).append(' ').append(row.app).append(" JDK ").append(row.jdk)
      if (row.vt) append(" VT")
      row.tomcat?.let { append(" Tomcat ").append(it) }
      row.coexist?.let { append(" with ").append(it) }
      if (row.status.yaml != "gate") append(" (").append(row.status.yaml).append(')')
    }

  fun sources(catalog: MatrixCatalog, file: String = CATALOG_PATH): List<SourceTest> =
    catalog.rows.map { row ->
      SourceTest(file, row.line, file.substringBeforeLast('/', ""), SOURCE, file, null, title(row), null, false, listOf(row.id), listOf(REQUIREMENT), emptyList())
    }

  /** One case for a run result; the row (aliases resolved) names the test when [catalog] knows it. */
  fun result(run: MatrixRunResult, catalog: MatrixCatalog?, resultFile: String, file: String = CATALOG_PATH): ScriptCase {
    val row = catalog?.find(run.id)
    val status =
      when {
        run.failed > 0 -> Status.FAILED
        run.passed > 0 -> Status.PASSED
        else -> Status.SKIPPED
      }
    val reason =
      when (status) {
        Status.SKIPPED ->
          if (run.missingRequirements.isNotEmpty()) "missing runner requirements: " + run.missingRequirements.joinToString(", ")
          else run.skipReasons.keys.firstOrNull() ?: "no test ran"
        Status.FAILED -> run.failures.firstOrNull()
        else -> null
      }
    val id = row?.id ?: run.id
    return ScriptCase(
      source = SOURCE,
      file = if (row != null) file else resultFile,
      name = row?.let(::title) ?: id,
      status = status,
      timestamp = run.finishedAt ?: run.startedAt,
      suite = SOURCE,
      resultFile = resultFile,
      testIds = listOf(id),
      requirements = listOf(REQUIREMENT),
      reason = reason,
    )
  }
}
