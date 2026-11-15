/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.guard;

import io.argos.agent.bootstrap.ModuleHealth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * One self-diagnostics snapshot (18 §19, 10 §12.1 SELF_DIAG subset): the leak and budget gauges
 * {@code threadlocal_contexts_live}, {@code active_registry_size}, {@code weakmap_size}, {@code
 * agent_threads_cpu_seconds_total}, {@code direct_memory_bytes}, {@code premain_ms}, every counter
 * of {@link AgentCounters#snapshot()} and the state and error count of every module. Immutable.
 *
 * <p>{@code weakmap_size} counts the fallback context-store entries that hold transaction state (a
 * {@code TraceContext} or {@code Scope} value, {@code ContextStore.contextEntries()}): the leak
 * gauge of 18 §5.9, 0 after GC. {@code weakmap_entries} counts every live entry of every store
 * ({@code ContextStore.totalEntries()}), including caches keyed by long-lived objects (pooled
 * connections, class loaders); it is not 0 after GC, and the leak test checks that it does not grow
 * between two equal loads. This split of the 18 §19 field is awaiting a PM decision (WO-10 report).
 *
 * <p>Missing is not zero (C-4, D-23): a gauge the agent could not measure is absent, never 0 or -1
 * ({@link OptionalLong#empty()}), and {@link #toJson()} leaves it out. The snapshot holds names,
 * numbers and module ids only, never collected data (SEC-26), so it may be written to a file
 * ({@code argos.test.selfdiag-file}, C-95) or sent with {@code SELF_DIAG} (WO-17).
 */
public final class SelfDiagnostics {
  /** Version of the {@link #toJson()} layout. */
  public static final int FORMAT = 1;

  public static final String THREADLOCAL_CONTEXTS_LIVE = "threadlocal_contexts_live";
  public static final String ACTIVE_REGISTRY_SIZE = "active_registry_size";
  public static final String WEAKMAP_SIZE = "weakmap_size";
  public static final String WEAKMAP_ENTRIES = "weakmap_entries";
  public static final String AGENT_THREADS_CPU_SECONDS_TOTAL = "agent_threads_cpu_seconds_total";
  public static final String DIRECT_MEMORY_BYTES = "direct_memory_bytes";
  public static final String PREMAIN_MS = "premain_ms";

  /** The state and error count of one instrumentation module. */
  public static final class Module {
    private final int id;
    private final String name;
    private final int state;
    private final long errors;

    /**
     * A module entry: module id (0..63), module name (10 §5.1 id), {@link ModuleHealth} state and
     * counted advice/transform errors.
     */
    public Module(int id, String name, int state, long errors) {
      this.id = id;
      this.name = name;
      this.state = state;
      this.errors = errors;
    }

    public int id() {
      return id;
    }

    public String name() {
      return name;
    }

    public int state() {
      return state;
    }

    /** {@link ModuleHealth#stateName(int)} of {@link #state()}. */
    public String stateName() {
      return ModuleHealth.stateName(state);
    }

    public long errors() {
      return errors;
    }
  }

  private final Long threadLocalContextsLive;
  private final Long activeRegistrySize;
  private final Long weakMapSize;
  private final Long weakMapEntries;
  private final Double agentThreadsCpuSeconds;
  private final Long directMemoryBytes;
  private final Double premainMs;
  private final Map<String, Long> counters;
  private final List<Module> modules;

  private SelfDiagnostics(Builder b) {
    this.threadLocalContextsLive = b.threadLocalContextsLive;
    this.activeRegistrySize = b.activeRegistrySize;
    this.weakMapSize = b.weakMapSize;
    this.weakMapEntries = b.weakMapEntries;
    this.agentThreadsCpuSeconds = b.agentThreadsCpuSeconds;
    this.directMemoryBytes = b.directMemoryBytes;
    this.premainMs = b.premainMs;
    this.counters = Collections.unmodifiableMap(new LinkedHashMap<String, Long>(b.counters));
    this.modules = Collections.unmodifiableList(new ArrayList<Module>(b.modules));
  }

  public static Builder builder() {
    return new Builder();
  }

  public OptionalLong threadLocalContextsLive() {
    return optional(threadLocalContextsLive);
  }

  public OptionalLong activeRegistrySize() {
    return optional(activeRegistrySize);
  }

  public OptionalLong weakMapSize() {
    return optional(weakMapSize);
  }

  /** Every live entry of the fallback context stores, long-lived cache keys included. */
  public OptionalLong weakMapEntries() {
    return optional(weakMapEntries);
  }

  /** CPU seconds of all {@code ArgosThread}s since start. */
  public OptionalDouble agentThreadsCpuSeconds() {
    return agentThreadsCpuSeconds == null
        ? OptionalDouble.empty()
        : OptionalDouble.of(agentThreadsCpuSeconds);
  }

  public OptionalLong directMemoryBytes() {
    return optional(directMemoryBytes);
  }

  /** Milliseconds from the start of premain to the end of P8 (N-30). */
  public OptionalDouble premainMs() {
    return premainMs == null ? OptionalDouble.empty() : OptionalDouble.of(premainMs);
  }

  /** {@link AgentCounters#snapshot()} at the time of this snapshot. */
  public Map<String, Long> counters() {
    return counters;
  }

  public List<Module> modules() {
    return modules;
  }

  /**
   * The snapshot as one JSON object: the measured gauges by their 18 §19 names (absent when not
   * measured), {@code "counters"} (OpenMetrics name to value) and {@code "modules"} (id, name,
   * state, errors). No collected data.
   */
  public String toJson() {
    StringBuilder out = new StringBuilder(256 + 64 * counters.size());
    out.append("{\"format\":").append(FORMAT);
    number(out, THREADLOCAL_CONTEXTS_LIVE, threadLocalContextsLive);
    number(out, ACTIVE_REGISTRY_SIZE, activeRegistrySize);
    number(out, WEAKMAP_SIZE, weakMapSize);
    number(out, WEAKMAP_ENTRIES, weakMapEntries);
    decimal(out, AGENT_THREADS_CPU_SECONDS_TOTAL, agentThreadsCpuSeconds);
    number(out, DIRECT_MEMORY_BYTES, directMemoryBytes);
    decimal(out, PREMAIN_MS, premainMs);
    out.append(",\"counters\":{");
    boolean first = true;
    for (Map.Entry<String, Long> counter : counters.entrySet()) {
      out.append(first ? "" : ",");
      string(out, counter.getKey());
      out.append(':').append(counter.getValue().longValue());
      first = false;
    }
    out.append("},\"modules\":[");
    first = true;
    for (Module module : modules) {
      out.append(first ? "" : ",").append("{\"id\":").append(module.id).append(",\"name\":");
      string(out, module.name);
      out.append(",\"state\":");
      string(out, module.stateName());
      out.append(",\"errors\":").append(module.errors).append('}');
      first = false;
    }
    return out.append("]}").toString();
  }

  @Override
  public String toString() {
    return toJson();
  }

  private static OptionalLong optional(Long value) {
    return value == null ? OptionalLong.empty() : OptionalLong.of(value);
  }

  private static void number(StringBuilder out, String name, Long value) {
    if (value != null) {
      out.append(',');
      string(out, name);
      out.append(':').append(value.longValue());
    }
  }

  private static void decimal(StringBuilder out, String name, Double value) {
    if (value != null && !value.isNaN() && !value.isInfinite()) {
      out.append(',');
      string(out, name);
      out.append(':').append(String.format(Locale.ROOT, "%.3f", value));
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

  /** Builder; every gauge starts as "not measured". */
  public static final class Builder {
    private Long threadLocalContextsLive;
    private Long activeRegistrySize;
    private Long weakMapSize;
    private Long weakMapEntries;
    private Double agentThreadsCpuSeconds;
    private Long directMemoryBytes;
    private Double premainMs;
    private Map<String, Long> counters = Collections.emptyMap();
    private final List<Module> modules = new ArrayList<Module>();

    Builder() {}

    public Builder threadLocalContextsLive(long value) {
      this.threadLocalContextsLive = value;
      return this;
    }

    public Builder activeRegistrySize(long value) {
      this.activeRegistrySize = value;
      return this;
    }

    public Builder weakMapSize(long value) {
      this.weakMapSize = value;
      return this;
    }

    public Builder weakMapEntries(long value) {
      this.weakMapEntries = value;
      return this;
    }

    public Builder agentThreadsCpuSeconds(double value) {
      this.agentThreadsCpuSeconds = value;
      return this;
    }

    public Builder directMemoryBytes(long value) {
      this.directMemoryBytes = value;
      return this;
    }

    public Builder premainMs(double value) {
      this.premainMs = value;
      return this;
    }

    /** The counters ({@link AgentCounters#snapshot()}). */
    public Builder counters(Map<String, Long> value) {
      this.counters = value == null ? Collections.<String, Long>emptyMap() : value;
      return this;
    }

    /** Adds a module entry; at most {@link ModuleHealth#MAX_MODULES} are kept. */
    public Builder module(int id, String name, int state, long errors) {
      if (modules.size() < ModuleHealth.MAX_MODULES) {
        modules.add(new Module(id, name, state, errors));
      }
      return this;
    }

    public SelfDiagnostics build() {
      return new SelfDiagnostics(this);
    }
  }
}
