/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.config.ServerBuild;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Request ids and the server version header (14 §2.1, §2.6; 17 §11.3). */
@Tag("req:DSN-14-01")
class RequestIdFilterTest {

  private final ServerBuild build = new ServerBuild("1.2.3", "abc1234", "1");

  @Test
  @DisplayName("ids have the ULID layout, sort by time and do not repeat")
  void ids() {
    Clock early = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);
    Clock late = Clock.fixed(Instant.parse("2026-11-03T00:00:01Z"), ZoneOffset.UTC);
    String a = new RequestIds(early).next();
    String b = new RequestIds(late).next();
    assertThat(a).matches(RequestIds.FORMAT).hasSize(26);
    assertThat(a.compareTo(b)).isNegative();
    assertThat(RequestIds.format(0L, 0L, 0)).isEqualTo("0".repeat(26));
    assertThat(RequestIds.format(0xFFFF_FFFF_FFFFL, -1L, 0xFFFF))
        .isEqualTo("7ZZZZZZZZZZZZZZZZZZZZZZZZZ");
    Set<String> seen = new HashSet<>();
    RequestIds ids = new RequestIds(early);
    for (int i = 0; i < 10_000; i++) {
      assertThat(seen.add(ids.next())).isTrue();
    }
  }

  @Test
  @DisplayName("every response carries X-Request-Id and X-Argos-Server-Version; MDC only inside")
  void headersAndMdc() throws Exception {
    AtomicReference<String> seenInChain = new AtomicReference<>();
    RequestIdFilter filter = new RequestIdFilter(new RequestIds(), build);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/apps");
    MockHttpServletResponse response = new MockHttpServletResponse();
    // A spoofed id from the client is not used.
    request.addHeader("X-Request-Id", "client-chosen");

    filter.doFilter(
        request,
        response,
        new MockFilterChain(
            new jakarta.servlet.http.HttpServlet() {
              private static final long serialVersionUID = 1L;

              @Override
              protected void service(
                  jakarta.servlet.http.HttpServletRequest req,
                  jakarta.servlet.http.HttpServletResponse res) {
                seenInChain.set(MDC.get(RequestIdFilter.MDC_KEY));
                res.setStatus(404);
              }
            }));

    String id = response.getHeader(RequestIdFilter.HEADER);
    assertThat(id).matches(RequestIds.FORMAT).isNotEqualTo("client-chosen");
    assertThat(seenInChain.get()).isEqualTo(id);
    assertThat(RequestIdFilter.currentId(request)).isEqualTo(id);
    assertThat(response.getHeader(RequestIdFilter.VERSION_HEADER)).isEqualTo("1.2.3");
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("the error dispatch of a request keeps the id of that request")
  void errorDispatchKeepsId() throws Exception {
    RequestIdFilter filter = new RequestIdFilter(new RequestIds(), build);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    String id = RequestIdFilter.currentId(request);

    request.setDispatcherType(DispatcherType.ERROR);
    AtomicReference<String> inError = new AtomicReference<>();
    filter.doFilter(request, response, (req, res) -> inError.set(MDC.get(RequestIdFilter.MDC_KEY)));
    assertThat(inError.get()).isEqualTo(id);
    assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
  }
}
