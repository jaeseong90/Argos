/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.config;

import java.util.function.Supplier;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.standard.StandardWebSocketUpgradeStrategy;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * SAMPLE_CONTAINER unset or tomcat: the standard (JSR-356) upgrade strategy for {@code
 * EchoWebSocketConfig}, the one Spring Framework 7 picks on Tomcat. Its default handler prefers the
 * Jetty strategy whenever Jetty is on the class path, which fails on Tomcat.
 */
final class TomcatContainer {

  private TomcatContainer() {}

  static void register(GenericApplicationContext context) {
    Supplier<HandshakeHandler> handshake =
        () -> new DefaultHandshakeHandler(new StandardWebSocketUpgradeStrategy());
    context.registerBean(ServletContainers.HANDSHAKE_HANDLER, HandshakeHandler.class, handshake);
  }
}
