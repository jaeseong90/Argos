// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.server

/**
 * The file name rule of the server's PostgreSQL migrations (ADR-0003 C-28, decided by WO-20 for
 * 13 §13.1): every file in `src/main/resources/db/pg` is a versioned Flyway migration named
 * `V<NNN>__<lower_snake>.sql`.
 *
 * - `NNN` is exactly three digits; versions start at 001 and are contiguous (no gaps), and no
 *   version appears twice. Flyway itself orders `V1`, `V01` and `V001` alike and accepts gaps, so
 *   a typo would silently reorder or skip a migration.
 * - `<lower_snake>` is one or more groups of `[a-z0-9]` joined by single underscores.
 * - Only versioned migrations: no repeatable (`R__`), undo (`U__`) or baseline (`B`) scripts, no
 *   Java migrations, no other files and no subdirectories (Flyway scans locations recursively).
 *
 * The rule is a pure function of the relative paths, so the build-logic tests can check every
 * negative case without Gradle.
 */
object FlywayNames {

  /** The only accepted file name form. */
  val PATTERN: Regex = Regex("""V([0-9]{3})__([a-z0-9]+(?:_[a-z0-9]+)*)\.sql""")

  /** Directory below `src/main/resources` that Flyway reads (classpath location `db/pg`). */
  const val RESOURCE_DIRECTORY: String = "db/pg"

  /**
   * Returns the violations for the files of the migration directory, empty when every name is
   * valid and the versions are 001..n.
   *
   * @param relativePaths paths relative to the migration directory, with `/` separators
   */
  fun violations(relativePaths: Collection<String>): List<String> {
    val violations = mutableListOf<String>()
    val versions = sortedMapOf<Int, MutableList<String>>()
    for (path in relativePaths.sorted()) {
      if (path.contains('/')) {
        violations += "$path: subdirectories are not allowed (one flat directory of V<NNN>__<lower_snake>.sql)"
        continue
      }
      val match = PATTERN.matchEntire(path)
      if (match == null) {
        violations += "$path: name does not match V<NNN>__<lower_snake>.sql (${reason(path)})"
        continue
      }
      val version = match.groupValues[1].toInt()
      if (version == 0) {
        violations += "$path: version 000 is not allowed (versions start at 001)"
        continue
      }
      versions.getOrPut(version) { mutableListOf() } += path
    }
    for ((version, files) in versions) {
      if (files.size > 1) {
        violations += "version ${"%03d".format(version)} appears ${files.size} times: ${files.joinToString(", ")}"
      }
    }
    var expected = 1
    for (version in versions.keys) {
      if (version != expected) {
        val missing =
          (expected until version).joinToString(", ") { "V${"%03d".format(it)}" }
        violations += "versions are not contiguous from 001: missing $missing before V${"%03d".format(version)}"
      }
      expected = version + 1
    }
    return violations
  }

  /** A short explanation of why [name] does not match [PATTERN], for the error message. */
  private fun reason(name: String): String =
    when {
      !name.endsWith(".sql") -> "not a .sql file"
      name.startsWith("R__") || name.startsWith("U") || name.startsWith("B") ->
        "only versioned migrations (V) are allowed"
      !name.startsWith("V") -> "must start with V"
      !Regex("""V[0-9]{3}(?![0-9]).*""").matches(name) -> "the version must be exactly three digits"
      !Regex("""V[0-9]{3}__.*""").matches(name) -> "the version and the description are separated by two underscores"
      else -> "the description must be lower_snake_case: [a-z0-9] groups joined by single underscores"
    }

  /** The message of the build failure. */
  fun failureMessage(directory: String, violations: List<String>): String =
    "Invalid PostgreSQL migration files in $directory (ADR-0003 C-28: V<NNN>__<lower_snake>.sql, " +
      "versions 001..n without gaps or duplicates; a new migration takes the next free number):\n" +
      violations.joinToString("\n") { "  - $it" }
}
