// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import io.argos.buildlogic.traceability.JUnitXmlResults.Status
import java.util.Collections
import java.util.IdentityHashMap

/** Aggregated result of one test over every result file that recorded it. */
data class ResultSummary(val status: Status, val runs: Int, val lastRun: String?, val suites: List<String>) {
  companion object {
    fun of(cases: List<JUnitXmlResults.Case>): ResultSummary? {
      if (cases.isEmpty()) return null
      val status =
        when {
          cases.any { it.status == Status.FAILED } -> Status.FAILED
          cases.any { it.status == Status.PASSED } -> Status.PASSED
          else -> Status.SKIPPED
        }
      return ResultSummary(status, cases.size, cases.mapNotNull { it.timestamp }.maxOrNull(), cases.map { it.suite }.distinct().sorted())
    }
  }
}

/**
 * One reported test: a source test, or one runtime case of a dynamic source test (factories,
 * parameterized tests), or a result whose source was not found. [name] is what JUnit, Vitest or
 * Playwright shows (display name, method name or title).
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
) {
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
}

object Traceability {

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
   * @param results results with the project directory (repository-relative) they belong to
   */
  fun build(
    classes: List<SourceClass>,
    tests: List<SourceTest>,
    results: List<Pair<String, JUnitXmlResults.Case>>,
    sourceFiles: Int,
    resultFiles: Int,
    unreadableResultFiles: List<String> = emptyList(),
    testsWithoutId: Int = 0,
  ): TraceabilityModel {
    val classByKey = HashMap<Pair<String, String>, SourceClass>()
    val projectOfClassFile = HashMap<String, String>()
    for (test in tests) projectOfClassFile[test.file] = test.projectDir
    for (c in classes) classByKey[(projectOfClassFile[c.file] ?: projectDirOf(c.file)) to c.className] = c

    val casesByKey = results.groupBy({ (project, case) -> project to case.className }, { it.second })
    // Identity: two runs of the same test can produce equal cases; each one counts.
    val consumed: MutableSet<JUnitXmlResults.Case> = Collections.newSetFromMap(IdentityHashMap())
    val entries = mutableListOf<TestEntry>()

    val junitTests = tests.filter { it.methodName != null }
    val scriptTests = tests.filter { it.methodName == null }

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
    val scripts =
      scriptTests.map {
        TestEntry(it.testIds, it.requirements, it.tags, it.className, it.displayName.orEmpty(), it.file, it.line, it.sourceSet, null)
      }
    return TraceabilityModel(merged + scripts, sourceFiles, resultFiles, unreadableResultFiles, testsWithoutId)
  }

  private fun mergeResults(results: List<ResultSummary>): ResultSummary? {
    if (results.isEmpty()) return null
    val status =
      when {
        results.any { it.status == Status.FAILED } -> Status.FAILED
        results.any { it.status == Status.PASSED } -> Status.PASSED
        else -> Status.SKIPPED
      }
    return ResultSummary(
      status,
      results.sumOf { it.runs },
      results.mapNotNull { it.lastRun }.maxOrNull(),
      results.flatMap { it.suites }.distinct().sorted(),
    )
  }

  /** "agent/agent-core/src/test/java/..." -> "agent/agent-core". */
  fun projectDirOf(path: String): String {
    val marker = path.indexOf("/src/")
    return if (marker >= 0) path.substring(0, marker) else if (path.startsWith("src/")) "" else path.substringBeforeLast('/')
  }
}
