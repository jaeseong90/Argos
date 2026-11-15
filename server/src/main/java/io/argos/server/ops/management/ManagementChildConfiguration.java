/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.management;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Beans of the management web server (the child context that Spring Boot starts for {@code
 * management.server.port}, 02 C-7): the {@link ManagementAccessFilter}. The servlet filters of the
 * HTTP port are not applied to the management port, and the other way round.
 *
 * <p>Registered through {@code
 * META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports}
 * and deliberately not annotated: a {@code @ManagementContextConfiguration} class is a
 * {@code @Configuration} and would also be picked up by the component scan of the main context.
 * Without the annotation Spring Boot imports it into the management context only (context type
 * {@code ANY}: the child context when the management port is separate, which it always is here).
 */
public class ManagementChildConfiguration {

  /**
   * The access filter. Without a {@link MetricsAccessPolicy} bean (an application built from parts
   * of the server, e.g. a test application) info and prometheus refuse every request.
   */
  @Bean
  FilterRegistrationBean<ManagementAccessFilter> argosManagementAccessFilter(
      ObjectProvider<MetricsAccessPolicy> policy,
      @Value("${management.endpoints.web.base-path:/actuator}") String basePath) {
    FilterRegistrationBean<ManagementAccessFilter> registration =
        new FilterRegistrationBean<>(new ManagementAccessFilter(policy.getIfAvailable(), basePath));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.setName("argosManagementAccessFilter");
    return registration;
  }
}
