/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.agent.core.trace.IgnoreRules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The key registry against the 10 §15 table, and the value rules of {@link KeySpec}. */
class ConfigKeysTest {

  /**
   * 10 §15 transcribed: key, documented default (null = none/automatic), "원격" column (Y, N, -).
   * List defaults are written as the registry stores them; {@code remote-config.allow} follows
   * ADR-0010 C-92 instead of the 13 patterns of the table.
   */
  private static final String[][] TABLE = {
    {"server.address", null, "-"},
    {"server.tls", "true", "-"},
    {"server.tls.ca-file", null, "-"},
    {"server.tls.insecure-skip-verify", "false", "-"},
    {"server.token", null, "-"},
    {"server.token-file", null, "-"},
    {"server.connect-timeout-ms", "5000", "-"},
    {"server.deadline-ms", "5000", "-"},
    {"app.name", null, "-"},
    {"app.env", "default", "-"},
    {"app.instance", null, "-"},
    {"app.version", null, "-"},
    {"app.group", null, "-"},
    {"enabled", "true", "Y"},
    {"agent.disabled", "false", "-"},
    {"sampling.profile.slow-threshold-ms", "1000", "Y"},
    {"sampling.profile.normal-rate", "0.1", "Y"},
    {"sampling.profile.max-steps", "1000", "Y"},
    {"capture.query-string", "off", "N"},
    {"capture.query-string.whitelist", "", "N"},
    {"capture.http-headers", "", "N"},
    // ADR-0010 C-124 adds this row to 10 §15 (WO-12).
    {"capture.request-id-header", null, "N"},
    {"capture.remote-ip", "masked", "N"},
    {"capture.trusted-proxies", "", "N"},
    {"capture.user-id.mode", "off", "N"},
    {"capture.user-id.source", null, "N"},
    {"capture.sql-parameters", "masked", "N"},
    {"capture.sql-parameters.whitelist-columns", "", "N"},
    {"capture.mask-patterns", "", "N"},
    {"capture.error-message-max-length", "1024", "Y"},
    {"capture.stack-depth", "30", "Y"},
    {"instrumentation.disabled", "", "Y"},
    {"instrumentation.exclude-classes", "", "Y"},
    {
      "instrumentation.ignore-paths",
      "/actuator/health, /actuator/health/**, /health, /healthz, /ready, /favicon.ico",
      "Y"
    },
    {
      "instrumentation.ignore-extensions",
      "css, js, map, png, jpg, jpeg, gif, svg, ico, woff, woff2, ttf, eot",
      "Y"
    },
    {"instrumentation.ignore-user-agents", "", "Y"},
    {"instrumentation.ignore-methods", "OPTIONS", "Y"},
    {"instrumentation.custom-methods", "", "N"},
    {"instrumentation.custom-methods.max-classes-per-pattern", "500", "N"},
    {"instrumentation.app-packages", null, "Y"},
    {"instrumentation.service-dictionary-max", "10000", "N"},
    {"instrumentation.error-threshold", "100", "N"},
    {"instrumentation.transform-error-threshold", "10", "N"},
    {"transaction.max-duration-ms", "3600000", "Y"},
    {"transaction.slow-stack.enabled", "true", "Y"},
    {"transaction.slow-stack.interval-ms", "3000", "Y"},
    {"transaction.slow-stack.max-captures", "5", "Y"},
    {"transaction.slow-stack.max-threads-per-cycle", "10", "Y"},
    {"transaction.slow-stack.depth", "64", "Y"},
    {"propagation.exclude-hosts", "", "N"},
    {"propagation.argos-state.hosts", "", "N"},
    {"propagation.preserve-incoming", "true", "N"},
    {"async.step", "false", "Y"},
    {"buffer.transaction.capacity", "20000", "N"},
    {"buffer.max-age-seconds", "120", "N"},
    {"buffer.max-bytes", "32MB", "N"},
    {"sender.flush-interval-ms", "100", "N"},
    {"sender.batch-size", "500", "N"},
    {"sender.max-in-flight", "4", "N"},
    {"sender.compression", "gzip", "N"},
    {"metrics.interval-seconds", "5", "Y"},
    {"metrics.max-series", "500", "N"},
    {"stats.window-seconds", "10", "N"},
    {"stats.max-keys", "5000", "N"},
    {"active.snapshot-interval-seconds", "5", "Y"},
    {"sql.max-length", "65536", "N"},
    {"sql.backslash-escape", "true", "N"},
    {"sql.keep-hints", "false", "N"},
    {"commands.env-dump.mask-patterns", "", "N"},
    {"commands.thread-dump.cooldown-seconds", "5", "N"},
    {"commands.thread-dump.max-bytes", "1048576", "N"},
    {
      "remote-config.allow",
      "sampling.**, instrumentation.disabled, instrumentation.ignore-*, log.level, enabled,"
          + " transaction.slow-stack.*, capture.error-message-max-length, capture.stack-depth,"
          + " instrumentation.app-packages, async.step, active.snapshot-interval-seconds,"
          + " metrics.interval-seconds, transaction.max-duration-ms",
      "-"
    },
    {"log.level", "INFO", "Y"},
    {"log.dir", null, "-"},
    {"log.max-file-mb", "10", "-"},
    {"log.max-files", "5", "-"},
    {"debug.transform-timing", "false", "-"},
  };

  @Test
  @Tag("req:O-R03")
  @DisplayName("every key of the 10 §15 table is registered with its default and remote column")
  void registryMatchesTheTable() {
    Map<String, KeySpec> byName = new LinkedHashMap<String, KeySpec>();
    for (KeySpec spec : ConfigKeys.all()) {
      byName.put(spec.name(), spec);
    }
    List<String> tableKeys = new ArrayList<String>();
    for (String[] row : TABLE) {
      KeySpec spec = byName.get(row[0]);
      assertThat(spec).as(row[0]).isNotNull();
      assertThat(spec.defaultValue()).as(row[0] + " default").isEqualTo(row[1]);
      assertThat(remoteColumn(spec)).as(row[0] + " remote").isEqualTo(row[2]);
      assertThat(spec.description()).as(row[0] + " description").isNotBlank();
      tableKeys.add(row[0]);
    }
    // Everything else in the registry is the per-service family, a diagnostics or a test key.
    List<String> extra = new ArrayList<String>(byName.keySet());
    extra.removeAll(tableKeys);
    assertThat(extra)
        .containsExactly(
            "sampling.profile.slow-threshold-ms.services.*",
            "debug.transform-log",
            "debug.transform-list",
            "test.exporter",
            "test.faults",
            "test.selfdiag-file",
            "test.overhead-monitor");
    assertThat(byName.get("debug.transform-log").kind()).isEqualTo(KeySpec.Kind.DIAGNOSTIC);
    assertThat(byName.get("debug.transform-list").kind()).isEqualTo(KeySpec.Kind.DIAGNOSTIC);
    assertThat(byName.get("debug.transform-timing").kind()).isEqualTo(KeySpec.Kind.DIAGNOSTIC);
    for (String test :
        Arrays.asList(
            "test.exporter", "test.faults", "test.selfdiag-file", "test.overhead-monitor")) {
      assertThat(byName.get(test).kind()).as(test).isEqualTo(KeySpec.Kind.TEST);
      assertThat(ConfigKeys.isTestOnly(test)).isTrue();
    }
  }

  @Test
  @Tag("req:SEC-06")
  @DisplayName("C-88: the PoC exporter keys are gone; only server.token is secret")
  void pocKeysRemovedAndSecrets() {
    assertThat(ConfigKeys.isKnown("exporter")).isFalse();
    assertThat(ConfigKeys.isKnown("exporter.file.path")).isFalse();
    assertThat(ConfigKeys.isKnown("exporter.file.max-mb")).isFalse();
    List<String> secrets = new ArrayList<String>();
    for (KeySpec spec : ConfigKeys.all()) {
      if (spec.isSecret()) {
        secrets.add(spec.name());
      }
    }
    assertThat(secrets).containsExactly("server.token");
    assertThat(ConfigKeys.spec("server.address").isRequired()).isTrue();
    assertThat(ConfigKeys.spec("app.name").isRequired()).isTrue();
    assertThat(ConfigKeys.spec("agent.disabled").isPropertyOnly()).isTrue();
  }

  @Test
  @DisplayName("names: argos.enabled is the -D form of enabled, ARGOS_<KEY>, families")
  void names() {
    assertThat(ConfigKeys.canonical("argos.enabled")).isEqualTo("enabled");
    assertThat(ConfigKeys.canonical("log.level")).isEqualTo("log.level");
    assertThat(ConfigKeys.environmentName("log.max-file-mb")).isEqualTo("ARGOS_LOG_MAX_FILE_MB");
    assertThat(ConfigKeys.environmentName("transaction.slow-stack.max-threads-per-cycle"))
        .isEqualTo("ARGOS_TRANSACTION_SLOW_STACK_MAX_THREADS_PER_CYCLE");
    assertThat(ConfigKeys.spec("sampling.profile.slow-threshold-ms.services.GET /x").name())
        .isEqualTo("sampling.profile.slow-threshold-ms.services.*");
    assertThat(ConfigKeys.spec("sampling.profile.slow-threshold-ms.services.")).isNull();
    assertThat(ConfigKeys.spec("no.such.key")).isNull();
    assertThat(ConfigKeys.isKnown(ConfigKeys.CONFIG_FILE)).isFalse();
    assertThat(ConfigKeys.isTestOnly("testing.x")).isTrue();
    assertThat(ConfigKeys.isTestOnly("tests")).isFalse();
  }

  @Test
  @Tag("req:F-A23")
  @DisplayName("the registry defaults of the exclusion rules equal IgnoreRules' defaults")
  void ignoreDefaultsAgree() {
    AgentConfig defaults = AgentConfig.defaults();
    assertThat(defaults.instrumentationIgnorePaths()).isEqualTo(IgnoreRules.DEFAULT_PATHS);
    assertThat(defaults.instrumentationIgnoreExtensions())
        .isEqualTo(IgnoreRules.DEFAULT_EXTENSIONS);
    assertThat(defaults.instrumentationIgnoreUserAgents())
        .isEqualTo(IgnoreRules.DEFAULT_USER_AGENTS);
    assertThat(defaults.instrumentationIgnoreMethods()).isEqualTo(IgnoreRules.DEFAULT_METHODS);
  }

  @Test
  @DisplayName("every key is bounded: numbers by a range, strings and lists by lengths and counts")
  void everyKeyIsBounded() {
    for (KeySpec spec : ConfigKeys.all()) {
      switch (spec.type()) {
        case INT:
          assertThat(spec.min()).as(spec.name()).isGreaterThan(Integer.MIN_VALUE);
          assertThat(spec.max()).as(spec.name()).isLessThanOrEqualTo(Integer.MAX_VALUE);
          break;
        case BYTES:
          assertThat(spec.max()).as(spec.name()).isLessThan(Long.MAX_VALUE);
          break;
        case LIST:
          assertThat(spec.maxItems()).as(spec.name()).isBetween(1, 256);
          assertThat(spec.itemMaxLength()).as(spec.name()).isBetween(1, 1024);
          break;
        case STRING:
        case PATH:
        case ENUM:
          assertThat(spec.maxLength()).as(spec.name()).isBetween(1, 4096);
          break;
        default:
          break;
      }
    }
  }

  @Test
  @DisplayName("KeySpec parses units, flow lists and formats, and never quotes a rejected value")
  void valueRules() {
    assertThat(KeySpec.parseBytes("32MB")).isEqualTo(32L * 1024 * 1024);
    assertThat(KeySpec.parseBytes("512 kb")).isEqualTo(512L * 1024);
    assertThat(KeySpec.parseBytes("1GiB")).isEqualTo(1L << 30);
    assertThat(KeySpec.parseBytes("100")).isEqualTo(100);
    assertThat(KeySpec.parseDuration("250ms")).isEqualTo(250);
    assertThat(KeySpec.parseDuration("5s")).isEqualTo(5000);
    assertThat(KeySpec.parseDuration("2m")).isEqualTo(120_000);
    assertThat(KeySpec.parseDuration("1h")).isEqualTo(3_600_000);
    assertThatThrownBy(() -> KeySpec.parseBytes("lots"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(KeySpec.splitList("a, b ,, c", 10)).containsExactly("a", "b", "c");
    assertThat(KeySpec.splitList("[\"\\\\d{3,4}\", 'it''s', plain]", 10))
        .containsExactly("\\d{3,4}", "it's", "plain");
    assertThat(KeySpec.splitList("[\"open]", 10)).isNull();
    assertThat(KeySpec.splitList("[\"a\" x, b]", 10)).isNull();
    assertThat(KeySpec.splitList("[\"a\" , b]", 10)).containsExactly("a", "b");
    assertThat(KeySpec.splitList("a,b,c", 2)).isNull();

    KeySpec masks = ConfigKeys.spec(ConfigKeys.CAPTURE_MASK_PATTERNS);
    assertThat(masks.parse("[\"\\\\d{6}-\\\\d{7}\"]")).isEqualTo(Arrays.asList("\\d{6}-\\d{7}"));
    assertThat(masks.check("[\"(unclosed\"]")).contains("regular expression");
    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 33; i++) {
      many.append(i == 0 ? "" : ",").append("a").append(i);
    }
    assertThat(masks.check(many.toString())).contains("more than 32 items");
    assertThat(masks.check("[\"" + repeat('a', 513) + "\"]")).contains("longer than 512");

    KeySpec address = ConfigKeys.spec(ConfigKeys.SERVER_ADDRESS);
    assertThat(address.check("collector.example.com:9100")).isNull();
    assertThat(address.check("collector")).isNull();
    assertThat(address.check("[::1]:9100")).isNull();
    assertThat(address.check("collector:99999")).contains("port");
    assertThat(address.check("bad host:1")).isNotNull();

    KeySpec proxies = ConfigKeys.spec(ConfigKeys.CAPTURE_TRUSTED_PROXIES);
    assertThat(proxies.check("10.0.0.0/8, 192.168.1.10, 2001:db8::/32")).isNull();
    assertThat(proxies.check("10.0.0.0/33")).isNotNull();
    assertThat(proxies.check("300.1.1.1")).isNotNull();

    KeySpec custom = ConfigKeys.spec(ConfigKeys.INSTRUMENTATION_CUSTOM_METHODS);
    assertThat(custom.check("com.example.*Service#*|true, com.example.Job#run")).isNull();
    // the step type suffix of the earlier format still parses (CustomMethodPolicy ignores the
    // item locally, a push is rejected FORBIDDEN_PATTERN), C-237 (5)
    assertThat(custom.check("com.example.*Service#*|METHOD|false, com.example.Job#run")).isNull();
    assertThat(custom.check("com.example.Job#run|maybe")).isNotNull();
    assertThat(custom.check("com.example.Job")).isNotNull();
    assertThat(custom.check("com.example.Job#run|NOPE")).isNotNull();

    KeySpec rate = ConfigKeys.spec(ConfigKeys.NORMAL_RATE);
    assertThat(rate.parse("0.25")).isEqualTo(0.25);
    assertThat(rate.check("1.5")).contains("[0.0, 1.0]");
    assertThat(rate.check("NaN")).isNotNull();

    KeySpec level = ConfigKeys.spec(ConfigKeys.LOG_LEVEL);
    assertThat(level.parse("debug")).isEqualTo("DEBUG");
    assertThat(level.check("verbose")).contains("ERROR, WARN, INFO, DEBUG, TRACE");

    KeySpec token = ConfigKeys.spec(ConfigKeys.SERVER_TOKEN);
    String secret = "argos_" + repeat('s', 43);
    assertThat(token.check(secret)).isNull();
    assertThat(token.check("argos_ with space")).doesNotContain("with space");

    StringBuilder huge = new StringBuilder();
    for (int i = 0; i <= KeySpec.MAX_RAW_LENGTH; i++) {
      huge.append('x');
    }
    assertThat(ConfigKeys.spec(ConfigKeys.APP_GROUP).check(huge.toString()))
        .contains("longer than 65536");
    assertThat(ConfigKeys.spec(ConfigKeys.APP_NAME).check(repeat('n', 257)))
        .contains("longer than 256");
  }

  private static String remoteColumn(KeySpec spec) {
    switch (spec.remote()) {
      case Y:
        return "Y";
      case N:
        return "N";
      case NONE:
        return "-";
    }
    throw new AssertionError(spec.remote());
  }

  private static String repeat(char c, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, c);
    return new String(chars);
  }
}
