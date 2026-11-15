/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ArgosTestServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-0010 C-190 (15 §14, N-14) on the real server: the hashed UI files below {@code /assets/} are
 * gzip-compressed for a client that accepts gzip (2 KB or more) and cached for a year ({@code
 * public, immutable}), while the page and the API keep {@code Cache-Control: no-store} (SEC-45) and
 * API JSON is never compressed by the server's response compression, also above 2 KB.
 */
@Tag("req:SEC-45")
@Tag("req:N-14")
class StaticAssetsSecurityTest {

  /** C-190, written out here, not read from the server's constant. */
  private static final String IMMUTABLE = "max-age=31536000, public, immutable";

  /** A fixture of more than 2 KB, and one below it (src/securityTest/resources/static/assets). */
  private static final String LARGE = "/assets/compressible-B4rT9qZk.js";

  private static final String SMALL = "/assets/fixture.js";

  private final ArgosTestServer server = SecuritySuite.server();

  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .build();

  @BeforeEach
  void resetLimits() {
    SecuritySuite.resetLimits(server);
  }

  /** A GET with the raw body bytes (the test client decodes text, which gzip is not). */
  private HttpResponse<byte[]> get(String path, Map<String, String> headers) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(server.baseUrl() + path)).timeout(Duration.ofSeconds(10));
    headers.forEach(request::header);
    return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static byte[] resource(String path) {
    try (InputStream in = StaticAssetsSecurityTest.class.getResourceAsStream("/static" + path)) {
      assertThat(in).as("fixture " + path).isNotNull();
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] gunzip(byte[] body) throws IOException {
    try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
      return in.readAllBytes();
    }
  }

  private static List<String> varyTokens(HttpResponse<?> response) {
    return response.headers().allValues("Vary").stream()
        .flatMap(v -> List.of(v.split(",")).stream())
        .map(v -> v.strip().toLowerCase(Locale.ROOT))
        .toList();
  }

  @Test
  @DisplayName(
      "T-SEC-45 a hashed /assets file of 2 KB or more is gzip-compressed for a client that accepts"
          + " gzip, varies on Accept-Encoding and is cached for a year, public and immutable"
          + " (C-190)")
  void largeAssetIsCompressedAndCached() throws Exception {
    byte[] expected = resource(LARGE);
    assertThat(expected.length).as("the fixture is above the 2 KB threshold").isGreaterThan(2048);

    HttpResponse<byte[]> gzip = get(LARGE, Map.of("Accept-Encoding", "gzip"));
    assertThat(gzip.statusCode()).isEqualTo(200);
    assertThat(gzip.headers().allValues("Content-Encoding")).containsExactly("gzip");
    assertThat(gzip.headers().allValues("Cache-Control")).containsExactly(IMMUTABLE);
    assertThat(varyTokens(gzip)).contains("accept-encoding");
    assertThat(gzip.headers().firstValue("Content-Type").orElseThrow().toLowerCase(Locale.ROOT))
        .contains("javascript");
    assertThat(gzip.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
    assertThat(gzip.body().length).as("compressed").isLessThan(expected.length);
    assertThat(gunzip(gzip.body())).isEqualTo(expected);

    HttpResponse<byte[]> identity = get(LARGE, Map.of());
    assertThat(identity.statusCode()).isEqualTo(200);
    assertThat(identity.headers().firstValue("Content-Encoding")).isEmpty();
    assertThat(identity.headers().allValues("Cache-Control")).containsExactly(IMMUTABLE);
    assertThat(identity.body()).isEqualTo(expected);
  }

  @Test
  @DisplayName(
      "T-SEC-45 a hashed /assets file below 2 KB is sent as it is, and cached like every hashed"
          + " file (C-190)")
  void smallAssetIsNotCompressed() throws Exception {
    HttpResponse<byte[]> response = get(SMALL, Map.of("Accept-Encoding", "gzip"));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
    assertThat(response.headers().allValues("Cache-Control")).containsExactly(IMMUTABLE);
    assertThat(response.body()).isEqualTo(resource(SMALL));
  }

  @Test
  @DisplayName(
      "T-SEC-45 the page and client routes stay no-store, a missing /assets file and other static"
          + " files get no caching header (C-190 exception for hashed files only)")
  void pageStaysNoStore() throws Exception {
    for (String path : List.of("/", "/index.html", "/apps/3/dashboard")) {
      HttpResponse<byte[]> page = get(path, Map.of("Accept-Encoding", "gzip"));
      assertThat(page.statusCode()).as(path).isEqualTo(200);
      assertThat(page.headers().allValues("Cache-Control")).as(path).containsExactly("no-store");
      assertThat(page.headers().firstValue("Content-Security-Policy")).as(path).isPresent();
    }
    HttpResponse<byte[]> missing = get("/assets/missing-0000.js", Map.of());
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(missing.headers().allValues("Cache-Control"))
        .noneMatch(v -> v.contains("max-age") || v.contains("immutable") || v.contains("public"));
    HttpResponse<byte[]> other = get("/fixture.txt", Map.of("Accept-Encoding", "gzip"));
    assertThat(other.statusCode()).isEqualTo(200);
    assertThat(other.headers().allValues("Cache-Control")).noneMatch(v -> v.contains("immutable"));
    assertThat(other.headers().firstValue("Content-Encoding")).as("text/plain").isEmpty();
  }

  @Test
  @DisplayName(
      "T-SEC-45 API JSON of more than 2 KB is not compressed for a client that accepts gzip and"
          + " stays no-store (C-190: the compression list has no JSON)")
  void apiJsonIsNotCompressed() {
    ApiResponse spec =
        SecuritySuite.client(server, "viewer")
            .header("Accept-Encoding", "gzip")
            .get("/api/v1/openapi");
    assertThat(spec.status()).isEqualTo(200);
    assertThat(spec.body().length()).as("larger than the 2 KB threshold").isGreaterThan(2048);
    assertThat(spec.header("Content-Encoding")).isEmpty();
    assertThat(spec.headers("Cache-Control")).containsExactly("no-store");
    assertThat(spec.header("Content-Type").orElseThrow().toLowerCase(Locale.ROOT))
        .startsWith("application/json");
    assertThat(spec.json().path("openapi").asString()).startsWith("3.");

    ApiResponse error =
        SecuritySuite.client(server, null)
            .header("Accept-Encoding", "gzip")
            .get("/api/v1/no-such-endpoint");
    assertThat(error.status()).isEqualTo(404);
    assertThat(error.header("Content-Encoding")).isEmpty();
    assertThat(error.headers("Cache-Control")).containsExactly("no-store");
  }
}
