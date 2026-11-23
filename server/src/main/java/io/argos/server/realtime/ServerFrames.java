/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The server frames of 14 §5.2 as JSON text (ADR-0010 C-167 (1); {@code reauth} C-167 (2)): {@code
 * ack}, {@code push}, {@code error}, {@code pong}, {@code notice} and {@code reauth}. The envelope
 * is written here; a push's {@code data} and {@code dict} arrive as JSON already. Times are
 * ISO-8601 UTC with milliseconds ({@code 2026-10-23T01:02:03.456Z}).
 */
public final class ServerFrames {

  /** {@code error} code: the role does not allow the topic (14 §5.2). */
  public static final String FORBIDDEN = "forbidden";

  /** {@code error} code: a subscription or rate limit was reached (14 §5.1). */
  public static final String LIMIT_EXCEEDED = "limit_exceeded";

  /** {@code error} code: the frame or its parameters are not valid. */
  public static final String INVALID_PARAMS = "invalid_params";

  /** {@code error} code: no such topic (14 §5.3). */
  public static final String TOPIC_UNKNOWN = "topic_unknown";

  /** {@code notice} code of a backpressure drop (14 §5.3). */
  public static final String BACKPRESSURE = "backpressure";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private ServerFrames() {}

  /** The frame time format of 14 §5.2. */
  public static String time(Instant t) {
    return TIME.format(t);
  }

  /** The frame time of epoch milliseconds. */
  public static String time(long epochMs) {
    return TIME.format(Instant.ofEpochMilli(epochMs));
  }

  /** {@code {"op":"ack","id":..,"topic":..,"since":..}}. */
  public static String ack(String id, Topic topic, Instant since) {
    return "{\"op\":\"ack\",\"id\":"
        + str(id)
        + ",\"topic\":"
        + str(topic.wire())
        + ",\"since\":"
        + str(time(since))
        + "}";
  }

  /**
   * {@code {"op":"push","id":..,"topic":..,"t":..,"data":{..},"dict":{..},"dropped":n}}.
   *
   * @param dataJson the {@code data} object as JSON
   * @param dictJson the {@code dict} object as JSON, null to leave the field out
   * @param dropped points dropped for this subscription since its previous push, null to leave the
   *     field out
   */
  public static String push(
      String id, Topic topic, long tMs, String dataJson, String dictJson, Long dropped) {
    StringBuilder b =
        new StringBuilder(dataJson.length() + (dictJson == null ? 0 : dictJson.length()) + 128);
    b.append("{\"op\":\"push\",\"id\":")
        .append(str(id))
        .append(",\"topic\":")
        .append(str(topic.wire()))
        .append(",\"t\":")
        .append(str(time(tMs)))
        .append(",\"data\":")
        .append(dataJson);
    if (dictJson != null) {
      b.append(",\"dict\":").append(dictJson);
    }
    if (dropped != null) {
      b.append(",\"dropped\":").append(dropped.longValue());
    }
    return b.append('}').toString();
  }

  /**
   * {@code {"op":"error","id":..,"code":..,"message":..}}.
   *
   * @param id the subscription id, null when the frame had none
   * @param message an English message without sent values, null to leave it out
   */
  public static String error(String id, String code, String message) {
    StringBuilder b = new StringBuilder(96).append("{\"op\":\"error\"");
    if (id != null) {
      b.append(",\"id\":").append(str(id));
    }
    b.append(",\"code\":").append(str(code));
    if (message != null) {
      b.append(",\"message\":").append(str(message));
    }
    return b.append('}').toString();
  }

  /** {@code {"op":"pong","t":<client t>,"server_t":..}}. */
  public static String pong(long clientT, Instant serverT) {
    return "{\"op\":\"pong\",\"t\":" + clientT + ",\"server_t\":" + str(time(serverT)) + "}";
  }

  /** {@code {"op":"notice","code":"backpressure","topic":..,"dropped":n}}. */
  public static String backpressure(Topic topic, long dropped) {
    return "{\"op\":\"notice\",\"code\":\""
        + BACKPRESSURE
        + "\",\"topic\":"
        + str(topic.wire())
        + ",\"dropped\":"
        + dropped
        + "}";
  }

  /** {@code {"op":"reauth"}}: the session expired or was revoked; the server closes with 4401. */
  public static String reauth() {
    return "{\"op\":\"reauth\"}";
  }

  /** A JSON string literal. */
  static String str(String value) {
    return JSON.writeValueAsString(value);
  }
}
