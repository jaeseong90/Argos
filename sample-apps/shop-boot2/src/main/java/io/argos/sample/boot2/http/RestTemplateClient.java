/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.http;

import io.argos.sample.common.http.OutboundClient;
import io.argos.sample.common.http.OutboundResponse;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.web.client.ClientHttpRequestFactorySupplier;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * {@code ?client=resttemplate}: RestTemplate from Spring Boot 2.7's RestTemplateBuilder on the
 * request factory Boot detects from the class path (Apache HC4 here: {@code
 * HttpComponentsClientHttpRequestFactory}), redirects followed. The client keeps that factory so
 * that {@link #close()} closes its HttpClient (WAR undeploy).
 */
public final class RestTemplateClient implements OutboundClient, Closeable {

  private final RestTemplate template;
  private final ClientHttpRequestFactory requestFactory;

  public RestTemplateClient(RestTemplateBuilder builder, int connectTimeoutMs, int readTimeoutMs) {
    // The same detection RestTemplateBuilder does without a supplier (HC4, then OkHttp, then JDK).
    ClientHttpRequestFactory factory = new ClientHttpRequestFactorySupplier().get();
    this.requestFactory = factory;
    // The builder applies the timeouts to the supplied factory as it does to a detected one; the
    // actuator's metrics interceptor wraps it.
    this.template =
        builder
            .requestFactory(() -> factory)
            .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
            .setReadTimeout(Duration.ofMillis(readTimeoutMs))
            .build();
  }

  @Override
  public String name() {
    return "resttemplate";
  }

  /** Simple class name of the request factory under the interceptors (GET /api/runtime). */
  public String requestFactory() {
    return requestFactory.getClass().getSimpleName();
  }

  @Override
  public OutboundResponse get(URI uri) {
    try {
      ResponseEntity<String> response = template.getForEntity(uri, String.class);
      return new OutboundResponse(response.getStatusCodeValue(), response.getBody());
    } catch (RestClientResponseException e) {
      return new OutboundResponse(e.getRawStatusCode(), e.getResponseBodyAsString());
    }
  }

  /** Closes the HttpClient of the request factory (HttpComponentsClientHttpRequestFactory). */
  @Override
  public void close() throws IOException {
    if (requestFactory instanceof DisposableBean) {
      try {
        ((DisposableBean) requestFactory).destroy();
      } catch (IOException | RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IOException("Closing the RestTemplate request factory failed", e);
      }
    }
  }
}
