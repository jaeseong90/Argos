/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import io.argos.server.storage.clickhouse.RollupPartitions;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ClickHouse migration runner ({@code MigrationRunner} of 12 §1 and §9.3; 13 §13.2; ADR-0007
 * C-56). It runs inside the {@code CLICKHOUSE_MIGRATION} start phase with the migration account
 * {@code argos_migrator} and keeps its history in PostgreSQL {@code argos.ch_schema_history}.
 *
 * <ol>
 *   <li>Read and check every bundled file ({@link MigrationCatalog}): names, numbers, placeholders
 *       (only {@code {db}}, {@code {p}}, {@code {retention.*}}, {@code {rollup:<table>}}; {@code
 *       {where}} in rollup files), procedure format, checksums (C-56), and that no schema script
 *       references an object of a procedure (new installations and upgrades order them differently,
 *       {@link MigrationPlan}).
 *   <li>Take the migration lock (PostgreSQL advisory lock) and read the history. When the
 *       ClickHouse database does not exist although history rows do (ClickHouse reset or replaced,
 *       13 §10), the history is ignored and the schema is created again.
 *   <li>Refuse the start for: a successful history version this server does not bundle (newer
 *       server), a pending version before an applied one, and a changed checksum of an applied
 *       script or step (naming the version), unless {@code repairChecksums} is set: then each
 *       changed checksum is logged as a WARN, re-recorded (scripts are not run again) and one
 *       {@code repair.<timestamp>} history row lists the versions.
 *   <li>Render and lint every pending script and procedure statement before running anything
 *       ({@link SchemaLint}: idempotent DDL only, no {@code MODIFY ORDER BY}, no {@code MODIFY
 *       COLUMN} type change, {@code MODIFY TTL} only with {@code materialize_ttl_after_modify = 0},
 *       no {@code RENAME} statement, {@code RENAME COLUMN} only with {@code IF EXISTS}).
 *   <li>Run the pending scripts in number order, one statement per request, each limited to the
 *       statement timeout, with one progress line per statement; then the pending procedure steps
 *       ({@code ddl}, {@code backfill} per partition with progress rows, {@code switch} of {@code
 *       settings.rollup_alias}, {@code drop}). A history row is written after each script and step;
 *       a failure writes a failed row (retried at the next start) and stops the start.
 * </ol>
 *
 * Retention values are rendered when a table is created; existing tables keep their TTL (changing
 * it is WO-28, ADR-0010 C-99).
 */
public final class ClickHouseMigrationRunner {

  /** Statement timeout (12 §9.3). */
  public static final Duration STATEMENT_TIMEOUT = Duration.ofMinutes(10);

  /** Suffix of the staging table of a backfill ({@code <table>_backfill}). */
  static final String STAGING_SUFFIX = "_backfill";

  private static final Logger LOG = LoggerFactory.getLogger(ClickHouseMigrationRunner.class);
  private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.BASIC_ISO_DATE;
  private static final DateTimeFormatter MONTH_KEY =
      DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT);
  private static final DateTimeFormatter REPAIR_KEY =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);
  private static final Pattern DAY_KEY_PATTERN = Pattern.compile("\\d{8}");
  private static final Pattern MONTH_KEY_PATTERN = Pattern.compile("\\d{6}");
  private static final Pattern SOURCE_TABLE =
      Pattern.compile("(?i)\\bFROM\\s+\\{db}\\.\\{p}([a-z][a-z0-9_]*)");

  private final MigrationSource source;
  private final SchemaParameters params;
  private final ClickHouseStatements clickHouse;
  private final MigrationStore store;
  private final Clock clock;
  private final boolean repairChecksums;
  private final SchemaTemplates templates;

  /**
   * Creates a runner.
   *
   * @param source bundled migration files
   * @param params database, prefix, retention
   * @param clickHouse statements of the migration account
   * @param store history and settings in PostgreSQL (schema owner)
   * @param clock UTC clock (backfill day range, repair row time)
   * @param repairChecksums {@code argos.clickhouse.migration.repair-checksums}
   */
  ClickHouseMigrationRunner(
      MigrationSource source,
      SchemaParameters params,
      ClickHouseStatements clickHouse,
      MigrationStore store,
      Clock clock,
      boolean repairChecksums) {
    this.source = source;
    this.params = params;
    this.clickHouse = clickHouse;
    this.store = store;
    this.clock = clock;
    this.repairChecksums = repairChecksums;
    this.templates = new SchemaTemplates(source);
  }

  /**
   * What one run did.
   *
   * @param applied scripts, steps and backfill partitions applied, in order
   * @param repaired versions whose checksum was re-recorded
   * @param recreated true when the history was ignored because the database was missing
   * @param durationMs duration of the whole run
   */
  public record Result(
      List<Applied> applied, List<String> repaired, boolean recreated, long durationMs) {

    public Result {
      applied = List.copyOf(applied);
      repaired = List.copyOf(repaired);
    }
  }

  /**
   * One applied script, step or backfill partition.
   *
   * @param version history version
   * @param description description
   * @param statements number of ClickHouse statements
   * @param durationMs duration
   */
  public record Applied(String version, String description, int statements, long durationMs) {}

  /**
   * Runs the migration.
   *
   * @throws MigrationException when the start must be refused (already rolled back to a failed
   *     history row where something was attempted)
   */
  public Result run() throws MigrationException {
    long start = System.nanoTime();
    MigrationCatalog catalog;
    try {
      catalog = MigrationCatalog.load(source, templates);
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new MigrationException("ClickHouse migration files are invalid: " + e.getMessage(), e);
    }
    store.lock();
    try {
      List<HistoryEntry> history = store.history();
      boolean recreate = !history.isEmpty() && !databaseExists();
      if (recreate) {
        LOG.warn(
            "ClickHouse database {} does not exist although ch_schema_history has {} rows:"
                + " ClickHouse was reset or replaced (13 §10); the schema is created again",
            params.database(),
            history.size());
      }
      MigrationPlan plan = MigrationPlan.of(catalog, history, recreate);
      refuseIfInvalid(plan);
      if (!repairChecksums) {
        refuseIfChanged(plan);
      }
      List<Prepared> prepared = prepare(plan); // refuses before anything is written
      List<String> repaired = repair(plan);
      List<Applied> applied = new ArrayList<>();
      if (plan.upToDate()) {
        LOG.info(
            "ClickHouse schema of database {} is up to date ({} scripts, {} procedure steps applied)",
            params.database(),
            catalog.scripts().size(),
            catalog.steps().size());
      } else {
        LOG.info(
            "ClickHouse migration of database {} (prefix '{}'): {} scripts and {} procedure steps"
                + " to apply",
            params.database(),
            params.tablePrefix(),
            plan.pendingScripts().size(),
            plan.pendingSteps().size());
        for (Prepared p : prepared) {
          applied.addAll(p.apply(plan.completedProgress()));
        }
      }
      return new Result(applied, repaired, recreate, millisSince(start));
    } finally {
      store.unlock();
    }
  }

  private boolean databaseExists() throws MigrationException {
    List<String> rows =
        clickHouse.queryColumn(
            "SELECT toString(count()) FROM system.databases WHERE name = '"
                + params.database()
                + "'");
    return !rows.isEmpty() && !rows.get(0).equals("0");
  }

  private static void refuseIfInvalid(MigrationPlan plan) throws MigrationException {
    if (!plan.unknown().isEmpty()) {
      throw new MigrationException(
          "ch_schema_history records ClickHouse schema versions that this server does not bundle: "
              + plan.unknown()
              + ". A newer server applied them; start that version (or follow the rollback"
              + " procedure of the release notes)");
    }
    if (!plan.outOfOrder().isEmpty()) {
      throw new MigrationException(
          "ClickHouse schema versions "
              + plan.outOfOrder()
              + " are not applied although later versions are; versions are applied in order"
              + " only");
    }
  }

  private static void refuseIfChanged(MigrationPlan plan) throws MigrationException {
    if (!plan.mismatches().isEmpty()) {
      MigrationPlan.Mismatch first = plan.mismatches().get(0);
      throw new MigrationException(
          "ClickHouse schema "
              + first.version()
              + " was applied with checksum "
              + abbreviate(first.recorded())
              + " but the bundled file has "
              + abbreviate(first.bundled())
              + (plan.mismatches().size() > 1
                  ? " (also changed: "
                      + versions(plan.mismatches().subList(1, plan.mismatches().size()))
                      + ")"
                  : "")
              + ": an applied migration file was changed. Restore the released file, or after"
              + " review start once with argos.clickhouse.migration.repair-checksums=true");
    }
  }

  private List<String> repair(MigrationPlan plan) throws MigrationException {
    if (plan.mismatches().isEmpty()) {
      if (repairChecksums) {
        LOG.warn(
            "argos.clickhouse.migration.repair-checksums is set but no checksum differs;"
                + " remove the setting (it is meant for one start)");
      }
      return List.of();
    }
    long start = System.nanoTime();
    List<String> repaired = new ArrayList<>();
    StringBuilder canonical = new StringBuilder();
    for (MigrationPlan.Mismatch m : plan.mismatches()) {
      LOG.warn(
          "Repairing the checksum of ClickHouse schema {}: recorded {}, bundled {}; the script is"
              + " not run again (argos.clickhouse.migration.repair-checksums)",
          m.version(),
          m.recorded(),
          m.bundled());
      store.updateChecksum(m.version(), m.bundled());
      repaired.add(m.version());
      canonical.append(m.version()).append('=').append(m.bundled()).append('\n');
    }
    String version = "repair." + REPAIR_KEY.format(LocalDateTime.now(clock));
    store.record(
        version,
        "checksums re-recorded: " + String.join(", ", repaired),
        SchemaTemplates.checksum(canonical.toString()),
        millisSince(start),
        true,
        null);
    return repaired;
  }

  private static String versions(List<MigrationPlan.Mismatch> mismatches) {
    List<String> versions = new ArrayList<>();
    for (MigrationPlan.Mismatch m : mismatches) {
      versions.add(m.version());
    }
    return String.join(", ", versions);
  }

  private static String abbreviate(String checksum) {
    return checksum == null || checksum.length() < 12
        ? String.valueOf(checksum)
        : checksum.substring(0, 12) + "...";
  }

  // --- preparation: render and lint everything before running anything ------------------------

  private List<Prepared> prepare(MigrationPlan plan) throws MigrationException {
    List<Prepared> prepared = new ArrayList<>();
    List<String> violations = new ArrayList<>();
    for (SchemaScript script : plan.pendingScripts()) {
      List<String> statements = TemplatesRendering.render(script.expanded(), params, script.path());
      for (String v : SchemaLint.violations(statements)) {
        violations.add(script.path() + " " + v);
      }
      prepared.add(new ScriptRun(script, statements));
    }
    for (Procedure.Step step : plan.pendingSteps()) {
      switch (step.kind()) {
        case DDL, DROP -> {
          List<String> statements = new ArrayList<>();
          for (String statement : step.statements()) {
            statements.addAll(TemplatesRendering.render(statement, params, step.version()));
          }
          for (String v : SchemaLint.violations(statements)) {
            violations.add(step.version() + " " + v);
          }
          prepared.add(new StatementStepRun(step, statements));
        }
        case BACKFILL -> prepared.add(new BackfillRun(step, days(step)));
        case SWITCH -> prepared.add(new SwitchRun(step));
      }
    }
    if (!violations.isEmpty()) {
      throw new MigrationException(
          "ClickHouse migration refused, nothing was applied (13 §13.2 idempotency and ALTER"
              + " rules): "
              + String.join("; ", violations));
    }
    return prepared;
  }

  private int days(Procedure.Step step) {
    String days = step.days();
    if (days.startsWith("{retention.")) {
      return params.retention(days.substring("{retention.".length(), days.length() - 1));
    }
    return Integer.parseInt(days);
  }

  /** Rendering with the operator-facing error of a failed substitution. */
  private static final class TemplatesRendering {
    private TemplatesRendering() {}

    static List<String> render(String expanded, SchemaParameters params, String origin)
        throws MigrationException {
      try {
        return SchemaTemplates.render(expanded, params);
      } catch (IllegalArgumentException e) {
        throw new MigrationException(origin + ": " + e.getMessage(), e);
      }
    }
  }

  // --- execution ---------------------------------------------------------------------------------

  /** One pending script or step, rendered and checked. */
  private interface Prepared {
    List<Applied> apply(Set<String> completedProgress) throws MigrationException;
  }

  private final class ScriptRun implements Prepared {
    private final SchemaScript script;
    private final List<String> statements;

    ScriptRun(SchemaScript script, List<String> statements) {
      this.script = script;
      this.statements = statements;
    }

    @Override
    public List<Applied> apply(Set<String> completedProgress) throws MigrationException {
      long start = System.nanoTime();
      LOG.info(
          "ClickHouse migration {} ({}): {} statements",
          script.version(),
          script.description(),
          statements.size());
      executeAll(script.version(), script.description(), script.checksum(), statements, start);
      long ms = millisSince(start);
      store.record(script.version(), script.description(), script.checksum(), ms, true, null);
      LOG.info("ClickHouse migration {} applied in {} ms", script.version(), ms);
      return List.of(new Applied(script.version(), script.description(), statements.size(), ms));
    }
  }

  private final class StatementStepRun implements Prepared {
    private final Procedure.Step step;
    private final List<String> statements;

    StatementStepRun(Procedure.Step step, List<String> statements) {
      this.step = step;
      this.statements = statements;
    }

    @Override
    public List<Applied> apply(Set<String> completedProgress) throws MigrationException {
      long start = System.nanoTime();
      String description = description(step);
      LOG.info("ClickHouse procedure step {}: {} statements", step, statements.size());
      executeAll(step.version(), description, step.checksum(), statements, start);
      long ms = millisSince(start);
      store.record(step.version(), description, step.checksum(), ms, true, null);
      LOG.info("ClickHouse procedure step {} applied in {} ms", step.version(), ms);
      return List.of(new Applied(step.version(), description, statements.size(), ms));
    }
  }

  private final class SwitchRun implements Prepared {
    private final Procedure.Step step;

    SwitchRun(Procedure.Step step) {
      this.step = step;
    }

    @Override
    public List<Applied> apply(Set<String> completedProgress) throws MigrationException {
      long start = System.nanoTime();
      String description = description(step);
      try {
        List<String> exists =
            clickHouse.queryColumn(
                "SELECT toString(count()) FROM system.tables WHERE database = '"
                    + params.database()
                    + "' AND name = '"
                    + params.tablePrefix()
                    + step.table()
                    + "'");
        if (exists.isEmpty() || exists.get(0).equals("0")) {
          throw new MigrationException(
              step.version() + ": switch target " + params.table(step.table()) + " does not exist");
        }
        store.setRollupAlias(step.alias(), step.table());
      } catch (MigrationException e) {
        recordFailure(step.version(), description, step.checksum(), start, e);
        throw e;
      }
      long ms = millisSince(start);
      store.record(step.version(), description, step.checksum(), ms, true, null);
      LOG.info(
          "ClickHouse procedure step {}: queries of {} now read {} (settings.rollup_alias), {} ms",
          step.version(),
          step.alias(),
          step.table(),
          ms);
      return List.of(new Applied(step.version(), description, 0, ms));
    }
  }

  /**
   * Backfill of a rollup table, one partition at a time, through a staging table and {@code REPLACE
   * PARTITION} (the rebuild procedure of ADR-0007 C-59): nothing is inserted into the rollup table
   * itself, so its materialized view and later cascades never count a row twice. The runner runs
   * before WAL recovery and before any listener, so the source is quiescent during the backfill.
   * Partitions without source rows are left untouched (the source may already be past its retention
   * while the rollup is not).
   */
  private final class BackfillRun implements Prepared {
    private final Procedure.Step step;
    private final int days;

    BackfillRun(Procedure.Step step, int days) {
      this.step = step;
      this.days = days;
    }

    @Override
    public List<Applied> apply(Set<String> completedProgress) throws MigrationException {
      long start = System.nanoTime();
      String description = description(step);
      boolean monthly = step.unit().equals("month");
      LocalDate today = LocalDate.now(clock);
      LocalDate first = today.minusDays(days - 1L);
      List<String> partitions;
      try {
        partitions = partitionsWithData(monthly, first, today);
      } catch (MigrationException e) {
        recordFailure(step.version(), description, step.checksum(), start, e);
        throw e;
      }
      LOG.info(
          "ClickHouse procedure step {}: backfill {} from {} to {}, {} {} with source rows",
          step.version(),
          params.table(step.table()),
          first,
          today,
          partitions.size(),
          monthly ? "months" : "days");
      List<Applied> applied = new ArrayList<>();
      int statements = 0;
      int done = 0;
      for (String key : partitions) {
        done++;
        String progressVersion = step.version() + "/" + key;
        if (completedProgress.contains(progressVersion)) {
          LOG.info("ClickHouse procedure step {}: {} already done", step.version(), key);
          continue;
        }
        long partStart = System.nanoTime();
        RollupPartitions.Partition partition = RollupPartitions.Partition.ofKey(monthly, key);
        String partDescription = "backfill " + step.table() + " " + key;
        long rows;
        try {
          rows =
              RollupPartitions.replace(
                      clickHouse,
                      params.table(step.table()),
                      staging(),
                      partition,
                      templates.rollupSelect(
                          step.table(), params, "AND (" + partition.sourcePredicate() + ")"),
                      RollupPartitions.EmptyStaging.KEEP)
                  .stagingRows();
        } catch (MigrationException e) {
          recordFailure(progressVersion, partDescription, step.checksum(), partStart, e);
          recordFailure(step.version(), description, step.checksum(), start, e);
          throw new MigrationException(
              step.version() + " backfill of " + key + " failed: " + e.getMessage(), e);
        }
        long ms = millisSince(partStart);
        statements += rows > 0 ? 6 : 5;
        store.record(
            progressVersion,
            partDescription + ": " + rows + " rows",
            step.checksum(),
            ms,
            true,
            null);
        LOG.info(
            "ClickHouse procedure step {}: {} ({}/{}) {} rows in {} ms",
            step.version(),
            key,
            done,
            partitions.size(),
            rows,
            ms);
        applied.add(new Applied(progressVersion, partDescription, rows > 0 ? 6 : 5, ms));
      }
      long ms = millisSince(start);
      store.record(step.version(), description, step.checksum(), ms, true, null);
      LOG.info("ClickHouse procedure step {} applied in {} ms", step.version(), ms);
      applied.add(new Applied(step.version(), description, statements, ms));
      return applied;
    }

    /** Partition keys ({@code yyyyMMdd} or {@code yyyyMM}) in the range that have source rows. */
    private List<String> partitionsWithData(boolean monthly, LocalDate first, LocalDate last)
        throws MigrationException {
      Matcher m = SOURCE_TABLE.matcher(templates.rollupTemplate(step.table()));
      Set<String> keys = new TreeSet<>();
      if (!m.find()) {
        // No recognisable source: every partition of the range.
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
          keys.add(monthly ? MONTH_KEY.format(d) : DAY_KEY.format(d));
        }
        return new ArrayList<>(keys);
      }
      String sourceTable = params.table(m.group(1));
      String query =
          monthly
              ? "SELECT DISTINCT toString(toYYYYMM(minute)) FROM "
                  + sourceTable
                  + " WHERE toDate(minute) BETWEEN '"
                  + first
                  + "' AND '"
                  + last
                  + "'"
              : "SELECT DISTINCT toString(toYYYYMMDD(ts)) FROM "
                  + sourceTable
                  + " WHERE toDate(ts) BETWEEN '"
                  + first
                  + "' AND '"
                  + last
                  + "'";
      for (String key : clickHouse.queryColumn(query)) {
        if (!(monthly ? MONTH_KEY_PATTERN : DAY_KEY_PATTERN).matcher(key).matches()) {
          throw new MigrationException(
              step.version() + ": unexpected partition key '" + key + "' from " + sourceTable);
        }
        keys.add(key);
      }
      return new ArrayList<>(keys);
    }

    /**
     * The staging table of the backfill. {@link RollupPartitions#replace} drops a leftover one,
     * creates and fills it, replaces the partition (skipped when the staging table stays empty: the
     * partition is kept) and drops it.
     */
    private String staging() {
      return params.table(step.table() + STAGING_SUFFIX);
    }
  }

  private void executeAll(
      String version, String description, String checksum, List<String> statements, long start)
      throws MigrationException {
    for (int i = 0; i < statements.size(); i++) {
      String statement = statements.get(i);
      long statementStart = System.nanoTime();
      try {
        clickHouse.execute(statement);
      } catch (MigrationException e) {
        recordFailure(version, description, checksum, start, e);
        throw new MigrationException(
            "ClickHouse migration "
                + version
                + " ("
                + description
                + ") failed at statement "
                + (i + 1)
                + "/"
                + statements.size()
                + " ("
                + SqlScript.summary(statement)
                + "): "
                + e.getMessage(),
            e);
      }
      LOG.info(
          "ClickHouse migration {} statement {}/{} done in {} ms: {}",
          version,
          i + 1,
          statements.size(),
          millisSince(statementStart),
          SqlScript.summary(statement));
    }
  }

  private void recordFailure(
      String version, String description, String checksum, long start, MigrationException e) {
    try {
      store.record(
          version,
          description,
          checksum,
          millisSince(start),
          false,
          MigrationException.oneLine(e, 2000));
    } catch (MigrationException recordError) {
      e.addSuppressed(recordError);
    }
  }

  private static String description(Procedure.Step step) {
    return step.id() + " (" + step.kind().name().toLowerCase(Locale.ROOT) + ")";
  }

  private static long millisSince(long startNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
  }
}
