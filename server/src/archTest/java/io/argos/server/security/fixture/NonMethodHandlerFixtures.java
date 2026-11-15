/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.fixture;

import io.argos.server.auth.SelfAuthenticatingHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.mvc.Controller;

/**
 * Negative fixtures of the non-method handler rules (never production code): handlers that cannot
 * carry {@code @RequiresRole}, and the one kind that may exist ({@link Marked}).
 */
public final class NonMethodHandlerFixtures {

  private NonMethodHandlerFixtures() {}

  /** Violates: an {@code HttpRequestHandler} without the marker. */
  public static class PlainHttpRequestHandler implements HttpRequestHandler {
    @Override
    public void handleRequest(HttpServletRequest request, HttpServletResponse response) {}
  }

  /** Violates: an MVC {@code Controller} without the marker. */
  public static class PlainMvcController implements Controller {
    @Override
    public ModelAndView handleRequest(HttpServletRequest request, HttpServletResponse response) {
      return null;
    }
  }

  /** Allowed: authenticates every request itself. */
  public static class Marked implements HttpRequestHandler, SelfAuthenticatingHandler {
    @Override
    public void handleRequest(HttpServletRequest request, HttpServletResponse response) {}
  }

  /** Bean methods: a router function, a lambda request handler, and the allowed marked one. */
  public static class Beans {

    /** Violates: a router function. */
    @Bean
    public RouterFunction<ServerResponse> reviewRouter() {
      return RouterFunctions.route()
          .POST("/api/v1/review-router", request -> ServerResponse.ok().build())
          .build();
    }

    /** Violates: a URL-named request handler bean (a lambda has no class of its own to check). */
    @Bean("/api/v1/review-probe")
    public HttpRequestHandler reviewProbe() {
      return (request, response) -> response.setStatus(200);
    }

    /** Allowed: the marked handler. */
    @Bean("/ws-fixture")
    public Marked markedHandler() {
      return new Marked();
    }

    /** Not a handler at all. */
    @Bean
    public String unrelated() {
      return "";
    }
  }
}
