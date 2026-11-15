/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.CompositeTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The real Log4j configuration of the server ({@code log4j2-spring.xml}, {@code
 * argos-server-log.json}, {@code log4j2.component.properties}) in its own logger context: JSON
 * Lines with the fields of 17 §11.3, rolling 100 MB x 10, and masking of messages and stack traces.
 */
@Tag("req:SEC-06")
@Tag("req:F-S20")
class Log4jConfigurationTest {

  @TempDir Path dir;

  private LoggerContext context;
  private String previousLogPath;

  @BeforeEach
  void start() throws Exception {
    previousLogPath = System.getProperty("LOG_PATH");
    System.setProperty("LOG_PATH", dir.toString());
    context = new LoggerContext("argos-log-test");
    URI config = getClass().getClassLoader().getResource("log4j2-spring.xml").toURI();
    Configuration configuration =
        ConfigurationFactory.getInstance().getConfiguration(context, "argos-log-test", config);
    context.start(configuration);
  }

  @AfterEach
  void stop() {
    context.stop();
    if (previousLogPath == null) {
      System.clearProperty("LOG_PATH");
    } else {
      System.setProperty("LOG_PATH", previousLogPath);
    }
  }

  private List<JsonNode> lines() throws Exception {
    context.stop();
    ObjectMapper json = new ObjectMapper();
    return Files.readAllLines(dir.resolve("argos-server.log"), StandardCharsets.UTF_8).stream()
        .map(json::readTree)
        .toList();
  }

  @Test
  @DisplayName("events are created by the masking factory (log4j2.component.properties)")
  void factoryInstalled() {
    assertThat(context.getConfiguration().getRootLogger().getLogEventFactory())
        .isInstanceOf(MaskingLogEventFactory.class);
  }

  @Test
  @DisplayName("JSON Lines with ts, level, logger, thread, request_id and message")
  void fields() throws Exception {
    Logger log = context.getLogger("io.argos.server.test");
    ThreadContext.put("request_id", "01JTESTREQUEST0000000000000");
    try {
      log.info("first {}", 1);
    } finally {
      ThreadContext.remove("request_id");
    }
    log.warn("second");

    List<JsonNode> lines = lines();
    assertThat(lines).hasSize(2);
    JsonNode first = lines.get(0);
    assertThat(first.get("ts").asString())
        .matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z");
    assertThat(first.get("level").asString()).isEqualTo("INFO");
    assertThat(first.get("logger").asString()).isEqualTo("io.argos.server.test");
    assertThat(first.get("thread").asString()).isEqualTo(Thread.currentThread().getName());
    assertThat(first.get("request_id").asString()).isEqualTo("01JTESTREQUEST0000000000000");
    assertThat(first.get("message").asString()).isEqualTo("first 1");
    assertThat(first.has("error")).isFalse();
    assertThat(lines.get(1).has("request_id")).isFalse();
    assertThat(lines.get(1).get("level").asString()).isEqualTo("WARN");
  }

  @Test
  @DisplayName("secrets in messages and stack traces are masked before they reach the file")
  void masking() throws Exception {
    String token = LogMaskingTest.agentToken();
    String secret = LogMaskingTest.secret();
    Logger log = context.getLogger("io.argos.server.test");
    log.info("connect jdbc:postgresql://db/argos?password={} with {}", secret, token);
    log.error(
        "request failed",
        new IllegalStateException(
            "Authorization: Bearer " + secret, new RuntimeException("password=" + secret)));
    // A header map as HttpHeaders and Map<String, List<String>> print it.
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("Authorization", List.of("Bearer " + secret));
    headers.put("Accept", List.of("*/*"));
    log.info("request headers {}", headers);

    List<JsonNode> lines = lines();
    String raw = Files.readString(dir.resolve("argos-server.log"), StandardCharsets.UTF_8);
    assertThat(raw).doesNotContain(secret).doesNotContain(token.substring(6));
    assertThat(lines.get(0).get("message").asString())
        .isEqualTo("connect jdbc:postgresql://db/argos?password=**** with argos_****");
    JsonNode error = lines.get(1).get("error");
    assertThat(error.get("class").asString()).isEqualTo("java.lang.IllegalStateException");
    assertThat(error.get("stack").asString())
        .startsWith("java.lang.IllegalStateException: Authorization: Bearer ****\n\tat ")
        .contains("Caused by: java.lang.RuntimeException: password=****");
    assertThat(lines.get(1).get("message").asString()).isEqualTo("request failed");
    assertThat(lines.get(2).get("message").asString())
        .isEqualTo("request headers {Authorization=[Bearer ****], Accept=[*/*]}");
  }

  @Test
  @DisplayName("the file rolls at 100 MB and keeps ten files (17 §11.3)")
  void rolling() {
    RollingFileAppender file = context.getConfiguration().getAppender("file");
    assertThat(Path.of(file.getFileName())).isEqualTo(dir.resolve("argos-server.log"));
    assertThat(file.getFilePattern()).endsWith("argos-server.log.%i");
    CompositeTriggeringPolicy policies = file.getTriggeringPolicy();
    assertThat(policies.getTriggeringPolicies())
        .singleElement()
        .isInstanceOfSatisfying(
            SizeBasedTriggeringPolicy.class,
            size -> assertThat(size.getMaxFileSize()).isEqualTo(100L * 1024 * 1024));
    DefaultRolloverStrategy strategy =
        (DefaultRolloverStrategy) file.getManager().getRolloverStrategy();
    assertThat(strategy.getMaxIndex() - strategy.getMinIndex() + 1 + 1).isEqualTo(10);
    Appender console = context.getConfiguration().getAppender("console");
    assertThat(console).isNotNull();
  }
}
