// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.testing

import io.argos.buildlogic.repositoryRoot
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * test/matrix/matrix.yaml: the YAML subset, row validation and selection (18 §6.1, §6.2, DSN-18-11,
 * WO-50), and the committed file itself (every 02 §9 combination is a row).
 */
@Tag("req:DSN-18-11")
class MatrixCatalogTest {

  private fun row(id: String, extra: String = "", group: String = "boot3", app: String = "shop-boot3", jdk: Int = 21) =
    """
    |  - id: $id
    |    group: $group
    |    app: $app
    |    jdk: $jdk
    |    suites: [io.argos.agent.testing.e2e.SampleAppAgentTest]
    |    required: [io.argos.agent.testing.e2e.SampleAppAgentTest]
    """.trimMargin() + (if (extra.isEmpty()) "" else "\n" + extra.trimMargin())

  private fun doc(vararg rows: String) = "version: 1\nrows:\n" + rows.joinToString("\n") + "\n"

  private fun failure(text: String): String =
    try {
      MatrixCatalog.parse(text, "m.yaml")
      throw AssertionError("expected a MatrixDefinitionException")
    } catch (e: MatrixDefinitionException) {
      e.message.orEmpty()
    }

  @Test
  @DisplayName("T-MX-001..121 the YAML subset: block and flow sequences, quotes, comments, suite sets")
  fun parsesSubset() {
    val catalog =
      MatrixCatalog.parse(
        """
        # comment
        version: 1
        suite-sets:
          base:
            - io.argos.agent.testing.matrix.MatrixSmokeTest # trailing comment
            - 'io.argos.agent.testing.e2e.*'
        rows:
        - id: T-MX-022
          aliases: [T-MX-017]
          group: boot3
          app: shop-boot3
          jdk: 21
          vt: true
          app-env: [SAMPLE_CONTAINER=jetty, SAMPLE_X=a.b]
          props:
            - agent.jdbc.containers=postgres,mysql
          suites: [set:base, io.argos.agent.testing.async.*]
          required:
            - set:base
            - "*JdbcStepsTest.T-AG-031 postgres *"
          status: gate
          requires: [env:ARGOS_TEST_X, prop:agent.jdk8u242, os:linux]
          note: "a # not a comment, and \"quoted\""
        """.trimIndent()
      )
    val r = catalog.rows.single()
    assertThat(r.id).isEqualTo("T-MX-022")
    assertThat(r.aliases).containsExactly("T-MX-017")
    assertThat(r.vt).isTrue()
    assertThat(r.appEnvProperty).isEqualTo("SAMPLE_CONTAINER=jetty;SAMPLE_X=a.b")
    assertThat(r.props).containsEntry("agent.jdbc.containers", "postgres,mysql")
    assertThat(r.suites).containsExactly(
      "io.argos.agent.testing.matrix.MatrixSmokeTest",
      "io.argos.agent.testing.e2e.*",
      "io.argos.agent.testing.async.*",
    )
    assertThat(r.required).contains("*JdbcStepsTest.T-AG-031 postgres *")
    assertThat(r.requires).containsExactly("env:ARGOS_TEST_X", "prop:agent.jdk8u242", "os:linux")
    assertThat(r.note).isEqualTo("a # not a comment, and \"quoted\"")
    assertThat(r.e2eCombo).isEqualTo("shop-boot3@21")
    assertThat(r.status).isEqualTo(MatrixStatus.GATE)
    assertThat(catalog.find("T-MX-017")).isSameAs(r)
  }

  @Test
  @DisplayName("T-MX-001..121 invalid rows fail with the file and line at configuration time")
  fun rejectsInvalidRows() {
    assertThat(failure(doc(row("T-MX-021", "|    color: red")))).contains("m.yaml:9").contains("unknown row key 'color'")
    assertThat(failure(doc(row("T-MX-21")))).contains("id 'T-MX-21'")
    assertThat(failure(doc(row("T-MX-002")))).contains("outside the range of group 'boot3' (T-MX-020..031)")
    assertThat(failure(doc(row("T-MX-021", group = "boot9")))).contains("unknown group 'boot9'")
    assertThat(failure(doc(row("T-MX-021", jdk = 8)))).contains("shop-boot3 does not run on JDK 8 (02 §9")
    assertThat(failure(doc(row("T-MX-021", jdk = 9)))).contains("jdk '9'")
    assertThat(failure(doc(row("T-MX-021", app = "shop-boot5")))).contains("unknown app 'shop-boot5'")
    assertThat(failure(doc(row("T-MX-001", "|    vt: true", group = "boot2", app = "shop-boot2")))).contains("virtual threads need")
    assertThat(failure(doc(row("T-MX-020", "|    vt: true", jdk = 17)))).contains("virtual threads need")
    assertThat(failure(doc(row("T-MX-080", group = "war-javax", app = "shop-war-javax", jdk = 8)))).contains("needs 'tomcat'")
    assertThat(failure(doc(row("T-MX-090", "|    tomcat: tomcat9", group = "war-jakarta", app = "shop-war-jakarta"))))
      .contains("tomcat 'tomcat9' is not a standalone Tomcat of shop-war-jakarta")
    assertThat(failure(doc(row("T-MX-021", "|    tomcat: tomcat11")))).contains("is not a standalone Tomcat of shop-boot3")
    assertThat(failure(doc(row("T-MX-021", "|    props: [agent.e2e=x@1]")))).contains("'agent.e2e' is not a harness switch")
    assertThat(failure(doc(row("T-MX-021", "|    app-env: [lower=x]")))).contains("expected <ENV_NAME>=<value>")
    assertThat(failure(doc(row("T-MX-021", "|    app-env: [A=x;y]")))).contains("unsupported characters")
    assertThat(failure(doc(row("T-MX-021", "|    coexist: jprofiler")))).contains("coexist 'jprofiler'")
    assertThat(failure(doc(row("T-MX-121", "|    coexist: devtools", group = "routing", app = "shop-boot2", jdk = 8))))
      .contains("devtools variant exists for shop-boot3 only")
    assertThat(failure(doc(row("T-MX-021", "|    status: flaky")))).contains("status 'flaky'")
    assertThat(failure(doc(row("T-MX-021", "|    requires: [ARGOS_X]")))).contains("requirement 'ARGOS_X'")
    assertThat(failure(doc(row("T-MX-021"), row("T-MX-021")))).contains("ID 'T-MX-021' is already used")
    assertThat(failure(doc(row("T-MX-021", "|    aliases: [T-MX-022]"), row("T-MX-022")))).contains("ID 'T-MX-022' is already used")
    assertThat(failure(doc(row("T-MX-021", "|    note: x").replace("[io.argos.agent.testing.e2e.SampleAppAgentTest]\n    required", "[set:none]\n    required"))))
      .contains("unknown suite set 'set:none'")
    assertThat(failure("version: 1\nrows:\n  - id: T-MX-021\n    group: boot3\n    app: shop-boot3\n    jdk: 21\n    suites: [a.B]\n"))
      .contains("'required' is empty")
    assertThat(failure("version: 2\nrows: []\n")).contains("unsupported version '2'")
    assertThat(failure("version: 1\nrows: []\n")).contains("no rows")
    assertThat(failure("version: 1\nextra: x\nrows: []\n")).contains("unknown top-level key 'extra'")
  }

  @Test
  @DisplayName("T-MX-001..121 YAML outside the documented subset is rejected, never guessed")
  fun rejectsUnsupportedYaml() {
    assertThat(failure("version: 1\n\trows: []\n")).contains("m.yaml:2: tab characters")
    assertThat(failure("version: 1\nrows: &a []\n")).contains("anchors, aliases")
    assertThat(failure("version: 1\nrows:\n  - id: T-MX-021\n    note: |\n      x\n")).contains("block scalars")
    assertThat(failure("version: 1\nrows: {a: b}\n")).contains("flow mappings")
    assertThat(failure("version: 1\nrows: [a,\n  b]\n")).contains("flow sequences must close on the same line")
    assertThat(failure("version: 1\nversion: 1\n")).contains("duplicate key 'version'")
    assertThat(failure("version: 1\nrows:\n  - id: \"T-MX-021\n")).contains("unterminated quoted scalar")
    assertThat(failure("---\nversion: 1\n")).contains("document markers")
    assertThat(failure("version: 1\nrows:\n  - id: T-MX-021\n      group: boot3\n")).contains("unexpected indentation")
  }

  @Test
  @DisplayName("T-MX-001..121 selection by -Pmatrix.combo (IDs and aliases) and -Pmatrix.group; unknown names fail")
  fun selection() {
    val catalog =
      MatrixCatalog.parse(
        doc(
          row("T-MX-021"),
          row("T-MX-022", "|    aliases: [T-MX-017]\n|    vt: true"),
          row("T-MX-001", group = "boot2", app = "shop-boot2", jdk = 8),
        )
      )
    assertThat(MatrixSelection.select(catalog, null, null, all = false)).isEmpty()
    assertThat(MatrixSelection.select(catalog, null, null, all = true).map { it.id }).containsExactly("T-MX-021", "T-MX-022", "T-MX-001")
    assertThat(MatrixSelection.select(catalog, "T-MX-017", null, all = false).map { it.id }).containsExactly("T-MX-022")
    assertThat(MatrixSelection.select(catalog, " T-MX-001 , T-MX-021", "", all = true).map { it.id }).containsExactly("T-MX-021", "T-MX-001")
    assertThat(MatrixSelection.select(catalog, null, "boot3", all = false).map { it.id }).containsExactly("T-MX-021", "T-MX-022")
    assertThat(MatrixSelection.select(catalog, "T-MX-001", "boot3", all = false).map { it.id })
      .containsExactly("T-MX-021", "T-MX-022", "T-MX-001")
    assertThatThrownBy { MatrixSelection.select(catalog, "T-MX-999", null, all = false) }
      .isInstanceOf(MatrixDefinitionException::class.java)
      .hasMessageContaining("-Pmatrix.combo: unknown combination 'T-MX-999'")
    assertThatThrownBy { MatrixSelection.select(catalog, null, "jdbc", all = false) }
      .hasMessageContaining("-Pmatrix.group: unknown group 'jdbc' (boot2, boot3)")
    assertThat(MatrixSelection.taskName(catalog.rows[0])).isEqualTo("matrixTest-T-MX-021")
    assertThat(MatrixSelection.resultTaskName(catalog.rows[0])).isEqualTo("matrixResult-T-MX-021")
    assertThat(TestLayers.isNotInCheck("matrixTest-T-MX-021")).isTrue()
    assertThat(TestLayers.isNotInCheck("matrixResult-T-MX-021")).isTrue()
    assertThat(TestLayers.isNotInCheck("matrixReport")).isTrue()
  }

  private val committed = File(repositoryRoot, "test/matrix/matrix.yaml")

  @Test
  @DisplayName("T-MX-001..121 test/matrix/matrix.yaml covers every 02 §9 combination, VT on/off, JDBC, coexistence")
  fun committedCatalogCoversTheValidCombinations() {
    val catalog = MatrixCatalog.load(committed)
    fun rows(app: String, vt: Boolean = false) = catalog.rows.filter { it.app == app && it.vt == vt && it.group !in setOf("containers", "boundary", "transport", "routing", "jdbc", "coexist") }
    assertThat(rows("shop-boot2").map { it.jdk }).containsExactlyInAnyOrder(8, 11, 17, 21, 25)
    assertThat(rows("shop-boot2").single { it.jdk == 25 }.status).isEqualTo(MatrixStatus.UNVERIFIED)
    for (app in listOf("shop-boot3", "shop-boot4")) {
      assertThat(rows(app).map { it.jdk }).describedAs(app).containsExactlyInAnyOrder(17, 21, 25)
      assertThat(rows(app, vt = true).map { it.jdk }).describedAs("$app VT").containsExactlyInAnyOrder(21, 25)
    }
    assertThat(rows("shop-heavy").map { it.jdk }).containsExactlyInAnyOrder(17, 21, 25)
    assertThat(rows("shop-batch").map { it.jdk }).containsExactlyInAnyOrder(17, 21, 25)
    assertThat(rows("shop-war-javax").map { "${it.tomcat}@${it.jdk}" })
      .containsExactlyInAnyOrder("tomcat9@8", "tomcat9@11", "tomcat9@17", "tomcat9@21")
    assertThat(rows("shop-war-jakarta").map { "${it.tomcat}@${it.jdk}" })
      .containsExactlyInAnyOrder("tomcat10@17", "tomcat10@21", "tomcat10@25", "tomcat11@17", "tomcat11@21", "tomcat11@25")
    assertThat(catalog.rows.filter { it.group.startsWith("war") }).allSatisfy {
      assertThat(it.suites).contains("io.argos.agent.testing.matrix.WarRedeployTest")
    }
    // 18 §6.3: nine databases, the two extra pools on PostgreSQL.
    val jdbc = catalog.rows.filter { it.group == "jdbc" }
    assertThat(jdbc).hasSize(9)
    assertThat(jdbc.flatMap { it.required }.joinToString(" "))
      .contains("mysql", "mariadb", "postgres", "mssql", "oracle", "tibero", "altibase", "cubrid", "db2", "PoolRegistryE2eTest")
    assertThat(jdbc.filter { it.requires.isNotEmpty() }.flatMap { it.requires }).contains("env:ARGOS_TEST_JDBC_DRIVER_DIR")
    assertThat(catalog.rows.mapNotNull { it.coexist })
      .containsExactlyInAnyOrder("jacoco-before", "jacoco-after", "otel-before", "otel-after", "devtools")
    assertThat(catalog.rows.filter { it.group == "routing" }.map { it.e2eCombo }).containsExactly("shop-boot2@8", "shop-boot3@21")
    assertThat(catalog.find("T-MX-017")?.id).isEqualTo("T-MX-022")
    assertThat(catalog.find("T-MX-017")!!.vt).isTrue()
    assertThat(catalog.groups).containsExactly(
      "boot2", "boot3", "boot4", "heavy", "batch", "war-javax", "war-jakarta", "jdbc", "coexist", "routing",
      "containers", "transport", "boundary",
    )
    // Every row's ID lies in its group's range (also checked by the parser) and is unique.
    assertThat(catalog.rows.map { it.id }).doesNotHaveDuplicates()
  }

  @Test
  @DisplayName("T-BLD-029 the nightly workflow runs the groups of matrix.yaml through matrixTest and generates the report")
  fun nightlyWorkflowUsesTheCatalog() {
    val nightly = File(repositoryRoot, ".github/workflows/nightly.yml").readText()
    assertThat(nightly)
      .contains("test/matrix/matrix.yaml")
      .contains("./gradlew")
      .contains("matrixTest")
      .contains("-Pmatrix.group=")
      .contains("-Pmatrix.combo=")
      .contains(":agent:agent-testing:matrixReport")
      .contains("ARGOS_RUNNER_LABELS: argos-matrix")
      .contains("-Pserver.nightly=true")
      .contains(" securityTest")
      .doesNotContain("security.pii.requests")
      .contains(":deploy:composeSmoke")
      .contains(":deploy:demoSmoke -Pdemo.smoke.enforce=true")
    // The plan job reads the group names the way MatrixCatalog writes them.
    val groupLines = committed.readLines().filter { Regex("""^\s+group: [a-z0-9-]+\s*$""").matches(it) }
    assertThat(groupLines.map { it.substringAfter("group:").trim() }.distinct()).isEqualTo(MatrixCatalog.load(committed).groups)
  }
}
