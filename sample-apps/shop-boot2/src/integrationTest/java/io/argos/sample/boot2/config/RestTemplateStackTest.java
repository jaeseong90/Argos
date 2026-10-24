/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.sample.boot2.http.RestTemplateClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

/**
 * RestTemplate of the 18 §4.1 shop-boot2 stack runs on Apache HttpClient 4 (part of StackInfoTest
 * before; slow in a fresh JVM, so integrationTest, 18 §2.2).
 */
@Tag("slow")
class RestTemplateStackTest {

  @Test
  void restTemplateRunsOnApacheHttpClient4() throws Exception {
    try (RestTemplateClient client =
        new RestTemplateClient(new RestTemplateBuilder(), 1000, 1000)) {
      assertThat(client.requestFactory()).isEqualTo("HttpComponentsClientHttpRequestFactory");
    }
  }
}
