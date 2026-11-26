/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.config;

import java.util.function.Supplier;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.springframework.boot.jetty.JettyServerCustomizer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.jetty.JettyRequestUpgradeStrategy;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * SAMPLE_CONTAINER=jetty: the beans of {@link ServletContainers} next to Spring Boot's Jetty 12.1
 * (ee11) auto-configuration, which creates the server factory and the WebSocket customizer.
 */
final class JettyContainer {

  /** Bean name of the server customizer below. */
  static final String URI_CUSTOMIZER = "sampleJettyUriCompliance";

  private JettyContainer() {}

  static void register(GenericApplicationContext context) {
    Supplier<HandshakeHandler> handshake =
        () -> new DefaultHandshakeHandler(new JettyRequestUpgradeStrategy());
    Supplier<JettyServerCustomizer> uris = () -> JettyContainer::legacyUris;
    context.registerBean(ServletContainers.HANDSHAKE_HANDLER, HandshakeHandler.class, handshake);
    context.registerBean(URI_CUSTOMIZER, JettyServerCustomizer.class, uris);
  }

  /**
   * Jetty with Tomcat's URI handling: the seed paths of 18 §4.3 contain empty segments ({@code
   * //}), which Jetty's default URI compliance answers with 400 and Tomcat accepts.
   */
  private static void legacyUris(Server server) {
    for (Connector connector : server.getConnectors()) {
      HttpConnectionFactory http = connector.getConnectionFactory(HttpConnectionFactory.class);
      if (http != null) {
        http.getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);
      }
    }
  }
}
