// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * gate1Report (WO-53): the Gate 1 NFR rows take PASS or FAIL only from approved dedicated-machine
 * reports (ADR-0010 C-252 (5)), N-07/N-24 need both C-180 conditions, N-15/N-23/N-25 come from CI
 * results, and the page ends with the person's items.
 */
@Tag("req:DSN-18-01")
@Tag("req:DSN-18-07")
class Gate1ReportTest {
  @TempDir lateinit var dir: File

  private fun benchReport(approved: Boolean) =
    "# Agent overhead\n\nnumbers ...\n\n<!-- argos-bench-summary\n{\"name\": \"2026-12-01-abcdef1.md\", \"verdict\": \"PASS\", \"approved\": $approved}\n-->\n"

  private val fragment =
    """
    # Gate 1, agent overhead: PASS

    | test | requirement | subject | limit | numbers | verdict | evidence | why not decided / notes |
    |---|---|---|---|---|---|---|---|
    | T-NFR-01 | N-01 | agent CPU overhead | relative mean <= 5% | mean 2.1% (limit 5%) (2026-12-01-abcdef1.md) | **PASS** | 2026-12-01-abcdef1.md (main) | - |
    | T-NFR-03 | N-03 | added latency | p99 | p99 +2.0 ms (limit 1 ms) (2026-12-01-abcdef1.md) | **FAIL** | 2026-12-01-abcdef1.md (main) | - |
    | T-NFR-30 | N-30 | agent startup | premain <= 500 ms | - | **INVALID** | - | 2026-12-01-abcdef1.md: not measured |

    ## Reports

    | report | verdict | approved | machine profile | exporter |
    |---|---|---|---|---|
    | 2026-12-01-abcdef1.md | PASS | yes | argos-bench-app | noop (encoding protobuf) |
    """.trimIndent()

  private fun apiBench(enforced: Boolean, approved: Boolean, coldDetail: String = "PASS") =
    """
    # Query API bench (N-07, N-24)

    Verdict: **${if (enforced) "FAIL" else "reference (INVALID)"}**. full data.

    ## Calls

    | Call | Request | Target | Cold ms | Warm ms (median of 3) | Warm runs ms | Status | Wire / JSON bytes | Result | CPU load | Warm <= target | Cold <= 2 x target | Verdict |
    |---|---|---|---|---|---|---|---|---|---|---|---|---|
    | dashboard-1h | `GET /a` | N-07 dashboard 1 h: 1 s | 488.0 | 285.0 | 1, 2, 3 | 200 | 1 / 1 | ok | 4 % | PASS | PASS | PASS |
    | tx-detail | `GET /b` | N-07, N-24 transaction detail / tx id: 0.5 s | 1500.0 | 55.9 | 1, 2, 3 | 200 | 1 / 1 | ok | 4 % | PASS | $coldDetail | ${if (coldDetail == "PASS") "PASS" else "FAIL"} |
    | trace | `GET /c` | N-24 trace id: 0.5 s | 121.1 | 36.7 | 1, 2, 3 | 200 | 1 / 1 | ok | 4 % | PASS | PASS | PASS |

    <!-- argos-api-bench-summary {"approved": $approved} -->
    """.trimIndent()

  private fun rows(files: Map<String, String>, model: TraceabilityModel? = null) =
    Gate1Report.build(Gate1Inputs(files, model)).associateBy { it.def.requirement }

  @Test
  @DisplayName("T-BLD-014h nothing approved: every performance row is 'no evidence (INVALID)' with the reason, the person's items close the page")
  fun noEvidence() {
    val rows =
      rows(mapOf("gate1-agent-2026-12-01-abcdef1.md" to fragment, "2026-12-01-abcdef1.md" to benchReport(approved = false), "server-load.md" to "# Server load\n\n| T-NFR-06 | N-06 | TPS | >= 5,000/s | 1,000/s | **INVALID** | smoke |\n"))
    assertThat(rows.keys).containsExactly("N-01", "N-02", "N-03", "N-04", "N-05", "N-06", "N-07", "N-15", "N-23", "N-24", "N-25", "N-26", "N-27", "N-28", "N-29", "N-30")
    assertThat(rows.getValue("N-01").verdict).isEqualTo(Gate1Report.NO_EVIDENCE)
    assertThat(rows.getValue("N-01").reasons.single()).contains("not approved").contains("C-252 (5)")
    assertThat(rows.getValue("N-06").verdict).isEqualTo(Gate1Report.NO_EVIDENCE)
    assertThat(rows.getValue("N-06").reasons.single()).contains("server-load.md: not approved")
    assertThat(rows.getValue("N-07").reasons.single()).contains("no docs/bench/api-bench-")
    assertThat(rows.getValue("N-05").reasons.single()).contains("chaos")
    assertThat(rows.getValue("N-15").verdict).isEqualTo("NOT RUN")
    val md = Gate1Report.markdown(rows.values.toList(), Gate1Inputs(emptyMap(), null), "2026-11-26T00:00:00Z")
    assertThat(md).contains("| N-01 | T-NFR-01 |").contains("**${Gate1Report.NO_EVIDENCE}**")
    assertThat(md.substringAfter("## Still needed from a person")).contains("C-240 (7)").contains("C-244 (7)").contains("C-245 (1)")
  }

  @Test
  @DisplayName("T-BLD-014h an approved dedicated report makes the fragment's PASS and FAIL count; INVALID rows stay without evidence")
  fun approvedFragment() {
    val rows = rows(mapOf("gate1-agent-2026-12-01-abcdef1.md" to fragment, "2026-12-01-abcdef1.md" to benchReport(approved = true)))
    assertThat(rows.getValue("N-01").verdict).isEqualTo("PASS")
    assertThat(rows.getValue("N-01").numbers).contains("mean 2.1%")
    assertThat(rows.getValue("N-01").evidence).contains("docs/bench/2026-12-01-abcdef1.md", "docs/bench/gate1-agent-2026-12-01-abcdef1.md")
    assertThat(rows.getValue("N-03").verdict).isEqualTo("FAIL")
    assertThat(rows.getValue("N-30").verdict).isEqualTo(Gate1Report.NO_EVIDENCE)
    assertThat(rows.getValue("N-02").reasons.single()).contains("no N-02 row")
    // A fragment that cites a report missing from docs/bench is not taken either.
    assertThat(rows(mapOf("gate1-agent-2026-12-01-abcdef1.md" to fragment)).getValue("N-01").verdict).isEqualTo(Gate1Report.NO_EVIDENCE)
  }

  @Test
  @DisplayName("T-BLD-014h N-07 and N-24 (C-180): an enforced approved run passes only when warm and cold both pass for every call")
  fun apiBenchC180() {
    val pass = rows(mapOf("api-bench-2026-12-02-abcdef1.md" to apiBench(enforced = true, approved = true)))
    assertThat(pass.getValue("N-07").verdict).isEqualTo("PASS")
    assertThat(pass.getValue("N-07").warm).isEqualTo("2/2")
    assertThat(pass.getValue("N-24").numbers).contains("tx-detail").contains("trace")

    val coldFail = rows(mapOf("api-bench-2026-12-02-abcdef1.md" to apiBench(enforced = true, approved = true, coldDetail = "FAIL")))
    assertThat(coldFail.getValue("N-07").verdict).isEqualTo("FAIL")
    assertThat(coldFail.getValue("N-07").warm).isEqualTo("2/2")
    assertThat(coldFail.getValue("N-07").cold).isEqualTo("1/2")
    assertThat(coldFail.getValue("N-24").cold).isEqualTo("1/2")

    val reference = rows(mapOf("api-bench-2026-12-02-abcdef1.md" to apiBench(enforced = false, approved = true)))
    assertThat(reference.getValue("N-07").verdict).isEqualTo(Gate1Report.NO_EVIDENCE)
    assertThat(reference.getValue("N-07").reasons.single()).contains("not an enforced run")
    val unapproved = rows(mapOf("api-bench-2026-12-02-abcdef1.md" to apiBench(enforced = true, approved = false)))
    assertThat(unapproved.getValue("N-24").verdict).isEqualTo(Gate1Report.NO_EVIDENCE)

    val md = Gate1Report.markdown(coldFail.values.toList(), Gate1Inputs(emptyMap(), null), "now")
    assertThat(md).contains("| N-07 | 2 calls: dashboard-1h, tx-detail | 2/2 | 1/2 | **FAIL** |")
  }

  @Test
  // The display name must not name the 18 §16.1 latency test IDs: the traceability scan would then
  // count this build-logic test as evidence for N-15, N-23 and N-25.
  @DisplayName("T-BLD-014h N-15, N-23, N-25 come from the CI results of their 18 §16.1 latency tests")
  fun ciRows() {
    File(dir, "srv/src/integrationTest/java/x/LatencyIT.java").apply { parentFile.mkdirs() }.writeText(
      """
      package x;
      import org.junit.jupiter.api.*;
      class LatencyIT {
        @Test @Tag("req:N-15") @DisplayName("T-SV-020 ingest to WS") void a() {}
        @Test @Tag("req:N-15") @DisplayName("T-SV-020a ingest to query") void b() {}
        @Test @Tag("req:N-23") @DisplayName("T-SV-030 alert delivery") void c() {}
      }
      """.trimIndent()
    )
    File(dir, "srv/build/test-results/integrationTest/TEST-x.LatencyIT.xml").apply { parentFile.mkdirs() }.writeText(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="x.LatencyIT" tests="3" timestamp="2026-11-26T10:00:00">
        <testcase name="T-SV-020 ingest to WS" classname="x.LatencyIT" time="1"/>
        <testcase name="T-SV-020a ingest to query" classname="x.LatencyIT" time="1"/>
        <testcase name="T-SV-030 alert delivery" classname="x.LatencyIT" time="1"><failure message="late">late</failure></testcase>
      </testsuite>
      """.trimIndent()
    )
    val rows = rows(emptyMap(), RepositoryScan.model(dir))
    assertThat(rows.getValue("N-15").verdict).isEqualTo("PASS (CI)")
    assertThat(rows.getValue("N-15").numbers).contains("2 tests").contains("PASSED 2")
    assertThat(rows.getValue("N-23").verdict).isEqualTo("FAIL (CI)")
    assertThat(rows.getValue("N-25").verdict).isEqualTo("MISSING")
    val clean = rows(emptyMap(), RepositoryScan.model(dir, includeResults = false))
    assertThat(clean.getValue("N-15").verdict).isEqualTo("NOT RUN")

    // A passing unit test does not decide the row while the latency IT with the same ID has not run.
    File(dir, "srv/src/test/java/x/PushUnitTest.java").apply { parentFile.mkdirs() }.writeText(
      """
      package x;
      import org.junit.jupiter.api.*;
      class PushUnitTest {
        @Test @Tag("req:N-25") @DisplayName("T-SV-031 push fan-out unit") void a() {}
      }
      """.trimIndent()
    )
    File(dir, "srv/build/test-results/test/TEST-x.PushUnitTest.xml").apply { parentFile.mkdirs() }.writeText(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="x.PushUnitTest" tests="1" timestamp="2026-11-26T10:00:00">
        <testcase name="T-SV-031 push fan-out unit" classname="x.PushUnitTest" time="0.1"/>
      </testsuite>
      """.trimIndent()
    )
    File(dir, "srv/src/integrationTest/java/x/PushScaleIT.java").writeText(
      """
      package x;
      import org.junit.jupiter.api.*;
      class PushScaleIT {
        @Test @Tag("req:N-25") @DisplayName("T-SV-031 push to 50 agents") void a() {}
      }
      """.trimIndent()
    )
    val partly = rows(emptyMap(), RepositoryScan.model(dir))
    val n25 = partly.getValue("N-25")
    assertThat(n25.verdict).isEqualTo("NOT DECIDED (partly run)")
    assertThat(n25.verdict).doesNotContain("PASS")
    assertThat(n25.numbers).contains("PASSED 1").contains("NOT RUN 1")
    assertThat(n25.reasons.joinToString()).contains("PushScaleIT.java").doesNotContain("PushUnitTest.java")
    // N-15 has every test run and stays PASS.
    assertThat(partly.getValue("N-15").verdict).isEqualTo("PASS (CI)")
    val md = Gate1Report.markdown(partly.values.toList(), Gate1Inputs(emptyMap(), null), "now")
    assertThat(md).contains("**NOT DECIDED (partly run)**").doesNotContain("PASS (CI, partly run)")
  }
}
