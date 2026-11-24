/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.privacy;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.ops.retention.RetentionApplier.TableState;
import io.argos.server.ops.retention.RetentionPlanner.TtlChange;
import io.argos.server.ops.retention.RetentionService;
import io.argos.server.ops.retention.RetentionSettings;
import io.argos.server.storage.postgres.privacyjob.PrivacyJob;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Request and response bodies of the privacy and retention administration API (14 §4.14; WO-28).
 * Field names are the API's snake_case; times are ISO-8601 UTC in milliseconds (14 §2.4). No body
 * carries a deletion subject value: a job shows the kind and the SHA-256 of the value only.
 */
final class PrivacyDtos {

  /** The notice of every deletion response (13 §9 step 5). */
  static final String ROLLUP_NOTICE =
      "Rollups (per-minute and per-hour statistics) are aggregates without user ids or addresses"
          + " and are not changed by a deletion.";

  /** The warning of an address deletion (16 §6.8). */
  static final String MASKED_IP_NOTICE =
      "Rows of instances in masked IP mode store the network only: the deletion removes every row"
          + " of the address's /24 (IPv4) or /48 (IPv6) network for those instances.";

  private PrivacyDtos() {}

  static @Nullable Instant millis(@Nullable Instant instant) {
    return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
  }

  /**
   * {@code POST /admin/privacy/deletions}: the subject travels in this body only, never in the URL
   * (SEC-33, ADR-0010 C-241 (3)). Exactly one of {@code user_id} and {@code ip}.
   */
  record DeletionRequest(
      @Schema(description = "The user id (normalised like the search)") @JsonProperty("user_id")
          @Nullable String userId,
      @Schema(description = "The client address, an IPv4 or IPv6 literal") @Nullable String ip,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              format = "date-time",
              description = "Range start, ISO-8601 UTC")
          @Nullable String from,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              format = "date-time",
              description = "Range end (exclusive), ISO-8601 UTC, at most 30 days after from")
          @Nullable String to,
      @Schema(description = "Restrict to one application") @JsonProperty("app_id")
          @Nullable Integer appId) {}

  /** {@code POST /admin/privacy/deletions}: 202 with the queued job. */
  record DeletionAccepted(
      @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid") @JsonProperty("job_id")
          String jobId,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"queued"})
          String status,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"user_id", "ip"})
          @JsonProperty("subject_kind")
          String subjectKind,
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "SHA-256 of the normalised value")
          @JsonProperty("subject_sha256")
          String subjectSha256,
      @Schema(description = "null: every application") @JsonProperty("app_id")
          @Nullable Integer appId,
      @Schema(requiredMode = RequiredMode.REQUIRED) Instant from,
      @Schema(requiredMode = RequiredMode.REQUIRED) Instant to,
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "Always true (13 §9)")
          @JsonProperty("rollups_excluded")
          boolean rollupsExcluded,
      @Schema(requiredMode = RequiredMode.REQUIRED) List<String> notices) {

    static DeletionAccepted of(PrivacyJob job) {
      List<String> notices =
          "ip".equals(job.subjectKind())
              ? List.of(ROLLUP_NOTICE, MASKED_IP_NOTICE)
              : List.of(ROLLUP_NOTICE);
      return new DeletionAccepted(
          job.id().toString(),
          job.status().value(),
          job.subjectKind(),
          job.subjectHash(),
          job.appId(),
          millis(job.from()),
          millis(job.to()),
          true,
          notices);
    }
  }

  /** One issued mutation of a job. */
  record JobTable(
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"xlog", "profile_step"})
          String name,
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "UTC day yyyy-MM-dd")
          String partition,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("mutation_id") String mutationId,
      @Schema(requiredMode = RequiredMode.REQUIRED) boolean done) {}

  /** {@code GET /admin/privacy/jobs/{jobId}} (14 §4.14). */
  record PrivacyJobView(
      @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid") @JsonProperty("job_id")
          String jobId,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"queued", "running", "done", "failed"})
          String status,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"user_id", "ip"})
          @JsonProperty("subject_kind")
          String subjectKind,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("subject_sha256")
          String subjectSha256,
      @JsonProperty("app_id") @Nullable Integer appId,
      @Schema(requiredMode = RequiredMode.REQUIRED) Instant from,
      @Schema(requiredMode = RequiredMode.REQUIRED) Instant to,
      @Schema(requiredMode = RequiredMode.REQUIRED) List<JobTable> tables,
      @Schema(
              description =
                  "Matching rows of xlog and profile_step counted before the mutations were"
                      + " issued; null while they are being issued")
          @JsonProperty("rows_affected")
          @Nullable Long rowsAffected,
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "e.g. ip_masked_scope")
          List<String> warnings,
      @Schema(description = "Short failure code (e.g. interrupted_by_restart)")
          @Nullable String error,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("started_at") @Nullable Instant startedAt,
      @JsonProperty("finished_at") @Nullable Instant finishedAt,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("rollups_excluded")
          boolean rollupsExcluded) {

    static PrivacyJobView of(PrivacyJob job) {
      return new PrivacyJobView(
          job.id().toString(),
          job.status().value(),
          job.subjectKind(),
          job.subjectHash(),
          job.appId(),
          millis(job.from()),
          millis(job.to()),
          job.tables().stream()
              .map(t -> new JobTable(t.name(), t.partition(), t.mutationId(), t.done()))
              .toList(),
          job.rowsAffected(),
          job.warnings(),
          job.error(),
          millis(job.createdAt()),
          millis(job.startedAt()),
          millis(job.finishedAt()),
          true);
    }
  }

  /** The six retention values (14 §4.14 field names), whole days. */
  record RetentionValues(
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("xlog_days") int xlogDays,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("profile_days") int profileDays,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("stat_raw_days") int statRawDays,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("metric_raw_days")
          int metricRawDays,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("rollup_1m_days")
          int rollup1mDays,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("rollup_1h_days")
          int rollup1hDays) {

    static RetentionValues of(RetentionSettings s) {
      return new RetentionValues(
          s.xlog(), s.profile(), s.stat(), s.metric(), s.rollup1m(), s.rollup1h());
    }
  }

  /** One governed ClickHouse table of {@code GET /admin/settings/retention}. */
  record RetentionTable(
      @Schema(requiredMode = RequiredMode.REQUIRED) String table,
      @Schema(requiredMode = RequiredMode.REQUIRED) String field,
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "TTL of the table now; -1: none")
          @JsonProperty("current_days")
          int currentDays,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("desired_days") int desiredDays,
      @Schema(description = "Oldest active partition id (yyyyMMdd or yyyyMM); null: empty")
          @JsonProperty("oldest_partition")
          @Nullable String oldestPartition,
      @Schema(requiredMode = RequiredMode.REQUIRED) int partitions,
      @Schema(requiredMode = RequiredMode.REQUIRED) long rows,
      @Schema(requiredMode = RequiredMode.REQUIRED) long bytes) {

    static RetentionTable of(TableState s) {
      return new RetentionTable(
          s.table(),
          s.field(),
          s.currentDays(),
          s.desiredDays(),
          s.oldestPartition(),
          s.partitions(),
          s.rows(),
          s.bytes());
    }
  }

  /** {@code GET /admin/settings/retention}. */
  record RetentionView(
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "The effective retention")
          RetentionValues values,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              description = "Origin of each value: settings (set through this API) or config")
          Map<String, String> sources,
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "argos.retention.* values")
          RetentionValues config,
      @Schema(description = "The ClickHouse tables; null when ClickHouse could not be read")
          @Nullable List<RetentionTable> tables,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("pending_drops")
          int pendingDrops) {

    static RetentionView of(
        RetentionService.Effective e, @Nullable List<TableState> tables, int pendingDrops) {
      return new RetentionView(
          RetentionValues.of(e.values()),
          e.sources(),
          RetentionValues.of(e.config()),
          tables == null ? null : tables.stream().map(RetentionTable::of).toList(),
          pendingDrops);
    }
  }

  /** {@code PUT /admin/settings/retention}: the values to change; absent fields stay. */
  record RetentionUpdateRequest(
      @Schema(minimum = "1", maximum = "36500") @JsonProperty("xlog_days")
          @Nullable Integer xlogDays,
      @Schema(minimum = "1", maximum = "36500") @JsonProperty("profile_days")
          @Nullable Integer profileDays,
      @Schema(minimum = "1", maximum = "36500") @JsonProperty("stat_raw_days")
          @Nullable Integer statRawDays,
      @Schema(minimum = "1", maximum = "36500") @JsonProperty("metric_raw_days")
          @Nullable Integer metricRawDays,
      @Schema(minimum = "1", maximum = "36500") @JsonProperty("rollup_1m_days")
          @Nullable Integer rollup1mDays,
      @Schema(minimum = "1", maximum = "36500") @JsonProperty("rollup_1h_days")
          @Nullable Integer rollup1hDays,
      @Schema(
              description =
                  "Must be true when a value becomes shorter: data outside the new retention is"
                      + " dropped")
          @Nullable Boolean confirm) {}

  /** One TTL change made by an update. */
  record RetentionChange(
      @Schema(requiredMode = RequiredMode.REQUIRED) String table,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("from_days") int fromDays,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("to_days") int toDays) {

    static RetentionChange of(TtlChange c) {
      return new RetentionChange(c.table(), c.fromDays(), c.toDays());
    }
  }

  /** The answer of {@code PUT /admin/settings/retention}. */
  record RetentionUpdated(
      @Schema(requiredMode = RequiredMode.REQUIRED) RetentionValues values,
      @Schema(requiredMode = RequiredMode.REQUIRED) Map<String, String> sources,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              description =
                  "The TTLs were applied to ClickHouse; false: stored, applied at the next start"
                      + " or by argos-admin retention apply")
          boolean applied,
      @Schema(description = "Why the values were not applied") @JsonProperty("apply_error")
          @Nullable String applyError,
      @Schema(requiredMode = RequiredMode.REQUIRED) List<RetentionChange> changes,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              description = "Partitions outside a shortened retention, dropped in the background")
          @JsonProperty("queued_drops")
          int queuedDrops) {

    static RetentionUpdated of(RetentionService.UpdateResult r) {
      return new RetentionUpdated(
          RetentionValues.of(r.effective().values()),
          r.effective().sources(),
          r.outcome().applied(),
          r.outcome().error(),
          r.outcome().changes().stream().map(RetentionChange::of).toList(),
          r.outcome().queuedDrops());
    }
  }

  /** {@code GET /admin/settings/privacy}: the server defaults. */
  record PrivacySettingsView(
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"full", "masked", "off"})
          @JsonProperty("default_ip_mode")
          String defaultIpMode,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"settings", "config"},
              description =
                  "settings: chosen in the setup or through this API; config:"
                      + " argos.privacy.default-ip-mode")
          @JsonProperty("default_ip_mode_source")
          String defaultIpModeSource) {}

  /** {@code PUT /admin/settings/privacy}. */
  record PrivacySettingsRequest(
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"full", "masked", "off"})
          @JsonProperty("default_ip_mode")
          @Nullable String defaultIpMode) {}
}
