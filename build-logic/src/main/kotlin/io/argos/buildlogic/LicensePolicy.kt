// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import groovy.json.JsonSlurper
import java.io.File
import org.gradle.api.GradleException

/**
 * License allow-list policy (19 §5.5, DSN-19-04, O-L03, T-BLD-007).
 *
 * Inputs:
 * - `build-logic/allowed-licenses.json`: SPDX identifiers that shipped artifacts may contain.
 * - `build-logic/license-overrides.json`: manual `group:artifact` -> SPDX mappings for modules whose
 *   metadata is missing or ambiguous; every entry needs a source URL.
 * - `docs/licenses/dev-only.md`: test-only components under other licenses.
 * - the JSON report of com.github.jk1.dependency-license-report (raw POM/manifest license data).
 *
 * License names are mapped to SPDX identifiers by [SpdxNames], a deliberately small table: a name
 * that is not recognised stays unknown and fails the check until an override is added. A module
 * that declares several licenses is treated as dual-licensed (any allowed license suffices).
 */
class LicensePolicy(val allowed: Set<String>, val overrides: List<LicenseOverride>) {

  /** Resolves the license of [module] (`group:artifact`) at [version] from [declared] licenses. */
  fun resolve(module: String, version: String, declared: List<DeclaredLicense>): ResolvedLicense {
    val override =
      overrides.firstOrNull { it.module == module && (it.version == null || it.version == version) }
    if (override != null) {
      return ResolvedLicense(override.license, isAllowed(override.license), "override", declared)
    }
    val ids = declared.mapNotNull { SpdxNames.toSpdx(it) }.distinct().sorted()
    if (ids.isEmpty()) return ResolvedLicense(null, false, "unknown", declared)
    val expression = if (ids.size == 1) ids.single() else ids.joinToString(" OR ") { "($it)" }
    return ResolvedLicense(expression, isAllowed(expression), "metadata", declared)
  }

  /** True when [expression] can be satisfied with allowed licenses only (OR: one branch). */
  fun isAllowed(expression: String): Boolean = SpdxExpression.parse(expression).satisfiedBy(allowed)

  companion object {
    fun load(allowedFile: File, overridesFile: File): LicensePolicy =
      LicensePolicy(readAllowed(allowedFile), readOverrides(overridesFile))

    fun readAllowed(file: File): Set<String> {
      val json = parseJson(file)
      val allowed =
        (json["allowedLicenses"] as? List<*>)?.map { entry ->
          (entry as? Map<*, *>)?.get("spdx") as? String
            ?: throw GradleException("$file: every allowedLicenses entry needs an 'spdx' id")
        } ?: throw GradleException("$file: missing 'allowedLicenses' array")
      if (allowed.isEmpty()) throw GradleException("$file: the allow-list is empty")
      return allowed.toSortedSet()
    }

    fun readOverrides(file: File): List<LicenseOverride> {
      val json = parseJson(file)
      val entries =
        json["overrides"] as? List<*> ?: throw GradleException("$file: missing 'overrides' array")
      return entries.mapIndexed { index, raw ->
        val entry = raw as? Map<*, *> ?: throw GradleException("$file: overrides[$index] is not an object")
        fun field(name: String): String? = (entry[name] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val module =
          field("module")?.takeIf { it.matches(Regex("[^:\\s]+:[^:\\s]+")) }
            ?: throw GradleException("$file: overrides[$index].module must be 'group:artifact'")
        val license =
          field("license") ?: throw GradleException("$file: overrides[$index].license is required")
        SpdxExpression.parse(license) // validates the syntax
        val source =
          field("source")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: throw GradleException(
              "$file: overrides[$index] ($module) needs a 'source' URL that shows the license"
            )
        LicenseOverride(module, field("version"), license, source)
      }
    }

    /**
     * `group:artifact` patterns from the machine-readable table of docs/licenses/dev-only.md. Rows
     * for components Gradle does not resolve are skipped: `tool:` and `vendor:` (listed for
     * completeness) and `npm:` (UI tool packages, checked by `pnpm -C ui check:licenses:dev`).
     */
    fun readDevOnly(file: File): List<String> {
      if (!file.isFile) throw GradleException("Missing $file (dev-only license list, 19 §5.5)")
      val cell = Regex("^\\|\\s*`([^`]+)`\\s*\\|")
      return file.readLines().mapNotNull { line -> cell.find(line)?.groupValues?.get(1)?.trim() }
        .filter { it.contains(':') && NON_MAVEN_PREFIXES.none { prefix -> it.startsWith(prefix) } }
    }

    private val NON_MAVEN_PREFIXES = listOf("tool:", "vendor:", "npm:")

    fun devOnlyMatches(patterns: List<String>, module: String): Boolean =
      patterns.any { pattern ->
        if (pattern.endsWith(":*")) module.substringBefore(':') == pattern.removeSuffix(":*")
        else module == pattern
      }

    /**
     * Reads the [LicenseDataRenderer] report: `group:artifact:version` -> declared licenses. The POM
     * declaration wins (Maven semantics: the closest POM that declares licenses); the jar manifest
     * and license files are used only when no POM declares a license.
     */
    fun readReport(file: File): Map<String, List<DeclaredLicense>> {
      val json = parseJson(file)
      val modules =
        json["modules"] as? List<*> ?: throw GradleException("$file: missing 'modules' array")
      return modules.filterIsInstance<Map<*, *>>().associate { module ->
        fun licenses(source: String): List<DeclaredLicense> =
          (module[source] as? List<*>)
            .orEmpty()
            .filterIsInstance<Map<*, *>>()
            .map { DeclaredLicense(blankToNull(it["name"]), blankToNull(it["url"]), source) }
            .filter { it.name != null || it.url != null }
            .distinct()
        val declared =
          licenses("pom").ifEmpty { licenses("manifest") }.ifEmpty { licenses("licenseFiles") }
        "${module["module"]}:${module["version"]}" to declared
      }
    }

    private fun blankToNull(value: Any?): String? = (value as? String)?.trim()?.ifEmpty { null }

    private fun parseJson(file: File): Map<*, *> {
      if (!file.isFile) throw GradleException("Missing $file")
      return JsonSlurper().parse(file) as? Map<*, *>
        ?: throw GradleException("$file: expected a JSON object")
    }
  }
}

data class DeclaredLicense(val name: String?, val url: String?, val source: String = "pom") {
  override fun toString(): String =
    listOfNotNull(name?.let { "'$it'" }, url?.let { "<$it>" }).joinToString(" ") + " from $source"
}

data class LicenseOverride(
  val module: String,
  val version: String?,
  val license: String,
  val source: String,
)

data class ResolvedLicense(
  /** SPDX expression, or null when the license is unknown. */
  val expression: String?,
  val allowed: Boolean,
  /** "metadata", "override" or "unknown". */
  val origin: String,
  val declared: List<DeclaredLicense>,
)

/**
 * Conservative mapping of license names and URLs found in POMs and manifests to SPDX identifiers.
 * Only unambiguous spellings are listed; for example a bare "BSD License" is not mapped (it could be
 * any BSD variant) and needs an override.
 */
object SpdxNames {
  private val byName: List<Pair<Regex, String>> =
    listOf(
      // Allowed licenses (build-logic/allowed-licenses.json).
      "(the )?apache( software)? licen[cs]e,?( -)?( version| v)? ?2(\\.0)?( licen[cs]e)?" to "Apache-2.0",
      "apache[- ]?(licen[cs]e[- ])?(v|version )?2(\\.0)?" to "Apache-2.0",
      "asl,? ?(v|version )?2(\\.0)?" to "Apache-2.0",
      "(the )?mit( licen[cs]e)?( \\(mit\\))?" to "MIT",
      // MIT No Attribution (ADR-0004 C-42), e.g. org.reactivestreams:reactive-streams 1.0.4.
      "(the )?(mit[- ]0|mit no attribution)( licen[cs]e)?( \\(mit-0\\))?" to "MIT-0",
      "(the )?(bsd[- ]2[- ]clause|2[- ]clause bsd|simplified bsd|freebsd)( licen[cs]e)?" to "BSD-2-Clause",
      "(the )?(bsd[- ]3[- ]clause|3[- ]clause bsd|new bsd|revised bsd|modified bsd)( licen[cs]e)?" to
        "BSD-3-Clause",
      "eclipse distribution licen[cs]e,?( -)? ?(v|version)? ?1\\.0|edl[- ]?1\\.0" to "BSD-3-Clause",
      "(the )?isc( licen[cs]e)?" to "ISC",
      "0bsd|bsd zero[- ]clause( licen[cs]e)?|zero[- ]clause bsd" to "0BSD",
      "(the )?postgresql( licen[cs]e)?" to "PostgreSQL",
      "cc0( 1\\.0| v1\\.0|-1\\.0)?( universal)?|creative commons zero.*|public domain, per creative commons cc0" to
        "CC0-1.0",
      "unicode-dfs-2015" to "Unicode-DFS-2015",
      "unicode-dfs-2016|unicode licen[cs]e agreement - data files and software \\(2016\\)" to
        "Unicode-DFS-2016",
      // Common non-allowed licenses, mapped only to produce clear failure messages.
      "(the )?gnu general public licen[cs]e,? (v|version )?2(\\.0)?.*classpath.*|gpl2 w/ cpe|gpl-2\\.0 with classpath-exception-2\\.0" to
        "GPL-2.0-only WITH Classpath-exception-2.0",
      "(the )?gnu general public licen[cs]e,? (v|version )?2(\\.0)?,? with (the )?universal foss exception.*" to
        "GPL-2.0-only WITH Universal-FOSS-exception-1.0",
      "(the )?gnu general public licen[cs]e,? (v|version )?2(\\.0)?( only)?|gpl-?2\\.0(-only)?|gplv2" to
        "GPL-2.0-only",
      "(the )?gnu general public licen[cs]e,? (v|version )?3(\\.0)?( only)?|gpl-?3\\.0(-only)?|gplv3" to
        "GPL-3.0-only",
      "(the )?gnu affero general public licen[cs]e,? (v|version )?3(\\.0)?|agpl-?3\\.0(-only)?" to
        "AGPL-3.0-only",
      "(the )?gnu lesser general public licen[cs]e,? (v|version )?2\\.1( or later)?|lgpl-?2\\.1(-only)?|lgpl 2\\.1" to
        "LGPL-2.1-only",
      "(the )?gnu lesser general public licen[cs]e,? (v|version )?3(\\.0)?|lgpl-?3\\.0(-only)?" to
        "LGPL-3.0-only",
      "eclipse public licen[cs]e,?( -)? ?(v|version)? ?1\\.0|epl[- ]1\\.0" to "EPL-1.0",
      "eclipse public licen[cs]e,?( -)? ?(v|version)? ?2\\.0|epl[- ]2\\.0" to "EPL-2.0",
      "mozilla public licen[cs]e,?( -)? ?(v|version)? ?2\\.0|mpl[- ]2\\.0" to "MPL-2.0",
      "common development and distribution licen[cs]e.*1\\.0|cddl[- ]1\\.0" to "CDDL-1.0",
      "common development and distribution licen[cs]e.*1\\.1|cddl[- ]1\\.1" to "CDDL-1.1",
    ).map { (pattern, id) -> Regex(pattern, RegexOption.IGNORE_CASE) to id }

  private val byUrl: List<Pair<Regex, String>> =
    listOf(
      "https?://(www\\.)?apache\\.org/licenses/license-2\\.0(\\.txt|\\.html)?" to "Apache-2.0",
      "https?://(www\\.)?opensource\\.org/licen[cs]es?/apache-2\\.0(\\.php)?" to "Apache-2.0",
      "https?://(www\\.)?opensource\\.org/licen[cs]es?/mit(\\.php|-license\\.php)?" to "MIT",
      "https?://(www\\.)?opensource\\.org/licen[cs]es?/mit-0(\\.php)?" to "MIT-0",
      "https?://spdx\\.org/licenses/mit-0(\\.html)?" to "MIT-0",
      "https?://github\\.com/aws/mit-0" to "MIT-0",
      "https?://(www\\.)?opensource\\.org/licen[cs]es?/bsd-2-clause(\\.php)?" to "BSD-2-Clause",
      "https?://(www\\.)?opensource\\.org/licen[cs]es?/bsd-3-clause(\\.php)?" to "BSD-3-Clause",
      "https?://(www\\.)?eclipse\\.org/org/documents/edl-v10\\.(php|html)" to "BSD-3-Clause",
      "https?://(www\\.)?opensource\\.org/licen[cs]es?/isc(\\.php)?" to "ISC",
      "https?://(www\\.)?opensource\\.org/licen[cs]es?/0bsd" to "0BSD",
      "https?://(www\\.)?opensource\\.org/licen[cs]es?/postgresql(\\.php)?" to "PostgreSQL",
      "https?://(www\\.)?postgresql\\.org/about/licen[cs]e/?" to "PostgreSQL",
      "https?://creativecommons\\.org/publicdomain/zero/1\\.0(/legalcode)?/?" to "CC0-1.0",
      "https?://(www\\.)?gnu\\.org/licenses/gpl-3\\.0(\\.html|\\.txt|\\.en\\.html)?" to "GPL-3.0-only",
      "https?://(www\\.)?gnu\\.org/licenses/(old-licenses/)?gpl-2\\.0(\\.html|\\.txt)?" to "GPL-2.0-only",
      "https?://(www\\.)?gnu\\.org/licenses/agpl-3\\.0(\\.html|\\.txt)?" to "AGPL-3.0-only",
      "https?://(www\\.)?gnu\\.org/licenses/(old-licenses/)?lgpl-2\\.1(\\.html|\\.txt)?" to
        "LGPL-2.1-only",
      "https?://(www\\.)?eclipse\\.org/legal/epl-v10\\.html" to "EPL-1.0",
      "https?://(www\\.)?eclipse\\.org/legal/epl-2\\.0/?" to "EPL-2.0",
      "https?://(www\\.)?mozilla\\.org/(en-us/)?mpl/2\\.0/?" to "MPL-2.0",
    ).map { (pattern, id) -> Regex(pattern, RegexOption.IGNORE_CASE) to id }

  /** The SPDX identifier (or expression) for [license], or null when it is not recognised. */
  fun toSpdx(license: DeclaredLicense): String? =
    license.name?.let { fromName(it) } ?: license.url?.let { fromUrl(it) }

  fun fromName(name: String): String? {
    val text = name.trim().replace(Regex("\\s+"), " ")
    if (text.isEmpty()) return null
    byName.firstOrNull { (regex, _) -> regex.matches(text) }?.let { return it.second }
    // An SPDX expression written in the metadata, e.g. "Apache-2.0 OR MIT".
    return runCatching { SpdxExpression.parse(text) }
      .getOrNull()
      ?.takeIf { expression -> expression.identifiers().all { it in KNOWN_IDS } }
      ?.let { text }
  }

  fun fromUrl(url: String): String? {
    val text = url.trim().removeSuffix("/")
    return byUrl.firstOrNull { (regex, _) -> regex.matches(text) || regex.matches("$text/") }?.second
  }

  private val KNOWN_IDS =
    setOf(
      "Apache-2.0",
      "MIT",
      "MIT-0",
      "BSD-2-Clause",
      "BSD-3-Clause",
      "ISC",
      "0BSD",
      "PostgreSQL",
      "CC0-1.0",
      "Unicode-DFS-2015",
      "Unicode-DFS-2016",
      "GPL-2.0-only",
      "GPL-2.0-or-later",
      "GPL-3.0-only",
      "GPL-3.0-or-later",
      "AGPL-3.0-only",
      "LGPL-2.1-only",
      "LGPL-2.1-or-later",
      "LGPL-3.0-only",
      "EPL-1.0",
      "EPL-2.0",
      "MPL-2.0",
      "CDDL-1.0",
      "CDDL-1.1",
      "Classpath-exception-2.0",
      "Universal-FOSS-exception-1.0",
    )
}

/** Minimal SPDX license expression: identifiers combined with WITH, AND, OR and parentheses. */
sealed class SpdxExpression {
  abstract fun satisfiedBy(allowed: Set<String>): Boolean

  abstract fun identifiers(): List<String>

  data class Id(val id: String) : SpdxExpression() {
    override fun satisfiedBy(allowed: Set<String>) = id in allowed

    override fun identifiers() = listOf(id)
  }

  /** "X WITH exception": allowed only when the full expression is on the allow-list. */
  data class With(val license: String, val exception: String) : SpdxExpression() {
    override fun satisfiedBy(allowed: Set<String>) = "$license WITH $exception" in allowed

    override fun identifiers() = listOf(license, exception)
  }

  data class And(val parts: List<SpdxExpression>) : SpdxExpression() {
    override fun satisfiedBy(allowed: Set<String>) = parts.all { it.satisfiedBy(allowed) }

    override fun identifiers() = parts.flatMap { it.identifiers() }
  }

  data class Or(val parts: List<SpdxExpression>) : SpdxExpression() {
    override fun satisfiedBy(allowed: Set<String>) = parts.any { it.satisfiedBy(allowed) }

    override fun identifiers() = parts.flatMap { it.identifiers() }
  }

  companion object {
    private val ID = Regex("[A-Za-z0-9.+-]+")

    fun parse(text: String): SpdxExpression {
      val tokens = Regex("\\(|\\)|[^\\s()]+").findAll(text).map { it.value }.toList()
      if (tokens.isEmpty()) throw GradleException("Empty SPDX expression")
      val parser = Parser(tokens, text)
      val expression = parser.or()
      if (!parser.done()) throw GradleException("Invalid SPDX expression '$text'")
      return expression
    }
  }

  private class Parser(private val tokens: List<String>, private val text: String) {
    private var position = 0

    fun done() = position == tokens.size

    private fun peek(): String? = tokens.getOrNull(position)

    private fun next(): String =
      tokens.getOrNull(position++) ?: throw GradleException("Invalid SPDX expression '$text'")

    fun or(): SpdxExpression {
      val parts = mutableListOf(and())
      while (peek() == "OR") {
        next()
        parts += and()
      }
      return if (parts.size == 1) parts.single() else Or(parts)
    }

    private fun and(): SpdxExpression {
      val parts = mutableListOf(primary())
      while (peek() == "AND") {
        next()
        parts += primary()
      }
      return if (parts.size == 1) parts.single() else And(parts)
    }

    private fun primary(): SpdxExpression {
      val token = next()
      if (token == "(") {
        val inner = or()
        if (next() != ")") throw GradleException("Invalid SPDX expression '$text'")
        return inner
      }
      if (!ID.matches(token) || token in setOf("AND", "OR", "WITH", ")")) {
        throw GradleException("Invalid SPDX expression '$text'")
      }
      if (peek() == "WITH") {
        next()
        val exception = next()
        if (!ID.matches(exception)) throw GradleException("Invalid SPDX expression '$text'")
        return With(token, exception)
      }
      return Id(token)
    }
  }
}
