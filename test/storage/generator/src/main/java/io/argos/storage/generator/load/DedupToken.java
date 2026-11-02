/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.load;

import io.argos.storage.generator.GeneratorConfig;
import io.argos.storage.generator.model.Hash;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The deduplication token contract of 13 §5.1 and ADR-0007 C-61 (which corrects C-2): every insert
 * carries {@code insert_deduplication_token = <wal_instance_id>:<epoch>:<first>-<last>} and {@code
 * deduplicate_blocks_in_dependent_materialized_views = 1}. The token is a deterministic value
 * written to the BLOCK_INTENT record before the insert: the WAL instance id, the WAL epoch
 * (incremented when the WAL is re-initialized) and the positions of the first and last data record
 * of the block (lower-case hex). A block is always re-sent with exactly the same token and bytes,
 * and the writer never judges a duplicate by {@code X-ClickHouse-Summary.written_rows} (ClickHouse
 * reports the rows of a deduplicated re-send too). Replay tokens use the simulated WAL id; bulk
 * tokens use {@code bulk-<run id>} as instance id, epoch 1 and the row positions of the run, so two
 * runs never share a token.
 */
public final class DedupToken {

  /**
   * Version of the data model; part of the derived run id so that a changed generator never reuses
   * the tokens of rows it generated differently before.
   */
  public static final int MODEL_VERSION = 1;

  /** Accepted token shape: {@code <wal_instance_id>:<epoch>:<first>-<last>} (C-61). */
  public static final Pattern FORMAT =
      Pattern.compile("[A-Za-z0-9-]{1,40}:[1-9][0-9]*:[0-9a-f]+-[0-9a-f]+");

  private DedupToken() {}

  /**
   * {@code <wal_instance_id>:<epoch>:<first>-<last>}.
   *
   * @param walInstanceId WAL instance id (letters, digits, dash)
   * @param epoch WAL epoch, at least 1
   * @param first position of the first data record of the block
   * @param last position of the last data record of the block, not below first
   */
  public static String of(String walInstanceId, long epoch, long first, long last) {
    if (epoch < 1 || Long.compareUnsigned(first, last) > 0) {
      throw new IllegalArgumentException(
          "Invalid token range " + walInstanceId + ":" + epoch + ":" + first + "-" + last);
    }
    String token =
        String.format(
            Locale.ROOT,
            "%s:%d:%s-%s",
            walInstanceId,
            epoch,
            Long.toHexString(first),
            Long.toHexString(last));
    if (!FORMAT.matcher(token).matches()) {
      throw new IllegalArgumentException("Invalid WAL instance id: " + walInstanceId);
    }
    return token;
  }

  /** WAL instance id of a bulk run (no WAL: the rows of the run are numbered instead). */
  public static String bulkInstance(String runId) {
    return "bulk-" + runId;
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
