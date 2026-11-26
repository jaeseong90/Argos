// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import groovy.json.JsonSlurper
import io.argos.buildlogic.traceability.JUnitXmlResults.Status
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Where a Gate 1 NFR row takes its verdict from. */
enum class Gate1Source(val label: String) {
  BENCH("dedicated machine: bench fragment docs/bench/gate1-agent-*.md (WO-51)"),
  CHAOS("dedicated machine: chaos report docs/bench/chaos-*.md (WO-52)"),
  SERVER_LOAD("target hardware: docs/bench/server-load.md, serverLoadTest full (WO-29)"),
  API_BENCH("target hardware: docs/bench/api-bench-*.md, apiBench full + enforce (WO-23, C-180)"),
  CI("CI: last JUnit results of the test (integration job, nightly)"),
}

/** Everything gate1Report reads: the Markdown files of docs/bench by name, and the traceability model. */
data class Gate1Inputs(val benchFiles: Map<String, String>, val model: TraceabilityModel?) {
  companion object {
    fun load(root: File, model: TraceabilityModel?): Gate1Inputs {
      val dir = File(root, "docs/bench")
      val files =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty().sortedBy { it.name }.associate { it.name to it.readText() }
      return Gate1Inputs(files, model)
    }
  }
}

/**
 * `gate1Report` (WO-53): the NFR row of 03 §4 Gate 1 (N-01..N-07, N-15, N-23..N-30) as one page,
 * docs/bench/gate1.md. A performance row is PASS or FAIL only from a dedicated-machine report whose
 * summary comment says `"approved": true` (ADR-0010 C-252 (5); the same convention for server-load,
 * API bench and chaos reports, WO-53 PM question 3); otherwise it reads "no evidence (INVALID)".
 * N-07 and N-24 need both C-180 conditions per call (warm median <= target, cold <= 2 x target).
 * N-15, N-23 and N-25 take the last results of T-SV-020, T-SV-030 and T-SV-031 (source CI, PM
 * question 4). The page ends with what only a person can close (C-240 (7), C-244 (7), C-245 (1)).
 */
object Gate1Report {

  const val NO_EVIDENCE = "no evidence (INVALID)"

  data class RowDef(val requirement: String, val tests: List<String>, val subject: String, val limit: String, val source: Gate1Source)

  /** One row of the page. */
  data class Row(
    val def: RowDef,
    val verdict: String,
    val numbers: String,
    val evidence: List<String>,
    val reasons: List<String>,
    val warm: String? = null,
    val cold: String? = null,
  )

  val ROWS: List<RowDef> =
    listOf(
      RowDef("N-01", listOf("T-NFR-01"), "agent CPU overhead", "relative mean <= 5%, 1-minute peak <= 8%", Gate1Source.BENCH),
      RowDef("N-02", listOf("T-NFR-02"), "agent heap", "io.argos.* <= 64 MB (72-hour slope: T-SOAK-001)", Gate1Source.BENCH),
      RowDef("N-03", listOf("T-NFR-03"), "added latency", "p99 increase <= max(1 ms, 5% of off p99)", Gate1Source.BENCH),
      RowDef("N-04", listOf("T-NFR-04"), "application startup", "+2 s at most, heavy app +10% at most", Gate1Source.BENCH),
      RowDef("N-05", listOf("T-CH-001", "T-CH-002", "T-CH-003"), "app impact while the collector fails", "0 (no blocking; drop and count)", Gate1Source.CHAOS),
      RowDef("N-06", listOf("T-NFR-06"), "ingest throughput", "5,000 TPS + 50,000 step rows/s + 10,000 statistics rows/s for 1 hour", Gate1Source.SERVER_LOAD),
      RowDef("N-07", listOf("T-NFR-07"), "query response (30 days)", "dashboard 1 h 1 s, XLog 100,000 points 2 s, detail 0.5 s, Top N 1 day 1 s", Gate1Source.API_BENCH),
      RowDef("N-15", listOf("T-SV-020"), "ingest latency", "end -> realtime 1 s, -> query 5 s", Gate1Source.CI),
      RowDef("N-23", listOf("T-SV-030"), "alert latency", "condition met -> channel request 30 s", Gate1Source.CI),
      RowDef("N-24", listOf("T-NFR-24"), "ID search (30 days)", "tx and trace id 0.5 s, user and request id 1 h 3 s", Gate1Source.API_BENCH),
      RowDef("N-25", listOf("T-SV-031"), "dynamic config and kill switch", "applied and shown in 10 s (50 fake agents)", Gate1Source.CI),
      RowDef("N-26", listOf("T-NFR-26"), "agent non-heap", "metaspace +32 MB, direct memory 8 MB, agent threads 6", Gate1Source.BENCH),
      RowDef("N-27", listOf("T-NFR-27"), "transformation cost", "heavy app startup +10% at most (class list: T-AG-005)", Gate1Source.BENCH),
      RowDef("N-28", listOf("T-NFR-28"), "virtual threads", "N-01..N-03 limits with virtual threads", Gate1Source.BENCH),
      RowDef("N-29", listOf("T-NFR-29"), "container --cpus=2 --memory=4g", "relative CPU <= 8%, N-03 p99 limit", Gate1Source.BENCH),
      RowDef("N-30", listOf("T-NFR-30"), "agent startup", "premain <= 500 ms (one dedicated run, C-240 (7))", Gate1Source.BENCH),
    )

  /** What only a person can close (dedicated machines, target hardware). */
  val HUMAN_ITEMS: List<String> =
    listOf(
      "ADR-0010 C-240 (7): N-30 is judged on one run on the dedicated bench machine; PremainTimeAttachTest on a shared machine is a reference only.",
      "ADR-0010 C-244 (7): the 17 open item 9 and the §7.3 ratios are decided after the full serverLoadTest run (profile full, 1 hour) on the 13 §7.1 target hardware.",
      "ADR-0010 C-245 (1): the class histogram of that full serverLoadTest run re-checks the alert window memory (339.4 MiB extrapolated for 30 apps x 500 services, budget 400 MiB).",
      "Approve each dedicated-machine report in review by setting \"approved\": true in its summary comment (docs/bench/README.md \"Approval\"); then run ./gradlew gate1Report -Pgate1.publish=true.",
    )

  private val summaryComment = Regex("""<!--\s*argos-[a-z0-9-]*summary\s*(\{.*?})\s*-->""", RegexOption.DOT_MATCHES_ALL)

  /** The summary comment (`<!-- argos-…-summary {…} -->`) of a report, or null. */
  fun summary(text: String): Map<*, *>? {
    val match = summaryComment.findAll(text).lastOrNull() ?: return null
    return try {
      JsonSlurper().parseText(match.groupValues[1]) as? Map<*, *>
    } catch (e: RuntimeException) {
      null
    }
  }

  /** True when the report's summary comment says `"approved": true` (C-252 (5)). */
  fun approved(text: String?): Boolean = text != null && summary(text)?.get("approved") == true

  private fun verdictOf(cell: String): String = cell.replace("*", "").trim()

  private fun tableRows(text: String) = CatalogDocs.tableRows(text)

  fun build(inputs: Gate1Inputs): List<Row> = ROWS.map { def ->
    when (def.source) {
      Gate1Source.BENCH -> bench(def, inputs)
      Gate1Source.CHAOS -> chaos(def, inputs)
      Gate1Source.SERVER_LOAD -> serverLoad(def, inputs)
      Gate1Source.API_BENCH -> apiBench(def, inputs)
      Gate1Source.CI -> ci(def, inputs)
    }
  }

  /** Newest first: report names start with the date (and gate1-agent-<date>-<commit7>). */
  private fun named(inputs: Gate1Inputs, prefix: String): List<Pair<String, String>> =
    inputs.benchFiles.filterKeys { it.startsWith(prefix) }.toList().sortedByDescending { it.first }

  private fun bench(def: RowDef, inputs: Gate1Inputs): Row {
    val reasons = mutableListOf<String>()
    val fragments = named(inputs, "gate1-agent-")
    if (fragments.isEmpty()) reasons += "no Gate 1 fragment docs/bench/gate1-agent-*.md (bench.sh gate1 after a dedicated run)"
    for ((name, text) in fragments) {
      val row = tableRows(text).firstOrNull { it.cells.getOrNull(1) == def.requirement && it.cells.size >= 7 }
      if (row == null) {
        reasons += "$name: no ${def.requirement} row"
        continue
      }
      val verdict = verdictOf(row.cells[5])
      val cited =
        row.cells[6].split(";").map { it.trim().substringBefore(" (").trim() }.filter { it.isNotEmpty() && it != "-" }
      val notApproved = cited.filterNot { approved(inputs.benchFiles[it]) }
      when {
        verdict != "PASS" && verdict != "FAIL" -> reasons += "$name: $verdict"
        cited.isEmpty() -> reasons += "$name: $verdict without an evidence report"
        notApproved.isNotEmpty() -> reasons += "$name: $verdict from ${notApproved.joinToString(", ")}, not approved (\"approved\": true missing or report not in docs/bench, C-252 (5))"
        else -> return Row(def, verdict, row.cells[4], cited.map { "docs/bench/$it" } + "docs/bench/$name", emptyList())
      }
    }
    return Row(def, NO_EVIDENCE, "-", emptyList(), reasons)
  }

  /** Rows of an approved report whose first cell is one of [ids], with the PASS/FAIL/INVALID cell. */
  private fun verdictRows(text: String, ids: List<String>): List<Pair<String, String>> =
    tableRows(text).mapNotNull { row ->
      val first = row.cells.firstOrNull()?.replace("`", "")?.trim() ?: return@mapNotNull null
      val id = IdExpressions.expand(first).firstOrNull { it in ids } ?: return@mapNotNull null
      val verdict = row.cells.map { verdictOf(it) }.firstOrNull { it == "PASS" || it == "FAIL" || it == "INVALID" } ?: return@mapNotNull null
      id to verdict
    }

  private fun combine(def: RowDef, name: String, rows: List<Pair<String, String>>, reasons: MutableList<String>): Row? {
    if (rows.isEmpty()) {
      reasons += "$name: approved, but no ${def.tests.joinToString("/")} verdict rows"
      return null
    }
    val verdicts = rows.map { it.second }
    val numbers = "${verdicts.count { it == "PASS" }} of ${verdicts.size} checks PASS"
    return when {
      "FAIL" in verdicts -> Row(def, "FAIL", numbers, listOf("docs/bench/$name"), emptyList())
      verdicts.all { it == "PASS" } && def.tests.all { t -> rows.any { it.first == t } } -> Row(def, "PASS", numbers, listOf("docs/bench/$name"), emptyList())
      else -> {
        reasons += "$name: approved, but not every ${def.tests.joinToString("/")} check is PASS ($numbers)"
        null
      }
    }
  }

  private fun chaos(def: RowDef, inputs: Gate1Inputs): Row {
    val reasons = mutableListOf<String>()
    val reports = named(inputs, "chaos-")
    if (reports.isEmpty()) reasons += "no chaos report docs/bench/chaos-*.md (chaosTest on the dedicated machine, WO-52)"
    for ((name, text) in reports) {
      if (!approved(text)) {
        reasons += "$name: not approved (C-252 (5))"
        continue
      }
      combine(def, name, verdictRows(text, def.tests), reasons)?.let { return it }
    }
    return Row(def, NO_EVIDENCE, "-", emptyList(), reasons)
  }

  private fun serverLoad(def: RowDef, inputs: Gate1Inputs): Row {
    val reasons = mutableListOf<String>()
    val reports = named(inputs, "server-load")
    if (reports.isEmpty()) reasons += "no docs/bench/server-load*.md (serverLoadTest -Pserver.load.profile=full -Pserver.load.publish=true)"
    for ((name, text) in reports) {
      if (!approved(text)) {
        reasons += "$name: not approved (no summary comment with \"approved\": true; WO-53 PM question 3)"
        continue
      }
      combine(def, name, verdictRows(text, def.tests), reasons)?.let { return it }
    }
    return Row(def, NO_EVIDENCE, "-", emptyList(), reasons)
  }

  private fun apiBench(def: RowDef, inputs: Gate1Inputs): Row {
    val reasons = mutableListOf<String>()
    val reports = named(inputs, "api-bench")
    if (reports.isEmpty()) {
      reasons += "no docs/bench/api-bench-*.md (copy of server/build/reports/api-bench/report.md from -Pserver.apiBench.profile=full -Pserver.apiBench.enforce=true on argos-storage)"
    }
    for ((name, text) in reports) {
      if (!approved(text)) {
        reasons += "$name: not approved (no summary comment with \"approved\": true; WO-53 PM question 3)"
        continue
      }
      val headline = Regex("""Verdict: \*\*([^*]+)\*\*""").find(text)?.groupValues?.get(1)?.trim()
      if (headline != "PASS" && headline != "FAIL") {
        reasons += "$name: not an enforced run (verdict '${headline ?: "none"}'; C-180 needs -Pserver.apiBench.enforce=true)"
        continue
      }
      val rows = tableRows(text).filter { "Warm <= target" in it.header && "Cold <= 2 x target" in it.header }
      val mine =
        rows.filter { r ->
          val target = r.cells.getOrNull(r.header.indexOf("Target") + (r.cells.size - r.header.size)).orEmpty()
          RequirementIds.expand(target).contains(def.requirement)
        }
      if (mine.isEmpty()) {
        reasons += "$name: no ${def.requirement} calls"
        continue
      }
      fun col(r: CatalogDocs.TableRow, name: String) = verdictOf(r.cells.getOrNull(r.header.indexOf(name) + (r.cells.size - r.header.size)).orEmpty())
      val warm = mine.count { col(it, "Warm <= target") == "PASS" }
      val cold = mine.count { col(it, "Cold <= 2 x target") == "PASS" }
      val calls = mine.map { it.cells.first() }
      val verdict = if (warm == mine.size && cold == mine.size) "PASS" else "FAIL"
      return Row(def, verdict, "${mine.size} calls: ${calls.joinToString(", ")}", listOf("docs/bench/$name"), emptyList(), "$warm/${mine.size}", "$cold/${mine.size}")
    }
    return Row(def, NO_EVIDENCE, "-", emptyList(), reasons, "-", "-")
  }

  private fun ci(def: RowDef, inputs: Gate1Inputs): Row {
    val model = inputs.model ?: return Row(def, "NOT RUN", "-", emptyList(), listOf("no traceability model"))
    val entries = model.entries.filter { e -> e.testIds.any { TestIds.catalogId(it) in def.tests } }
    if (entries.isEmpty()) return Row(def, "MISSING", "-", emptyList(), listOf("no test carries ${def.tests.joinToString(", ")}"))
    val counts = ResultCounts.of(entries)
    val last = entries.mapNotNull { it.result?.lastRun }.maxOrNull()
    val files = entries.map { it.file }.distinct().sorted()
    val numbers = "${entries.size} tests: $counts" + (last?.let { ", last run $it" } ?: "")
    val verdict =
      when {
        counts.failed > 0 -> "FAIL (CI)"
        counts.passed > 0 && counts.notRun == 0 -> "PASS (CI)"
        // A passing unit test next to a latency IT that did not run says nothing about the latency:
        // the row stays undecided until every test carrying the ID has a result (WO-53 fix F1).
        counts.passed > 0 -> "NOT DECIDED (partly run)"
        else -> "NOT RUN"
      }
    val reasons =
      when {
        counts.passed == 0 && counts.failed == 0 -> listOf("no result in this workspace: ./gradlew :server:integrationTest (or the nightly) then gate1Report")
        counts.notRun > 0 -> {
          val notRunFiles = entries.filter { it.result == null }.map { it.file }.distinct().sorted()
          listOf("NOT RUN: ${notRunFiles.joinToString(", ")} (run them, then gate1Report)")
        }
        else -> emptyList()
      }
    return Row(def, verdict, numbers, files, reasons)
  }

  private fun cell(text: String) = text.replace("|", "\\|").replace("\n", " ")

  fun markdown(rows: List<Row>, inputs: Gate1Inputs, generatedAt: String): String {
    val sb = StringBuilder()
    sb.appendLine("# Gate 1 NFR report")
    sb.appendLine()
    sb.appendLine(
      "Generated by `./gradlew gate1Report -Pgate1.publish=true` at $generatedAt (WO-53). The NFR row of the Gate 1 table " +
        "(docs/03 §4: N-01..N-07, N-15, N-23..N-30), gathered from the reports in docs/bench and the last test results of the workspace."
    )
    sb.appendLine()
    sb.appendLine(
      "Rules: a performance row is PASS or FAIL only from a dedicated-machine report whose summary comment says `\"approved\": true` " +
        "(ADR-0010 C-252 (5)); without one it reads \"$NO_EVIDENCE\". N-07 and N-24 need both C-180 conditions for every call: warm median of 3 " +
        "<= target and cold (first call after a restart) <= 2 x target. N-15, N-23 and N-25 come from the CI results of T-SV-020, T-SV-030 and T-SV-031. " +
        "Every Gate 1 row, including the non-NFR ones, with its evidence: [docs/gates/gate1-evidence.md](../gates/gate1-evidence.md)."
    )
    sb.appendLine()
    sb.appendLine("| Requirement | Test | Subject | Limit | Source | Verdict | Numbers | Evidence | Why not decided |")
    sb.appendLine("|---|---|---|---|---|---|---|---|---|")
    for (r in rows) {
      sb.appendLine(
        "| ${r.def.requirement} | ${r.def.tests.joinToString(", ")} | ${cell(r.def.subject)} | ${cell(r.def.limit)} | ${cell(r.def.source.label)} | **${r.verdict}** | " +
          "${cell(r.numbers)} | ${cell(r.evidence.joinToString(", ").ifEmpty { "-" })} | ${cell(r.reasons.joinToString("; ").ifEmpty { "-" })} |"
      )
    }
    sb.appendLine()
    sb.appendLine("## Query rows, both C-180 conditions")
    sb.appendLine()
    sb.appendLine("| Requirement | Calls | Warm median <= target | Cold <= 2 x target | Verdict |")
    sb.appendLine("|---|---|---|---|---|")
    for (r in rows.filter { it.def.source == Gate1Source.API_BENCH }) {
      sb.appendLine("| ${r.def.requirement} | ${cell(r.numbers)} | ${r.warm ?: "-"} | ${r.cold ?: "-"} | **${r.verdict}** |")
    }
    sb.appendLine()
    sb.appendLine("## Reports read")
    sb.appendLine()
    val relevant = inputs.benchFiles.filterKeys { n -> listOf("gate1-agent-", "chaos-", "server-load", "api-bench").any { n.startsWith(it) } }
    if (relevant.isEmpty()) sb.appendLine("None.")
    for ((name, text) in relevant) {
      sb.appendLine("- `docs/bench/$name`: ${if (approved(text)) "approved" else if (summary(text) == null) "no summary comment" else "not approved"}")
    }
    sb.appendLine()
    sb.appendLine("## Still needed from a person")
    sb.appendLine()
    for (item in HUMAN_ITEMS) sb.appendLine("- $item")
    return sb.toString()
  }
}

/** `gate1Report`: writes build/reports/gate1.md, or docs/bench/gate1.md with `-Pgate1.publish=true`. */
@DisableCachingByDefault(because = "Reads docs/bench and the workspace results on every run")
abstract class Gate1ReportTask : DefaultTask() {

  @get:Internal abstract val repositoryRoot: DirectoryProperty

  @get:Input abstract val includeResults: Property<Boolean>

  @get:OutputFile abstract val report: RegularFileProperty

  init {
    group = "reporting"
    description = "Writes the Gate 1 NFR report (03 §4) from approved dedicated-machine reports and CI results (WO-53)."
    doNotTrackState("Reads docs/bench and the workspace results on every run")
  }

  @TaskAction
  fun generate() {
    val root = repositoryRoot.get().asFile
    val model = RepositoryScan.model(root, includeResults.get())
    val inputs = Gate1Inputs.load(root, model)
    val rows = Gate1Report.build(inputs)
    val out = report.get().asFile.apply { parentFile.mkdirs() }
    out.writeText(Gate1Report.markdown(rows, inputs, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()))
    logger.lifecycle(
      "Gate 1 NFR: " + rows.groupingBy { it.verdict }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}" } + ". Report: ${out.toURI()}"
    )
  }
}
