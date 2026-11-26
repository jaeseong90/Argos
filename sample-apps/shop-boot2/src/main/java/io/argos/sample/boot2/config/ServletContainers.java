/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.config;

import io.argos.sample.common.ServletContainer;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/**
 * SAMPLE_CONTAINER (WO-50, ADR-0010 C-127): starts shop-boot2 on Jetty 9.4 (Servlet 3.1, the lowest
 * servlet version of the agent's range, C-240 (10)) or Undertow 2.2 instead of Spring Boot's
 * default Tomcat 9. All three are on the jar's class path. An initializer registers, before the
 * context reads its configuration classes: for every container, Tomcat included, its WebSocket
 * customizer under Spring Boot's bean name {@value #WEBSOCKET_CUSTOMIZER} (Spring Boot's three
 * WebSocket configurations register theirs under that one name only when it is missing, so with all
 * three on the class path the first one wins, whichever container runs); for Jetty and Undertow
 * also a {@code ServletWebServerFactory} bean of the container (Spring Boot's Tomcat factory backs
 * off) and a {@code HandshakeHandler} with the container's upgrade strategy, {@value
 * #HANDSHAKE_HANDLER}, for {@code EchoWebSocketConfig}. With Tomcat the beans are those Spring Boot
 * registers without Jetty and Undertow on the class path. The container classes are referenced only
 * from {@link TomcatContainer}, {@link JettyContainer} and {@link UndertowContainer}.
 * shop-war-javax does not call {@link #select}.
 */
public final class ServletContainers {

  /** Bean name of the container's {@code ServletWebServerFactory}. */
  static final String FACTORY = "sampleServletWebServerFactory";

  /** Spring Boot's bean name of the WebSocket customizer of the embedded container. */
  static final String WEBSOCKET_CUSTOMIZER = "websocketServletWebServerCustomizer";

  /** Bean name of the WebSocket handshake handler of a selected container. */
  public static final String HANDSHAKE_HANDLER = "sampleWebSocketHandshakeHandler";

  private ServletContainers() {}

  /** Adds the beans of the container that {@code env} selects. */
  public static ServletContainer select(SpringApplication application, Map<String, String> env) {
    ServletContainer container = ServletContainer.fromEnvironment(env);
    switch (container) {
      case JETTY:
        application.addInitializers(context -> JettyContainer.register(generic(context)));
        break;
      case UNDERTOW:
        application.addInitializers(context -> UndertowContainer.register(generic(context)));
        break;
      case TOMCAT:
        application.addInitializers(context -> TomcatContainer.register(generic(context)));
        break;
    }
    return container;
  }

  private static GenericApplicationContext generic(ConfigurableApplicationContext context) {
    if (!(context instanceof GenericApplicationContext)) {
      throw new IllegalStateException(
          "SAMPLE_CONTAINER needs a GenericApplicationContext, got " + context.getClass());
    }
    return (GenericApplicationContext) context;
  }
}
