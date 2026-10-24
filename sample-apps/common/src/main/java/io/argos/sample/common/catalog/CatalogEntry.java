/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One request of an endpoint catalog (18 §4.2, §4.3): what to send, what must come back, and why
 * the endpoint exists. Shared by every app of the catalog, the smoke runner, and the agent tests
 * (route = expected service name pattern).
 */
public final class CatalogEntry {

  /** How the request is made. */
  public enum Protocol {
    HTTP,
    /** GET with {@code Accept: text/event-stream}, body read to the end. */
    SSE,
    /** WebSocket handshake (101) and one echo. */
    WEBSOCKET
  }

  /**
   * When the smoke runs the entry: SEQUENTIAL one after another, CONCURRENT in parallel with the
   * sequential ones (long but independent calls), EXCLUSIVE alone at the end (pool exhaustion would
   * slow every other database call).
   */
  public enum Phase {
    SEQUENTIAL,
    CONCURRENT,
    EXCLUSIVE
  }

  private final String id;
  private final String method;
  private final String path;
  private final String route;
  private final Map<String, String> headers;
  private final int expectedStatus;
  private final List<Check> checks;
  private final String requires;
  private final Protocol protocol;
  private final Phase phase;
  private final String purpose;

  private CatalogEntry(Builder b) {
    this.id = b.id;
    this.method = b.method;
    this.path = b.path;
    this.route = b.route == null ? routeOf(b.path) : b.route;
    this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
    this.expectedStatus = b.expectedStatus;
    this.checks = Collections.unmodifiableList(new ArrayList<>(b.checks));
    this.requires = b.requires;
    this.protocol = b.protocol;
    this.phase = b.phase;
    this.purpose = b.purpose;
  }

  public static Builder get(String id, String path) {
    return new Builder(id, "GET", path);
  }

  public static Builder post(String id, String path) {
    return new Builder(id, "POST", path);
  }

  public static Builder options(String id, String path) {
    return new Builder(id, "OPTIONS", path);
  }

  /** Unique id, e.g. {@code orders.get}, {@code pay.hc5.timeout}, {@code seed.xss.path.0}. */
  public String id() {
    return id;
  }

  public String method() {
    return method;
  }

  /** Request path below the app's base URL (context path), percent-encoded, with query string. */
  public String path() {
    return path;
  }

  /** Route template, e.g. {@code /api/orders/{id}}: the expected service name is method + route. */
  public String route() {
    return route;
  }

  public Map<String, String> headers() {
    return headers;
  }

  public int expectedStatus() {
    return expectedStatus;
  }

  public List<Check> checks() {
    return checks;
  }

  /**
   * Capability the app must report in GET /api/runtime ({@code client:<name>} for the outgoing call
   * clients), or null when every app of the catalog implements the entry.
   */
  public String requires() {
    return requires;
  }

  public Protocol protocol() {
    return protocol;
  }

  public Phase phase() {
    return phase;
  }

  /** Why the endpoint exists (verification purpose column of 18 §4.2). */
  public String purpose() {
    return purpose;
  }

  private static String routeOf(String path) {
    int query = path.indexOf('?');
    return query < 0 ? path : path.substring(0, query);
  }

  /** Builder of {@link CatalogEntry}. */
  public static final class Builder {
    private final String id;
    private final String method;
    private final String path;
    private String route;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private int expectedStatus = 200;
    private final List<Check> checks = new ArrayList<>();
    private String requires;
    private Protocol protocol = Protocol.HTTP;
    private Phase phase = Phase.SEQUENTIAL;
    private String purpose = "";

    private Builder(String id, String method, String path) {
      if (!path.startsWith("/")) {
        throw new IllegalArgumentException("path must start with '/': " + path);
      }
      this.id = id;
      this.method = method;
      this.path = path;
    }

    public Builder route(String value) {
      this.route = value;
      return this;
    }

    public Builder header(String name, String value) {
      headers.put(name, value);
      return this;
    }

    public Builder status(int value) {
      this.expectedStatus = value;
      return this;
    }

    public Builder check(Check check) {
      checks.add(check);
      return this;
    }

    public Builder requires(String capability) {
      this.requires = capability;
      return this;
    }

    public Builder protocol(Protocol value) {
      this.protocol = value;
      return this;
    }

    public Builder phase(Phase value) {
      this.phase = value;
      return this;
    }

    public Builder purpose(String value) {
      this.purpose = value;
      return this;
    }

    public CatalogEntry build() {
      return new CatalogEntry(this);
    }
  }
}
