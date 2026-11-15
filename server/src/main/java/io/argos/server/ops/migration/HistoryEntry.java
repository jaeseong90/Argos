/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * One row of PostgreSQL {@code argos.ch_schema_history} (13 §12).
 *
 * <p>Versions written by the runner:
 *
 * <ul>
 *   <li>{@code V<NNN>}: a schema script;
 *   <li>{@code M<NNN>.<step>}: a procedure step;
 *   <li>{@code M<NNN>.<step>/<yyyyMMdd>} or {@code /<yyyyMM>}: backfill progress of one partition
 *       (a restarted backfill skips the partitions that have a successful row);
 *   <li>{@code repair.<yyyyMMddHHmmss>}: a checksum repair ({@code
 *       argos.clickhouse.migration.repair-checksums}); its description lists the versions.
 * </ul>
 *
 * @param version version as above (at most 32 characters)
 * @param description description (at most 256 characters)
 * @param checksum checksum of the script or step (64 hex characters)
 * @param appliedAt when the row was written
 * @param durationMs duration of the script, step or partition
 * @param success whether it completed; a failed row is retried at the next start
 * @param error one-line error of a failed attempt, else null
 */
public record HistoryEntry(
    String version,
    String description,
    String checksum,
    Instant appliedAt,
    Integer durationMs,
    boolean success,
    String error) {

  static final Pattern SCRIPT = Pattern.compile("V(\\d{3})");
  static final Pattern STEP = Pattern.compile("(M\\d{3})\\.([a-z][a-z0-9_]{0,15})");
  static final Pattern PROGRESS =
      Pattern.compile("(M\\d{3}\\.[a-z][a-z0-9_]{0,15})/(\\d{6}|\\d{8})");
  static final Pattern REPAIR = Pattern.compile("repair\\.\\d{14}");

  /** Maximum length of a version (column type). */
  static final int MAX_VERSION = 32;

  /** Maximum length of a description (column type). */
  static final int MAX_DESCRIPTION = 256;

  /** Maximum length of the stored error text. */
  static final int MAX_ERROR = 2000;
}
