// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.testing

import java.io.File

/** The four marks of docs/compatibility-matrix.md (18 §6.2). */
enum class MatrixVerdict(val symbol: String, val label: String) {
  VERIFIED("○", "verified"),
  FAILED("✗", "failed"),
  UNVERIFIED("△", "not verified"),
  COMMUNITY("◎", "community verified"),
}

/** One user report of a working combination (a `kind/compat-report` issue, curated by maintainers). */
data class CommunityReport(
  val id: String?,
  val combination: String,
  val issue: String,
  val agentVersion: String,
  val reported: String,
  val note: String,
)

/**
 * Generates docs/compatibility-matrix.md from test/matrix/matrix.yaml, the combination results
 * (`build/matrix/<ID>.json`, or the downloaded nightly artifacts) and
 * test/matrix/community-reports.yaml. Pure functions: no clock, no network (the issue links are
 * written, never fetched). Rules: test/matrix/README.md "Verdicts".
 */
object MatrixReport {

  data class Judgement(val verdict: MatrixVerdict, val reason: String)

  private val issueUrl = Regex("""https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/issues/[0-9]+""")
  private val date = Regex("""[0-9]{4}-[0-9]{2}-[0-9]{2}""")
  private val version = Regex("""[0-9A-Za-z.+-]{1,40}""")
  private val idPattern = Regex("""T-MX-[0-9]{3}""")

  /**
   * The verdict of [row]: ✗ when a test failed; ○ only for a gate row that ran with every
   * requirement met, at least one test passed and every `required` pattern passed without a skip;
   * otherwise △ with the reason, or ◎ when a community report covers the combination. A combination
   * that did not run is never ○.
   */
  fun judge(row: MatrixRow, result: MatrixRunResult?, community: List<CommunityReport>): Judgement {
    val unverified = fun(reason: String): Judgement =
      if (community.isNotEmpty()) {
        Judgement(MatrixVerdict.COMMUNITY, "community report (${community.size}); $reason")
      } else {
        Judgement(MatrixVerdict.UNVERIFIED, reason)
      }
    if (result == null) return unverified("not run")
    if (result.failed > 0) {
      val first = result.failures.firstOrNull() ?: "a test failed"
      val gate = if (row.status == MatrixStatus.UNVERIFIED) " (unverified row, not a gate)" else ""
      return Judgement(MatrixVerdict.FAILED, "${result.failed} failed$gate: $first")
    }
    if (row.status == MatrixStatus.UNVERIFIED) {
      return unverified("unverified combination, run but not a gate (${result.passed} passed, ${result.skipped} skipped)")
    }
    if (result.missingRequirements.isNotEmpty()) {
      return unverified("requires ${result.missingRequirements.joinToString(", ")} (not set on the runner)")
    }
    if (result.passed == 0) {
      val why = topSkipReason(result)?.let { ": $it" }.orEmpty()
      return unverified("no test passed (${result.skipped} skipped$why)")
    }
    for (pattern in row.required) {
      val matching = result.cases.filter { matches(pattern, it.className, it.name) }
      val skipped = matching.firstOrNull { it.status == "SKIPPED" }
      when {
        matching.isEmpty() -> return unverified("required $pattern did not run")
        skipped != null -> return unverified("required $pattern skipped: ${skipped.message.ifEmpty { "no reason given" }}")
        matching.none { it.status == "PASSED" } -> return unverified("required $pattern did not pass")
      }
    }
    return Judgement(MatrixVerdict.VERIFIED, "${result.passed} passed, ${result.skipped} skipped")
  }

  /**
   * Gradle test filter semantics for a pattern of matrix.yaml: `*` matches any text; a pattern
   * matches the fully qualified class name, the class name plus `.` and the test name, or the simple
   * class name.
   */
  fun matches(pattern: String, className: String, name: String): Boolean {
    val regex = Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })
    return regex.matches(className) || regex.matches("$className.$name") || regex.matches(className.substringAfterLast('.'))
  }

  /** Gate rows whose latest result has a failed test, as "<ID>: <reason>" (unverified rows never gate). */
  fun gateFailures(catalog: MatrixCatalog, results: Map<String, MatrixRunResult>): List<String> =
    catalog.rows
      .filter { it.status == MatrixStatus.GATE }
      .mapNotNull { row ->
        val judgement = judge(row, results[row.id], emptyList())
        if (judgement.verdict == MatrixVerdict.FAILED) "${row.id}: ${judgement.reason}" else null
      }

  private fun topSkipReason(result: MatrixRunResult): String? = result.skipReasons.maxByOrNull { it.value }?.key

  /** Reads every `T-MX-nnn.json` below [dir] (nested artifact folders included); the newest run of an ID wins. */
  fun readResults(dir: File): Map<String, MatrixRunResult> {
    if (!dir.isDirectory) return emptyMap()
    val out = LinkedHashMap<String, MatrixRunResult>()
    dir.walkTopDown()
      .filter { it.isFile && Regex("""T-MX-[0-9]{3}\.json""").matches(it.name) }
      .sortedBy { it.path }
      .forEach { file ->
        val result = MatrixRunResult.fromJson(file)
        val previous = out[result.id]
        if (previous == null || (result.finishedAt ?: "") > (previous.finishedAt ?: "")) out[result.id] = result
      }
    return out
  }

  /** test/matrix/community-reports.yaml (format in its header). */
  fun parseCommunity(text: String, source: String = "community-reports.yaml"): List<CommunityReport> {
    val root =
      try {
        MatrixYaml.parse(text, source)
      } catch (e: MatrixYaml.ParseException) {
        throw MatrixDefinitionException(e.message ?: "invalid YAML")
      }
    fun fail(line: Int, message: String): Nothing = throw MatrixDefinitionException("$source:$line: $message")
    if (root !is MatrixYaml.Mapping) fail(root.line, "the document must be a mapping (version, reports)")
    root.entries.keys.firstOrNull { it !in setOf("version", "reports") }?.let { fail(root.keyLines.getValue(it), "unknown key '$it'") }
    if ((root.entries["version"] as? MatrixYaml.Scalar)?.value != "1") fail(root.line, "missing 'version: 1'")
    val reports = root.entries["reports"] as? MatrixYaml.Sequence ?: fail(root.line, "missing 'reports:' sequence ([] when empty)")
    val keys = setOf("id", "combination", "issue", "agent-version", "reported", "note")
    return reports.items.map { node ->
      if (node !is MatrixYaml.Mapping) fail(node.line, "each report must be a mapping")
      node.entries.keys.firstOrNull { it !in keys }?.let { fail(node.keyLines.getValue(it), "unknown key '$it' (${keys.joinToString(", ")})") }
      fun value(key: String) = (node.entries[key] as? MatrixYaml.Scalar)?.value
      val id = value("id")
      if (id != null && !idPattern.matches(id)) fail(node.line, "id '$id': T-MX-nnn")
      val combination = value("combination") ?: id ?: fail(node.line, "a report needs 'id' (a matrix row) or 'combination' (free text)")
      val issue = value("issue") ?: fail(node.line, "missing 'issue'")
      if (!issueUrl.matches(issue)) fail(node.line, "issue '$issue': https://github.com/<owner>/<repo>/issues/<n>")
      val agentVersion = value("agent-version") ?: fail(node.line, "missing 'agent-version'")
      if (!version.matches(agentVersion)) fail(node.line, "agent-version '$agentVersion'")
      val reported = value("reported") ?: fail(node.line, "missing 'reported' (yyyy-MM-dd)")
      if (!date.matches(reported)) fail(node.line, "reported '$reported': yyyy-MM-dd")
      CommunityReport(id, combination, issue, agentVersion, reported, value("note").orEmpty())
    }
  }

  /** The Markdown page. Deterministic for the same inputs (no clock). */
  fun render(catalog: MatrixCatalog, results: Map<String, MatrixRunResult>, community: List<CommunityReport>): String {
    val unknown = community.mapNotNull { it.id }.filter { catalog.find(it) == null }
    if (unknown.isNotEmpty()) throw MatrixDefinitionException("community-reports.yaml names unknown rows: ${unknown.joinToString(", ")}")
    val byRow = community.filter { it.id != null }.groupBy { catalog.find(it.id!!)!!.id }
    val judged = catalog.rows.map { it to judge(it, results[it.id], byRow[it.id].orEmpty()) }
    val counts = MatrixVerdict.values().associateWith { v -> judged.count { it.second.verdict == v } }
    val used = catalog.rows.mapNotNull { results[it.id] }
    val sb = StringBuilder()
    sb.appendLine("<!-- SPDX-License-Identifier: Apache-2.0 -->")
    sb.appendLine("<!-- Generated by ./gradlew :agent:agent-testing:matrixReport from test/matrix/matrix.yaml. Do not edit by hand. -->")
    sb.appendLine()
    sb.appendLine("# Compatibility matrix")
    sb.appendLine()
    sb.appendLine("Results of the agent compatibility matrix: every combination of sample application, JDK and")
    sb.appendLine("variant that `test/matrix/matrix.yaml` defines, with the verdict of its latest run. The supported")
    sb.appendLine("versions themselves are listed in [compatibility.md](compatibility.md); how the matrix runs is in")
    sb.appendLine("[test/matrix/README.md](../test/matrix/README.md).")
    sb.appendLine()
    sb.appendLine("| Mark | Meaning |")
    sb.appendLine("|---|---|")
    sb.appendLine("| ○ | Verified: the combination ran, no test failed and every required test passed |")
    sb.appendLine("| ✗ | Failed: at least one test failed in the latest run |")
    sb.appendLine("| △ | Not verified: not run, skipped (for example a missing database or JRE), or an unverified combination that runs without being a gate; the reason is given |")
    sb.appendLine("| ◎ | Community verified: not verified by the matrix, but a user reported it working (linked issue) |")
    sb.appendLine()
    sb.appendLine(
      "Summary: ${catalog.rows.size} combinations, " +
        MatrixVerdict.values().joinToString(", ") { "${it.symbol} ${counts.getValue(it)}" } + "."
    )
    if (used.isEmpty()) {
      sb.appendLine("No combination has a result yet.")
    } else {
      val days = used.mapNotNull { it.finishedAt?.take(10) }.sorted()
      val commits = used.map { it.commit.take(7) }.distinct().sorted()
      val oses = used.map { MatrixResults.osFamily(it.os) }.distinct().sorted()
      sb.appendLine(
        "Results: ${used.size} combination${if (used.size == 1) "" else "s"} run between ${days.firstOrNull() ?: "unknown"} and ${days.lastOrNull() ?: "unknown"} (UTC), " +
          "commit ${commits.joinToString(", ")}, on ${oses.joinToString(", ")}."
      )
    }
    for (group in MatrixCatalog.GROUPS) {
      val rows = judged.filter { it.first.group == group.name }
      if (rows.isEmpty()) continue
      sb.appendLine()
      sb.appendLine("## ${group.title}")
      sb.appendLine()
      sb.appendLine("| ID | Application | JDK | Variant | Result | Details | Run |")
      sb.appendLine("|---|---|---|---|---|---|---|")
      for ((row, judgement) in rows) {
        val result = results[row.id]
        val id = (listOf(row.id) + row.aliases.map { "alias $it" }).joinToString(", ")
        val reports = byRow[row.id].orEmpty().joinToString(", ") { "[report](${it.issue}) (${it.agentVersion}, ${it.reported})" }
        val details = listOf(judgement.reason, metrics(result), reports).filter { it.isNotEmpty() }.joinToString("; ")
        val run = result?.let { "${it.finishedAt?.take(10) ?: "unknown"} ${it.commit.take(7)} ${MatrixResults.osFamily(it.os)}" } ?: "-"
        sb.appendLine(
          "| ${cell(id)} | ${cell(row.app)} | ${row.jdk} | ${cell(variant(row))} | ${judgement.verdict.symbol} ${judgement.verdict.label} | " +
            "${cell(details)} | ${cell(run)} |"
        )
      }
    }
    val free = community.filter { it.id == null }
    sb.appendLine()
    sb.appendLine("## Community reports")
    sb.appendLine()
    if (free.isEmpty()) {
      sb.appendLine("No community report outside the matrix rows yet.")
    } else {
      sb.appendLine("| Combination | Agent version | Reported | Issue | Note |")
      sb.appendLine("|---|---|---|---|---|")
      free.sortedWith(compareBy({ it.combination }, { it.reported })).forEach {
        sb.appendLine("| ${cell(it.combination)} | ${cell(it.agentVersion)} | ${it.reported} | [issue](${it.issue}) | ${cell(it.note)} |")
      }
    }
    sb.appendLine()
    sb.appendLine("To report a combination that works for you, open a compatibility report issue")
    sb.appendLine("(`kind/compat-report`); maintainers add confirmed reports to `test/matrix/community-reports.yaml`.")
    return sb.toString()
  }

  private fun variant(row: MatrixRow): String {
    val parts = mutableListOf<String>()
    if (row.vt) parts += "virtual threads"
    row.tomcat?.let { parts += tomcatName(it) }
    row.coexist?.let { parts += "with ${it.replace('-', ' ')}" }
    row.appEnv.forEach { (k, v) -> parts += "$k=$v" }
    row.props.forEach { (k, v) -> parts += "$k=$v" }
    if (row.note.isNotEmpty()) parts += row.note
    return parts.joinToString(", ").ifEmpty { "default" }
  }

  private fun tomcatName(key: String) =
    when (key) {
      "tomcat9" -> "Tomcat 9"
      "tomcat10" -> "Tomcat 10.1"
      "tomcat11" -> "Tomcat 11"
      else -> key
    }

  private fun metrics(result: MatrixRunResult?): String {
    if (result == null) return ""
    val m = result.metrics
    val parts = mutableListOf<String>()
    m["startup_ms"]?.let { parts += "startup $it ms" }
    m["transformed_classes"]?.let { parts += "$it transformed classes" }
    m["transactions"]?.let { parts += "$it transactions" }
    return parts.joinToString(", ")
  }

  /** A Markdown table cell: no line breaks, pipes escaped. */
  private fun cell(text: String) = text.replace("\r", " ").replace("\n", " ").replace("|", "\\|")
}
