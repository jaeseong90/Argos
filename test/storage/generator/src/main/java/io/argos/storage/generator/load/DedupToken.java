/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.load;

import io.argos.storage.generator.GeneratorConfig;
import io.argos.storage.generator.model.Hash;
import io.argos.storage.generator.rows.Table;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The deduplication token contract of 13 §5.1 and C-2: every insert carries {@code
 * insert_deduplication_token = argos:<table>:<block_id>} and {@code
 * deduplicate_blocks_in_dependent_materialized_views = 1}; a block is always re-sent with exactly
 * the same token and bytes. Block ids are unique within a run and include a run id (bulk) or a WAL
 * id (replay), so two runs never share a token.
 */
public final class DedupToken {

  /**
   * Version of the data model; part of the derived run id so that a changed generator never reuses
   * the tokens of rows it generated differently before.
   */
  public static final int MODEL_VERSION = 1;

  /** Accepted token shape. */
  public static final Pattern FORMAT =
      Pattern.compile("argos:(xlog|profile_step|metric|sql_stat_raw|ext_stat_raw):[A-Za-z0-9-]+");

  private DedupToken() {}

  /** {@code argos:<table>:<blockId>}. */
  public static String of(Table table, String blockId) {
    return "argos:" + table.name() + ":" + blockId;
  }

  /** Block id of bulk block {@code sequence} of a run. */
  public static String bulkBlockId(String runId, long sequence) {
    return String.format(Locale.ROOT, "bulk-%s-%08d", runId, sequence);
  }

  /** Keys that do not change the generated rows (excluded from the derived run id). */
  private static final java.util.Set<String> NON_DATA_KEYS =
      java.util.Set.of(
          "run-id",
          "target",
          "database",
          "table-prefix",
          "apply-schema",
          "docker-memory",
          "docker-volume",
          "out",
          "machine-profile",
          "monitor-interval-s");

  /**
   * The run id: {@code run-id} when set, otherwise 12 hex digits derived from the start and every
   * parameter that shapes the rows (same configuration and start: same tokens, so a repeated
   * identical load is deduplicated within the window; any other configuration gets other tokens).
   */
  public static String runId(GeneratorConfig config, long startMs) {
    if (!config.runId.isEmpty()) {
      if (!config.runId.matches("[A-Za-z0-9]{1,32}")) {
        throw new IllegalArgumentException("run-id must be 1-32 letters or digits");
      }
      return config.runId;
    }
    long h = Hash.of(config.seed, startMs, MODEL_VERSION);
    for (java.util.Map.Entry<String, String> e : config.values().entrySet()) {
      if (!NON_DATA_KEYS.contains(e.getKey())) {
        h = Hash.of(h, e.getKey() + "=" + e.getValue());
      }
    }
    return String.format(Locale.ROOT, "%012x", h >>> 16);
  }
}
