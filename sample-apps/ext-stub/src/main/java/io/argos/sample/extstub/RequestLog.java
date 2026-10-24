/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.extstub;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The last {@code capacity} requests ext-stub received (method, raw path and query, headers), for
 * propagation checks such as "the outgoing call carried traceparent" (F-A32). Bounded: the oldest
 * entry is dropped first; header count and value length are capped.
 */
final class RequestLog {

  static final int MAX_HEADERS = 64;
  static final int MAX_VALUE_CHARS = 1024;

  private final int capacity;
  private final Deque<Map<String, Object>> entries = new ArrayDeque<>();

  RequestLog(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.capacity = capacity;
  }

  void record(String method, String rawPath, String rawQuery, Map<String, List<String>> headers) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("time", Instant.now().toString());
    entry.put("method", method);
    entry.put("path", rawPath);
    entry.put("query", rawQuery);
    Map<String, Object> copied = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
      if (copied.size() >= MAX_HEADERS) {
        break;
      }
      List<String> values = header.getValue();
      String value = values == null || values.isEmpty() ? "" : values.get(0);
      copied.put(
          header.getKey().toLowerCase(java.util.Locale.ROOT),
          value.length() > MAX_VALUE_CHARS ? value.substring(0, MAX_VALUE_CHARS) : value);
    }
    entry.put("headers", copied);
    synchronized (entries) {
      if (entries.size() == capacity) {
        entries.removeFirst();
      }
      entries.addLast(entry);
    }
  }

  List<Map<String, Object>> snapshot() {
    synchronized (entries) {
      return new ArrayList<>(entries);
    }
  }

  void clear() {
    synchronized (entries) {
      entries.clear();
    }
  }
}
