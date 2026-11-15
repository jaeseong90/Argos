/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The policy text of 16 §9.9 and the page template. */
@Tag("req:SEC-46")
class CspPolicyTest {

  @Test
  @DisplayName("T-SEC-46a the header is exactly the value of 16 §9.9")
  void exactValue() {
    assertThat(new CspPolicy("https://argos.example.com", false).header("N0nce"))
        .isEqualTo(
            "default-src 'self'; script-src 'self' 'nonce-N0nce' 'strict-dynamic'; style-src 'self'"
                + " 'nonce-N0nce'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'"
                + " wss://argos.example.com; worker-src 'self' blob:; object-src 'none'; base-uri"
                + " 'self'; form-action 'self'; frame-ancestors 'none'; report-uri"
                + " /api/v1/csp-report; report-to csp");
  }

  @Test
  @DisplayName("T-SEC-46a the style fallback replaces the nonce of style-src only, off by default")
  void styleFallback() {
    String header = new CspPolicy("https://a.example", true).header("N");
    assertThat(header)
        .contains("style-src 'self' 'unsafe-inline';")
        .contains("script-src 'self' 'nonce-N' 'strict-dynamic';");
    assertThat(new CspPolicy("https://a.example", false).header("N")).doesNotContain("unsafe");
  }

  @Test
  @DisplayName("the WebSocket origin of the public URL: scheme, host in lower case, port, no path")
  void websocketOrigin() {
    assertThat(CspPolicy.websocketOrigin("https://Argos.Example.com"))
        .isEqualTo("wss://argos.example.com");
    assertThat(CspPolicy.websocketOrigin("https://a.example:8443/ui/"))
        .isEqualTo("wss://a.example:8443");
    assertThat(CspPolicy.websocketOrigin("http://127.0.0.1:18080"))
        .isEqualTo("ws://127.0.0.1:18080");
    for (String bad : new String[] {"", "argos.example.com", "ftp://a.example", "https://", null}) {
      assertThatThrownBy(() -> CspPolicy.websocketOrigin(bad))
          .as(String.valueOf(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("T-SEC-46a a nonce is 16 random bytes in base64url, and never repeats")
  void nonces() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 1_000; i++) {
      String nonce = CspPolicy.newNonce();
      assertThat(Base64.getUrlDecoder().decode(nonce)).hasSize(16);
      assertThat(nonce).matches("[A-Za-z0-9_-]+");
      assertThat(seen.add(nonce)).isTrue();
    }
  }

  private static String render(String html, String nonce) {
    return new String(IndexHtmlTemplate.parse(html).render(nonce), StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("T-SEC-46a the template puts the nonce into every script, style and preload tag")
  void templateTags() {
    assertThat(
            render(
                "<head><script src=\"/a.js\"></script><SCRIPT>1</SCRIPT><style>b{}</style>"
                    + "<link rel=\"modulepreload\" href=\"/m.js\"><link rel=preload href=\"/p\">"
                    + "<link rel=\"stylesheet\" href=\"/s.css\"></head>",
                "N"))
        .isEqualTo(
            "<head><meta name=\"csp-nonce\" content=\"N\">"
                + "<script nonce=\"N\" src=\"/a.js\"></script><SCRIPT nonce=\"N\">1</SCRIPT>"
                + "<style nonce=\"N\">b{}</style>"
                + "<link nonce=\"N\" rel=\"modulepreload\" href=\"/m.js\">"
                + "<link nonce=\"N\" rel=preload href=\"/p\">"
                + "<link rel=\"stylesheet\" href=\"/s.css\"></head>");
  }

  @Test
  @DisplayName("T-SEC-46a a meta csp-nonce of the file is filled, a nonce of the file is replaced")
  void templateExistingValues() {
    assertThat(
            render(
                "<head><meta charset=\"UTF-8\"><meta name='csp-nonce' content='old'>"
                    + "<script nonce=\"old\" src=\"/a.js\"></script>"
                    + "<script src=\"/b.js\" NONCE='old'></script></head>",
                "N"))
        .isEqualTo(
            "<head><meta charset=\"UTF-8\"><meta name=\"csp-nonce\" content=\"N\">"
                + "<script nonce=\"N\" src=\"/a.js\"></script>"
                + "<script nonce=\"N\" src=\"/b.js\"></script></head>");
  }

  @Test
  @DisplayName("the template renders the UI's own page shape, and a page without tags unchanged")
  void templateShapes() {
    String vite =
        "<!doctype html>\n<html><head>\n<meta charset=\"UTF-8\" />\n"
            + "<script type=\"module\" crossorigin src=\"/assets/index-a.js\"></script>\n"
            + "<link rel=\"stylesheet\" crossorigin href=\"/assets/index-b.css\">\n</head>"
            + "<body><div id=\"root\"></div></body></html>";
    IndexHtmlTemplate template = IndexHtmlTemplate.parse(vite);
    assertThat(template.nonceCount()).isEqualTo(2);
    assertThat(new String(template.render("N"), StandardCharsets.UTF_8))
        .contains("<script nonce=\"N\" type=\"module\" crossorigin src=\"/assets/index-a.js\">")
        .contains("<meta name=\"csp-nonce\" content=\"N\">")
        .contains("<link rel=\"stylesheet\" crossorigin href=\"/assets/index-b.css\">");
    assertThat(render("plain text", "N")).isEqualTo("plain text");
  }
}
