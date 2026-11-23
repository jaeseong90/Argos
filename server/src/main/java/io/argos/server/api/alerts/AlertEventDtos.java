/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.alerts;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.alerts.AlertViews.AlertApp;
import io.argos.server.api.alerts.AlertViews.AlertRuleRef;
import io.argos.server.api.alerts.AlertViews.AlertTarget;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.argos.server.storage.postgres.alert.rule.AlertQueryRepository.DeliveryRow;
import io.argos.server.storage.postgres.alert.rule.AlertQueryRepository.EventRow;
import io.argos.server.storage.postgres.alert.rule.AlertQueryRepository.RuleStatsRow;
import io.argos.server.storage.postgres.alert.rule.MuteRepository.MuteRow;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Bodies of {@code /alerts/events*}, {@code /alerts/mutes*} and {@code /alerts/stats} (14 §4.13).
 * Request and response records are separate.
 */
final class AlertEventDtos {

  private AlertEventDtos() {}

  /**
   * The delivery of an event to one channel (F-S27).
   *
   * @param channelId the channel
   * @param status {@code pending}, {@code sent}, {@code failed} or {@code dropped}
   * @param attempts attempts so far
   * @param lastErrorCode the error code of the last attempt (never a response body), or null
   */
  @Schema(name = "AlertDelivery")
  record Delivery(
      @JsonProperty("channel_id") String channelId,
      @Schema(allowableValues = {"pending", "sent", "failed", "dropped"}) String status,
      int attempts,
      @JsonProperty("last_error_code") @Nullable String lastErrorCode) {

    static Delivery of(DeliveryRow d) {
      return new Delivery(
          d.channelId() == null ? null : d.channelId().toString(),
          d.status(),
          d.attempts(),
          d.lastErrorCode());
    }
  }

  /**
   * One state transition of the history (14 §4.13 event schema).
   *
   * @param eventId the event
   * @param ts the transition time
   * @param app the application, null for a server self alert
   * @param env the environment, or null
   * @param target what it is about
   * @param rule the rule
   * @param severity warn, critical or null
   * @param state the state the transition led to
   * @param fromState the state before
   * @param reason why the transition happened (C-227 (3)), null for events stored before it
   * @param value the value at the transition, or null
   * @param threshold the threshold, or null
   * @param durationS seconds the condition held (FIRING) or the alert fired (RESOLVED), or null
   * @param deliveries the notifications of this event, one per channel
   * @param ackedBy the acknowledging user's name (ACKED), or null
   * @param ackedAt the acknowledgement time (ACKED: the transition time), or null
   * @param note the acknowledgement note, or null
   * @param deeplink the 14 §6 deep link around the transition
   */
  @Schema(name = "AlertEvent")
  record Event(
      @JsonProperty("event_id") String eventId,
      Instant ts,
      @Nullable AlertApp app,
      @ArgosUntrusted @Nullable String env,
      AlertTarget target,
      AlertRuleRef rule,
      @Schema(allowableValues = {"warn", "critical"}) @Nullable String severity,
      @Schema(allowableValues = {"OK", "PENDING", "FIRING", "ACKED", "RESOLVED", "NO_DATA"})
          String state,
      @Schema(allowableValues = {"OK", "PENDING", "FIRING", "ACKED", "RESOLVED", "NO_DATA"})
          @JsonProperty("from_state")
          @Nullable String fromState,
      @Schema(
              allowableValues = {
                "condition",
                "cleared",
                "escalated",
                "acked",
                "resolved",
                "target_gone",
                "rule_disabled",
                "rule_deleted",
                "no_data",
                "data_returned"
              },
              description =
                  "Why the transition happened; null for events stored before the reason was"
                      + " recorded")
          @Nullable String reason,
      @Nullable Double value,
      @Nullable Double threshold,
      @JsonProperty("duration_s") @Nullable Integer durationS,
      List<Delivery> deliveries,
      @JsonProperty("acked_by") @ArgosUntrusted @Nullable String ackedBy,
      @JsonProperty("acked_at") @Nullable Instant ackedAt,
      @ArgosUntrusted @Nullable String note,
      String deeplink) {

    static Event of(EventRow e, List<DeliveryRow> deliveries, String deeplink) {
      boolean acked = "ACKED".equals(e.toState());
      String ackedBy =
          e.ackedByName() != null
              ? e.ackedByName()
              : e.ackedBy() == null ? null : e.ackedBy().toString();
      return new Event(
          e.id().toString(),
          AlertViews.millis(e.ts()),
          e.appId() == null ? null : new AlertApp(e.appId(), e.appName()),
          e.env(),
          new AlertTarget(
              e.targetKind() == null ? "server" : e.targetKind(),
              e.targetId() == null ? null : Long.toUnsignedString(e.targetId()),
              e.targetName()),
          AlertRuleRef.of(e.ruleId().toString(), e.ruleName(), e.ruleMetric()),
          e.severity(),
          e.toState(),
          e.fromState(),
          e.reason(),
          e.value(),
          e.threshold(),
          e.durationS(),
          deliveries.stream().map(Delivery::of).toList(),
          ackedBy,
          acked ? AlertViews.millis(e.ts()) : null,
          e.ackNote(),
          deeplink);
    }
  }

  /**
   * The answer of an acknowledgement.
   *
   * @param eventId the acknowledged firing
   * @param state {@code ACKED}
   * @param ackedBy the acknowledging user's name
   * @param ackedAt when
   * @param note the note, or null
   */
  @Schema(name = "AlertAck")
  record Acked(
      @JsonProperty("event_id") String eventId,
      @Schema(allowableValues = {"ACKED"}) String state,
      @JsonProperty("acked_by") @ArgosUntrusted String ackedBy,
      @JsonProperty("acked_at") Instant ackedAt,
      @ArgosUntrusted @Nullable String note) {}

  /** {@code POST /alerts/events/{eventId}/ack}. */
  @Schema(name = "AlertAckRequest")
  record AckRequest(
      @Schema(description = "A note, at most 1 KB (UTF-8) and 512 characters")
          @Nullable String note) {}

  /** {@code target} of a mute request; every field optional (absent: any). */
  @Schema(name = "AlertMuteTargetRequest")
  record MuteTargetRequest(
      @Nullable Integer app,
      @Nullable String env,
      @Nullable Integer instance,
      @Schema(format = "uuid") @Nullable String rule) {}

  /** {@code POST /alerts/mutes}: at most 30 days, ending in the future. */
  @Schema(name = "AlertMuteRequest")
  record MuteRequest(
      @Nullable MuteTargetRequest target,
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "ISO-8601 UTC") String from,
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "ISO-8601 UTC") String to,
      @Schema(description = "At most 1 KB (UTF-8) and 512 characters") @Nullable String reason) {}

  /** {@code target} of a mute. */
  @Schema(name = "AlertMuteTarget")
  record MuteTarget(
      @Nullable Integer app,
      @ArgosUntrusted @Nullable String env,
      @Nullable Integer instance,
      @Nullable String rule) {}

  /** A one-off mute. */
  @Schema(name = "AlertMute")
  record Mute(
      @JsonProperty("mute_id") String muteId,
      MuteTarget target,
      Instant from,
      Instant to,
      @ArgosUntrusted @Nullable String reason,
      @JsonProperty("created_by") @Nullable String createdBy,
      @JsonProperty("created_at") @Nullable Instant createdAt) {

    static Mute of(MuteRow m) {
      return new Mute(
          m.id().toString(),
          new MuteTarget(
              m.appId(),
              m.env(),
              m.instanceId(),
              m.ruleId() == null ? null : m.ruleId().toString()),
          AlertViews.millis(m.startsAt()),
          AlertViews.millis(m.endsAt()),
          m.reason(),
          m.createdBy() == null ? null : m.createdBy().toString(),
          AlertViews.millis(m.createdAt()));
    }
  }

  /** The statistics of one rule (14 §4.13 {@code /alerts/stats}). */
  @Schema(name = "AlertRuleStats")
  record RuleStats(
      @JsonProperty("rule_id") String ruleId,
      @ArgosUntrusted String name,
      long firings,
      @JsonProperty("avg_duration_s") @Nullable Double avgDurationS,
      @JsonProperty("avg_time_to_ack_s") @Nullable Double avgTimeToAckS,
      @JsonProperty("avg_time_to_resolve_s") @Nullable Double avgTimeToResolveS) {

    static RuleStats of(RuleStatsRow r) {
      return new RuleStats(
          r.ruleId().toString(),
          r.name(),
          r.firings(),
          round(r.avgDurationS()),
          round(r.avgTimeToAckS()),
          round(r.avgTimeToResolveS()));
    }

    private static Double round(Double v) {
      return v == null ? null : Math.round(v * 10.0) / 10.0;
    }
  }
}
