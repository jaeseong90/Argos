/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.server.auth.session.PublicOrigin;
import io.argos.server.auth.session.SessionAuthenticator;
import java.time.Clock;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;

/**
 * The WebSocket endpoint {@value #PATH} (14 §5, 12 §3.7): a plain {@code WebSocketHandler}, no
 * STOMP. The URL mapping is declared here instead of with {@code @EnableWebSocket}, whose handler
 * would be Spring's unmarked {@code WebSocketHttpRequestHandler}: the server accepts a handler that
 * is not a {@code @RequestMapping} method only when it is a {@code SelfAuthenticatingHandler}
 * (DSN-12-11, {@code RoleMatrix}).
 *
 * <p>The path is outside {@code /api}: it passes the default security chain (no API filters, no
 * CSRF check; the handshake authenticates itself) and gets the security headers of every response.
 */
@Configuration(proxyBeanMethods = false)
public class WebSocketConfig {

  /** The endpoint (14 §5.1). */
  public static final String PATH = "/ws";

  @Bean
  ArgosWebSocketRequestHandler argosWebSocketRequestHandler(
      WsConnectionRegistry registry,
      SubscriptionManager subscriptions,
      Clock clock,
      PublicOrigin origin,
      SessionAuthenticator authenticator) {
    return new ArgosWebSocketRequestHandler(
        new RealtimeWebSocketHandler(registry, subscriptions, clock),
        new WsHandshakeInterceptor(origin, authenticator));
  }

  /** {@value #PATH} to {@link ArgosWebSocketRequestHandler}, ahead of the other URL mappings. */
  @Bean
  WebSocketHandlerMapping argosWebSocketHandlerMapping(ArgosWebSocketRequestHandler handler) {
    WebSocketHandlerMapping mapping = new WebSocketHandlerMapping();
    mapping.setUrlMap(Map.of(PATH, handler));
    mapping.setOrder(1);
    return mapping;
  }
}
