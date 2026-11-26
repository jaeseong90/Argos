/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.config;

import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.websocket.servlet.UndertowWebSocketServletWebServerCustomizer;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.standard.StandardWebSocketUpgradeStrategy;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * SAMPLE_CONTAINER=undertow: Undertow 2.3 beans of {@link ServletContainers}. The handshake uses
 * Spring's standard upgrade strategy (Jakarta WebSocket 2.1 {@code upgradeHttpToWebSocket}), which
 * replaces the deprecated Undertow-specific one of Spring 6.
 */
final class UndertowContainer {

  private UndertowContainer() {}

  static void register(GenericApplicationContext context) {
    Supplier<ServletWebServerFactory> factory = UndertowServletWebServerFactory::new;
    Supplier<UndertowWebSocketServletWebServerCustomizer> webSocket =
        UndertowWebSocketServletWebServerCustomizer::new;
    Supplier<HandshakeHandler> handshake =
        () -> new DefaultHandshakeHandler(new StandardWebSocketUpgradeStrategy());
    context.registerBean(ServletContainers.FACTORY, ServletWebServerFactory.class, factory);
    context.registerBean(
        ServletContainers.WEBSOCKET_CUSTOMIZER,
        UndertowWebSocketServletWebServerCustomizer.class,
        webSocket);
    context.registerBean(ServletContainers.HANDSHAKE_HANDLER, HandshakeHandler.class, handshake);
  }
}
