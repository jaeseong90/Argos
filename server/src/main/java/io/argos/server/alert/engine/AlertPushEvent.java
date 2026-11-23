/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.engine;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The event object of one {@code alert} push (docs/14 §5.3, the §4.13 event schema): what the alert
 * engine hands to {@link io.argos.server.realtime.RealtimeBus#publishAlert} for every stored
 * transition. Every push carries the transition's {@code reason}, the same value as its {@code
 * alert_events.reason} row (ADR-0010 C-228 (2)); null only if a transition had none.
 *
 * @param eventId the transition's {@code alert_events.id}
 * @param tsMs server time of the transition
 * @param appId the application, null for a server self alert
 * @param appName the application name, null when unknown
 * @param env the environment, null for a server self alert
 * @param targetType the evaluation unit's target type ({@code instance}, {@code service}, ...)
 * @param targetId the target id (unsigned for a service), null for an application unit
 * @param targetName the target's name, null when unknown
 * @param ruleId the rule
 * @param ruleName the rule's name
 * @param severity {@code warn} or {@code critical}, null when the transition has none
 * @param state the state after the transition
 * @param value the evaluated value, null without one (no data, target gone)
 * @param threshold the threshold of the severity, null without one
 * @param durationS seconds the alert fired, 0 when not applicable
 * @param reason the closed-list reason ({@code condition}, {@code cleared}, ...), as stored
 * @param deeplink the screen link of the alert
 */
public record AlertPushEvent(
    UUID eventId,
    long tsMs,
    Integer appId,
    String appName,
    String env,
    String targetType,
    Long targetId,
    String targetName,
    UUID ruleId,
    String ruleName,
    String severity,
    String state,
    Double value,
    Double threshold,
    int durationS,
    String reason,
    String deeplink) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The event as the JSON object of the push ({@code {"event": <this>}} is added by the bus). */
  public String toJson() {
    ObjectNode e = JSON.createObjectNode();
    e.put("event_id", eventId.toString());
    e.put("ts", Instant.ofEpochMilli(tsMs).toString());
    if (appId == null) {
      e.putNull("app");
    } else {
      ObjectNode a = e.putObject("app");
      a.put("id", appId.intValue());
      a.put("name", appName);
    }
    e.put("env", env);
    ObjectNode t = e.putObject("target");
    t.put("type", targetType);
    t.put("id", targetId == null ? null : Long.toUnsignedString(targetId));
    t.put("name", targetName);
    ObjectNode rule = e.putObject("rule");
    rule.put("id", ruleId.toString());
    rule.put("name", ruleName);
    e.put("severity", severity);
    e.put("state", state);
    if (value == null) {
      e.putNull("value");
    } else {
      e.put("value", value.doubleValue());
    }
    if (threshold == null) {
      e.putNull("threshold");
    } else {
      e.put("threshold", threshold.doubleValue());
    }
    e.put("duration_s", durationS);
    e.put("reason", reason);
    e.put("deeplink", deeplink);
    return JSON.writeValueAsString(e);
  }
}
