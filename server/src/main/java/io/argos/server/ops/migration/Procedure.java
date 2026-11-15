/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import java.util.List;

/**
 * A multi-step ClickHouse procedure {@code migrations/M<NNN>__<desc>.yaml} (13 §13.2), e.g. the
 * four-step replacement of a materialized view: create the new table and view ({@link
 * Step.Kind#DDL}), backfill it ({@link Step.Kind#BACKFILL}), switch the queries to it ({@link
 * Step.Kind#SWITCH}) and drop the old objects ({@link Step.Kind#DROP}, only when declared). Every
 * step is one {@code ch_schema_history} row, version {@code M<NNN>.<step id>}.
 *
 * @param number procedure number ({@code NNN})
 * @param version {@code M<NNN>}
 * @param description the {@code description} of the file
 * @param path path in the source
 * @param steps the steps in file order
 */
public record Procedure(
    int number, String version, String description, String path, List<Step> steps) {

  public Procedure {
    steps = List.copyOf(steps);
  }

  /**
   * One step of a procedure.
   *
   * @param procedure version of the procedure ({@code M<NNN>})
   * @param id step id, {@code [a-z][a-z0-9_]{0,15}}
   * @param kind what the step does
   * @param statements {@link Kind#DDL} and {@link Kind#DROP}: one statement per element, expanded
   *     ({@code {rollup:}} included, environment placeholders as written); empty otherwise
   * @param table {@link Kind#BACKFILL}: the rollup table to fill (its SELECT is {@code
   *     rollups/<table>.sql}); {@link Kind#SWITCH}: the physical table the queries use from now on
   * @param alias {@link Kind#SWITCH}: the logical rollup name ({@code settings.rollup_alias} key)
   * @param unit {@link Kind#BACKFILL}: partition unit of {@code table}, {@code day} (1m tables,
   *     source column {@code ts}) or {@code month} (1h tables, source column {@code minute})
   * @param days {@link Kind#BACKFILL}: the most recent days to fill (today included): a number or
   *     {@code {retention.<key>}}
   * @param checksum lower-case hex SHA-256 of the canonical step text ({@link
   *     ProcedureParser#canonical}): statements after the {@code {rollup:}} expansion and before
   *     the environment placeholders (C-56), so another database, prefix or retention keeps it
   */
  public record Step(
      String procedure,
      String id,
      Kind kind,
      List<String> statements,
      String table,
      String alias,
      String unit,
      String days,
      String checksum) {

    public Step {
      statements = List.copyOf(statements);
    }

    /** The history version, {@code M<NNN>.<id>}. */
    public String version() {
      return procedure + "." + id;
    }

    @Override
    public String toString() {
      return version() + " (" + kind.name().toLowerCase(java.util.Locale.ROOT) + ")";
    }

    /** What a step does. */
    public enum Kind {
      /** Idempotent DDL statements (CREATE ... IF NOT EXISTS, ADD COLUMN IF NOT EXISTS). */
      DDL,
      /** Fills a rollup table from its source, one partition at a time, with progress rows. */
      BACKFILL,
      /** Points a logical rollup name at a physical table ({@code settings.rollup_alias}). */
      SWITCH,
      /** Drops objects ({@code DROP ... IF EXISTS}); runs only because it is declared. */
      DROP
    }
  }
}
