/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.http;

import io.argos.sample.common.http.OutboundClient;
import io.argos.sample.common.http.OutboundRequest;
import io.argos.sample.common.http.OutboundResponse;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * {@code ?client=resttemplate}: RestTemplate from Spring Boot's RestTemplateBuilder (Boot picks the
 * Apache HC5 request factory because HC5 is on the class path), redirects followed. Hop calls use a
 * second template of the same builder with the hop read timeout.
 */
public final class RestTemplateClient implements OutboundClient {

  private final RestTemplate template;
  private final RestTemplate hopTemplate;

  public RestTemplateClient(RestTemplateBuilder builder, int connectTimeoutMs, int readTimeoutMs) {
    this.template =
        builder
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .readTimeout(Duration.ofMillis(readTimeoutMs))
            .build();
    this.hopTemplate =
        builder
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .readTimeout(Duration.ofMillis(OutboundRequest.hopReadTimeoutMs(readTimeoutMs)))
            .build();
  }

  @Override
  public String name() {
    return "resttemplate";
  }

  @Override
  public OutboundResponse get(URI uri) {
    return send(OutboundRequest.get(uri));
  }

  @Override
  public OutboundResponse send(OutboundRequest request) {
    if (!OutboundRequest.SYNC.equals(request.mode())) {
      throw new IllegalArgumentException("Client 'resttemplate' supports mode sync only");
    }
    HttpHeaders headers = new HttpHeaders();
    request.headers().forEach(headers::set);
    try {
      ResponseEntity<String> response =
          (request.hop() ? hopTemplate : template)
              .exchange(request.uri(), HttpMethod.GET, new HttpEntity<>(headers), String.class);
      return new OutboundResponse(response.getStatusCode().value(), response.getBody());
    } catch (RestClientResponseException e) {
      return new OutboundResponse(e.getStatusCode().value(), e.getResponseBodyAsString());
    }
  }
}
