/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.agent.bootstrap.CaptureConfig;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The typed configuration snapshot (10 §15, 02 §7). */
class AgentConfigTest {
  private static final String TOKEN = "argos_" + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";

  @Test
  @DisplayName("defaults: every typed getter answers without a value set")
  void defaults() throws Exception {
    AgentConfig config = AgentConfig.defaults();
    int getters = 0;
    for (Method method : AgentConfig.class.getDeclaredMethods()) {
      if (Modifier.isPublic(method.getModifiers())
          && !Modifier.isStatic(method.getModifiers())
          && method.getParameterTypes().length == 0) {
        method.invoke(config);
        getters++;
      }
    }
    assertThat(getters).isGreaterThan(80);
    assertThat(config.serverAddress()).isNull();
    assertThat(config.serverTls()).isTrue();
    assertThat(config.appEnv()).isEqualTo("default");
    assertThat(config.enabled()).isTrue();
    assertThat(config.normalRate()).isEqualTo(0.1);
    assertThat(config.maxSteps()).isEqualTo(1000);
    assertThat(config.bufferMaxBytes()).isEqualTo(32L * 1024 * 1024);
    assertThat(config.bufferMaxAgeSeconds()).isEqualTo(120);
    assertThat(config.senderCompression()).isEqualTo("gzip");
    assertThat(config.logLevel()).isEqualTo("INFO");
    assertThat(config.remoteConfigAllow())
        .containsExactly(
            "sampling.**",
            "instrumentation.disabled",
            "instrumentation.ignore-paths",
            "log.level",
            "enabled");
    assertThat(config.instrumentationAppPackages()).isEmpty();
    assertThat(config.serverToken().isPresent()).isFalse();
    assertThat(config.missingRequired()).containsExactly("server.address", "app.name");
    assertThat(config.warnings()).isEmpty();
    assertThat(config.explicitValues()).isEmpty();
    assertThat(config.capture().remoteIpMode()).isEqualTo(CaptureConfig.REMOTE_IP_MASKED);
  }

  @Test
  @DisplayName("explicit values are parsed to their types; the argos. prefix is accepted")
  void typedValues() {
    Map<String, String> values = new LinkedHashMap<String, String>();
    values.put("server.address", "collector:9100");
    values.put("argos.app.name", "shop");
    values.put("enabled", "FALSE");
    values.put("buffer.max-bytes", "16MB");
    values.put("sampling.profile.normal-rate", "0.5");
    values.put("instrumentation.disabled", "jdbc, servlet ,");
    values.put("capture.mask-patterns", "[\"\\\\d{3,4}\", 'x']");
    values.put("log.level", "debug");
    AgentConfig config = AgentConfig.of(values);
    assertThat(config.serverAddress()).isEqualTo("collector:9100");
    assertThat(config.appName()).isEqualTo("shop");
    assertThat(config.enabled()).isFalse();
    assertThat(config.bufferMaxBytes()).isEqualTo(16L * 1024 * 1024);
    assertThat(config.normalRate()).isEqualTo(0.5);
    assertThat(config.instrumentationDisabled()).containsExactly("jdbc", "servlet");
    assertThat(config.captureMaskPatterns()).containsExactly("\\d{3,4}", "x");
    assertThat(config.logLevel()).isEqualTo("DEBUG");
    assertThat(config.isSet("app.name")).isTrue();
    assertThat(config.isSet("log.dir")).isFalse();
    assertThat(config.missingRequired()).isEmpty();
    assertThat(config.warnings()).isEmpty();
    assertThatThrownBy(() -> config.getInt("no.such.key"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> config.getInt("log.level"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("unknown keys and invalid values are reported; invalid values keep the default")
  void warnings() {
    Map<String, String> values = new LinkedHashMap<String, String>();
    values.put("no.such.key", "1");
    values.put("log.max-files", "many");
    values.put("enabled", "maybe");
    values.put("sampling.profile.max-steps", "0");
    values.put("capture.remote-ip", "partial");
    AgentConfig config = AgentConfig.of(values);
    assertThat(config.logMaxFiles()).isEqualTo(5);
    assertThat(config.enabled()).isTrue();
    assertThat(config.maxSteps()).isEqualTo(1000);
    assertThat(config.captureRemoteIp()).isEqualTo("masked");
    assertThat(config.warnings())
        .hasSize(5)
        .anySatisfy(w -> assertThat(w).contains("unknown configuration key 'no.such.key'"))
        .anySatisfy(w -> assertThat(w).contains("invalid value 'many' for log.max-files"))
        .anySatisfy(w -> assertThat(w).contains("invalid value 'maybe' for enabled"))
        .anySatisfy(w -> assertThat(w).contains("invalid value '0' for sampling.profile.max-steps"))
        .anySatisfy(w -> assertThat(w).contains("for capture.remote-ip"));
  }

  @Test
  @DisplayName("warnings are capped at 100; a long value is quoted shortened")
  void warningCap() {
    Map<String, String> values = new LinkedHashMap<String, String>();
    for (int i = 0; i < 150; i++) {
      values.put("unknown.key" + i, "v");
    }
    AgentConfig config = AgentConfig.of(values);
    assertThat(config.warnings()).hasSize(AgentConfig.MAX_WARNINGS);
    char[] longValue = new char[500];
    Arrays.fill(longValue, 'z');
    AgentConfig one =
        AgentConfig.of(Collections.singletonMap("log.max-files", new String(longValue)));
    assertThat(one.warnings().get(0)).hasSizeLessThan(200);
  }

  @Test
  @DisplayName("an empty scalar means unset; an empty list disables a list rule")
  void emptyValues() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("log.dir", "");
    values.put("log.max-files", " ");
    values.put("instrumentation.ignore-paths", "");
    AgentConfig config = AgentConfig.of(values);
    assertThat(config.logDir()).isNull();
    assertThat(config.logMaxFiles()).isEqualTo(5);
    assertThat(config.isSet("log.dir")).isFalse();
    assertThat(config.instrumentationIgnorePaths()).isEmpty();
    assertThat(config.isSet("instrumentation.ignore-paths")).isTrue();
    assertThat(config.warnings()).isEmpty();
  }

  @Test
  @Tag("req:SEC-06")
  @DisplayName("SEC-06: the token is kept as a TokenHolder, never in values, warnings or toString")
  void tokenIsNeverExposed() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("server.token", TOKEN);
    values.put("app.name", "shop");
    AgentConfig config = AgentConfig.of(values);
    assertThat(config.serverToken().isPresent()).isTrue();
    assertThat(config.serverToken().reveal()).isEqualTo(TOKEN);
    assertThat(config.serverToken().source()).isEqualTo(TokenHolder.Source.PLAIN);
    assertThat(config.explicitValues()).doesNotContainKey("server.token");
    assertThat(config.explicitValues().values()).doesNotContain(TOKEN);
    assertThat(config.getString("server.token")).isNull();
    assertThat(config.isSet("server.token")).isTrue();
    assertThat(config.toString()).doesNotContain(TOKEN).contains(TokenHolder.MASK);

    AgentConfig invalid =
        AgentConfig.of(Collections.singletonMap("server.token", "argos_secret with space"));
    assertThat(invalid.serverToken().isPresent()).isFalse();
    assertThat(invalid.warnings())
        .singleElement()
        .asString()
        .contains("(hidden)")
        .doesNotContain("secret with space");

    AgentConfig changed = config.withValues(Collections.singletonMap("log.level", "WARN"));
    assertThat(changed.logLevel()).isEqualTo("WARN");
    assertThat(changed.appName()).isEqualTo("shop");
    assertThat(changed.serverToken().reveal()).isEqualTo(TOKEN);
    assertThat(config.logLevel()).isEqualTo("INFO");
  }

  @Test
  @Tag("req:SEC-06")
  @DisplayName(
      "T-SEC-06a withValues (remote push, reload): a token in an integer key is masked before the"
          + " 64-character cut, also the token kept from the snapshot")
  void tokenIsMaskedBeforeTheCutOnChanges() {
    String prefix = "timeout-for-node-"; // 17 + 49 = 66 characters
    String longPrefix = "abcdefghij-abcdefghij-abcdefghij-abcdefghij-abcdefghij-abcde"; // 60
    Map<String, String> overrides = new LinkedHashMap<String, String>();
    overrides.put("server.connect-timeout-ms", prefix + TOKEN);
    overrides.put("sampling.profile.max-steps", longPrefix + TOKEN);
    AgentConfig changed = AgentConfig.defaults().withValues(overrides);
    assertThat(changed.warnings())
        .hasSize(2)
        .anySatisfy(
            w ->
                assertThat(w)
                    .startsWith(
                        "invalid value '" + prefix + "argos_****' for server.connect-timeout-ms"));
    ConfigLoaderTest.assertNoPart(changed.warnings(), TOKEN);
    for (String warning : changed.warnings()) {
      assertThat(warning).doesNotContainPattern("argos_[A-Za-z0-9_-]");
    }

    // The token of the snapshot (another shape than 16 §2.1) is masked in a later change too.
    String secret = "Zq7.not-of-the-argos-format.0123456789";
    String middle = "a-forty-character-prefix-before-a-secret";
    Map<String, String> values = new HashMap<String, String>();
    values.put("server.token", secret);
    AgentConfig withToken = AgentConfig.of(values);
    AgentConfig pushed =
        withToken.withValues(
            Collections.singletonMap("server.connect-timeout-ms", middle + secret));
    assertThat(pushed.serverToken().reveal()).isEqualTo(secret);
    assertThat(pushed.warnings())
        .singleElement()
        .asString()
        .startsWith("invalid value '" + middle + "argos_****' for server.connect-timeout-ms");
    ConfigLoaderTest.assertNoPart(pushed.warnings(), secret);
    // and a token given in the same map as the bad value
    Map<String, String> both = new LinkedHashMap<String, String>();
    both.put("server.connect-timeout-ms", middle + secret);
    both.put("server.token", secret);
    ConfigLoaderTest.assertNoPart(AgentConfig.of(both).warnings(), secret);
  }

  @Test
  @DisplayName("per-service slow thresholds: own value, default otherwise, at most 1,000 services")
  void serviceThresholds() {
    Map<String, String> values = new LinkedHashMap<String, String>();
    values.put("sampling.profile.slow-threshold-ms", "2000");
    values.put("sampling.profile.slow-threshold-ms.services.GET /api/orders/{id}", "500");
    AgentConfig config = AgentConfig.of(values);
    assertThat(config.slowThresholdMs("GET /api/orders/{id}")).isEqualTo(500);
    assertThat(config.slowThresholdMs("GET /other")).isEqualTo(2000);
    assertThat(config.slowThresholdMs(null)).isEqualTo(2000);
    assertThat(config.value("sampling.profile.slow-threshold-ms.services.GET /api/orders/{id}"))
        .isEqualTo(500);

    Map<String, String> many = new LinkedHashMap<String, String>();
    for (int i = 0; i < AgentConfig.MAX_SERVICE_THRESHOLDS + 5; i++) {
      many.put("sampling.profile.slow-threshold-ms.services.GET /s" + i, "100");
    }
    AgentConfig capped = AgentConfig.of(many);
    assertThat(capped.slowThresholdServices()).hasSize(AgentConfig.MAX_SERVICE_THRESHOLDS);
    assertThat(capped.warnings()).hasSize(5);
  }

  @Test
  @Tag("req:SEC-31")
  @DisplayName("capture(): every capture key and async.step reach the bootstrap snapshot")
  void captureSnapshot() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("capture.query-string", "whitelist");
    values.put("capture.query-string.whitelist", "page, size");
    values.put("capture.http-headers", "X-Request-Id");
    values.put("capture.remote-ip", "off");
    values.put("capture.trusted-proxies", "10.0.0.0/8");
    values.put("capture.user-id.mode", "hashed");
    values.put("capture.user-id.source", "header:x-user");
    values.put("capture.sql-parameters", "partial");
    values.put("capture.sql-parameters.whitelist-columns", "status");
    values.put("capture.error-message-max-length", "200");
    values.put("capture.stack-depth", "12");
    values.put("async.step", "true");
    CaptureConfig capture = AgentConfig.of(values).capture();
    assertThat(capture.queryStringMode()).isEqualTo(CaptureConfig.QUERY_STRING_WHITELIST);
    assertThat(capture.queryStringWhitelist()).containsExactly("page", "size");
    assertThat(capture.headerWhitelist()).containsExactly("x-request-id");
    assertThat(capture.remoteIpMode()).isEqualTo(CaptureConfig.REMOTE_IP_OFF);
    assertThat(capture.trustedProxies()).containsExactly("10.0.0.0/8");
    assertThat(capture.userIdMode()).isEqualTo(CaptureConfig.USER_ID_HASHED);
    assertThat(capture.userIdSource()).isEqualTo("header:x-user");
    assertThat(capture.userIdMaxLength()).isEqualTo(CaptureConfig.USER_ID_MAX_LENGTH);
    assertThat(capture.sqlParamMode()).isEqualTo(CaptureConfig.SQL_PARAMS_PARTIAL);
    assertThat(capture.sqlParamWhitelistColumns()).containsExactly("status");
    assertThat(capture.errorMessageMax()).isEqualTo(200);
    assertThat(capture.stackDepth()).isEqualTo(12);
    assertThat(capture.asyncStep()).isTrue();

    values.put("capture.query-string", "full");
    values.put("capture.remote-ip", "full");
    values.put("capture.user-id.mode", "plain");
    values.put("capture.sql-parameters", "full");
    CaptureConfig full = AgentConfig.of(values).capture();
    assertThat(full.queryStringMode()).isEqualTo(CaptureConfig.QUERY_STRING_FULL);
    assertThat(full.remoteIpMode()).isEqualTo(CaptureConfig.REMOTE_IP_FULL);
    assertThat(full.userIdMode()).isEqualTo(CaptureConfig.USER_ID_PLAIN);
    assertThat(full.sqlParamMode()).isEqualTo(CaptureConfig.SQL_PARAMS_FULL);
  }
}
