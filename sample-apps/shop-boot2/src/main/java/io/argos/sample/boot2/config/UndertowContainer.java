/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.config;

import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.websocket.servlet.UndertowWebSocketServletWebServerCustomizer;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.standard.UndertowRequestUpgradeStrategy;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/** SAMPLE_CONTAINER=undertow: Undertow 2.2 beans of {@link ServletContainers}. */
final class UndertowContainer {

  private UndertowContainer() {}

  static void register(GenericApplicationContext context) {
    Supplier<ServletWebServerFactory> factory = UndertowServletWebServerFactory::new;
    Supplier<UndertowWebSocketServletWebServerCustomizer> webSocket =
        UndertowWebSocketServletWebServerCustomizer::new;
    Supplier<HandshakeHandler> handshake =
        () -> new DefaultHandshakeHandler(new UndertowRequestUpgradeStrategy());
    context.registerBean(ServletContainers.FACTORY, ServletWebServerFactory.class, factory);
    context.registerBean(
        ServletContainers.WEBSOCKET_CUSTOMIZER,
        UndertowWebSocketServletWebServerCustomizer.class,
        webSocket);
    context.registerBean(ServletContainers.HANDSHAKE_HANDLER, HandshakeHandler.class, handshake);
  }
}
