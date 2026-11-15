/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;

/**
 * What the runner has to do, computed from the bundled files and {@code ch_schema_history} (pure,
 * no database access).
 *
 * <ul>
 *   <li>{@link #pendingScripts()}: scripts without a successful row (a failed row is retried, the
 *       scripts are idempotent);
 *   <li>{@link #pendingSteps()}: procedure steps without a successful row;
 *   <li>{@link #mismatches()}: applied scripts and steps whose checksum differs from the bundled
 *       file (start refused unless repaired);
 *   <li>{@link #unknown()}: successful rows of versions this server does not bundle (written by a
 *       newer server: start refused) and rows with an unrecognised version;
 *   <li>{@link #outOfOrder()}: a pending script before an applied one, or a pending step before an
 *       applied step (start refused). Scripts and procedure steps are two separate sequences:
 *       pending scripts run first, then pending steps.
 * </ul>
 *
 * <p>Because the two sequences are numbered separately, the order differs between the two paths of
 * 13 §13.2: on an empty database every script runs before every procedure step, while an upgraded
 * installation has run the procedures of earlier releases before the scripts of a later release.
 * Both paths give the same schema only because no script references an object that a procedure
 * creates, backfills, switches to or drops (the first script that creates a dropped object
 * excepted); {@link MigrationCatalog#load} refuses the files otherwise, and such an object is
 * changed by a {@code ddl} step of a later procedure ({@code ADD COLUMN IF NOT EXISTS}).
 *
 * @param pendingScripts scripts to apply, in order
 * @param pendingSteps steps to apply, in order
 * @param mismatches changed checksums, in version order
 * @param unknown unknown versions
 * @param outOfOrder versions that are pending although a later one is applied
 * @param completedProgress successful backfill progress versions ({@code M<NNN>.<step>/<unit>})
 */
record MigrationPlan(
    List<SchemaScript> pendingScripts,
    List<Procedure.Step> pendingSteps,
    List<Mismatch> mismatches,
    List<String> unknown,
    List<String> outOfOrder,
    Set<String> completedProgress) {

  /**
   * An applied version whose recorded checksum differs from the bundled one.
   *
   * @param version script or step version
   * @param recorded checksum in {@code ch_schema_history}
   * @param bundled checksum of the bundled file
   */
  record Mismatch(String version, String recorded, String bundled) {}

  MigrationPlan {
    pendingScripts = List.copyOf(pendingScripts);
    pendingSteps = List.copyOf(pendingSteps);
    mismatches = List.copyOf(mismatches);
    unknown = List.copyOf(unknown);
    outOfOrder = List.copyOf(outOfOrder);
    completedProgress = Set.copyOf(completedProgress);
  }

  /**
   * The plan for {@code catalog} against {@code history}.
   *
   * @param ignoreHistory true when the ClickHouse database does not exist although history rows do
   *     (ClickHouse was reset, 13 §10): every script and step is pending again
   */
  static MigrationPlan of(
      MigrationCatalog catalog, List<HistoryEntry> history, boolean ignoreHistory) {
    Map<String, String> bundled = new LinkedHashMap<>();
    for (SchemaScript script : catalog.scripts()) {
      bundled.put(script.version(), script.checksum());
    }
    for (Procedure.Step step : catalog.steps()) {
      bundled.put(step.version(), step.checksum());
    }
    Map<String, HistoryEntry> applied = new HashMap<>();
    Map<String, String> mismatched = new LinkedHashMap<>();
    Set<String> unknown = new TreeSet<>();
    Set<String> progress = new TreeSet<>();
    Map<String, String> recordedOfMismatch = new HashMap<>();
    if (!ignoreHistory) {
      for (HistoryEntry entry : history) {
        String version = entry.version();
        if (HistoryEntry.REPAIR.matcher(version).matches()) {
          continue;
        }
        Matcher progressMatcher = HistoryEntry.PROGRESS.matcher(version);
        String owner = progressMatcher.matches() ? progressMatcher.group(1) : version;
        boolean known =
            HistoryEntry.SCRIPT.matcher(owner).matches()
                || HistoryEntry.STEP.matcher(owner).matches();
        if (!known) {
          unknown.add(version);
          continue;
        }
        String expected = bundled.get(owner);
        if (expected == null) {
          if (entry.success()) {
            unknown.add(version);
          }
          continue;
        }
        if (!entry.success()) {
          continue;
        }
        if (!expected.equals(entry.checksum())) {
          mismatched.put(owner, expected);
          recordedOfMismatch.putIfAbsent(owner, entry.checksum());
        }
        if (progressMatcher.matches()) {
          progress.add(version);
        } else {
          applied.put(version, entry);
        }
      }
    }
    List<SchemaScript> pendingScripts = new ArrayList<>();
    List<String> outOfOrder = new ArrayList<>();
    boolean laterApplied = false;
    List<SchemaScript> scripts = catalog.scripts();
    for (int i = scripts.size() - 1; i >= 0; i--) {
      SchemaScript script = scripts.get(i);
      if (applied.containsKey(script.version())) {
        laterApplied = true;
      } else {
        pendingScripts.add(0, script);
        if (laterApplied) {
          outOfOrder.add(0, script.version());
        }
      }
    }
    List<Procedure.Step> pendingSteps = new ArrayList<>();
    List<String> stepsOutOfOrder = new ArrayList<>();
    laterApplied = false;
    List<Procedure.Step> steps = catalog.steps();
    for (int i = steps.size() - 1; i >= 0; i--) {
      Procedure.Step step = steps.get(i);
      if (applied.containsKey(step.version())) {
        laterApplied = true;
      } else {
        pendingSteps.add(0, step);
        if (laterApplied) {
          stepsOutOfOrder.add(0, step.version());
        }
      }
    }
    outOfOrder.addAll(stepsOutOfOrder);
    List<Mismatch> mismatches = new ArrayList<>();
    for (Map.Entry<String, String> e : mismatched.entrySet()) {
      mismatches.add(new Mismatch(e.getKey(), recordedOfMismatch.get(e.getKey()), e.getValue()));
    }
    return new MigrationPlan(
        pendingScripts, pendingSteps, mismatches, new ArrayList<>(unknown), outOfOrder, progress);
  }

  /** True when there is nothing to apply. */
  boolean upToDate() {
    return pendingScripts.isEmpty() && pendingSteps.isEmpty();
  }
}
