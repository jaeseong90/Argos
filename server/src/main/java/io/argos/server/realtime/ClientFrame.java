/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A client frame of 14 §5.2 (ADR-0010 C-167 (1)): {@code subscribe {id, topic, params}}, {@code
 * unsubscribe {id}}, {@code ping {t}}. {@link #parse(String)} is strict: a message that is not a
 * JSON object, an unknown {@code op}, a missing or extra field, or a field of the wrong type is an
 * {@link Invalid} frame, answered {@code error invalid_params} (with the {@code id} when one could
 * be read). The topic name and the parameters are checked later ({@code topic_unknown}, {@link
 * TopicRequest}).
 */
sealed interface ClientFrame {

  /** Longest subscription id (the UI's frame schema, {@code ui/src/realtime/frames.ts}). */
  int MAX_ID_LENGTH = 64;

  /** Longest topic name that is looked up (longer is {@code topic_unknown} anyway). */
  int MAX_TOPIC_LENGTH = 32;

  /** {@code subscribe}: {@code params} is the parameter object as plain Java values. */
  record Subscribe(String id, String topic, Map<String, Object> params) implements ClientFrame {
    /** Copies. */
    public Subscribe {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(topic, "topic");
      params = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
  }

  /** {@code unsubscribe}. */
  record Unsubscribe(String id) implements ClientFrame {}

  /** {@code ping}: {@code t} is the client's time, echoed in the {@code pong}. */
  record Ping(long t) implements ClientFrame {}

  /**
   * A frame that breaks 14 §5.2.
   *
   * @param id the frame's {@code id} when it was a valid id string, else null
   * @param message a fixed English reason without sent values
   */
  record Invalid(String id, String message) implements ClientFrame {}

  /** Parses one complete client message. */
  static ClientFrame parse(String text) {
    JsonNode node;
    try {
      node = Parser.JSON.readTree(text);
    } catch (JacksonException e) {
      return new Invalid(null, "frame: not_json");
    }
    if (!(node instanceof ObjectNode frame)) {
      return new Invalid(null, "frame: not_an_object");
    }
    String id = Parser.id(frame.get("id"));
    JsonNode op = frame.get("op");
    if (op == null || !op.isString()) {
      return new Invalid(id, "op: required");
    }
    return switch (op.asString()) {
      case "subscribe" -> Parser.subscribe(frame, id);
      case "unsubscribe" -> Parser.unsubscribe(frame, id);
      case "ping" -> Parser.ping(frame, id);
      default -> new Invalid(id, "op: unsupported_value");
    };
  }

  /** The parsing helpers. */
  final class Parser {

    static final JsonMapper JSON =
        JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private Parser() {}

    /** A valid id, else null. */
    static String id(JsonNode node) {
      if (node == null || !node.isString()) {
        return null;
      }
      String s = node.asString();
      return s.isEmpty() || s.length() > MAX_ID_LENGTH ? null : s;
    }

    private static ClientFrame subscribe(ObjectNode frame, String id) {
      String unknown = unknownField(frame, Set.of("op", "id", "topic", "params"));
      if (unknown != null) {
        return new Invalid(id, unknown);
      }
      if (id == null) {
        return new Invalid(null, "id: invalid_value");
      }
      JsonNode topic = frame.get("topic");
      if (topic == null || !topic.isString() || topic.asString().isEmpty()) {
        return new Invalid(id, "topic: invalid_value");
      }
      JsonNode params = frame.get("params");
      if (params == null || !params.isObject()) {
        return new Invalid(id, "params: invalid_value");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> values = JSON.convertValue(params, LinkedHashMap.class);
      String name = topic.asString();
      return new Subscribe(
          id,
          name.length() > MAX_TOPIC_LENGTH ? name.substring(0, MAX_TOPIC_LENGTH) : name,
          values);
    }

    private static ClientFrame unsubscribe(ObjectNode frame, String id) {
      String unknown = unknownField(frame, Set.of("op", "id"));
      if (unknown != null) {
        return new Invalid(id, unknown);
      }
      return id == null ? new Invalid(null, "id: invalid_value") : new Unsubscribe(id);
    }

    private static ClientFrame ping(ObjectNode frame, String id) {
      String unknown = unknownField(frame, Set.of("op", "t"));
      if (unknown != null) {
        return new Invalid(id, unknown);
      }
      JsonNode t = frame.get("t");
      if (t == null || !t.isIntegralNumber() || !t.canConvertToLong() || t.asLong() < 0) {
        return new Invalid(id, "t: invalid_value");
      }
      return new Ping(t.asLong());
    }

    /** {@code "field: unknown_field"} for the first field outside {@code allowed}, else null. */
    private static String unknownField(ObjectNode frame, Set<String> allowed) {
      Iterator<String> names = frame.propertyNames().iterator();
      while (names.hasNext()) {
        if (!allowed.contains(names.next())) {
          return "frame: unknown_field";
        }
      }
      return null;
    }
  }
}
