/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Static rules of the ClickHouse migration runner (13 §13.2): every script is idempotent, so a
 * second application is a no-op, and the forbidden ALTERs are rejected before anything runs.
 *
 * <ul>
 *   <li>{@code CREATE DATABASE|TABLE|MATERIALIZED VIEW|VIEW|DICTIONARY} needs {@code IF NOT EXISTS}
 *   <li>{@code DROP DATABASE|TABLE|VIEW|DICTIONARY} needs {@code IF EXISTS}
 *   <li>{@code ALTER ... ADD COLUMN|INDEX|PROJECTION|CONSTRAINT} needs {@code IF NOT EXISTS},
 *       {@code DROP COLUMN|INDEX|PROJECTION|CONSTRAINT} needs {@code IF EXISTS}
 *   <li>{@code MODIFY ORDER BY} and {@code MODIFY COLUMN} are forbidden (new table + 4-step
 *       replacement instead)
 *   <li>{@code MODIFY TTL} needs {@code materialize_ttl_after_modify = 0}
 *   <li>only DDL ({@code CREATE}, {@code ALTER}, {@code DROP}, {@code RENAME}); no data statements
 * </ul>
 */
public final class SchemaLint {

  private static final Pattern CREATE =
      Pattern.compile("^CREATE\\s+(DATABASE|TABLE|MATERIALIZED\\s+VIEW|VIEW|DICTIONARY)\\s+(.*)$");
  private static final Pattern DROP =
      Pattern.compile("^DROP\\s+(DATABASE|TABLE|VIEW|DICTIONARY)\\s+(.*)$");
  private static final Pattern ALTER_ADD =
      Pattern.compile(
          "\\bADD\\s+(COLUMN|INDEX|PROJECTION|CONSTRAINT)\\s+(?!IF\\s+NOT\\s+EXISTS\\b)");
  private static final Pattern ALTER_DROP =
      Pattern.compile("\\bDROP\\s+(COLUMN|INDEX|PROJECTION|CONSTRAINT)\\s+(?!IF\\s+EXISTS\\b)");
  private static final Pattern MODIFY_FORBIDDEN =
      Pattern.compile("\\bMODIFY\\s+(ORDER\\s+BY|COLUMN)\\b");
  private static final Pattern MODIFY_TTL = Pattern.compile("\\bMODIFY\\s+TTL\\b");
  private static final Pattern NO_MATERIALIZE =
      Pattern.compile("\\bMATERIALIZE_TTL_AFTER_MODIFY\\s*=\\s*0\\b");

  private SchemaLint() {}

  /**
   * Violations of the rules, one message per offending statement; empty when the script is fine.
   */
  public static List<String> violations(List<String> statements) {
    List<String> violations = new ArrayList<>();
    for (int i = 0; i < statements.size(); i++) {
      String problem = check(normalize(statements.get(i)));
      if (problem != null) {
        violations.add(
            "statement " + (i + 1) + ": " + problem + ": " + abbreviate(statements.get(i)));
      }
    }
    return violations;
  }

  private static String check(String s) {
    if (s.startsWith("CREATE ")) {
      var m = CREATE.matcher(s);
      if (!m.matches()) {
        return "unsupported CREATE";
      }
      return m.group(2).startsWith("IF NOT EXISTS ") ? null : "CREATE without IF NOT EXISTS";
    }
    if (s.startsWith("DROP ")) {
      var m = DROP.matcher(s);
      if (!m.matches()) {
        return "unsupported DROP";
      }
      return m.group(2).startsWith("IF EXISTS ") ? null : "DROP without IF EXISTS";
    }
    if (s.startsWith("ALTER ")) {
      if (MODIFY_FORBIDDEN.matcher(s).find()) {
        return "MODIFY ORDER BY / MODIFY COLUMN is forbidden (new table + 4-step replacement)";
      }
      if (ALTER_ADD.matcher(s).find()) {
        return "ADD without IF NOT EXISTS";
      }
      if (ALTER_DROP.matcher(s).find()) {
        return "DROP without IF EXISTS";
      }
      if (MODIFY_TTL.matcher(s).find() && !NO_MATERIALIZE.matcher(s).find()) {
        return "MODIFY TTL without materialize_ttl_after_modify = 0";
      }
      return null;
    }
    if (s.startsWith("RENAME ")) {
      return null;
    }
    return "not a DDL statement";
  }

  /** Comments removed, quoted text blanked, whitespace collapsed, upper case. */
  private static String normalize(String statement) {
    StringBuilder sb = new StringBuilder();
    for (SqlScript.Segment segment : SqlScript.lex(statement)) {
      switch (segment.kind()) {
        case CODE -> sb.append(segment.text());
        case QUOTED -> sb.append(" ? ");
        case COMMENT -> sb.append(' ');
      }
    }
    return sb.toString().replaceAll("\\s+", " ").strip().toUpperCase(Locale.ROOT);
  }

  private static String abbreviate(String statement) {
    String oneLine = statement.replaceAll("\\s+", " ").strip();
    return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 117) + "...";
  }
}
