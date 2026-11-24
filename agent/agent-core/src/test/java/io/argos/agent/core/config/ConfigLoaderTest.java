/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.agent.core.config.EffectiveConfig.Source;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ConfigLoader (10 §4.3 P4, §15, 02 §7, SEC-06, T-BLD-010): sources, token, required keys. */
class ConfigLoaderTest {
  private static final String TOKEN = "argos_" + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";
  private static final String OTHER_TOKEN =
      "argos_" + "ZyXwVuTsRqPoNmLkJiHgFeDcBa9876543210_-edcba";

  @TempDir Path dir;

  // --- helpers --------------------------------------------------------------------------------

  private static Map<String, String> map(String... keyValues) {
    Map<String, String> map = new LinkedHashMap<String, String>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  private File yml(String... lines) throws IOException {
    Path file = dir.resolve("argos-agent.yml");
    Files.write(file, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    return file.toFile();
  }

  /** Required keys set so that only the case under test warns. */
  private static Map<String, String> required(String... more) {
    Map<String, String> props =
        map(
            "argos.server.address", "collector:9100",
            "argos.app.name", "order-api",
            "argos.app.env", "test");
    props.putAll(map(more));
    return props;
  }

  private static LoadedConfig load(
      File yml, Map<String, String> props, Map<String, String> env, boolean release) {
    return ConfigLoader.load(
        new ConfigInputs(yml, props, env, release),
        new TokenFile.PermissionProbe() {
          @Override
          public TokenFile.Access check(Path path) {
            return TokenFile.Access.OWNER_ONLY;
          }
        });
  }

  private static LoadedConfig load(
      File yml, Map<String, String> props, Map<String, String> env, TokenFile.Access access) {
    return ConfigLoader.load(
        new ConfigInputs(yml, props, env, false),
        new TokenFile.PermissionProbe() {
          @Override
          public TokenFile.Access check(Path path) {
            return access;
          }
        });
  }

  private static Map<String, String> none() {
    return Collections.<String, String>emptyMap();
  }

  private static void assertNoToken(LoadedConfig loaded, String token) {
    List<String> all = new ArrayList<String>();
    all.addAll(loaded.warnings());
    all.addAll(loaded.errors());
    all.addAll(loaded.notes());
    all.addAll(loaded.effective().lines());
    all.addAll(ConfigHash.canonicalLines(loaded.config()));
    all.add(loaded.config().toString());
    all.add(loaded.token().toString());
    for (String line : all) {
      assertThat(line).doesNotContain(token);
    }
  }

  // --- sources --------------------------------------------------------------------------------

  @Test
  @Tag("req:DSN-02-17")
  @DisplayName(
      "precedence default < yml < -Dargos.<key> < ARGOS_<KEY>, with the source of each value")
  void precedenceAndSources() throws Exception {
    File file =
        yml(
            "server:",
            "  address: from-file:9100",
            "app:",
            "  name: from-file",
            "  env: prod",
            "log:",
            "  level: WARN",
            "sampling.profile.normal-rate: 0.5");
    LoadedConfig loaded =
        load(
            file,
            map("argos.app.name", "from-property", "argos.log.level", "DEBUG"),
            map("ARGOS_LOG_LEVEL", "TRACE", "ARGOS_APP_GROUP", "payments"),
            false);
    AgentConfig config = loaded.config();
    EffectiveConfig effective = loaded.effective();
    assertThat(config.serverAddress()).isEqualTo("from-file:9100");
    assertThat(effective.source("server.address")).isEqualTo(Source.FILE);
    assertThat(config.appName()).isEqualTo("from-property");
    assertThat(effective.source("app.name")).isEqualTo(Source.SYSTEM_PROPERTY);
    assertThat(config.logLevel()).isEqualTo("TRACE");
    assertThat(effective.entries().get("log.level").value()).isEqualTo("TRACE");
    assertThat(effective.source("log.level")).isEqualTo(Source.ENVIRONMENT);
    assertThat(config.appGroup()).isEqualTo("payments");
    assertThat(config.normalRate()).isEqualTo(0.5);
    assertThat(effective.source("sampling.profile.max-steps")).isEqualTo(Source.DEFAULT);
    assertThat(effective.value("sampling.profile.max-steps")).isEqualTo("1000");
    assertThat(effective.source("sampling.profile.normal-rate")).isEqualTo(Source.FILE);
    assertThat(loaded.warnings()).isEmpty();
    assertThat(loaded.errors()).isEmpty();
    assertThat(effective.lines())
        .contains(
            "log.level = TRACE (env)",
            "app.name = from-property (sysprop)",
            "server.address = from-file:9100 (file)",
            "sampling.profile.max-steps = 1000 (default)",
            "server.token = - (default)");
    // every registry key has an entry
    for (KeySpec spec : ConfigKeys.all()) {
      if (!spec.isFamily()) {
        assertThat(effective.entries()).containsKey(spec.name());
      }
    }
  }

  @Test
  @Tag("req:O-R03")
  @DisplayName(
      "unknown keys: one WARN each from every source, never a failure; inputs are not keys")
  void unknownKeys() throws Exception {
    File file = yml("server:", "  adress: x:1", "config: other.yml", "app:", "  name: a");
    LoadedConfig loaded =
        load(
            file,
            map(
                "argos.server.address", "c:1",
                "argos.app.env", "test",
                "argos.no.such.key", "1",
                "argos.config", "ignored.yml"),
            map("ARGOS_NO_SUCH_KEY", "1", "ARGOS_CONFIG", "x.yml"),
            false);
    assertThat(loaded.warnings())
        .containsExactly(
            "unknown configuration key 'server.adress' (argos-agent.yml line 2) ignored",
            "unknown configuration key 'config' (argos-agent.yml line 3) ignored",
            "unknown configuration key 'no.such.key' (-Dargos.no.such.key) ignored",
            "unknown environment variable ARGOS_NO_SUCH_KEY ignored");
    assertThat(loaded.config().appName()).isEqualTo("a");
  }

  @Test
  @DisplayName("warnings are capped at 100: the last line counts the ones not logged")
  void warningCap() {
    Map<String, String> props = required();
    for (int i = 0; i < 150; i++) {
      props.put("argos.unknown.key" + i, "x");
    }
    LoadedConfig loaded = load(null, props, none(), false);
    assertThat(loaded.warnings()).hasSize(ConfigLoader.MAX_WARNINGS);
    assertThat(loaded.warnings().get(ConfigLoader.MAX_WARNINGS - 1))
        .isEqualTo("51 more configuration warnings were not logged");
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName("an invalid value is a WARN naming key and source, and the key keeps its default")
  void invalidValues() throws Exception {
    File file = yml("sampling:", "  profile:", "    max-steps: lots", "log.level: [INFO]");
    LoadedConfig loaded =
        load(
            file,
            required("argos.sampling.profile.normal-rate", "1.5", "argos.capture.user-id.mode", ""),
            map("ARGOS_SERVER_CONNECT_TIMEOUT_MS", "-5"),
            false);
    assertThat(loaded.warnings())
        .containsExactlyInAnyOrder(
            "log.level (argos-agent.yml line 4) takes a single value, not a list; ignored",
            "invalid value 'lots' for sampling.profile.max-steps (argos-agent.yml line 3):"
                + " expected an integer; using the default",
            "invalid value '1.5' for sampling.profile.normal-rate"
                + " (-Dargos.sampling.profile.normal-rate): expected a number in [0.0, 1.0];"
                + " using the default",
            "invalid value '-5' for server.connect-timeout-ms (ARGOS_SERVER_CONNECT_TIMEOUT_MS):"
                + " expected a value in [1, 600000]; using the default");
    AgentConfig config = loaded.config();
    assertThat(config.maxSteps()).isEqualTo(1000);
    assertThat(config.normalRate()).isEqualTo(0.1);
    assertThat(config.serverConnectTimeoutMs()).isEqualTo(5000);
    assertThat(config.captureUserIdMode()).isEqualTo("off"); // empty scalar = unset, no warning
    assertThat(loaded.effective().source("sampling.profile.max-steps")).isEqualTo(Source.DEFAULT);
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName("a yml outside the subset: one WARN with the line, the whole file ignored")
  void malformedFileIsIgnoredAsAWhole() throws Exception {
    File file = yml("server:", "  address: from-file:1", "app:", "  name: |", "    multi");
    LoadedConfig loaded = load(file, map("argos.app.env", "x"), none(), false);
    assertThat(loaded.warnings())
        .containsExactly(
            "configuration file "
                + file.getPath()
                + " line 4: block scalars are not supported; the whole file is ignored");
    assertThat(loaded.config().serverAddress()).isNull(); // line 2 was not applied
    assertThat(loaded.errors()).hasSize(2);
  }

  @Test
  @DisplayName("a yml over 256 KiB, a missing yml and a directory are WARNs; other sources apply")
  void unusableFiles() throws Exception {
    Path big = dir.resolve("big.yml");
    byte[] bytes = new byte[YamlSubset.MAX_FILE_BYTES + 1];
    Arrays.fill(bytes, (byte) '#');
    Files.write(big, bytes);
    LoadedConfig loaded = load(big.toFile(), required(), none(), false);
    assertThat(loaded.warnings())
        .containsExactly(
            "configuration file "
                + big.toFile().getPath()
                + " is larger than 262144 bytes; the whole file is ignored");
    assertThat(loaded.config().appName()).isEqualTo("order-api");

    File missing = dir.resolve("missing.yml").toFile();
    assertThat(load(missing, required(), none(), false).warnings())
        .containsExactly(
            "configuration file "
                + missing.getPath()
                + " does not exist or is not a file; continuing without it");
    assertThat(load(dir.toFile(), required(), none(), false).warnings()).hasSize(1);
  }

  @Test
  @DisplayName("yml: list values, the slow threshold map form and custom-methods mappings")
  void yamlValueForms() throws Exception {
    File file =
        yml(
            "instrumentation:",
            "  ignore-paths:",
            "    - /ping",
            "    - \"/a,b\"",
            "  ignore-methods: []",
            "  custom-methods:",
            "    - pattern: \"com.example.service.*Service#*\"",
            "      step-type: method",
            "      as-transaction: TRUE",
            "    - pattern: com.example.Job#run",
            "    - step-type: SQL",
            "    - pattern: com.example.X#y",
            "      color: blue",
            "    - pattern: com.example.Batch#run",
            "      as-transaction: true",
            "  disabled: jdbc, executor",
            "sampling:",
            "  profile:",
            "    slow-threshold-ms:",
            "      default: 800",
            "      services:",
            "        \"GET /orders/{id}\": 2000",
            "capture:",
            "  http-headers:",
            "    a: b");
    LoadedConfig loaded = load(file, required(), none(), false);
    AgentConfig config = loaded.config();
    assertThat(config.instrumentationIgnorePaths()).containsExactly("/ping", "/a,b");
    assertThat(config.instrumentationIgnoreMethods()).isEmpty();
    assertThat(config.instrumentationCustomMethods())
        .containsExactly(
            // step-type stays a suffix, so the policy ignores that item alone (C-237 (5))
            "com.example.service.*Service#*|METHOD|true",
            "com.example.Job#run",
            "com.example.X#y",
            "com.example.Batch#run|true");
    assertThat(config.instrumentationDisabled()).containsExactly("jdbc", "executor");
    assertThat(config.slowThresholdMs()).isEqualTo(800);
    assertThat(config.slowThresholdMs("GET /orders/{id}")).isEqualTo(2000);
    assertThat(loaded.effective().source("sampling.profile.slow-threshold-ms"))
        .isEqualTo(Source.FILE);
    assertThat(
            loaded
                .effective()
                .value("sampling.profile.slow-threshold-ms.services.GET /orders/{id}"))
        .isEqualTo("2000");
    assertThat(loaded.warnings())
        .containsExactly(
            "instrumentation.custom-methods item 3 (argos-agent.yml line 6) has no pattern;"
                + " item ignored",
            "instrumentation.custom-methods item 4 (argos-agent.yml line 6): unknown field"
                + " 'color' ignored",
            "unknown configuration key 'capture.http-headers.a' (argos-agent.yml line 25)"
                + " ignored");
  }

  @Test
  @DisplayName("placeholders other than server.token: ${ARGOS_TOKEN} are WARNs and stay unset")
  void placeholders() throws Exception {
    File file = yml("app:", "  version: ${APP_VERSION:}", "  group: \"${GROUP}\"");
    LoadedConfig loaded = load(file, required("argos.log.dir", "${HOME}/logs"), none(), false);
    assertThat(loaded.config().appVersion()).isNull();
    assertThat(loaded.config().appGroup()).isNull();
    assertThat(loaded.config().logDir()).isNull();
    assertThat(loaded.warnings())
        .containsExactly(
            "placeholders are supported only in argos-agent.yml as server.token: ${ARGOS_TOKEN}; app.version"
                + " (argos-agent.yml line 2) is ignored",
            "placeholders are supported only in argos-agent.yml as server.token: ${ARGOS_TOKEN}; app.group"
                + " (argos-agent.yml line 3) is ignored",
            "placeholders are supported only in argos-agent.yml as server.token: ${ARGOS_TOKEN}; log.dir"
                + " (-Dargos.log.dir) is ignored");
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName("agent.disabled is honoured from -Dargos.agent.disabled only")
  void agentDisabledIsPropertyOnly() throws Exception {
    File file = yml("agent:", "  disabled: true");
    LoadedConfig fromFile = load(file, required(), map("ARGOS_AGENT_DISABLED", "true"), false);
    assertThat(fromFile.config().agentDisabled()).isFalse();
    assertThat(fromFile.warnings())
        .containsExactly(
            "agent.disabled is read only from -Dargos.agent.disabled; the value from"
                + " argos-agent.yml line 2 is ignored",
            "agent.disabled is read only from -Dargos.agent.disabled; the value from"
                + " ARGOS_AGENT_DISABLED is ignored");
    LoadedConfig fromProperty = load(null, required("argos.agent.disabled", "true"), none(), false);
    assertThat(fromProperty.config().agentDisabled()).isTrue();
    assertThat(fromProperty.effective().source("agent.disabled")).isEqualTo(Source.SYSTEM_PROPERTY);
  }

  @Test
  @Tag("req:DSN-18-15")
  @DisplayName(
      "T-BLD-010 a release build ignores test.* and testing.* from every source with one WARN")
  void releaseBuildIgnoresTestKeys() throws Exception {
    File file = yml("test:", "  faults: advice-throw:jdbc", "debug.transform-timing: true");
    Map<String, String> props =
        required(
            "argos.test.exporter", "file:/tmp/x",
            "argos.testing.anything", "1");
    Map<String, String> env =
        map("ARGOS_TEST_SELFDIAG_FILE", "/tmp/d.json", "ARGOS_TESTING_FOO", "1");
    LoadedConfig release = load(file, props, env, true);
    assertThat(release.warnings())
        .containsExactly(
            "test-only settings ignored in a release build (T-BLD-010): [-Dargos.test.exporter,"
                + " -Dargos.testing.anything, ARGOS_TESTING_FOO, ARGOS_TEST_SELFDIAG_FILE,"
                + " test.faults (argos-agent.yml line 2)]");
    assertThat(release.errors()).isEmpty();
    assertThat(release.config().testExporter()).isNull();
    assertThat(release.config().testFaults()).isEmpty();
    assertThat(release.config().testSelfdiagFile()).isNull();
    assertThat(release.config().debugTransformTiming()).isTrue(); // C-89: diagnostics stay
    assertThat(release.effective().source("test.exporter")).isEqualTo(Source.DEFAULT);

    LoadedConfig development = load(file, props, env, false);
    assertThat(development.config().testExporter()).isEqualTo("file:/tmp/x");
    assertThat(development.config().testFaults()).containsExactly("advice-throw:jdbc");
    assertThat(development.config().testSelfdiagFile()).isEqualTo("/tmp/d.json");
    assertThat(development.warnings())
        .containsExactly(
            "unknown configuration key 'testing.anything' (-Dargos.testing.anything) ignored",
            "unknown environment variable ARGOS_TESTING_FOO ignored");
  }

  @Test
  @Tag("req:DSN-18-15")
  @DisplayName(
      "T-AG-064g C-240 (3) test.overhead-monitor: a release build drops it from every source with"
          + " the T-BLD-010 WARN and keeps the CPU guard on; a development build reads off; an"
          + " invalid value keeps the default on")
  void overheadMonitorTestKeyIsDevelopmentOnly() throws Exception {
    Map<String, String> props = required("argos.test.overhead-monitor", "off");
    LoadedConfig release = load(null, props, none(), true);
    assertThat(release.warnings())
        .containsExactly(
            "test-only settings ignored in a release build (T-BLD-010):"
                + " [-Dargos.test.overhead-monitor]");
    assertThat(release.config().testOverheadMonitorOff()).isFalse();
    assertThat(release.effective().source("test.overhead-monitor")).isEqualTo(Source.DEFAULT);
    LoadedConfig fromEnv = load(null, required(), map("ARGOS_TEST_OVERHEAD_MONITOR", "off"), true);
    assertThat(fromEnv.config().testOverheadMonitorOff()).isFalse();
    assertThat(fromEnv.warnings()).hasSize(1);
    assertThat(fromEnv.warnings().get(0)).contains("ARGOS_TEST_OVERHEAD_MONITOR");

    LoadedConfig development = load(null, props, none(), false);
    assertThat(development.warnings()).isEmpty();
    assertThat(development.config().testOverheadMonitorOff()).isTrue();
    assertThat(development.effective().source("test.overhead-monitor"))
        .isEqualTo(Source.SYSTEM_PROPERTY);
    LoadedConfig invalid =
        load(null, required("argos.test.overhead-monitor", "disabled"), none(), false);
    assertThat(invalid.config().testOverheadMonitorOff()).isFalse();
    assertThat(invalid.warnings()).hasSize(1);
  }

  @Test
  @DisplayName("the T-BLD-010 WARN names at most 20 settings and counts the rest")
  void ignoredTestSettingsCap() {
    Map<String, String> props = required();
    for (int i = 0; i < 30; i++) {
      props.put("argos.test.k" + (i < 10 ? "0" : "") + i, "x");
    }
    LoadedConfig loaded = load(null, props, none(), true);
    assertThat(loaded.warnings()).hasSize(1);
    assertThat(loaded.warnings().get(0))
        .startsWith("test-only settings ignored in a release build (T-BLD-010): [-Dargos.test.k00,")
        .endsWith("-Dargos.test.k19] and 10 more");
  }

  @Test
  @DisplayName("per-service slow thresholds collected from all sources are capped at 1,000")
  void familyCap() {
    Map<String, String> props = required();
    for (int i = 0; i < AgentConfig.MAX_SERVICE_THRESHOLDS + 5; i++) {
      props.put("argos.sampling.profile.slow-threshold-ms.services.svc" + i, "10");
    }
    LoadedConfig loaded = load(null, props, none(), false);
    assertThat(loaded.config().slowThresholdServices()).hasSize(AgentConfig.MAX_SERVICE_THRESHOLDS);
    assertThat(loaded.warnings()).hasSize(5);
    assertThat(loaded.warnings().get(0)).startsWith("more than 1000 per-service slow thresholds;");
  }

  // --- required keys --------------------------------------------------------------------------

  @Test
  @Tag("req:F-A15")
  @DisplayName("missing server.address and app.name: one ERROR each, a fallback name, app.env WARN")
  void requiredKeys() {
    LoadedConfig loaded =
        ConfigLoader.load(
            new ConfigInputs(
                null,
                none(),
                none(),
                false,
                "/opt/shop/shop-boot3-1.2.0-SNAPSHOT.jar --password=secret",
                Collections.<String>emptyList()));
    assertThat(loaded.errors())
        .containsExactly(
            "server.address is not set: the agent sends no data and opens no connection; set"
                + " server.address in argos-agent.yml, -Dargos.server.address or"
                + " ARGOS_SERVER_ADDRESS",
            "app.name is not set; using 'shop-boot3' from the command line; set app.name in"
                + " argos-agent.yml, -Dargos.app.name or ARGOS_APP_NAME");
    assertThat(loaded.warnings()).containsExactly("app.env is not set; using 'default'");
    assertThat(loaded.config().appName()).isEqualTo("shop-boot3");
    assertThat(loaded.config().appEnv()).isEqualTo("default");
    assertThat(loaded.effective().value("app.name")).isEqualTo("shop-boot3");
    assertThat(loaded.effective().source("app.name")).isEqualTo(Source.DEFAULT);
    assertThat(loaded.transportConfigured()).isFalse();
    for (String line : loaded.errors()) {
      assertThat(line).doesNotContain("secret");
    }

    // a development test exporter replaces the transport: no server.address ERROR
    LoadedConfig harness =
        load(
            null,
            map("argos.app.name", "a", "argos.app.env", "e", "argos.test.exporter", "file:/x"),
            none(),
            false);
    assertThat(harness.errors()).isEmpty();
    assertThat(harness.transportConfigured()).isFalse();
    LoadedConfig grpc =
        load(
            null,
            map("argos.app.name", "a", "argos.app.env", "e", "argos.test.exporter", "grpc"),
            none(),
            false);
    assertThat(grpc.errors()).hasSize(1);
    LoadedConfig release =
        load(
            null,
            map("argos.app.name", "a", "argos.app.env", "e", "argos.test.exporter", "noop"),
            none(),
            true);
    assertThat(release.errors()).hasSize(1);
    assertThat(load(null, required(), none(), false).transportConfigured()).isTrue();
  }

  @Test
  @DisplayName("fallback application name: jar without version, main class, sanitized, bounded")
  void fallbackAppName() {
    assertThat(ConfigLoader.fallbackAppName("shop-boot3.jar")).isEqualTo("shop-boot3");
    assertThat(ConfigLoader.fallbackAppName("C:\\apps\\order-api-2.1.0.war --x"))
        .isEqualTo("order-api");
    assertThat(ConfigLoader.fallbackAppName("com.example.OrderApplication arg1"))
        .isEqualTo("OrderApplication");
    assertThat(ConfigLoader.fallbackAppName("org.apache.catalina.startup.Bootstrap start"))
        .isEqualTo("Bootstrap");
    assertThat(ConfigLoader.fallbackAppName("Main")).isEqualTo("Main");
    assertThat(ConfigLoader.fallbackAppName("/x/app?name.jar")).isEqualTo("app-name");
    assertThat(ConfigLoader.fallbackAppName("/x/\u00e9\u00e9.jar")).isEqualTo("unnamed-app");
    assertThat(ConfigLoader.fallbackAppName(null)).isEqualTo("unnamed-app");
    assertThat(ConfigLoader.fallbackAppName("   ")).isEqualTo("unnamed-app");
    assertThat(ConfigLoader.fallbackAppName(YamlSubsetTest.repeat('a', 500) + ".jar"))
        .hasSize(ConfigLoader.MAX_FALLBACK_NAME_CHARS);
  }

  // --- token (SEC-06, 16 §2.6) ---------------------------------------------------------------

  @Test
  @Tag("req:SEC-06")
  @DisplayName(
      "T-SEC-06a plaintext server.token in the yml: the 16 §2.6 WARN once, argos_**** only")
  void plaintextToken() throws Exception {
    File file = yml("server:", "  token: " + TOKEN);
    LoadedConfig loaded = load(file, required(), none(), false);
    assertThat(loaded.token().isPresent()).isTrue();
    assertThat(loaded.token().reveal()).isEqualTo(TOKEN);
    assertThat(loaded.token().source()).isEqualTo(TokenHolder.Source.PLAIN);
    assertThat(loaded.warnings())
        .containsExactly(
            "token is stored in plaintext in argos-agent.yml; prefer token-file or ARGOS_TOKEN");
    assertThat(loaded.effective().value("server.token")).isEqualTo("argos_****");
    assertThat(loaded.effective().source("server.token")).isEqualTo(Source.FILE);
    assertThat(loaded.config().explicitValues()).doesNotContainKey("server.token");
    assertNoToken(loaded, TOKEN);
  }

  @Test
  @Tag("req:SEC-06")
  @DisplayName("T-SEC-06a the token never changes ConfigHash and never reaches its input")
  void tokenNotInHash() throws Exception {
    LoadedConfig none = load(null, required(), none(), false);
    LoadedConfig first = load(yml("server.token: " + TOKEN), required(), none(), false);
    LoadedConfig second = load(null, required(), map("ARGOS_TOKEN", OTHER_TOKEN), false);
    assertThat(first.configHash()).isEqualTo(none.configHash()).isEqualTo(second.configHash());
    assertThat(first.configHash()).matches("[0-9a-f]{16}");
    for (String line : ConfigHash.canonicalLines(first.config())) {
      assertThat(line).doesNotStartWith("server.token=");
    }
  }

  @Test
  @Tag("req:SEC-06")
  @DisplayName(
      "T-SEC-06a token ways: server.token > server.token-file > ARGOS_TOKEN, ${ARGOS_TOKEN}")
  void tokenPrecedence() throws Exception {
    Path tokenFile = dir.resolve("token");
    Files.write(tokenFile, ("  " + OTHER_TOKEN + "\n\n").getBytes(StandardCharsets.UTF_8));
    String path = tokenFile.toString();

    LoadedConfig fromEnv = load(null, required(), map("ARGOS_TOKEN", TOKEN), false);
    assertThat(fromEnv.token().reveal()).isEqualTo(TOKEN);
    assertThat(fromEnv.token().source()).isEqualTo(TokenHolder.Source.ENVIRONMENT);
    assertThat(fromEnv.effective().source("server.token")).isEqualTo(Source.ENVIRONMENT);
    assertThat(fromEnv.warnings()).isEmpty();

    LoadedConfig fromFile =
        load(null, required("argos.server.token-file", path), map("ARGOS_TOKEN", TOKEN), false);
    assertThat(fromFile.token().reveal()).isEqualTo(OTHER_TOKEN); // read once, trimmed
    assertThat(fromFile.token().source()).isEqualTo(TokenHolder.Source.FILE);
    assertThat(fromFile.warnings()).isEmpty();
    assertThat(fromFile.notes())
        .containsExactly(
            "agent token taken from server.token-file; also configured and not used:"
                + " [ARGOS_TOKEN] (16 §2.6 order: server.token, server.token-file, ARGOS_TOKEN)");
    assertThat(fromFile.effective().value("server.token-file")).isEqualTo(path);

    File plain = yml("server:", "  token: " + TOKEN, "  token-file: " + path);
    LoadedConfig fromPlain = load(plain, required(), map("ARGOS_TOKEN", OTHER_TOKEN), false);
    assertThat(fromPlain.token().reveal()).isEqualTo(TOKEN);
    assertThat(fromPlain.notes()).hasSize(1);
    assertThat(fromPlain.notes().get(0)).contains("[server.token-file, ARGOS_TOKEN]");

    File placeholder = yml("server:", "  token: ${ARGOS_TOKEN}");
    LoadedConfig substituted = load(placeholder, required(), map("ARGOS_TOKEN", TOKEN), false);
    assertThat(substituted.token().reveal()).isEqualTo(TOKEN);
    assertThat(substituted.token().source()).isEqualTo(TokenHolder.Source.ENVIRONMENT);
    assertThat(substituted.warnings()).isEmpty(); // not a plaintext token
    assertThat(substituted.notes()).isEmpty();
    LoadedConfig unset = load(placeholder, required(), none(), false);
    assertThat(unset.token().isPresent()).isFalse();
    assertThat(unset.warnings())
        .containsExactly(
            "server.token (argos-agent.yml line 2) refers to ${ARGOS_TOKEN} but ARGOS_TOKEN is"
                + " not set; no token from it");

    LoadedConfig property = load(null, required("argos.server.token", TOKEN), none(), false);
    assertThat(property.token().source()).isEqualTo(TokenHolder.Source.PLAIN);
    assertThat(property.warnings())
        .containsExactly(
            "token is given in plaintext as -Dargos.server.token (visible in the process list);"
                + " prefer token-file or ARGOS_TOKEN");
    LoadedConfig serverTokenVariable =
        load(null, required(), map("ARGOS_SERVER_TOKEN", TOKEN), false);
    assertThat(serverTokenVariable.token().source()).isEqualTo(TokenHolder.Source.ENVIRONMENT);
    assertThat(serverTokenVariable.warnings()).isEmpty();
    for (LoadedConfig loaded :
        Arrays.asList(fromEnv, fromFile, fromPlain, substituted, property, serverTokenVariable)) {
      assertNoToken(loaded, TOKEN);
      assertNoToken(loaded, OTHER_TOKEN);
    }
  }

  @Test
  @Tag("req:SEC-06")
  @DisplayName("T-SEC-06a token-file: group/other readable WARN once; no POSIX = note, no WARN")
  void tokenFilePermissions() throws Exception {
    Path tokenFile = dir.resolve("token");
    Files.write(tokenFile, TOKEN.getBytes(StandardCharsets.UTF_8));
    Map<String, String> props = required("argos.server.token-file", tokenFile.toString());
    LoadedConfig readable = load(null, props, none(), TokenFile.Access.GROUP_OR_OTHERS);
    assertThat(readable.warnings())
        .containsExactly(
            "server.token-file "
                + tokenFile
                + " can be read by group or others; restrict it to the owner (chmod 600)");
    assertThat(readable.token().reveal()).isEqualTo(TOKEN);
    LoadedConfig unknown = load(null, props, none(), TokenFile.Access.UNKNOWN);
    assertThat(unknown.warnings()).isEmpty();
    assertThat(unknown.notes())
        .containsExactly(
            "server.token-file permissions were not checked: the file system has no POSIX"
                + " permissions");
    LoadedConfig owner = load(null, props, none(), TokenFile.Access.OWNER_ONLY);
    assertThat(owner.warnings()).isEmpty();
    assertThat(owner.notes()).isEmpty();
  }

  @Test
  @Tag("req:SEC-06")
  @DisplayName("T-SEC-06a token-file on a POSIX file system: 0644 warns, 0600 does not")
  void tokenFilePosix() throws Exception {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Path tokenFile = dir.resolve("token");
    Files.write(tokenFile, TOKEN.getBytes(StandardCharsets.UTF_8));
    Map<String, String> props = required("argos.server.token-file", tokenFile.toString());
    Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-r--r--"));
    assertThat(ConfigLoader.load(new ConfigInputs(null, props, none(), false)).warnings())
        .containsExactly(
            "server.token-file "
                + tokenFile
                + " can be read by group or others; restrict it to the owner (chmod 600)");
    Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-------"));
    LoadedConfig owner = ConfigLoader.load(new ConfigInputs(null, props, none(), false));
    assertThat(owner.warnings()).isEmpty();
    assertThat(owner.notes()).isEmpty();
  }

  @Test
  @Tag("req:SEC-06")
  @DisplayName("T-SEC-06a unusable token files: missing, empty, over 4 KiB; the next way is used")
  void unusableTokenFiles() throws Exception {
    Path empty = dir.resolve("empty");
    Files.write(empty, " \n".getBytes(StandardCharsets.UTF_8));
    Path big = dir.resolve("big");
    byte[] bytes = new byte[TokenFile.MAX_BYTES + 1];
    Arrays.fill(bytes, (byte) 'a');
    Files.write(big, bytes);
    Path missing = dir.resolve("missing");
    for (Path path : Arrays.asList(empty, big, missing)) {
      LoadedConfig loaded =
          load(
              null,
              required("argos.server.token-file", path.toString()),
              map("ARGOS_TOKEN", TOKEN),
              false);
      assertThat(loaded.token().reveal()).as(path.toString()).isEqualTo(TOKEN);
      assertThat(loaded.token().source()).isEqualTo(TokenHolder.Source.ENVIRONMENT);
      assertThat(loaded.warnings()).hasSize(1);
      assertThat(loaded.warnings().get(0)).startsWith("server.token-file " + path);
      assertNoToken(loaded, TOKEN);
    }
  }

  @Test
  @Tag("req:SEC-06")
  @DisplayName("T-SEC-06a a token in a wrong key or an invalid token never shows in a message")
  void tokenNeverInMessages() throws Exception {
    LoadedConfig misplaced =
        load(
            null,
            map(
                "argos.server.address", TOKEN,
                "argos.app.name", "a",
                "argos.app.env", "e"),
            none(),
            false);
    assertThat(misplaced.warnings()).hasSize(1);
    assertThat(misplaced.warnings().get(0))
        .startsWith("invalid value 'argos_****' for server.address");
    assertNoToken(misplaced, TOKEN);

    LoadedConfig invalid =
        load(null, required("argos.server.token", "argos_ with spaces"), none(), false);
    assertThat(invalid.token().isPresent()).isFalse();
    assertThat(invalid.warnings())
        .containsExactly(
            "invalid value (hidden) for server.token (-Dargos.server.token): empty or contains"
                + " whitespace");

    // A token of another shape (the seed ARGOS_TOKEN) put into another key: shown masked.
    String jwtLike = "eyJhbGciOi.secret-seed.sig";
    LoadedConfig env =
        load(null, required("argos.app.group", jwtLike), map("ARGOS_TOKEN", jwtLike), false);
    assertThat(env.config().appGroup()).isEqualTo(jwtLike);
    assertThat(env.effective().value("app.group")).isEqualTo("argos_****");
    for (String line : env.effective().lines()) {
      assertThat(line).doesNotContain(jwtLike);
    }
    assertThat(env.warnings()).isEmpty();
  }

  /** 17 characters: with a 16 §2.1 token (49) the value is 66 characters, over the 64 cut. */
  private static final String PREFIX = "timeout-for-node-";

  /** 60 characters: the cut falls inside the masked token. */
  private static final String LONG_PREFIX =
      "abcdefghij-abcdefghij-abcdefghij-abcdefghij-abcdefghij-abcde";

  /** A configured token that is not of the 16 §2.1 format (the argos_ rule cannot see it). */
  private static final String OTHER_SHAPE_TOKEN = "Zq7.not-of-the-argos-format.0123456789";

  /** Every message and effective value of {@code loaded}. */
  private static List<String> messages(LoadedConfig loaded) {
    List<String> all = new ArrayList<String>();
    all.addAll(loaded.warnings());
    all.addAll(loaded.errors());
    all.addAll(loaded.notes());
    all.addAll(loaded.effective().lines());
    return all;
  }

  /** No part of 8 or more characters of {@code secret} shows in any of {@code lines}. */
  static void assertNoPart(List<String> lines, String secret) {
    for (String line : lines) {
      for (int i = 0; i + 8 <= secret.length(); i++) {
        assertThat(line).as("part %d of the token", i).doesNotContain(secret.substring(i, i + 8));
      }
    }
  }

  @Test
  @Tag("req:SEC-06")
  @DisplayName(
      "T-SEC-06a a token in an integer key is masked before the 64-character cut (-D, yml,"
          + " ARGOS_*): argos_**** only, no part of the token")
  void tokenIsMaskedBeforeTheCut() throws Exception {
    String value = PREFIX + TOKEN;
    assertThat(value.length()).isGreaterThan(64);
    String connect = "server.connect-timeout-ms";
    String steps = "sampling.profile.max-steps";

    LoadedConfig property =
        load(
            null,
            required("argos." + connect, value, "argos." + steps, LONG_PREFIX + TOKEN),
            none(),
            false);
    LoadedConfig file =
        load(
            yml(
                "server:",
                "  connect-timeout-ms: " + value,
                "sampling.profile.max-steps: " + LONG_PREFIX + TOKEN),
            required(),
            none(),
            false);
    LoadedConfig env =
        load(
            null,
            required(),
            map(
                ConfigKeys.environmentName(connect),
                value,
                ConfigKeys.environmentName(steps),
                LONG_PREFIX + TOKEN),
            false);
    for (LoadedConfig loaded : Arrays.asList(property, file, env)) {
      List<String> all = messages(loaded);
      assertThat(loaded.warnings())
          .anySatisfy(
              w ->
                  assertThat(w)
                      .startsWith("invalid value '" + PREFIX + "argos_****' for " + connect))
          .anySatisfy(
              w -> assertThat(w).startsWith("invalid value '" + LONG_PREFIX.substring(0, 60)));
      assertNoPart(all, TOKEN);
      for (String line : all) {
        assertThat(line).doesNotContainPattern("argos_[A-Za-z0-9_-]");
      }
      assertThat(loaded.config().serverConnectTimeoutMs())
          .isEqualTo(AgentConfig.defaults().serverConnectTimeoutMs());
    }

    // The configured token of another shape: masked as a whole before the cut as well.
    String middle = "a-forty-character-prefix-before-a-secret";
    LoadedConfig configured =
        load(
            null,
            required("argos." + connect, middle + OTHER_SHAPE_TOKEN),
            map("ARGOS_TOKEN", OTHER_SHAPE_TOKEN),
            false);
    assertThat(configured.token().reveal()).isEqualTo(OTHER_SHAPE_TOKEN);
    assertThat(configured.warnings())
        .anySatisfy(w -> assertThat(w).startsWith("invalid value '" + middle + "argos_****' for "));
    assertNoPart(messages(configured), OTHER_SHAPE_TOKEN);
  }

  // --- masking pattern lists ------------------------------------------------------------------

  @Test
  @Tag("req:SEC-36")
  @DisplayName("mask patterns: an invalid or long pattern is skipped, the others stay; 32 at most")
  void maskPatternLists() throws Exception {
    File file =
        yml(
            "capture:",
            "  mask-patterns:",
            "    - \"ORD-\\\\d{6}\"",
            "    - \"(unclosed\"",
            "    - \"" + YamlSubsetTest.repeat('x', 513) + "\"",
            "    - 'cust-[0-9]+'");
    LoadedConfig loaded = load(file, required(), none(), false);
    assertThat(loaded.config().captureMaskPatterns()).containsExactly("ORD-\\d{6}", "cust-[0-9]+");
    assertThat(loaded.warnings())
        .containsExactly(
            "capture.mask-patterns pattern 2 (argos-agent.yml line 2) is not a valid regular"
                + " expression; skipped",
            "capture.mask-patterns pattern 3 (argos-agent.yml line 2) is longer than 512"
                + " characters; skipped");

    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      many.append(i == 0 ? "" : ",").append("p").append(i);
    }
    LoadedConfig capped =
        load(null, required("argos.capture.mask-patterns", many.toString()), none(), false);
    assertThat(capped.config().captureMaskPatterns()).hasSize(32).startsWith("p0").endsWith("p31");
    assertThat(capped.warnings())
        .containsExactly(
            "capture.mask-patterns (-Dargos.capture.mask-patterns) has more than 32 patterns; the"
                + " first 32 are used");
  }

  // --- inputs ---------------------------------------------------------------------------------

  @Test
  @DisplayName("yml location: -Dargos.config > ARGOS_CONFIG > config=<path> > next to the jar")
  void locateYaml() throws Exception {
    Path jarDir = Files.createDirectories(dir.resolve("agent"));
    File jar = jarDir.resolve("argos-agent.jar").toFile();
    List<String> warnings = new ArrayList<String>();
    assertThat(ConfigInputs.locateYaml(jar, null, none(), none(), warnings)).isNull();
    Files.write(jarDir.resolve("argos-agent.yml"), "a: b\n".getBytes(StandardCharsets.UTF_8));
    assertThat(ConfigInputs.locateYaml(jar, "", none(), none(), warnings))
        .isEqualTo(jarDir.resolve("argos-agent.yml").toFile().getAbsoluteFile());
    assertThat(ConfigInputs.locateYaml(jar, "config=/etc/a.yml", none(), none(), warnings))
        .isEqualTo(new File("/etc/a.yml"));
    assertThat(
            ConfigInputs.locateYaml(
                jar, "config=/etc/a.yml", none(), map("ARGOS_CONFIG", "/etc/b.yml"), warnings))
        .isEqualTo(new File("/etc/b.yml"));
    assertThat(
            ConfigInputs.locateYaml(
                jar,
                "config=/etc/a.yml",
                map("argos.config", "/etc/c.yml"),
                map("ARGOS_CONFIG", "/etc/b.yml"),
                warnings))
        .isEqualTo(new File("/etc/c.yml"));
    assertThat(warnings).isEmpty();
    ConfigInputs.locateYaml(jar, "debug=true", none(), none(), warnings);
    assertThat(warnings)
        .containsExactly(
            "agent arguments other than 'config=<path>' after '-javaagent:...=' are ignored;"
                + " configure with argos-agent.yml, -Dargos.<key> or ARGOS_<KEY>");

    ConfigInputs inputs =
        new ConfigInputs(
            null, required(), none(), false, null, Arrays.asList("w1", "w2", null, "w3"));
    assertThat(ConfigLoader.load(inputs).warnings()).containsExactly("w1", "w2", "w3");
    List<String> many = new ArrayList<String>();
    for (int i = 0; i < 20; i++) {
      many.add("w" + i);
    }
    assertThat(new ConfigInputs(null, none(), none(), false, null, many).inputWarnings())
        .hasSize(ConfigInputs.MAX_INPUT_WARNINGS);
  }

  @Test
  @DisplayName("every registry key has its own ARGOS_ variable (no two keys share one)")
  void environmentNamesAreUnique() {
    Map<String, String> seen = new HashMap<String, String>();
    for (KeySpec spec : ConfigKeys.all()) {
      if (!spec.isFamily()) {
        String previous = seen.put(ConfigKeys.environmentName(spec.name()), spec.name());
        assertThat(previous).as(spec.name()).isNull();
      }
    }
    assertThat(ConfigLoader.environmentIndex()).hasSize(seen.size());
    assertThat(seen).doesNotContainKeys(ConfigKeys.TOKEN_ENVIRONMENT, "ARGOS_CONFIG");
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName("hostile inputs never throw: control characters, huge values, odd names")
  void neverThrows() throws Exception {
    Map<String, String> props = new LinkedHashMap<String, String>();
    props.put("argos.", "x");
    props.put("argos.app.name", "a\u0000b");
    props.put("argos.app.env", YamlSubsetTest.repeat('e', KeySpec.MAX_RAW_LENGTH + 10));
    props.put("argos.capture.mask-patterns", "[\"unclosed");
    props.put("argos.sampling.profile.slow-threshold-ms.services.", "5");
    Map<String, String> env = new LinkedHashMap<String, String>();
    env.put("ARGOS_", "x");
    env.put("ARGOS_TOKEN", "  ");
    LoadedConfig loaded = load(yml("\u0000: x"), props, env, false);
    assertThat(loaded.config()).isNotNull();
    assertThat(loaded.token().isPresent()).isFalse();
    assertThat(loaded.warnings()).isNotEmpty();
    for (String warning : loaded.warnings()) {
      for (int i = 0; i < warning.length(); i++) {
        assertThat(Character.isISOControl(warning.charAt(i))).as(warning).isFalse();
      }
      assertThat(warning.length()).isLessThan(1024);
    }
  }
}
