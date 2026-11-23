/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import io.argos.agent.testing.collector.CommandOutcome;
import io.argos.agent.testing.collector.FakeCollector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The agent's self-diagnostics as 18 §5.2/§5.9 and 10 §19 require them ({@code
 * threadlocal_contexts_live}, {@code active_registry_size}, {@code weakmap_size}, {@code
 * agent_threads_cpu_seconds_total}, {@code direct_memory_bytes}, {@code premain_ms}, module states
 * and error counts, drop/exclude/orphan counters).
 *
 * <p>Source: the snapshot file a development build of the agent rewrites every 2 s and at shutdown
 * with {@code -Dargos.test.selfdiag-file=<file>} (ADR-0010 C-95, agent-core {@code
 * SelfDiagnosticsFile}; the launcher's harness defaults set it to {@code
 * <workDir>/out/selfdiag.json}), and the {@code SELF_DIAGNOSTICS} command of the session (WO-17,
 * {@link #fromCommand}, {@link RunningApp#selfDiagnostics(FakeCollector)}). Both sources go through
 * the same parser ({@link #parse(String)}), so a key means the same in both (C-95); the command
 * result may add members the file does not have (version, effective configuration and its sources,
 * ...), which {@link #json()} and {@link #member(String)} expose as parsed.
 *
 * <p>Missing is never zero: a field the agent did not report (it leaves out what it could not
 * measure, C-4) and every field of {@link #notAvailable(String)} make {@link #value(String)} throw
 * {@link NotAvailableException}, so a check that needs it fails or reports "not available" and
 * never passes on a default value. The flat fields {@code sql_outside_tx_total} and {@code
 * orphan_steps_total} are read from the counters {@code argos_agent_sql_outside_tx_total} and the
 * sum of {@code argos_agent_orphan_steps_total{type=...}}.
 *
 * <p>Weak maps: {@code weakmap_size} is the 18 §5.9 leak gauge, the fallback context-store entries
 * that hold transaction state ({@code TraceContext} or {@code Scope} values), 0 after GC. {@link
 * #WEAKMAP_ENTRIES} counts every live entry of every store, including caches keyed by long-lived
 * objects (pooled connections, class loaders), so it is not 0 after GC; the leak test checks that
 * it does not grow between two equal loads. The split is awaiting a PM decision (WO-10 report).
 */
public final class SelfDiagnostics {
  /** Field names of 10 §19 / 18 §19. */
  public static final List<String> FIELDS =
      List.of(
          "threadlocal_contexts_live",
          "active_registry_size",
          "weakmap_size",
          "agent_threads_cpu_seconds_total",
          "direct_memory_bytes",
          "premain_ms",
          "sql_outside_tx_total",
          "orphan_steps_total");

  /**
   * Every live entry of the agent's fallback context stores after GC (not an 18 §19 field, so not
   * in {@link #FIELDS}): the non-growth check of T-AG-053 for stores keyed by long-lived objects.
   */
  public static final String WEAKMAP_ENTRIES = "weakmap_entries";

  static final String SQL_OUTSIDE_TX_COUNTER = "argos_agent_sql_outside_tx_total";
  static final String ORPHAN_STEPS_COUNTER = "argos_agent_orphan_steps_total";

  static final String REASON =
      "agent self-diagnostics are not available: the run has no argos.test.selfdiag-file snapshot"
          + " (a release build ignores argos.test.*, the agent did not start, or harness defaults"
          + " are off)";

  /** One instrumentation module of the snapshot. */
  public record Module(int id, String name, String state, long errors) {}

  private final boolean available;
  private final String reason;
  private final Map<String, Double> values;
  private final Map<String, Long> counters;
  private final List<Module> modules;
  private final List<Gauges> pools;
  private final List<Gauges> connectors;
  private final Map<String, Object> json;

  /**
   * One entry of the development-only registry members {@code pools} and {@code connectors} (agent
   * {@code core.metric.MetricDiagnostics}, WO-13): a pool or Tomcat connector by name, with the
   * values the agent read from its getters. A value the agent could not read is absent (C-4).
   *
   * @param name pool or connector name
   * @param kind {@code hikari}, {@code tomcat-jdbc}, {@code dbcp2} for pools; null for connectors
   * @param values measured values by member name ({@code active}, {@code threads_busy}, ...)
   */
  public record Gauges(String name, String kind, Map<String, Long> values) {
    public Gauges {
      values = Map.copyOf(values);
    }

    /** Whether the agent reported {@code field}. */
    public boolean has(String field) {
      return values.containsKey(field);
    }

    /**
     * The value of {@code field}.
     *
     * @throws NotAvailableException when the agent left it out (missing, never zero)
     */
    public long value(String field) {
      Long value = values.get(field);
      if (value == null) {
        throw new NotAvailableException(name + " " + field + ": missing (not measured)");
      }
      return value;
    }
  }

  private SelfDiagnostics(
      boolean available,
      String reason,
      Map<String, Double> values,
      Map<String, Long> counters,
      List<Module> modules) {
    this(available, reason, values, counters, modules, null, null);
  }

  private SelfDiagnostics(
      boolean available,
      String reason,
      Map<String, Double> values,
      Map<String, Long> counters,
      List<Module> modules,
      List<Gauges> pools,
      List<Gauges> connectors) {
    this(available, reason, values, counters, modules, pools, connectors, Map.of());
  }

  private SelfDiagnostics(
      boolean available,
      String reason,
      Map<String, Double> values,
      Map<String, Long> counters,
      List<Module> modules,
      List<Gauges> pools,
      List<Gauges> connectors,
      Map<String, Object> json) {
    this.json = Collections.unmodifiableMap(new LinkedHashMap<>(json));
    this.available = available;
    this.reason = reason;
    this.values = Map.copyOf(values);
    this.counters = Map.copyOf(counters);
    this.modules = List.copyOf(modules);
    this.pools = pools == null ? null : List.copyOf(pools);
    this.connectors = connectors == null ? null : List.copyOf(connectors);
  }

  /** No self-diagnostics for the run (see {@link #REASON}). */
  public static SelfDiagnostics notAvailable() {
    return notAvailable(REASON);
  }

  /** No self-diagnostics for the run, for {@code reason}. */
  public static SelfDiagnostics notAvailable(String reason) {
    return new SelfDiagnostics(false, reason, Map.of(), Map.of(), List.of());
  }

  /** Reported values (unit tests and other sources): flat fields by name, nothing else. */
  public static SelfDiagnostics of(Map<String, Long> reported) {
    Map<String, Double> values = new LinkedHashMap<>();
    reported.forEach((key, value) -> values.put(key, value.doubleValue()));
    return new SelfDiagnostics(true, "", values, Map.of(), List.of());
  }

  /**
   * The snapshot file of {@code argos.test.selfdiag-file}; {@link #notAvailable(String)} naming the
   * file when it does not exist.
   */
  public static SelfDiagnostics read(Path file) {
    String json;
    try {
      json = Files.readString(file, StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      return notAvailable("the agent wrote no self-diagnostics file " + file + "; " + REASON);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
    return parse(json);
  }

  /** The agent's snapshot JSON ({@code SelfDiagnostics.toJson()} of agent-core, format 1). */
  public static SelfDiagnostics parse(String json) {
    return parse(json, true);
  }

  /** Default wait of {@link #fromCommand(FakeCollector)}. */
  public static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);

  /**
   * The {@code SELF_DIAGNOSTICS} command result of the only agent session of {@code collector}
   * (WO-17, 10 §12.1), waiting up to {@link #COMMAND_TIMEOUT}.
   */
  public static SelfDiagnostics fromCommand(FakeCollector collector) {
    return fromCommand(collector, COMMAND_TIMEOUT);
  }

  /**
   * The {@code SELF_DIAGNOSTICS} command result of the only agent session of {@code collector}; not
   * available (with the reason) when the agent does not answer {@code OK} with JSON within {@code
   * timeout}, never zeros.
   */
  public static SelfDiagnostics fromCommand(FakeCollector collector, Duration timeout) {
    CommandOutcome outcome;
    try {
      outcome =
          collector
              .command("SELF_DIAGNOSTICS", Map.of())
              .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return notAvailable("interrupted while waiting for the SELF_DIAGNOSTICS command result");
    } catch (ExecutionException | TimeoutException e) {
      return notAvailable(
          "no SELF_DIAGNOSTICS command result within " + timeout + ": " + e.getMessage());
    }
    return fromCommand(outcome);
  }

  /**
   * A {@code SELF_DIAGNOSTICS} command result: parsed like the snapshot file when the status is
   * {@code OK} (a {@code format} member, when present, must be 1), else not available with the
   * agent's status and error.
   */
  public static SelfDiagnostics fromCommand(CommandOutcome outcome) {
    if (!outcome.ok()) {
      return notAvailable(
          "the agent answered SELF_DIAGNOSTICS with "
              + outcome.status()
              + (outcome.error().isEmpty() ? "" : ": " + outcome.error()));
    }
    if (!outcome.contentType().startsWith("application/json")) {
      throw new IllegalArgumentException(
          "self-diagnostics command: content type " + outcome.contentType() + ", not JSON");
    }
    return parse(outcome.payloadText(), false);
  }

  @SuppressWarnings("unchecked")
  private static SelfDiagnostics parse(String json, boolean requireFormat) {
    Object root = new JsonReader(json.strip()).read();
    if (!(root instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("self-diagnostics: not a JSON object: " + json);
    }
    Object format = map.get("format");
    boolean formatOk = format instanceof Number number && number.intValue() == 1;
    if (requireFormat ? !formatOk : format != null && !formatOk) {
      throw new IllegalArgumentException("self-diagnostics: unsupported format " + format);
    }
    Map<String, Double> values = new LinkedHashMap<>();
    Map<String, Long> counters = new LinkedHashMap<>();
    List<Module> modules = new ArrayList<>();
    List<Gauges> pools = null;
    List<Gauges> connectors = null;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String key = (String) entry.getKey();
      Object value = entry.getValue();
      if (key.equals("format")) {
        continue;
      }
      if (key.equals("counters") && value instanceof Map<?, ?> counterMap) {
        counterMap.forEach(
            (name, count) -> counters.put((String) name, ((Number) count).longValue()));
      } else if (key.equals("modules") && value instanceof List<?> list) {
        for (Object item : list) {
          Map<String, Object> module = (Map<String, Object>) item;
          modules.add(
              new Module(
                  ((Number) module.get("id")).intValue(),
                  (String) module.get("name"),
                  (String) module.get("state"),
                  ((Number) module.get("errors")).longValue()));
        }
      } else if (key.equals("pools") && value instanceof List<?> list) {
        pools = gauges(list, true);
      } else if (key.equals("connectors") && value instanceof List<?> list) {
        connectors = gauges(list, false);
      } else if (key.equals("registry_counters") && value instanceof Map<?, ?> counterMap) {
        counterMap.forEach(
            (name, count) -> counters.put((String) name, ((Number) count).longValue()));
      } else if (value instanceof Number measured) {
        values.put(key, measured.doubleValue());
      }
    }
    Long sqlOutside = counters.get(SQL_OUTSIDE_TX_COUNTER);
    if (sqlOutside != null) {
      values.put("sql_outside_tx_total", sqlOutside.doubleValue());
    }
    long orphans = 0;
    boolean anyOrphan = false;
    for (Map.Entry<String, Long> counter : counters.entrySet()) {
      if (counter.getKey().startsWith(ORPHAN_STEPS_COUNTER + "{")) {
        orphans += counter.getValue();
        anyOrphan = true;
      }
    }
    if (anyOrphan) {
      values.put("orphan_steps_total", (double) orphans);
    }
    return new SelfDiagnostics(
        true, "", values, counters, modules, pools, connectors, (Map<String, Object>) map);
  }

  @SuppressWarnings("unchecked")
  private static List<Gauges> gauges(List<?> list, boolean pool) {
    List<Gauges> out = new ArrayList<>();
    for (Object item : list) {
      Map<String, Object> fields = (Map<String, Object>) item;
      Map<String, Long> measured = new LinkedHashMap<>();
      fields.forEach(
          (name, value) -> {
            if (value instanceof Number number) {
              measured.put(name, number.longValue());
            }
          });
      out.add(
          new Gauges(
              (String) fields.get("name"), pool ? (String) fields.get("kind") : null, measured));
    }
    return out;
  }

  public boolean available() {
    return available;
  }

  /**
   * The whole parsed JSON object (maps, lists, strings, {@code Long}/{@code Double}, booleans,
   * null), including members the typed accessors do not read; empty when not available.
   */
  public Map<String, Object> json() {
    return json;
  }

  /**
   * The member at a dotted path ({@code "config.sampling.profile.normal-rate"} is tried as the
   * member {@code config} and then its key {@code sampling.profile.normal-rate}: a whole key wins,
   * else each dotted prefix that is a key is followed, shortest first), if present.
   */
  public Optional<Object> member(String path) {
    return member(json, path);
  }

  private static Optional<Object> member(Object node, String path) {
    if (!(node instanceof Map<?, ?> map)) {
      return Optional.empty();
    }
    if (map.containsKey(path)) {
      return Optional.ofNullable(map.get(path));
    }
    for (int dot = path.indexOf('.'); dot > 0; dot = path.indexOf('.', dot + 1)) {
      String head = path.substring(0, dot);
      if (map.containsKey(head)) {
        Optional<Object> found = member(map.get(head), path.substring(dot + 1));
        if (found.isPresent()) {
          return found;
        }
      }
    }
    return Optional.empty();
  }

  /** Why the values are not available (empty when they are). */
  public String reason() {
    return available ? "" : reason;
  }

  /**
   * The value of {@code field} (fractions dropped: use {@link #decimal(String)} for {@code
   * premain_ms} and {@code agent_threads_cpu_seconds_total}).
   *
   * @throws NotAvailableException when the agent does not report self-diagnostics or that field
   */
  public long value(String field) {
    return (long) decimal(field);
  }

  /**
   * The value of {@code field} with its fraction.
   *
   * @throws NotAvailableException when the agent does not report self-diagnostics or that field
   */
  public double decimal(String field) {
    Double value = values.get(field);
    if (!available || value == null) {
      throw new NotAvailableException(
          field + ": " + (available ? "not reported by the agent" : reason));
    }
    return value;
  }

  /** The OpenMetrics counters of the snapshot ({@code argos_agent_dropped_total{queue="..."}}). */
  public Map<String, Long> counters() {
    requireAvailable("counters");
    return counters;
  }

  /**
   * The counter {@code name}.
   *
   * @throws NotAvailableException when the snapshot does not have it
   */
  public long counter(String name) {
    Long value = counters().get(name);
    if (value == null) {
      throw new NotAvailableException(name + ": not reported by the agent");
    }
    return value;
  }

  /** The modules of the snapshot (id, name, state, errors). */
  public List<Module> modules() {
    requireAvailable("modules");
    return modules;
  }

  /** The module named {@code name}, if the snapshot lists it. */
  public Optional<Module> module(String name) {
    return modules().stream().filter(module -> module.name().equals(name)).findFirst();
  }

  /**
   * The connection pools of the agent's pool registry (development builds, WO-13), in registration
   * order.
   *
   * @throws NotAvailableException when the snapshot has no {@code pools} member (no
   *     self-diagnostics, or an agent without the registries)
   */
  public List<Gauges> pools() {
    requireAvailable("pools");
    if (pools == null) {
      throw new NotAvailableException("pools: not reported by the agent");
    }
    return pools;
  }

  /**
   * The Tomcat connectors of the agent's Tomcat registry (development builds, WO-13), in start
   * order.
   *
   * @throws NotAvailableException when the snapshot has no {@code connectors} member
   */
  public List<Gauges> connectors() {
    requireAvailable("connectors");
    if (connectors == null) {
      throw new NotAvailableException("connectors: not reported by the agent");
    }
    return connectors;
  }

  private void requireAvailable(String what) {
    if (!available) {
      throw new NotAvailableException(what + ": " + reason);
    }
  }

  @Override
  public String toString() {
    return available
        ? "SelfDiagnostics" + values + " " + modules
        : "SelfDiagnostics(" + reason + ")";
  }

  /** Thrown for a self-diagnostics value the agent does not report. */
  public static final class NotAvailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NotAvailableException(String message) {
      super(message);
    }
  }

  /** Minimal JSON reader for the snapshot (objects, arrays, strings, numbers, booleans, null). */
  static final class JsonReader {
    private final String text;
    private int pos;

    JsonReader(String text) {
      this.text = text;
    }

    Object read() {
      Object value = value();
      skipWhitespace();
      if (pos != text.length()) {
        throw error("trailing characters");
      }
      return value;
    }

    private Object value() {
      skipWhitespace();
      if (pos >= text.length()) {
        throw error("unexpected end");
      }
      char c = text.charAt(pos);
      if (c == '{') {
        return object();
      }
      if (c == '[') {
        return array();
      }
      if (c == '"') {
        return string();
      }
      if (text.startsWith("true", pos)) {
        pos += 4;
        return true;
      }
      if (text.startsWith("false", pos)) {
        pos += 5;
        return false;
      }
      if (text.startsWith("null", pos)) {
        pos += 4;
        return null;
      }
      return number();
    }

    private Map<String, Object> object() {
      Map<String, Object> map = new LinkedHashMap<>();
      pos++;
      skipWhitespace();
      if (peek() == '}') {
        pos++;
        return map;
      }
      while (true) {
        skipWhitespace();
        String key = string();
        skipWhitespace();
        expect(':');
        map.put(key, value());
        skipWhitespace();
        if (peek() == ',') {
          pos++;
          continue;
        }
        expect('}');
        return map;
      }
    }

    private List<Object> array() {
      List<Object> list = new ArrayList<>();
      pos++;
      skipWhitespace();
      if (peek() == ']') {
        pos++;
        return list;
      }
      while (true) {
        list.add(value());
        skipWhitespace();
        if (peek() == ',') {
          pos++;
          continue;
        }
        expect(']');
        return list;
      }
    }

    private String string() {
      expect('"');
      StringBuilder out = new StringBuilder();
      while (pos < text.length()) {
        char c = text.charAt(pos++);
        if (c == '"') {
          return out.toString();
        }
        if (c == '\\') {
          char escaped = text.charAt(pos++);
          switch (escaped) {
            case 'n' -> out.append('\n');
            case 't' -> out.append('\t');
            case 'r' -> out.append('\r');
            case 'b' -> out.append('\b');
            case 'f' -> out.append('\f');
            case 'u' -> {
              out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
              pos += 4;
            }
            default -> out.append(escaped);
          }
        } else {
          out.append(c);
        }
      }
      throw error("unterminated string");
    }

    private Number number() {
      int start = pos;
      while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
        pos++;
      }
      String number = text.substring(start, pos);
      if (number.isEmpty()) {
        throw error("unexpected character");
      }
      if (number.contains(".") || number.contains("e") || number.contains("E")) {
        return Double.parseDouble(number);
      }
      return Long.parseLong(number);
    }

    private char peek() {
      return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private void expect(char c) {
      if (peek() != c) {
        throw error("expected '" + c + "'");
      }
      pos++;
    }

    private void skipWhitespace() {
      while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
        pos++;
      }
    }

    private IllegalArgumentException error(String message) {
      return new IllegalArgumentException(
          "self-diagnostics JSON: " + message + " at " + pos + " in " + text);
    }
  }
}
