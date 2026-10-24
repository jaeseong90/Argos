/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.web;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * GET /ws/echo: WebSocket upgrade (101) and text echo (18 §4.2, F-A34). Embedded Tomcat 9 and the
 * standalone Tomcat 9 of shop-war-javax both provide the JSR-356 (javax.websocket) container.
 */
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
        throws IOException {
      session.sendMessage(new TextMessage(message.getPayload()));
    }
  }
}
