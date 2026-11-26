/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.config;

import io.argos.sample.common.ServletContainer;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/**
 * SAMPLE_CONTAINER (WO-50, ADR-0010 C-127): starts shop-boot3 on Jetty 12 (ee10) or Undertow 2.3
 * instead of Spring Boot's default Tomcat 10.1. All three are on the jar's class path. An
 * initializer registers, before the context reads its configuration classes:
 *
 * <ul>
 *   <li>Jetty and Undertow: a {@code ServletWebServerFactory} bean of the container (Spring Boot's
 *       Tomcat factory backs off when one exists) and a {@code HandshakeHandler} with the
 *       container's upgrade strategy, {@value #HANDSHAKE_HANDLER}, used by {@code
 *       EchoWebSocketConfig} (Spring's default handler picks the Tomcat strategy when Tomcat is on
 *       the class path);
 *   <li>every container, Tomcat included: its WebSocket customizer under Spring Boot's bean name
 *       {@value #WEBSOCKET_CUSTOMIZER}. Spring Boot's three WebSocket configurations register
 *       theirs under that one name only when it is missing, so with all three on the class path the
 *       first one wins, whichever container runs.
 * </ul>
 *
 * With Tomcat the beans are those Spring Boot registers without Jetty and Undertow on the class
 * path. The container classes are referenced only from {@link TomcatContainer}, {@link
 * JettyContainer} and {@link UndertowContainer}, which load when selected. WAR deployments
 * (shop-war-jakarta) and shop-heavy do not call {@link #select}.
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
      case JETTY ->
          application.addInitializers(context -> JettyContainer.register(generic(context)));
      case UNDERTOW ->
          application.addInitializers(context -> UndertowContainer.register(generic(context)));
      case TOMCAT ->
          application.addInitializers(context -> TomcatContainer.register(generic(context)));
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
