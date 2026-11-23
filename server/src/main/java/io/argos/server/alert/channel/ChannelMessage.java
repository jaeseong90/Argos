/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import io.argos.server.alert.api.AlertNotification;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a channel sends, independent of its format: a notification of the engine ({@link
 * #of(AlertNotification)}) or a test message ({@link #test}). Only allowed template variables
 * (SEC-39, {@link AlertNotification#VARIABLES}) and rendered texts are in it; collected raw text
 * never is.
 *
 * <p>The webhook payload ({@link #webhookJson()}, 12 §5.5, 16 §8.4, T-API-008) is:
 *
 * <pre>{@code
 * {"schema_version": 1,
 *  "alert": {"kind": "firing|resolved|renotify|escalated|self|test", "severity": "warn|critical",
 *            "app": {"id": 3, "name": "shop"} | null, "env": "prod" | null,
 *            "title": "...", "text": "...",
 *            "items": [{"event_id", "rule_id", "kind", "severity", "at", "message",
 *                       "variables": {<allowed template variables>}}],
 *            "omitted": 0},
 *  "link": "<deep link of the first item>" | null}
 * }</pre>
 *
 * @param kind lower-case kind
 * @param severity {@code warn} or {@code critical}
 * @param appId the application id, or null
 * @param appName the application name, or null
 * @param env the environment, or null
 * @param title one-line title
 * @param text rendered body
 * @param items the items
 * @param omitted items left out
 * @param link the deep link, or null
 */
public record ChannelMessage(
    String kind,
    String severity,
    Integer appId,
    String appName,
    String env,
    String title,
    String text,
    List<AlertNotification.Item> items,
    int omitted,
    String link) {

  /** The payload schema version (16 §8.4). */
  public static final int SCHEMA_VERSION = 1;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public ChannelMessage {
    Objects.requireNonNull(kind, "kind");
    items = List.copyOf(items);
  }

  /** The message of an engine notification. */
  public static ChannelMessage of(AlertNotification n) {
    String link = n.items().isEmpty() ? null : n.items().get(0).variables().get("link");
    return new ChannelMessage(
        n.kind().name().toLowerCase(Locale.ROOT),
        n.group().severity().wire(),
        n.group().appId(),
        n.group().appName(),
        n.group().env(),
        n.title(),
        n.body(),
        n.items(),
        n.omitted(),
        link);
  }

  /** The message of a test send ({@code POST /alerts/channels/{id}/test}). */
  public static ChannelMessage test(String channelName, String link) {
    return new ChannelMessage(
        "test",
        "warn",
        null,
        null,
        null,
        "Argos test notification",
        "This is a test notification of the channel \"" + channelName + "\". No alert is firing.",
        List.of(),
        0,
        link);
  }

  /** The webhook JSON body (UTF-8). */
  public byte[] webhookJson() {
    ObjectNode root = JSON.createObjectNode();
    root.put("schema_version", SCHEMA_VERSION);
    ObjectNode alert = root.putObject("alert");
    alert.put("kind", kind);
    alert.put("severity", severity);
    if (appId == null) {
      alert.putNull("app");
    } else {
      ObjectNode app = alert.putObject("app");
      app.put("id", appId);
      app.put("name", appName);
    }
    if (env == null) {
      alert.putNull("env");
    } else {
      alert.put("env", env);
    }
    alert.put("title", title);
    alert.put("text", text);
    ArrayNode array = alert.putArray("items");
    for (AlertNotification.Item item : items) {
      ObjectNode i = array.addObject();
      i.put("event_id", item.eventId().toString());
      i.put("rule_id", item.ruleId().toString());
      i.put("kind", item.kind().name().toLowerCase(Locale.ROOT));
      i.put("severity", item.severity().wire());
      i.put("at", iso(item.at()));
      i.put("message", item.message());
      ObjectNode variables = i.putObject("variables");
      for (Map.Entry<String, String> v : item.variables().entrySet()) {
        if (AlertNotification.VARIABLES.contains(v.getKey())) {
          variables.put(v.getKey(), v.getValue());
        }
      }
    }
    alert.put("omitted", omitted);
    if (link == null) {
      root.putNull("link");
    } else {
      root.put("link", link);
    }
    return JSON.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The Slack incoming webhook body: {@code {"text": ...}} with {@code &}, {@code <} and {@code >}
   * escaped as Slack requires, the link as {@code <url|Open in Argos>}.
   */
  public byte[] slackJson() {
    StringBuilder sb = new StringBuilder();
    sb.append('*').append(slackEscape(title)).append("*\n").append(slackEscape(text));
    if (link != null && link.indexOf('|') < 0 && link.indexOf('>') < 0) {
      sb.append("\n<").append(slackEscape(link)).append("|Open in Argos>");
    }
    ObjectNode root = JSON.createObjectNode();
    root.put("text", sb.toString());
    return JSON.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
  }

  /** The mail subject: {@code [Argos] <title>}, one line. */
  public String mailSubject() {
    return "[Argos] " + oneLine(title);
  }

  /** The mail body (plain text). */
  public String mailBody() {
    StringBuilder sb = new StringBuilder(text);
    if (omitted > 0 && !text.contains("more")) {
      sb.append("\n\n... and ").append(omitted).append(" more");
    }
    if (link != null) {
      sb.append("\n\n").append(link);
    }
    sb.append("\n\n-- \nSent by Argos APM\n");
    return sb.toString();
  }

  private static String slackEscape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  static String oneLine(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      sb.append(Character.isISOControl(c) ? ' ' : c);
    }
    String out = sb.toString().strip();
    return out.length() > 200 ? out.substring(0, 200) : out;
  }

  private static String iso(Instant at) {
    return at.truncatedTo(ChronoUnit.MILLIS).toString();
  }
}
