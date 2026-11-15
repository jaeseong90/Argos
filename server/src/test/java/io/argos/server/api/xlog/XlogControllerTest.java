/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.xlog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** The Accept-Encoding rule of the XLog response (DSN-14-08). */
class XlogControllerTest {

  private static boolean accepts(String header) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (header != null) {
      request.addHeader("Accept-Encoding", header);
    }
    return XlogController.acceptsGzip(request);
  }

  @Test
  @Tag("req:DSN-14-08")
  @DisplayName("gzip when Accept-Encoding lists gzip (or *) with a quality above 0")
  void acceptEncoding() {
    assertThat(accepts("gzip, deflate, br")).isTrue();
    assertThat(accepts("br;q=1.0, GZIP;q=0.5")).isTrue();
    assertThat(accepts("*")).isTrue();
    assertThat(accepts("gzip;q=0")).isFalse();
    assertThat(accepts("deflate")).isFalse();
    assertThat(accepts("gzip;q=x")).isFalse();
    assertThat(accepts(null)).isFalse();
  }

  @Test
  @Tag("req:DSN-14-08")
  @DisplayName("the gzip body is a valid gzip stream of the JSON and much smaller for columns")
  void gzipRoundTrip() throws Exception {
    StringBuilder json = new StringBuilder("{\"data\":{\"ts\":[");
    for (int i = 0; i < 20_000; i++) {
      json.append(i == 0 ? "" : ",").append(1_763_000_000_000L + i * 7L);
    }
    json.append("]}}");
    byte[] plain = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] gz = XlogController.gzip(plain);
    assertThat(gz[0]).isEqualTo((byte) 0x1f);
    assertThat(gz[1]).isEqualTo((byte) 0x8b);
    try (java.util.zip.GZIPInputStream in =
        new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gz))) {
      assertThat(in.readAllBytes()).isEqualTo(plain);
    }
    assertThat(gz.length).isLessThan(plain.length / 3);
  }
}
