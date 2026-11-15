/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.bootstrap.StartupPhaseException;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.management.MetricsAccessPolicy.Decision;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The management port's access rules (SEC-55, 02 C-7, 16 §7.12). */
@Tag("req:SEC-55")
class ManagementAccessFilterTest {

  private static final SecureRandom RANDOM = new SecureRandom();

  @TempDir Path dir;

  private static String token() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private MetricsAccessPolicy policy(String token, String... cidrs) throws Exception {
    Map<String, String> m = new HashMap<>();
    if (token != null) {
      Path file =
          Files.writeString(dir.resolve("metrics-token"), token + "\n", StandardCharsets.UTF_8);
      m.put("argos.metrics.token-file", file.toString());
    }
    for (int i = 0; i < cidrs.length; i++) {
      m.put("argos.metrics.allow-cidrs[" + i + "]", cidrs[i]);
    }
    ArgosProperties p =
        new Binder(new MapConfigurationPropertySource(m))
            .bindOrCreate("argos", ArgosProperties.class);
    MetricsAccessPolicy policy = new MetricsAccessPolicy(p);
    policy.run();
    return policy;
  }

  private static MockHttpServletResponse call(
      ManagementAccessFilter filter,
      String path,
      String remote,
      String authorization,
      AtomicBoolean passed)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRemoteAddr(remote);
    if (authorization != null) {
      request.addHeader("Authorization", authorization);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    passed.set(false);
    filter.doFilter(request, response, (req, res) -> passed.set(true));
    return response;
  }

  @Test
  @DisplayName("T-SEC-55 health is open; info and prometheus need address and token; rest 404")
  void rules() throws Exception {
    String token = token();
    ManagementAccessFilter filter =
        new ManagementAccessFilter(policy(token, "127.0.0.1/32", "::1/128"), "/actuator");
    AtomicBoolean passed = new AtomicBoolean();

    for (String path :
        new String[] {
          "/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness"
        }) {
      assertThat(call(filter, path, "203.0.113.5", null, passed).getStatus())
          .as(path)
          .isEqualTo(200);
      assertThat(passed).as(path).isTrue();
    }
    for (String path : new String[] {"/actuator/prometheus", "/actuator/info"}) {
      MockHttpServletResponse none = call(filter, path, "127.0.0.1", null, passed);
      assertThat(none.getStatus()).as(path).isEqualTo(401);
      assertThat(none.getHeader("WWW-Authenticate")).startsWith("Bearer ");
      assertThat(none.getContentAsString()).contains("\"code\":\"unauthenticated\"");
      assertThat(call(filter, path, "127.0.0.1", "Bearer " + token(), passed).getStatus())
          .isEqualTo(401);
      assertThat(call(filter, path, "127.0.0.1", "Basic " + token, passed).getStatus())
          .isEqualTo(401);
      assertThat(passed).isFalse();
      assertThat(call(filter, path, "127.0.0.1", "Bearer " + token, passed).getStatus())
          .isEqualTo(200);
      assertThat(passed).isTrue();
      assertThat(call(filter, path, "0:0:0:0:0:0:0:1", "bearer " + token, passed).getStatus())
          .isEqualTo(200);
      MockHttpServletResponse outside = call(filter, path, "192.0.2.10", "Bearer " + token, passed);
      assertThat(outside.getStatus()).isEqualTo(403);
      assertThat(outside.getContentAsString()).contains("\"code\":\"forbidden\"");
      assertThat(passed).isFalse();
    }
    for (String path :
        new String[] {
          "/actuator/env",
          "/actuator/heapdump",
          "/actuator/threaddump",
          "/actuator/loggers",
          "/actuator/mappings",
          "/actuator/shutdown",
          "/actuator",
          "/actuator/metrics"
        }) {
      MockHttpServletResponse r = call(filter, path, "127.0.0.1", "Bearer " + token, passed);
      assertThat(r.getStatus()).as(path).isEqualTo(404);
      assertThat(passed).as(path).isFalse();
    }
    assertThat(call(filter, "/api/v1/apps", "127.0.0.1", null, passed).getStatus()).isEqualTo(200);
    assertThat(passed).as("paths outside the base path are left alone").isTrue();
  }

  @Test
  @DisplayName("T-SEC-55 empty allow-cidrs or no token: nobody reads info or prometheus")
  void denyByDefault() throws Exception {
    String token = token();
    assertThat(policy(token).decide("127.0.0.1", "Bearer " + token)).isEqualTo(Decision.FORBIDDEN);
    assertThat(policy(null, "127.0.0.1/32").decide("127.0.0.1", "Bearer " + token))
        .isEqualTo(Decision.UNAUTHENTICATED);
    assertThat(policy(token, "127.0.0.1/32").decide("not-an-ip", "Bearer " + token))
        .isEqualTo(Decision.FORBIDDEN);
  }

  @Test
  @DisplayName("an unreadable token file refuses the start without its content")
  void unreadableTokenFile() {
    ArgosProperties p =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of("argos.metrics.token-file", dir.resolve("absent").toString())))
            .bindOrCreate("argos", ArgosProperties.class);
    assertThatThrownBy(() -> new MetricsAccessPolicy(p).run())
        .isInstanceOf(StartupPhaseException.class)
        .hasMessageStartingWith("argos.metrics.token-file names a file that cannot be read");
  }

  @Test
  @DisplayName("bearer credentials are parsed case-insensitively; other schemes are not")
  void bearerParsing() {
    assertThat(MetricsAccessPolicy.bearer("Bearer abc")).isEqualTo("abc");
    assertThat(MetricsAccessPolicy.bearer("BEARER   abc ")).isEqualTo("abc");
    assertThat(MetricsAccessPolicy.bearer("Basic abc")).isNull();
    assertThat(MetricsAccessPolicy.bearer("Bearer ")).isNull();
    assertThat(MetricsAccessPolicy.bearer(null)).isNull();
  }
}
