// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import groovy.json.JsonSlurper
import io.argos.buildlogic.traceability.JUnitXmlResults.Status
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The traceability report generator skeleton (18 §3, §16, DSN-18-01): test IDs and requirement tags
 * from Java, Kotlin and TypeScript test sources, results from JUnit XML, the join and the output.
 */
@Tag("req:DSN-18-01")
class TraceabilityScannerTest {
  @TempDir lateinit var dir: File

  private val javaSource =
    """
    package com.example;

    import org.junit.jupiter.api.*;
    import static org.assertj.core.api.Assertions.assertThat;

    @Tag("req:F-A01")
    @DisplayName("T-AG-010 class level")
    class SampleTest {
      // @Test void commented() {}
      /* @Tag("req:X-99") @Test void blockComment() {} */
      private static final String TEXT = ""${'"'}
          @Test void inTextBlock() {}
          ""${'"'};

      @Test
      @Tag("req:D-04")
      @DisplayName("T-AG-011 dispatch: " +
          "five kinds")
      void dispatch() {
        String s = "@Test void fake() {";
        char c = '{';
        Object record = null;
        if (record == null) { s = "}"; }
      }

      @Test
      void plainMethod() {}

      @ParameterizedTest(name = "T-BLD-003 rejects a jar without {0}")
      @ValueSource(strings = {"a", "b"})
      void parameterized(String x) {}

      @Nested
      @Tag("quarantine")
      class Inner {
        @Test
        @Tags({@Tag("req:SEC-06"), @Tag("slow")})
        void nested() {}
      }

      @TestFactory
      java.util.stream.Stream<DynamicTest> factory() { return java.util.stream.Stream.empty(); }

      record Point(int x, int y) {}
    }
    """
      .trimIndent()

  private val kotlinSource =
    """
    package io.example

    import org.junit.jupiter.api.Tag
    import org.junit.jupiter.api.Test as JTest

    @Tag("req:O-L03")
    class KotlinTest {
      @TempDir lateinit var dir: File
      private val repo: File
        get() = File(dir, "repo")

      data class Row(val a: Int)

      @Test
      fun `T-BLD-007 backtick name`() {
        val s = "${'$'}{dir.name} {"
      }

      @Test
      @DisplayName("T-BLD-002 display")
      fun displayed() {}

      @Test @Tags(Tag("req:SEC-77"), Tag("req:O-L04")) fun kotlinTags() {}

      companion object {
        @JvmStatic fun helper() = 1
      }
    }
    """
      .trimIndent()

  private fun scanJava() = JavaKotlinTestScanner.scan("p/src/test/java/com/example/SampleTest.java", javaSource, "p", "test")

  @Test
  @DisplayName("T-BLD-014 Java: class and method tags, concatenated display names, nested classes, comments and strings")
  fun javaScanner() {
    val scan = scanJava()
    assertThat(scan.classes.map { it.className })
      .containsExactly("com.example.SampleTest", "com.example.SampleTest\$Inner", "com.example.SampleTest\$Point")
    val byMethod = scan.tests.associateBy { it.methodName }
    assertThat(byMethod.keys).containsExactly("dispatch", "plainMethod", "parameterized", "nested", "factory")
    with(byMethod.getValue("dispatch")) {
      assertThat(displayName).isEqualTo("T-AG-011 dispatch: five kinds")
      assertThat(testIds).containsExactly("T-AG-011")
      assertThat(requirements).containsExactly("D-04", "F-A01")
      assertThat(line).isEqualTo(19)
      assertThat(dynamic).isFalse()
    }
    // No own ID: the class display name's ID applies.
    assertThat(byMethod.getValue("plainMethod").testIds).containsExactly("T-AG-010")
    with(byMethod.getValue("parameterized")) {
      assertThat(testIds).containsExactly("T-BLD-003")
      assertThat(nameTemplate).isEqualTo("T-BLD-003 rejects a jar without {0}")
      assertThat(dynamic).isTrue()
    }
    with(byMethod.getValue("nested")) {
      assertThat(className).isEqualTo("com.example.SampleTest\$Inner")
      assertThat(requirements).containsExactly("SEC-06", "F-A01")
      assertThat(tags).containsExactly("slow", "quarantine")
    }
    assertThat(byMethod.getValue("factory").dynamic).isTrue()
  }

  @Test
  @DisplayName("T-BLD-014 Kotlin: backtick names, templates, nested Tag(...) without @, body-less classes, companions")
  fun kotlinScanner() {
    val scan = JavaKotlinTestScanner.scan("build-logic/src/test/kotlin/K.kt", kotlinSource, "build-logic", "test")
    assertThat(scan.tests.map { it.methodName }).containsExactly("T-BLD-007 backtick name", "displayed", "kotlinTags")
    assertThat(scan.tests.map { it.className }.distinct()).containsExactly("io.example.KotlinTest")
    assertThat(scan.tests[0].testIds).containsExactly("T-BLD-007")
    assertThat(scan.tests[0].requirements).containsExactly("O-L03")
    assertThat(scan.tests[1].testIds).containsExactly("T-BLD-002")
    assertThat(scan.tests[2].requirements).containsExactly("SEC-77", "O-L04", "O-L03")
    assertThat(scan.classes.map { it.className }).contains("io.example.KotlinTest", "io.example.KotlinTest\$Companion")
  }

  @Test
  @DisplayName("T-BLD-014 test IDs: every prefix of 18 §3, suffixes, several per name, no partial matches")
  fun testIds() {
    assertThat(TestIds.find("T-BLD-006/T-AG-060 release jar")).containsExactly("T-BLD-006", "T-AG-060")
    assertThat(TestIds.find("T-AG-031a, T-NFR-10a, T-SEC-3A, T-ACPT-07, T-E2E-040, T-SOAK-001"))
      .containsExactly("T-AG-031a", "T-NFR-10a", "T-SEC-3A", "T-ACPT-07", "T-E2E-040", "T-SOAK-001")
    assertThat(TestIds.find("XT-AG-001 T-XX-001 T-AG-01ab C-22 T-AG- T-AG-001")).containsExactly("T-AG-001")
    assertThat(TestIds.find(null)).isEmpty()
  }

  @Test
  @DisplayName(
    // No other test ID in the name: the report would read it as a test of that ID.
    "T-BLD-014 test IDs ending in a letter (ADR-0010 C-182): [0-9]+[A-Z]?[a-z]?, a trailing lower-case letter " +
      "resolves to the catalogue ID, the NFR IDs of N-10a..d are catalogue IDs themselves"
  )
  fun letterEndedTestIds() {
    assertThat(TestIds.find("T-SEC-3Aa masked default; T-SEC-3A, T-AG-031a, T-SEC-3Ab"))
      .containsExactly("T-SEC-3Aa", "T-SEC-3A", "T-AG-031a", "T-SEC-3Ab")
    assertThat(TestIds.find("T-SEC-3aA T-SEC-3AB T-AG-01ab T-SEC-3Aab")).isEmpty()
    assertThat(TestIds.catalogId("T-SEC-3Aa")).isEqualTo("T-SEC-3A")
    assertThat(TestIds.catalogId("T-AG-031a")).isEqualTo("T-AG-031")
    assertThat(TestIds.catalogId("T-SEC-3A")).isEqualTo("T-SEC-3A")
    assertThat(TestIds.catalogId("T-AG-031")).isEqualTo("T-AG-031")
    assertThat(TestIds.catalogId("T-NFR-10a")).isEqualTo("T-NFR-10a")
    assertThat(TestIds.isPartial("T-SEC-23a")).isTrue()
    assertThat(TestIds.isPartial("T-SEC-3A")).isFalse()
    assertThat(TestIds.isPartial("T-NFR-10b")).isFalse()
  }

  @Test
  @DisplayName(
    "T-BLD-014 report (ADR-0010 C-182): a catalogue ID that only suffixed IDs passed is shown as partial, not as a " +
      "full PASSED; a passed full ID keeps its partial checks plain"
  )
  fun partialCoverage() {
    val source =
      """
      package p;
      @Tag("req:SEC-23")
      class PartialTest {
        @Test @DisplayName("T-SEC-23a gate unit") void gate() {}
        @Test @Tag("req:SEC-3A") @DisplayName("T-SEC-3Aa agent side, masked") void agentSide() {}
        @Test @Tag("req:SEC-35") @DisplayName("T-SEC-35 normalizer") void full() {}
        @Test @Tag("req:SEC-35") @DisplayName("T-SEC-35a brackets") void brackets() {}
        @Test @Tag("req:N-10a") @DisplayName("T-NFR-10a checklist") void asvs() {}
        @Test @Tag("req:SEC-26") @DisplayName("T-SEC-26a not run yet") void notRun() {}
      }
      """
        .trimIndent()
    val scan = JavaKotlinTestScanner.scan("p/src/test/java/p/PartialTest.java", source, "p", "test")
    fun passed(name: String) =
      "p" to JUnitXmlResults.Case("p.PartialTest", name, Status.PASSED, 0.1, "2026-11-14T00:00:00Z", "test", File("p/x.xml"))
    val results =
      listOf("T-SEC-23a gate unit", "T-SEC-3Aa agent side, masked", "T-SEC-35 normalizer", "T-SEC-35a brackets", "T-NFR-10a checklist")
        .map(::passed)
    val model = Traceability.build(scan.classes, scan.tests, results, sourceFiles = 1, resultFiles = 1)
    assertThat(model.partiallyCovered)
      .containsExactly(
        org.assertj.core.api.Assertions.entry("T-SEC-3A", listOf("T-SEC-3Aa")),
        org.assertj.core.api.Assertions.entry("T-SEC-23", listOf("T-SEC-23a")),
        org.assertj.core.api.Assertions.entry("T-SEC-26", listOf("T-SEC-26a")),
      )
    val md = TraceabilityRenderer.markdown(model, "now")
    assertThat(md)
      .contains("| Catalogue test IDs covered only by partial checks (suffixed IDs, C-182) | 3 |")
      .contains("| SEC-23 | T-SEC-23a | `PartialTest` T-SEC-23a gate unit ")
      .contains("| test | PASSED (2026-11-14T00:00:00Z), partial of T-SEC-23 |")
      .contains("T-SEC-3Aa agent side, masked ([p/src/test/java/p/PartialTest.java:5](p/src/test/java/p/PartialTest.java)) | test | PASSED (2026-11-14T00:00:00Z), partial of T-SEC-3A |")
      .contains("T-SEC-35a brackets ([p/src/test/java/p/PartialTest.java:7](p/src/test/java/p/PartialTest.java)) | test | PASSED (2026-11-14T00:00:00Z) |")
      .contains("T-NFR-10a checklist ([p/src/test/java/p/PartialTest.java:8](p/src/test/java/p/PartialTest.java)) | test | PASSED (2026-11-14T00:00:00Z) |")
      .contains("T-SEC-26a not run yet ([p/src/test/java/p/PartialTest.java:9](p/src/test/java/p/PartialTest.java)) | test | NOT RUN |")
      .contains("## Catalogue test IDs covered only by partial checks")
      .contains("| T-SEC-23 | T-SEC-23a |")
    @Suppress("UNCHECKED_CAST")
    val json = JsonSlurper().parseText(TraceabilityRenderer.json(model, "now")) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val tests = (json["requirements"] as List<Map<String, Any?>>).flatMap { it["tests"] as List<Map<String, Any?>> }
    assertThat(tests.filter { it.containsKey("partialOf") }.map { it["name"] to it["partialOf"] }.distinct())
      .containsExactlyInAnyOrder(
        "T-SEC-23a gate unit" to listOf("T-SEC-23"),
        "T-SEC-3Aa agent side, masked" to listOf("T-SEC-3A"),
        "T-SEC-26a not run yet" to listOf("T-SEC-26"),
      )
    @Suppress("UNCHECKED_CAST")
    assertThat((json["summary"] as Map<String, Any?>)["partiallyCoveredTestIds"]).isEqualTo(3)
  }

  @Test
  @DisplayName("T-BLD-014 Vitest and Playwright titles with IDs and file-level req annotations")
  fun scriptScanner() {
    val ts =
      """
      const REQ = { annotation: { type: 'req', description: 'F-U02' } };
      describe('T-UI-001 coordinate transforms', () => {
        it('T-UI-021 maps x', () => {});
        it("no id here", () => {});
      });
      test.skip(`T-E2E-040 deep link`, REQ, async () => {});
      testInfo.annotations.push({ type: "req", description: "N-14" });
      """
        .trimIndent()
    val scan = ScriptTestScanner.scan("ui/src/x.test.ts", ts, "ui", "vitest")
    assertThat(scan.tests.map { it.testIds.single() to it.line })
      .containsExactly("T-UI-001" to 2, "T-UI-021" to 3, "T-E2E-040" to 6)
    assertThat(scan.tests.first().requirements).containsExactly("F-U02", "N-14")
    assertThat(scan.untitledTests).isEqualTo(1)
    val md = TraceabilityRenderer.markdown(Traceability.build(emptyList(), scan.tests, emptyList(), 1, 0), "now")
    assertThat(md).contains("| F-U02 | T-UI-021 | `x.test.ts` T-UI-021 maps x ([ui/src/x.test.ts:3](ui/src/x.test.ts)) | vitest | NOT RUN |")
  }

  @Test
  @DisplayName("T-BLD-014 JUnit XML: passed, failed, errored and skipped cases, timestamps, malformed files")
  fun junitXml() {
    val xml =
      File(dir, "test/TEST-a.xml").apply {
        parentFile.mkdirs()
        writeText(
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <testsuite name="a.A" timestamp="2026-11-02T16:55:37.674Z">
            <testcase name="ok &amp; fine" classname="a.A" time="0.5"/>
            <testcase name="bad()" classname="a.A" time="1"><failure message="boom">trace</failure></testcase>
            <testcase name="err()" classname="a.A" time="1"><error message="boom"/></testcase>
            <testcase name="skip()" classname="a.A" time="0"><skipped/></testcase>
            <system-out><![CDATA[out]]></system-out>
          </testsuite>
          """
            .trimIndent()
        )
      }
    val cases = JUnitXmlResults.read(xml)
    assertThat(cases.map { it.name to it.status })
      .containsExactly("ok & fine" to Status.PASSED, "bad()" to Status.FAILED, "err()" to Status.FAILED, "skip()" to Status.SKIPPED)
    assertThat(cases.first().suite).isEqualTo("test")
    assertThat(cases.first().timestamp).isEqualTo("2026-11-02T16:55:37.674Z")
    val broken = File(dir, "test/TEST-broken.xml").apply { writeText("<testsuite><testcase name=") }
    assertThat(JUnitXmlResults.read(broken)).isEmpty()
    val dtd =
      File(dir, "test/TEST-dtd.xml").apply {
        writeText("""<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><testsuite><testcase name="&e;" classname="a"/></testsuite>""")
      }
    assertThat(JUnitXmlResults.read(dtd).map { it.name }).allSatisfy { assertThat(it).doesNotContain("root:") }
  }

  @Test
  @DisplayName("T-BLD-014 join: exact names, parameterized prefixes, factory cases, result-only cases, aggregation")
  fun join() {
    val scan = scanJava()
    fun case(className: String, name: String, status: Status = Status.PASSED, suite: String = "test", ts: String = "2026-11-01T00:00:00Z") =
      "p" to JUnitXmlResults.Case(className, name, status, 0.1, ts, suite, File("p/build/test-results/$suite/x.xml"))
    val results =
      listOf(
        case("com.example.SampleTest", "T-AG-011 dispatch: five kinds"),
        case("com.example.SampleTest", "T-AG-011 dispatch: five kinds", suite = "slowTest", ts = "2026-11-02T00:00:00Z"),
        case("com.example.SampleTest", "plainMethod()", Status.FAILED),
        case("com.example.SampleTest", "T-BLD-003 rejects a jar without a"),
        case("com.example.SampleTest", "T-BLD-003 rejects a jar without b", Status.SKIPPED),
        case("com.example.SampleTest", "T-SEC-26 dynamic case"),
        case("com.example.SampleTest\$Inner", "nested()", Status.SKIPPED),
        // Another project with the same class name never matches.
        "q" to JUnitXmlResults.Case("com.example.SampleTest", "plainMethod()", Status.PASSED, 0.1, null, "test", File("q/x.xml")),
        case("com.example.Gone", "T-AG-099 stale result"),
      )
    val model = Traceability.build(scan.classes, scan.tests, results, sourceFiles = 1, resultFiles = 2)
    val byName = model.entries.associateBy { it.name }
    with(byName.getValue("T-AG-011 dispatch: five kinds").result!!) {
      assertThat(status).isEqualTo(Status.PASSED)
      assertThat(runs).isEqualTo(2)
      assertThat(lastRun).isEqualTo("2026-11-02T00:00:00Z")
      assertThat(suites).containsExactly("slowTest", "test")
    }
    val plain = model.entries.filter { it.name == "plainMethod()" }.associateBy { it.file }
    assertThat(plain.getValue("p/src/test/java/com/example/SampleTest.java").result!!.status).isEqualTo(Status.FAILED)
    assertThat(plain.getValue("q/x.xml").result!!.status).isEqualTo(Status.PASSED)
    assertThat(byName.getValue("T-BLD-003 rejects a jar without a").testIds).containsExactly("T-BLD-003")
    assertThat(byName.getValue("T-BLD-003 rejects a jar without b").result!!.status).isEqualTo(Status.SKIPPED)
    with(byName.getValue("T-SEC-26 dynamic case")) {
      assertThat(testIds).containsExactly("T-SEC-26")
      assertThat(requirements).containsExactly("F-A01")
      assertThat(line).isEqualTo(42)
    }
    assertThat(byName.getValue("nested()").quarantined).isTrue()
    assertThat(byName.getValue("T-AG-099 stale result").requirements).isEmpty()
    assertThat(model.entries.filter { it.className == "com.example.SampleTest" && it.name == "plainMethod()" }).hasSize(2)
    assertThat(model.byRequirement.keys).containsExactly("D-04", "F-A01", "SEC-06")
    assertThat(model.byRequirement.getValue("D-04").map { it.testIds }).containsExactly(listOf("T-AG-011"))
    assertThat(model.withoutRequirement.map { it.name }).contains("T-AG-099 stale result")
  }

  @Test
  @DisplayName("T-BLD-014 report: markdown table with requirement, test ID, test, suite, last result; valid JSON twin")
  fun rendering() {
    val scan = scanJava()
    val results =
      listOf(
        "p" to
          JUnitXmlResults.Case(
            "com.example.SampleTest",
            "T-AG-011 dispatch: five kinds",
            Status.PASSED,
            0.1,
            "2026-11-01T00:00:00Z",
            "test",
            File("p/build/test-results/test/x.xml"),
          ),
        "p" to
          JUnitXmlResults.Case("com.example.Untagged", "boom()", Status.FAILED, 0.1, null, "test", File("p/build/test-results/test/y.xml")),
      )
    val model = Traceability.build(scan.classes, scan.tests, results, sourceFiles = 1, resultFiles = 1, testsWithoutId = 3)
    val md = TraceabilityRenderer.markdown(model, "2026-11-03T00:00:00Z")
    assertThat(md)
      .contains("| Requirement | Test ID | Test | Suite | Last result |")
      .contains("## Failed in the last results (tagged or not)")
      .contains("| - | `Untagged` boom() ([p/build/test-results/test/y.xml:0](p/build/test-results/test/y.xml)) | test | FAILED |")
      .contains(
        "| D-04 | T-AG-011 | `SampleTest` T-AG-011 dispatch: five kinds ([p/src/test/java/com/example/SampleTest.java:19]" +
          "(p/src/test/java/com/example/SampleTest.java)) | test | PASSED (2026-11-01T00:00:00Z) |"
      )
      .contains("| F-A01 | T-AG-010 | `SampleTest` plainMethod() ")
      .contains("NOT RUN")
      .contains("| Quarantined tests (`@Tag(\"quarantine\")`, limit 10, DSN-18-13) | 1 |")
      .contains("## Quarantined tests")
      .contains("| Tests with neither (JUnit methods and UI titles) | 3 |")
    @Suppress("UNCHECKED_CAST")
    val json = JsonSlurper().parseText(TraceabilityRenderer.json(model, "2026-11-03T00:00:00Z")) as Map<String, Any?>
    assertThat(json["generatedAt"]).isEqualTo("2026-11-03T00:00:00Z")
    @Suppress("UNCHECKED_CAST")
    val requirements = json["requirements"] as List<Map<String, Any?>>
    assertThat(requirements.map { it["id"] }).containsExactly("D-04", "F-A01", "SEC-06")
    assertThat(Json.write(mapOf("a" to "x\"y\\z\n\u0001", "b" to listOf(1, true, null), "c" to emptyMap<String, Any>())))
      .isEqualTo("{\n  \"a\": \"x\\\"y\\\\z\\n\\u0001\",\n  \"b\": [1, true, null],\n  \"c\": {}\n}")
  }

  @Test
  @DisplayName("T-BLD-014 repository walk: test source sets, UI tests and test results only; outputs and dependencies skipped")
  fun repositoryWalk() {
    fun put(path: String, text: String = "x") = File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
    put("a/src/test/java/A.java")
    put("a/src/integrationTest/java/B.java")
    put("a/src/main/java/Main.java")
    put("a/src/archFixtures/java/F.java")
    put("a/src/test/java/io/argos/build/Pkg.java") // a source package named build
    put("build-logic/src/test/kotlin/K.kt")
    put("a/build/test-results/test/TEST-A.xml", "<testsuite/>")
    put("a/build/test-results/integrationTest/TEST-B.xml", "<testsuite/>")
    put("a/build/classes/java/test/A.java")
    put("ui/src/x.test.ts")
    put("ui/e2e/specs/y.spec.ts")
    put("ui/node_modules/z/z.test.ts")
    put("docs/bench/q/src/test/java/Q.java")
    put("a/build/test-results/test/binary/results.bin")
    val files = RepositoryScan.collect(dir)
    fun rel(list: List<File>) = list.map { it.relativeTo(dir).invariantSeparatorsPath }
    assertThat(rel(files.junitSources))
      .containsExactlyInAnyOrder(
        "a/src/test/java/A.java",
        "a/src/integrationTest/java/B.java",
        "a/src/test/java/io/argos/build/Pkg.java",
        "build-logic/src/test/kotlin/K.kt",
      )
    assertThat(rel(files.scriptSources)).containsExactlyInAnyOrder("ui/src/x.test.ts", "ui/e2e/specs/y.spec.ts")
    assertThat(rel(files.resultFiles))
      .containsExactly("a/build/test-results/integrationTest/TEST-B.xml", "a/build/test-results/test/TEST-A.xml")
    assertThat(RepositoryScan.sourceSetOf("x/src/matrixTest/java/A.java")).isEqualTo("matrixTest")
    assertThat(RepositoryScan.sourceSetOf("x/src/main/java/A.java")).isNull()
    assertThat(RepositoryScan.projectDirOfResult("agent/agent-core/build/test-results/test/T.xml")).isEqualTo("agent/agent-core")
    assertThat(RepositoryScan.projectDirOfResult("build/test-results/test/T.xml")).isEqualTo("")
  }
}
