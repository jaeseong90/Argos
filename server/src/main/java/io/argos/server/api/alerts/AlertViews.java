/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.alerts;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.alert.engine.ActiveAlert;
import io.argos.server.alert.metric.AlertMetric;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The alert items that the dashboard ({@code data.firing[]}, 14 §4.4), the overview ({@code GET
 * /overview/alerts}, 14 §4.2) and the alert API share: the active alert of the 14 §4.13 event
 * schema (WO-25 plan §3.1, ADR-0010 C-212 (2)). Names of applications, rules and targets and the
 * acknowledgement note are entered or collected text (x-argos-untrusted, 14 §2.10).
 */
public final class AlertViews {

  private AlertViews() {}

  static Instant millis(Instant instant) {
    return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
  }

  /**
   * The application of an alert.
   *
   * @param id the application id
   * @param name its name, or null when the application is gone
   */
  @Schema(name = "AlertApp")
  public record AlertApp(int id, @ArgosUntrusted @Nullable String name) {}

  /**
   * What an alert is about.
   *
   * @param type {@code app}, {@code instance}, {@code service}, {@code ext_host} or {@code server}
   * @param id the instance id, service key or host key (decimal), or null for the application or
   *     the server
   * @param name the instance name, service or host text, or channel name, or null
   */
  @Schema(name = "AlertTarget")
  public record AlertTarget(
      @Schema(allowableValues = {"app", "instance", "service", "ext_host", "server"}) String type,
      @Schema(pattern = "^[0-9]{1,20}$") @Nullable String id,
      @ArgosUntrusted @Nullable String name) {}

  /**
   * The unit of a metric's values as the API names it ({@link AlertMetric.Unit} in lower case: ms,
   * seconds, days, ratio, score, count, per_second, state), or null for an unknown metric.
   */
  public static @Nullable String unit(@Nullable String metric) {
    AlertMetric m = AlertMetric.of(metric);
    return m == null ? null : m.unit().name().toLowerCase(Locale.ROOT);
  }

  /**
   * The rule of an alert, with the metric and unit of its value and threshold (ADR-0010 C-227 (4),
   * WO-38: a response addition to the 14 §4.13 {@code rule {id, name}}).
   *
   * @param id the rule id
   * @param name its name
   * @param metric the rule's metric (F-S31 wire name)
   * @param unit the unit of {@code value} and {@code threshold}; null for an unknown metric
   */
  @Schema(name = "AlertRuleRef")
  public record AlertRuleRef(
      String id,
      @ArgosUntrusted String name,
      @Schema(
              description = "The rule's metric (F-S31 wire name, as AlertRule.metric)",
              allowableValues = {
                "response_time_p50",
                "response_time_p95",
                "response_time_p99",
                "tps",
                "error_rate",
                "apdex",
                "active_transactions",
                "active_ratio_to_max_threads",
                "jvm_heap_used_ratio",
                "jvm_old_gen_after_gc_ratio",
                "jvm_gc_pause_ratio",
                "jvm_threads_live",
                "tomcat_threads_usage_ratio",
                "jdbc_pool_waiting",
                "jdbc_pool_usage_ratio",
                "ext_call_p95",
                "ext_call_error_ratio",
                "ext_call_timeout_ratio",
                "batch_elapsed_seconds",
                "system_cpu_ratio",
                "system_memory_ratio",
                "system_disk_usage_ratio",
                "argos_agent_dropped_total",
                "argos_server_dropped_total",
                "argos_server_ingest_lag_seconds",
                "argos_server_disk_usage_ratio",
                "argos_notification_failures_total",
                "argos_server_tls_cert_expiry_days",
                "agent_token_expiry_days",
                "instance_state",
                "error_group_new"
              })
          String metric,
      @Schema(
              allowableValues = {
                "ms",
                "seconds",
                "days",
                "ratio",
                "score",
                "count",
                "per_second",
                "state"
              },
              description =
                  "Unit of value and threshold: ratio is 0..1 (shown as a percentage), score is"
                      + " Apdex 0..1, state 1 = DOWN")
          @Nullable String unit) {

    /** The reference of rule {@code id}; the unit follows from {@code metric}. */
    public static AlertRuleRef of(String id, String name, String metric) {
      return new AlertRuleRef(id, name, metric, AlertViews.unit(metric));
    }
  }

  /**
   * One active alert (FIRING or ACKED).
   *
   * @param eventId the FIRING event of the current firing: the acknowledgement target and the deep
   *     link's {@code alert}
   * @param ts when it fired
   * @param app the application, null for a server self alert
   * @param env the environment, or null
   * @param target what it is about
   * @param rule the rule
   * @param severity {@code warn} or {@code critical}
   * @param state {@code FIRING} or {@code ACKED}
   * @param value the last evaluated value, or null
   * @param threshold the threshold of the severity, or null
   * @param durationS seconds since it fired
   * @param ackedBy the acknowledging user's name, or null
   * @param ackedAt the acknowledgement time, or null
   * @param note the acknowledgement note, or null
   * @param deeplink the 14 §6 deep link (absolute times ±15 minutes around the firing, no live)
   */
  @Schema(name = "AlertEventItem")
  public record AlertEventItem(
      @JsonProperty("event_id") String eventId,
      Instant ts,
      @Nullable AlertApp app,
      @ArgosUntrusted @Nullable String env,
      AlertTarget target,
      AlertRuleRef rule,
      @Schema(allowableValues = {"warn", "critical"}) String severity,
      @Schema(allowableValues = {"FIRING", "ACKED"}) String state,
      @Nullable Double value,
      @Nullable Double threshold,
      @JsonProperty("duration_s") long durationS,
      @JsonProperty("acked_by") @ArgosUntrusted @Nullable String ackedBy,
      @JsonProperty("acked_at") @Nullable Instant ackedAt,
      @ArgosUntrusted @Nullable String note,
      String deeplink) {

    /** The item of an active alert; its duration counted up to {@code now}. */
    public static AlertEventItem of(ActiveAlert a, Instant now) {
      long duration = Math.max(0, Duration.between(a.ts(), now).toSeconds());
      String ackedBy =
          a.ackedByName() != null
              ? a.ackedByName()
              : a.ackedBy() == null ? null : a.ackedBy().toString();
      return new AlertEventItem(
          a.eventId() == null ? null : a.eventId().toString(),
          millis(a.ts()),
          a.appId() == null ? null : new AlertApp(a.appId(), a.appName()),
          a.env(),
          new AlertTarget(a.targetType(), a.targetId(), a.targetName()),
          AlertRuleRef.of(a.ruleId().toString(), a.ruleName(), a.ruleMetric()),
          a.severity(),
          a.state(),
          a.value(),
          a.threshold(),
          duration,
          ackedBy,
          millis(a.ackedAt()),
          a.note(),
          a.deeplink());
    }
  }

  /**
   * Firing alerts of one application by severity (14 §4.2 {@code firing}): the FIRING and ACKED
   * alerts.
   *
   * @param critical active critical alerts
   * @param warn active warn alerts
   */
  @Schema(name = "FiringCounts")
  public record FiringCounts(int critical, int warn) {}
}
