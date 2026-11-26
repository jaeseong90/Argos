/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.config;

import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.websocket.servlet.JettyWebSocketServletWebServerCustomizer;
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.jetty.JettyRequestUpgradeStrategy;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/** SAMPLE_CONTAINER=jetty: Jetty 9.4 beans of {@link ServletContainers}. */
final class JettyContainer {

  private JettyContainer() {}

  static void register(GenericApplicationContext context) {
    Supplier<ServletWebServerFactory> factory = JettyServletWebServerFactory::new;
    Supplier<JettyWebSocketServletWebServerCustomizer> webSocket =
        JettyWebSocketServletWebServerCustomizer::new;
    Supplier<HandshakeHandler> handshake =
        () -> new DefaultHandshakeHandler(new JettyRequestUpgradeStrategy());
    context.registerBean(ServletContainers.FACTORY, ServletWebServerFactory.class, factory);
    context.registerBean(
        ServletContainers.WEBSOCKET_CUSTOMIZER,
        JettyWebSocketServletWebServerCustomizer.class,
        webSocket);
    context.registerBean(ServletContainers.HANDSHAKE_HANDLER, HandshakeHandler.class, handshake);
  }
}
