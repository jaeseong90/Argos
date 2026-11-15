/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import io.argos.server.api.error.ErrorBodyWriter;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.AuthenticationFailure;
import io.argos.server.auth.HandlerRoles;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.RoleAuthorizationInterceptor;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Security of the HTTP port (WO-24; 12 §7.2-§7.4, 16 §7, DSN-12-10, DSN-12-11). Spring
 * Security provides the filter chain, the security context and the anonymous principal only; the
 * Argos filters decide authentication and CSRF, and {@link RequiresRole} decides authorisation.
 *
 * <p>Two chains, both stateless:
 *
 * <ol>
 *   <li><b>{@value #API_PATTERN}</b> ({@link #apiSecurityFilterChain}): every {@link ApiFilter}
 *       bean in {@link FilterOrder} position (IP rate limit, access-token authentication, CSRF,
 *       user rate limit, password-change gate), then Spring's anonymous filter (requests without a
 *       valid session are anonymous; {@code PUBLIC} handlers serve them). Authorisation per handler
 *       by {@link RoleAuthorizationInterceptor} (no URL pattern matrix).
 *   <li><b>everything else</b> ({@link #defaultSecurityFilterChain}): the SPA ({@code index.html},
 *       client routes), static files, the error page; no authentication. The management port (8081)
 *       passes this chain too (Spring Boot registers the same filter there) and keeps its own
 *       bearer filter (SEC-55, WO-20).
 * </ol>
 *
 * In both chains Spring's own mechanisms are off: no HTTP session ({@code STATELESS}, never a
 * {@code JSESSIONID}), no Spring CSRF token repository (Argos' CSRF filter, 16 §7.6), no form
 * login, HTTP basic, logout or remember-me endpoints, no saved requests, no Spring header writer
 * (the security headers filter owns every header, 16 §9.8). Authentication and access-denied
 * outcomes of Spring Security, and requests its firewall rejects, are answered with the {@code
 * ErrorBody} of 14 §2.6.
 *
 * <p>Later work orders add filters as {@link ApiFilter} beans and handlers with {@link
 * RequiresRole}; this class does not change.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig implements WebMvcConfigurer {

  /** Paths of the REST API (14 §2.1). */
  public static final String API_PATTERN = "/api/**";

  private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

  private final HandlerRoles handlerRoles;
  private final ApplicationContext context;

  public SecurityConfig(HandlerRoles handlerRoles, ApplicationContext context) {
    this.handlerRoles = handlerRoles;
    this.context = context;
  }

  /** The {@value #API_PATTERN} chain: Argos API filters, anonymous principal, ErrorBody errors. */
  @Bean
  @Order(1)
  SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, ObjectProvider<ApiFilter> filters)
      throws Exception {
    List<ApiFilter> apiFilters = filters.orderedStream().toList();
    ApiFilterChain chain = new ApiFilterChain(apiFilters);
    LOG.debug(
        "API filters: {}",
        chain.filters().stream()
            .map(f -> f.position() + " " + f.getClass().getSimpleName())
            .toList());
    http.securityMatcher(API_PATTERN);
    stateless(http);
    http.addFilterBefore(chain, AnonymousAuthenticationFilter.class);
    return http.build();
  }

  /** Every other path (SPA, static files, error page, management port): no authentication. */
  @Bean
  @Order(2)
  SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
    stateless(http);
    return http.build();
  }

  /**
   * Requests rejected by Spring Security's firewall (malformed URLs): 400 {@code invalid_request}.
   */
  @Bean
  WebSecurityCustomizer argosFirewallErrors() {
    RequestRejectedHandler rejected =
        (request, response, exception) -> {
          LOG.debug("Request rejected by the firewall: {}", exception.getClass().getSimpleName());
          ErrorBodyWriter.write(request, response, ErrorCode.INVALID_REQUEST);
        };
    return web -> web.requestRejectedHandler(rejected);
  }

  /**
   * Argos never authenticates through Spring's {@code AuthenticationManager} (no form login, no
   * basic): this one refuses everything. Its presence also keeps Spring Boot's in-memory user and
   * the "generated security password" log line away.
   */
  @Bean
  AuthenticationManager argosNoAuthenticationManager() {
    return authentication -> {
      throw new ProviderNotFoundException("Argos authenticates with session cookies only");
    };
  }

  /** {@link RoleAuthorizationInterceptor} before every handler of this context. */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new RoleAuthorizationInterceptor(handlerRoles, context))
        .order(Ordered.HIGHEST_PRECEDENCE);
  }

  /** The settings both chains share. */
  private static void stateless(HttpSecurity http) throws Exception {
    http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(c -> c.disable())
        .formLogin(c -> c.disable())
        .httpBasic(c -> c.disable())
        .logout(c -> c.disable())
        .rememberMe(c -> c.disable())
        .requestCache(c -> c.disable())
        .headers(c -> c.disable())
        .anonymous(Customizer.withDefaults())
        .exceptionHandling(
            e -> e.authenticationEntryPoint(UNAUTHENTICATED).accessDeniedHandler(ACCESS_DENIED))
        // Authorisation is per handler (@RequiresRole, RoleAuthorizationInterceptor), DSN-12-11.
        .authorizeHttpRequests(a -> a.anyRequest().permitAll());
  }

  /** Spring Security found no authentication where one was needed: 401. */
  static final AuthenticationEntryPoint UNAUTHENTICATED =
      (request, response, exception) ->
          ErrorBodyWriter.write(request, response, AuthenticationFailure.of(request));

  /** Spring Security denied an authenticated request: 403 {@code forbidden}. */
  static final AccessDeniedHandler ACCESS_DENIED =
      (request, response, exception) ->
          ErrorBodyWriter.write(request, response, ErrorCode.FORBIDDEN);
}
