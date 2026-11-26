/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.warjakarta;

import io.argos.sample.boot3.ShopBoot3Application;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * shop-war-jakarta: the shop-boot3 app as a WAR for a standalone Tomcat 10.1 or 11 (18 §4.1). The
 * container finds this class through Spring's {@code SpringServletContainerInitializer}; every web
 * application deployed from the WAR (/shop-a, /shop-b) starts its own Spring context with its own
 * class loader, data source and in-memory database.
 */
public class ServletInitializer extends SpringBootServletInitializer {

  /** App name of the WAR (build-info.properties says the same; this one is for log lines). */
  static final String APP_NAME = "shop-war-jakarta";

  /**
   * Names the Spring application after the WAR and its context path ({@code
   * shop-war-jakarta/shop-a}), so that the log lines of the two web applications, which share one
   * Tomcat console, can be told apart. A servlet context init parameter outranks the shop-boot3
   * application.properties ({@code spring.application.name=${SAMPLE_APP_NAME:shop-boot3}}), so an
   * explicit SAMPLE_APP_NAME keeps precedence by skipping this.
   */
  @Override
  public void onStartup(ServletContext servletContext) throws ServletException {
    if (System.getenv("SAMPLE_APP_NAME") == null) {
      servletContext.setInitParameter(
          "spring.application.name", APP_NAME + servletContext.getContextPath());
    }
    super.onStartup(servletContext);
  }

  @Override
  protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
    return builder.sources(ShopBoot3Application.class, WarAppConfiguration.class);
  }
}
