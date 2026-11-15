/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.guard.AgentCounters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The final service name of a transaction (10 §6.1 exit, §6.2, F-A29, F-A36, C-48, SEC-34): the
 * fallback normalization of non-fixed HTTP names, the candidates it leaves alone, and the per-agent
 * cap with its counter, through the real tracer.
 */
@Tag("req:F-A29")
class ServiceNameResolverTest {

  @ParameterizedTest(name = "T-AG-013a fallback of [{0}] is [{1}]")
  @CsvSource(
      delimiter = '|',
      value = {
        "GET /api/x/12345|GET /api/x/{*}",
        "GET /api/orders/42/items|GET /api/orders/{*}/items",
        "GET /42/7|GET /{*}/{*}",
        "DELETE /u/123e4567-e89b-12d3-a456-426614174000|DELETE /u/{*}",
        "GET /f/0123456789abcdef0123456789ABCDEF|GET /f/{*}",
        // 31 hex characters, a word with digits, a short hex word: not identifiers.
        "GET /f/0123456789abcdef0123456789abcde|GET /f/0123456789abcdef0123456789abcde",
        "GET /api/v2/beef|GET /api/v2/beef",
        "GET /api/not-found-x|GET /api/not-found-x",
        "GET /a;jsessionid=X/12|GET /a/{*}",
        "GET /|GET /",
        // F-A36: the context path prefix stays, only the path token is normalized.
        "/shop-a GET /api/orders/42|/shop-a GET /api/orders/{*}",
        "/shop-b POST /api/tx/commit|/shop-b POST /api/tx/commit"
      })
  void fallbackNormalizesThePath(String provisional, String expected) {
    assertThat(ServiceNameResolver.fallback(provisional)).isEqualTo(expected);
    assertThat(ServiceNameResolver.resolve(Kind.HTTP, provisional, false)).isEqualTo(expected);
    // ServiceNames.fallback is the same rule (TracerImpl calls it).
    assertThat(ServiceNames.fallback(provisional)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "T-AG-013c no query string in the service name of [{0}]")
  @Tag("req:SEC-34")
  @CsvSource(
      delimiter = '|',
      value = {
        "GET /api/user?id=42|GET /api/user",
        "GET /seed/phone?phone=010-ARGOS_SEED-0001|GET /seed/phone",
        "GET /api/orders/7?page=0&phone=010-9999-0001|GET /api/orders/{*}",
        "GET /a#frag|GET /a",
        "GET /a/1#f?x=1|GET /a/{*}",
        "GET /?q=1|GET /",
        "/shop-a GET /api/x?email=kim@example.test|/shop-a GET /api/x"
      })
  void noQueryString(String provisional, String expected) {
    String resolved = ServiceNameResolver.resolve(Kind.HTTP, provisional, false);
    assertThat(resolved).isEqualTo(expected).doesNotContain("?").doesNotContain("=");
  }

  @Test
  @DisplayName(
      "T-AG-013a candidates that are not a concrete path stay: Controller#method, servlet mapping"
          + " patterns; fixed names are never normalized")
  void candidatesStay() {
    // spring-webmvc HandlerMethod without a pattern (non-fixed).
    assertThat(ServiceNameResolver.resolve(Kind.HTTP, "OrderController#get", false))
        .isEqualTo("OrderController#get");
    assertThat(ServiceNameResolver.resolve(Kind.HTTP, "/shop-a OrderController#get", false))
        .isEqualTo("/shop-a OrderController#get");
    // Servlet 4+ HttpServletMapping patterns (prefix, extension, exact), non-fixed.
    assertThat(ServiceNameResolver.resolve(Kind.HTTP, "/fixture GET /items/*", false))
        .isEqualTo("/fixture GET /items/*");
    assertThat(ServiceNameResolver.resolve(Kind.HTTP, "/fixture GET *.do", false))
        .isEqualTo("/fixture GET *.do");
    assertThat(ServiceNameResolver.resolve(Kind.HTTP, "/fixture GET /plain/exact", false))
        .isEqualTo("/fixture GET /plain/exact");
    // A fixed Spring pattern keeps its wildcards ('?' is a one-character wildcard, not a query).
    assertThat(ServiceNameResolver.resolve(Kind.HTTP, "GET /files/?.txt", true))
        .isEqualTo("GET /files/?.txt");
    assertThat(ServiceNameResolver.resolve(Kind.HTTP, "GET /api/orders/{id}", true))
        .isEqualTo("GET /api/orders/{id}");
    assertThat(ServiceNameResolver.resolve(Kind.HTTP, "GET /api/v1/42", true))
        .isEqualTo("GET /api/v1/42");
    // Other kinds are not HTTP paths.
    assertThat(ServiceNameResolver.resolve(Kind.BATCH, "BATCH /jobs/42", false))
        .isEqualTo("BATCH /jobs/42");
    assertThat(ServiceNameResolver.resolve(Kind.HTTP, null, false)).isNull();
    assertThat(ServiceNameResolver.fallback(null)).isNull();
  }

  @Test
  @DisplayName("T-AG-013a names are cut to 1,024 characters, fixed or not")
  void cutTo1024() {
    StringBuilder path = new StringBuilder("GET /");
    while (path.length() < 2000) {
      path.append("segment/");
    }
    String name = path.toString();
    assertThat(ServiceNameResolver.resolve(Kind.HTTP, name, false))
        .hasSize(ServiceNameResolver.MAX_CHARS);
    assertThat(ServiceNameResolver.resolve(Kind.HTTP, name, true))
        .hasSize(ServiceNameResolver.MAX_CHARS);
    assertThat(ServiceNameResolver.MAX_CHARS).isEqualTo(TracerImpl.MAX_SERVICE_NAME_CHARS);
  }

  @Test
  @DisplayName(
      "T-AG-013b the 10,001st distinct name becomes _other_ and counts"
          + " service_dictionary_overflow_total; known names still pass")
  void dictionaryCapThroughTheTracer() {
    AgentCounters counters = new AgentCounters();
    TracerImpl tracer = TracerImpl.builder().counters(counters).build();
    assertThat(tracer.serviceNames().maxBytes()).isEqualTo(ServiceNames.DEFAULT_MAX_BYTES);
    for (int i = 0; i < ServiceNames.DEFAULT_MAX; i++) {
      assertThat(endHttp(tracer, "GET /api/p" + i)).isEqualTo("GET /api/p" + i);
    }
    assertThat(counters.serviceDictionaryOverflow()).isZero();
    assertThat(endHttp(tracer, "GET /api/p" + ServiceNames.DEFAULT_MAX))
        .isEqualTo(ServiceNames.OTHER);
    assertThat(counters.serviceDictionaryOverflow()).isEqualTo(1);
    // A known name, and a new path that normalizes to a known name, still pass.
    assertThat(endHttp(tracer, "GET /api/p0")).isEqualTo("GET /api/p0");
    assertThat(tracer.serviceNames().size()).isEqualTo(ServiceNames.DEFAULT_MAX);
    assertThat(counters.serviceDictionaryOverflow()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "T-AG-013b a lowered instrumentation.service-dictionary-max: normalized names share one entry,"
          + " new ones beyond the cap are _other_")
  void loweredCap() {
    AgentCounters counters = new AgentCounters();
    TracerImpl tracer = TracerImpl.builder().counters(counters).build();
    tracer.setServiceNames(new ServiceNames(2));
    assertThat(endHttp(tracer, "GET /api/x/1")).isEqualTo("GET /api/x/{*}");
    assertThat(endHttp(tracer, "GET /api/x/2?phone=010")).isEqualTo("GET /api/x/{*}");
    assertThat(endHttp(tracer, "/shop-a GET /api/x/3")).isEqualTo("/shop-a GET /api/x/{*}");
    assertThat(endHttp(tracer, "GET /api/y")).isEqualTo(ServiceNames.OTHER);
    assertThat(endHttp(tracer, "GET /api/z")).isEqualTo(ServiceNames.OTHER);
    assertThat(counters.serviceDictionaryOverflow()).isEqualTo(2);
    assertThat(tracer.serviceNames().overflowCount()).isEqualTo(2);
    // Fixed names count against the same cap.
    TraceContext fixed = tracer.begin(Kind.HTTP, "GET /api/orders/1", 0);
    tracer.setServiceName(fixed, "GET /api/orders/{id}", true);
    tracer.end(fixed, 200, null, null, null, 1);
    assertThat(fixed.serviceName).isEqualTo(ServiceNames.OTHER);
    assertThat(counters.serviceDictionaryOverflow()).isEqualTo(3);
  }

  /** Begins and ends one HTTP transaction with the provisional name; returns its final name. */
  private static String endHttp(TracerImpl tracer, String provisional) {
    TraceContext ctx = tracer.begin(Kind.HTTP, provisional, 0);
    tracer.end(ctx, 200, null, null, null, 1);
    return ctx.serviceName;
  }
}
