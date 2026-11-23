/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.health;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.events.EventViews;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The response types of the health resources (14 §4.2 {@code server}, §4.14, §4.15). */
public final class HealthViews {

  private HealthViews() {}

  /**
   * The server summary (14 §4.2 {@code data.server}, §4.15 {@code /server/health}). Null means "no
   * source yet", never 0.
   *
   * @param ingestLagS current p99 ingest lag in seconds (null before the first insert)
   * @param diskUsageRatio server disk usage: null until the storage health job exists (WO-28)
   * @param channelFailures24h notification deliveries that failed in the last 24 h (all channels),
   *     null only while PostgreSQL cannot be read
   * @param sessions live agent sessions
   * @param certExpiryDays days until the TLS certificate expires: null until WO-28 reads it
   */
  @Schema(name = "ServerHealth")
  public record Server(
      @JsonProperty("ingest_lag_s") @Nullable Double ingestLagS,
      @JsonProperty("disk_usage_ratio") @Nullable Double diskUsageRatio,
      @JsonProperty("channel_failures_24h") @Nullable Long channelFailures24h,
      int sessions,
      @JsonProperty("cert_expiry_days") @Nullable Long certExpiryDays) {}

  /**
   * A series on the bucket grid of {@code ts}.
   *
   * @param ts bucket starts
   * @param value one value per bucket, null without data
   */
  @Schema(name = "HealthSeries")
  public record Series(List<Instant> ts, List<@Nullable Double> value) {}

  /**
   * The excluded transactions.
   *
   * @param ts bucket starts
   * @param value excluded per bucket
   * @param byReason total per reason label over the range
   */
  @Schema(name = "ExcludedSeries")
  public record Excluded(
      List<Instant> ts,
      List<@Nullable Double> value,
      @JsonProperty("by_reason") @ArgosUntrusted Map<String, Long> byReason) {}

  /**
   * The clock offset of a live instance.
   *
   * @param instanceId instance
   * @param skewMs offset in ms (agent clock minus server clock)
   */
  @Schema(name = "ClockSkew")
  public record Skew(
      @JsonProperty("instance_id") int instanceId, @JsonProperty("skew_ms") long skewMs) {}

  /**
   * One instance's collection health over the range.
   *
   * @param instanceId instance
   * @param name stored name
   * @param drops agent drops, or null without the series
   * @param excluded excluded transactions, or null without the series
   * @param skewMs live clock offset, or null when not live
   * @param lagS ingest lag per instance: no per-instance source, null
   */
  @Schema(name = "InstanceCollection")
  public record PerInstance(
      @JsonProperty("instance_id") int instanceId,
      @ArgosUntrusted String name,
      @Nullable Long drops,
      @Nullable Long excluded,
      @JsonProperty("skew_ms") @Nullable Long skewMs,
      @JsonProperty("lag_s") @Nullable Double lagS) {}

  /**
   * The collection health of an application (14 §4.15, F-S10).
   *
   * @param agentDrops items the agents dropped per bucket ({@code argos_agent_dropped_total})
   * @param serverDrops items the server dropped per bucket, server-wide (its own series, WO-28)
   * @param serverDropsQuota the part refused by the instance quotas
   * @param excluded transactions excluded by rule ({@code argos_agent_ignored_total})
   * @param ingestLagS the server's ingest lag per bucket (its own series, WO-28)
   * @param lateRows late transactions of the range (ranges up to 24 h, null beyond)
   * @param clockSkew the clock offsets of the live instances (live: null unless the range ends in
   *     the last 2 minutes)
   * @param outages collection outages (D-18)
   * @param perInstance per instance
   */
  @Schema(name = "CollectionHealth")
  public record Collection(
      @JsonProperty("agent_drops") Series agentDrops,
      @JsonProperty("server_drops") Series serverDrops,
      @JsonProperty("server_drops_quota") Series serverDropsQuota,
      Excluded excluded,
      @JsonProperty("ingest_lag_s") Series ingestLagS,
      @JsonProperty("late_rows") @Nullable Long lateRows,
      @JsonProperty("clock_skew") @Nullable List<Skew> clockSkew,
      List<EventViews.Outage> outages,
      @JsonProperty("per_instance") List<PerInstance> perInstance) {}

  /**
   * A ClickHouse disk.
   *
   * @param name disk name
   * @param usedRatio (total - free) / total
   * @param freeBytes free bytes
   * @param totalBytes total bytes
   */
  @Schema(name = "StorageDisk")
  public record Disk(
      String name,
      @JsonProperty("used_ratio") @Nullable Double usedRatio,
      @JsonProperty("free_bytes") long freeBytes,
      @JsonProperty("total_bytes") long totalBytes) {}

  /**
   * A partition with many active parts.
   *
   * @param table table
   * @param partition partition
   * @param active active parts
   * @param rows rows
   * @param bytes bytes on disk
   */
  @Schema(name = "StoragePartition")
  public record Part(String table, String partition, long active, long rows, long bytes) {}

  /**
   * Merges in progress.
   *
   * @param running merges running
   * @param longestS the longest running merge in seconds, or null
   * @param avgProgress average progress 0..1, or null
   */
  @Schema(name = "StorageMerges")
  public record Merges(
      long running,
      @JsonProperty("longest_s") @Nullable Double longestS,
      @JsonProperty("avg_progress") @Nullable Double avgProgress) {}

  /**
   * The write-ahead log of the server.
   *
   * @param bytes bytes of the segments on disk, or null without a WAL
   * @param segments segment files, or null without a WAL
   */
  @Schema(name = "StorageWal")
  public record Wal(@Nullable Long bytes, @Nullable Long segments) {}

  /**
   * The storage health (14 §4.14 {@code /admin/storage/health}, 13 §7.5, F-S22, F-S23).
   *
   * @param disk the fullest ClickHouse disk
   * @param disks every ClickHouse disk
   * @param parts the partitions with the most active parts (at most 50)
   * @param activeParts active parts of the Argos database
   * @param partitions partitions of the Argos database
   * @param merges merges in progress
   * @param delayedInserts inserts delayed by too many parts since the ClickHouse start
   * @param rejectedInserts inserts rejected by too many parts since the ClickHouse start
   * @param insertedRows rows inserted since the ClickHouse start
   * @param oldestUnmergedPartAgeS age of the oldest never-merged part in a partition with more than
   *     one part, or null
   * @param mutationsPending unfinished mutations (deletion jobs)
   * @param ttlLastRunAt the last TTL run: null until the storage health job records it (WO-28)
   * @param dictionaryUnresolved dictionary ids still unknown after the requests to the agents
   * @param wal the server's write-ahead log
   * @param emergencyRetentionActive whether emergency retention shortening is on: null until WO-28
   */
  @Schema(name = "StorageHealth")
  public record Storage(
      @Nullable Disk disk,
      List<Disk> disks,
      List<Part> parts,
      @JsonProperty("active_parts") long activeParts,
      long partitions,
      Merges merges,
      @JsonProperty("delayed_inserts") @Nullable Long delayedInserts,
      @JsonProperty("rejected_inserts") @Nullable Long rejectedInserts,
      @JsonProperty("inserted_rows") @Nullable Long insertedRows,
      @JsonProperty("oldest_unmerged_part_age_s") @Nullable Long oldestUnmergedPartAgeS,
      @JsonProperty("mutations_pending") long mutationsPending,
      @JsonProperty("ttl_last_run_at") @JsonInclude(JsonInclude.Include.ALWAYS)
          @Nullable Instant ttlLastRunAt,
      @JsonProperty("dictionary_unresolved") @Nullable Long dictionaryUnresolved,
      Wal wal,
      @JsonProperty("emergency_retention_active") @Nullable Boolean emergencyRetentionActive) {}
}
