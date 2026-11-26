// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import io.argos.buildlogic.traceability.JUnitXmlResults.Status
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Aggregated result of one test over every result file that recorded it. [reasons]: why it was
 * skipped or failed, when the result format says (Playwright fixme, matrix runner requirements).
 */
data class ResultSummary(
  val status: Status,
  val runs: Int,
  val lastRun: String?,
  val suites: List<String>,
  val reasons: List<String> = emptyList(),
) {
  companion object {
    fun of(cases: List<JUnitXmlResults.Case>): ResultSummary? {
      if (cases.isEmpty()) return null
      return ResultSummary(statusOf(cases.map { it.status }), cases.size, cases.mapNotNull { it.timestamp }.maxOrNull(), cases.map { it.suite }.distinct().sorted())
    }

    fun ofScript(cases: List<ScriptCase>): ResultSummary? {
      if (cases.isEmpty()) return null
      val status = statusOf(cases.map { it.status })
      return ResultSummary(
        status,
        cases.size,
        cases.mapNotNull { it.timestamp }.maxOrNull(),
        cases.map { it.suite }.distinct().sorted(),
        cases.filter { it.status == status }.mapNotNull { it.reason }.distinct(),
      )
    }

    /** FAILED if any run failed, else PASSED if any passed, else SKIPPED. */
    fun statusOf(statuses: List<Status>): Status =
      when {
        Status.FAILED in statuses -> Status.FAILED
        Status.PASSED in statuses -> Status.PASSED
        else -> Status.SKIPPED
      }
  }
}

/**
 * One result of a test that is not a JUnit method: a Vitest case (JUnit XML of
 * ui/build/test-results/vitest), a Playwright test of one project, a shell assertion line (TAP) or a
 * matrix combination. [file] is the repository-relative test source file the result belongs to,
 * [name] the full title (Vitest/Playwright "describe > title", shell description, matrix row title),
 * [requirements] per-test requirements of the result itself (Playwright `req` annotations, matrix
 * N-13), [resultFile] the result file it was read from.
 */
data class ScriptCase(
  val source: String,
  val file: String,
  val name: String,
  val status: Status,
  val timestamp: String?,
  val suite: String,
  val resultFile: String,
  val testIds: List<String> = TestIds.find(name),
  val requirements: List<String> = emptyList(),
  val reason: String? = null,
)

/**
 * Counts of one kind of test source and result ([TestEntry.source]): test source files, result
 * files, result cases read, and result cases left out of the report because they have neither a
 * test ID nor a requirement and match no source test.
 */
data class SourceStats(val source: String, val sourceFiles: Int, val resultFiles: Int, val resultCases: Int, val unlistedResultCases: Int)

/**
 * One reported test: a source test, or one runtime case of a dynamic source test (factories,
 * parameterized tests), or a result whose source was not found. [name] is what JUnit, Vitest or
 * Playwright shows (display name, method name or title), a shell description or a matrix row title.
 * [source] is the kind: "junit", "vitest", "playwright", "shell" or "matrix" ([Traceability.SOURCES]).
 */
data class TestEntry(
  val testIds: List<String>,
  val requirements: List<String>,
  val tags: List<String>,
  val className: String,
  val name: String,
  val file: String,
  val line: Int,
  val suite: String,
  val result: ResultSummary?,
  val source: String = Traceability.SOURCE_JUNIT,
) {
  val quarantined: Boolean
    get() = "quarantine" in tags
}

/** Everything the report shows; built by [Traceability.build]. */
data class TraceabilityModel(
  val entries: List<TestEntry>,
  val sourceFiles: Int,
  val resultFiles: Int,
  val unreadableResultFiles: List<String>,
  val testsWithoutId: Int,
  /** Per kind of source, in the order of [Traceability.SOURCES] (empty for a model built by hand). */
  val sources: List<SourceStats> = emptyList(),
) {
  /** Last-result counts of the entries of [source]: PASSED, FAILED, SKIPPED, NOT RUN (all four keys). */
  fun statusCounts(source: String? = null): Map<String, Int> {
    val counts = entries.filter { source == null || it.source == source }.groupingBy { it.result?.status?.name ?: "NOT RUN" }.eachCount()
    return listOf("PASSED", "FAILED", "SKIPPED", "NOT RUN").associateWith { counts[it] ?: 0 }
  }

  /** Requirement -> entries, requirements in natural order (D-2 before D-10). */
  val byRequirement: Map<String, List<TestEntry>> by lazy {
    val map = sortedMapOf<String, MutableList<TestEntry>>(Traceability.naturalOrder)
    for (entry in entries) for (req in entry.requirements) map.getOrPut(req) { mutableListOf() } += entry
    map.mapValues { (_, list) -> list.sortedWith(Traceability.entryOrder) }
  }

  /** Entries with a test ID but no requirement tag. */
  val withoutRequirement: List<TestEntry> by lazy {
    entries.filter { it.requirements.isEmpty() && it.testIds.isNotEmpty() }.sortedWith(Traceability.entryOrder)
  }

  val testIds: Set<String> by lazy { entries.flatMap { it.testIds }.toSortedSet(Traceability.naturalOrder) }

  /**
   * Catalogue IDs that only partial checks cover (ADR-0010 C-122, C-182): some test carries a
   * suffixed ID of it (T-SEC-23a, T-SEC-3Aa), and no test that carries the catalogue ID itself has
   * passed. Catalogue ID -> its partial IDs, both in natural order. The report shows the passed
   * results of those partial tests as partial, never as a full PASSED of the catalogue ID.
   */
  val partiallyCovered: Map<String, List<String>> by lazy {
    val passedFull =
      entries.filter { it.result?.status == Status.PASSED }.flatMap { e -> e.testIds.filterNot(TestIds::isPartial) }.toSet()
    val map = sortedMapOf<String, MutableSet<String>>(Traceability.naturalOrder)
    for (id in testIds) {
      val catalog = TestIds.catalogId(id)
      if (catalog != id && catalog !in passedFull) map.getOrPut(catalog) { sortedSetOf(Traceability.naturalOrder) } += id
    }
    map.mapValues { (_, ids) -> ids.toList() }
  }

  /** The catalogue IDs of [entry]'s partial IDs that only partial checks cover ([partiallyCovered]). */
  fun partialOf(entry: TestEntry): List<String> =
    entry.testIds.filter(TestIds::isPartial).map(TestIds::catalogId).filter { it in partiallyCovered }.distinct()
}

object Traceability {

  const val SOURCE_JUNIT: String = "junit"
  const val SOURCE_VITEST: String = "vitest"

  /** Kinds of test sources and results, in report order. */
  val SOURCES: List<String> =
    listOf(SOURCE_JUNIT, SOURCE_VITEST, PlaywrightResults.SOURCE, ShellTestScanner.SOURCE, MatrixEvidence.SOURCE)

  /** A Vitest case in JUnit XML: classname is the test file relative to ui/ ("src/a/b.test.ts"). */
  private val scriptFile = Regex(""".+\.(test|spec)\.(ts|tsx|mts|cts|js|jsx|mjs|cjs)$""")

  /** Natural order of IDs: "T-AG-2" < "T-AG-10", "D-2" < "D-10". */
  val naturalOrder: Comparator<String> = Comparator { a, b -> compareNatural(a, b) }

  val entryOrder: Comparator<TestEntry> =
    compareBy<TestEntry, String>(naturalOrder) { it.testIds.firstOrNull() ?: "~" }
      .thenBy { it.className }
      .thenBy { it.name }

  private fun compareNatural(a: String, b: String): Int {
    val pa = Regex("""\d+|\D+""").findAll(a).map { it.value }.toList()
    val pb = Regex("""\d+|\D+""").findAll(b).map { it.value }.toList()
    for (k in 0 until minOf(pa.size, pb.size)) {
      val x = pa[k]
      val y = pb[k]
      val c =
        if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()).takeIf { it != 0 } ?: x.compareTo(y)
        else x.compareTo(y)
      if (c != 0) return c
    }
    return pa.size.compareTo(pb.size)
  }

  /**
   * Joins source tests with JUnit results. Results are matched per project directory and class:
   * first by exact name (display name, `method()` or `method(…)`); then dynamic source tests with a
   * literal name-template prefix take the remaining cases that start with it, and a single dynamic
   * test without a prefix takes the rest of its class. Cases left over are reported with the tags
   * of their class.
   *
   * Script tests (Vitest, Playwright, shell, matrix: [SourceTest.methodName] null) are joined with
   * [scriptResults] and with the Vitest cases among [results] (project `ui`, classname a test file)
   * by file and title, see [joinScripts].
   *
   * @param results results with the project directory (repository-relative) they belong to
   * @param sourceFileCounts test source files per kind of [SOURCES] (for [TraceabilityModel.sources])
   * @param resultFileCounts result files per kind of [SOURCES]
   */
  fun build(
    classes: List<SourceClass>,
    tests: List<SourceTest>,
    results: List<Pair<String, JUnitXmlResults.Case>>,
    sourceFiles: Int,
    resultFiles: Int,
    unreadableResultFiles: List<String> = emptyList(),
    testsWithoutId: Int = 0,
    scriptResults: List<ScriptCase> = emptyList(),
    sourceFileCounts: Map<String, Int> = emptyMap(),
    resultFileCounts: Map<String, Int> = emptyMap(),
  ): TraceabilityModel {
    val (vitestResults, junitResults) = results.partition { (project, case) -> project == "ui" && scriptFile.matches(case.className) }
    val vitestCases =
      vitestResults.map { (_, c) ->
        ScriptCase(SOURCE_VITEST, "ui/" + c.className, c.name, c.status, c.timestamp, c.suite, c.file.invariantSeparatorsPath)
      }
    val junit = buildJUnit(classes, tests, junitResults)
    val unlisted = HashMap<String, Int>()
    val scripts = joinScripts(tests.filter { it.methodName == null }, vitestCases + scriptResults, unlisted)
    val entries = junit + scripts
    val casesBySource = (vitestCases + scriptResults).groupingBy { it.source }.eachCount()
    val stats =
      SOURCES.map { s ->
        SourceStats(
          source = s,
          sourceFiles = sourceFileCounts[s] ?: tests.filter { sourceOf(it) == s }.map { it.file }.distinct().size,
          resultFiles = resultFileCounts[s] ?: 0,
          resultCases = if (s == SOURCE_JUNIT) junitResults.size else casesBySource[s] ?: 0,
          unlistedResultCases = unlisted[s] ?: 0,
        )
      }
    return TraceabilityModel(entries, sourceFiles, resultFiles, unreadableResultFiles, testsWithoutId, stats)
  }

  /** "junit" for a JUnit method, otherwise the script kind kept in [SourceTest.sourceSet]. */
  fun sourceOf(test: SourceTest): String = if (test.methodName != null) SOURCE_JUNIT else test.sourceSet

  /**
   * Joins script tests with their results. A case matches the source tests of its file whose title
   * produced it: Vitest and Playwright titles are one " > " segment of the case name (an `it` title
   * is the last one, a `describe` title an earlier one; `${…}` matches anything); a shell description
   * template matches the whole description (`$x`, `${…}`, `$(…)` match anything, a failure detail in
   * parentheses may follow), and a shell case that matches nothing in its script is tried against
   * the other scripts below the script's directory (container case files run by a parent script);
   * a matrix case matches the row with its ID. A case can match several source tests (a describe
   * and its test). A shell case without a test ID that matches nothing goes to the script's header
   * entry ([SourceTest.matchAll]). Requirements: Playwright `req` annotations of the results replace
   * the per-file association of the source; other sources add the case requirements to theirs.
   * Cases that match nothing are reported on their own when they have a test ID (then with the
   * requirements of their file's source tests too) or a requirement or failed, and counted in
   * [unlisted] otherwise.
   */
  private fun joinScripts(scriptTests: List<SourceTest>, cases: List<ScriptCase>, unlisted: MutableMap<String, Int>): List<TestEntry> {
    val byFile = scriptTests.groupBy { it.file }
    val matchers = IdentityHashMap<SourceTest, Regex>()
    fun matcher(test: SourceTest): Regex =
      matchers.getOrPut(test) {
        val title = test.displayName.orEmpty()
        when (test.sourceSet) {
          ShellTestScanner.SOURCE -> Regex("^" + ShellTestScanner.templatePattern(title, shell = true) + "(?: \\(.*\\))?$", RegexOption.DOT_MATCHES_ALL)
          else -> Regex("^(?:.* > )?" + ShellTestScanner.templatePattern(title, shell = false) + "(?: > .*)?$", RegexOption.DOT_MATCHES_ALL)
        }
      }
    fun matches(test: SourceTest, case: ScriptCase): Boolean =
      when {
        test.matchAll -> false
        test.sourceSet == MatrixEvidence.SOURCE -> case.testIds.any { it in test.testIds }
        test.displayName.isNullOrEmpty() -> false
        else -> matcher(test).matches(case.name)
      }
    val casesOf = IdentityHashMap<SourceTest, MutableList<ScriptCase>>()
    val leftover = mutableListOf<ScriptCase>()
    for (case in cases) {
      val own = byFile[case.file].orEmpty().filter { it.sourceSet == case.source }
      var hits = own.filter { matches(it, case) }
      if (hits.isEmpty() && case.source == ShellTestScanner.SOURCE) {
        val dir = case.file.substringBeforeLast('/', "") + "/"
        hits = scriptTests.filter { it.sourceSet == ShellTestScanner.SOURCE && it.file != case.file && it.file.startsWith(dir) && matches(it, case) }
        if (hits.isEmpty() && case.testIds.isEmpty()) hits = own.filter { it.matchAll }
      }
      if (hits.isEmpty()) leftover += case
      for (test in hits) casesOf.getOrPut(test) { mutableListOf() } += case
    }
    val entries = mutableListOf<TestEntry>()
    for (test in scriptTests) {
      val mine = casesOf[test].orEmpty()
      val caseReqs = mine.flatMap { it.requirements }.distinct()
      val reqs =
        if (test.sourceSet == PlaywrightResults.SOURCE && caseReqs.isNotEmpty()) caseReqs else (test.requirements + caseReqs).distinct()
      entries +=
        TestEntry(test.testIds, reqs, test.tags, test.className, test.displayName.orEmpty(), test.file, test.line, test.sourceSet, ResultSummary.ofScript(mine), sourceOf(test))
    }
    val fileReqs = byFile.mapValues { (_, list) -> list.flatMap { it.requirements }.distinct() }
    for ((key, group) in leftover.groupBy { Triple(it.source, it.file, it.name) }) {
      val (source, file, name) = key
      val ids = group.flatMap { it.testIds }.distinct()
      val caseReqs = group.flatMap { it.requirements }.distinct()
      // Like the source scanners: only a test with an ID takes the per-file requirements.
      val inherited = if (ids.isNotEmpty()) fileReqs[file].orEmpty() else emptyList()
      val reqs = if (source == PlaywrightResults.SOURCE) caseReqs.ifEmpty { inherited } else (caseReqs + inherited).distinct()
      // A failure is always listed (a shell script that exited non-zero without a description).
      if (ids.isEmpty() && reqs.isEmpty() && group.none { it.status == Status.FAILED }) {
        unlisted.merge(source, group.size, Int::plus)
        continue
      }
      entries += TestEntry(ids, reqs, emptyList(), file, name, file, 0, group.first().suite, ResultSummary.ofScript(group), source)
    }
    return entries
  }

  private fun buildJUnit(classes: List<SourceClass>, tests: List<SourceTest>, results: List<Pair<String, JUnitXmlResults.Case>>): List<TestEntry> {
    val classByKey = HashMap<Pair<String, String>, SourceClass>()
    val projectOfClassFile = HashMap<String, String>()
    for (test in tests) projectOfClassFile[test.file] = test.projectDir
    for (c in classes) classByKey[(projectOfClassFile[c.file] ?: projectDirOf(c.file)) to c.className] = c

    val casesByKey = results.groupBy({ (project, case) -> project to case.className }, { it.second })
    // Identity: two runs of the same test can produce equal cases; each one counts.
    val consumed: MutableSet<JUnitXmlResults.Case> = Collections.newSetFromMap(IdentityHashMap())
    val entries = mutableListOf<TestEntry>()

    val junitTests = tests.filter { it.methodName != null }

    // 1. Exact matches of static and dynamic tests.
    val exact = HashMap<SourceTest, List<JUnitXmlResults.Case>>()
    for (test in junitTests) {
      val cases = casesByKey[test.projectDir to test.className].orEmpty()
      val method = test.methodName!!
      val matched =
        cases.filter { c ->
          c.name == test.displayName || c.name == "$method()" || c.name.startsWith("$method(")
        }
      consumed += matched
      exact[test] = matched
    }
    // 2. Dynamic tests take the remaining cases of their class.
    val dynamicByClass = junitTests.filter { it.dynamic }.groupBy { it.projectDir to it.className }
    val dynamicCases = HashMap<SourceTest, List<JUnitXmlResults.Case>>()
    for ((key, dynamics) in dynamicByClass) {
      val remaining = casesByKey[key].orEmpty().filter { it !in consumed }
      fun prefixOf(test: SourceTest) = test.nameTemplate?.substringBefore('{')?.trim().orEmpty()
      // Tests with a literal name prefix first, then a single test without one takes the rest.
      val (templated, plain) = dynamics.partition { prefixOf(it).isNotEmpty() }
      for (test in templated) {
        val mine = remaining.filter { it !in consumed && it.name.startsWith(prefixOf(test)) }
        consumed += mine
        dynamicCases[test] = mine
      }
      if (plain.size == 1) {
        val mine = remaining.filter { it !in consumed }
        consumed += mine
        dynamicCases[plain[0]] = mine
      }
    }
    for (test in junitTests) {
      val dyn = dynamicCases[test].orEmpty()
      if (test.dynamic && dyn.isNotEmpty()) {
        // One entry per runtime name: factories name their cases (T-BLD-006/T-AG-060 ...).
        for ((name, cases) in dyn.groupBy { it.name }) {
          entries +=
            TestEntry(
              testIds = TestIds.find(name).ifEmpty { test.testIds },
              requirements = test.requirements,
              tags = test.tags,
              className = test.className,
              name = name,
              file = test.file,
              line = test.line,
              suite = test.sourceSet,
              result = ResultSummary.of(cases),
            )
        }
      } else {
        entries +=
          TestEntry(
            test.testIds,
            test.requirements,
            test.tags,
            test.className,
            test.displayName ?: test.nameTemplate ?: "${test.methodName}()",
            test.file,
            test.line,
            test.sourceSet,
            ResultSummary.of(exact[test].orEmpty()),
          )
      }
    }
    // 3. Results without a source test: dynamic names of classes with several dynamic tests,
    // or classes whose source was not scanned.
    for ((project, case) in results) {
      if (case in consumed) continue
      val owner = classByKey[project to case.className] ?: classByKey[project to case.className.substringBefore('$')]
      entries +=
        TestEntry(
          testIds = TestIds.find(case.name).ifEmpty { owner?.testIds.orEmpty() },
          requirements = owner?.requirements.orEmpty(),
          tags = owner?.tags.orEmpty(),
          className = case.className,
          name = case.name,
          file = owner?.file ?: case.file.invariantSeparatorsPath,
          line = owner?.line ?: 0,
          suite = case.suite,
          result = ResultSummary.of(listOf(case)),
        )
      consumed += case
    }
    // Merge result-only entries recorded by several runs (the same name in two test tasks).
    val merged =
      entries.groupBy { listOf(it.className, it.name, it.file) }.map { (_, group) ->
        if (group.size == 1) group[0]
        else
          group[0].copy(
            testIds = group.flatMap { it.testIds }.distinct(),
            requirements = group.flatMap { it.requirements }.distinct(),
            tags = group.flatMap { it.tags }.distinct(),
            result = mergeResults(group.mapNotNull { it.result }),
          )
      }
    return merged
  }

  private fun mergeResults(results: List<ResultSummary>): ResultSummary? {
    if (results.isEmpty()) return null
    return ResultSummary(
      ResultSummary.statusOf(results.map { it.status }),
      results.sumOf { it.runs },
      results.mapNotNull { it.lastRun }.maxOrNull(),
      results.flatMap { it.suites }.distinct().sorted(),
      results.flatMap { it.reasons }.distinct(),
    )
  }

  /** "agent/agent-core/src/test/java/..." -> "agent/agent-core". */
  fun projectDirOf(path: String): String {
    val marker = path.indexOf("/src/")
    return if (marker >= 0) path.substring(0, marker) else if (path.startsWith("src/")) "" else path.substringBeforeLast('/')
  }
}
