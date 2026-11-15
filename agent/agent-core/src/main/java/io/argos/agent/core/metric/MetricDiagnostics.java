/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The pool and connector registries as members of the development self-diagnostics file ({@code
 * argos.test.selfdiag-file}, ADR-0010 C-95; development builds only, T-BLD-010), so that
 * integration tests can check the getter values before WO-16 sends the 10 §11 metrics. Not part of
 * the {@code SELF_DIAG} command payload (WO-17).
 *
 * <p>Layout ({@link #members}), values missing (-1) are left out (C-4: missing is never 0):
 *
 * <pre>
 * "pools":[{"name":"HikariPool-1","kind":"hikari","active":0,"idle":10,"waiting":0,"total":10,"max":10}],
 * "connectors":[{"name":"http-nio-8080","port":8080,"threads_busy":1,"threads_current":10,
 *                "threads_max":200,"connections_current":1,"connections_max":8192}],
 * "registry_counters":{"argos_agent_registry_overflow_total{registry=\"pool\"}":0, ...}
 * </pre>
 *
 * Pool and connector names are application configuration (identifiers), never collected data.
 */
public final class MetricDiagnostics {
  private MetricDiagnostics() {}

  /**
   * The JSON members of both registries, without braces, to add to the snapshot object; null when
   * they cannot be built (never throws). Calls the getters now ({@link PoolRegistry#snapshot()},
   * {@link TomcatRegistry#snapshot()}), so it runs on an agent thread.
   */
  public static String members(PoolRegistry pools, TomcatRegistry connectors) {
    try {
      StringBuilder out = new StringBuilder(512);
      out.append("\"pools\":[");
      List<PoolSample> poolSamples =
          pools == null ? java.util.Collections.<PoolSample>emptyList() : pools.snapshot();
      boolean first = true;
      for (PoolSample sample : poolSamples) {
        out.append(first ? "{" : ",{");
        string(out, "name");
        out.append(':');
        string(out, sample.name());
        out.append(',');
        string(out, "kind");
        out.append(':');
        string(out, sample.kindName());
        number(out, "active", sample.active());
        number(out, "idle", sample.idle());
        number(out, "waiting", sample.waiting());
        number(out, "total", sample.total());
        number(out, "max", sample.max());
        out.append('}');
        first = false;
      }
      out.append("],\"connectors\":[");
      List<ConnectorSample> connectorSamples =
          connectors == null
              ? java.util.Collections.<ConnectorSample>emptyList()
              : connectors.snapshot();
      first = true;
      for (ConnectorSample sample : connectorSamples) {
        out.append(first ? "{" : ",{");
        string(out, "name");
        out.append(':');
        string(out, sample.name());
        number(out, "port", sample.port());
        number(out, "threads_busy", sample.threadsBusy());
        number(out, "threads_current", sample.threadsCurrent());
        number(out, "threads_max", sample.threadsMax());
        number(out, "connections_current", sample.connectionsCurrent());
        number(out, "connections_max", sample.connectionsMax());
        out.append('}');
        first = false;
      }
      out.append("],\"registry_counters\":{");
      first = true;
      if (pools != null) {
        first = counters(out, pools.counters(), first);
      }
      if (connectors != null) {
        counters(out, connectors.counters(), first);
      }
      return out.append('}').toString();
    } catch (Throwable t) {
      return null;
    }
  }

  private static boolean counters(StringBuilder out, Map<String, Long> counters, boolean first) {
    boolean head = first;
    for (Map.Entry<String, Long> counter : counters.entrySet()) {
      out.append(head ? "" : ",");
      string(out, counter.getKey());
      out.append(':').append(counter.getValue().longValue());
      head = false;
    }
    return head;
  }

  /** {@code ,"name":value} for a measured value; nothing for a missing one (-1). */
  private static void number(StringBuilder out, String name, long value) {
    if (value >= 0) {
      out.append(',');
      string(out, name);
      out.append(':').append(value);
    }
  }

  /** A JSON string (RFC 8259 escaping). */
  static void string(StringBuilder out, String value) {
    if (value == null) {
      out.append("null");
      return;
    }
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        out.append('\\').append(c);
      } else if (c < 0x20 || c == 0x2028 || c == 0x2029) {
        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    out.append('"');
  }
}
