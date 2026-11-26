// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import io.argos.buildlogic.traceability.JUnitXmlResults.Status
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** What the check reads: the design documents, the evidence registry and the scanned workspace. */
data class CheckInputs(
  val catalogue: Catalogue,
  val mapping: List<MappingRow>,
  val planRequirements: List<RequirementDef>,
  val securityRequirements: List<RequirementDef>,
  val evidence: EvidenceRegistry,
  /** docs/gates/gate1-evidence.md, null when the file does not exist. */
  val gateEvidence: String?,
  val model: TraceabilityModel,
  val resultsIncluded: Boolean,
  /**
   * IDs that start a string literal of a test source (`"T-ACC-24 " + where`): the runtime names of
   * dynamic tests, which the source scan cannot attribute (ID -> files). Used only by R2 and R3,
   * so that a clean checkout does not miss an expected ID that only a factory's cases carry.
   */
  val literalIds: Map<String, List<String>> = emptyMap(),
) {
  companion object {
    const val GATE1_EVIDENCE = "docs/gates/gate1-evidence.md"

    private val literalStart =
      Regex("""["'`](T-(?:${TestIds.PREFIXES.joinToString("|")})-(?:[a-z]+(?:-[a-z]+)*-)?[0-9]+[A-Z]?[a-z]?)[ :]""")

    /** IDs at the start of a string literal in the test sources below [root] (see [literalIds]). */
    fun literalIds(root: File): Map<String, List<String>> {
      val files = RepositoryScan.collect(root, includeResults = false)
      val map = sortedMapOf<String, MutableSet<String>>(Traceability.naturalOrder)
      for (file in files.junitSources + files.scriptSources) {
        val relative = file.relativeTo(root).invariantSeparatorsPath
        // The build logic's own tests hold fixture documents and sources as literals: not test names.
        if (relative.startsWith("build-logic/")) continue
        for (m in literalStart.findAll(file.readText())) map.getOrPut(m.groupValues[1]) { sortedSetOf() } += relative
      }
      return map.mapValues { it.value.toList() }
    }

    /** Reads everything below [root]; [includeResults] false reproduces a clean checkout (no result files). */
    fun load(root: File, includeResults: Boolean): CheckInputs {
      fun read(path: String): String {
        val file = File(root, path)
        if (!file.isFile) throw GradleException("Traceability check: $path is missing")
        return file.readText()
      }
      val catalogueText = read(CatalogDocs.CATALOGUE_DOC)
      val evidenceFile = File(root, EvidenceRegistry.PATH)
      val evidence =
        if (evidenceFile.isFile) {
          try {
            EvidenceRegistry.parse(evidenceFile.readText())
          } catch (e: EvidenceException) {
            throw GradleException("Traceability check: ${e.message}")
          }
        } else EvidenceRegistry.EMPTY
      // Without results the scan collects no result file at all (sources only, every test NOT RUN).
      val model = RepositoryScan.model(root, includeResults)
      return CheckInputs(
        catalogue = CatalogDocs.catalogue(catalogueText),
        mapping = CatalogDocs.mapping(catalogueText),
        planRequirements = CatalogDocs.planRequirements(read(CatalogDocs.PLAN_DOC)),
        securityRequirements = CatalogDocs.securityRequirements(read(CatalogDocs.SECURITY_DOC)),
        evidence = evidence,
        gateEvidence = File(root, GATE1_EVIDENCE).takeIf { it.isFile }?.readText(),
        model = model,
        resultsIncluded = includeResults,
        literalIds = literalIds(root),
      )
    }
  }
}

/** A failing finding: rule R1..R4, its subject and what is wrong. */
data class Violation(val rule: String, val subject: String, val message: String)

/** A finding that is reported and never fails the build. */
data class Warning(val kind: String, val subject: String, val message: String)

/** Last results of the tests that cover something. */
data class ResultCounts(val passed: Int, val failed: Int, val skipped: Int, val notRun: Int) {
  val total: Int
    get() = passed + failed + skipped + notRun

  override fun toString(): String = "PASSED $passed, FAILED $failed, SKIPPED $skipped, NOT RUN $notRun"

  companion object {
    fun of(entries: Collection<TestEntry>) =
      ResultCounts(
        entries.count { it.result?.status == Status.PASSED },
        entries.count { it.result?.status == Status.FAILED },
        entries.count { it.result?.status == Status.SKIPPED },
        entries.count { it.result == null },
      )
  }
}

/** Coverage of one required requirement (R3). */
data class RequirementCoverage(
  val id: String,
  val group: String,
  val rule: String,
  val testIds: List<String>,
  val taggedTests: Int,
  val mappedIds: List<String>,
  val evidence: List<EvidenceEntry>,
  val results: ResultCounts,
  val covered: Boolean,
)

/** One 18 §16.1 expectation and what the workspace has for it (R2). */
data class MappingStatus(val row: MappingRow, val found: List<String>, val partial: List<String>, val viaEvidence: List<String>, val missing: List<String>)

/** A catalogue ID divided by a '(분할)' row of appendix A (C-182): full when every suffix ID passed. */
data class SplitStatus(val catalogId: String, val suffixIds: List<String>, val notPassed: List<String>) {
  val full: Boolean
    get() = notPassed.isEmpty()
}

data class CheckResult(
  val inputs: CheckInputs,
  val violations: List<Violation>,
  val warnings: List<Warning>,
  val coverage: List<RequirementCoverage>,
  val mapping: List<MappingStatus>,
  val splits: List<SplitStatus>,
  val usedIds: Set<String>,
) {
  val failed: Boolean
    get() = violations.isNotEmpty()

  fun count(rule: String) = violations.count { it.rule == rule }
}

/**
 * `checkTraceability` (WO-53, 18 §3 "16장과 diff가 나면 실패"): compares the workspace with the
 * catalogue and the 18 §16.1 table. Failing rules:
 * - R1 every test ID used in a test source, a result or the evidence registry is in the docs/18
 *   catalogue (a suffixed ID needs its own row, for example a '(분할)' row);
 * - R2 every expected ID (and family, and non-test evidence) of every 18 §16.1 row exists in a
 *   source, a result or test/traceability/evidence.yaml;
 * - R3 no required requirement is missing: D-01..D-26, every N of docs/01, every stage-M SEC of
 *   docs/06 ('자동' ones need a T-SEC-nn family ID or, with a warning, a test tagged req:SEC-nn;
 *   the others a tagged test, a T-SEC-nn ID or an evidence entry);
 * - R4 every test ID in docs/gates/gate1-evidence.md is in the catalogue.
 * Reported only: ID-number rule mismatches (T-ACC-nn without req:D-nn ...), malformed IDs,
 * catalogue IDs covered only partly, FAILED and NOT RUN counts. No result files make every test
 * NOT RUN, never a failure.
 */
object TraceabilityCheck {

  /** Families whose numbers follow a requirement (18 §3) and their number width. */
  private val twoDigitFamilies = setOf("ACC", "NFR", "SEC", "ACPT")

  private fun family(id: String): String = id.removePrefix("T-").substringBefore('-')

  /** The requirement an ID's number names (T-ACC-13 -> D-13, T-NFR-10a -> N-10a, T-SEC-3Aa -> SEC-3A), or null. */
  fun numberedRequirement(id: String): String? {
    val catalog = TestIds.catalogId(id)
    val number = catalog.substringAfterLast('-')
    return when (family(id)) {
      "ACC" -> "D-$number"
      "NFR" -> "N-$number"
      "SEC" -> "SEC-$number"
      else -> null
    }
  }

  /** True when the number of [id] does not have the width of 18 §3 (T-SEC-010, T-AG-31). */
  fun malformed(id: String): Boolean {
    val fam = family(id)
    val rest = id.removePrefix("T-$fam-")
    if (fam == "E2E" && rest.firstOrNull()?.isLetter() == true) return false
    val number = Regex("""^[0-9]+[A-Z]?""").find(rest)?.value ?: return true
    return if (fam in twoDigitFamilies) number.length != 2 else number.length != 3
  }

  fun run(inputs: CheckInputs): CheckResult {
    val model = inputs.model
    val catalogue = inputs.catalogue
    val evidence = inputs.evidence
    val violations = mutableListOf<Violation>()
    val warnings = mutableListOf<Warning>()

    // Which entries carry each ID (exact) and each catalogue ID (exact or suffixed).
    val entriesById = HashMap<String, MutableList<TestEntry>>()
    val entriesByCatalogId = HashMap<String, MutableList<TestEntry>>()
    for (entry in model.entries) {
      for (id in entry.testIds) {
        entriesById.getOrPut(id) { mutableListOf() } += entry
        entriesByCatalogId.getOrPut(TestIds.catalogId(id)) { mutableListOf() } += entry
      }
    }
    val usedIds = entriesById.keys.toSortedSet(Traceability.naturalOrder)

    // R1: used IDs are catalogued.
    for (id in usedIds) {
      if (id in catalogue) continue
      val where = entriesById[id].orEmpty().map { "${it.file}:${it.line}" }.distinct().sorted()
      if (malformed(id)) {
        warnings += Warning("malformed-id", id, "number width differs from 18 §3 and the ID is not catalogued; used in ${where.joinToString(", ")}")
      } else {
        violations += Violation("R1", id, "not in the docs/18 catalogue; used in ${where.take(5).joinToString(", ")}${if (where.size > 5) " and ${where.size - 5} more" else ""}")
      }
    }
    for (entry in evidence.entries) {
      for (id in entry.ids.filter { it !in catalogue }) {
        violations += Violation("R1", id, "not in the docs/18 catalogue; used in ${EvidenceRegistry.PATH}:${entry.line}")
      }
    }

    // R2: 18 §16.1 expectations.
    fun sourcePartial(id: String) =
      entriesByCatalogId[id].orEmpty().flatMap { e -> e.testIds.filter { TestIds.catalogId(it) == id && it != id } }.distinct().sortedWith(Traceability.naturalOrder)
    val mappingStatus = mutableListOf<MappingStatus>()
    for (row in inputs.mapping) {
      // An ID that only a dynamic test's runtime name carries counts when results are absent.
      val dynamicOnly = row.expectedIds.filter { it !in entriesById && it in inputs.literalIds }
      for (id in dynamicOnly) {
        warnings +=
          Warning(
            "dynamic-name-only",
            id,
            "18 §16.1 ${row.requirement}: no source test is named with it; a string literal starts with it " +
              "(dynamic test name) in ${inputs.literalIds.getValue(id).joinToString(", ")}",
          )
      }
      val found = row.expectedIds.filter { it in entriesById || it in dynamicOnly }
      val partial = row.expectedIds.filter { it !in found && sourcePartial(it).isNotEmpty() }
      val viaEvidence = row.expectedIds.filter { it !in found && it !in partial && it in evidence.ids }
      val missing = row.expectedIds.filter { it !in found && it !in partial && it !in viaEvidence }.toMutableList()
      for (fam in row.families) {
        val present = usedIds.any { it.startsWith("$fam-") } || evidence.ids.any { it.startsWith("$fam-") }
        if (!present) missing += "$fam (family)"
      }
      if (row.nonTest != null && evidence.forRequirement(row.requirement).isEmpty()) {
        missing += "non-test evidence (${row.nonTest.name.lowercase()})"
      }
      mappingStatus += MappingStatus(row, found, partial, viaEvidence, missing)
      for (m in missing) {
        violations +=
          Violation(
            "R2",
            row.requirement,
            "18 §16.1 (line ${row.line}) expects $m: no test source or result has it and ${EvidenceRegistry.PATH} has no entry",
          )
      }
      for (p in partial) {
        warnings += Warning("partial-mapping", row.requirement, "18 §16.1 expects $p; only suffixed IDs exist: ${sourcePartial(p).joinToString(", ")}")
      }
    }

    // R3: required requirements.
    val coverage = mutableListOf<RequirementCoverage>()
    val mappingByRequirement = inputs.mapping.groupBy { it.requirement }
    val required =
      inputs.planRequirements.filter { it.id.startsWith("D-") }.map { it to "D" } +
        inputs.planRequirements.filter { it.id.startsWith("N-") }.map { it to "N" } +
        inputs.securityRequirements.filter { it.stageM }.map { it to "SEC (M)" }
    for ((req, group) in required) {
      val tagged = model.byRequirement[req.id].orEmpty()
      val mappedIds =
        mappingByRequirement[req.id].orEmpty().flatMap { row ->
          row.expectedIds.filter { it in entriesById || it in inputs.literalIds || sourcePartial(it).isNotEmpty() } +
            row.families.filter { f -> usedIds.any { it.startsWith("$f-") } }
        }.distinct()
      val familyIds =
        if (group.startsWith("SEC")) usedIds.filter { family(it) == "SEC" && TestIds.catalogId(it) == "T-${req.id}" } else emptyList()
      val ev = evidence.forRequirement(req.id)
      val (rule, covered) =
        when {
          // 18 §16.2: an automatic SEC is checked by T-SEC-nn. A test tagged req:SEC-nn under another ID
          // still covers it, with a warning (the 1:1 naming is the test owner's to fix).
          group.startsWith("SEC") && req.automatic ->
            "자동: a T-SEC-${req.id.removePrefix("SEC-")} family ID (a tagged test under another ID: warning)" to
              (familyIds.isNotEmpty() || tagged.isNotEmpty())
          group.startsWith("SEC") ->
            "${req.verificationKind ?: "manual"}: a tagged test, a T-SEC ID or an evidence entry" to
              (tagged.isNotEmpty() || familyIds.isNotEmpty() || ev.isNotEmpty())
          else -> "a tagged test, a 18 §16.1 ID or an evidence entry" to (tagged.isNotEmpty() || mappedIds.isNotEmpty() || ev.isNotEmpty())
        }
      val coveringEntries =
        (tagged + familyIds.flatMap { entriesById[it].orEmpty() } + mappedIds.flatMap { entriesByCatalogId[it].orEmpty() + entriesById[it].orEmpty() })
          .distinctBy { System.identityHashCode(it) }
      val ids = (tagged.flatMap { it.testIds } + familyIds + mappedIds).distinct().sortedWith(Traceability.naturalOrder)
      coverage += RequirementCoverage(req.id, group, rule, ids, tagged.size, mappedIds, ev, ResultCounts.of(coveringEntries), covered)
      if (!covered) {
        violations += Violation("R3", req.id, "required ($group, ${req.document}:${req.line}) and not covered: needs $rule")
      } else if (group.startsWith("SEC") && req.automatic && familyIds.isEmpty()) {
        warnings +=
          Warning(
            "sec-without-id",
            req.id,
            "automatic stage-M requirement checked only by tests tagged req:${req.id} under other IDs " +
              "(${tagged.flatMap { it.testIds }.distinct().take(5).joinToString(", ")}); 18 §16.2 expects T-${req.id}",
          )
      }
    }

    // R4: IDs in the Gate 1 evidence page are catalogued.
    if (inputs.gateEvidence == null) {
      violations += Violation("R4", CheckInputs.GATE1_EVIDENCE, "missing (03 Gate 1 rows -> evidence IDs, WO-53)")
    } else {
      val lines = inputs.gateEvidence.lines()
      for ((index, line) in lines.withIndex()) {
        for (id in IdExpressions.expand(line).filter { it !in catalogue }) {
          violations += Violation("R4", id, "${CheckInputs.GATE1_EVIDENCE}:${index + 1} names an ID that is not in the docs/18 catalogue")
        }
      }
    }

    // Splits (C-182) and partly covered catalogue IDs.
    val splits =
      catalogue.splits.map { (catalogId, suffixes) ->
        SplitStatus(
          catalogId,
          suffixes,
          suffixes.filter { id ->
            val es = entriesById[id].orEmpty()
            es.none { it.result?.status == Status.PASSED } || es.any { it.result?.status == Status.FAILED }
          },
        )
      }
    val fullSplits = splits.filter { it.full }.map { it.catalogId }.toSet()
    for ((id, partial) in model.partiallyCovered) {
      if (id in fullSplits) continue
      warnings += Warning("partial", id, "covered only by suffixed IDs ${partial.joinToString(", ")} (C-182)" + if (id in catalogue.splits) "; '(분할)' row, not every suffix passed" else "")
    }

    // ID-number rule (18 §3): T-ACC-nn <-> D-nn, T-NFR-nn <-> N-nn, T-SEC-nn <-> SEC-nn.
    val mismatches = LinkedHashMap<Pair<String, String>, MutableSet<String>>()
    for (entry in model.entries) {
      if (entry.requirements.isEmpty()) continue
      for (id in entry.testIds) {
        val expected = numberedRequirement(id) ?: continue
        if (expected !in entry.requirements) {
          mismatches.getOrPut(entry.file to id) { sortedSetOf() } += entry.requirements
        }
      }
    }
    for ((key, reqs) in mismatches) {
      warnings += Warning("id-number", key.second, "${key.first}: tagged ${reqs.joinToString(", ")} but not ${numberedRequirement(key.second)} (18 §3 number rule)")
    }
    for ((id, entries) in entriesById.filterKeys { it in evidence.ids }) {
      warnings += Warning("evidence-and-source", id, "has ${entries.size} test(s) and an evidence entry; the tests are shown first")
    }
    val failed = model.entries.filter { it.result?.status == Status.FAILED }
    for (entry in failed.sortedWith(Traceability.entryOrder)) {
      warnings += Warning("failed", entry.testIds.joinToString(", ").ifEmpty { entry.name }, "${entry.file}:${entry.line} ${entry.name} FAILED in the last results")
    }
    return CheckResult(inputs, violations, warnings, coverage, mappingStatus, splits, usedIds)
  }

  fun failureMessage(result: CheckResult, report: File?): String {
    val sb = StringBuilder()
    sb.appendLine(
      "Traceability check failed: ${result.violations.size} finding(s) " +
        "(R1 ${result.count("R1")}, R2 ${result.count("R2")}, R3 ${result.count("R3")}, R4 ${result.count("R4")})."
    )
    for (v in result.violations.take(200)) sb.appendLine("  ${v.rule} ${v.subject}: ${v.message}")
    if (result.violations.size > 200) sb.appendLine("  ... and ${result.violations.size - 200} more")
    sb.appendLine(
      "Fix: R1 add a row to docs/18 appendix A (suffixed IDs: their own row or a '(분할)' row), R2/R3 add the test " +
        "or an entry to ${EvidenceRegistry.PATH}, R4 correct ${CheckInputs.GATE1_EVIDENCE} (test/README.md section 6)."
    )
    report?.let { sb.append("Report: ${it.toURI()}") }
    return sb.toString()
  }

  private fun cell(text: String): String = text.replace("|", "\\|").replace("\r", " ").replace("\n", " ")

  fun markdown(result: CheckResult, generatedAt: String): String {
    val inputs = result.inputs
    val model = inputs.model
    val sb = StringBuilder()
    sb.appendLine("# Traceability check")
    sb.appendLine()
    sb.appendLine(
      "Generated by `./gradlew checkTraceability` at $generatedAt (WO-53; 18 §3, §16, appendix A; DSN-18-01). " +
        if (inputs.resultsIncluded) "Results: the result files of this workspace." else "Results: ignored (`-Ptraceability.results=none`, clean checkout)."
    )
    sb.appendLine(
      "Rules R1..R4 fail the task; the warnings below never do. Rules, the split marker and the inputs: test/README.md section 6."
    )
    sb.appendLine()
    sb.appendLine("## Summary")
    sb.appendLine()
    sb.appendLine("| Item | Value |")
    sb.appendLine("|---|---|")
    sb.appendLine("| Verdict | ${if (result.failed) "**FAIL**" else "**PASS**"} |")
    sb.appendLine("| R1 IDs not in the catalogue | ${result.count("R1")} |")
    sb.appendLine("| R2 18 §16.1 expectations without a test or evidence | ${result.count("R2")} |")
    sb.appendLine("| R3 required requirements not covered (D, N, SEC stage M) | ${result.count("R3")} |")
    sb.appendLine("| R4 IDs of ${CheckInputs.GATE1_EVIDENCE} not in the catalogue | ${result.count("R4")} |")
    sb.appendLine("| Catalogue rows / IDs (docs/18) | ${inputs.catalogue.rows.size} / ${inputs.catalogue.ids.size} |")
    sb.appendLine("| Test IDs used (sources and results) | ${result.usedIds.size} |")
    sb.appendLine("| Evidence entries (${EvidenceRegistry.PATH}) | ${inputs.evidence.entries.size} |")
    sb.appendLine("| Required requirements covered | ${result.coverage.count { it.covered }} of ${result.coverage.size} |")
    val counts = ResultCounts.of(model.entries)
    sb.appendLine("| Last results of all tests | $counts |")
    // Suites "playwright:<project>", "shell:<suite>" group by their prefix; Gradle test tasks by name.
    val bySuite =
      model.entries
        .mapNotNull { e -> e.result?.let { r -> (r.suites.firstOrNull() ?: e.suite).let { s -> if (s.contains(':')) s.substringBefore(':') else s } } }
        .groupingBy { it }
        .eachCount()
    sb.appendLine("| Tests with a result, by source | ${bySuite.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "none" }} |")
    sb.appendLine("| '(분할)' catalogue IDs full / partial | ${result.splits.count { it.full }} / ${result.splits.count { !it.full }} |")
    for (kind in result.warnings.map { it.kind }.distinct()) sb.appendLine("| Warnings: $kind | ${result.warnings.count { it.kind == kind }} |")
    if (result.violations.isNotEmpty()) {
      sb.appendLine()
      sb.appendLine("## Failures")
      sb.appendLine()
      sb.appendLine("| Rule | Subject | Finding |")
      sb.appendLine("|---|---|---|")
      for (v in result.violations) sb.appendLine("| ${v.rule} | ${cell(v.subject)} | ${cell(v.message)} |")
    }
    sb.appendLine()
    sb.appendLine("## Required requirements (R3)")
    sb.appendLine()
    sb.appendLine("| Requirement | Group | Covered | Rule | Test IDs | Tagged tests | Evidence | Last results |")
    sb.appendLine("|---|---|---|---|---|---|---|---|")
    for (c in result.coverage) {
      val ids = c.testIds.take(12).joinToString(", ") + if (c.testIds.size > 12) " (+${c.testIds.size - 12})" else ""
      val ev = c.evidence.joinToString("; ") { "${it.kind.label}, ${it.owner}, ${it.gate}" }
      sb.appendLine(
        "| ${c.id} | ${c.group} | ${if (c.covered) "yes" else "**no**"} | ${cell(c.rule)} | ${cell(ids.ifEmpty { "-" })} | ${c.taggedTests} | ${cell(ev.ifEmpty { "-" })} | ${c.results} |"
      )
    }
    sb.appendLine()
    sb.appendLine("## 18 §16.1 table (R2)")
    sb.appendLine()
    sb.appendLine("| Requirement | 18 §16.1 says | Found in tests | Only suffixed IDs | Evidence entry | Missing |")
    sb.appendLine("|---|---|---|---|---|---|")
    for (m in result.mapping) {
      val ev = inputs.evidence.forRequirement(m.row.requirement).map { it.kind.label } + m.viaEvidence
      sb.appendLine(
        "| ${m.row.requirement} | ${cell(m.row.text)} | ${m.found.joinToString(", ").ifEmpty { "-" }} | ${m.partial.joinToString(", ").ifEmpty { "-" }} | ${cell(ev.distinct().joinToString(", ").ifEmpty { "-" })} | ${cell(m.missing.joinToString(", ").ifEmpty { "-" })} |"
      )
    }
    sb.appendLine()
    sb.appendLine("## Non-test evidence (${EvidenceRegistry.PATH})")
    sb.appendLine()
    sb.appendLine("| IDs | Requirements | Kind | Source | Owner | Gate |")
    sb.appendLine("|---|---|---|---|---|---|")
    for (e in inputs.evidence.entries) {
      sb.appendLine(
        "| ${cell(e.idExpressions.joinToString(", ").ifEmpty { "-" })} | ${e.requirements.joinToString(", ").ifEmpty { "-" }} | ${e.kind.label} | ${cell(e.source)} | ${e.owner} | ${cell(e.gate)} |"
      )
    }
    if (result.splits.isNotEmpty()) {
      sb.appendLine()
      sb.appendLine("## '(분할)' catalogue IDs (C-182)")
      sb.appendLine()
      sb.appendLine("A '(분할)' row of appendix A divides its catalogue ID among its suffixed IDs: the catalogue ID is full when every one of them passed in the last results, else partial.")
      sb.appendLine()
      sb.appendLine("| Catalogue ID | Status | Suffixed IDs | Not passed |")
      sb.appendLine("|---|---|---|---|")
      for (s in result.splits) {
        sb.appendLine(
          "| ${s.catalogId} | ${if (s.full) "full (split)" else "partial"} | ${s.suffixIds.joinToString(", ")} | ${s.notPassed.joinToString(", ").ifEmpty { "-" }} |"
        )
      }
    }
    if (result.warnings.isNotEmpty()) {
      sb.appendLine()
      sb.appendLine("## Warnings (never fail)")
      sb.appendLine()
      sb.appendLine("| Kind | Subject | Detail |")
      sb.appendLine("|---|---|---|")
      for (w in result.warnings) sb.appendLine("| ${w.kind} | ${cell(w.subject)} | ${cell(w.message)} |")
    }
    return sb.toString()
  }

  fun json(result: CheckResult, generatedAt: String): String {
    val inputs = result.inputs
    val root =
      linkedMapOf(
        "generatedAt" to generatedAt,
        "resultsIncluded" to inputs.resultsIncluded,
        "verdict" to if (result.failed) "FAIL" else "PASS",
        "summary" to
          linkedMapOf(
            "R1" to result.count("R1"),
            "R2" to result.count("R2"),
            "R3" to result.count("R3"),
            "R4" to result.count("R4"),
            "catalogueRows" to inputs.catalogue.rows.size,
            "catalogueIds" to inputs.catalogue.ids.size,
            "usedIds" to result.usedIds.size,
            "evidenceEntries" to inputs.evidence.entries.size,
            "requiredRequirements" to result.coverage.size,
            "requiredCovered" to result.coverage.count { it.covered },
            "warnings" to result.warnings.groupingBy { it.kind }.eachCount(),
          ),
        "violations" to result.violations.map { linkedMapOf("rule" to it.rule, "subject" to it.subject, "message" to it.message) },
        "requirements" to
          result.coverage.map {
            linkedMapOf(
              "id" to it.id,
              "group" to it.group,
              "covered" to it.covered,
              "rule" to it.rule,
              "testIds" to it.testIds,
              "taggedTests" to it.taggedTests,
              "evidence" to it.evidence.map { e -> linkedMapOf("kind" to e.kind.label, "owner" to e.owner, "gate" to e.gate, "source" to e.source) },
              "results" to linkedMapOf("passed" to it.results.passed, "failed" to it.results.failed, "skipped" to it.results.skipped, "notRun" to it.results.notRun),
            )
          },
        "mapping" to
          result.mapping.map {
            linkedMapOf("requirement" to it.row.requirement, "expected" to it.row.expectedIds, "families" to it.row.families, "nonTest" to it.row.nonTest?.name?.lowercase(), "found" to it.found, "partial" to it.partial, "viaEvidence" to it.viaEvidence, "missing" to it.missing)
          },
        "splits" to result.splits.map { linkedMapOf("id" to it.catalogId, "full" to it.full, "suffixIds" to it.suffixIds, "notPassed" to it.notPassed) },
        "warnings" to result.warnings.map { linkedMapOf("kind" to it.kind, "subject" to it.subject, "message" to it.message) },
        "catalogueIds" to inputs.catalogue.ids.toList(),
      )
    return Json.write(root) + "\n"
  }
}

/**
 * `checkTraceability`: runs [TraceabilityCheck] over the repository and writes
 * build/reports/traceability-check.md and .json; fails on R1..R4. `-Ptraceability.results=none`
 * ignores result files (what CI sees on a clean checkout).
 */
@DisableCachingByDefault(because = "Reads the whole workspace; fast, and always reflects the latest sources and results")
abstract class CheckTraceability : DefaultTask() {

  @get:Internal abstract val repositoryRoot: DirectoryProperty

  @get:Input abstract val includeResults: Property<Boolean>

  @get:OutputFile abstract val markdownReport: RegularFileProperty

  @get:OutputFile abstract val jsonReport: RegularFileProperty

  init {
    group = "verification"
    description =
      "Checks test IDs against the docs/18 catalogue and the 18 §16.1 table, required requirements and the Gate 1 evidence page (WO-53)."
    doNotTrackState("Scans test sources, result files and design documents of the whole workspace on every run")
  }

  @TaskAction
  fun check() {
    val root = repositoryRoot.get().asFile
    val result = TraceabilityCheck.run(CheckInputs.load(root, includeResults.get()))
    val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    val md = markdownReport.get().asFile.apply { parentFile.mkdirs() }
    md.writeText(TraceabilityCheck.markdown(result, now))
    jsonReport.get().asFile.apply { parentFile.mkdirs() }.writeText(TraceabilityCheck.json(result, now))
    logger.lifecycle(
      "Traceability check: R1 ${result.count("R1")}, R2 ${result.count("R2")}, R3 ${result.count("R3")}, R4 ${result.count("R4")}; " +
        "${result.coverage.count { it.covered }}/${result.coverage.size} required requirements covered, ${result.warnings.size} warnings. Report: ${md.toURI()}"
    )
    if (result.failed) throw GradleException(TraceabilityCheck.failureMessage(result, md))
  }
}
