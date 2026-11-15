/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.servertest.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Test-only evidence that a request passed every filter and the role check and reached its handler:
 * an interceptor after all others that sets {@value #HEADER}. A 403 without the header comes from
 * the authorisation layer; a 401 or 403 with it comes from the handler itself (a password or token
 * it checks). Added to an application explicitly, like {@link HeaderAuthentication}.
 */
public class HandlerReachedMarker implements WebMvcConfigurer {

  /** Present on the response when the handler was reached. */
  public static final String HEADER = "X-Test-Handler-Reached";

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (handler instanceof HandlerMethod) {
                  response.setHeader(HEADER, "true");
                }
                return true;
              }
            })
        .order(Ordered.LOWEST_PRECEDENCE);
  }
}
