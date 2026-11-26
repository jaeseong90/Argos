// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.testing

import java.io.File

/** A broken test/matrix/matrix.yaml or an unknown selection: fails the build at configuration. */
class MatrixDefinitionException(message: String) : RuntimeException(message)

/** Gate rows decide the verdict ○/✗; unverified rows run but never gate (18 §6.2, 02 §9 △). */
enum class MatrixStatus(val yaml: String) {
  GATE("gate"),
  UNVERIFIED("unverified"),
}

/**
 * One combination of the compatibility matrix (18 §6.1): a sample app on a JDK with its variant
 * (virtual threads, Tomcat, servlet container, coexisting agent, database) and the agent-testing
 * suites that verify it. Field meanings: test/matrix/README.md "Row fields".
 */
data class MatrixRow(
  val id: String,
  val aliases: List<String>,
  val group: String,
  val app: String,
  val jdk: Int,
  val vt: Boolean,
  val tomcat: String?,
  val appEnv: Map<String, String>,
  val props: Map<String, String>,
  val coexist: String?,
  val suites: List<String>,
  val required: List<String>,
  val status: MatrixStatus,
  val requires: List<String>,
  val note: String,
  val line: Int,
) {
  /** "shop-boot3@21" for the harness (-Pagent.e2e); null for the trivial attach app. */
  val e2eCombo: String?
    get() = if (app == MatrixCatalog.TRIVIAL_APP) null else "$app@$jdk"

  /** `K=V;K=V`, the value of argos.test.matrix.appEnv. */
  val appEnvProperty: String
    get() = appEnv.entries.joinToString(";") { "${it.key}=${it.value}" }
}

/** A group of rows: one nightly job, one ID range of 18 §6.1 (or a proposed range). */
data class MatrixGroup(val name: String, val ids: IntRange, val title: String)

/** test/matrix/matrix.yaml: the single definition of the matrix (18 §6.1, DSN-18-11). */
class MatrixCatalog(val rows: List<MatrixRow>) {

  /** Group names in the order of [GROUPS] that have at least one row. */
  val groups: List<String>
    get() = GROUPS.map { it.name }.filter { name -> rows.any { it.group == name } }

  /** The row with this ID or alias, or null. */
  fun find(idOrAlias: String): MatrixRow? = rows.firstOrNull { it.id == idOrAlias || idOrAlias in it.aliases }

  companion object {
    const val TRIVIAL_APP = "trivial"

    /** `set:<name>` in suites or required expands to a list of `suite-sets`. */
    const val SET_PREFIX = "set:"

    /**
     * Groups and their ID ranges: 18 §6.1 for T-MX-001..121, proposed ranges (PM registration) for
     * servlet containers (C-127), transport variants (C-235 (12), C-250 (8)) and module boundary
     * versions (C-240 (10)).
     */
    val GROUPS: List<MatrixGroup> =
      listOf(
        MatrixGroup("boot2", 1..12, "Spring Boot 2.7 (javax, embedded Tomcat 9)"),
        MatrixGroup("boot3", 20..31, "Spring Boot 3 (jakarta, embedded Tomcat 10.1)"),
        MatrixGroup("boot4", 40..51, "Spring Boot 4 (jakarta, embedded Tomcat 11)"),
        MatrixGroup("heavy", 60..63, "Large application (shop-heavy, startup time)"),
        MatrixGroup("batch", 70..75, "Batch and scheduling (shop-batch)"),
        MatrixGroup("war-javax", 80..87, "Standalone Tomcat 9 with a javax WAR"),
        MatrixGroup("war-jakarta", 90..95, "Standalone Tomcat 10.1 and 11 with jakarta WARs"),
        MatrixGroup("jdbc", 100..108, "JDBC drivers and connection pools"),
        MatrixGroup("coexist", 110..119, "Coexistence with other agents and devtools"),
        MatrixGroup("routing", 120..121, "Routing DataSource and nested JDBC wrappers"),
        MatrixGroup("containers", 130..139, "Jetty and Undertow servlet containers"),
        MatrixGroup("transport", 140..149, "Agent transport variants"),
        MatrixGroup("boundary", 150..159, "Instrumentation module boundary versions"),
      )

    val JDKS: Set<Int> = setOf(8, 11, 17, 21, 25)

    /** Apps of the harness (agent-testing -Pagent.e2e) and the JDKs of 02 §9 for each. */
    val APP_JDKS: Map<String, Set<Int>> =
      mapOf(
        "shop-boot2" to setOf(8, 11, 17, 21, 25),
        "shop-boot3" to setOf(17, 21, 25),
        "shop-boot4" to setOf(17, 21, 25),
        "shop-heavy" to setOf(17, 21, 25),
        "shop-batch" to setOf(17, 21, 25),
        "shop-war-javax" to setOf(8, 11, 17, 21),
        "shop-war-jakarta" to setOf(17, 21, 25),
        TRIVIAL_APP to JDKS,
      )

    /** Standalone Tomcat keys of the WAR apps (sample-apps tomcat(...) keys). */
    val APP_TOMCATS: Map<String, Set<String>> =
      mapOf("shop-war-javax" to setOf("tomcat9"), "shop-war-jakarta" to setOf("tomcat10", "tomcat11"))

    /** Apps whose virtual-thread run exists (Boot 3/4 on JDK 21+, 02 §9). */
    val VT_APPS: Set<String> = setOf("shop-boot3", "shop-boot4", "shop-heavy", "shop-batch")

    val COEXIST: Set<String> = setOf("jacoco-before", "jacoco-after", "otel-before", "otel-after", "devtools")

    /** -P switches of agent-testing that a row may set (agent/agent-testing/matrix-parameters.md). */
    val PROPS: Set<String> =
      setOf("agent.jdbc.containers", "agent.jdbc.oracle", "agent.container", "agent.attach.jdks", "agent.gc.jdks")

    private val ROW_KEYS =
      setOf(
        "id", "aliases", "group", "app", "jdk", "vt", "tomcat", "app-env", "props", "coexist", "suites",
        "required", "status", "requires", "note",
      )
    private val idPattern = Regex("""T-MX-(\d{3})""")
    private val envKey = Regex("""[A-Z][A-Z0-9_]*""")
    private val propValue = Regex("""[A-Za-z0-9_.,@:/=+-]*""")
    /** Suites are Gradle test filters (class patterns); required patterns may name a test too. */
    private val testPattern = Regex("""[A-Za-z0-9_.*$]+""")
    private val requiredPattern = Regex("""[A-Za-z0-9_.*$][A-Za-z0-9_.*$ @:()/=+-]*""")
    private val requirement = Regex("""(env:[A-Z][A-Z0-9_]*|prop:[a-z][A-Za-z0-9._-]*|os:(linux|windows|mac))""")
    private val groupName = Regex("""[a-z0-9-]+""")

    fun load(file: File): MatrixCatalog = parse(file.readText(Charsets.UTF_8), file.name)

    fun parse(text: String, source: String = "matrix.yaml"): MatrixCatalog {
      val root =
        try {
          MatrixYaml.parse(text, source)
        } catch (e: MatrixYaml.ParseException) {
          throw MatrixDefinitionException(e.message ?: "invalid YAML")
        }
      fun fail(line: Int, message: String): Nothing = throw MatrixDefinitionException("$source:$line: $message")
      if (root !is MatrixYaml.Mapping) fail(root.line, "the document must be a mapping (version, suite-sets, rows)")
      root.entries.keys.firstOrNull { it !in setOf("version", "suite-sets", "rows") }?.let {
        fail(root.keyLines.getValue(it), "unknown top-level key '$it' (version, suite-sets, rows)")
      }
      val version = root.entries["version"] as? MatrixYaml.Scalar ?: fail(root.line, "missing 'version: 1'")
      if (version.value != "1") fail(version.line, "unsupported version '${version.value}' (1)")

      val sets = LinkedHashMap<String, List<String>>()
      when (val node = root.entries["suite-sets"]) {
        null -> {}
        is MatrixYaml.Mapping ->
          node.entries.forEach { (name, value) ->
            if (!groupName.matches(name)) fail(node.keyLines.getValue(name), "suite set name '$name': lowercase letters, digits, '-'")
            sets[name] = strings(value, "suite-sets.$name", source).onEach {
              if (!testPattern.matches(it)) fail(value.line, "suite set '$name': '$it' is not a test class pattern")
            }
          }
        else -> fail(node.line, "'suite-sets' must be a mapping of name -> list of test class patterns")
      }

      val rowsNode = root.entries["rows"] as? MatrixYaml.Sequence ?: fail(root.line, "missing 'rows:' sequence")
      val rows = rowsNode.items.map { node -> row(node, sets, source) }
      if (rows.isEmpty()) fail(rowsNode.line, "no rows")
      val seen = HashMap<String, Int>()
      for (row in rows) {
        for (name in listOf(row.id) + row.aliases) {
          seen.put(name, row.line)?.let { fail(row.line, "ID '$name' is already used at line $it") }
        }
      }
      return MatrixCatalog(rows)
    }

    private fun strings(node: MatrixYaml.Node, key: String, source: String): List<String> =
      when (node) {
        is MatrixYaml.Sequence ->
          node.items.map {
            (it as? MatrixYaml.Scalar)?.value
              ?: throw MatrixDefinitionException("$source:${it.line}: '$key' items must be scalars")
          }
        is MatrixYaml.Scalar -> if (node.value.isEmpty()) emptyList() else listOf(node.value)
        is MatrixYaml.Mapping -> throw MatrixDefinitionException("$source:${node.line}: '$key' must be a list")
      }

    private fun row(node: MatrixYaml.Node, sets: Map<String, List<String>>, source: String): MatrixRow {
      fun fail(line: Int, message: String): Nothing = throw MatrixDefinitionException("$source:$line: $message")
      if (node !is MatrixYaml.Mapping) fail(node.line, "each row must be a mapping")
      node.entries.keys.firstOrNull { it !in ROW_KEYS }?.let {
        fail(node.keyLines.getValue(it), "unknown row key '$it' (${ROW_KEYS.joinToString(", ")})")
      }
      fun lineOf(key: String) = node.keyLines[key] ?: node.line
      fun scalar(key: String): String? =
        when (val value = node.entries[key]) {
          null -> null
          is MatrixYaml.Scalar -> value.value
          else -> fail(value.line, "'$key' must be a scalar")
        }
      fun list(key: String): List<String> = node.entries[key]?.let { strings(it, key, source) }.orEmpty()
      fun expand(key: String, rule: Regex): List<String> =
        list(key).flatMap { item ->
          if (item.startsWith(SET_PREFIX)) {
            sets[item.removePrefix(SET_PREFIX)] ?: fail(lineOf(key), "'$key': unknown suite set '$item'")
          } else {
            if (!rule.matches(item)) fail(lineOf(key), "'$key': '$item' is not a test pattern")
            listOf(item)
          }
        }.distinct()
      fun pairs(key: String, keyRule: Regex, what: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (item in list(key)) {
          val k = item.substringBefore('=', "")
          val v = item.substringAfter('=', "")
          if (!item.contains('=') || !keyRule.matches(k)) fail(lineOf(key), "'$key': expected $what, got '$item'")
          if (!propValue.matches(v) || v.contains(';')) fail(lineOf(key), "'$key': value '$v' has unsupported characters")
          if (out.put(k, v) != null) fail(lineOf(key), "'$key': '$k' given twice")
        }
        return out
      }

      val id = scalar("id") ?: fail(node.line, "missing 'id'")
      val number = idPattern.matchEntire(id)?.groupValues?.get(1)?.toInt() ?: fail(lineOf("id"), "id '$id': T-MX-nnn")
      val aliases = list("aliases").onEach { if (!idPattern.matches(it)) fail(lineOf("aliases"), "alias '$it': T-MX-nnn") }
      val group = scalar("group") ?: fail(node.line, "$id: missing 'group'")
      val groupSpec = GROUPS.firstOrNull { it.name == group }
        ?: fail(lineOf("group"), "$id: unknown group '$group' (${GROUPS.joinToString(", ") { it.name }})")
      if (number !in groupSpec.ids) {
        fail(lineOf("id"), "$id is outside the range of group '$group' (T-MX-%03d..%03d)".format(groupSpec.ids.first, groupSpec.ids.last))
      }
      val app = scalar("app") ?: fail(node.line, "$id: missing 'app'")
      val appJdks = APP_JDKS[app] ?: fail(lineOf("app"), "$id: unknown app '$app' (${APP_JDKS.keys.joinToString(", ")})")
      val jdkText = scalar("jdk") ?: fail(node.line, "$id: missing 'jdk'")
      val jdk = jdkText.toIntOrNull()?.takeIf { it in JDKS } ?: fail(lineOf("jdk"), "$id: jdk '$jdkText' (${JDKS.sorted().joinToString(", ")})")
      if (jdk !in appJdks) fail(lineOf("jdk"), "$id: $app does not run on JDK $jdk (02 §9: ${appJdks.sorted().joinToString(", ")})")
      val vt =
        when (val v = scalar("vt") ?: "false") {
          "true" -> true
          "false" -> false
          else -> fail(lineOf("vt"), "$id: vt '$v' (true or false)")
        }
      if (vt && (app !in VT_APPS || jdk < 21)) fail(lineOf("vt"), "$id: virtual threads need a Boot 3/4 app on JDK 21+ (02 §9)")
      val tomcat = scalar("tomcat")
      val tomcats = APP_TOMCATS[app]
      if (tomcats != null && tomcat == null) fail(node.line, "$id: WAR app $app needs 'tomcat' (${tomcats.joinToString(", ")})")
      if (tomcat != null && (tomcats == null || tomcat !in tomcats)) {
        fail(lineOf("tomcat"), "$id: tomcat '$tomcat' is not a standalone Tomcat of $app (${tomcats?.joinToString(", ") ?: "none"})")
      }
      val coexist = scalar("coexist")
      if (coexist != null && coexist !in COEXIST) fail(lineOf("coexist"), "$id: coexist '$coexist' (${COEXIST.joinToString(", ")})")
      if (coexist == "devtools" && app != "shop-boot3") fail(lineOf("coexist"), "$id: the devtools variant exists for shop-boot3 only")
      val props = pairs("props", Regex("""[a-z][A-Za-z0-9._-]*"""), "<gradle property>=<value>")
      props.keys.firstOrNull { it !in PROPS }?.let { fail(lineOf("props"), "$id: property '$it' is not a harness switch (${PROPS.joinToString(", ")})") }
      val appEnv = pairs("app-env", envKey, "<ENV_NAME>=<value>")
      val suites = expand("suites", testPattern)
      if (suites.isEmpty()) fail(node.line, "$id: 'suites' is empty")
      val required = expand("required", requiredPattern)
      if (required.isEmpty()) fail(node.line, "$id: 'required' is empty (the tests that must pass for ○)")
      val status =
        when (val s = scalar("status") ?: "gate") {
          "gate" -> MatrixStatus.GATE
          "unverified" -> MatrixStatus.UNVERIFIED
          else -> fail(lineOf("status"), "$id: status '$s' (gate or unverified)")
        }
      val requires = list("requires").onEach {
        if (!requirement.matches(it)) fail(lineOf("requires"), "$id: requirement '$it' (env:NAME, prop:name, os:linux|windows|mac)")
      }
      return MatrixRow(
        id, aliases, group, app, jdk, vt, tomcat, appEnv, props, coexist, suites, required, status, requires,
        scalar("note").orEmpty(), node.line,
      )
    }
  }
}

/** Which rows a run selects: -Pmatrix.combo, -Pmatrix.group, or all on the matrix runner (18 §6.2). */
object MatrixSelection {

  /** Name of the agent-testing Test task of [row]. */
  fun taskName(row: MatrixRow): String = "matrixTest-${row.id}"

  /** Name of the result finalizer of [row]. */
  fun resultTaskName(row: MatrixRow): String = "matrixResult-${row.id}"

  /**
   * Rows of `-Pmatrix.combo=<id|alias>[,...]` and `-Pmatrix.group=<group>[,...]` (union, in
   * catalog order); every row when neither is given and [all] is true (the self-hosted runner).
   * Unknown IDs and groups fail.
   */
  fun select(catalog: MatrixCatalog, combo: String?, group: String?, all: Boolean): List<MatrixRow> {
    val combos = split(combo)
    val groups = split(group)
    val unknownCombos = combos.filter { catalog.find(it) == null }
    if (unknownCombos.isNotEmpty()) {
      throw MatrixDefinitionException(
        "-Pmatrix.combo: unknown combination ${unknownCombos.joinToString(", ") { "'$it'" }} " +
          "(test/matrix/matrix.yaml has ${catalog.rows.first().id}..${catalog.rows.last().id})"
      )
    }
    val unknownGroups = groups.filter { it !in catalog.groups }
    if (unknownGroups.isNotEmpty()) {
      throw MatrixDefinitionException(
        "-Pmatrix.group: unknown group ${unknownGroups.joinToString(", ") { "'$it'" }} (${catalog.groups.joinToString(", ")})"
      )
    }
    if (combos.isEmpty() && groups.isEmpty()) return if (all) catalog.rows else emptyList()
    val ids = combos.mapNotNull { catalog.find(it)?.id }.toSet()
    return catalog.rows.filter { it.id in ids || it.group in groups }
  }

  private fun split(value: String?): List<String> =
    value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
