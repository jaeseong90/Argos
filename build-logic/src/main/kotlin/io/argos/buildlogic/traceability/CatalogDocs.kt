// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

/**
 * Test ID expressions as the design documents write them (18 §3, appendix A): one ID (`T-AG-031`,
 * `T-SEC-3Aa`, `T-E2E-stats-1`), numeric ranges (`T-ST-001~020`, zero padding of the start kept),
 * lists (`T-SEC-01·02`, `T-E2E-dumps-1·2`), suffix-letter ranges and lists (`T-SV-010a~v`,
 * `T-AG-052a~k·p~s`, `T-ACC-23m·23n`), and in the first column of a catalogue table also comma lists
 * (`T-NFR-01~04, 26~30`). Text after an ID (`T-NFR-30 (부착 시험)`, `T-SEC-47 (fresh)`) is a variant
 * note of the same ID. A family name without a number (`T-MX 전부`, `T-ACPT 설치`) is a family
 * reference, not an ID.
 */
object IdExpressions {

  private val prefixes = TestIds.PREFIXES.joinToString("|")

  /** Start of an expression: prefix, optional E2E screen area, number, capital and small letter. */
  private val start =
    Regex(
      """(?<![A-Za-z0-9-])T-($prefixes)-(?:([a-z]+(?:-[a-z]+)*)-)?([0-9]+)([A-Z]?)([a-z]?)(?![A-Za-z0-9])"""
    )
  private val family = Regex("""(?<![A-Za-z0-9-])T-($prefixes)(?![-A-Za-z0-9])""")
  private val numberToken = Regex("""^([0-9]+)([A-Z]?)([a-z]?)(?![A-Za-z0-9])""")
  private val letterToken = Regex("""^([a-z])(?![A-Za-z0-9])""")

  /** One expression found in a text: what was written, where, and the IDs it stands for. */
  data class Expression(val text: String, val offset: Int, val ids: List<String>)

  private data class Part(val number: String, val upper: String, val lower: String)

  /**
   * Every expression in [text], in order. [allowComma] also continues an expression over `, <number>`
   * (catalogue first columns only; in prose a comma ends the expression).
   */
  fun find(text: String, allowComma: Boolean = false): List<Expression> {
    val out = mutableListOf<Expression>()
    var from = 0
    while (true) {
      val m = start.find(text, from) ?: break
      val head = "T-${m.groupValues[1]}-" + m.groupValues[2].let { if (it.isEmpty()) "" else "$it-" }
      val ids = LinkedHashSet<String>()
      var last = Part(m.groupValues[3], m.groupValues[4], m.groupValues[5])
      ids += head + last.number + last.upper + last.lower
      var i = m.range.last + 1
      loop@ while (i < text.length) {
        val c = text[i]
        val (separator, skip) =
          when {
            c == '~' -> '~' to 1
            c == '·' -> '·' to 1
            allowComma && c == ',' && text.startsWith(", ", i) && i + 2 < text.length && text[i + 2].isDigit() -> ',' to 2
            else -> break@loop
          }
        // `T-NFR-01~T-NFR-04`: the end may repeat the head.
        val repeated = if (text.startsWith(head, i + skip)) head.length else 0
        val rest = text.substring(i + skip + repeated)
        val number = numberToken.find(rest)
        val letter = if (number == null) letterToken.find(rest) else null
        when {
          number != null -> {
            val next = Part(number.groupValues[1], number.groupValues[2], number.groupValues[3])
            if (separator == '~') ids += range(head, last, next) else ids += head + next.number + next.upper + next.lower
            last = next
            i += skip + repeated + number.value.length
          }
          letter != null && repeated == 0 -> {
            val next = Part(last.number, last.upper, letter.groupValues[1])
            if (separator == '~' && last.lower.isNotEmpty()) {
              for (ch in last.lower[0]..next.lower[0]) ids += head + last.number + last.upper + ch
            } else {
              ids += head + next.number + next.upper + next.lower
            }
            last = next
            i += skip + 1
          }
          else -> break@loop
        }
      }
      out += Expression(text.substring(m.range.first, i), m.range.first, ids.toList())
      from = maxOf(i, m.range.last + 1)
    }
    return out
  }

  /** All IDs of every expression in [text], distinct, in order. */
  fun expand(text: String, allowComma: Boolean = false): List<String> = find(text, allowComma).flatMap { it.ids }.distinct()

  /** Family references without a number (`T-MX 전부` -> T-MX). */
  fun families(text: String): List<String> = family.findAll(text).map { "T-" + it.groupValues[1] }.distinct().toList()

  private fun range(head: String, from: Part, to: Part): List<String> {
    if (from.upper.isEmpty() && to.upper.isEmpty() && from.lower.isEmpty() && to.lower.isEmpty()) {
      val a = from.number.toInt()
      val b = to.number.toInt()
      if (b < a || b - a > 999) return listOf(head + from.number, head + to.number)
      val width = from.number.length
      return (a..b).map { head + it.toString().padStart(width, '0') }
    }
    if (from.number == to.number && from.upper == to.upper && from.lower.isNotEmpty() && to.lower.isNotEmpty()) {
      return (from.lower[0]..to.lower[0]).map { head + from.number + from.upper + it }
    }
    return listOf(head + to.number + to.upper + to.lower)
  }
}

/** Requirement IDs D-nn, N-nn(a~d) and SEC-nn in text, with `·` lists and `~` ranges (`D-03·12·13`, `N-01~N-07`). */
object RequirementIds {
  private val start = Regex("""(?<![A-Za-z0-9-])(D|N|SEC)-([0-9]+)([A-Z]?)([a-z]?)(?![A-Za-z0-9])""")
  private val token = Regex("""^(?:(D|N|SEC)-)?([0-9]+)([A-Z]?)([a-z]?)(?![A-Za-z0-9])""")
  private val letter = Regex("""^([a-z])(?![A-Za-z0-9])""")

  fun expand(text: String): List<String> {
    val out = LinkedHashSet<String>()
    var from = 0
    while (true) {
      val m = start.find(text, from) ?: break
      val prefix = m.groupValues[1]
      var number = m.groupValues[2]
      var upper = m.groupValues[3]
      var lower = m.groupValues[4]
      out += "$prefix-$number$upper$lower"
      var i = m.range.last + 1
      while (i < text.length && (text[i] == '~' || text[i] == '·')) {
        val separator = text[i]
        val rest = text.substring(i + 1)
        val t = token.find(rest)
        if (t != null && (t.groupValues[1].isEmpty() || t.groupValues[1] == prefix)) {
          val n = t.groupValues[2]
          if (separator == '~' && upper.isEmpty() && lower.isEmpty() && t.groupValues[3].isEmpty() && t.groupValues[4].isEmpty()) {
            val a = number.toInt()
            val b = n.toInt()
            if (b >= a && b - a < 200) for (k in a..b) out += "$prefix-" + k.toString().padStart(number.length, '0')
          } else {
            out += "$prefix-$n${t.groupValues[3]}${t.groupValues[4]}"
          }
          number = n
          upper = t.groupValues[3]
          lower = t.groupValues[4]
          i += 1 + t.value.length
          continue
        }
        val l = letter.find(rest)
        if (l != null && lower.isNotEmpty()) {
          val end = l.groupValues[1][0]
          if (separator == '~') for (ch in lower[0]..end) out += "$prefix-$number$upper$ch" else out += "$prefix-$number$upper$end"
          lower = end.toString()
          i += 2
          continue
        }
        break
      }
      from = maxOf(i, m.range.last + 1)
    }
    return out.toList()
  }
}

/** One catalogue row of docs/18: a table row whose first column holds test ID expressions. */
data class CatalogRow(
  val expression: String,
  val ids: List<String>,
  val line: Int,
  val section: String,
  val description: String,
  val requirements: List<String>,
  val appendix: Boolean,
) {
  /**
   * The '(분할)' marker of appendix A (ADR-0010 C-182): the suffixed IDs of this row divide their
   * catalogue ID between them, so the catalogue ID counts as fully covered when all of them passed.
   */
  val split: Boolean
    get() = appendix && description.trimStart().startsWith(CatalogDocs.SPLIT_MARKER)
}

/** The test catalogue of docs/18: every ID of every row, and the split groups of appendix A. */
class Catalogue(val rows: List<CatalogRow>) {
  val ids: Set<String> = rows.flatMap { it.ids }.toSortedSet(Traceability.naturalOrder)

  operator fun contains(id: String): Boolean = id in ids

  /** Catalogue ID -> the suffixed IDs of its '(분할)' rows (C-182). */
  val splits: Map<String, List<String>> by lazy {
    val map = sortedMapOf<String, MutableSet<String>>(Traceability.naturalOrder)
    for (row in rows.filter { it.split }) {
      for (id in row.ids.filter(TestIds::isPartial)) map.getOrPut(TestIds.catalogId(id)) { sortedSetOf(Traceability.naturalOrder) } += id
    }
    map.mapValues { it.value.toList() }
  }

  /** The rows that list [id]. */
  fun rowsOf(id: String): List<CatalogRow> = rows.filter { id in it.ids }
}

/** Evidence that a 18 §16.1 row asks for and that is not a test (`(설계 확인, 테스트 없음)`, `T-ACPT …`, `ASVS 체크리스트`). */
enum class NonTestKind {
  DESIGN,
  HUMAN,
}

/**
 * One requirement of the 18 §16.1 table with what it expects: test IDs, families without a number
 * (`T-MX 전부`) and non-test evidence.
 */
data class MappingRow(
  val requirement: String,
  val text: String,
  val line: Int,
  val expectedIds: List<String>,
  val families: List<String>,
  val nonTest: NonTestKind?,
)

/**
 * A requirement of docs/01 (D-nn, N-nn) or docs/06 (SEC-nn) with its verification method and stage
 * (06 §0: M = before Gate 1).
 */
data class RequirementDef(val id: String, val document: String, val line: Int, val verification: String?, val stage: String?) {
  /** Stage M (06): the stage cell names M (`M`, `M(초안)·G2`, `M(인증·토큰·명령·설정)·G2`). */
  val stageM: Boolean
    get() = stage != null && Regex("""(?<![A-Za-z0-9])M(?![A-Za-z0-9])""").containsMatchIn(stage)

  /** Verification starts with "자동" (06 §0: CI test). */
  val automatic: Boolean
    get() = verification?.trimStart()?.startsWith("자동") == true

  /** First word of the verification cell: 자동, 수동 or 정적. */
  val verificationKind: String?
    get() = verification?.trim()?.takeWhile { it != ':' && !it.isWhitespace() && it != '(' }?.ifEmpty { null }
}

/** Parses the design documents the traceability check reads (18, 01, 06; WO-53). */
object CatalogDocs {

  const val SPLIT_MARKER = "(분할)"
  const val CATALOGUE_DOC = "docs/18-test-design.md"
  const val PLAN_DOC = "docs/01-product-plan.md"
  const val SECURITY_DOC = "docs/06-security-requirements.md"

  /** A Markdown table row: its cells (outer pipes removed, `\|` kept as text) and line. */
  data class TableRow(val cells: List<String>, val line: Int, val header: List<String>, val section: String, val appendix: Boolean)

  /** Every body row of every pipe table in [text], with the header of its table and the heading above it. */
  fun tableRows(text: String): List<TableRow> {
    val out = mutableListOf<TableRow>()
    var section = ""
    var appendix = false
    var header: List<String>? = null
    var inTable = false
    val lines = text.lines()
    for ((index, raw) in lines.withIndex()) {
      val line = raw.trim()
      if (line.startsWith("#")) {
        section = line.trimStart('#').trim()
        if (line.startsWith("## ")) appendix = section.startsWith("부록 A")
        inTable = false
        header = null
        continue
      }
      if (!line.startsWith("|")) {
        inTable = false
        header = null
        continue
      }
      val cells = cells(line)
      if (!inTable) {
        // A table starts with a header followed by its separator row.
        val next = lines.getOrNull(index + 1)?.trim().orEmpty()
        if (next.startsWith("|") && cells(next).all { it.matches(Regex(""":?-+:?""")) }) {
          header = cells
          inTable = true
        }
        continue
      }
      if (cells.all { it.matches(Regex(""":?-+:?""")) }) continue
      out += TableRow(cells, index + 1, header.orEmpty(), section, appendix)
    }
    return out
  }

  private fun cells(line: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    val body = line.trim().removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
    while (i < body.length) {
      val c = body[i]
      if (c == '\\' && i + 1 < body.length && body[i + 1] == '|') {
        current.append('|')
        i += 2
        continue
      }
      if (c == '|') {
        parts += current.toString().trim()
        current.clear()
      } else current.append(c)
      i++
    }
    parts += current.toString().trim()
    return parts
  }

  /** The catalogue: the first-column IDs of every table of docs/18 (body and appendix A). */
  fun catalogue(text: String): Catalogue {
    val rows = mutableListOf<CatalogRow>()
    for (row in tableRows(text)) {
      val first = row.cells.firstOrNull()?.replace("`", "") ?: continue
      // The ID convention table of 18 §3 names patterns (`T-AG-nnn`), not IDs.
      if (!first.startsWith("T-")) continue
      val ids = IdExpressions.expand(first, allowComma = true)
      if (ids.isEmpty()) continue
      val requirementCell =
        if (row.appendix || row.header.lastOrNull() == "요구") row.cells.lastOrNull().orEmpty() else ""
      rows +=
        CatalogRow(
          expression = first,
          ids = ids,
          line = row.line,
          section = row.section,
          description = row.cells.getOrNull(1).orEmpty(),
          requirements = requirementTokens(requirementCell),
          appendix = row.appendix,
        )
    }
    return Catalogue(rows)
  }

  /** Requirement tokens of a requirement cell: D/N/SEC expanded, other IDs (F-A01, DSN-18-01, O-L04) as written. */
  fun requirementTokens(cell: String): List<String> {
    val others =
      Regex("""(?<![A-Za-z0-9-])(?:F-[A-Z]+[0-9]+|DSN-[0-9]+-[0-9]+|O-[A-Z][0-9]+)""").findAll(cell).map { it.value }.toList()
    return (RequirementIds.expand(cell) + others).distinct()
  }

  /** The 18 §16.1 table: requirement -> expected test IDs (two requirement/test pairs per row). */
  fun mapping(text: String): List<MappingRow> {
    val out = mutableListOf<MappingRow>()
    for (row in tableRows(text).filter { it.section.startsWith("16.1") }) {
      for (pair in row.cells.chunked(2)) {
        if (pair.size < 2 || pair[0].isBlank()) continue
        val requirements = RequirementIds.expand(pair[0])
        val testText = pair[1].replace("`", "")
        val ids = IdExpressions.expand(testText)
        val nonTest =
          when {
            testText.contains("설계 확인") -> NonTestKind.DESIGN
            testText.trimStart().startsWith("T-ACPT") || testText.contains("ASVS 체크리스트") -> NonTestKind.HUMAN
            else -> null
          }
        val families =
          if (nonTest != null) emptyList()
          else IdExpressions.families(testText).filter { f -> ids.none { it.startsWith("$f-") } }
        if (requirements.size > 1 && requirements.size == ids.size) {
          // D-01~D-26 | T-ACC-01~26: one to one.
          requirements.forEachIndexed { k, req -> out += MappingRow(req, pair[1], row.line, listOf(ids[k]), families, nonTest) }
        } else {
          for (req in requirements) out += MappingRow(req, pair[1], row.line, ids, families, nonTest)
        }
      }
    }
    return out
  }

  /** D-nn and N-nn of docs/01 (the first column of its requirement tables). */
  fun planRequirements(text: String): List<RequirementDef> =
    tableRows(text)
      .mapNotNull { row ->
        val id = row.cells.firstOrNull()?.replace("*", "")?.trim().orEmpty()
        if (id.matches(Regex("""(D|N)-[0-9]{2}[a-d]?"""))) RequirementDef(id, PLAN_DOC, row.line, null, null) else null
      }
      .distinctBy { it.id }

  /** SEC-nn of docs/06 with verification and stage (the last two columns of its requirement tables). */
  fun securityRequirements(text: String): List<RequirementDef> =
    tableRows(text)
      .mapNotNull { row ->
        val id = row.cells.firstOrNull()?.trim().orEmpty()
        if (!id.matches(Regex("""SEC-[0-9]+[A-Z]?"""))) return@mapNotNull null
        val stageIndex = row.header.indexOf("단계")
        val verificationIndex = row.header.indexOf("검증")
        // Count from the end: an unescaped pipe inside a code span of the requirement text adds cells.
        val fromEnd = row.cells.size - row.header.size
        val stage = if (stageIndex >= 0) row.cells.getOrNull(stageIndex + fromEnd) else row.cells.lastOrNull()
        val verification = if (verificationIndex >= 0) row.cells.getOrNull(verificationIndex + fromEnd) else null
        RequirementDef(id, SECURITY_DOC, row.line, verification, stage)
      }
      .distinctBy { it.id }
}
