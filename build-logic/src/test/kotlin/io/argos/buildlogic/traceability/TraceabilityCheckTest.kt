// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import groovy.json.JsonSlurper
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A small repository for the traceability check: the fixture documents of
 * src/test/resources/traceability/check (docs/18, 01, 06, the Gate 1 evidence page, the evidence
 * registry), one Java test class and, on request, its JUnit XML results. The Java source is written
 * at run time so that the repository's own scan never sees it.
 */
internal object CheckFixture {
  private val documents =
    listOf(
      "docs/18-test-design.md",
      "docs/01-product-plan.md",
      "docs/06-security-requirements.md",
      "docs/gates/gate1-evidence.md",
      "test/traceability/evidence.yaml",
    )

  /** Display name -> requirement tags of the fixture tests. */
  val tests: List<Pair<String, List<String>>> =
    listOf(
      "T-ACC-01 monotonic clock" to listOf("D-01"),
      "T-ACC-02 clock skew" to listOf("D-02"),
      "T-ACC-02 skew also checks the monotonic clock" to listOf("D-01"),
      "T-SEC-01 token scope" to listOf("SEC-01"),
      "T-SEC-11 gzip bomb" to listOf("SEC-11", "N-10b"),
      "T-SEC-12a quota part" to listOf("SEC-12"),
      "T-SEC-13 dictionary flood" to listOf("SEC-13"),
      "T-AG-031a wrapper" to listOf("D-24"),
      "T-AG-031b proxy" to listOf("D-24"),
      "T-AG-031c driver" to listOf("D-24"),
      "T-MX-001 boot3 on JDK 21" to listOf("N-13"),
    )

  const val SOURCE = "fixture/src/test/java/example/FixtureTest.java"
  const val RESULTS = "fixture/build/test-results/test/TEST-example.FixtureTest.xml"

  fun create(dir: File, extraTests: List<Pair<String, List<String>>> = emptyList()) {
    for (path in documents) {
      val text = checkNotNull(javaClass.getResource("/traceability/check/$path")) { path }.readText()
      File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
    }
    writeSource(dir, tests + extraTests)
  }

  fun writeSource(dir: File, all: List<Pair<String, List<String>>>) {
    val methods =
      all.mapIndexed { i, (name, reqs) ->
        "  @Test ${reqs.joinToString(" ") { "@Tag(\"req:$it\")" }} @DisplayName(\"$name\") void m$i() {}"
      }
    File(dir, SOURCE).apply { parentFile.mkdirs() }.writeText(
      "package example;\n\nimport org.junit.jupiter.api.*;\n\nclass FixtureTest {\n" + methods.joinToString("\n") + "\n}\n"
    )
  }

  /** JUnit XML of every fixture test; the names in [failed] fail. */
  fun writeResults(dir: File, failed: Set<String> = emptySet(), names: List<String> = tests.map { it.first }) {
    val cases =
      names.joinToString("\n") { name ->
        if (name in failed) "  <testcase name=\"$name\" classname=\"example.FixtureTest\" time=\"0.01\"><failure message=\"x\">x</failure></testcase>"
        else "  <testcase name=\"$name\" classname=\"example.FixtureTest\" time=\"0.01\"/>"
      }
    File(dir, RESULTS).apply { parentFile.mkdirs() }.writeText(
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"example.FixtureTest\" tests=\"${names.size}\" timestamp=\"2026-11-26T10:00:00\">\n$cases\n</testsuite>\n"
    )
  }

  fun replace(dir: File, path: String, old: String, new: String) {
    val file = File(dir, path)
    val text = file.readText()
    check(text.contains(old)) { "$path has no '$old'" }
    file.writeText(text.replace(old, new))
  }
}

/**
 * checkTraceability (WO-53): R1 catalogue, R2 18 §16.1 diff, R3 required requirements, R4 Gate 1
 * evidence page; warnings that never fail; the C-182 split marker; a clean checkout (no results)
 * passes with every test NOT RUN.
 */
@Tag("req:DSN-18-01")
class TraceabilityCheckTest {
  @TempDir lateinit var dir: File

  @BeforeEach
  fun setUp() = CheckFixture.create(dir)

  private fun check(includeResults: Boolean = true) = TraceabilityCheck.run(CheckInputs.load(dir, includeResults))

  @Test
  @DisplayName("T-BLD-014g a consistent repository without result files passes: R1..R4 0, every test NOT RUN, warnings do not fail")
  fun cleanCheckoutPasses() {
    val result = check(includeResults = false)
    assertThat(result.violations).isEmpty()
    assertThat(result.failed).isFalse()
    assertThat(result.coverage.map { it.id })
      .containsExactly("D-01", "D-02", "N-01", "N-02", "N-09", "N-10a", "N-10b", "N-11", "N-13", "SEC-01", "SEC-11", "SEC-30", "SEC-91")
    assertThat(result.coverage).allSatisfy { assertThat(it.covered).isTrue() }
    assertThat(result.coverage.single { it.id == "N-09" }.evidence.single().kind).isEqualTo(EvidenceKind.DESIGN)
    assertThat(result.coverage.single { it.id == "D-01" }.results.notRun).isGreaterThan(0)
    assertThat(result.coverage.sumOf { it.results.passed + it.results.failed }).isZero()
    // Reported, never failing: the number rule, a §16.1 ID covered only by a suffixed ID, partial IDs.
    assertThat(result.warnings.map { it.kind }).contains("id-number", "partial-mapping", "partial")
    assertThat(result.warnings.single { it.kind == "id-number" }.message).contains("D-01").contains("not D-02")
    assertThat(result.warnings.single { it.kind == "partial-mapping" }.message).contains("T-SEC-12").contains("T-SEC-12a")
    assertThat(result.splits.single().full).isFalse()
    val md = TraceabilityCheck.markdown(result, "2026-11-26T00:00:00Z")
    assertThat(md).contains("| Verdict | **PASS** |").contains("Results: ignored").contains("| T-AG-031 | partial |")
    val json = JsonSlurper().parseText(TraceabilityCheck.json(result, "now")) as Map<*, *>
    assertThat(json["verdict"]).isEqualTo("PASS")
    assertThat((json["summary"] as Map<*, *>)["R3"]).isEqualTo(0)
  }

  @Test
  @DisplayName("T-BLD-014g split marker (C-182): '(분할)' catalogue ID is full when every suffix passed, partial when one failed")
  fun splitMarker() {
    CheckFixture.writeResults(dir)
    val full = check()
    assertThat(full.violations).isEmpty()
    assertThat(full.splits.single().full).isTrue()
    assertThat(full.warnings.filter { it.kind == "partial" }.map { it.subject }).doesNotContain("T-AG-031")
    assertThat(TraceabilityCheck.markdown(full, "now")).contains("| T-AG-031 | full (split) |")

    CheckFixture.writeResults(dir, failed = setOf("T-AG-031c driver"))
    val partial = check()
    assertThat(partial.violations).isEmpty()
    assertThat(partial.splits.single().notPassed).containsExactly("T-AG-031c")
    assertThat(partial.warnings.map { it.kind }).contains("failed")
    // Results ignored: the same workspace is a clean checkout again.
    assertThat(check(includeResults = false).coverage.sumOf { it.results.failed }).isZero()
  }

  @Test
  @DisplayName("T-BLD-014g R1: an uncatalogued ID fails with where it is used, a malformed ID only warns, evidence IDs are checked")
  fun r1() {
    CheckFixture.writeSource(dir, CheckFixture.tests + listOf("T-AG-099 new test" to listOf("F-A01"), "T-SEC-010 typo" to listOf("SEC-01")))
    CheckFixture.replace(dir, "test/traceability/evidence.yaml", "[T-SOAK-001]", "[T-SOAK-001, T-SOAK-002]")
    val result = check(includeResults = false)
    assertThat(result.violations.map { it.rule to it.subject }).containsExactlyInAnyOrder("R1" to "T-AG-099", "R1" to "T-SOAK-002")
    assertThat(result.violations.first { it.subject == "T-AG-099" }.message).contains(CheckFixture.SOURCE)
    assertThat(result.violations.first { it.subject == "T-SOAK-002" }.message).contains("evidence.yaml:")
    assertThat(result.warnings.single { it.kind == "malformed-id" }.subject).isEqualTo("T-SEC-010")
    assertThat(TraceabilityCheck.failureMessage(result, null)).contains("R1 2").contains("R1 T-AG-099").contains("appendix A")
  }

  @Test
  @DisplayName("T-BLD-014g R2: a 18 §16.1 ID without test or evidence, and a design row without its evidence entry, fail with the diff")
  fun r2() {
    CheckFixture.writeSource(dir, CheckFixture.tests.filterNot { it.first == "T-SEC-13 dictionary flood" })
    CheckFixture.replace(dir, "test/traceability/evidence.yaml", "requirements: [N-09]", "requirements: [N-08]")
    val result = check(includeResults = false)
    assertThat(result.violations.filter { it.rule == "R2" }.map { it.subject }).containsExactlyInAnyOrder("N-10b", "N-09")
    assertThat(result.violations.first { it.subject == "N-10b" }.message).contains("T-SEC-13")
    assertThat(result.violations.first { it.subject == "N-09" }.message).contains("non-test evidence (design)")
    // N-09 is also a required requirement without coverage now.
    assertThat(result.violations.filter { it.rule == "R3" }.map { it.subject }).containsExactly("N-09")
    assertThat(result.mapping.single { it.row.requirement == "N-10b" }.missing).containsExactly("T-SEC-13")
  }

  @Test
  @DisplayName("T-BLD-014g R3: an automatic stage-M SEC needs a T-SEC family ID or a tagged test (then a warning), D needs a test")
  fun r3() {
    // A test tagged req:SEC-01 under another ID covers SEC-01 with a warning.
    val renamed = CheckFixture.tests.map { if (it.first == "T-SEC-01 token scope") "T-BLD-001 token scope seen by the build" to it.second else it }
    CheckFixture.writeSource(dir, renamed)
    val tagged = check(includeResults = false)
    assertThat(tagged.violations).isEmpty()
    assertThat(tagged.warnings.single { it.kind == "sec-without-id" }.message).contains("T-BLD-001").contains("T-SEC-01")

    // Without the tag, and without any D-02 test or §16.1 row, both fail.
    val untagged = CheckFixture.tests.map { if (it.first == "T-SEC-01 token scope") "T-BLD-001 token scope seen by the build" to listOf("O-L04") else it }
    CheckFixture.writeSource(dir, untagged.filterNot { it.first.startsWith("T-ACC-02") })
    CheckFixture.replace(dir, "docs/18-test-design.md", "| D-01~D-02 | T-ACC-01~02(10장) |", "| D-01 | T-ACC-01 |")
    val result = check(includeResults = false)
    val r3 = result.violations.filter { it.rule == "R3" }
    assertThat(r3.map { it.subject }).containsExactlyInAnyOrder("SEC-01", "D-02")
    assertThat(r3.first { it.subject == "SEC-01" }.message).contains("T-SEC-01 family")
    assertThat(result.coverage.single { it.id == "SEC-30" }.covered).isTrue()
  }

  @Test
  @DisplayName("T-BLD-014g R2/R3 on a clean checkout: an expected ID that only a dynamic test's runtime name carries counts, with a warning")
  fun dynamicTestNames() {
    CheckFixture.writeSource(dir, CheckFixture.tests.filterNot { it.first.startsWith("T-ACC-02") })
    File(dir, "fixture/src/test/java/example/FactoryTest.java").writeText(
      """
      package example;

      import java.util.stream.Stream;
      import org.junit.jupiter.api.*;

      class FactoryTest {
        @TestFactory
        Stream<DynamicTest> clocks() {
          return Stream.of("a", "b").map(x -> DynamicTest.dynamicTest("T-ACC-02 skew " + x, () -> {}));
        }
      }
      """.trimIndent()
    )
    val result = check(includeResults = false)
    assertThat(result.violations).isEmpty()
    assertThat(result.warnings.single { it.kind == "dynamic-name-only" }.message).contains("D-02").contains("FactoryTest.java")
    assertThat(result.coverage.single { it.id == "D-02" }.mappedIds).containsExactly("T-ACC-02")
    // Without the literal the same checkout misses T-ACC-02 (R2) and D-02 (R3).
    File(dir, "fixture/src/test/java/example/FactoryTest.java").delete()
    assertThat(check(includeResults = false).violations.map { it.rule to it.subject }).containsExactlyInAnyOrder("R2" to "D-02", "R3" to "D-02")
  }

  @Test
  @DisplayName("T-BLD-014g R4: the Gate 1 evidence page names only catalogued IDs and must exist")
  fun r4() {
    CheckFixture.replace(dir, "docs/gates/gate1-evidence.md", "T-SEC-12a", "T-SEC-12a, T-ACC-77")
    val result = check(includeResults = false)
    assertThat(result.violations.map { it.rule to it.subject }).containsExactly("R4" to "T-ACC-77")
    assertThat(result.violations.single().message).contains("gate1-evidence.md:6")
    File(dir, CheckInputs.GATE1_EVIDENCE).delete()
    assertThat(check(includeResults = false).violations.map { it.rule }).containsExactly("R4")
  }
}
