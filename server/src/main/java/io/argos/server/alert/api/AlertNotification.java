/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One notification the alert engine hands to the delivery side (12 §5.4 grouping, §5.5; WO-25 plan
 * §3.2): the items of one group ({@code app}, {@code env}, {@code severity}), at most {@link
 * #MAX_ITEMS} of them plus the count of the ones left out ("and N more"), with their template
 * variables, the rendered title and body, and the channels to deliver to.
 *
 * <p>Only the allowed template variables ({@link #VARIABLES}, SEC-39, 14 §4.13) are in an item's
 * map; collected raw text (SQL, exception messages, URLs, user ids) is never a variable. The values
 * are plain text: a channel escapes them for its format (JSON, Slack mrkdwn, mail).
 *
 * @param group the group key
 * @param kind why the notification is sent
 * @param items the items, at most {@link #MAX_ITEMS}, oldest first
 * @param omitted how many further items of the group window were left out ("and N more")
 * @param title rendered one-line title (mail subject, Slack header)
 * @param body rendered body (one rendered message per item, then "and N more")
 * @param recipients the channels to deliver to
 * @param eventIds the {@code alert_events.id} of every item, the left-out ones included
 */
public record AlertNotification(
    Group group,
    Kind kind,
    List<Item> items,
    int omitted,
    String title,
    String body,
    Recipients recipients,
    List<UUID> eventIds) {

  /** Items per notification (12 §5.4). */
  public static final int MAX_ITEMS = 20;

  /**
   * The allowed template variables, in the order of 12 §5.5 (SEC-39). {@code started_at} is in
   * {@code argos.server.timezone} with its UTC offset; {@code link} is the 14 §6 deep link.
   */
  public static final Set<String> VARIABLES =
      Set.of(
          "app",
          "env",
          "instance",
          "service",
          "host",
          "metric",
          "value",
          "threshold",
          "severity",
          "duration",
          "started_at",
          "link",
          "runbook",
          "rule");

  /** Validates and copies. */
  public AlertNotification {
    Objects.requireNonNull(group, "group");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(recipients, "recipients");
    items = List.copyOf(items);
    if (items.isEmpty() || items.size() > MAX_ITEMS) {
      throw new IllegalArgumentException("1.." + MAX_ITEMS + " items, got " + items.size());
    }
    if (omitted < 0) {
      throw new IllegalArgumentException("omitted < 0");
    }
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(body, "body");
    eventIds = List.copyOf(eventIds);
  }

  /** Why a notification is sent. */
  public enum Kind {
    /** A rule started firing (PENDING or OK to FIRING). */
    FIRING,
    /** A firing (or acknowledged) alert resolved: always sent (12 §5.4). */
    RESOLVED,
    /** Still firing after {@code renotify_interval} (never while acknowledged). */
    RENOTIFY,
    /** The severity of a firing alert rose (warn to critical): sent at once. */
    ESCALATED,
    /**
     * A server self alert ({@code channel_failure}, 12 §5.5): goes to every enabled channel except
     * the failing one ({@link Recipients#allEnabledExcept}).
     */
    SELF
  }

  /** Severity of an alert (two levels, 12 §5.4). */
  public enum Severity {
    /** Warning level. */
    WARN,
    /** Critical level. */
    CRITICAL;

    /** The API and stored value ({@code warn}, {@code critical}). */
    public String wire() {
      return this == WARN ? "warn" : "critical";
    }
  }

  /**
   * The group key: one application environment and severity (12 §5.4).
   *
   * @param appId {@code apps.id}, or null for a server self alert
   * @param appName the application's name (untrusted text), or null for a server self alert
   * @param env the environment, or null when the rule applies to every environment
   * @param severity the severity
   */
  public record Group(Integer appId, String appName, String env, Severity severity) {

    /** Validates. */
    public Group {
      Objects.requireNonNull(severity, "severity");
    }
  }

  /**
   * One alert of a notification.
   *
   * @param eventId the {@code alert_events.id} of the transition
   * @param ruleId the rule ({@code alert_rules.id})
   * @param kind the transition of this item
   * @param severity the item's severity
   * @param at server time of the transition
   * @param variables the allowed template variables ({@link #VARIABLES}) with their values; a
   *     variable without a value is absent
   * @param message the rule's rendered message template for this item
   */
  public record Item(
      UUID eventId,
      UUID ruleId,
      Kind kind,
      Severity severity,
      Instant at,
      Map<String, String> variables,
      String message) {

    /** Validates and copies; refuses a variable outside {@link #VARIABLES}. */
    public Item {
      Objects.requireNonNull(eventId, "eventId");
      Objects.requireNonNull(ruleId, "ruleId");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(severity, "severity");
      Objects.requireNonNull(at, "at");
      Map<String, String> copy = new LinkedHashMap<>();
      for (Map.Entry<String, String> e : variables.entrySet()) {
        if (!VARIABLES.contains(e.getKey())) {
          throw new IllegalArgumentException("not an allowed template variable: " + e.getKey());
        }
        if (e.getValue() != null) {
          copy.put(e.getKey(), e.getValue());
        }
      }
      variables = Collections.unmodifiableMap(copy);
      Objects.requireNonNull(message, "message");
    }
  }

  /**
   * The channels of a notification: either the listed channel ids, or every enabled channel except
   * one (a server self alert about that channel, 12 §5.5).
   *
   * @param channelIds the channels ({@code notification_channels.id}), deduplicated, in order
   * @param allEnabledExcept when not null: every enabled channel except this one ({@code
   *     channelIds} is then empty)
   */
  public record Recipients(List<UUID> channelIds, UUID allEnabledExcept) {

    /** Validates and copies. */
    public Recipients {
      List<UUID> distinct = new ArrayList<>();
      for (UUID id : channelIds) {
        if (!distinct.contains(Objects.requireNonNull(id, "channel id"))) {
          distinct.add(id);
        }
      }
      channelIds = List.copyOf(distinct);
      if (allEnabledExcept != null && !channelIds.isEmpty()) {
        throw new IllegalArgumentException("channelIds must be empty with allEnabledExcept");
      }
    }

    /** The listed channels. */
    public static Recipients of(List<UUID> channelIds) {
      return new Recipients(channelIds, null);
    }

    /** Every enabled channel except {@code channelId}. */
    public static Recipients allEnabledExcept(UUID channelId) {
      return new Recipients(List.of(), Objects.requireNonNull(channelId, "channelId"));
    }

    /** Whether nothing would be delivered (no channel listed and no "all except"). */
    public boolean isEmpty() {
      return channelIds.isEmpty() && allEnabledExcept == null;
    }
  }
}
