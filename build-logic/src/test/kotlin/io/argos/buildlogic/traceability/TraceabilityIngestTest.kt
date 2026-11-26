// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import groovy.json.JsonSlurper
import io.argos.buildlogic.testing.MatrixCatalog
import io.argos.buildlogic.testing.MatrixRunResult
import io.argos.buildlogic.traceability.JUnitXmlResults.Status
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Result ingestion of the traceability report (WO-53, 18 §3, §16, DSN-18-01; ADR-0010 C-200 (1),
 * C-250 (6)): the test ID grammar with named E2E IDs, Playwright JSON with per-test requirement
 * annotations, Vitest JUnit XML, shell sources and TAP results, compatibility matrix rows and results.
 * Fixtures: build-logic/src/test/resources/traceability/ingest (the Playwright one trimmed from a real
 * ui/test-results/e2e/results.json).
 */
@Tag("req:DSN-18-01")
class TraceabilityIngestTest {
  @TempDir lateinit var dir: File

  private fun fixture(path: String): File =
    File(requireNotNull(javaClass.getResource("/traceability/ingest/$path")) { "fixture $path" }.toURI())

  private fun copy(fixturePath: String, to: String): File =
    File(dir, to).apply {
      parentFile.mkdirs()
      writeBytes(fixture(fixturePath).readBytes())
    }

  private fun put(path: String, text: String): File = File(dir, path).apply { parentFile.mkdirs() }.apply { writeText(text) }

  @Test
  @DisplayName(
    "T-BLD-014a test ID grammar: named E2E IDs with hyphenated areas and an optional letter, parenthesized variants are " +
      "the same ID, the existing grammar and the catalogue-ID rules are kept"
  )
  fun grammar() {
    assertThat(TestIds.find("T-E2E-stats-1 list; T-E2E-service-version-2 p95, T-E2E-admin-20a / T-E2E-040 and T-E2E-common-7"))
      .containsExactly("T-E2E-stats-1", "T-E2E-service-version-2", "T-E2E-admin-20a", "T-E2E-040", "T-E2E-common-7")
    assertThat(TestIds.find("T-E2E-stats T-E2E-Stats-1 T-E2E-stats- XT-E2E-stats-1 T-E2E-stats-1ab T-E2E--1")).isEmpty()
    assertThat(TestIds.find("T-SEC-47 (fresh) without a token")).containsExactly("T-SEC-47")
    assertThat(TestIds.find("T-ACC-13 (b) Compose, T-SEC-47(fresh)")).containsExactly("T-ACC-13", "T-SEC-47")
    assertThat(TestIds.catalogId("T-E2E-stats-1a")).isEqualTo("T-E2E-stats-1")
    assertThat(TestIds.catalogId("T-E2E-service-version-2")).isEqualTo("T-E2E-service-version-2")
    assertThat(TestIds.isPartial("T-E2E-admin-20a")).isTrue()
    assertThat(TestIds.isPartial("T-E2E-stats-1")).isFalse()
    // Unchanged: [0-9]+[A-Z]?[a-z]?, N-10a..d catalogue IDs, partial suffixes.
    assertThat(TestIds.find("T-SEC-3Aa T-NFR-10a T-AG-031a T-SEC-3aA")).containsExactly("T-SEC-3Aa", "T-NFR-10a", "T-AG-031a")
    assertThat(TestIds.catalogId("T-NFR-10a")).isEqualTo("T-NFR-10a")
    assertThat(TestIds.catalogId("T-SEC-3Aa")).isEqualTo("T-SEC-3A")
    // Script titles with named IDs are tests.
    val scan = ScriptTestScanner.scan("ui/e2e/specs/stats.spec.ts", "test('T-E2E-stats-1 values', async () => {});", "ui", "playwright")
    assertThat(scan.tests.single().testIds).containsExactly("T-E2E-stats-1")
  }

  private val wizardSpec =
    """
    const REQ = { annotation: [{ type: 'req', description: 'F-U99' }] };
    test.describe.serial('setup wizard', () => {
      test('T-SEC-47 (fresh) without a token, with a wrong one or a too short password the setup is refused (token message, 403/422) and nothing is created', REQ, async () => {});
      test('T-E2E-059 T-SEC-47 (fresh) the real wizard: token, administrator (12 characters), IP mode chosen, first application with its agent token once (no-store), finish', REQ, async () => {});
      test('T-SEC-47 (fresh) after the setup /setup shows the closed view', REQ, async () => {});
    });
    """
      .trimIndent()

  private val statsSpec =
    """
    test.describe('T-E2E-stats statistics screens', () => {
      test('T-E2E-stats-1 list values equal the API', async () => {});
      test(`T-E2E-stats-2 CSV ${'$'}{'export'}`, async () => {});
      test.fixme('T-E2E-stats-3 version markers', async () => {});
    });
    """
      .trimIndent()

  @Test
  @DisplayName(
    "T-BLD-014b Playwright JSON: cases per test and project, req annotations replace the per-file association, " +
      "fixme and timedOut, result-only engine tests T-E2E-<screen>-<n>, unreadable JSON"
  )
  fun playwright() {
    val fresh = PlaywrightResults.read(fixture("playwright/results-fresh-install.json"), "ui/test-results/e2e/results-fresh-install.json")!!
    assertThat(fresh.map { it.file })
      .containsExactly(
        "ui/e2e/setup/fresh.setup.ts",
        "ui/e2e/setup/fresh.teardown.ts",
        "ui/e2e/specs/fresh/setup-wizard.spec.ts",
        "ui/e2e/specs/fresh/setup-wizard.spec.ts",
      )
    with(fresh[1]) {
      assertThat(testIds).containsExactly("T-SEC-46")
      assertThat(requirements).containsExactly("SEC-46")
      assertThat(suite).isEqualTo("playwright:fresh-teardown")
      assertThat(status).isEqualTo(Status.PASSED)
      assertThat(timestamp).isEqualTo("2026-11-26T01:33:29.112Z")
    }
    val stack = PlaywrightResults.read(fixture("playwright/results-stack-shard-1-of-2.json"), "ui/test-results/e2e/results-stack-shard-1-of-2.json")!!
    val byName = stack.associateBy { it.name }
    with(byName.getValue("T-E2E-stats statistics screens > T-E2E-stats-1 list values equal the API")) {
      assertThat(status).isEqualTo(Status.PASSED)
      assertThat(testIds).containsExactly("T-E2E-stats-1")
    }
    with(byName.getValue("T-E2E-stats statistics screens > T-E2E-stats-2 CSV export")) {
      assertThat(status).isEqualTo(Status.FAILED)
      assertThat(reason).isEqualTo("timedOut (expected passed)")
      assertThat(requirements).containsExactly("F-U07", "F-U08")
    }
    with(byName.getValue("T-E2E-stats statistics screens > T-E2E-stats-3 version markers")) {
      assertThat(status).isEqualTo(Status.SKIPPED)
      assertThat(reason).isEqualTo("fixme: needs the deployment marker seed (C-201)")
    }
    assertThat(byName.getValue("Active transactions (docs/05 §2.6) > expected failure that passed unexpectedly").status)
      .isEqualTo(Status.FAILED)

    val tests =
      ScriptTestScanner.scan("ui/e2e/specs/fresh/setup-wizard.spec.ts", wizardSpec, "ui", "playwright").tests +
        ScriptTestScanner.scan("ui/e2e/specs/stats.spec.ts", statsSpec, "ui", "playwright").tests
    val model = Traceability.build(emptyList(), tests, emptyList(), 2, 2, scriptResults = fresh + stack)
    val entries = model.entries.associateBy { it.name }
    with(entries.getValue(wizardSpec.lines()[2].substringAfter("test('").substringBefore("', REQ"))) {
      assertThat(requirements).containsExactly("SEC-47", "SEC-48", "SEC-31")
      assertThat(result!!.status).isEqualTo(Status.PASSED)
      assertThat(result!!.suites).containsExactly("playwright:fresh-install")
      assertThat(source).isEqualTo("playwright")
    }
    with(entries.getValue("T-SEC-47 (fresh) after the setup /setup shows the closed view")) {
      assertThat(result).isNull()
      assertThat(requirements).containsExactly("F-U99")
    }
    with(entries.getValue("T-E2E-stats-2 CSV \${'export'}")) {
      assertThat(result!!.status).isEqualTo(Status.FAILED)
      assertThat(requirements).containsExactly("F-U07", "F-U08")
    }
    assertThat(entries.getValue("T-E2E-stats-3 version markers").result!!.reasons).containsExactly("fixme: needs the deployment marker seed (C-201)")
    with(entries.getValue("Active transactions (docs/05 §2.6) > T-E2E-active-1 2.6-1 per-instance bars equal the last real push and REST")) {
      assertThat(testIds).containsExactly("T-E2E-active-1")
      assertThat(requirements).containsExactly("F-U04")
      assertThat(file).isEqualTo("ui/e2e/specs/required.spec.ts")
      assertThat(suite).isEqualTo("playwright:stack")
      assertThat(result!!.status).isEqualTo(Status.PASSED)
    }
    assertThat(entries.getValue(fresh[1].name).requirements).containsExactly("SEC-46")
    // The setup project's test has neither ID nor requirement: counted, not listed.
    assertThat(model.entries.map { it.name }).noneMatch { it.startsWith("start the fresh-install stack") }
    assertThat(model.sources.single { it.source == "playwright" }.unlistedResultCases).isEqualTo(1)
    assertThat(model.byRequirement.getValue("SEC-47").map { it.testIds })
      .contains(listOf("T-SEC-47"), listOf("T-E2E-059", "T-SEC-47"))

    val broken = File(dir, "ui/test-results/e2e/results.json").apply { parentFile.mkdirs() }.apply { writeText("{ not json") }
    assertThat(PlaywrightResults.read(broken)).isNull()
    assertThat(PlaywrightResults.isResultsFile("ui/test-results/e2e/results-stack-shard-2-of-4.json")).isTrue()
    assertThat(PlaywrightResults.isResultsFile("ui/test-results/e2e/results.json")).isTrue()
    assertThat(PlaywrightResults.isResultsFile("ui/test-results/e2e/x/trace.json")).isFalse()
  }

  @Test
  @DisplayName(
    "T-BLD-014c Vitest JUnit XML: cases match script tests by file and title (nested describe segments, the same title " +
      "in two files, template titles); a describe title takes its children"
  )
  fun vitest() {
    val coords =
      """
      describe('T-UI-001 coordinate transforms', () => {
        it('T-UI-021 maps x', () => {});
        describe('nested', () => { it('T-UI-022 maps y', () => {}); });
        it('clamps without an id', () => {});
      });
      """
        .trimIndent()
    val table =
      """
      const REQ = { type: 'req', description: 'F-U07' };
      describe('table', () => {
        it('T-UI-021 maps x', () => {});
        it('renders the empty state', () => {});
      });
      it(`T-UI-040 sorts by p95 for user ${'$'}{id}`, () => {});
      """
        .trimIndent()
    val tests =
      ScriptTestScanner.scan("ui/src/xlog/coords.test.ts", coords, "ui", "vitest").tests +
        ScriptTestScanner.scan("ui/src/stats/table.test.tsx", table, "ui", "vitest").tests
    val xml = copy("vitest/junit.xml", "ui/build/test-results/vitest/junit.xml")
    val results = JUnitXmlResults.read(xml).map { "ui" to it }
    assertThat(results).hasSize(6)
    val model = Traceability.build(emptyList(), tests, results, sourceFiles = 2, resultFiles = 1)
    fun entry(file: String, name: String) = model.entries.single { it.file == file && it.name == name }
    with(entry("ui/src/xlog/coords.test.ts", "T-UI-021 maps x").result!!) {
      assertThat(status).isEqualTo(Status.PASSED)
      assertThat(runs).isEqualTo(1)
      assertThat(suites).containsExactly("vitest")
    }
    assertThat(entry("ui/src/stats/table.test.tsx", "T-UI-021 maps x").result!!.runs).isEqualTo(1)
    assertThat(entry("ui/src/xlog/coords.test.ts", "T-UI-022 maps y").result!!.status).isEqualTo(Status.FAILED)
    with(entry("ui/src/xlog/coords.test.ts", "T-UI-001 coordinate transforms").result!!) {
      assertThat(status).isEqualTo(Status.FAILED)
      assertThat(runs).isEqualTo(3)
    }
    with(entry("ui/src/stats/table.test.tsx", "T-UI-040 sorts by p95 for user \${id}")) {
      assertThat(result!!.status).isEqualTo(Status.PASSED)
      assertThat(requirements).containsExactly("F-U07")
      assertThat(source).isEqualTo("vitest")
    }
    // No JUnit entry for the Vitest cases; the skipped case without an ID is counted only.
    assertThat(model.entries).allMatch { it.source == "vitest" }
    val vitest = model.sources.single { it.source == "vitest" }
    assertThat(vitest.resultCases).isEqualTo(6)
    assertThat(vitest.unlistedResultCases).isEqualTo(1)
    assertThat(model.sources.single { it.source == "junit" }.resultCases).isEqualTo(0)
  }

  @Test
  @DisplayName(
    "T-BLD-014d shell: IDs of quoted assertion descriptions and the # test-ids / # req headers (comments, here-documents " +
      "and failure messages ignored); TAP results with SKIP, failure details, container cases and an exit-code-only failure"
  )
  fun shell() {
    val text = fixture("shell/backup.test.sh.txt").readText()
    val scan = ShellTestScanner.scan("deploy/compose/test/backup.test.sh", text)
    assertThat(scan.headerTestIds).containsExactly("T-SEC-84a", "T-NFR-20c", "T-SEC-52e")
    assertThat(scan.headerRequirements).containsExactly("SEC-84", "N-20")
    assertThat(scan.tests.map { it.displayName })
      .containsExactly(
        "T-SEC-84a: backup.sh exits 0 (caller umask 022)",
        "T-SEC-84a: the set directory \$set_dir is 0700",
        "T-NFR-20c: the ClickHouse target on the same disk is refused",
        "backup.test.sh (header test-ids)",
      )
    assertThat(scan.tests.map { it.line }).containsExactly(14, 15, 17, 6)
    assertThat(scan.tests.flatMap { it.testIds }).doesNotContain("T-SEC-99", "T-NFR-77", "T-SEC-98", "T-SEC-97")
    assertThat(scan.tests.last().testIds).containsExactly("T-SEC-52e")
    assertThat(scan.tests.last().matchAll).isTrue()
    assertThat(scan.tests).allMatch { it.requirements == listOf("SEC-84", "N-20") && it.sourceSet == "shell" }
    val cases = ShellTestScanner.scan("deploy/compose/test/container/backup-cases.sh", fixture("shell/backup-cases.sh.txt").readText())
    assertThat(cases.tests.map { it.testIds.single() }).containsExactly("T-SEC-84b", "T-SEC-84c")

    assertThat(ShellTestScanner.isShellTest("deploy/compose/test/backup.test.sh")).isTrue()
    assertThat(ShellTestScanner.isShellTest("deploy/compose/test/container/backup-cases.sh")).isTrue()
    assertThat(ShellTestScanner.isShellTest("deploy/compose/test/compose-smoke.sh")).isTrue()
    assertThat(ShellTestScanner.isShellTest("deploy/test/release-signing-rehearsal.sh")).isTrue()
    assertThat(ShellTestScanner.isShellTest("deploy/compose/test/lib.sh")).isFalse()
    assertThat(ShellTestScanner.isShellTest("build-logic/src/test/resources/x/a.test.sh")).isFalse()

    val tap = ShellResults.read(fixture("shell/backup.test.sh.tap"), "build/test-results/shell/deploy-compose/backup.test.sh.tap")!!
    assertThat(tap.map { it.status })
      .containsExactly(Status.PASSED, Status.PASSED, Status.FAILED, Status.PASSED, Status.SKIPPED, Status.PASSED, Status.PASSED)
    assertThat(tap).allMatch { it.suite == "shell:deploy-compose" && it.file == "deploy/compose/test/backup.test.sh" }
    assertThat(tap[4].reason).isEqualTo("gpg missing on this image")
    assertThat(tap[4].name).isEqualTo("T-SEC-84c: no plaintext copy next to the .gpg file")
    assertThat(tap[0].timestamp).isEqualTo("2026-11-26T10:00:00Z")
    val exitOnly = ShellResults.read(fixture("shell/release-notes.test.sh.tap"), "build/test-results/shell/release-scripts/release-notes.test.sh.tap")!!
    assertThat(exitOnly.map { it.name to it.status }).containsExactly("release-notes.test.sh exited 2" to Status.FAILED)
    assertThat(ShellResults.read(put("x.tap", "ok 1 - plain TAP without the marker\n"))).isNull()

    val notes = ShellTestScanner.scan("scripts/test/release-notes.test.sh", "#!/usr/bin/env bash\n# req: O-R01\nexpect_exit 0 \"renders the notes\" x\n")
    val model =
      Traceability.build(emptyList(), scan.tests + cases.tests + notes.tests, emptyList(), 3, 2, scriptResults = tap + exitOnly)
    fun entry(name: String) = model.entries.single { it.name == name }
    assertThat(entry("T-SEC-84a: backup.sh exits 0 (caller umask 022)").result!!.status).isEqualTo(Status.PASSED)
    assertThat(entry("T-SEC-84a: the set directory \$set_dir is 0700").result!!.status).isEqualTo(Status.PASSED)
    assertThat(entry("T-NFR-20c: the ClickHouse target on the same disk is refused").result!!.status).isEqualTo(Status.FAILED)
    with(entry("T-SEC-84b: retention keeps the ClickHouse chain of \$kept sets")) {
      assertThat(file).isEqualTo("deploy/compose/test/container/backup-cases.sh")
      assertThat(result!!.status).isEqualTo(Status.PASSED)
      assertThat(result!!.suites).containsExactly("shell:deploy-compose")
    }
    with(entry("T-SEC-84c: no plaintext copy next to the .gpg file").result!!) {
      assertThat(status).isEqualTo(Status.SKIPPED)
      assertThat(reasons).containsExactly("gpg missing on this image")
    }
    with(entry("backup.test.sh (header test-ids)")) {
      assertThat(testIds).containsExactly("T-SEC-52e")
      assertThat(result!!.status).isEqualTo(Status.PASSED)
      assertThat(result!!.runs).isEqualTo(1)
    }
    with(entry("T-SEC-84z: a description no template produced")) {
      assertThat(testIds).containsExactly("T-SEC-84z")
      assertThat(requirements).containsExactly("SEC-84", "N-20")
      assertThat(source).isEqualTo("shell")
    }
    with(entry("release-notes.test.sh (header test-ids)")) {
      assertThat(requirements).containsExactly("O-R01")
      assertThat(result!!.status).isEqualTo(Status.FAILED)
    }
    // Without the script's source the exit-code-only failure is still listed.
    val unscanned = Traceability.build(emptyList(), emptyList(), emptyList(), 0, 1, scriptResults = exitOnly)
    assertThat(unscanned.entries.single().result!!.status).isEqualTo(Status.FAILED)
    assertThat(ShellTestScanner.matches("T-X-1 \$a and \${b} via \$(c \"d\")", "T-X-1 1 and 2 via 3 (exit 1, expected 0)")).isTrue()
    assertThat(ShellTestScanner.matches("T-SEC-84a: backup.sh exits 0", "T-SEC-84a: backup.sh exits 0 twice")).isFalse()
  }

  @Test
  @DisplayName(
    "T-BLD-014e matrix: rows of matrix.yaml are tests with N-13; results failed > 0 FAILED, passed > 0 PASSED, otherwise " +
      "SKIPPED with the missing runner requirements; aliases resolve; a row without a result is NOT RUN"
  )
  fun matrix() {
    val catalog = MatrixCatalog.parse(fixture("matrix/matrix.yaml").readText(), "matrix.yaml")
    val sources = MatrixEvidence.sources(catalog)
    assertThat(sources.map { it.displayName })
      .containsExactly("T-MX-001 shop-boot2 JDK 8", "T-MX-021 shop-boot3 JDK 21", "T-MX-022 shop-boot3 JDK 21 VT", "T-MX-023 shop-boot3 JDK 25 (unverified)")
    assertThat(sources).allMatch { it.requirements == listOf("N-13") && it.file == "test/matrix/matrix.yaml" && it.sourceSet == "matrix" }
    val results =
      listOf("T-MX-001", "T-MX-021", "T-MX-017").map {
        MatrixEvidence.result(MatrixRunResult.fromJson(fixture("matrix/$it.json")), catalog, "build/matrix/$it.json")
      }
    assertThat(results.map { it.testIds.single() to it.status })
      .containsExactly("T-MX-001" to Status.PASSED, "T-MX-021" to Status.FAILED, "T-MX-022" to Status.SKIPPED)
    assertThat(results[2].reason).isEqualTo("missing runner requirements: env:ARGOS_MATRIX_DOCKER")
    assertThat(results[2].timestamp).isEqualTo("2026-11-26T08:00:00.000Z")
    val model = Traceability.build(emptyList(), sources, emptyList(), 1, 3, scriptResults = results)
    val byId = model.entries.associateBy { it.testIds.single() }
    assertThat(byId.getValue("T-MX-001").result!!.status).isEqualTo(Status.PASSED)
    assertThat(byId.getValue("T-MX-021").result!!.reasons).containsExactly("io.argos.agent.testing.matrix.MatrixSmokeTest > smoke(): expected 500 transactions")
    assertThat(byId.getValue("T-MX-022").result!!.status).isEqualTo(Status.SKIPPED)
    assertThat(byId.getValue("T-MX-023").result).isNull()
    assertThat(model.byRequirement.getValue("N-13")).hasSize(4)
    assertThat(model.entries).allMatch { it.source == "matrix" }
    // A result of a row the catalogue does not know is still listed under its own ID.
    val unknown = MatrixEvidence.result(MatrixRunResult.fromJson(fixture("matrix/T-MX-001.json")), null, "build/matrix/T-MX-001.json")
    assertThat(unknown.file).isEqualTo("build/matrix/T-MX-001.json")
    assertThat(MatrixEvidence.isResultFile("build/matrix/T-MX-001.json")).isTrue()
    assertThat(MatrixEvidence.isResultFile("build/matrix/T-MX-001/junit.xml")).isFalse()
    assertThat(MatrixEvidence.isResultFile("agent/build/matrix/T-MX-001.json")).isFalse()
  }

  @Test
  @DisplayName(
    "T-BLD-014 repository scan with every source: per-source counts, source field in the JSON, unreadable result files; " +
      "without results (a clean checkout) every test is NOT RUN and none FAILED"
  )
  fun repository() {
    put("a/src/test/java/p/ATest.java", "package p;\nclass ATest {\n  @Test @Tag(\"req:D-01\") @DisplayName(\"T-ACC-01 counts\") void counts() {}\n}\n")
    put(
      "a/build/test-results/test/TEST-p.ATest.xml",
      "<testsuite timestamp=\"2026-11-26T00:00:00Z\"><testcase classname=\"p.ATest\" name=\"T-ACC-01 counts\" time=\"0.1\"/></testsuite>",
    )
    put(
      "ui/src/xlog/coords.test.ts",
      "const R = { type: 'req', description: 'F-U01' };\ndescribe('T-UI-001 coordinate transforms', () => { it('T-UI-021 maps x', () => {}); });\n",
    )
    copy("vitest/junit.xml", "ui/build/test-results/vitest/junit.xml")
    put("ui/e2e/specs/fresh/setup-wizard.spec.ts", wizardSpec)
    copy("playwright/results-fresh-install.json", "ui/test-results/e2e/results-fresh-install.json")
    copy("playwright/results-stack-shard-1-of-2.json", "ui/test-results/e2e/results.json")
    put("ui/test-results/e2e/fresh.setup.ts-start/trace.json", "{}")
    copy("shell/backup.test.sh.txt", "deploy/compose/test/backup.test.sh")
    copy("shell/backup-cases.sh.txt", "deploy/compose/test/container/backup-cases.sh")
    put("deploy/compose/test/lib.sh", "check() { echo \"PASS  T-SEC-01 \$1\"; }\n")
    copy("shell/backup.test.sh.tap", "build/test-results/shell/deploy-compose/backup.test.sh.tap")
    copy("shell/release-notes.test.sh.tap", "build/test-results/shell/release-scripts/release-notes.test.sh.tap")
    put("build/test-results/shell/broken/x.tap", "hello\n")
    copy("matrix/matrix.yaml", "test/matrix/matrix.yaml")
    for (id in listOf("T-MX-001", "T-MX-021", "T-MX-017")) copy("matrix/$id.json", "build/matrix/$id.json")
    put("build/matrix/T-MX-001/TEST-x.xml", "<testsuite/>")

    val model = RepositoryScan.model(dir)
    val stats = model.sources.associateBy { it.source }
    assertThat(model.sources.map { it.source }).containsExactly("junit", "vitest", "playwright", "shell", "matrix")
    assertThat(stats.getValue("junit").let { it.sourceFiles to it.resultFiles }).isEqualTo(1 to 1)
    assertThat(stats.getValue("vitest").let { it.sourceFiles to it.resultFiles }).isEqualTo(1 to 1)
    assertThat(stats.getValue("playwright").let { it.sourceFiles to it.resultFiles }).isEqualTo(1 to 2)
    assertThat(stats.getValue("shell").let { it.sourceFiles to it.resultFiles }).isEqualTo(2 to 3)
    assertThat(stats.getValue("matrix").let { it.sourceFiles to it.resultFiles }).isEqualTo(1 to 3)
    assertThat(model.sourceFiles).isEqualTo(6)
    assertThat(model.resultFiles).isEqualTo(10)
    assertThat(model.unreadableResultFiles).containsExactly("build/test-results/shell/broken/x.tap")
    assertThat(model.statusCounts("matrix")).containsEntry("PASSED", 1).containsEntry("FAILED", 1).containsEntry("SKIPPED", 1).containsEntry("NOT RUN", 1)
    assertThat(model.statusCounts("junit")).containsEntry("PASSED", 1)
    assertThat(model.byRequirement.keys).contains("D-01", "SEC-47", "SEC-84", "N-13", "F-U04")

    val md = TraceabilityRenderer.markdown(model, "now")
    assertThat(md)
      .contains("## Sources")
      .contains("| Shell suites (TAP) | 2 | 3 |")
      .contains("| Compatibility matrix | 1 | 3 | 3 | 0 | 1 | 1 | 1 | 1 |")
      .contains("SKIPPED (2026-11-26T08:00:00.000Z) [missing runner requirements: env:ARGOS_MATRIX_DOCKER]")
      .contains("from 6 test source files and 10 result files")
      .doesNotContain("Skeleton")
    @Suppress("UNCHECKED_CAST")
    val json = JsonSlurper().parseText(TraceabilityRenderer.json(model, "now")) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val tests = (json["requirements"] as List<Map<String, Any?>>).flatMap { it["tests"] as List<Map<String, Any?>> }
    assertThat(tests.map { it["source"] }.toSet()).containsExactlyInAnyOrder("junit", "vitest", "playwright", "shell", "matrix")
    @Suppress("UNCHECKED_CAST")
    assertThat((json["sources"] as List<Map<String, Any?>>).map { it["source"] }).containsExactly("junit", "vitest", "playwright", "shell", "matrix")

    val clean = RepositoryScan.model(dir, includeResults = false)
    assertThat(clean.resultFiles).isEqualTo(0)
    assertThat(clean.entries).isNotEmpty().allMatch { it.result == null }
    assertThat(clean.statusCounts()).containsEntry("FAILED", 0).containsEntry("NOT RUN", clean.entries.size)
    assertThat(clean.unreadableResultFiles).isEmpty()
    assertThat(clean.sourceFiles).isEqualTo(model.sourceFiles)
  }
}
