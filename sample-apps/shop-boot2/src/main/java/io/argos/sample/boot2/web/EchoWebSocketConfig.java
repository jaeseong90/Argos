/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.web;

import io.argos.sample.boot2.config.ServletContainers;
import java.io.IOException;
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
 * GET /ws/echo: WebSocket upgrade (101) and text echo (18 §4.2, F-A34). Embedded Tomcat 9 and the
 * standalone Tomcat 9 of shop-war-javax both provide the JSR-356 (javax.websocket) container. On
 * Jetty or Undertow (SAMPLE_CONTAINER) the handshake uses that container's upgrade strategy ({@link
 * ServletContainers#HANDSHAKE_HANDLER}).
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
        throws IOException {
      session.sendMessage(new TextMessage(message.getPayload()));
    }
  }
}
