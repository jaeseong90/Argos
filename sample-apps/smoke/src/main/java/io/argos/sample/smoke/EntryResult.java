/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.smoke;

import io.argos.sample.common.catalog.CatalogEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Outcome of one catalog entry call. */
final class EntryResult {

  final CatalogEntry entry;
  final String context;
  int status;
  long elapsedMs;
  String body = "";
  Map<String, List<String>> headers = new LinkedHashMap<>();

  /** SSE: number of {@code data:} lines; WebSocket: the echoed text. */
  Object protocolValue;

  final List<String> failures = new ArrayList<>();
  String skipped;

  /** Actual values of the JSON fields the checks read (reported, e.g. sqlStatements = 21). */
  final Map<String, Object> observed = new LinkedHashMap<>();

  EntryResult(CatalogEntry entry, String context) {
    this.entry = entry;
    this.context = context;
  }

  boolean passed() {
    return skipped == null && failures.isEmpty();
  }

  String header(String name) {
    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
      if (header.getKey().equalsIgnoreCase(name) && !header.getValue().isEmpty()) {
        return header.getValue().get(0);
      }
    }
    return null;
  }

  Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", entry.id());
    if (!context.isEmpty()) {
      map.put("context", context);
    }
    map.put("method", entry.method());
    map.put("path", entry.path());
    map.put("route", entry.route());
    map.put("expectedStatus", entry.expectedStatus());
    map.put("status", status);
    map.put("elapsedMs", elapsedMs);
    map.put("result", skipped != null ? "SKIPPED" : failures.isEmpty() ? "PASS" : "FAIL");
    if (skipped != null) {
      map.put("skipped", skipped);
    }
    if (!observed.isEmpty()) {
      map.put("observed", observed);
    }
    if (!failures.isEmpty()) {
      map.put("failures", failures);
      map.put("body", body.length() > 600 ? body.substring(0, 600) + "..." : body);
    }
    return map;
  }
}
