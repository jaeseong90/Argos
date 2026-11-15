/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.RoleMatrix;
import io.argos.server.auth.session.AccessTokenAuthenticationFilter;
import io.argos.server.auth.session.CsrfFilter;
import io.argos.server.auth.session.MustChangePasswordFilter;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ArgosTestServer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/**
 * How the parts of WO-24 meet on the real server (real PostgreSQL and ClickHouse, nothing stubbed):
 * every filter at its {@link FilterOrder} position, in the order the design asks for, and {@link
 * RoleMatrix} with a role for every handler. The other suites test what each part does; this one
 * tests that the running application contains them, in that place.
 */
@Tag("req:SEC-49")
@Tag("req:DSN-12-11")
class ServerWiringIT {

  @Test
  @DisplayName(
      "T-SEC-49c the real server wires each filter at its FilterOrder position and RoleMatrix knows"
          + " every handler with its role")
  void wiring() {
    try (ServerTestEnvironment env = ServerTestEnvironment.start();
        ArgosTestServer server = ArgosTestServer.start(env)) {
      assertThat(apiFilters(server))
          .as("the /api chain, in execution order")
          .containsExactly(
              Map.entry(FilterOrder.IP_RATE_LIMIT, IpRateLimitFilter.class),
              Map.entry(FilterOrder.CORS_PREFLIGHT, CorsPreflightFilter.class),
              Map.entry(
                  FilterOrder.ACCESS_TOKEN_AUTHENTICATION, AccessTokenAuthenticationFilter.class),
              Map.entry(FilterOrder.CSRF, CsrfFilter.class),
              Map.entry(FilterOrder.USER_RATE_LIMIT, UserRateLimitFilter.class),
              Map.entry(FilterOrder.MUST_CHANGE_PASSWORD, MustChangePasswordFilter.class));

      assertThat(servletFilterOrders(server))
          .containsEntry("argosRequestIdFilter", FilterOrder.REQUEST_ID)
          .containsEntry("argosEarlySecurityHeadersFilter", FilterOrder.SECURITY_HEADERS_EARLY)
          .containsEntry("argosRequestUrlLimitFilter", FilterOrder.URL_LIMIT)
          .containsEntry("argosRequestBodyLimitFilter", FilterOrder.BODY_LIMIT)
          .containsEntry("argosSecurityHeadersFilter", FilterOrder.SECURITY_HEADERS);
      assertThat(
              List.of(
                  FilterOrder.REQUEST_ID,
                  FilterOrder.SECURITY_HEADERS_EARLY,
                  FilterOrder.URL_LIMIT,
                  FilterOrder.BODY_LIMIT,
                  FilterOrder.SECURITY_HEADERS,
                  FilterOrder.SPRING_SECURITY))
          .as("the servlet filters run in the documented order, Spring Security last")
          .isSorted()
          .doesNotHaveDuplicates();

      RoleMatrix matrix = server.bean(RoleMatrix.class);
      assertThat(matrix.missing()).as("handlers without a role").isEmpty();
      assertThat(rolesByMethod(matrix.table())).containsAllEntriesOf(expectedRoles());
    }
  }

  /**
   * {@link RoleMatrix#table()} by {@code class#method}: its keys are signatures, and the roles of
   * all overloads of a name are joined, so an overload with another role fails the comparison.
   */
  private static Map<String, String> rolesByMethod(Map<String, String> table) {
    Map<String, Set<String>> roles = new TreeMap<>();
    table.forEach(
        (signature, role) ->
            roles
                .computeIfAbsent(
                    signature.substring(0, signature.indexOf('(')), method -> new TreeSet<>())
                .add(role));
    Map<String, String> joined = new TreeMap<>();
    roles.forEach((method, values) -> joined.put(method, String.join(",", values)));
    return joined;
  }

  private static List<Map.Entry<Integer, Class<?>>> apiFilters(ArgosTestServer server) {
    return server.context().getBeansOfType(ApiFilter.class).values().stream()
        .sorted(Comparator.comparingInt(ApiFilter::position))
        .map(filter -> Map.<Integer, Class<?>>entry(filter.position(), filter.getClass()))
        .toList();
  }

  @SuppressWarnings("rawtypes") // the registrations differ in their filter type
  private static Map<String, Integer> servletFilterOrders(ArgosTestServer server) {
    Map<String, Integer> orders = new LinkedHashMap<>();
    for (FilterRegistrationBean registration :
        server.context().getBeansOfType(FilterRegistrationBean.class).values()) {
      orders.put(registration.getFilterName(), registration.getOrder());
    }
    return orders;
  }

  /** The handlers of WO-24, each with the role of 14 §4.1, §4.14 and 16 §7.10. */
  private static Map<String, String> expectedRoles() {
    Map<String, String> roles = new LinkedHashMap<>();
    String auth = "io.argos.server.auth.api.AuthController#";
    roles.put(auth + "csrf", "public");
    roles.put(auth + "login", "public");
    roles.put(auth + "refresh", "public");
    roles.put(auth + "logout", "viewer");
    roles.put(auth + "me", "viewer");
    roles.put(auth + "changePassword", "viewer");
    String setup = "io.argos.server.auth.api.SetupController#";
    roles.put(setup + "status", "public");
    roles.put(setup + "createAdmin", "public");
    String users = "io.argos.server.auth.api.AdminUsersController#";
    roles.put(users + "list", "admin");
    roles.put(users + "create", "admin");
    roles.put(users + "patch", "admin");
    roles.put(users + "revokeSessions", "admin");
    roles.put(users + "resetPassword", "admin");
    roles.put(users + "unlock", "admin");
    roles.put("io.argos.server.web.CspReportController#report", "public");
    roles.put("io.argos.server.web.IndexHtmlController#page", "public");
    roles.put("io.argos.server.api.error.ApiErrorController#error", "public");
    roles.put("org.springdoc.webmvc.api.OpenApiWebMvcResource#openapiJson", "viewer");
    roles.put("org.springdoc.webmvc.api.OpenApiWebMvcResource#openapiYaml", "viewer");
    return roles;
  }
}
