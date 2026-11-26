/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.warjakarta;

import io.argos.sample.common.datasource.SampleDataSource;
import io.argos.sample.common.datasource.WebAppCleanup;
import io.argos.sample.common.diag.RuntimeInfo;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What only the WAR adds to the shop-boot3 application (WO-50):
 *
 * <ul>
 *   <li>GET /api/jsp-forward: {@code RequestDispatcher.forward} to the WAR's JSP {@value
 *       RuntimeInfo#JSP_FORWARD_TARGET}, compiled by Tomcat's Jasper with the distribution's ecj
 *       (ADR-0010 C-162 (2); the shop catalog entry {@code jsp-forward} needs the capability {@code
 *       jsp} that GET /api/runtime reports when the JSP is there).
 *   <li>Shutdown cleanup (C-162 (1)), so that an undeploy without an agent leaves no leak report in
 *       the standalone Tomcat: SHUTDOWN of the in-memory H2 database and deregistration of the JDBC
 *       drivers of WEB-INF/lib at context close, and Netty's thread-local map (Reactor Netty of the
 *       WebClient) removed from Tomcat's request threads at the end of every dispatch.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class WarAppConfiguration {

  /** H2 SHUTDOWN and driver deregistration at context close. */
  @Bean(destroyMethod = "close")
  public JdbcCleanup warJdbcCleanup(SampleDataSource dataSource) {
    return new JdbcCleanup(dataSource);
  }

  /** Clears Netty's thread-local map of the container thread after each dispatch. */
  @Bean
  public FilterRegistrationBean<NettyThreadLocalFilter> nettyThreadLocalFilter() {
    FilterRegistrationBean<NettyThreadLocalFilter> registration =
        new FilterRegistrationBean<>(new NettyThreadLocalFilter());
    registration.setName("argosSampleNettyThreadLocalFilter");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.setAsyncSupported(true);
    registration.setDispatcherTypes(
        DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
    registration.addUrlPatterns("/*");
    return registration;
  }

  /**
   * Clears Netty's thread-local map of the thread that deployed the web app once the context is up
   * (Reactor Netty's WebClient resources are created during the deployment, on Tomcat's deploy
   * thread).
   */
  @Bean
  public ApplicationListener<ContextRefreshedEvent> nettyThreadLocalsAfterStartup() {
    return event -> WebAppCleanup.clearNettyThreadLocals();
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

  /**
   * Context close: SHUTDOWN of the in-memory H2 database (it is destroyed before the data source,
   * which it depends on), then the web app's JDBC drivers leave DriverManager.
   */
  public static final class JdbcCleanup implements AutoCloseable {
    private final SampleDataSource dataSource;

    JdbcCleanup(SampleDataSource dataSource) {
      this.dataSource = dataSource;
    }

    @Override
    public void close() {
      WebAppCleanup.shutdownInMemoryH2(dataSource);
      WebAppCleanup.deregisterDrivers(JdbcCleanup.class.getClassLoader());
      // The undeploying thread (Tomcat's main thread at shutdown) may hold Netty's map too.
      WebAppCleanup.clearNettyThreadLocals();
    }
  }

  /** Removes Netty's thread-local map of the calling thread after the dispatch. */
  public static final class NettyThreadLocalFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
      try {
        chain.doFilter(request, response);
      } finally {
        WebAppCleanup.clearNettyThreadLocals();
      }
    }
  }
}
