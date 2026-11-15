/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.ArgosAuthentication;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Role;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

/** Which bucket a request is charged to, and what a refused request looks like. */
@Tag("req:SEC-56")
class RateLimitFiltersTest {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID SID = UUID.randomUUID();

  /** Limits small enough to exhaust. */
  private final RateLimiter limiter =
      new RateLimiter(
          Map.of(
              RateLimitBucket.AUTH, new RateLimiter.Limit(2, 2, Duration.ofMinutes(1), 100),
              RateLimitBucket.CSP_REPORT, new RateLimiter.Limit(3, 3, Duration.ofMinutes(1), 100),
              RateLimitBucket.API, new RateLimiter.Limit(2, 1, Duration.ofSeconds(1), 100),
              RateLimitBucket.HEAVY, new RateLimiter.Limit(1, 1, Duration.ofMinutes(1), 100)),
          new SimpleMeterRegistry(),
          TimeMeter.SYSTEM_NANOTIME);

  @AfterEach
  void clearPrincipal() {
    SecurityContextHolder.clearContext();
  }

  private static MockHttpServletRequest post(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setServletPath(path);
    request.setRemoteAddr("192.0.2.10");
    return request;
  }

  private static void signIn(Role role) {
    ArgosAuthentication.authenticate(
        new ArgosPrincipal(USER, "someone", SID, role, Set.of(), false));
  }

  @Test
  @DisplayName(
      "T-SEC-56 login, refresh and every non-read of /setup are the address bucket; CSP reports"
          + " theirs; reads of /setup and everything else none")
  void addressBuckets() {
    assertThat(IpRateLimitFilter.bucketOf(post("/api/v1/auth/login")))
        .isEqualTo(RateLimitBucket.AUTH);
    assertThat(IpRateLimitFilter.bucketOf(post("/api/v1/auth/refresh")))
        .isEqualTo(RateLimitBucket.AUTH);
    assertThat(IpRateLimitFilter.bucketOf(post("/api/v1/setup/admin")))
        .isEqualTo(RateLimitBucket.AUTH);
    assertThat(IpRateLimitFilter.bucketOf(post("/api/v1/csp-report")))
        .isEqualTo(RateLimitBucket.CSP_REPORT);

    MockHttpServletRequest status = post("/api/v1/setup/status");
    status.setMethod("GET");
    assertThat(IpRateLimitFilter.bucketOf(status)).isNull();
    assertThat(IpRateLimitFilter.bucketOf(post("/api/v1/auth/logout"))).isNull();
    assertThat(IpRateLimitFilter.bucketOf(post("/api/v1/auth/login-other"))).isNull();
  }

  @Test
  @DisplayName(
      "T-SEC-56 the path is compared decoded, as Spring MVC matches it, not as the client spelled"
          + " it")
  void decodedPath() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/logi%6E");
    request.setServletPath("/api/v1/auth/login");
    assertThat(IpRateLimitFilter.bucketOf(request)).isEqualTo(RateLimitBucket.AUTH);

    MockHttpServletRequest unrouted = new MockHttpServletRequest("POST", "/api/v1/auth/logi%6E");
    assertThat(IpRateLimitFilter.bucketOf(unrouted))
        .as("without a servlet path the URI is decoded")
        .isEqualTo(RateLimitBucket.AUTH);
  }

  @Test
  @DisplayName(
      "T-SEC-56 over the address limit: 429 rate_limited, Retry-After and the limit headers, and the"
          + " chain does not continue")
  void addressFilterRefuses() throws Exception {
    IpRateLimitFilter filter = new IpRateLimitFilter(limiter);
    for (int i = 0; i < 2; i++) {
      MockHttpServletResponse allowed = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();
      filter.doFilter(post("/api/v1/auth/login"), allowed, chain);
      assertThat(chain.getRequest()).isNotNull();
      assertThat(allowed.getHeader("X-RateLimit-Limit")).isEqualTo("2");
      assertThat(allowed.getHeader("X-RateLimit-Remaining")).isEqualTo(Integer.toString(1 - i));
    }
    MockHttpServletResponse refused = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(post("/api/v1/auth/login"), refused, chain);
    assertThat(chain.getRequest()).isNull();
    assertThat(refused.getStatus()).isEqualTo(429);
    assertThat(refused.getContentAsString()).contains("\"code\":\"rate_limited\"");
    assertThat(refused.getHeader("Retry-After")).isNotBlank();
    assertThat(refused.getHeader("X-RateLimit-Remaining")).isEqualTo("0");

    MockHttpServletRequest other = post("/api/v1/auth/login");
    other.setRemoteAddr("192.0.2.11");
    MockFilterChain otherChain = new MockFilterChain();
    filter.doFilter(other, new MockHttpServletResponse(), otherChain);
    assertThat(otherChain.getRequest()).as("another address has its own bucket").isNotNull();
  }

  @Test
  @DisplayName("the filter order: address limit first, user limit after CSRF")
  void positions() {
    assertThat(new IpRateLimitFilter(limiter).position()).isEqualTo(FilterOrder.IP_RATE_LIMIT);
    assertThat(new UserRateLimitFilter(limiter).position()).isEqualTo(FilterOrder.USER_RATE_LIMIT);
  }

  @Test
  @DisplayName(
      "T-SEC-56 the general bucket is per session; requests without a session share one per"
          + " address; a more specific bucket keeps its limit headers")
  void generalBucket() throws Exception {
    assertThat(UserRateLimitFilter.key(post("/x"))).isEqualTo("ip:192.0.2.10");
    signIn(Role.VIEWER);
    assertThat(UserRateLimitFilter.key(post("/x"))).isEqualTo("sid:" + SID);

    UserRateLimitFilter filter = new UserRateLimitFilter(limiter);
    MockHttpServletResponse specific = new MockHttpServletResponse();
    specific.setHeader(RateLimiter.LIMIT_HEADER, "20");
    filter.doFilter(post("/x"), specific, new MockFilterChain());
    assertThat(specific.getHeader(RateLimiter.LIMIT_HEADER)).isEqualTo("20");

    MockHttpServletResponse general = new MockHttpServletResponse();
    filter.doFilter(post("/x"), general, new MockFilterChain());
    assertThat(general.getHeader(RateLimiter.LIMIT_HEADER)).isEqualTo("2");

    MockHttpServletResponse refused = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(post("/x"), refused, chain);
    assertThat(refused.getStatus()).isEqualTo(429);
    assertThat(chain.getRequest()).isNull();
  }

  /** Handlers of different buckets. */
  static class Handlers {
    @RateLimited(RateLimitBucket.HEAVY)
    public void heavy() {}

    @RateLimited(RateLimitBucket.COMMAND)
    public void command() {}

    @RateLimited(RateLimitBucket.AUTH)
    public void auth() {}

    public void plain() {}
  }

  /** A class-level bucket and a method override. */
  @RateLimited(RateLimitBucket.HEAVY)
  static class AnnotatedClass {
    public void inherited() {}

    @RateLimited(RateLimitBucket.CSP_REPORT)
    public void overridden() {}
  }

  private static HandlerMethod handler(Object bean, String name) throws NoSuchMethodException {
    Method method = bean.getClass().getMethod(name);
    return new HandlerMethod(bean, method);
  }

  @Test
  @DisplayName(
      "T-SEC-56 @RateLimited(HEAVY) is charged per user after the role check; a refusal is the"
          + " ApiException with the headers")
  void heavyHandler() throws Exception {
    RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter);
    signIn(Role.VIEWER);
    Handlers bean = new Handlers();
    MockHttpServletResponse allowed = new MockHttpServletResponse();
    assertThat(interceptor.preHandle(post("/x"), allowed, handler(bean, "heavy"))).isTrue();
    assertThat(allowed.getHeader(RateLimiter.LIMIT_HEADER)).isEqualTo("1");

    assertThatThrownBy(
            () ->
                interceptor.preHandle(
                    post("/x"), new MockHttpServletResponse(), handler(bean, "heavy")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
              assertThat(e.headers()).containsKeys("Retry-After", "X-RateLimit-Limit");
            });
  }

  @Test
  @DisplayName(
      "@RateLimited: other handlers are not charged, buckets of later work orders are only markers,"
          + " a bucket the path filter charged is not charged twice")
  void otherHandlers() throws Exception {
    RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter);
    Handlers bean = new Handlers();
    for (String name : new String[] {"plain", "command"}) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      assertThat(interceptor.preHandle(post("/x"), response, handler(bean, name))).isTrue();
      assertThat(response.getHeader(RateLimiter.LIMIT_HEADER)).as(name).isNull();
    }
    assertThat(interceptor.preHandle(post("/x"), new MockHttpServletResponse(), new Object()))
        .as("not a handler method")
        .isTrue();

    MockHttpServletRequest charged = post("/x");
    IpRateLimitFilter.markApplied(charged, RateLimitBucket.AUTH);
    MockHttpServletResponse response = new MockHttpServletResponse();
    assertThat(interceptor.preHandle(charged, response, handler(bean, "auth"))).isTrue();
    assertThat(response.getHeader(RateLimiter.LIMIT_HEADER)).isNull();
  }

  @Test
  @DisplayName("@RateLimited on a method wins over the class")
  void annotationLookup() throws Exception {
    AnnotatedClass bean = new AnnotatedClass();
    assertThat(RateLimitInterceptor.bucketOf(handler(bean, "inherited")))
        .contains(RateLimitBucket.HEAVY);
    assertThat(RateLimitInterceptor.bucketOf(handler(bean, "overridden")))
        .contains(RateLimitBucket.CSP_REPORT);
    assertThat(RateLimitInterceptor.bucketOf(handler(new Handlers(), "plain"))).isEmpty();
  }

  @Test
  @DisplayName("the client address: the socket peer, without brackets or zone")
  void clientAddress() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("[2001:db8::1%eth0]");
    assertThat(RateLimiter.clientAddress(request)).isEqualTo("2001:db8::1");
    request.setRemoteAddr("192.0.2.1");
    request.addHeader("X-Forwarded-For", "10.0.0.1");
    assertThat(RateLimiter.clientAddress(request)).isEqualTo("192.0.2.1");
    request.setRemoteAddr(" ");
    assertThat(RateLimiter.clientAddress(request)).isEqualTo("unknown");
  }
}
