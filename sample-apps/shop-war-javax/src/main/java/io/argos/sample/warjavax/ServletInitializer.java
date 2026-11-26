/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.warjavax;

import io.argos.sample.boot2.ShopBoot2Application;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * shop-war-javax: the shop-boot2 application as a WAR in a standalone Tomcat 9 (javax Servlet 4.0),
 * deployed under a non-root context path ({@code /shop-war-javax}: the context path prefix of the
 * service name, F-A36). Tomcat finds this class through Spring's ServletContainerInitializer. The
 * name and version in GET /api/version come from this WAR's own build-info (WEB-INF/classes is
 * searched before WEB-INF/lib).
 */
public class ServletInitializer extends SpringBootServletInitializer {

  @Override
  protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
    return builder.sources(ShopBoot2Application.class, WarAppConfiguration.class);
  }
}
