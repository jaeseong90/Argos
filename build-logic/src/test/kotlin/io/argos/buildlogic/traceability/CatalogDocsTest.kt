// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The catalogue parser of WO-53 (18 §3, appendix A, §16.1; docs/01 and docs/06): ID expressions as
 * the documents write them, the '(분할)' split marker (ADR-0010 C-182), the §16.1 expectations with
 * their non-test evidence, and the requirement tables with stage and verification.
 */
@Tag("req:DSN-18-01")
class CatalogDocsTest {

  private fun fixture(path: String): String =
    checkNotNull(javaClass.getResource("/traceability/check/$path")) { "fixture $path" }.readText()

  @Test
  @DisplayName("T-BLD-014f ID expressions: ranges with padding, lists, suffix ranges, named E2E IDs, variants, families")
  fun idExpressions() {
    assertThat(IdExpressions.expand("T-ST-001~003")).containsExactly("T-ST-001", "T-ST-002", "T-ST-003")
    assertThat(IdExpressions.expand("T-SEC-03~05")).containsExactly("T-SEC-03", "T-SEC-04", "T-SEC-05")
    assertThat(IdExpressions.expand("T-SEC-01·02")).containsExactly("T-SEC-01", "T-SEC-02")
    assertThat(IdExpressions.expand("T-SV-010a~d")).containsExactly("T-SV-010a", "T-SV-010b", "T-SV-010c", "T-SV-010d")
    assertThat(IdExpressions.expand("T-AG-052a~c·p~r"))
      .containsExactly("T-AG-052a", "T-AG-052b", "T-AG-052c", "T-AG-052p", "T-AG-052q", "T-AG-052r")
    assertThat(IdExpressions.expand("T-ACC-23m·23n")).containsExactly("T-ACC-23m", "T-ACC-23n")
    assertThat(IdExpressions.expand("T-SEC-22b·52c·52d")).containsExactly("T-SEC-22b", "T-SEC-52c", "T-SEC-52d")
    assertThat(IdExpressions.expand("T-SEC-3Aa")).containsExactly("T-SEC-3Aa")
    assertThat(IdExpressions.expand("T-NFR-10a~d")).containsExactly("T-NFR-10a", "T-NFR-10b", "T-NFR-10c", "T-NFR-10d")
    assertThat(IdExpressions.expand("T-E2E-stats-1~3")).containsExactly("T-E2E-stats-1", "T-E2E-stats-2", "T-E2E-stats-3")
    assertThat(IdExpressions.expand("T-E2E-service-version-1·2")).containsExactly("T-E2E-service-version-1", "T-E2E-service-version-2")
    assertThat(IdExpressions.expand("T-E2E-active-31a·31b")).containsExactly("T-E2E-active-31a", "T-E2E-active-31b")
    assertThat(IdExpressions.expand("T-E2E-057·058")).containsExactly("T-E2E-057", "T-E2E-058")
    // Variant notes after an ID name the same ID.
    assertThat(IdExpressions.expand("T-NFR-30 (부착 시험)")).containsExactly("T-NFR-30")
    assertThat(IdExpressions.expand("T-SEC-47 (fresh)")).containsExactly("T-SEC-47")
    assertThat(IdExpressions.expand("T-ACC-13 (b) Compose")).containsExactly("T-ACC-13")
    // A comma continues only in catalogue first columns.
    assertThat(IdExpressions.expand("T-NFR-01~02, 26~27", allowComma = true))
      .containsExactly("T-NFR-01", "T-NFR-02", "T-NFR-26", "T-NFR-27")
    assertThat(IdExpressions.expand("T-NFR-01~02, 26~27")).containsExactly("T-NFR-01", "T-NFR-02")
    // Prose: the expression ends where the text goes on; the head may repeat after '~'.
    assertThat(IdExpressions.expand("T-NFR-07 Q1·Q3(30일 데이터), T-ACC-01~03(10장)"))
      .containsExactly("T-NFR-07", "T-ACC-01", "T-ACC-02", "T-ACC-03")
    assertThat(IdExpressions.expand("T-NFR-01~T-NFR-03")).containsExactly("T-NFR-01", "T-NFR-02", "T-NFR-03")
    assertThat(IdExpressions.expand("`T-AG-nnn` pattern, XT-AG-001, T-AG-001x2")).isEmpty()
    assertThat(IdExpressions.families("T-MX 전부, T-ACPT 설치, T-SEC-11")).containsExactly("T-MX", "T-ACPT")
  }

  @Test
  @DisplayName("T-BLD-014f requirement IDs: D, N and SEC lists and ranges, N-10a~d, others kept")
  fun requirementIds() {
    assertThat(RequirementIds.expand("D-01~D-03")).containsExactly("D-01", "D-02", "D-03")
    assertThat(RequirementIds.expand("D-03·12·13")).containsExactly("D-03", "D-12", "D-13")
    assertThat(RequirementIds.expand("N-01~04, N-26")).containsExactly("N-01", "N-02", "N-03", "N-04", "N-26")
    assertThat(RequirementIds.expand("N-10a~d")).containsExactly("N-10a", "N-10b", "N-10c", "N-10d")
    assertThat(RequirementIds.expand("SEC-70·71, SEC-3A")).containsExactly("SEC-70", "SEC-71", "SEC-3A")
    assertThat(CatalogDocs.requirementTokens("F-A01·A02, DSN-18-01, O-L04, D-24"))
      .containsExactly("D-24", "F-A01", "DSN-18-01", "O-L04")
  }

  @Test
  @DisplayName("T-BLD-014f catalogue: first-column IDs of every table, requirement column, '(분할)' rows, the §3 patterns are no IDs")
  fun catalogue() {
    val catalogue = CatalogDocs.catalogue(fixture("docs/18-test-design.md"))
    assertThat(catalogue.ids)
      .contains("T-ACC-01", "T-ACC-02", "T-SEC-01", "T-SEC-02", "T-SEC-11", "T-SEC-12", "T-SEC-13", "T-SEC-12a")
      .contains("T-AG-031a", "T-AG-031b", "T-AG-031c", "T-NFR-01", "T-NFR-02", "T-NFR-30", "T-NFR-10d", "T-E2E-stats-3")
      .doesNotContain("T-AG-031", "T-NFR-03", "T-AG-nnn")
    // §16.1 is a table too, but its first column holds requirements.
    assertThat(catalogue.ids.filter { it.startsWith("D-") || it.startsWith("N-") }).isEmpty()
    val split = catalogue.rowsOf("T-AG-031b").single()
    assertThat(split.split).isTrue()
    assertThat(split.requirements).containsExactly("D-24")
    assertThat(split.appendix).isTrue()
    assertThat(catalogue.splits).containsOnlyKeys("T-AG-031")
    assertThat(catalogue.splits["T-AG-031"]).containsExactly("T-AG-031a", "T-AG-031b", "T-AG-031c")
    // A '(분할)'-looking description outside appendix A is no split marker; neither is a later "(분할)".
    val body = CatalogDocs.catalogue("## 12. 보안\n\n| ID | 내용 |\n|---|---|\n| T-SEC-24a~b | (분할) body |\n\n## 부록 A. 카탈로그\n\n| ID | 내용 | 요구 |\n|---|---|---|\n| T-SV-010a·010b | ack (분할) | D-13 |\n")
    assertThat(body.splits).isEmpty()
    assertThat(body.rowsOf("T-SV-010b").single().requirements).containsExactly("D-13")
    assertThat(catalogue.rowsOf("T-NFR-30").single().requirements).containsExactly("N-01", "N-02", "N-30")
  }

  @Test
  @DisplayName("T-BLD-014f 18 §16.1: one-to-one ranges, expected IDs, families, design and human evidence")
  fun mapping() {
    val rows = CatalogDocs.mapping(fixture("docs/18-test-design.md")).associateBy { it.requirement }
    assertThat(rows.keys).containsExactlyInAnyOrder("D-01", "D-02", "N-01", "N-02", "N-09", "N-10a", "N-10b", "N-11", "N-13")
    assertThat(rows.getValue("D-01").expectedIds).containsExactly("T-ACC-01")
    assertThat(rows.getValue("D-02").expectedIds).containsExactly("T-ACC-02")
    assertThat(rows.getValue("N-02").expectedIds).containsExactly("T-NFR-02", "T-SOAK-001")
    assertThat(rows.getValue("N-09").nonTest).isEqualTo(NonTestKind.DESIGN)
    assertThat(rows.getValue("N-10a").nonTest).isEqualTo(NonTestKind.HUMAN)
    assertThat(rows.getValue("N-10a").families).isEmpty()
    assertThat(rows.getValue("N-11").nonTest).isEqualTo(NonTestKind.HUMAN)
    assertThat(rows.getValue("N-10b").expectedIds).containsExactly("T-SEC-11", "T-SEC-12", "T-SEC-13")
    assertThat(rows.getValue("N-13").families).containsExactly("T-MX")
    assertThat(rows.getValue("N-13").nonTest).isNull()
  }

  @Test
  @DisplayName("T-BLD-014f docs/01 D and N rows (N-10a~d), docs/06 stage M and verification even with a pipe in a code span")
  fun requirements() {
    val plan = CatalogDocs.planRequirements(fixture("docs/01-product-plan.md")).map { it.id }
    assertThat(plan).containsExactly("D-01", "D-02", "N-01", "N-02", "N-09", "N-10a", "N-10b", "N-11", "N-13")
    val sec = CatalogDocs.securityRequirements(fixture("docs/06-security-requirements.md")).associateBy { it.id }
    assertThat(sec.filterValues { it.stageM }.keys).containsExactlyInAnyOrder("SEC-01", "SEC-11", "SEC-30", "SEC-91")
    assertThat(sec.getValue("SEC-01").automatic).isTrue()
    assertThat(sec.getValue("SEC-30").automatic).isFalse()
    assertThat(sec.getValue("SEC-30").verificationKind).isEqualTo("수동")
    assertThat(sec.getValue("SEC-34").stage).isEqualTo("G2")
    assertThat(sec.getValue("SEC-34").automatic).isTrue()
    assertThat(sec.getValue("SEC-91").stage).isEqualTo("M(초안)·G2")
  }

  @Test
  @DisplayName("T-BLD-014f evidence registry: kinds, expressions expanded, errors name the line")
  fun evidenceRegistry() {
    val registry = EvidenceRegistry.parse(fixture("test/traceability/evidence.yaml"))
    assertThat(registry.ids).containsExactly("T-NFR-01", "T-NFR-02", "T-SOAK-001")
    assertThat(registry.forRequirement("N-09").single().kind).isEqualTo(EvidenceKind.DESIGN)
    assertThat(registry.forRequirement("N-02").map { it.kind }).containsExactly(EvidenceKind.MEASUREMENT, EvidenceKind.DEFERRED)
    assertThatThrownBy { EvidenceRegistry.parse("format: 1\nentries:\n  - requirements: [N-09]\n    kind: guess\n    source: x\n    owner: PM\n    gate: Gate 1\n") }
      .isInstanceOf(EvidenceException::class.java)
      .hasMessageContaining(":3:")
      .hasMessageContaining("guess")
    assertThatThrownBy { EvidenceRegistry.parse("format: 1\nentries:\n  - ids: [N-09]\n    kind: human\n    source: x\n    owner: PM\n    gate: Gate 1\n") }
      .hasMessageContaining("not a test ID expression")
    assertThatThrownBy { EvidenceRegistry.parse("entries: []\n") }.hasMessageContaining("format: 1")
    assertThatThrownBy { EvidenceRegistry.parse("format: 1\nentries:\n  - requirements: [N-09]\n    kind: human\n    owner: PM\n    gate: Gate 1\n") }
      .hasMessageContaining("'source' is required")
  }
}
