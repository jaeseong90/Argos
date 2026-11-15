/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.web.security.CspPolicy;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** The UI page of {@link IndexHtmlController} without a server. */
@Tag("req:SEC-46")
class IndexHtmlControllerTest {

  private static final CspPolicy POLICY = new CspPolicy("https://a.example", false);

  private final IndexHtmlController controller =
      new IndexHtmlController(
          POLICY,
          new ByteArrayResource(
              "<head><script src=\"/a.js\"></script></head>".getBytes(StandardCharsets.UTF_8)));

  @Test
  @DisplayName("T-SEC-46a a response has the page with the nonce of its policy, and is not cached")
  void page() {
    ResponseEntity<byte[]> response = controller.page(null);
    HttpHeaders headers = response.getHeaders();
    String policy = headers.getFirst(CspPolicy.HEADER);
    String nonce = policy.replaceAll(".*'nonce-([^']+)'.*", "$1");
    assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
        .isEqualTo(
            "<head><meta name=\"csp-nonce\" content=\""
                + nonce
                + "\"><script nonce=\""
                + nonce
                + "\" src=\"/a.js\"></script></head>");
    assertThat(headers.getCacheControl()).isEqualTo(CacheControl.noStore().getHeaderValue());
    assertThat(headers.getFirst(CspPolicy.REPORTING_ENDPOINTS_HEADER))
        .isEqualTo("csp=\"/api/v1/csp-report\"");
    assertThat(headers.getContentType().toString()).isEqualTo("text/html;charset=UTF-8");
  }

  @Test
  @DisplayName("T-SEC-46a a client route with a file extension is a missing file, not the page")
  void fileNames() {
    assertThat(controller.page("/3/dashboard").getStatusCode().value()).isEqualTo(200);
    assertThat(controller.page("").getStatusCode().value()).isEqualTo(200);
    assertThat(controller.page("/a.b/c").getStatusCode().value())
        .as("only the last segment counts")
        .isEqualTo(200);
    for (String rest : new String[] {"/x.js", "/a/b.css", "/.env"}) {
      assertThatThrownBy(() -> controller.page(rest))
          .as(rest)
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
  }

  @Test
  @DisplayName("a build without the UI has no page: 404")
  void noPage() {
    IndexHtmlController none =
        new IndexHtmlController(POLICY, new ClassPathResource("static/no-such-page.html"));
    assertThatThrownBy(() -> none.page(null))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }
}
