/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.AllowList;
import io.argos.agent.core.config.ConfigKeys;
import io.argos.agent.core.config.EffectiveConfig;
import io.argos.agent.core.config.TokenHolder;
import io.argos.agent.core.export.AgentInfo;
import io.argos.agent.core.guard.SelfDiagnostics;
import io.argos.agent.core.guard.SelfDiagnosticsCollector;
import io.argos.agent.core.metric.CgroupReader;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.TraceRuntime;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code SELF_DIAGNOSTICS} (10 §12.1 SELF_DIAG, ADR-0010 C-95): one JSON object ({@code
 * application/json}) of at most {@value #MAX_BYTES} bytes.
 *
 * <p>It starts with the members of {@link SelfDiagnostics#toJson()} under the same names ({@code
 * format}, the 18 §19 gauges, {@code counters}, {@code modules} with state and error count), so the
 * harness reads the command result and the {@code argos.test.selfdiag-file} snapshot with one
 * parser (C-95). Then: {@code agent} (version, commit, protocol, release build), {@code jvm},
 * {@code uptime_ms} (JVM uptime, the agent starts with the JVM), {@code config_hash}, {@code
 * effective_config} (key to value and source; the token and any value that looks like a token are
 * {@value TokenHolder#MASK}), {@code remote_config_allow}, {@code capture} (capture modes), {@code
 * gauges} (queue lengths and bytes, estimated heap, dictionary bytes), {@code dictionary} (size,
 * capacity and overflow per kind), {@code active_registry} (size, capacity, rejected, virtual),
 * {@code threads} (the agent's {@code ArgosThread}s with state and CPU), {@code clock_offset_ms},
 * {@code virtual_threads}, {@code cgroup} and {@code extras} (values the wiring adds: transport,
 * transformed classes, ...).
 *
 * <p>Over the cap, effective values are cut and {@code extras} dropped, then {@code
 * effective_config} is left out; {@code truncated} says so. Names and numbers only, no collected
 * data (SEC-26).
 */
final class SelfDiagCommand implements CommandHandler {
  /** Result cap (10 §12.1). */
  static final int MAX_BYTES = 256 * 1024;

  /** Agent threads listed at most. */
  static final int MAX_THREADS = 64;

  /** Characters of an effective value at the first reduction step. */
  static final int CUT_VALUE_CHARS = 128;

  /** Configuration keys whose value is never shown (beyond the token). */
  private static final Pattern SECRET_KEY = Pattern.compile("(?i)(token|password|secret|salt)");

  private final CommandEnv env;

  SelfDiagCommand(CommandEnv env) {
    this.env = env;
  }

  @Override
  public CommandOutput run(Map<String, String> args) {
    SelfDiagnosticsCollector collector = env.diagnostics();
    SelfDiagnostics snapshot =
        collector == null ? SelfDiagnostics.builder().build() : collector.snapshot();
    String base = snapshot.toJson();
    for (int level = 0; level < 3; level++) {
      String json = build(base, level);
      byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
      if (utf8.length <= MAX_BYTES) {
        return CommandOutput.json(utf8, level > 0);
      }
    }
    // The snapshot members alone are bounded (counters and at most 64 modules).
    String json = base.substring(0, base.length() - 1) + ",\"truncated\":true}";
    return CommandOutput.json(json.getBytes(StandardCharsets.UTF_8), true);
  }

  /** The result at reduction {@code level} (0 full, 1 cut values without extras, 2 no config). */
  String build(String base, int level) {
    StringBuilder out = new StringBuilder(base.length() + 16 * 1024);
    out.append(base, 0, base.length() - 1);
    agent(out, env.info());
    jvm(out);
    AgentConfig config = env.config();
    String hash = env.configHash();
    if (hash != null) {
      out.append(",\"config_hash\":");
      ThreadDumpJson.string(out, hash);
    }
    if (level < 2) {
      effective(out, env.effective(), level == 0 ? Integer.MAX_VALUE : CUT_VALUE_CHARS);
    }
    AllowList allow = env.allow();
    List<String> patterns =
        allow != null ? allow.patterns() : config != null ? config.remoteConfigAllow() : null;
    if (patterns != null) {
      out.append(",\"remote_config_allow\":");
      stringArray(out, patterns, 256);
    }
    if (config != null) {
      capture(out, config);
    }
    TraceRuntime runtime = env.runtime();
    if (runtime != null) {
      runtime(out, runtime);
    }
    threads(out);
    if (env.hasClockOffset()) {
      out.append(",\"clock_offset_ms\":").append(env.clockOffsetMillis());
    }
    out.append(",\"virtual_threads\":{\"supported\":")
        .append(ThreadDumpCommand.featureVersion() >= 21)
        .append('}');
    out.append(",\"cgroup\":");
    ThreadDumpJson.string(out, cgroupMode());
    if (level == 0) {
      extras(out, env.extras());
    }
    out.append(",\"truncated\":").append(level > 0).append('}');
    return out.toString();
  }

  private static void agent(StringBuilder out, AgentInfo info) {
    if (info == null) {
      return;
    }
    out.append(",\"agent\":{\"version\":");
    ThreadDumpJson.string(out, info.version());
    out.append(",\"commit\":");
    ThreadDumpJson.string(out, info.commit());
    out.append(",\"protocol\":");
    ThreadDumpJson.string(out, info.protocolVersion());
    out.append(",\"release_build\":").append(info.isReleaseBuild()).append('}');
  }

  private static void jvm(StringBuilder out) {
    out.append(",\"jvm\":{\"java_version\":");
    ThreadDumpJson.string(out, System.getProperty("java.version", "unknown"));
    out.append(",\"vm_name\":");
    ThreadDumpJson.string(out, System.getProperty("java.vm.name", "unknown"));
    out.append(",\"feature_version\":").append(ThreadDumpCommand.featureVersion()).append('}');
    try {
      out.append(",\"uptime_ms\":").append(ManagementFactory.getRuntimeMXBean().getUptime());
    } catch (RuntimeException e) {
      // Missing is not zero: leave it out.
    }
  }

  /** {@code effective_config}: key to {value, source}; secrets masked. */
  static void effective(StringBuilder out, EffectiveConfig effective, int maxValueChars) {
    if (effective == null) {
      return;
    }
    out.append(",\"effective_config\":{");
    boolean first = true;
    for (Map.Entry<String, EffectiveConfig.Entry> e : effective.entries().entrySet()) {
      out.append(first ? "" : ",");
      first = false;
      ThreadDumpJson.string(out, e.getKey());
      out.append(":{\"value\":");
      ThreadDumpJson.string(out, shown(e.getKey(), e.getValue().value(), maxValueChars));
      out.append(",\"source\":");
      EffectiveConfig.Source source = e.getValue().source();
      ThreadDumpJson.string(out, source == null ? null : source.label());
      out.append('}');
    }
    out.append('}');
  }

  /** The value shown for {@code key}: the token and secret-looking keys masked, tokens removed. */
  static String shown(String key, String value, int maxValueChars) {
    if (value == null) {
      return null;
    }
    if (ConfigKeys.SERVER_TOKEN.equals(key)) {
      return TokenHolder.MASK;
    }
    if (key != null && SECRET_KEY.matcher(key).find() && !key.endsWith("-file")) {
      return TokenHolder.MASK;
    }
    String shown = TokenHolder.maskTokenRuns(value);
    if (shown.length() > maxValueChars) {
      shown = shown.substring(0, maxValueChars) + "...";
    }
    return shown;
  }

  private static void capture(StringBuilder out, AgentConfig config) {
    out.append(",\"capture\":{");
    boolean[] first = {true};
    member(out, first, "query_string", () -> config.captureQueryString());
    member(out, first, "user_id_mode", () -> config.captureUserIdMode());
    member(out, first, "user_id_source", () -> config.captureUserIdSource());
    member(out, first, "sql_parameters", () -> config.captureSqlParameters());
    member(out, first, "remote_ip", () -> config.captureRemoteIp());
    member(out, first, "request_id_header", () -> config.captureRequestIdHeader());
    try {
      List<String> headers = config.captureHttpHeaders();
      out.append(first[0] ? "" : ",").append("\"http_headers\":");
      stringArray(out, headers, 64);
      first[0] = false;
    } catch (RuntimeException e) {
      // Left out.
    }
    out.append('}');
  }

  private interface Value {
    String get();
  }

  private static void member(StringBuilder out, boolean[] first, String name, Value value) {
    String v;
    try {
      v = value.get();
    } catch (RuntimeException e) {
      return;
    }
    if (v == null) {
      return;
    }
    out.append(first[0] ? "" : ",");
    first[0] = false;
    ThreadDumpJson.string(out, name);
    out.append(':');
    ThreadDumpJson.string(out, v);
  }

  private static void runtime(StringBuilder out, TraceRuntime runtime) {
    try {
      Map<String, Long> values = runtime.selfValues();
      out.append(",\"gauges\":{");
      boolean first = true;
      for (Map.Entry<String, Long> e : values.entrySet()) {
        if (e.getValue() == null) {
          continue;
        }
        out.append(first ? "" : ",");
        first = false;
        ThreadDumpJson.string(out, e.getKey());
        out.append(':').append(e.getValue().longValue());
      }
      out.append('}');
    } catch (RuntimeException e) {
      // Left out (missing is not zero).
    }
    Dictionary dictionary = runtime.dictionary();
    out.append(",\"dictionary\":{");
    boolean first = true;
    for (int kind = 1; kind < DictKind.COUNT; kind++) {
      out.append(first ? "" : ",");
      first = false;
      ThreadDumpJson.string(out, DictKind.name(kind).toLowerCase(Locale.ROOT));
      out.append(":{\"size\":")
          .append(dictionary.size(kind))
          .append(",\"capacity\":")
          .append(Dictionary.capacity(kind))
          .append(",\"overflow\":")
          .append(dictionary.overflow(kind))
          .append('}');
    }
    out.append('}');
    final long[] virtual = {0};
    runtime
        .activeRegistry()
        .forEach(
            (TraceContext context) -> {
              if (context.virtualThread && !context.isEnded()) {
                virtual[0]++;
              }
            });
    out.append(",\"active_registry\":{\"size\":")
        .append(runtime.activeRegistry().size())
        .append(",\"capacity\":")
        .append(runtime.activeRegistry().capacity())
        .append(",\"rejected\":")
        .append(runtime.activeRegistry().rejected())
        .append(",\"virtual\":")
        .append(virtual[0])
        .append('}');
  }

  /** The live {@code ArgosThread}s: name, state, daemon, CPU milliseconds when measured. */
  private static void threads(StringBuilder out) {
    ThreadMXBean bean = null;
    try {
      bean = ManagementFactory.getThreadMXBean();
      if (!bean.isThreadCpuTimeSupported() || !bean.isThreadCpuTimeEnabled()) {
        bean = null;
      }
    } catch (RuntimeException e) {
      bean = null;
    }
    out.append(",\"threads\":[");
    int listed = 0;
    for (Thread thread : liveThreads()) {
      if (!(thread instanceof ArgosThread) || listed >= MAX_THREADS) {
        continue;
      }
      out.append(listed == 0 ? "" : ",").append("{\"name\":");
      ThreadDumpJson.string(out, thread.getName());
      out.append(",\"state\":");
      ThreadDumpJson.string(out, thread.getState().name());
      out.append(",\"daemon\":").append(thread.isDaemon());
      if (bean != null) {
        long cpu = bean.getThreadCpuTime(thread.getId());
        if (cpu >= 0) {
          out.append(",\"cpu_ms\":").append(cpu / 1_000_000L);
        }
      }
      out.append('}');
      listed++;
    }
    out.append(']');
  }

  /** Live platform threads of the JVM (bounded by {@code SelfDiagnosticsCollector}'s rule). */
  private static Thread[] liveThreads() {
    ThreadGroup root = Thread.currentThread().getThreadGroup();
    while (root != null && root.getParent() != null) {
      root = root.getParent();
    }
    if (root == null) {
      return new Thread[0];
    }
    Thread[] threads = new Thread[Math.min(65_536, root.activeCount() * 2 + 16)];
    int count = root.enumerate(threads, true);
    Thread[] live = new Thread[count];
    System.arraycopy(threads, 0, live, 0, count);
    return live;
  }

  private static String cgroupMode() {
    try {
      return CgroupReader.systemMode();
    } catch (RuntimeException e) {
      return CgroupReader.NONE;
    }
  }

  private static void extras(StringBuilder out, Map<String, Object> extras) {
    if (extras == null || extras.isEmpty()) {
      return;
    }
    out.append(",\"extras\":{");
    boolean first = true;
    int count = 0;
    for (Map.Entry<String, Object> e : extras.entrySet()) {
      Object value = e.getValue();
      if (e.getKey() == null || count >= 256) {
        continue;
      }
      out.append(first ? "" : ",");
      first = false;
      count++;
      ThreadDumpJson.string(out, e.getKey());
      out.append(':');
      if (value instanceof Number) {
        double d = ((Number) value).doubleValue();
        if (value instanceof Double || value instanceof Float) {
          out.append(
              Double.isNaN(d) || Double.isInfinite(d)
                  ? "null"
                  : String.format(Locale.ROOT, "%.3f", d));
        } else {
          out.append(((Number) value).longValue());
        }
      } else if (value instanceof Boolean) {
        out.append(((Boolean) value).booleanValue());
      } else if (value == null) {
        out.append("null");
      } else {
        String text = TokenHolder.maskTokenRuns(String.valueOf(value));
        ThreadDumpJson.string(out, text.length() > 1024 ? text.substring(0, 1024) : text);
      }
    }
    out.append('}');
  }

  /** A JSON array of at most {@code max} strings. */
  private static void stringArray(StringBuilder out, List<String> values, int max) {
    out.append('[');
    int n = 0;
    if (values != null) {
      for (String value : values) {
        if (n >= max) {
          break;
        }
        out.append(n == 0 ? "" : ",");
        ThreadDumpJson.string(out, value);
        n++;
      }
    }
    out.append(']');
  }
}
