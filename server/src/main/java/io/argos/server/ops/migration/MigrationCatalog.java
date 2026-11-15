/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checked content of a {@link MigrationSource}: schema scripts and procedures in number order,
 * each expanded and checksummed (C-56).
 *
 * <p>File rules (13 §13.2; checked when the server starts and by the unit tests of the bundled
 * files):
 *
 * <ul>
 *   <li>{@code schema/}: {@code V<NNN>__<lower_snake>.sql} only, numbers contiguous from 001;
 *   <li>{@code rollups/}: {@code <table>.sql} with {@code <table>} = {@code [a-z][a-z0-9_]*};
 *   <li>{@code migrations/}: {@code M<NNN>__<lower_snake>.yaml} only, numbers contiguous from 001;
 *   <li>rollback scripts ({@code U<NNN>__}, 13 §13.2) are not supported yet and refused;
 *   <li>a schema script does not reference an object that a procedure creates, backfills, switches
 *       to or drops (except the first script that creates a dropped object), because new
 *       installations and upgrades order scripts and procedure steps differently (see {@link
 *       MigrationPlan}).
 * </ul>
 *
 * @param scripts schema scripts by number
 * @param procedures procedures by number
 */
record MigrationCatalog(List<SchemaScript> scripts, List<Procedure> procedures) {

  static final Pattern SCRIPT_FILE = Pattern.compile("V(\\d{3})__([a-z0-9_]+)\\.sql");
  static final Pattern ROLLUP_FILE = Pattern.compile("[a-z][a-z0-9_]*\\.sql");
  static final Pattern PROCEDURE_FILE = Pattern.compile("M(\\d{3})__([a-z0-9_]+)\\.yaml");

  private static final String NAME = "\\{db}\\.\\{p}([A-Za-z_][A-Za-z0-9_]*)";
  private static final Pattern OBJECT = Pattern.compile(NAME);
  private static final Pattern CREATE_OBJECT =
      Pattern.compile(
          "(?i)CREATE\\s+(?:TABLE|MATERIALIZED\\s+VIEW|VIEW|DICTIONARY)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
              + NAME);
  private static final Pattern DROP_OBJECT =
      Pattern.compile("(?i)DROP\\s+(?:TABLE|VIEW|DICTIONARY)\\s+(?:IF\\s+EXISTS\\s+)?" + NAME);

  MigrationCatalog {
    scripts = List.copyOf(scripts);
    procedures = List.copyOf(procedures);
  }

  /**
   * Reads and checks every file of {@code source}.
   *
   * @throws IllegalArgumentException naming the file and the problem
   */
  static MigrationCatalog load(MigrationSource source, SchemaTemplates templates) {
    for (String name : source.names("rollups")) {
      if (!ROLLUP_FILE.matcher(name).matches()) {
        throw new IllegalArgumentException(
            source.origin() + ": unexpected file rollups/" + name + " (expected <table>.sql)");
      }
    }
    Map<Integer, String> scriptFiles =
        numbered(source, "schema", SCRIPT_FILE, "V<NNN>__<desc>.sql");
    List<SchemaScript> scripts = new ArrayList<>();
    for (Map.Entry<Integer, String> e : scriptFiles.entrySet()) {
      String path = "schema/" + e.getValue();
      Matcher m = SCRIPT_FILE.matcher(e.getValue());
      if (!m.matches()) {
        throw new IllegalStateException(e.getValue());
      }
      String expanded = templates.expandFile(path);
      scripts.add(
          new SchemaScript(
              e.getKey(),
              "V" + m.group(1),
              m.group(2),
              path,
              expanded,
              SchemaTemplates.checksum(expanded)));
    }
    Map<Integer, String> procedureFiles =
        numbered(source, "migrations", PROCEDURE_FILE, "M<NNN>__<desc>.yaml");
    ProcedureParser parser = new ProcedureParser(templates);
    List<Procedure> procedures = new ArrayList<>();
    for (Map.Entry<Integer, String> e : procedureFiles.entrySet()) {
      String path = "migrations/" + e.getValue();
      Matcher m = PROCEDURE_FILE.matcher(e.getValue());
      if (!m.matches()) {
        throw new IllegalStateException(e.getValue());
      }
      String text = source.read(path).orElseThrow();
      procedures.add(parser.parse(e.getKey(), "M" + m.group(1), path, text));
    }
    if (scripts.isEmpty()) {
      throw new IllegalArgumentException(source.origin() + ": no schema/V001__*.sql");
    }
    checkScriptsAvoidProcedureObjects(source, scripts, procedures);
    return new MigrationCatalog(scripts, procedures);
  }

  /**
   * The ordering rule between schema scripts and procedures: a {@code V<NNN>} script does not
   * reference an object that a procedure creates (a {@code CREATE} of a {@code ddl} step),
   * backfills or switches to, nor an object that a procedure drops (a {@code drop} step); the only
   * exception is the first script that creates a dropped object (the old table of a 4-step
   * replacement).
   *
   * <p>Why: an empty database applies every pending script before every pending procedure step
   * ({@link MigrationPlan}), while an upgraded installation has applied the procedures of earlier
   * releases before the scripts of later ones. A script that touches such an object would therefore
   * run in a different order on the two paths that 13 §13.2 requires to give the same result (for
   * example {@code V002} altering the {@code _v2} table of {@code M001}: after {@code M001} on an
   * upgrade, before it, on a missing table, on a new installation). A procedure object is changed
   * by a {@code ddl} step of a later procedure instead ({@code ADD COLUMN IF NOT EXISTS}).
   *
   * <p>The check reads the templates after the {@code {rollup:}} expansion and before the
   * environment placeholders: an object is a {@code {db}.{p}<name>} in SQL code (comments and
   * quoted text are ignored).
   *
   * @throws IllegalArgumentException naming each script and object
   */
  private static void checkScriptsAvoidProcedureObjects(
      MigrationSource source, List<SchemaScript> scripts, List<Procedure> procedures) {
    Map<String, List<String>> procedureObjects = new TreeMap<>();
    Map<String, Boolean> onlyDropped = new TreeMap<>();
    for (Procedure procedure : procedures) {
      for (Procedure.Step step : procedure.steps()) {
        switch (step.kind()) {
          case DDL -> {
            for (String statement : step.statements()) {
              String created = createdObject(statement);
              if (created != null) {
                addProcedureObject(procedureObjects, onlyDropped, created, step, "creates", false);
              }
            }
          }
          case DROP -> {
            for (String statement : step.statements()) {
              String dropped = droppedObject(statement);
              if (dropped != null) {
                addProcedureObject(procedureObjects, onlyDropped, dropped, step, "drops", true);
              }
            }
          }
          case BACKFILL ->
              addProcedureObject(
                  procedureObjects, onlyDropped, step.table(), step, "backfills", false);
          case SWITCH ->
              addProcedureObject(
                  procedureObjects, onlyDropped, step.table(), step, "switches to", false);
        }
      }
    }
    if (procedureObjects.isEmpty()) {
      return;
    }
    Map<String, String> firstCreator = new TreeMap<>();
    for (SchemaScript script : scripts) {
      for (String statement : SqlScript.split(script.expanded())) {
        String created = createdObject(statement);
        if (created != null) {
          firstCreator.putIfAbsent(created, script.version());
        }
      }
    }
    List<String> problems = new ArrayList<>();
    for (SchemaScript script : scripts) {
      for (String object : objects(script.expanded())) {
        List<String> reasons = procedureObjects.get(object);
        if (reasons == null) {
          continue;
        }
        if (onlyDropped.get(object) && script.version().equals(firstCreator.get(object))) {
          continue; // the old object of a replacement, created before the procedure drops it
        }
        problems.add(
            script.path() + " references " + object + " (" + String.join(", ", reasons) + ")");
      }
    }
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(
          source.origin()
              + ": schema scripts must not reference an object that a procedure creates, backfills,"
              + " switches to or drops: "
              + String.join("; ", problems)
              + ". A new installation runs every V script before every M procedure while an"
              + " upgrade has run the procedures of earlier releases first, so such a script would"
              + " run in a different order on the two paths (13 §13.2). Change a procedure's object"
              + " in a ddl step of a new procedure instead (ADD COLUMN IF NOT EXISTS)");
    }
  }

  private static void addProcedureObject(
      Map<String, List<String>> objects,
      Map<String, Boolean> onlyDropped,
      String object,
      Procedure.Step step,
      String verb,
      boolean drop) {
    objects
        .computeIfAbsent(object, k -> new ArrayList<>())
        .add(step.version() + " " + verb + " it");
    onlyDropped.merge(object, drop, Boolean::logicalAnd);
  }

  /** The object of a {@code CREATE ... {db}.{p}<name>} statement, or null. */
  private static String createdObject(String statement) {
    Matcher m = CREATE_OBJECT.matcher(SqlScript.withoutComments(statement).strip());
    return m.lookingAt() ? m.group(1) : null;
  }

  /** The object of a {@code DROP ... {db}.{p}<name>} statement, or null. */
  private static String droppedObject(String statement) {
    Matcher m = DROP_OBJECT.matcher(SqlScript.withoutComments(statement).strip());
    return m.lookingAt() ? m.group(1) : null;
  }

  /** Every {@code {db}.{p}<name>} in SQL code (not in comments or quoted text). */
  static Set<String> objects(String template) {
    Set<String> objects = new TreeSet<>();
    for (SqlScript.Segment segment : SqlScript.lex(template)) {
      if (segment.kind() == SqlScript.Kind.CODE) {
        Matcher m = OBJECT.matcher(segment.text());
        while (m.find()) {
          objects.add(m.group(1));
        }
      }
    }
    return objects;
  }

  private static Map<Integer, String> numbered(
      MigrationSource source, String directory, Pattern pattern, String expected) {
    Map<Integer, String> byNumber = new TreeMap<>();
    for (String name : source.names(directory)) {
      Matcher m = pattern.matcher(name);
      if (!m.matches()) {
        throw new IllegalArgumentException(
            source.origin()
                + ": unexpected file "
                + directory
                + "/"
                + name
                + " (expected "
                + expected
                + ")");
      }
      int number = Integer.parseInt(m.group(1));
      String previous = byNumber.put(number, name);
      if (previous != null) {
        throw new IllegalArgumentException(
            source.origin()
                + ": "
                + directory
                + "/"
                + previous
                + " and "
                + name
                + " share a number");
      }
    }
    int expectedNumber = 1;
    for (Map.Entry<Integer, String> e : byNumber.entrySet()) {
      if (e.getKey() != expectedNumber) {
        throw new IllegalArgumentException(
            source.origin()
                + ": "
                + directory
                + " numbers must be contiguous from 001; "
                + e.getValue()
                + " follows number "
                + (expectedNumber - 1));
      }
      expectedNumber++;
    }
    return byNumber;
  }

  /** Every step of every procedure, in execution order. */
  List<Procedure.Step> steps() {
    List<Procedure.Step> steps = new ArrayList<>();
    for (Procedure procedure : procedures) {
      steps.addAll(procedure.steps());
    }
    return steps;
  }
}
