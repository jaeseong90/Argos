/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.demo;

import io.argos.sample.common.catalog.DemoCatalog;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Function;

/**
 * Settings of the demo app, read from the environment (17 §3.2 demo profile, WO-41). Spring's
 * relaxed binding maps the variables to these keys:
 *
 * <ul>
 *   <li>{@code DEMO_ROLE} ({@code demo.role}): {@code order} (order-api, default) or {@code
 *       payment} (payment-api).
 *   <li>{@code PEER_URL} ({@code demo.peer-url}): base URL of payment-api, order role only (default
 *       {@code http://sample-app-b:8080}).
 *   <li>{@code DEMO_DB_URL}, {@code DEMO_DB_USER}: the demo database (PostgreSQL); each role keeps
 *       its data in its own schema ({@code demo_order}, {@code demo_payment}).
 *   <li>{@code DEMO_DB_PASSWORD_FILE}: file holding the database password. The password is read
 *       from a file only: {@code DEMO_DB_PASSWORD} is refused, so the secret never sits in the
 *       process environment, {@code docker inspect} or an image layer.
 *   <li>{@code DEMO_DB_WAIT} ({@code demo.db.wait}): how long the app waits for the database at
 *       start (default 120s; the database container may start after the app).
 * </ul>
 *
 * The batch period ({@code DEMO_BATCH_PERIOD}) is read by {@link SettlementBatch}.
 */
public final class DemoSettings {

  /** Upper bound of the password file (a password, not a document). */
  static final int MAX_PASSWORD_BYTES = 4096;

  static final String DEFAULT_PEER_URL = "http://sample-app-b:8080";
  static final String DEFAULT_DB_URL = "jdbc:postgresql://demo-db:5432/demo";
  static final String DEFAULT_DB_USER = "demo";
  static final Duration DEFAULT_DB_WAIT = Duration.ofMinutes(2);

  private final String role;
  private final URI peerUrl;
  private final String dbUrl;
  private final String dbUser;
  private final Path dbPasswordFile;
  private final Duration dbWait;

  private DemoSettings(
      String role, URI peerUrl, String dbUrl, String dbUser, Path dbPasswordFile, Duration dbWait) {
    this.role = role;
    this.peerUrl = peerUrl;
    this.dbUrl = dbUrl;
    this.dbUser = dbUser;
    this.dbPasswordFile = dbPasswordFile;
    this.dbWait = dbWait;
  }

  /**
   * Reads the settings through {@code property} (Spring's {@code Environment::getProperty}, or a
   * map in tests).
   *
   * @throws IllegalArgumentException with a message naming the variable (never a secret value)
   */
  public static DemoSettings from(Function<String, String> property) {
    String role = trimmed(property.apply("demo.role"));
    role = role == null ? DemoCatalog.ROLE_ORDER : role.toLowerCase(Locale.ROOT);
    if (!role.equals(DemoCatalog.ROLE_ORDER) && !role.equals(DemoCatalog.ROLE_PAYMENT)) {
      throw new IllegalArgumentException(
          "DEMO_ROLE must be '"
              + DemoCatalog.ROLE_ORDER
              + "' or '"
              + DemoCatalog.ROLE_PAYMENT
              + "', got '"
              + role
              + "'");
    }
    if (trimmed(property.apply("demo.db.password")) != null) {
      throw new IllegalArgumentException(
          "DEMO_DB_PASSWORD is not supported: put the password in a file and set"
              + " DEMO_DB_PASSWORD_FILE");
    }
    String passwordFile = trimmed(property.apply("demo.db.password-file"));
    if (passwordFile == null) {
      throw new IllegalArgumentException("DEMO_DB_PASSWORD_FILE is required");
    }
    String dbUrl = orDefault(property.apply("demo.db.url"), DEFAULT_DB_URL);
    if (!dbUrl.startsWith("jdbc:postgresql:")) {
      throw new IllegalArgumentException("DEMO_DB_URL must be a jdbc:postgresql: URL");
    }
    URI peer = null;
    if (role.equals(DemoCatalog.ROLE_ORDER)) {
      peer = peerUrl(orDefault(property.apply("demo.peer-url"), DEFAULT_PEER_URL));
    }
    return new DemoSettings(
        role,
        peer,
        dbUrl,
        orDefault(property.apply("demo.db.user"), DEFAULT_DB_USER),
        Path.of(passwordFile),
        duration("DEMO_DB_WAIT", property.apply("demo.db.wait"), DEFAULT_DB_WAIT));
  }

  /** {@code order} or {@code payment}. */
  public String role() {
    return role;
  }

  public boolean isOrder() {
    return role.equals(DemoCatalog.ROLE_ORDER);
  }

  /** Base URL of payment-api (order role), without a trailing slash; null for payment-api. */
  public URI peerUrl() {
    return peerUrl;
  }

  public String dbUrl() {
    return dbUrl;
  }

  /** The database URL without its query part (for logs: a query may carry settings). */
  public String dbUrlForLog() {
    int query = dbUrl.indexOf('?');
    return query < 0 ? dbUrl : dbUrl.substring(0, query);
  }

  public String dbUser() {
    return dbUser;
  }

  public Path dbPasswordFile() {
    return dbPasswordFile;
  }

  public Duration dbWait() {
    return dbWait;
  }

  /** Schema of this role's data: {@code demo_order} or {@code demo_payment}. */
  public String schema() {
    return "demo_" + role;
  }

  /**
   * Reads the password file: UTF-8, one trailing line break removed, not empty, at most {@value
   * #MAX_PASSWORD_BYTES} bytes. Errors name the file, never its content.
   */
  public String readDbPassword() {
    byte[] bytes;
    try {
      if (Files.size(dbPasswordFile) > MAX_PASSWORD_BYTES) {
        throw new IllegalArgumentException(
            "DEMO_DB_PASSWORD_FILE " + dbPasswordFile + " is larger than " + MAX_PASSWORD_BYTES);
      }
      bytes = Files.readAllBytes(dbPasswordFile);
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Cannot read DEMO_DB_PASSWORD_FILE " + dbPasswordFile + ": " + e.getClass().getName(), e);
    }
    String password = new String(bytes, StandardCharsets.UTF_8);
    if (password.endsWith("\n")) {
      password = password.substring(0, password.length() - 1);
    }
    if (password.endsWith("\r")) {
      password = password.substring(0, password.length() - 1);
    }
    if (password.isEmpty()) {
      throw new IllegalArgumentException("DEMO_DB_PASSWORD_FILE " + dbPasswordFile + " is empty");
    }
    return password;
  }

  /**
   * {@code 30s}, {@code 2m}, {@code 500ms}, {@code 1h}, ISO-8601 ({@code PT2M}) or plain seconds.
   */
  static Duration duration(String variable, String value, Duration fallback) {
    String v = trimmed(value);
    if (v == null) {
      return fallback;
    }
    Duration d;
    try {
      String lower = v.toLowerCase(Locale.ROOT);
      if (lower.startsWith("pt")) {
        d = Duration.parse(v.toUpperCase(Locale.ROOT));
      } else if (lower.endsWith("ms")) {
        d = Duration.ofMillis(Long.parseLong(lower.substring(0, lower.length() - 2)));
      } else if (lower.endsWith("s")) {
        d = Duration.ofSeconds(Long.parseLong(lower.substring(0, lower.length() - 1)));
      } else if (lower.endsWith("m")) {
        d = Duration.ofMinutes(Long.parseLong(lower.substring(0, lower.length() - 1)));
      } else if (lower.endsWith("h")) {
        d = Duration.ofHours(Long.parseLong(lower.substring(0, lower.length() - 1)));
      } else {
        d = Duration.ofSeconds(Long.parseLong(lower));
      }
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(variable + " is not a duration: '" + v + "'", e);
    }
    if (d.isNegative() || d.isZero()) {
      throw new IllegalArgumentException(variable + " must be positive: '" + v + "'");
    }
    return d;
  }

  private static URI peerUrl(String value) {
    String v = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    URI uri;
    try {
      uri = new URI(v);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("PEER_URL is not a URL: '" + value + "'", e);
    }
    String scheme = uri.getScheme();
    if (scheme == null
        || !(scheme.equals("http") || scheme.equals("https"))
        || uri.getHost() == null) {
      throw new IllegalArgumentException("PEER_URL must be an http(s) URL, got '" + value + "'");
    }
    return uri;
  }

  private static String orDefault(String value, String fallback) {
    String v = trimmed(value);
    return v == null ? fallback : v;
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String v = value.trim();
    return v.isEmpty() ? null : v;
  }
}
