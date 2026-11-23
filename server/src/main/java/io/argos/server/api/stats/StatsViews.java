/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.stats;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.openapi.ArgosUnsignedId;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The response types of the statistics resources (14 §4.8). 64-bit dictionary keys ({@code
 * service_id}, {@code sql_id}, {@code db_id}, {@code host_id}, {@code group_id}, {@code
 * version_id}) are unsigned decimal strings, like {@code tx_id}: JSON numbers lose precision above
 * 2^53. Names are collected strings ({@code x-argos-untrusted}).
 */
public final class StatsViews {

  private StatsViews() {}

  /**
   * Changes against the previous range of the same length ({@code compare_prev=true}): {@code
   * count} and {@code p95_ms} relative ({@code (cur - prev) / prev}), {@code error_rate} absolute;
   * null without a previous value.
   *
   * @param count relative change of the count
   * @param p95Ms relative change of the p95
   * @param errorRate absolute change of the error rate
   */
  @Schema(name = "StatsDelta")
  public record Delta(
      @Nullable Double count,
      @JsonProperty("p95_ms") @Nullable Double p95Ms,
      @JsonProperty("error_rate") @Nullable Double errorRate) {}

  /**
   * The part of a range that raw rows were read for (the dimensions the rollups lack): the last 24
   * h of the range at most, inside the raw retention; null when no raw rows are left.
   *
   * @param from start
   * @param to end (exclusive)
   */
  @Schema(name = "RawWindow")
  public record RawWindow(Instant from, Instant to) {}

  /**
   * A service (14 §4.8 {@code services/stats}).
   *
   * @param serviceId dictionary key
   * @param service name
   * @param versionId application version key ({@code split_version=true}), or absent
   * @param version application version, or absent
   * @param count transactions
   * @param errors errors
   * @param errorRate error rate
   * @param avgMs average
   * @param p50Ms p50 (DD states, C-53)
   * @param p95Ms p95
   * @param p99Ms p99
   * @param maxMs maximum
   * @param apdex Apdex (satisfied + tolerating / 2) / count
   * @param sumMs elapsed sum
   * @param sqlTimeRatio SQL time / elapsed
   * @param extTimeRatio outgoing call time / elapsed
   * @param delta changes ({@code compare_prev=true}), or absent
   */
  @Schema(name = "ServiceStats")
  public record ServiceRow(
      @JsonProperty("service_id") String serviceId,
      @ArgosUntrusted String service,
      @JsonProperty("version_id") @JsonInclude(JsonInclude.Include.NON_NULL) String versionId,
      @ArgosUntrusted @JsonInclude(JsonInclude.Include.NON_NULL) String version,
      long count,
      long errors,
      @JsonProperty("error_rate") @Nullable Double errorRate,
      @JsonProperty("avg_ms") @Nullable Double avgMs,
      @JsonProperty("p50_ms") @Nullable Double p50Ms,
      @JsonProperty("p95_ms") @Nullable Double p95Ms,
      @JsonProperty("p99_ms") @Nullable Double p99Ms,
      @JsonProperty("max_ms") @Nullable Long maxMs,
      @Nullable Double apdex,
      @JsonProperty("sum_ms") long sumMs,
      @JsonProperty("sql_time_ratio") @Nullable Double sqlTimeRatio,
      @JsonProperty("ext_time_ratio") @Nullable Double extTimeRatio,
      @JsonInclude(JsonInclude.Include.NON_NULL) Delta delta) {}

  /**
   * A time series of counts and percentiles on the bucket grid of {@code ts} (null where there is
   * no data).
   *
   * @param ts bucket starts
   * @param count count per bucket (0 for a covered bucket without rows)
   * @param errors errors per bucket; null for an error group, whose series is a count only
   * @param errorRate error rate; null for an error group
   * @param avgMs average; null for an error group
   * @param p95Ms p95; null for an error group
   * @param p99Ms p99 (transactions; absent for the bucket estimates)
   * @param timeouts timeouts per bucket (0 for a covered bucket without rows; outgoing call hosts
   *     only, absent elsewhere; ADR-0010 C-222 (7), 14 §4.8 correction)
   * @param connectFailures connection failures per bucket (as {@code timeouts})
   */
  @Schema(name = "StatsSeries")
  public record Series(
      List<Instant> ts,
      List<@Nullable Long> count,
      @Nullable List<@Nullable Long> errors,
      @JsonProperty("error_rate") @Nullable List<@Nullable Double> errorRate,
      @JsonProperty("avg_ms") @Nullable List<@Nullable Double> avgMs,
      @JsonProperty("p95_ms") @Nullable List<@Nullable Double> p95Ms,
      @JsonProperty("p99_ms") @JsonInclude(JsonInclude.Include.NON_NULL)
          List<@Nullable Double> p99Ms,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<@Nullable Long> timeouts,
      @JsonProperty("connect_failures") @JsonInclude(JsonInclude.Include.NON_NULL)
          List<@Nullable Long> connectFailures) {

    /** A series without the outgoing call failure classes. */
    public Series(
        List<Instant> ts,
        List<@Nullable Long> count,
        @Nullable List<@Nullable Long> errors,
        @Nullable List<@Nullable Double> errorRate,
        @Nullable List<@Nullable Double> avgMs,
        @Nullable List<@Nullable Double> p95Ms,
        @Nullable List<@Nullable Double> p99Ms) {
      this(ts, count, errors, errorRate, avgMs, p95Ms, p99Ms, null, null);
    }
  }

  /**
   * A count of a dimension value.
   *
   * @param id the key (instance id as text, version or service key)
   * @param name its name, or null
   * @param count count
   * @param errors errors, or null
   * @param avgMs average, or null
   * @param p95Ms p95, or null
   * @param share the count's share of the total, or null
   */
  @Schema(name = "StatsBreakdown")
  public record Breakdown(
      @ArgosUnsignedId String id,
      @ArgosUntrusted @Nullable String name,
      long count,
      @Nullable Long errors,
      @JsonProperty("avg_ms") @Nullable Double avgMs,
      @JsonProperty("p95_ms") @Nullable Double p95Ms,
      @Nullable Double share) {}

  /**
   * A related transaction.
   *
   * @param txId transaction id
   * @param ts start
   * @param serviceId service key
   * @param service service name
   * @param instanceId instance
   * @param elapsedMs elapsed time
   */
  @Schema(name = "RelatedTransaction")
  public record RelatedTx(
      @JsonProperty("tx_id") String txId,
      Instant ts,
      @JsonProperty("service_id") String serviceId,
      @ArgosUntrusted String service,
      @JsonProperty("instance_id") int instanceId,
      @JsonProperty("elapsed_ms") long elapsedMs) {}

  /**
   * One service (14 §4.8 {@code services/{serviceId}}).
   *
   * @param serviceId key
   * @param service name
   * @param series series of the range
   * @param byVersion per application version (rollups, exact)
   * @param byInstance per instance (raw window)
   * @param topSql the SQL ids of the service's transactions in the raw window (xlog.sql_ids, A-10;
   *     count = transactions that ran it, share = of the service's transactions)
   * @param topExternal hosts called by the service's profiled transactions in the raw window (a
   *     sample)
   * @param topErrorGroups error groups of the service (error_stat_1m, exact)
   * @param rawWindow the raw window of by_instance, top_sql and top_external
   */
  @Schema(name = "ServiceDetail")
  public record ServiceDetail(
      @JsonProperty("service_id") String serviceId,
      @ArgosUntrusted String service,
      Series series,
      @JsonProperty("by_version") List<Breakdown> byVersion,
      @JsonProperty("by_instance") List<Breakdown> byInstance,
      @JsonProperty("top_sql") List<Breakdown> topSql,
      @JsonProperty("top_external") List<Breakdown> topExternal,
      @JsonProperty("top_error_groups") List<Breakdown> topErrorGroups,
      @JsonProperty("raw_window") @Nullable RawWindow rawWindow) {}

  /**
   * A JDBC target (dictionary {@code db_target}).
   *
   * @param dbId key
   * @param target the stored target, e.g. {@code mysql://db1:3306/shop}
   * @param vendor the scheme, or null
   * @param host host and port, or null
   * @param name the database, or null
   */
  @Schema(name = "DbTarget")
  public record Db(
      @JsonProperty("db_id") String dbId,
      @ArgosUntrusted String target,
      @ArgosUntrusted @Nullable String vendor,
      @ArgosUntrusted @Nullable String host,
      @ArgosUntrusted @Nullable String name) {}

  /**
   * A SQL statement on a target (14 §4.8 {@code sql/stats}; complete counts, D-12).
   *
   * @param sqlId key
   * @param sqlNormalized normalized text, at most 2 KB
   * @param sqlTruncated whether the text was cut here or when stored
   * @param db the target
   * @param count executions
   * @param errors errors
   * @param sumMs time sum (execute + fetch)
   * @param execMs execute time sum
   * @param fetchMs fetch time sum
   * @param avgMs average
   * @param maxMs maximum
   * @param p50Ms p50 estimated from the buckets (C-14)
   * @param p95Ms p95 estimated from the buckets (C-14)
   * @param p99Ms p99 estimated from the buckets (C-14)
   * @param estimated always true: the percentiles are bucket estimates (C-14)
   * @param fetchRows fetched rows
   * @param delta changes ({@code compare_prev=true}), or absent
   */
  @Schema(name = "SqlStats")
  public record SqlRow(
      @JsonProperty("sql_id") String sqlId,
      @JsonProperty("sql_normalized") @ArgosUntrusted String sqlNormalized,
      @JsonProperty("sql_truncated") boolean sqlTruncated,
      Db db,
      long count,
      long errors,
      @JsonProperty("sum_ms") long sumMs,
      @JsonProperty("exec_ms") long execMs,
      @JsonProperty("fetch_ms") long fetchMs,
      @JsonProperty("avg_ms") @Nullable Double avgMs,
      @JsonProperty("max_ms") @Nullable Long maxMs,
      @JsonProperty("p50_ms") @Nullable Double p50Ms,
      @JsonProperty("p95_ms") @Nullable Double p95Ms,
      @JsonProperty("p99_ms") @Nullable Double p99Ms,
      boolean estimated,
      @JsonProperty("fetch_rows") long fetchRows,
      @JsonInclude(JsonInclude.Include.NON_NULL) Delta delta) {}

  /**
   * The related transactions of a SQL statement or host.
   *
   * @param note what the list covers
   * @param items the latest transactions, at most 10
   */
  @Schema(name = "RelatedTransactions")
  public record Related(String note, List<RelatedTx> items) {}

  /**
   * One SQL statement (14 §4.8 {@code sql/{sqlId}}).
   *
   * @param sqlId key
   * @param sqlNormalized normalized text, at most 2 KB
   * @param sqlText the full stored text, at most 64 KB
   * @param sqlTruncated whether the text was cut here or when stored
   * @param normVersion the normalizer version (D-22), or null
   * @param series series of the range (percentiles estimated, C-14)
   * @param estimated always true (C-14)
   * @param count executions in the range
   * @param execMsTotal execute time sum
   * @param fetchMsTotal fetch time sum
   * @param waitMsTotal connection wait: not measured (no per-statement attribution of pool wait
   *     yet; ADR-0010 C-178), always null
   * @param byDb per target
   * @param byService services whose transactions ran it in the raw window (xlog.sql_ids, A-10)
   * @param byInstance per instance in the raw window (sql_stat_raw)
   * @param relatedTx the latest transactions that ran it (xlog.sql_ids)
   * @param rawWindow the raw window of by_service, by_instance and related_tx
   */
  @Schema(name = "SqlDetail")
  public record SqlDetail(
      @JsonProperty("sql_id") String sqlId,
      @JsonProperty("sql_normalized") @ArgosUntrusted String sqlNormalized,
      @JsonProperty("sql_text") @ArgosUntrusted String sqlText,
      @JsonProperty("sql_truncated") boolean sqlTruncated,
      @JsonProperty("norm_version") @Nullable Integer normVersion,
      Series series,
      boolean estimated,
      long count,
      @JsonProperty("exec_ms_total") long execMsTotal,
      @JsonProperty("fetch_ms_total") long fetchMsTotal,
      @JsonProperty("wait_ms_total") @Nullable Long waitMsTotal,
      @JsonProperty("by_db") List<SqlRow> byDb,
      @JsonProperty("by_service") List<Breakdown> byService,
      @JsonProperty("by_instance") List<Breakdown> byInstance,
      @JsonProperty("related_tx") Related relatedTx,
      @JsonProperty("raw_window") @Nullable RawWindow rawWindow) {}

  /**
   * An outgoing call host (14 §4.8 {@code external/stats}).
   *
   * @param hostId key
   * @param host {@code scheme://host:port}
   * @param pathPattern path pattern: not stored, the statistics key is the host (ADR-0010 C-178
   *     (3), C-223 (5)), always null
   * @param count calls
   * @param errors errors (5xx and other exceptions)
   * @param timeouts timeouts
   * @param connectFailures connection failures
   * @param sumMs time sum
   * @param avgMs average
   * @param maxMs maximum
   * @param p50Ms p50 estimated (C-14)
   * @param p95Ms p95 estimated (C-14)
   * @param p99Ms p99 estimated (C-14)
   * @param estimated always true (C-14)
   * @param delta changes ({@code compare_prev=true}), or absent
   */
  @Schema(name = "ExternalStats")
  public record ExternalRow(
      @JsonProperty("host_id") String hostId,
      @ArgosUntrusted String host,
      @JsonProperty("path_pattern") @ArgosUntrusted @Nullable String pathPattern,
      long count,
      long errors,
      long timeouts,
      @JsonProperty("connect_failures") long connectFailures,
      @JsonProperty("sum_ms") long sumMs,
      @JsonProperty("avg_ms") @Nullable Double avgMs,
      @JsonProperty("max_ms") @Nullable Long maxMs,
      @JsonProperty("p50_ms") @Nullable Double p50Ms,
      @JsonProperty("p95_ms") @Nullable Double p95Ms,
      @JsonProperty("p99_ms") @Nullable Double p99Ms,
      boolean estimated,
      @JsonInclude(JsonInclude.Include.NON_NULL) Delta delta) {}

  /**
   * One host (14 §4.8 {@code external/{hostId}}).
   *
   * @param hostId key
   * @param host name
   * @param series series of the range (percentiles estimated, C-14)
   * @param estimated always true (C-14)
   * @param byService services of the profiled transactions that called it in the raw window (a
   *     sample: profile_step)
   * @param relatedTx the latest profiled transactions that called it (a sample)
   * @param rawWindow the raw window of by_service and related_tx
   */
  @Schema(name = "ExternalDetail")
  public record ExternalDetail(
      @JsonProperty("host_id") String hostId,
      @ArgosUntrusted String host,
      Series series,
      boolean estimated,
      @JsonProperty("by_service") List<Breakdown> byService,
      @JsonProperty("related_tx") Related relatedTx,
      @JsonProperty("raw_window") @Nullable RawWindow rawWindow) {}

  /**
   * An error group (14 §4.8 {@code errors/groups}).
   *
   * @param groupId fingerprint (C-131)
   * @param errorClass exception class
   * @param messagePattern masked message pattern, at most 1 KB
   * @param topFrame top application frame
   * @param count occurrences in the range
   * @param firstSeen first occurrence in the range
   * @param lastSeen last occurrence in the range
   * @param firstSeenEver first occurrence ever (error_groups), or null
   * @param isNew whether the group first appeared in the range
   * @param services the top 5 services by count
   * @param versions the application versions it occurred in (at most 20)
   * @param delta changes ({@code compare_prev=true}): {@code count} relative to the previous range
   *     of the same length (null when it had none), {@code p95_ms} and {@code error_rate} always
   *     null (a group has neither); absent otherwise and in the group detail
   */
  @Schema(name = "ErrorGroup")
  public record ErrorGroupRow(
      @JsonProperty("group_id") String groupId,
      @JsonProperty("error_class") @ArgosUntrusted @Nullable String errorClass,
      @JsonProperty("message_pattern") @ArgosUntrusted @Nullable String messagePattern,
      @JsonProperty("top_frame") @ArgosUntrusted @Nullable String topFrame,
      long count,
      @JsonProperty("first_seen") Instant firstSeen,
      @JsonProperty("last_seen") Instant lastSeen,
      @JsonProperty("first_seen_ever") @Nullable Instant firstSeenEver,
      @JsonProperty("is_new") boolean isNew,
      List<Breakdown> services,
      List<VersionRef> versions,
      @JsonInclude(JsonInclude.Include.NON_NULL) Delta delta) {}

  /**
   * An application version.
   *
   * @param versionId dictionary key ({@code 0}: no version reported)
   * @param version name, or null
   */
  @Schema(name = "VersionRef")
  public record VersionRef(
      @JsonProperty("version_id") String versionId, @ArgosUntrusted @Nullable String version) {}

  /**
   * One error group (14 §4.8 {@code errors/groups/{groupId}}).
   *
   * @param group the group
   * @param sampleStack one masked stack, one frame per element
   * @param series occurrences per bucket
   * @param byVersion per application version
   * @param byInstance per instance (raw window)
   * @param byService per service
   * @param recentTx the latest transactions of the group (raw window), at most 10
   * @param rawWindow the raw window of by_instance and recent_tx
   */
  @Schema(name = "ErrorGroupDetail")
  public record ErrorGroupDetail(
      ErrorGroupRow group,
      @JsonProperty("sample_stack") @ArgosUntrusted @Nullable List<String> sampleStack,
      Series series,
      @JsonProperty("by_version") List<Breakdown> byVersion,
      @JsonProperty("by_instance") List<Breakdown> byInstance,
      @JsonProperty("by_service") List<Breakdown> byService,
      @JsonProperty("recent_tx") List<RelatedTx> recentTx,
      @JsonProperty("raw_window") @Nullable RawWindow rawWindow) {}
}
