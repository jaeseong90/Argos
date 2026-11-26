/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.warjavax;

import io.argos.sample.common.datasource.WebAppCleanup;
import io.argos.sample.common.diag.RuntimeInfo;
import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What only the WAR adds to the shop-boot2 application (WO-50):
 *
 * <ul>
 *   <li>GET /api/jsp-forward: {@code RequestDispatcher.forward} to the WAR's JSP {@value
 *       RuntimeInfo#JSP_FORWARD_TARGET}, compiled by Tomcat's Jasper with the distribution's ecj
 *       (ADR-0010 C-162 (2); the shop catalog entry {@code jsp-forward} needs the capability {@code
 *       jsp} that GET /api/runtime reports when the JSP is there).
 *   <li>Shutdown cleanup (C-162 (1)): the JDBC drivers of WEB-INF/lib are deregistered when the
 *       context closes (after shop-boot2's in-memory H2 SHUTDOWN, {@code
 *       EmbeddedDatabaseShutdown}), so that an undeploy without an agent leaves no leak report.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class WarAppConfiguration {

  /** Deregisters the web app's JDBC drivers at context close. */
  @Bean(destroyMethod = "close")
  public DriverCleanup warDriverCleanup() {
    return new DriverCleanup();
  }

  /** GET /api/jsp-forward. */
  @RestController
  public static class JspForwardController {
    @GetMapping("/api/jsp-forward")
    public void forward(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
      request.getRequestDispatcher(RuntimeInfo.JSP_FORWARD_TARGET).forward(request, response);
    }
  }

  /** Context close: {@link WebAppCleanup#deregisterDrivers} for this web app's class loader. */
  public static final class DriverCleanup implements AutoCloseable {
    @Override
    public void close() {
      WebAppCleanup.deregisterDrivers(DriverCleanup.class.getClassLoader());
    }
  }
}
