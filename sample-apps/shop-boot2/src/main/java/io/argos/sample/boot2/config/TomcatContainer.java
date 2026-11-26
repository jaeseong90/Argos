/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.config;

import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.websocket.servlet.TomcatWebSocketServletWebServerCustomizer;
import org.springframework.context.support.GenericApplicationContext;

/**
 * SAMPLE_CONTAINER unset or tomcat: Spring Boot's own Tomcat factory, and its Tomcat WebSocket
 * customizer registered by name. Without Jetty and Undertow on the class path Spring Boot registers
 * the same customizer itself; with them its auto-configuration picks one of the three WebSocket
 * customizers under the one bean name {@value ServletContainers#WEBSOCKET_CUSTOMIZER}, not always
 * Tomcat's (GET /ws/echo would then fail with no JSR-356 container).
 */
final class TomcatContainer {

  private TomcatContainer() {}

  static void register(GenericApplicationContext context) {
    Supplier<TomcatWebSocketServletWebServerCustomizer> webSocket =
        TomcatWebSocketServletWebServerCustomizer::new;
    context.registerBean(
        ServletContainers.WEBSOCKET_CUSTOMIZER,
        TomcatWebSocketServletWebServerCustomizer.class,
        webSocket);
  }
}
