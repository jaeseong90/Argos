/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.AllowList;
import io.argos.agent.core.config.ConfigKeys;
import io.argos.agent.core.config.EffectiveConfig;
import io.argos.agent.core.config.TokenHolder;
import io.argos.agent.core.export.AgentInfo;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.guard.SelfDiagnostics;
import io.argos.agent.core.guard.SelfDiagnosticsCollector;
import io.argos.agent.core.trace.TraceRuntime;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code SELF_DIAGNOSTICS} (10 §12.1, ADR-0010 C-95): the members shared with the snapshot file,
 * the 10 §12.1 items, the masked token and the 256 KiB cap.
 */
@Tag("req:F-A12")
class SelfDiagCommandTest {
  private static final String TOKEN = "argos_" + String.join("", Collections.nCopies(43, "Q"));

  private static Map<String, Object> parse(CommandOutput out) {
    assertThat(out.contentType()).isEqualTo("application/json");
    return MiniJson.object(new String(out.payload(), StandardCharsets.UTF_8));
  }

  private static CommandEnv env(EffectiveConfig effective) {
    AgentCounters counters = new AgentCounters();
    Map<String, String> values = new LinkedHashMap<String, String>();
    values.put("app.name", "orders");
    values.put(ConfigKeys.SERVER_TOKEN, TOKEN);
    AgentConfig config = AgentConfig.of(values);
    TraceRuntime runtime = TraceRuntime.create(config, counters);
    SelfDiagnosticsCollector collector =
        new SelfDiagnosticsCollector(
            runtime::selfValues, counters, new String[] {"servlet", "jdbc"});
    Map<String, Object> extras = new LinkedHashMap<String, Object>();
    extras.put("transformed_classes", 12);
    extras.put("transport_state", "established " + TOKEN);
    extras.put("ratio", 0.5d);
    return CommandEnv.builder()
        .info(new AgentInfo("1.2.3-SNAPSHOT", "abc1234", "1"))
        .runtime(runtime)
        .diagnostics(collector)
        .config(() -> config)
        .effective(() -> effective)
        .allow(() -> AllowList.of(Arrays.asList("sampling.**", "log.level")))
        .configHash(() -> "00112233445566aa")
        .clockOffsetMillis(() -> -42L)
        .extras(() -> extras)
        .build();
  }

  private static EffectiveConfig effective(int extraKeys, int valueChars) {
    Map<String, EffectiveConfig.Entry> entries = new LinkedHashMap<String, EffectiveConfig.Entry>();
    entries.put(
        ConfigKeys.SERVER_TOKEN,
        new EffectiveConfig.Entry(TokenHolder.MASK, EffectiveConfig.Source.ENVIRONMENT));
    entries.put("log.level", new EffectiveConfig.Entry("info", EffectiveConfig.Source.DEFAULT));
    entries.put(
        "sampling.profile.normal-rate",
        new EffectiveConfig.Entry("0.3", EffectiveConfig.Source.REMOTE));
    entries.put(
        "instrumentation.ignore-paths",
        new EffectiveConfig.Entry("[/health, " + TOKEN + "]", EffectiveConfig.Source.FILE));
    StringBuilder value = new StringBuilder();
    for (int i = 0; i < valueChars; i++) {
      value.append('v');
    }
    for (int i = 0; i < extraKeys; i++) {
      entries.put(
          "x.key" + i, new EffectiveConfig.Entry(value.toString(), EffectiveConfig.Source.FILE));
    }
    return new EffectiveConfig(entries);
  }

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName(
      "self diagnostics JSON keeps the snapshot file's members (C-95) and adds version, config"
          + " hash, effective config with sources, allow list, capture modes, queues, dictionary,"
          + " agent threads, clock offset, virtual threads and cgroup")
  void items() {
    CommandEnv env = env(effective(0, 0));
    CommandOutput out = new SelfDiagCommand(env).run(Collections.<String, String>emptyMap());
    assertThat(out.truncated()).isFalse();
    Map<String, Object> root = parse(out);
    // Members shared with SelfDiagnostics.toJson() (the snapshot file), same names.
    Map<String, Object> file = MiniJson.object(env.diagnostics().snapshot().toJson());
    assertThat(root.get("format")).isEqualTo(1L);
    for (String key : file.keySet()) {
      assertThat(root).as(key).containsKey(key);
    }
    assertThat(root.get("active_registry_size")).isEqualTo(file.get("active_registry_size"));
    assertThat(((Map<String, Object>) root.get("counters")).keySet())
        .containsAll(((Map<String, Object>) file.get("counters")).keySet());
    assertThat((List<Object>) root.get("modules")).hasSize(2);

    Map<String, Object> agent = (Map<String, Object>) root.get("agent");
    assertThat(agent.get("version")).isEqualTo("1.2.3-SNAPSHOT");
    assertThat(agent.get("commit")).isEqualTo("abc1234");
    assertThat(agent.get("protocol")).isEqualTo("1");
    assertThat(agent.get("release_build")).isEqualTo(false);
    assertThat(root.get("uptime_ms")).isInstanceOf(Long.class);
    assertThat(root.get("config_hash")).isEqualTo("00112233445566aa");
    Map<String, Object> effective = (Map<String, Object>) root.get("effective_config");
    Map<String, Object> rate = (Map<String, Object>) effective.get("sampling.profile.normal-rate");
    assertThat(rate.get("value")).isEqualTo("0.3");
    assertThat(rate.get("source")).isEqualTo("remote");
    assertThat(((Map<String, Object>) effective.get("log.level")).get("source"))
        .isEqualTo("default");
    assertThat((List<Object>) root.get("remote_config_allow"))
        .containsExactly("sampling.**", "log.level");
    Map<String, Object> capture = (Map<String, Object>) root.get("capture");
    assertThat(capture).containsKeys("query_string", "sql_parameters", "http_headers");
    Map<String, Object> gauges = (Map<String, Object>) root.get("gauges");
    assertThat(gauges).containsKey(TraceRuntime.HEAP_BYTES_ESTIMATED);
    assertThat(gauges.keySet()).anyMatch(k -> k.startsWith(TraceRuntime.QUEUE_SIZE + "{"));
    Map<String, Object> dictionary = (Map<String, Object>) root.get("dictionary");
    assertThat((Map<String, Object>) dictionary.get("sql"))
        .containsKeys("size", "capacity", "overflow");
    assertThat((Map<String, Object>) root.get("active_registry"))
        .containsKeys("size", "capacity", "rejected", "virtual");
    assertThat(root.get("threads")).isInstanceOf(List.class);
    assertThat(root.get("clock_offset_ms")).isEqualTo(-42L);
    assertThat((Map<String, Object>) root.get("virtual_threads")).containsKey("supported");
    assertThat(root.get("cgroup")).isIn("v1", "v2", "none");
    Map<String, Object> extras = (Map<String, Object>) root.get("extras");
    assertThat(extras.get("transformed_classes")).isEqualTo(12L);
    assertThat(extras.get("ratio")).isEqualTo(0.5d);
    assertThat(root.get("truncated")).isEqualTo(false);
  }

  @Test
  @DisplayName(
      "self diagnostics never shows the token: server.token and token-shaped values are"
          + " argos_****")
  void tokenMasked() {
    CommandOutput out =
        new SelfDiagCommand(env(effective(0, 0))).run(Collections.<String, String>emptyMap());
    String text = new String(out.payload(), StandardCharsets.UTF_8);
    assertThat(text).doesNotContain(TOKEN).doesNotContain("QQQQQQQQ");
    assertThat(text).contains("\"server.token\":{\"value\":\"argos_****\",\"source\":\"env\"}");
    assertThat(SelfDiagCommand.shown(ConfigKeys.SERVER_TOKEN, TOKEN, 1000))
        .isEqualTo(TokenHolder.MASK);
    assertThat(SelfDiagCommand.shown("x.password", "pw", 1000)).isEqualTo(TokenHolder.MASK);
    assertThat(SelfDiagCommand.shown(ConfigKeys.SERVER_TOKEN_FILE, "/etc/argos/token", 1000))
        .isEqualTo("/etc/argos/token");
  }

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName(
      "self diagnostics stays within 256 KiB: values cut, then the effective config left out")
  void cap() {
    CommandOutput cut =
        new SelfDiagCommand(env(effective(1000, 300))).run(Collections.<String, String>emptyMap());
    assertThat(cut.payload().length).isLessThanOrEqualTo(SelfDiagCommand.MAX_BYTES);
    assertThat(cut.truncated()).isTrue();
    Map<String, Object> root = parse(cut);
    assertThat(root.get("truncated")).isEqualTo(true);
    Map<String, Object> effective = (Map<String, Object>) root.get("effective_config");
    assertThat((String) ((Map<String, Object>) effective.get("x.key1")).get("value"))
        .hasSize(SelfDiagCommand.CUT_VALUE_CHARS + 3);
    assertThat(root).doesNotContainKey("extras");

    CommandOutput omitted =
        new SelfDiagCommand(env(effective(5000, 300))).run(Collections.<String, String>emptyMap());
    assertThat(omitted.payload().length).isLessThanOrEqualTo(SelfDiagCommand.MAX_BYTES);
    Map<String, Object> small = parse(omitted);
    assertThat(small).doesNotContainKey("effective_config");
    assertThat(small.get("truncated")).isEqualTo(true);
    assertThat(small).containsKeys("agent", "config_hash", "counters", "modules");
  }

  @Test
  @DisplayName("self diagnostics without any wiring is still a valid JSON object")
  void emptyEnv() {
    Map<String, Object> root =
        parse(
            new SelfDiagCommand(CommandEnv.builder().build())
                .run(Collections.<String, String>emptyMap()));
    assertThat(root.get("format")).isEqualTo(1L);
    assertThat(root).containsKeys("counters", "modules", "jvm", "threads", "cgroup");
    assertThat(root).doesNotContainKeys("agent", "effective_config", "clock_offset_ms");
  }

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName(
      "C-234 (1) modules carry name, state and, when not enabled, the reason (05 §2.7-3 module"
          + " status)")
  void moduleReasons() {
    SelfDiagnostics snapshot =
        SelfDiagnostics.builder()
            .module(0, "servlet", ModuleHealth.ENABLED, 0)
            .module(1, "jdbc", ModuleHealth.DISABLED_BY_REMOTE, 0)
            .module(2, "okhttp", ModuleHealth.DISABLED_BY_ERRORS, 11)
            .module(3, "kafka", ModuleHealth.DISABLED_BY_CONFIG, 0)
            .build();
    Map<String, Object> root = MiniJson.object(snapshot.toJson());
    List<Object> modules = (List<Object>) root.get("modules");
    assertThat(modules).hasSize(4);
    Map<String, Object> servlet = (Map<String, Object>) modules.get(0);
    assertThat(servlet.get("name")).isEqualTo("servlet");
    assertThat(servlet.get("state")).isEqualTo("ENABLED");
    assertThat(servlet).doesNotContainKey("reason");
    Map<String, Object> jdbc = (Map<String, Object>) modules.get(1);
    assertThat(jdbc.get("state")).isEqualTo("DISABLED_BY_REMOTE");
    assertThat(jdbc.get("reason")).isEqualTo("instrumentation.disabled (remote configuration)");
    Map<String, Object> okhttp = (Map<String, Object>) modules.get(2);
    assertThat(okhttp.get("state")).isEqualTo("DISABLED_BY_ERRORS");
    assertThat(okhttp.get("errors")).isEqualTo(11L);
    assertThat((String) okhttp.get("reason")).contains("instrumentation.error-threshold");
    Map<String, Object> kafka = (Map<String, Object>) modules.get(3);
    assertThat((String) kafka.get("reason")).startsWith("instrumentation.disabled");
    // The command result carries the same module members (C-95: one parser for both).
    CommandEnv env = env(effective(0, 0));
    Map<String, Object> command =
        parse(new SelfDiagCommand(env).run(Collections.<String, String>emptyMap()));
    for (Object module : (List<Object>) command.get("modules")) {
      assertThat((Map<String, Object>) module).containsKeys("id", "name", "state", "errors");
    }
  }
}
