/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.config;

import io.argos.sample.common.ServletContainer;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/**
 * SAMPLE_CONTAINER=jetty (WO-50, ADR-0010 C-127): starts shop-boot4 on Jetty 12.1 (ee11, Servlet
 * 6.1) instead of Spring Boot's default Tomcat 11. Spring Boot 4 has no Undertow support, so {@code
 * undertow} fails the start.
 *
 * <p>Spring Boot 4 configures each embedded server in its own auto-configuration ({@value
 * #TOMCAT_AUTO_CONFIGURATION}, {@value #JETTY_AUTO_CONFIGURATION}); with both on the class path the
 * first one in Spring Boot's order (Jetty's) would create the server factory and the WebSocket
 * customizer. The app therefore excludes the other container's auto-configuration ({@code
 * spring.autoconfigure.exclude} as a default property): with Tomcat the beans are those Spring Boot
 * creates without Jetty on the class path. An initializer also registers a {@code
 * HandshakeHandler}, {@value #HANDSHAKE_HANDLER}, for {@code EchoWebSocketConfig}: Spring Framework
 * 7's default handler picks the Jetty strategy whenever Jetty is on the class path, so Tomcat gets
 * the standard strategy ({@link TomcatContainer}, what the default handler picks without Jetty) and
 * Jetty its own with a server customizer ({@link JettyContainer}). The Jetty classes are referenced
 * only from {@link JettyContainer}.
 */
public final class ServletContainers {

  /** Spring Boot 4's auto-configuration of embedded Tomcat. */
  static final String TOMCAT_AUTO_CONFIGURATION =
      "org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration";

  /** Spring Boot 4's auto-configuration of embedded Jetty. */
  static final String JETTY_AUTO_CONFIGURATION =
      "org.springframework.boot.jetty.autoconfigure.servlet.JettyServletWebServerAutoConfiguration";

  /** Bean name of the WebSocket handshake handler of a selected container. */
  public static final String HANDSHAKE_HANDLER = "sampleWebSocketHandshakeHandler";

  private ServletContainers() {}

  /** Configures {@code application} for the container that {@code env} selects. */
  public static ServletContainer select(SpringApplication application, Map<String, String> env) {
    ServletContainer container = ServletContainer.fromEnvironment(env);
    switch (container) {
      case JETTY -> {
        application.setDefaultProperties(
            Map.of("spring.autoconfigure.exclude", TOMCAT_AUTO_CONFIGURATION));
        application.addInitializers(context -> JettyContainer.register(generic(context)));
      }
      case UNDERTOW ->
          throw new IllegalArgumentException(
              "SAMPLE_CONTAINER=undertow: Spring Boot 4 has no Undertow support (shop-boot4: tomcat,"
                  + " jetty)");
      case TOMCAT -> {
        application.setDefaultProperties(
            Map.of("spring.autoconfigure.exclude", JETTY_AUTO_CONFIGURATION));
        application.addInitializers(context -> TomcatContainer.register(generic(context)));
      }
    }
    return container;
  }

  private static GenericApplicationContext generic(ConfigurableApplicationContext context) {
    if (!(context instanceof GenericApplicationContext generic)) {
      throw new IllegalStateException(
          "SAMPLE_CONTAINER needs a GenericApplicationContext, got " + context.getClass());
    }
    return generic;
  }
}
