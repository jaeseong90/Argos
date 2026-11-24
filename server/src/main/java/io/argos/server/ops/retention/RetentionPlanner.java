/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.retention;

import io.argos.server.storage.clickhouse.TableCatalog.PartitionInfo;
import io.argos.server.storage.clickhouse.TableCatalog.TableInfo;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The decisions of the retention step without database access (13 §8, 17 §9.1; unit-tested): which
 * retention key governs a table, which tables need {@code MODIFY TTL}, and which partitions are
 * wholly outside a retention.
 *
 * <p>Tables (names without the configured prefix): {@code xlog} ({@code xlog_days}), {@code
 * profile_step} ({@code profile_days}), {@code sql_stat_raw} and {@code ext_stat_raw} ({@code
 * stat_raw_days}), {@code metric} ({@code metric_raw_days}), every {@code <name>_1m} or {@code
 * <name>_1m_v<n>} ({@code rollup_1m_days}) and every {@code <name>_1h} or {@code <name>_1h_v<n>}
 * ({@code rollup_1h_days}; the {@code _v<n>} names are rebuilt rollups switched in by an alias, 13
 * §13.2). Staging tables ({@code _rebuild}, {@code _backfill}) and every other table are left
 * alone.
 *
 * <p>A day partition {@code d} is outside a retention of {@code n} days when its last row has
 * expired at the start of today (UTC): {@code d + 1 + n <= today}. A month partition of a 1h rollup
 * likewise with the first day of the next month. Today's partition is never outside (n >= 1).
 */
public final class RetentionPlanner {

  private static final Pattern ROLLUP_1M = Pattern.compile("[a-z0-9_]+_1m(_v[0-9]+)?");
  private static final Pattern ROLLUP_1H = Pattern.compile("[a-z0-9_]+_1h(_v[0-9]+)?");

  /** The time expressions a TTL of an Argos table uses (checked before they go into SQL). */
  private static final Pattern TTL_EXPRESSION =
      Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\([A-Za-z_][A-Za-z0-9_]*\\))?");

  private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT);

  private RetentionPlanner() {}

  /**
   * One {@code MODIFY TTL}.
   *
   * @param table table name without prefix
   * @param field the retention key ({@link RetentionSettings#FIELDS})
   * @param expression the time expression of the TTL
   * @param fromDays the current TTL in days
   * @param toDays the wanted TTL in days
   */
  public record TtlChange(String table, String field, String expression, int fromDays, int toDays) {

    /** Whether the retention becomes shorter. */
    public boolean shortens() {
      return toDays < fromDays;
    }
  }

  /** The retention key of a table, or empty when the retention step leaves it alone. */
  public static Optional<String> fieldOf(String table) {
    if (table.endsWith("_rebuild") || table.endsWith("_backfill")) {
      return Optional.empty();
    }
    return switch (table) {
      case "xlog" -> Optional.of("xlog_days");
      case "profile_step" -> Optional.of("profile_days");
      case "sql_stat_raw", "ext_stat_raw" -> Optional.of("stat_raw_days");
      case "metric" -> Optional.of("metric_raw_days");
      default -> {
        if (ROLLUP_1M.matcher(table).matches()) {
          yield Optional.of("rollup_1m_days");
        }
        if (ROLLUP_1H.matcher(table).matches()) {
          yield Optional.of("rollup_1h_days");
        }
        yield Optional.empty();
      }
    };
  }

  /**
   * The tables whose TTL differs from {@code desired}, in table order. A governed table without a
   * TTL in days, or with a time expression this step does not recognise, is skipped (reported by
   * the caller through {@link #unmanaged}).
   */
  public static List<TtlChange> ttlChanges(List<TableInfo> tables, RetentionSettings desired) {
    List<TtlChange> out = new ArrayList<>();
    for (TableInfo t : tables) {
      Optional<String> field = fieldOf(t.name());
      if (field.isEmpty() || !managed(t)) {
        continue;
      }
      int want = desired.get(field.get());
      if (t.ttlDays() != want) {
        out.add(new TtlChange(t.name(), field.get(), t.ttlExpression(), t.ttlDays(), want));
      }
    }
    return out;
  }

  /** Governed tables that the step cannot manage (no TTL in days or an unknown expression). */
  public static List<String> unmanaged(List<TableInfo> tables) {
    return tables.stream()
        .filter(t -> fieldOf(t.name()).isPresent() && !managed(t))
        .map(TableInfo::name)
        .toList();
  }

  private static boolean managed(TableInfo t) {
    return t.hasTtl() && TTL_EXPRESSION.matcher(t.ttlExpression()).matches();
  }

  /** The statement of one change on {@code qualifiedTable} (no materialisation of old parts). */
  public static String modifyTtl(String qualifiedTable, TtlChange change) {
    if (!TTL_EXPRESSION.matcher(change.expression()).matches()) {
      throw new IllegalArgumentException("Unexpected TTL expression of " + change.table());
    }
    return "ALTER TABLE "
        + qualifiedTable
        + " MODIFY TTL "
        + change.expression()
        + " + INTERVAL "
        + change.toDays()
        + " DAY SETTINGS materialize_ttl_after_modify = 0";
  }

  /**
   * The first day of a partition id ({@code yyyyMMdd} or {@code yyyyMM}), or empty for another id.
   */
  public static Optional<LocalDate> firstDay(String partitionId) {
    try {
      if (partitionId.length() == 8) {
        return Optional.of(LocalDate.parse(partitionId, DAY));
      }
      if (partitionId.length() == 6) {
        return Optional.of(YearMonth.parse(partitionId, MONTH).atDay(1));
      }
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  /** The first day after a partition (the next day, or the first day of the next month). */
  public static Optional<LocalDate> endDay(String partitionId) {
    Optional<LocalDate> first = firstDay(partitionId);
    if (first.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        partitionId.length() == 8 ? first.get().plusDays(1) : first.get().plusMonths(1));
  }

  /** Whether the whole partition is outside a retention of {@code days} days on {@code today}. */
  public static boolean outside(String partitionId, int days, LocalDate today) {
    Optional<LocalDate> end = endDay(partitionId);
    return end.isPresent() && !end.get().plusDays(days).isAfter(today);
  }

  /**
   * The partitions of {@code partitions} outside a retention of {@code days} days, oldest first.
   */
  public static List<PartitionInfo> outside(
      List<PartitionInfo> partitions, int days, LocalDate today) {
    return partitions.stream().filter(p -> outside(p.id(), days, today)).toList();
  }
}
