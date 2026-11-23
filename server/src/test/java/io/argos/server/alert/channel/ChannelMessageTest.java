/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.alert.api.AlertNotification;
import io.argos.server.alert.api.AlertNotification.Group;
import io.argos.server.alert.api.AlertNotification.Item;
import io.argos.server.alert.api.AlertNotification.Kind;
import io.argos.server.alert.api.AlertNotification.Recipients;
import io.argos.server.alert.api.AlertNotification.Severity;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("req:SEC-39")
@Tag("req:O-R03")
class ChannelMessageTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  static AlertNotification sample() {
    Map<String, String> variables = new LinkedHashMap<>();
    variables.put("app", "shop");
    variables.put("env", "prod");
    variables.put("instance", "web-01:8080");
    variables.put("service", "GET /orders");
    variables.put("host", "payments.internal");
    variables.put("metric", "response_time_p95");
    variables.put("value", "12000");
    variables.put("threshold", "10000");
    variables.put("severity", "critical");
    variables.put("duration", "5m");
    variables.put("started_at", "2026-11-23 10:02:03 +09:00");
    variables.put(
        "link",
        "https://argos.example.test/apps/3/xlog?from=2026-11-23T00:47:03Z&to=2026-11-23T01:17:03Z");
    variables.put("runbook", "https://wiki.example.test/runbook/p95");
    variables.put("rule", "p95 latency");
    Item item =
        new Item(
            UUID.fromString("6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b"),
            UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d"),
            Kind.FIRING,
            Severity.CRITICAL,
            Instant.parse("2026-11-23T01:02:03.456789Z"),
            variables,
            "shop/prod GET /orders p95 12000ms >= 10000ms for 5m");
    return new AlertNotification(
        new Group(3, "shop", "prod", Severity.CRITICAL),
        Kind.FIRING,
        List.of(item),
        2,
        "shop/prod: p95 latency critical",
        "shop/prod GET /orders p95 12000ms >= 10000ms for 5m",
        Recipients.of(List.of(UUID.randomUUID())),
        List.of(item.eventId()));
  }

  @Test
  @DisplayName(
      "T-API-008a the webhook payload is schema_version 1 with the snapshot's fields and only the"
          + " allowed template variables")
  void payloadSnapshot() throws Exception {
    JsonNode actual = JSON.readTree(ChannelMessage.of(sample()).webhookJson());
    JsonNode expected;
    try (InputStream in = ChannelMessageTest.class.getResourceAsStream("webhook-payload-v1.json")) {
      expected = JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    assertThat(actual).isEqualTo(expected);
    assertThat(actual.path("schema_version").asInt()).isEqualTo(ChannelMessage.SCHEMA_VERSION);
    List<String> names = new java.util.ArrayList<>();
    actual.path("alert").path("items").get(0).path("variables").propertyNames().forEach(names::add);
    assertThat(AlertNotification.VARIABLES).containsAll(names);
  }

  @Test
  @DisplayName("T-API-008b a test send has the same shape with kind test and no items")
  void testPayload() {
    JsonNode node =
        JSON.readTree(ChannelMessage.test("ops", "https://argos.example.test").webhookJson());
    assertThat(node.path("schema_version").asInt()).isEqualTo(1);
    assertThat(node.path("alert").path("kind").asString()).isEqualTo("test");
    assertThat(node.path("alert").path("items").size()).isZero();
    assertThat(node.path("link").asString()).isEqualTo("https://argos.example.test");
  }

  @Test
  @DisplayName(
      "Slack text escapes &, < and > and links the deep link; the mail subject is one line")
  void slackAndMail() {
    ChannelMessage m =
        new ChannelMessage(
            "firing",
            "warn",
            1,
            "a<b>",
            null,
            "t & <x>\r\nBcc: evil@example.com",
            "body <@channel>",
            List.of(),
            0,
            "https://argos.example.test/apps/1");
    String slack = JSON.readTree(m.slackJson()).path("text").asString();
    assertThat(slack)
        .contains("t &amp; &lt;x&gt;")
        .contains("body &lt;@channel&gt;")
        .contains("<https://argos.example.test/apps/1|Open in Argos>");
    assertThat(m.mailSubject()).doesNotContain("\r").doesNotContain("\n").startsWith("[Argos] ");
    assertThat(SmtpClient.encodeHeader("p95 지연")).startsWith("=?UTF-8?B?").endsWith("?=");
  }
}
