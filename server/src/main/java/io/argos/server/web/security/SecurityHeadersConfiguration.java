/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link SecurityHeadersFilter} on the HTTP port for every path, at {@link
 * FilterOrder#SECURITY_HEADERS}, for requests, error dispatches and async dispatches (16 §9.8,
 * SEC-45). The management port (8081) has its own servlet context and filters (WO-20).
 *
 * <p>The URL and body limit filters of WO-20 run earlier in {@link FilterOrder} and answer without
 * calling the chain, so {@link SecurityHeadersFilter.Early} sets the same headers right after the
 * request id; those 400 responses carry them too.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityHeadersConfiguration {

  @Bean
  FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
    FilterRegistrationBean<SecurityHeadersFilter> registration =
        new FilterRegistrationBean<>(new SecurityHeadersFilter());
    registration.setOrder(FilterOrder.SECURITY_HEADERS);
    registration.setName("argosSecurityHeadersFilter");
    registration.setDispatcherTypes(
        DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC);
    return registration;
  }

  @Bean
  FilterRegistrationBean<SecurityHeadersFilter.Early> earlySecurityHeadersFilter() {
    FilterRegistrationBean<SecurityHeadersFilter.Early> registration =
        new FilterRegistrationBean<>(new SecurityHeadersFilter.Early());
    registration.setOrder(FilterOrder.SECURITY_HEADERS_EARLY);
    registration.setName("argosEarlySecurityHeadersFilter");
    registration.setDispatcherTypes(DispatcherType.REQUEST);
    return registration;
  }
}
