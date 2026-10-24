/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.catalog;

/**
 * One expectation of a catalog entry besides its status code. The smoke runner evaluates it; {@link
 * #toString()} is the human-readable form used in reports and README tables.
 */
public final class Check {

  /** Kinds of expectation. */
  public enum Type {
    /** The response body contains {@code value}. */
    BODY_CONTAINS,
    /** The response body does not contain {@code value}. */
    BODY_NOT_CONTAINS,
    /** Top-level JSON field {@code key} equals {@code value} (string form of the JSON value). */
    JSON_EQUALS,
    /** Top-level numeric JSON field {@code key} is at least {@code value}. */
    JSON_AT_LEAST,
    /** The response took at least {@code value} ms. */
    MIN_ELAPSED_MS,
    /** Response header {@code key} is present. */
    HEADER_PRESENT,
    /** Content-Type starts with {@code value}. */
    CONTENT_TYPE,
    /** Server-sent events: exactly {@code value} {@code data:} lines, then the stream ends. */
    SSE_EVENTS,
    /** WebSocket: a text message {@code value} comes back unchanged. */
    WEBSOCKET_ECHO,
    /** The app's log (stdout) contains {@code value} after the call. */
    LOG_CONTAINS
  }

  private final Type type;
  private final String key;
  private final String value;

  private Check(Type type, String key, String value) {
    this.type = type;
    this.key = key;
    this.value = value;
  }

  public static Check bodyContains(String text) {
    return new Check(Type.BODY_CONTAINS, null, text);
  }

  public static Check bodyNotContains(String text) {
    return new Check(Type.BODY_NOT_CONTAINS, null, text);
  }

  public static Check jsonEquals(String field, Object expected) {
    return new Check(Type.JSON_EQUALS, field, String.valueOf(expected));
  }

  public static Check jsonAtLeast(String field, long minimum) {
    return new Check(Type.JSON_AT_LEAST, field, Long.toString(minimum));
  }

  public static Check minElapsedMs(long millis) {
    return new Check(Type.MIN_ELAPSED_MS, null, Long.toString(millis));
  }

  public static Check headerPresent(String header) {
    return new Check(Type.HEADER_PRESENT, header, null);
  }

  public static Check contentType(String prefix) {
    return new Check(Type.CONTENT_TYPE, null, prefix);
  }

  public static Check sseEvents(int count) {
    return new Check(Type.SSE_EVENTS, null, Integer.toString(count));
  }

  public static Check webSocketEcho(String message) {
    return new Check(Type.WEBSOCKET_ECHO, null, message);
  }

  public static Check logContains(String text) {
    return new Check(Type.LOG_CONTAINS, null, text);
  }

  public Type type() {
    return type;
  }

  /** Field or header name (null when the type has none). */
  public String key() {
    return key;
  }

  /** Expected value (null when the type has none). */
  public String value() {
    return value;
  }

  @Override
  public String toString() {
    switch (type) {
      case BODY_CONTAINS:
        return "body contains '" + value + "'";
      case BODY_NOT_CONTAINS:
        return "body does not contain '" + value + "'";
      case JSON_EQUALS:
        return key + " = " + value;
      case JSON_AT_LEAST:
        return key + " >= " + value;
      case MIN_ELAPSED_MS:
        return "takes >= " + value + " ms";
      case HEADER_PRESENT:
        return "header " + key;
      case CONTENT_TYPE:
        return "Content-Type " + value;
      case SSE_EVENTS:
        return value + " SSE events, then end";
      case WEBSOCKET_ECHO:
        return "WebSocket echo '" + value + "'";
      case LOG_CONTAINS:
        return "log contains '" + value + "'";
    }
    throw new AssertionError(type);
  }
}
