// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.testing

import io.argos.buildlogic.repositoryRoot
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Results and the generated compatibility table (18 §6.2, WO-50): JUnit XML -> build/matrix/<ID>.json,
 * the verdict rules (a combination that did not run, an unverified row and a skipped required test
 * are never ○), community reports and the generated Markdown.
 */
@Tag("req:DSN-18-11")
class MatrixReportTest {

  @TempDir lateinit var tmp: File

  private val catalog =
    MatrixCatalog.parse(
      """
      version: 1
      rows:
        - id: T-MX-021
          group: boot3
          app: shop-boot3
          jdk: 21
          suites: [io.argos.agent.testing.matrix.MatrixSmokeTest]
          required: [io.argos.agent.testing.matrix.MatrixSmokeTest]
        - id: T-MX-022
          aliases: [T-MX-017]
          group: boot3
          app: shop-boot3
          jdk: 21
          vt: true
          suites: [io.argos.agent.testing.matrix.MatrixSmokeTest]
          required: [io.argos.agent.testing.matrix.MatrixSmokeTest]
        - id: T-MX-005
          group: boot2
          app: shop-boot2
          jdk: 25
          status: unverified
          suites: [io.argos.agent.testing.matrix.MatrixSmokeTest]
          required: [io.argos.agent.testing.matrix.MatrixSmokeTest]
          note: Boot 2.7 on JDK 25
        - id: T-MX-105
          group: jdbc
          app: shop-boot3
          jdk: 21
          suites: [io.argos.agent.testing.jdbc.*]
          required: ["*VendorDatabaseTest.T-AG-030 tibero *"]
          requires: [env:ARGOS_TEST_TIBERO_URL]
          note: Tibero | in-house
        - id: T-MX-094
          group: war-jakarta
          app: shop-war-jakarta
          jdk: 21
          tomcat: tomcat11
          suites: [io.argos.agent.testing.matrix.WarRedeployTest]
          required: [io.argos.agent.testing.matrix.WarRedeployTest, io.argos.agent.testing.servlet.WarContextPrefixTest]
      """.trimIndent()
    )

  private fun row(id: String) = catalog.find(id)!!

  private fun case(cls: String, name: String, status: String, message: String = "") = MatrixRunResult.Case(cls, name, status, message)

  private fun result(id: String, vararg cases: MatrixRunResult.Case, missing: List<String> = emptyList()) =
    MatrixRunResult(
      id = id,
      task = ":agent:agent-testing:matrixTest-$id",
      commit = "0123456789abcdef0123456789abcdef01234567",
      os = "Linux",
      startedAt = "2026-11-26T01:00:00Z",
      finishedAt = "2026-11-26T01:04:00Z",
      durationSeconds = 240,
      passed = cases.count { it.status == "PASSED" },
      failed = cases.count { it.status == "FAILED" },
      skipped = cases.count { it.status == "SKIPPED" },
      cases = cases.toList(),
      skipReasons = cases.filter { it.status == "SKIPPED" }.groupingBy { it.message }.eachCount(),
      failures = cases.filter { it.status == "FAILED" }.map { "${it.className.substringAfterLast('.')} > ${it.name}: ${it.message}" },
      metrics = mapOf("startup_ms" to "4210", "transformed_classes" to "812", "transactions" to "520"),
      lines = emptyList(),
      missingRequirements = missing,
    )

  private val smoke = "io.argos.agent.testing.matrix.MatrixSmokeTest"

  @Test
  @DisplayName("T-BLD-029 verdicts: not run, unverified row, missing requirement, no pass or skipped required test are △, never ○")
  fun verdictRules() {
    fun judge(id: String, r: MatrixRunResult?, community: List<CommunityReport> = emptyList()) = MatrixReport.judge(row(id), r, community)

    assertThat(judge("T-MX-021", null)).isEqualTo(MatrixReport.Judgement(MatrixVerdict.UNVERIFIED, "not run"))
    assertThat(judge("T-MX-021", result("T-MX-021", case(smoke, "T-MX-021 smoke", "PASSED"))).verdict).isEqualTo(MatrixVerdict.VERIFIED)
    assertThat(judge("T-MX-021", result("T-MX-021", case(smoke, "T-MX-021 smoke", "PASSED"), case(smoke, "T-MX-021 dump", "FAILED", "boom"))))
      .isEqualTo(MatrixReport.Judgement(MatrixVerdict.FAILED, "1 failed: MatrixSmokeTest > T-MX-021 dump: boom"))
    // Unverified rows: passing is △, failing is ✗ but marked as not a gate.
    assertThat(judge("T-MX-005", result("T-MX-005", case(smoke, "a", "PASSED"))))
      .isEqualTo(MatrixReport.Judgement(MatrixVerdict.UNVERIFIED, "unverified combination, run but not a gate (1 passed, 0 skipped)"))
    assertThat(judge("T-MX-005", result("T-MX-005", case(smoke, "a", "FAILED", "x"))).reason).contains("(unverified row, not a gate)")
    // Missing requirement.
    assertThat(judge("T-MX-105", result("T-MX-105", case("io.argos.agent.testing.jdbc.JdbcStepsTest", "T-AG-030 h2 shop-boot3@21: x", "PASSED"), missing = listOf("env:ARGOS_TEST_TIBERO_URL"))))
      .isEqualTo(MatrixReport.Judgement(MatrixVerdict.UNVERIFIED, "requires env:ARGOS_TEST_TIBERO_URL (not set on the runner)"))
    // Only skips.
    assertThat(judge("T-MX-021", result("T-MX-021", case(smoke, "a", "SKIPPED", "Docker is not available"))))
      .isEqualTo(MatrixReport.Judgement(MatrixVerdict.UNVERIFIED, "no test passed (1 skipped: Docker is not available)"))
    // A required pattern that did not run, or ran with a skip.
    assertThat(judge("T-MX-094", result("T-MX-094", case("io.argos.agent.testing.matrix.WarRedeployTest", "T-AG-053w", "PASSED"))))
      .isEqualTo(MatrixReport.Judgement(MatrixVerdict.UNVERIFIED, "required io.argos.agent.testing.servlet.WarContextPrefixTest did not run"))
    assertThat(
      judge(
        "T-MX-094",
        result(
          "T-MX-094",
          case("io.argos.agent.testing.matrix.WarRedeployTest", "T-AG-053w", "PASSED"),
          case("io.argos.agent.testing.servlet.WarContextPrefixTest", "T-AG-015 a", "PASSED"),
          case("io.argos.agent.testing.servlet.WarContextPrefixTest", "T-AG-015 b", "SKIPPED", "no second context"),
        ),
      )
    ).isEqualTo(MatrixReport.Judgement(MatrixVerdict.UNVERIFIED, "required io.argos.agent.testing.servlet.WarContextPrefixTest skipped: no second context"))
    // Display-name patterns (dynamic tests).
    assertThat(
      judge("T-MX-105", result("T-MX-105", case("io.argos.agent.testing.jdbc.VendorDatabaseTest", "T-AG-030 tibero on a trusted host", "PASSED"))).verdict
    ).isEqualTo(MatrixVerdict.VERIFIED)
    // Community reports turn △ into ◎, never ✗ or ○.
    val report = CommunityReport("T-MX-005", "T-MX-005", "https://github.com/argos-apm/argos/issues/7", "1.0.0", "2026-12-01", "")
    assertThat(judge("T-MX-005", null, listOf(report)).verdict).isEqualTo(MatrixVerdict.COMMUNITY)
    assertThat(judge("T-MX-005", result("T-MX-005", case(smoke, "a", "PASSED")), listOf(report)).verdict).isEqualTo(MatrixVerdict.COMMUNITY)
    assertThat(judge("T-MX-005", result("T-MX-005", case(smoke, "a", "FAILED")), listOf(report)).verdict).isEqualTo(MatrixVerdict.FAILED)
    assertThat(judge("T-MX-021", result("T-MX-021", case(smoke, "a", "PASSED")), listOf(report)).verdict).isEqualTo(MatrixVerdict.VERIFIED)
    // Only gate rows fail the nightly report job.
    assertThat(
      MatrixReport.gateFailures(
        catalog,
        mapOf(
          "T-MX-021" to result("T-MX-021", case(smoke, "a", "FAILED", "boom")),
          "T-MX-005" to result("T-MX-005", case(smoke, "a", "FAILED", "x")),
          "T-MX-022" to result("T-MX-022", case(smoke, "a", "PASSED")),
        ),
      )
    ).containsExactly("T-MX-021: 1 failed: MatrixSmokeTest > a: boom")
  }

  @Test
  @DisplayName("T-BLD-029 patterns follow Gradle test filters: class, class.test name, simple name, '*' wildcards")
  fun patterns() {
    assertThat(MatrixReport.matches("io.argos.agent.testing.jdbc.*", "io.argos.agent.testing.jdbc.JdbcStepsTest", "x")).isTrue()
    assertThat(MatrixReport.matches("*JdbcStepsTest.T-AG-031 postgres *", "io.argos.agent.testing.jdbc.JdbcStepsTest", "T-AG-031 postgres shop-boot3@21: five levels")).isTrue()
    assertThat(MatrixReport.matches("*JdbcStepsTest.T-AG-031 postgres *", "io.argos.agent.testing.jdbc.JdbcStepsTest", "T-AG-031 postgres: not reached")).isFalse()
    assertThat(MatrixReport.matches("MatrixSmokeTest", smoke, "a")).isTrue()
    assertThat(MatrixReport.matches("io.argos.agent.testing.servlet.War*", "io.argos.agent.testing.servlet.StreamingTest", "a")).isFalse()
    assertThat(MatrixReport.matches("a.b(c)", "a.b(c)", "")).isTrue()
  }

  @Test
  @DisplayName("T-BLD-029 MatrixResults: JUnit XML and the result directory -> build/matrix/<ID>.json and back")
  fun collectsResults() {
    val xml = File(tmp, "test-results").apply { mkdirs() }
    File(xml, "TEST-io.argos.agent.testing.matrix.MatrixSmokeTest.xml").writeText(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="io.argos.agent.testing.matrix.MatrixSmokeTest" tests="3" skipped="1" failures="1" errors="0" timestamp="2026-11-26T01:00:00.000Z" hostname="h" time="125.5">
        <testcase name="T-MX-021 smoke sequence" classname="io.argos.agent.testing.matrix.MatrixSmokeTest" time="120.0"/>
        <testcase name="T-AG-054 virtual threads" classname="io.argos.agent.testing.matrix.MatrixSmokeTest" time="0.0">
          <skipped message="org.opentest4j.TestAbortedException: Assumption failed: argos.test.matrix.vt is false" type="org.opentest4j.TestAbortedException">stack</skipped>
        </testcase>
        <testcase name="T-AG-070 no IllegalAccessError" classname="io.argos.agent.testing.matrix.MatrixSmokeTest" time="5.5">
          <failure message="java.lang.AssertionError: 1 IllegalAccessError in the agent log&#10;second line" type="java.lang.AssertionError">stack</failure>
        </testcase>
        <system-out><![CDATA[noise
      [mx-smoke] combo=T-MX-021 startup_ms=4210 transactions=520
      ]]></system-out>
        <system-err><![CDATA[]]></system-err>
      </testsuite>
      """.trimIndent()
    )
    File(xml, "TEST-broken.xml").writeText("<testsuite><testcase")
    val resultDir = File(tmp, "T-MX-021").apply { mkdirs() }
    File(resultDir, "smoke.properties").writeText("transformedClasses=812\nstartup_ms=4300\nother=1\n")
    File(resultDir, "lines.txt").writeText("[mx-baseline] combo=T-MX-021 file=shop-boot3-21.txt\nignored\n")

    val r = MatrixResults.collect("T-MX-021", ":agent:agent-testing:matrixTest-T-MX-021", xml, resultDir, "abc", "Windows 11", listOf("os:linux"))
    assertThat(r.passed).isEqualTo(1)
    assertThat(r.failed).isEqualTo(1)
    assertThat(r.skipped).isEqualTo(1)
    assertThat(r.skipReasons).containsExactly(org.assertj.core.api.Assertions.entry("argos.test.matrix.vt is false", 1))
    assertThat(r.failures).containsExactly("MatrixSmokeTest > T-AG-070 no IllegalAccessError: java.lang.AssertionError: 1 IllegalAccessError in the agent log")
    // Properties of the result directory win over the [mx-...] line.
    assertThat(r.metrics).containsExactly(
      org.assertj.core.api.Assertions.entry("startup_ms", "4300"),
      org.assertj.core.api.Assertions.entry("transformed_classes", "812"),
      org.assertj.core.api.Assertions.entry("transactions", "520"),
    )
    assertThat(r.lines).containsExactly(
      "[mx-smoke] combo=T-MX-021 startup_ms=4210 transactions=520",
      "[mx-baseline] combo=T-MX-021 file=shop-boot3-21.txt",
    )
    assertThat(r.startedAt).isEqualTo("2026-11-26T01:00:00Z")
    assertThat(r.finishedAt).isEqualTo("2026-11-26T01:02:05.500Z")
    assertThat(r.durationSeconds).isEqualTo(125)
    assertThat(r.missingRequirements).containsExactly("os:linux")

    val json = File(tmp, "T-MX-021.json").apply { writeText(r.toJson()) }
    assertThat(MatrixRunResult.fromJson(json)).isEqualTo(r)
    // The newest run of an ID wins; nested artifact folders are searched.
    val older = File(tmp, "artifacts/matrix-boot3/T-MX-021.json").apply { parentFile.mkdirs() }
    older.writeText(r.copy(finishedAt = "2026-11-20T00:00:00Z", passed = 9).toJson())
    assertThat(MatrixReport.readResults(tmp).getValue("T-MX-021").passed).isEqualTo(1)
    assertThat(MatrixReport.readResults(File(tmp, "missing"))).isEmpty()
  }

  @Test
  @DisplayName("T-BLD-029 requirements: env, Gradle property and OS family")
  fun requirements() {
    val env = mapOf("ARGOS_TEST_TIBERO_URL" to "jdbc:tibero:thin:@h:8629:db", "BLANK" to " ")
    val missing =
      MatrixResults.missingRequirements(
        listOf("env:ARGOS_TEST_TIBERO_URL", "env:BLANK", "env:UNSET", "prop:agent.jdk8u242", "prop:agent.jdk8u252", "os:linux", "os:windows"),
        env::get,
        mapOf("agent.jdk8u242" to "/opt/jre8u242"),
        "Windows 11",
      )
    assertThat(missing).containsExactly("env:BLANK", "env:UNSET", "prop:agent.jdk8u252", "os:linux")
    assertThat(MatrixResults.reason("org.opentest4j.TestAbortedException: Assumption failed: x\nmore")).isEqualTo("x")
    assertThat(MatrixResults.reason("y".repeat(400))).hasSize(300).endsWith("...")
  }

  @Test
  @DisplayName("T-BLD-029 community-reports.yaml: format, issue links only, unknown rows rejected; the committed file parses")
  fun communityReports() {
    val reports =
      MatrixReport.parseCommunity(
        """
        version: 1
        reports:
          - id: T-MX-005
            issue: https://github.com/argos-apm/argos/issues/12
            agent-version: 1.0.0
            reported: 2026-12-02
          - combination: WebLogic 14.1 + JDK 11
            issue: https://github.com/argos-apm/argos/issues/15
            agent-version: 1.0.1
            reported: 2026-12-03
            note: reported by a user
        """.trimIndent()
      )
    assertThat(reports.map { it.combination }).containsExactly("T-MX-005", "WebLogic 14.1 + JDK 11")
    fun bad(entry: String) =
      assertThatThrownBy { MatrixReport.parseCommunity("version: 1\nreports:\n  - $entry\n") }.isInstanceOf(MatrixDefinitionException::class.java)
    bad("id: T-MX-005\n    issue: http://example.com/x\n    agent-version: 1.0.0\n    reported: 2026-12-02")
    bad("id: T-MX-005\n    issue: https://github.com/a/b/pull/1\n    agent-version: 1.0.0\n    reported: 2026-12-02")
    bad("issue: https://github.com/a/b/issues/1\n    agent-version: 1.0.0\n    reported: 2026-12-02")
    bad("id: T-MX-005\n    issue: https://github.com/a/b/issues/1\n    agent-version: 1.0.0\n    reported: 12/02/2026")
    bad("id: T-MX-005\n    issue: https://github.com/a/b/issues/1\n    agent-version: 1.0.0\n    reported: 2026-12-02\n    stars: 5")
    assertThatThrownBy {
      MatrixReport.render(catalog, emptyMap(), listOf(CommunityReport("T-MX-999", "T-MX-999", "https://github.com/a/b/issues/1", "1", "2026-12-01", "")))
    }.hasMessageContaining("unknown rows: T-MX-999")
    val committed = File(repositoryRoot, "test/matrix/community-reports.yaml")
    assertThat(MatrixReport.parseCommunity(committed.readText(), committed.name)).isEmpty()
  }

  @Test
  @DisplayName("T-BLD-029 the generated page: legend, summary, one table per group, marks and reasons (golden output)")
  fun rendersGoldenPage() {
    val results =
      mapOf(
        "T-MX-021" to result("T-MX-021", case(smoke, "T-MX-021 smoke", "PASSED")),
        "T-MX-005" to result("T-MX-005", case(smoke, "a", "FAILED", "IllegalAccessError")),
      )
    val community =
      listOf(
        CommunityReport("T-MX-017", "T-MX-017", "https://github.com/argos-apm/argos/issues/7", "1.0.0", "2026-12-01", ""),
        CommunityReport(null, "WebLogic 14.1 + JDK 11", "https://github.com/argos-apm/argos/issues/9", "1.0.0", "2026-12-02", "a | b"),
      )
    val page = MatrixReport.render(catalog, results, community)
    val expected =
      """
      <!-- SPDX-License-Identifier: Apache-2.0 -->
      <!-- Generated by ./gradlew :agent:agent-testing:matrixReport from test/matrix/matrix.yaml. Do not edit by hand. -->

      # Compatibility matrix

      Results of the agent compatibility matrix: every combination of sample application, JDK and
      variant that `test/matrix/matrix.yaml` defines, with the verdict of its latest run. The supported
      versions themselves are listed in [compatibility.md](compatibility.md); how the matrix runs is in
      [test/matrix/README.md](../test/matrix/README.md).

      | Mark | Meaning |
      |---|---|
      | ○ | Verified: the combination ran, no test failed and every required test passed |
      | ✗ | Failed: at least one test failed in the latest run |
      | △ | Not verified: not run, skipped (for example a missing database or JRE), or an unverified combination that runs without being a gate; the reason is given |
      | ◎ | Community verified: not verified by the matrix, but a user reported it working (linked issue) |

      Summary: 5 combinations, ○ 1, ✗ 1, △ 2, ◎ 1.
      Results: 2 combinations run between 2026-11-26 and 2026-11-26 (UTC), commit 0123456, on linux.

      ## Spring Boot 2.7 (javax, embedded Tomcat 9)

      | ID | Application | JDK | Variant | Result | Details | Run |
      |---|---|---|---|---|---|---|
      | T-MX-005 | shop-boot2 | 25 | Boot 2.7 on JDK 25 | ✗ failed | 1 failed (unverified row, not a gate): MatrixSmokeTest > a: IllegalAccessError; startup 4210 ms, 812 transformed classes, 520 transactions | 2026-11-26 0123456 linux |

      ## Spring Boot 3 (jakarta, embedded Tomcat 10.1)

      | ID | Application | JDK | Variant | Result | Details | Run |
      |---|---|---|---|---|---|---|
      | T-MX-021 | shop-boot3 | 21 | default | ○ verified | 1 passed, 0 skipped; startup 4210 ms, 812 transformed classes, 520 transactions | 2026-11-26 0123456 linux |
      | T-MX-022, alias T-MX-017 | shop-boot3 | 21 | virtual threads | ◎ community verified | community report (1); not run; [report](https://github.com/argos-apm/argos/issues/7) (1.0.0, 2026-12-01) | - |

      ## Standalone Tomcat 10.1 and 11 with jakarta WARs

      | ID | Application | JDK | Variant | Result | Details | Run |
      |---|---|---|---|---|---|---|
      | T-MX-094 | shop-war-jakarta | 21 | Tomcat 11 | △ not verified | not run | - |

      ## JDBC drivers and connection pools

      | ID | Application | JDK | Variant | Result | Details | Run |
      |---|---|---|---|---|---|---|
      | T-MX-105 | shop-boot3 | 21 | Tibero \| in-house | △ not verified | not run | - |

      ## Community reports

      | Combination | Agent version | Reported | Issue | Note |
      |---|---|---|---|---|
      | WebLogic 14.1 + JDK 11 | 1.0.0 | 2026-12-02 | [issue](https://github.com/argos-apm/argos/issues/9) | a \| b |

      To report a combination that works for you, open a compatibility report issue
      (`kind/compat-report`); maintainers add confirmed reports to `test/matrix/community-reports.yaml`.

      """.trimIndent()
    // trimIndent keeps the empty last line, so `expected` ends with the page's final line break.
    assertThat(page.replace("\r\n", "\n")).isEqualTo(expected)
    // Same inputs, same page (no clock).
    assertThat(MatrixReport.render(catalog, results, community)).isEqualTo(page)
    assertThat(MatrixReport.render(catalog, emptyMap(), emptyList())).contains("No combination has a result yet.").doesNotContain("○ verified |")
  }
}
