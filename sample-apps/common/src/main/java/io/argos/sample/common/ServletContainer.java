/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common;

import java.util.Locale;
import java.util.Map;

/**
 * SAMPLE_CONTAINER (WO-50, ADR-0010 C-127): the embedded servlet container a shop jar app starts
 * on. Tomcat is Spring Boot's default and needs nothing; Jetty and Undertow are selected through a
 * {@code ServletWebServerFactory} bean that the app registers before its context starts
 * (shop-boot2: Jetty 9.4 and Undertow 2.2, shop-boot3: Jetty 12 ee10 and Undertow 2.3, shop-boot4:
 * Jetty 12.1 ee11). WAR apps run in the standalone Tomcat and ignore the switch.
 */
public enum ServletContainer {
  TOMCAT("tomcat"),
  JETTY("jetty"),
  UNDERTOW("undertow");

  /** The environment variable. */
  public static final String VARIABLE = "SAMPLE_CONTAINER";

  private final String id;

  ServletContainer(String id) {
    this.id = id;
  }

  /** The SAMPLE_CONTAINER value. */
  public String id() {
    return id;
  }

  /** The container of {@code env} (TOMCAT when SAMPLE_CONTAINER is unset or blank). */
  public static ServletContainer fromEnvironment(Map<String, String> env) {
    String value = env.get(VARIABLE);
    return value == null || value.trim().isEmpty() ? TOMCAT : parse(value);
  }

  /** Parses a SAMPLE_CONTAINER value; an unknown value names the variable. */
  public static ServletContainer parse(String value) {
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    for (ServletContainer container : values()) {
      if (container.id.equals(normalized)) {
        return container;
      }
    }
    throw new IllegalArgumentException(
        VARIABLE + " must be one of tomcat, jetty, undertow, got '" + value + "'");
  }

  /**
   * The container that really serves a request, from {@code ServletContext.getServerInfo()} ({@code
   * Apache Tomcat/10.1.x}, {@code jetty/12.0.x}, {@code Undertow - 2.3.x.Final}), or "other".
   */
  public static String kindOf(String serverInfo) {
    if (serverInfo == null) {
      return "other";
    }
    String lower = serverInfo.toLowerCase(Locale.ROOT);
    for (ServletContainer container : values()) {
      if (lower.contains(container.id)) {
        return container.id;
      }
    }
    return "other";
  }
}
