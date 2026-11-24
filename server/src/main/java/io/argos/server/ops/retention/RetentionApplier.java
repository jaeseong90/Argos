/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.retention;

import io.argos.server.ops.migration.SchemaParameters;
import io.argos.server.ops.retention.RetentionPlanner.TtlChange;
import io.argos.server.storage.clickhouse.RollupPartitions;
import io.argos.server.storage.clickhouse.TableCatalog;
import io.argos.server.storage.clickhouse.TableCatalog.PartitionInfo;
import io.argos.server.storage.clickhouse.TableCatalog.TableInfo;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies a retention to the ClickHouse tables (13 §8, 17 §9.1; ADR-0010 C-99, C-112) with the
 * migration account: every governed table whose TTL differs gets {@code ALTER TABLE ... MODIFY TTL
 * ... SETTINGS materialize_ttl_after_modify = 0} (no part is rewritten: an extension keeps the old
 * parts as they are and lets them expire later, a shortening lets the TTL merges drop the parts of
 * expired days, 13 §8 "하루 파티션이므로 TTL 경계와 part 경계가 맞아 재작성이 없다"). The day (or month) partitions of a
 * shortened table that are wholly outside the new retention are listed so they can be dropped at
 * once ({@code DROP PARTITION}) instead of waiting for the next TTL merge (4 hours, N-08); {@link
 * #drop} drops them.
 *
 * <p>Nothing is written to {@code ch_schema_history} (PM question 3 assumption: the change is
 * recorded as the audit event {@code RETENTION_CHANGED} by the caller, apart from the checksummed
 * migration history).
 */
public final class RetentionApplier {

  private final SchemaParameters params;

  /**
   * @param params database and prefix of the tables
   */
  public RetentionApplier(SchemaParameters params) {
    this.params = params;
  }

  /**
   * One partition to drop.
   *
   * @param table table name without prefix
   * @param partitionId {@code yyyyMMdd} or {@code yyyyMM}
   * @param rows rows of its active parts
   * @param bytes bytes on disk
   */
  public record Drop(String table, String partitionId, long rows, long bytes) {}

  /**
   * The result of {@link #apply}.
   *
   * @param changes the {@code MODIFY TTL} statements executed
   * @param drops partitions of shortened tables wholly outside their new retention (not dropped
   *     yet)
   * @param unmanaged governed tables without a recognisable TTL (left alone)
   */
  public record Result(List<TtlChange> changes, List<Drop> drops, List<String> unmanaged) {

    public Result {
      changes = List.copyOf(changes);
      drops = List.copyOf(drops);
      unmanaged = List.copyOf(unmanaged);
    }
  }

  /**
   * The state of one governed table ({@code argos-admin retention show}).
   *
   * @param table table name without prefix
   * @param field its retention key
   * @param currentDays the TTL of the table now, or -1 when it has none
   * @param desiredDays the wanted retention
   * @param oldestPartition the oldest active partition id, or null when the table is empty
   * @param partitions active partitions
   * @param rows rows of the active parts
   * @param bytes bytes on disk of the active parts
   */
  public record TableState(
      String table,
      String field,
      int currentDays,
      int desiredDays,
      String oldestPartition,
      int partitions,
      long rows,
      long bytes) {}

  /**
   * Executes the {@code MODIFY TTL} statements of {@code desired} and lists the partitions of the
   * shortened tables that are outside their new retention on {@code today}.
   *
   * @param sql statements of the migration account
   * @param parts queries of the query account ({@code system.parts}, {@link
   *     TableCatalog#openReader})
   */
  public <E extends Exception> Result apply(
      RollupPartitions.Statements<E> sql,
      RollupPartitions.Statements<E> parts,
      RetentionSettings desired,
      LocalDate today)
      throws E {
    List<TableInfo> tables = TableCatalog.tables(sql, params.database(), params.tablePrefix());
    List<TtlChange> changes = RetentionPlanner.ttlChanges(tables, desired);
    List<Drop> drops = new ArrayList<>();
    for (TtlChange change : changes) {
      sql.execute(RetentionPlanner.modifyTtl(params.table(change.table()), change));
    }
    for (TtlChange change : changes) {
      if (!change.shortens()) {
        continue;
      }
      for (PartitionInfo p :
          RetentionPlanner.outside(
              TableCatalog.partitions(
                  parts, params.database(), params.tablePrefix() + change.table()),
              change.toDays(),
              today)) {
        drops.add(new Drop(change.table(), p.id(), p.rows(), p.bytes()));
      }
    }
    return new Result(changes, drops, RetentionPlanner.unmanaged(tables));
  }

  /** Drops one partition listed by {@link #apply}. */
  public <E extends Exception> void drop(RollupPartitions.Statements<E> sql, Drop drop) throws E {
    TableCatalog.dropPartition(sql, params.table(drop.table()), drop.partitionId());
  }

  /**
   * The state of every governed table under {@code desired}, in table order.
   *
   * @param sql queries of the migration account ({@code system.tables})
   * @param reader queries of the query account ({@code system.parts})
   */
  public <E extends Exception> List<TableState> show(
      RollupPartitions.Statements<E> sql,
      RollupPartitions.Statements<E> reader,
      RetentionSettings desired)
      throws E {
    List<TableState> out = new ArrayList<>();
    for (TableInfo t : TableCatalog.tables(sql, params.database(), params.tablePrefix())) {
      Optional<String> field = RetentionPlanner.fieldOf(t.name());
      if (field.isEmpty()) {
        continue;
      }
      List<PartitionInfo> parts =
          TableCatalog.partitions(reader, params.database(), params.tablePrefix() + t.name());
      out.add(
          new TableState(
              t.name(),
              field.get(),
              t.hasTtl() ? t.ttlDays() : -1,
              desired.get(field.get()),
              parts.isEmpty() ? null : parts.get(0).id(),
              parts.size(),
              parts.stream().mapToLong(PartitionInfo::rows).sum(),
              parts.stream().mapToLong(PartitionInfo::bytes).sum()));
    }
    return out;
  }
}
