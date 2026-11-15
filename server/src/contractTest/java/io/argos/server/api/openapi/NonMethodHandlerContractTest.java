/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.auth.RoleMatrix;
import io.argos.server.auth.SelfAuthenticatingHandler;
import io.argos.server.bootstrap.StartupAbortedException;
import io.argos.server.testing.auth.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * {@code RoleMatrix} on a started web layer, for handlers that are not {@code @RequestMapping}
 * methods (DSN-12-11, SEC-49, WO-24 review r1): a bean whose name is a URL and a router function
 * cannot carry {@code @RequiresRole}, so they fail the start instead of serving anonymous requests;
 * a {@link SelfAuthenticatingHandler} outside {@code /api} is the one such handler that starts and
 * is reached (it answers for itself).
 */
@Tag("req:SEC-49")
@Tag("req:DSN-12-11")
class NonMethodHandlerContractTest {

  /** The reviewer's probe: a request handler bean named by its URL. */
  @Configuration(proxyBeanMethods = false)
  public static class BeanNameProbe {
    @Bean("/api/v1/review-probe")
    HttpRequestHandler reviewProbe() {
      return (request, response) -> response.setStatus(200);
    }
  }

  /** The reviewer's probe: a router function. */
  @Configuration(proxyBeanMethods = false)
  public static class RouterProbe {
    @Bean
    RouterFunction<ServerResponse> reviewRouter() {
      return RouterFunctions.route()
          .POST("/api/v1/review-router", request -> ServerResponse.ok().build())
          .build();
    }
  }

  /** A request handler outside /api without the marker. */
  @Configuration(proxyBeanMethods = false)
  public static class PlainOutsideApi {
    @Bean("/plain-probe")
    HttpRequestHandler plainProbe() {
      return (request, response) -> response.setStatus(200);
    }
  }

  /** A self-authenticating handler (the WO-22 hook) at a path outside /api. */
  public static class Answering implements HttpRequestHandler, SelfAuthenticatingHandler {
    @Override
    public void handleRequest(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
      response.setStatus(299);
      response.getOutputStream().write("self".getBytes(StandardCharsets.US_ASCII));
    }
  }

  /**
   * The marked handler at WO-22's path {@code /ws} (reserved from the UI's client routes, so {@code
   * IndexHtmlController} does not take it first).
   */
  @Configuration(proxyBeanMethods = false)
  public static class MarkedOutsideApi {
    @Bean("/ws")
    Answering wsProbe() {
      return new Answering();
    }
  }

  /** The marked handler under /api: not allowed either. */
  @Configuration(proxyBeanMethods = false)
  public static class MarkedUnderApi {
    @Bean("/api/v1/ws-probe")
    Answering wsProbe() {
      return new Answering();
    }
  }

  @Test
  @DisplayName(
      "T-SEC-49 a request handler bean named /api/v1/review-probe fails the start (it would serve"
          + " anonymous requests outside the matrix)")
  void beanNameHandlerFailsTheStart() {
    assertThatThrownBy(() -> ContractServer.start(BeanNameProbe.class))
        .isInstanceOf(StartupAbortedException.class)
        .hasMessageContaining("role-matrix")
        .hasMessageContaining("cannot carry @RequiresRole")
        .hasMessageContaining("/api/v1/review-probe -> ")
        .hasMessageContaining("beanNameHandlerMapping");
  }

  @Test
  @DisplayName("T-SEC-49 a router function (POST /api/v1/review-router) fails the start")
  void routerFunctionFailsTheStart() {
    assertThatThrownBy(() -> ContractServer.start(RouterProbe.class))
        .isInstanceOf(StartupAbortedException.class)
        .hasMessageContaining("role-matrix")
        .hasMessageContaining("router function")
        .hasMessageContaining("/api/v1/review-router");
  }

  @Test
  @DisplayName(
      "T-SEC-49 outside /api too: an unmarked request handler fails the start, a marked one under"
          + " /api as well")
  void unmarkedOrUnderApiFailsTheStart() {
    assertThatThrownBy(() -> ContractServer.start(PlainOutsideApi.class))
        .isInstanceOf(StartupAbortedException.class)
        .hasMessageContaining("/plain-probe -> ");
    assertThatThrownBy(() -> ContractServer.start(MarkedUnderApi.class))
        .isInstanceOf(StartupAbortedException.class)
        .hasMessageContaining("/api/v1/ws-probe -> " + Answering.class.getName());
  }

  @Test
  @DisplayName(
      "T-SEC-49 a SelfAuthenticatingHandler outside /api starts and is reached; the server's own"
          + " context refuses nothing")
  void markedHandlerStartsAndIsReached() {
    try (ContractServer server = ContractServer.start(MarkedOutsideApi.class)) {
      assertThat(server.context().getBean(RoleMatrix.class).refusedNonMethodHandlers()).isEmpty();
      ApiResponse response = server.client(null).get("/ws");
      assertThat(response.status()).as(response.toString()).isEqualTo(299);
      assertThat(response.body()).isEqualTo("self");
    }
    try (ContractServer server = ContractServer.start()) {
      assertThat(server.context().getBean(RoleMatrix.class).refusedNonMethodHandlers()).isEmpty();
    }
  }
}
