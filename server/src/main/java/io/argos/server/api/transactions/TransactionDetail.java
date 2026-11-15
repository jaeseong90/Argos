/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The transaction detail of 14 §4.6 ({@code GET /transactions/{txId}}): one {@code xlog} row with
 * its names, the {@code profile_step} rows, the caller and the links. Values that were not measured
 * are null, never 0 (D-23). Collected strings carry {@code x-argos-untrusted} and are returned
 * unescaped (14 §2.10).
 *
 * @param txId the id (unsigned decimal)
 * @param traceId 32 hex digits, null when the transaction has none
 * @param spanId 16 hex digits
 * @param parentSpanId 16 hex digits, null for a root
 * @param app the application
 * @param instance the instance
 * @param kind {@code HTTP}, {@code BATCH}, {@code MESSAGE}
 * @param service the service name
 * @param serviceId its dictionary key
 * @param url the request path as the agent captured it (02 §5.2), null when none
 * @param httpMethod the HTTP method, empty for batch jobs
 * @param httpStatus the HTTP status; batch jobs: 1 failure, 0 success (ADR-0010 C-143 (3))
 * @param ts the start
 * @param elapsedMs elapsed time
 * @param error whether it failed
 * @param errorClass the exception class, null without one
 * @param errorClassId its dictionary key, null without one
 * @param errorGroupId the error group (C-131), null without one
 * @param version the application version, null when unknown
 * @param thread the normalized thread name, null when unknown
 * @param cpuTimeMs CPU time, null when not measured (D-23)
 * @param allocBytes allocated bytes, null when not measured
 * @param measured whether CPU time and allocation were measured
 * @param sql SQL totals
 * @param ext outgoing call totals
 * @param remoteIp the client address as stored by its mode, null when not collected
 * @param ipMode {@code full}, {@code masked}, {@code off}
 * @param userId the user id as stored by its mode, null when none
 * @param userIdMode {@code plain}, {@code hashed}, {@code off}
 * @param requestId the configured request id header (ADR-0010 C-124), null when none
 * @param headers whitelisted request headers (empty when none are collected)
 * @param caller the calling transaction of a distributed call, null for none or when not found
 * @param profile the steps
 * @param flags flags of the row
 * @param links related pages
 * @param lookup how it was found: {@code ts_hint}, {@code restored}, {@code two_day_fallback}
 * @param duplicates other rows with the same id (a collision, 13 §17 #11); empty normally
 */
@Schema(
    name = "TransactionDetail",
    description =
        "A transaction with its profile (14 §4.6). Not measured values are null (D-23). The"
            + " instance state is not part of the detail.")
public record TransactionDetail(
    @JsonProperty("tx_id") @Schema(requiredMode = RequiredMode.REQUIRED) String txId,
    @JsonProperty("trace_id") @Nullable String traceId,
    @JsonProperty("span_id") @Nullable String spanId,
    @JsonProperty("parent_span_id") @Nullable String parentSpanId,
    @JsonProperty("app") @Schema(requiredMode = RequiredMode.REQUIRED) AppRef app,
    @JsonProperty("instance") @Schema(requiredMode = RequiredMode.REQUIRED) InstanceRef instance,
    @JsonProperty("kind") @Schema(requiredMode = RequiredMode.REQUIRED) String kind,
    @JsonProperty("service") @ArgosUntrusted @Nullable String service,
    @JsonProperty("service_id") @Nullable String serviceId,
    @JsonProperty("url") @ArgosUntrusted @Nullable String url,
    @JsonProperty("http_method") @ArgosUntrusted String httpMethod,
    @JsonProperty("http_status") @Schema(requiredMode = RequiredMode.REQUIRED) int httpStatus,
    @JsonProperty("ts") @Schema(requiredMode = RequiredMode.REQUIRED) Instant ts,
    @JsonProperty("elapsed_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long elapsedMs,
    @JsonProperty("error") @Schema(requiredMode = RequiredMode.REQUIRED) boolean error,
    @JsonProperty("error_class") @ArgosUntrusted @Nullable String errorClass,
    @JsonProperty("error_class_id") @Nullable String errorClassId,
    @JsonProperty("error_group_id") @Nullable String errorGroupId,
    @JsonProperty("version") @ArgosUntrusted @Nullable String version,
    @JsonProperty("thread") @ArgosUntrusted @Nullable String thread,
    @JsonProperty("cpu_time_ms") @Nullable Long cpuTimeMs,
    @JsonProperty("alloc_bytes") @Nullable Long allocBytes,
    @JsonProperty("measured") @Schema(requiredMode = RequiredMode.REQUIRED) Measured measured,
    @JsonProperty("sql") @Schema(requiredMode = RequiredMode.REQUIRED) SqlTotals sql,
    @JsonProperty("ext") @Schema(requiredMode = RequiredMode.REQUIRED) ExtTotals ext,
    @JsonProperty("remote_ip") @Nullable String remoteIp,
    @JsonProperty("ip_mode") String ipMode,
    @JsonProperty("user_id") @ArgosUntrusted @Nullable String userId,
    @JsonProperty("user_id_mode") String userIdMode,
    @JsonProperty("request_id") @ArgosUntrusted @Nullable String requestId,
    @JsonProperty("headers") @ArgosUntrusted Map<String, String> headers,
    @JsonProperty("caller") @Nullable Caller caller,
    @JsonProperty("profile") @Schema(requiredMode = RequiredMode.REQUIRED) Profile profile,
    @JsonProperty("flags") @Schema(requiredMode = RequiredMode.REQUIRED) Flags flags,
    @JsonProperty("links") @Schema(requiredMode = RequiredMode.REQUIRED) Links links,
    @JsonProperty("lookup") @Schema(requiredMode = RequiredMode.REQUIRED) String lookup,
    @JsonProperty("duplicates") @Schema(requiredMode = RequiredMode.REQUIRED)
        List<Duplicate> duplicates) {

  /**
   * An application.
   *
   * @param appId id
   * @param name name, null when the row is gone
   * @param env environment
   */
  @Schema(name = "TransactionApp")
  public record AppRef(
      @JsonProperty("app_id") @Schema(requiredMode = RequiredMode.REQUIRED) int appId,
      @JsonProperty("name") @ArgosUntrusted @Nullable String name,
      @JsonProperty("env") @ArgosUntrusted @Nullable String env) {}

  /**
   * An instance.
   *
   * @param instanceId id
   * @param name the stored name (ADR-0010 C-157 (4)), null when the row is gone
   */
  @Schema(name = "TransactionInstance")
  public record InstanceRef(
      @JsonProperty("instance_id") @Schema(requiredMode = RequiredMode.REQUIRED) int instanceId,
      @JsonProperty("name") @ArgosUntrusted @Nullable String name) {}

  /**
   * Whether CPU time and allocation were measured (D-23: false means "not measurable", the UI says
   * so instead of showing 0).
   *
   * @param cpu CPU time measured
   * @param alloc allocation measured
   */
  @Schema(name = "TransactionMeasured")
  public record Measured(
      @JsonProperty("cpu") @Schema(requiredMode = RequiredMode.REQUIRED) boolean cpu,
      @JsonProperty("alloc") @Schema(requiredMode = RequiredMode.REQUIRED) boolean alloc) {}

  /**
   * SQL totals.
   *
   * @param count statements
   * @param timeMs execute + fetch time
   * @param waitMs connection acquisition wait
   */
  @Schema(name = "TransactionSqlTotals")
  public record SqlTotals(
      @JsonProperty("count") @Schema(requiredMode = RequiredMode.REQUIRED) long count,
      @JsonProperty("time_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long timeMs,
      @JsonProperty("wait_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long waitMs) {}

  /**
   * Outgoing call totals.
   *
   * @param count calls
   * @param timeMs time
   */
  @Schema(name = "TransactionExtTotals")
  public record ExtTotals(
      @JsonProperty("count") @Schema(requiredMode = RequiredMode.REQUIRED) long count,
      @JsonProperty("time_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long timeMs) {}

  /**
   * The calling transaction of a distributed call: the caller application's transaction in the same
   * trace whose run covers this start.
   *
   * @param appId caller application ({@code caller_app_id})
   * @param app its name
   * @param service the caller's service, null when its transaction was not found
   * @param instanceId the caller's instance, null when not found
   * @param txId the caller's transaction, null when not found
   * @param ts its start, null when not found
   */
  @Schema(name = "TransactionCaller")
  public record Caller(
      @JsonProperty("app_id") @Schema(requiredMode = RequiredMode.REQUIRED) int appId,
      @JsonProperty("app") @ArgosUntrusted @Nullable String app,
      @JsonProperty("service") @ArgosUntrusted @Nullable String service,
      @JsonProperty("instance_id") @Nullable Integer instanceId,
      @JsonProperty("tx_id") @Nullable String txId,
      @JsonProperty("ts") @Nullable Instant ts) {}

  /**
   * The profile (D-07, 14 §4.6, ADR-0010 C-154).
   *
   * @param state {@code captured} (steps were sent), {@code sampled_out} (the agent did not capture
   *     them), {@code lost} (captured but no step arrived within the ingest allowance, or older
   *     than the profile retention)
   * @param stepsPending captured, no step yet, and the transaction ended less than 2 minutes ago:
   *     the steps may still arrive
   * @param retentionExceeded the steps are older than the profile retention (7 days by default)
   * @param steps the steps in {@code seq} order
   * @param selfTimeMs time outside the root steps, null without steps
   * @param selfTimeRatio {@code self_time_ms / elapsed_ms}, null without steps
   * @param stepTruncated the agent cut the profile at its step cap (summary steps follow)
   * @param totalSteps steps that actually happened, null when unknown
   * @param paramsMode the bind parameter mode of the instance ({@code masked}, {@code partial},
   *     {@code full}), null when unknown
   */
  @Schema(name = "TransactionProfile")
  public record Profile(
      @JsonProperty("state")
          @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"captured", "sampled_out", "lost"})
          String state,
      @JsonProperty("steps_pending") @Schema(requiredMode = RequiredMode.REQUIRED)
          boolean stepsPending,
      @JsonProperty("retention_exceeded") @Schema(requiredMode = RequiredMode.REQUIRED)
          boolean retentionExceeded,
      @JsonProperty("steps") @Schema(requiredMode = RequiredMode.REQUIRED) List<Step> steps,
      @JsonProperty("self_time_ms") @Nullable Long selfTimeMs,
      @JsonProperty("self_time_ratio") @Nullable Double selfTimeRatio,
      @JsonProperty("step_truncated") @Schema(requiredMode = RequiredMode.REQUIRED)
          boolean stepTruncated,
      @JsonProperty("total_steps") @Nullable Long totalSteps,
      @JsonProperty("params_mode") @Nullable String paramsMode) {}

  /**
   * One profile step. Fields that do not apply to the step type are absent.
   *
   * @param seq sequence number (from 1)
   * @param parentSeq parent step, 0 directly under the transaction
   * @param type {@code SQL}, {@code DBC}, {@code COMMIT}, {@code ROLLBACK}, {@code HTTP}, {@code
   *     METHOD}, {@code ASYNC}, {@code ERROR}, {@code STACK}, {@code MESSAGE}, {@code SUMMARY}
   * @param startOffsetMs start from the transaction start
   * @param elapsedMs elapsed time (folded repeats: their total)
   * @param name the step's name: pool (DBC), host (HTTP, MESSAGE), method, exception class
   * @param nameId its dictionary key
   * @param target the target: database (SQL, DBC, COMMIT, ROLLBACK), host (HTTP)
   * @param status the HTTP status or the failure number (ADR-0010 C-143 (5)); for {@code SUMMARY}
   *     absent (see {@code summary})
   * @param failure {@code timeout}, {@code connect_fail}, {@code http_5xx}, {@code other_error}, or
   *     absent
   * @param error whether it failed
   * @param repeat consecutive repeats folded into this step (1 = single); the UI folds runs of the
   *     same {@code sql_id} and {@code parent_seq} (14 §4.6)
   * @param execMs SQL execute time
   * @param fetchMs SQL fetch time
   * @param rows SQL rows fetched, HTTP response bytes
   * @param batchSize SQL {@code executeBatch} items
   * @param sqlId SQL dictionary key
   * @param sql normalized SQL text, at most 2 KB (the full text is on the SQL detail)
   * @param sqlTruncated whether {@code sql} was cut
   * @param params bind parameters by {@code params_mode}
   * @param paramsMode {@code masked}, {@code partial}, {@code full} or {@code masked_by_role} (a
   *     {@code full} value shown as type and length to a user without {@code pii.view}, 14 §7.3)
   * @param url the URL path of an outgoing call
   * @param frames stack frames (STACK, ERROR)
   * @param summary the summary of a {@code SUMMARY} step (ADR-0010 C-141)
   */
  @Schema(name = "TransactionStep")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Step(
      @JsonProperty("seq") @Schema(requiredMode = RequiredMode.REQUIRED) int seq,
      @JsonProperty("parent_seq") @Schema(requiredMode = RequiredMode.REQUIRED) int parentSeq,
      @JsonProperty("type") @Schema(requiredMode = RequiredMode.REQUIRED) String type,
      @JsonProperty("start_offset_ms") @Schema(requiredMode = RequiredMode.REQUIRED)
          long startOffsetMs,
      @JsonProperty("elapsed_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long elapsedMs,
      @JsonProperty("name") @ArgosUntrusted String name,
      @JsonProperty("name_id") String nameId,
      @JsonProperty("target") @ArgosUntrusted String target,
      @JsonProperty("status") Integer status,
      @JsonProperty("failure") String failure,
      @JsonProperty("error") @Schema(requiredMode = RequiredMode.REQUIRED) boolean error,
      @JsonProperty("repeat") @Schema(requiredMode = RequiredMode.REQUIRED) int repeat,
      @JsonProperty("exec_ms") Long execMs,
      @JsonProperty("fetch_ms") Long fetchMs,
      @JsonProperty("rows") Long rows,
      @JsonProperty("batch_size") Long batchSize,
      @JsonProperty("sql_id") String sqlId,
      @JsonProperty("sql") @ArgosUntrusted String sql,
      @JsonProperty("sql_truncated") Boolean sqlTruncated,
      @JsonProperty("params") @ArgosUntrusted String params,
      @JsonProperty("params_mode") String paramsMode,
      @JsonProperty("url") @ArgosUntrusted String url,
      @JsonProperty("frames") @ArgosUntrusted List<String> frames,
      @JsonProperty("summary") Summary summary) {}

  /**
   * A {@code SUMMARY} step (summary mode, 10 §9.1, ADR-0010 C-141): the steps of one type and name
   * after the recorded ones.
   *
   * @param stepType the summarized step type
   * @param count how many steps
   * @param totalMs their total time
   * @param maxMs the longest one
   * @param failures how many failed
   */
  @Schema(name = "TransactionStepSummary")
  public record Summary(
      @JsonProperty("step_type") @Schema(requiredMode = RequiredMode.REQUIRED) String stepType,
      @JsonProperty("count") @Schema(requiredMode = RequiredMode.REQUIRED) long count,
      @JsonProperty("total_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long totalMs,
      @JsonProperty("max_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long maxMs,
      @JsonProperty("failures") @Schema(requiredMode = RequiredMode.REQUIRED) long failures) {}

  /**
   * Flags of the row (F-A34, F-A28, D-15, ADR-0007 C-69).
   *
   * @param streaming a streaming response
   * @param timeout cut at the agent's max duration
   * @param late arrived more than 60 s after its time
   * @param virtualThread ran on a virtual thread
   * @param leaked ended by the leak watch
   * @param summarized the profile is in summary mode
   * @param incomingTrace joined an incoming {@code traceparent}
   * @param dispatchCount servlet dispatches
   */
  @Schema(name = "TransactionFlags")
  public record Flags(
      @JsonProperty("streaming") @Schema(requiredMode = RequiredMode.REQUIRED) boolean streaming,
      @JsonProperty("timeout") @Schema(requiredMode = RequiredMode.REQUIRED) boolean timeout,
      @JsonProperty("late") @Schema(requiredMode = RequiredMode.REQUIRED) boolean late,
      @JsonProperty("virtual_thread") @Schema(requiredMode = RequiredMode.REQUIRED)
          boolean virtualThread,
      @JsonProperty("leaked") @Schema(requiredMode = RequiredMode.REQUIRED) boolean leaked,
      @JsonProperty("summarized") @Schema(requiredMode = RequiredMode.REQUIRED) boolean summarized,
      @JsonProperty("incoming_trace") @Schema(requiredMode = RequiredMode.REQUIRED)
          boolean incomingTrace,
      @JsonProperty("dispatch_count") @Schema(requiredMode = RequiredMode.REQUIRED)
          int dispatchCount) {}

  /**
   * Related pages.
   *
   * @param trace the trace view ({@code /traces/{traceId}?ts=...}), null without a trace
   * @param logs the log search link of {@code argos.ui.log-link-template} with the transaction's
   *     values (F-U18, {@link LogLinks}); null without a template
   */
  @Schema(name = "TransactionLinks")
  public record Links(
      @JsonProperty("trace") @Nullable String trace, @JsonProperty("logs") @Nullable String logs) {}

  /**
   * Another row with the same {@code tx_id} (13 §17 #11).
   *
   * @param appId application
   * @param instanceId instance
   * @param ts start
   */
  @Schema(name = "TransactionDuplicate")
  public record Duplicate(
      @JsonProperty("app_id") @Schema(requiredMode = RequiredMode.REQUIRED) int appId,
      @JsonProperty("instance_id") @Schema(requiredMode = RequiredMode.REQUIRED) int instanceId,
      @JsonProperty("ts") @Schema(requiredMode = RequiredMode.REQUIRED) Instant ts) {}
}
