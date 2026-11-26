/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.web;

import io.argos.sample.boot4.config.ServletContainers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeHandler;

/**
 * GET /ws/echo: WebSocket upgrade (101) and text echo (18 §4.2, F-A34). On Jetty (SAMPLE_CONTAINER)
 * the handshake uses Jetty's upgrade strategy ({@link ServletContainers#HANDSHAKE_HANDLER}); on
 * Tomcat Spring's default handler.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class EchoWebSocketConfig implements WebSocketConfigurer {

  private final ObjectProvider<HandshakeHandler> handshakeHandler;

  public EchoWebSocketConfig(
      @Qualifier(ServletContainers.HANDSHAKE_HANDLER)
          ObjectProvider<HandshakeHandler> handshakeHandler) {
    this.handshakeHandler = handshakeHandler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    WebSocketHandlerRegistration registration = registry.addHandler(new EchoHandler(), "/ws/echo");
    HandshakeHandler handler = handshakeHandler.getIfAvailable();
    if (handler != null) {
      registration.setHandshakeHandler(handler);
    }
  }

  /** Sends every text message back unchanged. */
  static final class EchoHandler extends TextWebSocketHandler {
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message)
        throws java.io.IOException {
      session.sendMessage(new TextMessage(message.getPayload()));
    }
  }
}
