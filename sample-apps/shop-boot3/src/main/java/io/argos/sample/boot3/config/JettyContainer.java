/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.config;

import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.springframework.boot.autoconfigure.websocket.servlet.JettyWebSocketServletWebServerCustomizer;
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.jetty.JettyRequestUpgradeStrategy;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/** SAMPLE_CONTAINER=jetty: Jetty 12 (ee10) beans of {@link ServletContainers}. */
final class JettyContainer {

  private JettyContainer() {}

  static void register(GenericApplicationContext context) {
    context.registerBean(
        ServletContainers.FACTORY, ServletWebServerFactory.class, JettyContainer::factory);
    context.registerBean(
        ServletContainers.WEBSOCKET_CUSTOMIZER,
        JettyWebSocketServletWebServerCustomizer.class,
        JettyWebSocketServletWebServerCustomizer::new);
    context.registerBean(
        ServletContainers.HANDSHAKE_HANDLER,
        HandshakeHandler.class,
        () -> new DefaultHandshakeHandler(new JettyRequestUpgradeStrategy()));
  }

  /**
   * Jetty with Tomcat's URI handling: the seed paths of 18 §4.3 contain empty segments ({@code
   * //}), which Jetty's default URI compliance answers with 400 and Tomcat accepts.
   */
  static ServletWebServerFactory factory() {
    JettyServletWebServerFactory factory = new JettyServletWebServerFactory();
    factory.addServerCustomizers(JettyContainer::legacyUris);
    return factory;
  }

  private static void legacyUris(Server server) {
    for (Connector connector : server.getConnectors()) {
      HttpConnectionFactory http = connector.getConnectionFactory(HttpConnectionFactory.class);
      if (http != null) {
        http.getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);
      }
    }
  }
}
