/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** GET /ws/echo: WebSocket upgrade (101) and text echo (18 §4.2, F-A34). */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class EchoWebSocketConfig implements WebSocketConfigurer {

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(new EchoHandler(), "/ws/echo");
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
