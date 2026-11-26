/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-BLD-015g: the OTLP black-hole receiver of the OpenTelemetry reference variant (01 §7.3 item 11,
 * 18 §7.5) with real HTTP requests, and the pinned OpenTelemetry Java agent ({@code --otel-agent
 * auto}, fetch-otel-agent.sh; never downloaded during a run).
 */
@Tag("req:DSN-18-07")
@Tag("req:N-01")
class OtlpBlackHoleTest {

  private static HttpResponse<byte[]> send(HttpClient client, String method, String url, int size)
      throws IOException, InterruptedException {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-protobuf");
    if (method.equals("POST")) {
      b.POST(HttpRequest.BodyPublishers.ofByteArray(new byte[size]));
    } else {
      b.GET();
    }
    return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  @Test
  @DisplayName(
      "T-BLD-015g POST /v1/traces|metrics|logs on 127.0.0.1: 200, empty protobuf, counted per path")
  void answersAndCounts() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    try (OtlpBlackHole hole = OtlpBlackHole.start()) {
      assertThat(hole.address().getAddress().isLoopbackAddress()).isTrue();
      assertThat(hole.port()).isPositive();
      assertThat(hole.endpoint()).isEqualTo("http://127.0.0.1:" + hole.port());
      Map<String, OtlpBlackHole.Counts> start = hole.snapshot();

      int[] sizes = {1000, 250, 0};
      for (int i = 0; i < OtlpBlackHole.PATHS.size(); i++) {
        HttpResponse<byte[]> r =
            send(client, "POST", hole.endpoint() + OtlpBlackHole.PATHS.get(i), sizes[i]);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValue("application/x-protobuf");
        assertThat(r.body()).isEmpty();
      }
      send(client, "POST", hole.endpoint() + "/v1/traces", 4096);

      assertThat(hole.of("/v1/traces")).isEqualTo(new OtlpBlackHole.Counts(2, 5096));
      assertThat(hole.of("/v1/metrics")).isEqualTo(new OtlpBlackHole.Counts(1, 250));
      assertThat(hole.of("/v1/logs")).isEqualTo(new OtlpBlackHole.Counts(1, 0));
      assertThat(hole.total()).isEqualTo(new OtlpBlackHole.Counts(4, 5346));

      // Not OTLP: refused and not counted.
      assertThat(send(client, "GET", hole.endpoint() + "/v1/traces", 0).statusCode())
          .isEqualTo(405);
      assertThat(send(client, "POST", hole.endpoint() + "/v1/profiles", 10).statusCode())
          .isEqualTo(404);
      assertThat(hole.rejected()).isEqualTo(2);
      assertThat(hole.total().requests()).isEqualTo(4);

      Map<String, Object> window = OtlpBlackHole.window(start, hole.snapshot());
      assertThat(window).containsEntry("requests", 4L).containsEntry("bytes", 5346L);
      assertThat(Json.object(window.get("requestsBySignal")))
          .containsEntry("traces", 2L)
          .containsEntry("metrics", 1L)
          .containsEntry("logs", 1L);
    }
  }

  @Test
  @DisplayName("T-BLD-015g the otel variant exports OTLP http/protobuf to the receiver, logs none")
  void otelVariantOptions() {
    assertThat(OtlpBlackHole.otelOptions("http://127.0.0.1:4711"))
        .containsExactly(
            "-Dotel.exporter.otlp.protocol=http/protobuf",
            "-Dotel.exporter.otlp.endpoint=http://127.0.0.1:4711",
            "-Dotel.traces.exporter=otlp",
            "-Dotel.metrics.exporter=otlp",
            "-Dotel.logs.exporter=none");
    // No receiver (container mode): exporters none, the WO-05 setting.
    assertThat(OtlpBlackHole.otelOptions(null))
        .containsExactly(
            "-Dotel.traces.exporter=none",
            "-Dotel.metrics.exporter=none",
            "-Dotel.logs.exporter=none");
    Platform.Setup setup = SelfDiagFileTest.setup(true, "http://127.0.0.1:4711");
    assertThat(
            Platform.variantOptions(
                "otel", setup, "shop-boot3", "/a.jar", "/otel.jar", "/l", "/r/selfdiag.json"))
        .startsWith("-javaagent:/otel.jar")
        .contains("-Dotel.exporter.otlp.endpoint=http://127.0.0.1:4711")
        .noneMatch(o -> o.startsWith("-Dargos."));
  }

  @Test
  @DisplayName(
      "T-BLD-015g --otel-agent auto resolves only the fetched, SHA-256-checked jar; never downloads")
  void otelAgentAuto(@TempDir Path repo) throws IOException {
    assertThatThrownBy(() -> OtlpBlackHole.resolveOtelAgent("auto", repo))
        .isInstanceOf(UsageException.class)
        .hasMessageContaining("test/bench/build/otel/opentelemetry-javaagent-2.32.0.jar")
        .hasMessageContaining("run test/bench/fetch-otel-agent.sh first");
    Path jar =
        Files.createDirectories(repo.resolve("test/bench/build/otel"))
            .resolve("opentelemetry-javaagent-2.32.0.jar");
    Files.writeString(jar, "not the agent", StandardCharsets.UTF_8);
    assertThatThrownBy(() -> OtlpBlackHole.resolveOtelAgent("auto", repo))
        .isInstanceOf(UsageException.class)
        .hasMessageContaining("expected " + OtlpBlackHole.OTEL_AGENT_SHA256);
    // An explicit path is used as given (its SHA-256 goes to the set's inputs).
    assertThat(OtlpBlackHole.resolveOtelAgent(jar.toString(), repo)).isEqualTo(jar);
  }

  @Test
  @DisplayName("T-BLD-015g fetch-otel-agent.sh pins the same version and SHA-256 as the harness")
  void fetchScriptPinsTheSameJar() throws IOException {
    Path root =
        Path.of(System.getProperty("argos.repositoryRoot", "../..")).toAbsolutePath().normalize();
    String script =
        Files.readString(root.resolve("test/bench/fetch-otel-agent.sh"), StandardCharsets.UTF_8);
    assertThat(script)
        .startsWith("#!/usr/bin/env bash\n# SPDX-License-Identifier: Apache-2.0\n")
        .contains("readonly VERSION=\"" + OtlpBlackHole.OTEL_AGENT_VERSION + "\"")
        .contains("readonly SHA256=\"" + OtlpBlackHole.OTEL_AGENT_SHA256 + "\"")
        .contains("io/opentelemetry/javaagent/opentelemetry-javaagent")
        .contains(OtlpBlackHole.OTEL_AGENT_DIR.substring("test/bench/".length()));
    assertThat(List.of(OtlpBlackHole.OTEL_AGENT_SHA256)).allMatch(s -> s.matches("[0-9a-f]{64}"));
  }
}
