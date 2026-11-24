/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.storage;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Which partition the emergency retention drops next (13 §8; PM question 1 assumption): the raw
 * tables in three groups, {@code profile_step} first, then {@code metric}, {@code sql_stat_raw} and
 * {@code ext_stat_raw}, then {@code xlog}. The first group that still has a droppable partition
 * gives its oldest day partition (within a group the oldest day wins, ties in the order above).
 * Droppable means a day partition ({@code yyyyMMdd}) strictly before today (UTC): today's
 * partition, future days and every other table (rollups) are never chosen.
 */
public final class EmergencyOrder {

  /** The tables in drop order, by group. */
  public static final List<List<String>> GROUPS =
      List.of(
          List.of("profile_step"),
          List.of("metric", "sql_stat_raw", "ext_stat_raw"),
          List.of("xlog"));

  private static final Pattern DAY = Pattern.compile("[0-9]{8}");

  private EmergencyOrder() {}

  /**
   * One active day partition of a raw table.
   *
   * @param table table name without the configured prefix
   * @param partitionId {@code yyyyMMdd}
   * @param rows rows
   * @param bytes bytes on disk
   */
  public record Candidate(String table, String partitionId, long rows, long bytes) {

    /** The day of the partition, or null when the id is not a day. */
    public LocalDate day() {
      if (!DAY.matcher(partitionId).matches()) {
        return null;
      }
      try {
        return LocalDate.parse(partitionId, DateTimeFormatter.BASIC_ISO_DATE);
      } catch (DateTimeParseException e) {
        return null;
      }
    }
  }

  /** The partition to drop next among {@code candidates}, or empty when none may be dropped. */
  public static Optional<Candidate> next(List<Candidate> candidates, LocalDate today) {
    for (List<String> group : GROUPS) {
      Optional<Candidate> pick =
          candidates.stream()
              .filter(c -> group.contains(c.table()))
              .filter(c -> c.day() != null && c.day().isBefore(today))
              .min(
                  Comparator.comparing(Candidate::day)
                      .thenComparingInt(c -> group.indexOf(c.table())));
      if (pick.isPresent()) {
        return pick;
      }
    }
    return Optional.empty();
  }

  /** Whether {@code table} is one the emergency retention may drop from. */
  public static boolean governs(String table) {
    return GROUPS.stream().anyMatch(g -> g.contains(table));
  }
}
