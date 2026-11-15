/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.guard.AgentCounters;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Request rules of the HTTP path: exclusion, path cleaning, fallback names, IP masking. */
class HttpRulesTest {

  @Test
  @Tag("req:F-A29")
  @DisplayName("F-A29 normalization: numbers, UUIDs and hex ids of 32+ chars become {*}")
  void normalize() {
    assertThat(HttpPaths.normalize("/api/orders/42")).isEqualTo("/api/orders/{*}");
    assertThat(HttpPaths.normalize("/api/orders/42/items")).isEqualTo("/api/orders/{*}/items");
    assertThat(HttpPaths.normalize("/42/7")).isEqualTo("/{*}/{*}");
    assertThat(HttpPaths.normalize("/u/123e4567-e89b-12d3-a456-426614174000")).isEqualTo("/u/{*}");
    assertThat(HttpPaths.normalize("/f/0123456789abcdef0123456789ABCDEF")).isEqualTo("/f/{*}");
    // 31 hex characters, a word with digits, a short hex word: not identifiers.
    assertThat(HttpPaths.normalize("/f/0123456789abcdef0123456789abcde"))
        .isEqualTo("/f/0123456789abcdef0123456789abcde");
    assertThat(HttpPaths.normalize("/api/v2/beef")).isEqualTo("/api/v2/beef");
    assertThat(HttpPaths.normalize("/api/not-found-x")).isEqualTo("/api/not-found-x");
    assertThat(HttpPaths.normalize("/api/")).isEqualTo("/api/");
    assertThat(HttpPaths.normalize("/")).isEqualTo("/");
    assertThat(HttpPaths.normalize("")).isEqualTo("/");
    assertThat(HttpPaths.normalize("/a;jsessionid=X/12")).isEqualTo("/a/{*}");
  }

  @Test
  @DisplayName("path parameters (;jsessionid=...) are removed; extensions are lower case")
  void cleanAndExtension() {
    assertThat(HttpPaths.clean("/a;jsessionid=ABC/b;v=1")).isEqualTo("/a/b");
    assertThat(HttpPaths.clean("/a/b")).isEqualTo("/a/b");
    assertThat(HttpPaths.clean(";x")).isEqualTo("/");
    assertThat(HttpPaths.clean(null)).isEqualTo("/");
    assertThat(HttpPaths.extension("/static/APP.JS")).isEqualTo("js");
    assertThat(HttpPaths.extension("/a.b/c")).isNull();
    assertThat(HttpPaths.extension("/a/b.")).isNull();
    assertThat(HttpPaths.extension("/a/b")).isNull();
  }

  @Test
  @Tag("req:F-A23")
  @Tag("req:D-19")
  @DisplayName("10 §7.2 defaults: OPTIONS, health paths, static extensions; order method > path")
  void defaultIgnoreRules() {
    IgnoreRules rules = IgnoreRules.defaults();
    assertThat(rules.reason("OPTIONS", "/actuator/health", null)).isEqualTo("method");
    for (String path :
        Arrays.asList(
            "/actuator/health",
            "/actuator/health/readiness",
            "/actuator/health/a/b",
            "/health",
            "/healthz",
            "/ready",
            "/favicon.ico",
            "/actuator/health;jsessionid=1")) {
      assertThat(rules.reason("GET", path, null)).as(path).isEqualTo("path");
    }
    for (String path :
        Arrays.asList("/static/app.js", "/a/b.CSS", "/x.woff2", "/m.map", "/i.svg")) {
      assertThat(rules.reason("GET", path, null)).as(path).isEqualTo("extension");
    }
    for (String path :
        Arrays.asList(
            "/api/orders/1", "/actuator/healthy", "/actuator/info", "/api/report.pdf", "/")) {
      assertThat(rules.reason("GET", path, "curl/8")).as(path).isNull();
    }
    assertThat(rules.hasUserAgentRules()).isFalse();
  }

  @Test
  @Tag("req:F-A23")
  @Tag("req:D-19")
  @DisplayName(
      "T-AG-012a 10 §7.2 order: method > path > extension > user agent; the first matching rule"
          + " names the reason; null lists are empty")
  void evaluationOrder() {
    IgnoreRules rules =
        IgnoreRules.of(
            Arrays.asList("/static/**"),
            Arrays.asList("js"),
            Arrays.asList("kube-probe/*"),
            Arrays.asList("options", "HEAD"));
    String probe = "kube-probe/1.29";
    assertThat(rules.reason("OPTIONS", "/static/app.js", probe)).isEqualTo("method");
    assertThat(rules.reason("HEAD", "/x", null)).isEqualTo("method");
    assertThat(rules.reason("GET", "/static/app.js", probe)).isEqualTo("path");
    assertThat(rules.reason("GET", "/x/app.js", probe)).isEqualTo("extension");
    assertThat(rules.reason("GET", "/x/app", probe)).isEqualTo("user_agent");
    assertThat(rules.reason("GET", "/x/app", "curl/8")).isNull();
    assertThat(rules.reason(null, null, null)).isNull();
    IgnoreRules empty = IgnoreRules.of(null, null, null, null);
    assertThat(empty.reason("OPTIONS", "/actuator/health", probe)).isNull();
  }

  @Test
  @DisplayName("glob semantics: * within a segment, ** across, ? one char; user-agent globs")
  void globs() {
    IgnoreRules rules =
        IgnoreRules.of(
            Arrays.asList("/a/*/c", "/b/**", "/d?"),
            Arrays.asList(".TXT"),
            Arrays.asList("kube-probe/*", "ELB-HealthChecker/?.0"),
            Collections.<String>emptyList());
    assertThat(rules.reason("GET", "/a/x/c", null)).isEqualTo("path");
    assertThat(rules.reason("GET", "/a/x/y/c", null)).isNull();
    assertThat(rules.reason("GET", "/b/x/y/z", null)).isEqualTo("path");
    assertThat(rules.reason("GET", "/d1", null)).isEqualTo("path");
    assertThat(rules.reason("GET", "/d/", null)).isNull();
    assertThat(rules.reason("GET", "/notes.txt", null)).isEqualTo("extension");
    assertThat(rules.reason("OPTIONS", "/z", null)).isNull();
    assertThat(rules.reason("GET", "/z", "kube-probe/1.29")).isEqualTo("user_agent");
    assertThat(rules.reason("GET", "/z", "ELB-HealthChecker/2.0")).isEqualTo("user_agent");
    assertThat(rules.reason("GET", "/z", "Mozilla/5.0")).isNull();
    assertThat(rules.hasUserAgentRules()).isTrue();
  }

  @Test
  @Tag("req:F-A29")
  @Tag("req:F-A36")
  @DisplayName("fallback names normalize the path token, keep a context path prefix")
  void fallbackNames() {
    assertThat(ServiceNames.fallback("GET /api/orders/42")).isEqualTo("GET /api/orders/{*}");
    assertThat(ServiceNames.fallback("/shop-a GET /api/orders/42"))
        .isEqualTo("/shop-a GET /api/orders/{*}");
    assertThat(ServiceNames.fallback("GET /api/not-found-x")).isEqualTo("GET /api/not-found-x");
    assertThat(ServiceNames.fallback("BATCH Job#run")).isEqualTo("BATCH Job#run");
    assertThat(ServiceNames.fallback(null)).isNull();
  }

  @Test
  @Tag("req:F-A29")
  @DisplayName("service name cap: known names pass, new names beyond the cap become _other_")
  void serviceNameCap() {
    ServiceNames names = new ServiceNames(2);
    assertThat(names.admit("a")).isEqualTo("a");
    assertThat(names.admit("b")).isEqualTo("b");
    assertThat(names.admit("a")).isEqualTo("a");
    assertThat(names.admit("c")).isEqualTo(ServiceNames.OTHER);
    assertThat(names.size()).isEqualTo(2);
    assertThat(names.overflowCount()).isEqualTo(1);
  }

  @Test
  @Tag("req:N-02")
  @Tag("req:F-A29")
  @DisplayName(
      "service name byte bound: 1,024-character names stop at 4 MiB, the next new name becomes"
          + " _other_ and is counted, known names still pass")
  void serviceNameByteBound() {
    AgentCounters counters = new AgentCounters();
    TracerImpl tracer = TracerImpl.builder().counters(counters).build();
    ServiceNames names = tracer.serviceNames();
    assertThat(names.maxBytes()).isEqualTo(ServiceNames.DEFAULT_MAX_BYTES);
    long perName = ServiceNames.bytesOf(TracerImpl.MAX_SERVICE_NAME_CHARS);
    int fit = (int) (ServiceNames.DEFAULT_MAX_BYTES / perName);
    for (int i = 0; i < fit; i++) {
      TraceContext ctx = tracer.begin(Kind.BATCH, longName(i), 0);
      tracer.end(ctx, 0, null, null, null, 1);
      assertThat(ctx.serviceName).isEqualTo(longName(i));
    }
    assertThat(names.size()).isEqualTo(fit).isLessThan(ServiceNames.DEFAULT_MAX);
    assertThat(names.estimatedBytes())
        .isEqualTo(fit * perName)
        .isLessThanOrEqualTo(ServiceNames.DEFAULT_MAX_BYTES);
    assertThat(counters.serviceDictionaryOverflow()).isZero();

    TraceContext next = tracer.begin(Kind.BATCH, longName(fit), 0);
    tracer.end(next, 0, null, null, null, 1);
    assertThat(next.serviceName).isEqualTo(ServiceNames.OTHER);
    assertThat(counters.serviceDictionaryOverflow()).isEqualTo(1);
    assertThat(names.overflowCount()).isEqualTo(1);
    assertThat(names.estimatedBytes()).isLessThanOrEqualTo(ServiceNames.DEFAULT_MAX_BYTES);

    TraceContext known = tracer.begin(Kind.BATCH, longName(0), 0);
    tracer.end(known, 0, null, null, null, 1);
    assertThat(known.serviceName).isEqualTo(longName(0));
    // Longer names are cut to 1,024 characters before they are admitted (not new names).
    TraceContext cut = tracer.begin(Kind.BATCH, longName(1) + "-tail", 0);
    tracer.end(cut, 0, null, null, null, 1);
    assertThat(cut.serviceName).isEqualTo(longName(1));
    assertThat(counters.serviceDictionaryOverflow()).isEqualTo(1);
  }

  @Test
  @Tag("req:F-A29")
  @DisplayName(
      "service name byte bound keeps the F-A29 count cap for names of up to 128 characters, also"
          + " for a raised service-dictionary-max")
  void serviceNameByteBoundKeepsCountCap() {
    for (int max : new int[] {ServiceNames.DEFAULT_MAX, 20_000}) {
      ServiceNames names = new ServiceNames(max);
      for (int i = 0; i < max; i++) {
        String name = name128(i);
        assertThat(names.admit(name)).isEqualTo(name);
      }
      assertThat(names.size()).isEqualTo(max);
      assertThat(names.admit(name128(max))).isEqualTo(ServiceNames.OTHER);
      assertThat(names.overflowCount()).isEqualTo(1);
      assertThat(names.estimatedBytes()).isLessThanOrEqualTo(names.maxBytes());
    }
    assertThat(new ServiceNames(ServiceNames.DEFAULT_MAX).maxBytes())
        .isEqualTo(ServiceNames.DEFAULT_MAX_BYTES);
  }

  /** A distinct 1,024-character name. */
  private static String longName(int number) {
    return padded("BATCH job-" + number + "-", TracerImpl.MAX_SERVICE_NAME_CHARS);
  }

  /** A distinct 128-character name. */
  private static String name128(int number) {
    return padded("GET /api/v" + number + "/", ServiceNames.TYPICAL_NAME_CHARS);
  }

  private static String padded(String prefix, int length) {
    StringBuilder text = new StringBuilder(length).append(prefix);
    while (text.length() < length) {
      text.append('x');
    }
    return text.toString();
  }

  @Test
  @Tag("req:SEC-31")
  @DisplayName("SEC-31 masked: IPv4 last octet 0, IPv6 low 80 bits 0; non-literals dropped")
  void remoteIpMasking() {
    assertThat(RemoteIps.mask("192.0.2.17")).isEqualTo("192.0.2.0");
    assertThat(RemoteIps.mask("2001:db8:85a3:8d3:1319:8a2e:370:7348")).isEqualTo("2001:db8:85a3::");
    assertThat(RemoteIps.mask("2001:db8::1")).isEqualTo("2001:db8:0::");
    assertThat(RemoteIps.mask("[::1]")).isEqualTo("0:0:0::");
    assertThat(RemoteIps.mask("fe80::1%eth0")).isEqualTo("fe80:0:0::");
    assertThat(RemoteIps.mask("::ffff:192.0.2.17")).isEqualTo("192.0.2.0");
    assertThat(RemoteIps.mask("0:0:0:0:0:0:0:1")).isEqualTo("0:0:0::");
    for (String bad :
        Arrays.asList(
            "host.example", "1.2.3", "1.2.3.256", "1:2:3", "1::2::3", "12345::", "", "١.٢.٣.٤")) {
      assertThat(RemoteIps.mask(bad)).as(bad).isNull();
    }
    assertThat(RemoteIps.apply("192.0.2.17", CaptureConfig.REMOTE_IP_FULL)).isEqualTo("192.0.2.17");
    assertThat(RemoteIps.apply("192.0.2.17", CaptureConfig.REMOTE_IP_OFF)).isNull();
    assertThat(RemoteIps.apply("192.0.2.17", CaptureConfig.REMOTE_IP_MASKED))
        .isEqualTo("192.0.2.0");
    assertThat(RemoteIps.apply(null, CaptureConfig.REMOTE_IP_FULL)).isNull();
  }
}
