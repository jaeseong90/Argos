/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.tls;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.HostConfig;
import io.argos.server.security.SecuritySuiteAccess;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ArgosTestServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-SEC-07c (ST-07-1, SEC-07, 16 §3.2): testssl.sh scans the gRPC collector's TLS port of the
 * security suite's server from a container (the image pinned by digest; GPL-2.0-only, a
 * development-only tool run as a separate container, docs/licenses/dev-only.md). Expected: SSLv2,
 * SSLv3, TLS 1.0 and TLS 1.1 not offered; TLS 1.2 and TLS 1.3 offered; every offered cipher suite
 * an AEAD suite (AES-GCM or ChaCha20-Poly1305) with forward secrecy: no CBC suite and no RSA key
 * exchange. The web listener has no TLS of its own (the UI and REST API sit behind the operator's
 * reverse proxy, 17 §3): there is nothing else to scan. The JSON result is kept as {@code
 * server/build/reports/security/testssl-collector.json}.
 *
 * <p>Docker Desktop reaches the host's loopback listener through {@code host.docker.internal}
 * ({@code host-gateway}); a Linux Docker Engine runs the scanner in the host network namespace and
 * scans {@code 127.0.0.1}. The security suite requires Docker: without it this test fails.
 */
@Tag("req:SEC-07")
class TestsslSecurityTest {

  /** testssl.sh 3.2 (GPL-2.0-only), the multi-architecture index pinned by digest. */
  static final String IMAGE =
      "ghcr.io/testssl/testssl.sh:3.2"
          + "@sha256:c24449ff42391fede3aa4d6f24cb00bfdbd53784625750f03fc20c7fcbc63711";

  /** Memory limit of the scanner container. */
  static final long MEMORY_BYTES = 512L * 1024 * 1024;

  /** TLS 1.2 and 1.3 AEAD suites with forward secrecy (16 §3.2). */
  static final Pattern AEAD_SUITE =
      Pattern.compile(
          "TLS_AES_(128|256)_GCM_SHA(256|384)|TLS_CHACHA20_POLY1305_SHA256"
              + "|TLS_ECDHE_(ECDSA|RSA)_WITH_(AES_(128|256)_GCM_SHA(256|384)|CHACHA20_POLY1305_SHA256)");

  private static final Pattern IANA_NAME = Pattern.compile("\\bTLS_[A-Z0-9_]+\\b");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  @DisplayName(
      "T-SEC-07c (ST-07-1) testssl.sh on the collector TLS port: no SSLv3, TLS 1.0 or 1.1; TLS 1.2"
          + " and 1.3 offered; only AEAD suites with ECDHE: zero CBC and zero RSA key exchange")
  void collectorPort() throws Exception {
    ArgosTestServer server = SecuritySuiteAccess.server();
    int port = server.collectorPort();

    String json = scan(port);
    Path report =
        Paths.get(System.getProperty("argos.repositoryRoot", ".."))
            .resolve("server/build/reports/security/testssl-collector.json");
    Files.createDirectories(report.getParent());
    Files.writeString(report, json, StandardCharsets.UTF_8);

    Map<String, String> protocols = new LinkedHashMap<>();
    Set<String> offered = new TreeSet<>();
    List<String> scanErrors = new ArrayList<>();
    for (JsonNode finding : JSON.readTree(json)) {
      String id = finding.path("id").asString();
      String text = finding.path("finding").asString();
      String severity = finding.path("severity").asString();
      if (Set.of("SSLv2", "SSLv3", "TLS1", "TLS1_1", "TLS1_2", "TLS1_3").contains(id)) {
        protocols.put(id, text);
      } else if (id.startsWith("cipher") && !notOffered(text)) {
        Matcher m = IANA_NAME.matcher(text);
        while (m.find()) {
          offered.add(m.group());
        }
      } else if ("FATAL".equals(severity) || id.startsWith("scanProblem")) {
        scanErrors.add(id + ": " + text);
      }
    }
    System.out.println("[T-SEC-07c] protocols " + protocols + ", suites " + offered);

    assertThat(scanErrors).as("testssl.sh scan problems (report %s)", report).isEmpty();
    for (String old : List.of("SSLv2", "SSLv3", "TLS1", "TLS1_1")) {
      assertThat(protocols).as("testssl.sh protocol findings").containsKey(old);
      assertThat(protocols.get(old)).as(old).containsIgnoringCase("not offered");
    }
    for (String current : List.of("TLS1_2", "TLS1_3")) {
      assertThat(protocols).as("testssl.sh protocol findings").containsKey(current);
      assertThat(notOffered(protocols.get(current))).as(current + " offered").isFalse();
    }
    assertThat(offered).as("offered cipher suites (IANA names)").isNotEmpty();
    assertThat(offered).as("CBC suites").noneMatch(s -> s.contains("_CBC_"));
    assertThat(offered).as("RSA key exchange suites").noneMatch(s -> s.startsWith("TLS_RSA_"));
    assertThat(offered)
        .as("only TLS 1.2 AEAD suites with ECDHE and TLS 1.3 suites")
        .allMatch(s -> AEAD_SUITE.matcher(s).matches());
  }

  private static boolean notOffered(String finding) {
    String text = finding.toLowerCase(Locale.ROOT);
    return text.contains("not offered") || text.contains("not available");
  }

  /** Runs testssl.sh once against the collector port and returns its JSON file. */
  private static String scan(int port) {
    String os = DockerClientFactory.instance().client().infoCmd().exec().getOperatingSystem();
    boolean desktop = os != null && os.contains("Docker Desktop");
    String target = (desktop ? "host.docker.internal" : "127.0.0.1") + ":" + port;
    String script =
        "testssl.sh --quiet --color 0 --warnings off --openssl-timeout 10 --protocols"
            + " --each-cipher --jsonfile /tmp/testssl.json "
            + target
            + " >/tmp/testssl.out 2>&1; test -s /tmp/testssl.json && cat /tmp/testssl.json"
            + " || { tail -n 40 /tmp/testssl.out >&2; exit 1; }";
    try (GenericContainer<?> scanner =
        new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withLabel(ServerTestEnvironment.CONTAINER_LABEL, "testssl")
            .withCreateContainerCmdModifier(
                cmd -> {
                  cmd.withEntrypoint("bash", "-c");
                  HostConfig host = Objects.requireNonNull(cmd.getHostConfig());
                  host.withMemory(MEMORY_BYTES).withMemorySwap(MEMORY_BYTES);
                  if (desktop) {
                    host.withExtraHosts("host.docker.internal:host-gateway");
                  } else {
                    host.withNetworkMode("host");
                  }
                })
            .withCommand(new String[] {script})
            .withStartupCheckStrategy(
                new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(10)))) {
      try {
        scanner.start();
      } catch (RuntimeException e) {
        throw new AssertionError(
            "testssl.sh against "
                + target
                + " failed: "
                + scanner.getLogs(OutputFrame.OutputType.STDERR),
            e);
      }
      String json = scanner.getLogs(OutputFrame.OutputType.STDOUT).strip();
      assertThat(json).as("testssl.sh JSON output").startsWith("[");
      return json;
    }
  }
}
