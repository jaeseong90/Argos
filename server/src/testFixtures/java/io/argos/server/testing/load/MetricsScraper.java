/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.load;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Reads {@code /actuator/prometheus} of a server's management port (loopback, 12 §9.4) with the
 * metrics bearer token (16 §9.7). The token is held only to build the header; it is never logged.
 */
public final class MetricsScraper {

  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .build();
  private final URI uri;
  private final String bearer;

  /** A scraper of {@code http://127.0.0.1:<managementPort>/actuator/prometheus}. */
  public MetricsScraper(int managementPort, String metricsToken) {
    this.uri = URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus");
    this.bearer = "Bearer " + metricsToken;
  }

  /** One scrape. */
  public PrometheusText scrape() {
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .header("Authorization", bearer)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
    try {
      HttpResponse<String> response =
          http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "GET " + uri.getPath() + " answered " + response.statusCode());
      }
      return PrometheusText.parse(response.body());
    } catch (IOException e) {
      throw new UncheckedIOException("GET " + uri.getPath() + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GET " + uri.getPath() + " interrupted", e);
    }
  }

  @Override
  public String toString() {
    return "MetricsScraper[" + uri + "]";
  }
}
