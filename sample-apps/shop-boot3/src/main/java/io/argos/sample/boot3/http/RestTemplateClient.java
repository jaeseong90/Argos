/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.http;

import io.argos.sample.common.http.OutboundClient;
import io.argos.sample.common.http.OutboundResponse;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * {@code ?client=resttemplate}: RestTemplate from Spring Boot's RestTemplateBuilder (Boot picks the
 * Apache HC5 request factory because HC5 is on the class path), redirects followed.
 */
public final class RestTemplateClient implements OutboundClient {

  private final RestTemplate template;

  public RestTemplateClient(RestTemplateBuilder builder, int connectTimeoutMs, int readTimeoutMs) {
    this.template =
        builder
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .readTimeout(Duration.ofMillis(readTimeoutMs))
            .build();
  }

  @Override
  public String name() {
    return "resttemplate";
  }

  @Override
  public OutboundResponse get(URI uri) {
    try {
      ResponseEntity<String> response = template.getForEntity(uri, String.class);
      return new OutboundResponse(response.getStatusCode().value(), response.getBody());
    } catch (RestClientResponseException e) {
      return new OutboundResponse(e.getStatusCode().value(), e.getResponseBodyAsString());
    }
  }
}
