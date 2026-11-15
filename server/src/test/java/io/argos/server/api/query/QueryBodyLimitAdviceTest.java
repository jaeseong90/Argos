/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.argos.server.api.error.ApiExceptionHandler;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The 256 KB JSON body limit of the query API (14 §7.2) on a {@code @RequestBody} of a controller
 * in {@code io.argos.server.api}, with the server's exception mapping (Spring MVC without a
 * server).
 */
@Tag("req:SEC-56")
class QueryBodyLimitAdviceTest {

  /** A query endpoint with a JSON body (in the advice's package). */
  @RestController
  static class SearchController {
    @PostMapping("/api/v1/search")
    Map<String, Object> search(@RequestBody Map<String, Object> body) {
      return Map.of("keys", body.size());
    }
  }

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new SearchController())
          .setControllerAdvice(new QueryBodyLimitAdvice(), new ApiExceptionHandler())
          .build();

  private MvcResult send(int bytes) throws Exception {
    String filler = "x".repeat(Math.max(0, bytes - 8));
    String body = "{\"q\":\"" + filler + "\"}"; // exactly `bytes` bytes
    assertThat(body).hasSize(bytes);
    return mvc.perform(post("/api/v1/search").contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn();
  }

  @Test
  @DisplayName("T-API-004a a 256 KB body is read, a longer one is 400 payload_too_large unparsed")
  void bodyLimit() throws Exception {
    MvcResult ok = send(QueryLimits.MAX_JSON_BODY_BYTES);
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    MvcResult tooLarge = send(QueryLimits.MAX_JSON_BODY_BYTES + 1);
    assertThat(tooLarge.getResponse().getStatus()).isEqualTo(400);
    assertThat(tooLarge.getResponse().getContentAsString())
        .contains("\"code\":\"payload_too_large\"")
        .contains("256 KB");
  }
}
