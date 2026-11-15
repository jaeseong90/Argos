/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web;

import io.argos.server.config.ServerBuild;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Servlet filters of the HTTP port (8080), in this order: {@link RequestIdFilter} first (every
 * later filter, including the authentication and CSRF filters of WO-24, logs with the request id),
 * then {@link RequestUrlLimitFilter} and {@link RequestBodyLimitFilter} (14 §7.2). Connector
 * limits, error-page rules, virtual threads and graceful shutdown are set in {@code
 * application.yml}. The management port (8081) has its own filters ({@link
 * io.argos.server.ops.management.ManagementChildConfiguration}).
 */
@Configuration(proxyBeanMethods = false)
public class WebConfiguration {

  /** Order of {@link RequestIdFilter}: before every other filter. */
  public static final int REQUEST_ID_ORDER = Ordered.HIGHEST_PRECEDENCE;

  /** Order of {@link RequestUrlLimitFilter}: right after the request id. */
  public static final int URL_LIMIT_ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

  /** Order of {@link RequestBodyLimitFilter}: after the URL limit. */
  public static final int BODY_LIMIT_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

  @Bean
  FilterRegistrationBean<RequestIdFilter> requestIdFilter(ServerBuild build) {
    FilterRegistrationBean<RequestIdFilter> registration =
        new FilterRegistrationBean<>(new RequestIdFilter(new RequestIds(), build));
    registration.setOrder(REQUEST_ID_ORDER);
    registration.setName("argosRequestIdFilter");
    // The error page of a failed request keeps its id in the MDC.
    registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
    return registration;
  }

  @Bean
  FilterRegistrationBean<RequestUrlLimitFilter> requestUrlLimitFilter() {
    FilterRegistrationBean<RequestUrlLimitFilter> registration =
        new FilterRegistrationBean<>(new RequestUrlLimitFilter());
    registration.setOrder(URL_LIMIT_ORDER);
    registration.setName("argosRequestUrlLimitFilter");
    return registration;
  }

  @Bean
  FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter() {
    FilterRegistrationBean<RequestBodyLimitFilter> registration =
        new FilterRegistrationBean<>(new RequestBodyLimitFilter());
    registration.setOrder(BODY_LIMIT_ORDER);
    registration.setName("argosRequestBodyLimitFilter");
    return registration;
  }
}
