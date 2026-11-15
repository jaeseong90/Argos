/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common;

import io.argos.sample.common.db.DbKind;
import io.argos.sample.common.db.PoolKind;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Environment switches of every sample app (18 §4.1, §4.4). Read once at startup; an invalid value
 * fails the start with a message that names the variable.
 *
 * <table>
 *   <caption>Switches</caption>
 *   <tr><th>Variable</th><th>Values</th><th>Default</th></tr>
 *   <tr><td>SAMPLE_DB</td><td>h2, postgres, mysql, mariadb, mssql, oracle</td>
 *       <td>h2 (in memory)</td></tr>
 *   <tr><td>SAMPLE_DB_URL, SAMPLE_DB_USER, SAMPLE_DB_PASSWORD</td><td>JDBC URL and account
 *       (Testcontainers inject them)</td><td>per database</td></tr>
 *   <tr><td>SAMPLE_POOL</td><td>hikari, tomcat, dbcp2</td><td>hikari</td></tr>
 *   <tr><td>SAMPLE_POOL_MAX</td><td>1..200</td><td>10</td></tr>
 *   <tr><td>SAMPLE_ROUTING_DS</td><td>true/false: AbstractRoutingDataSource ->
 *       TransactionAwareDataSourceProxy -> pool -> driver</td><td>false</td></tr>
 *   <tr><td>SAMPLE_DRIVER_WRAPPER</td><td>true/false: a delegating java.sql.Driver, Connection and
 *       Statement wrapper between the pool and the driver (ADR-0004 C-40), combinable with
 *       SAMPLE_ROUTING_DS</td><td>false</td></tr>
 *   <tr><td>SAMPLE_SECOND_POOL</td><td>true/false: a second pool of the SAMPLE_POOL kind on the
 *       same database, named shop-secondary (the app's pool is then named shop-primary), that sets
 *       up the schema and is then restarted under the same name (pool registry fixture of the
 *       agent tests, 10 §6.4)</td><td>false</td></tr>
 *   <tr><td>SAMPLE_VT</td><td>true/false: virtual threads (Boot 3.2+ on JDK 21+)</td>
 *       <td>false</td></tr>
 *   <tr><td>SAMPLE_CUSTOM_EXECUTOR</td><td>true/false: ThreadPoolExecutor subclass, thread names
 *       seed-ARGOS_SEED-%d</td><td>false</td></tr>
 *   <tr><td>SAMPLE_EXT_URL</td><td>base URL of ext-stub</td><td>http://127.0.0.1:18080</td></tr>
 *   <tr><td>SAMPLE_EXT_REFUSED_PORT</td><td>port on the ext-stub host where nothing listens</td>
 *       <td>1</td></tr>
 *   <tr><td>SAMPLE_EXT_TIMEOUT_MS, SAMPLE_EXT_CONNECT_TIMEOUT_MS</td><td>read and connect
 *       timeout of outgoing calls</td><td>1000, 5000</td></tr>
 *   <tr><td>-Dsample.fixed-clock or SAMPLE_FIXED_CLOCK</td><td>ISO-8601 instant: fixed business
 *       clock</td><td>system clock</td></tr>
 *   <tr><td>SAMPLE_APP_NAME, SAMPLE_APP_VERSION</td><td>override build-info app and version</td>
 *       <td>build-info.properties</td></tr>
 * </table>
 */
public final class SampleConfig {

  /** System property of the fixed business clock (18 §4.4). */
  public static final String FIXED_CLOCK_PROPERTY = "sample.fixed-clock";

  /** The driver wrapper of SAMPLE_DRIVER_WRAPPER (ADR-0004 C-40), outside io.argos on purpose. */
  public static final String WRAPPER_DRIVER_CLASS = "example.jdbcwrapper.WrappingDriver";

  /** URL prefix of the driver wrapper ({@code WrappingDriver.PREFIX}). */
  public static final String WRAPPER_URL_PREFIX = "jdbc:argos-wrapper:";

  private final DbKind db;
  private final String dbUrl;
  private final String dbUser;
  private final String dbPassword;
  private final PoolKind pool;
  private final int poolMaxSize;
  private final boolean routingDataSource;
  private final boolean driverWrapper;
  private final boolean secondPool;
  private final boolean virtualThreads;
  private final boolean customExecutor;
  private final String extUrl;
  private final int extRefusedPort;
  private final int extTimeoutMs;
  private final int extConnectTimeoutMs;
  private final Instant fixedClock;
  private final String appName;
  private final String appVersion;

  private SampleConfig(Map<String, String> env, Properties system, BuildInfo buildInfo) {
    this.appName = text(env, "SAMPLE_APP_NAME", buildInfo.app());
    this.appVersion = text(env, "SAMPLE_APP_VERSION", buildInfo.version());
    this.db = DbKind.parse(text(env, "SAMPLE_DB", "h2"));
    this.dbUrl = text(env, "SAMPLE_DB_URL", db.defaultUrl(appName));
    this.dbUser = text(env, "SAMPLE_DB_USER", db.defaultUser());
    this.dbPassword = text(env, "SAMPLE_DB_PASSWORD", "");
    this.pool = PoolKind.parse(text(env, "SAMPLE_POOL", "hikari"));
    this.poolMaxSize = integer(env, "SAMPLE_POOL_MAX", 10, 1, 200);
    this.routingDataSource = bool(env, "SAMPLE_ROUTING_DS");
    this.driverWrapper = bool(env, "SAMPLE_DRIVER_WRAPPER");
    this.secondPool = bool(env, "SAMPLE_SECOND_POOL");
    this.virtualThreads = bool(env, "SAMPLE_VT");
    this.customExecutor = bool(env, "SAMPLE_CUSTOM_EXECUTOR");
    this.extUrl = trimTrailingSlash(text(env, "SAMPLE_EXT_URL", "http://127.0.0.1:18080"));
    this.extRefusedPort = integer(env, "SAMPLE_EXT_REFUSED_PORT", 1, 1, 65535);
    this.extTimeoutMs = integer(env, "SAMPLE_EXT_TIMEOUT_MS", 1000, 50, 60_000);
    this.extConnectTimeoutMs = integer(env, "SAMPLE_EXT_CONNECT_TIMEOUT_MS", 5000, 50, 60_000);
    String clock = system.getProperty(FIXED_CLOCK_PROPERTY);
    if (clock == null || clock.trim().isEmpty()) {
      clock = env.get("SAMPLE_FIXED_CLOCK");
    }
    this.fixedClock = parseInstant(clock);
  }

  /** Reads the process environment and system properties. */
  public static SampleConfig fromEnvironment(BuildInfo buildInfo) {
    return from(System.getenv(), System.getProperties(), buildInfo);
  }

  /** Reads the given environment and system properties (tests). */
  public static SampleConfig from(Map<String, String> env, Properties system, BuildInfo buildInfo) {
    return new SampleConfig(
        Collections.unmodifiableMap(new HashMap<String, String>(env)), system, buildInfo);
  }

  public DbKind db() {
    return db;
  }

  public String dbUrl() {
    return dbUrl;
  }

  public String dbUser() {
    return dbUser;
  }

  public String dbPassword() {
    return dbPassword;
  }

  public PoolKind pool() {
    return pool;
  }

  public int poolMaxSize() {
    return poolMaxSize;
  }

  public boolean routingDataSource() {
    return routingDataSource;
  }

  /**
   * SAMPLE_DRIVER_WRAPPER: the pool connects through {@code example.jdbcwrapper.WrappingDriver}.
   */
  public boolean driverWrapper() {
    return driverWrapper;
  }

  /**
   * The JDBC URL the pool is configured with: {@link #dbUrl()}, behind the wrapper prefix with
   * SAMPLE_DRIVER_WRAPPER=true.
   */
  public String poolUrl() {
    return driverWrapper ? WRAPPER_URL_PREFIX + dbUrl : dbUrl;
  }

  /** The driver class the pool is configured with (the wrapper with SAMPLE_DRIVER_WRAPPER=true). */
  public String poolDriverClass() {
    return driverWrapper ? WRAPPER_DRIVER_CLASS : db.driverClass();
  }

  /**
   * SAMPLE_SECOND_POOL: a second, named pool next to the app's pool (two pools told apart by name
   * and a restarted pool for the agent's pool registry; see {@code SampleDataSources}).
   */
  public boolean secondPool() {
    return secondPool;
  }

  /** SAMPLE_VT as configured; whether requests really run on virtual threads is a runtime fact. */
  public boolean virtualThreads() {
    return virtualThreads;
  }

  public boolean customExecutor() {
    return customExecutor;
  }

  public String extUrl() {
    return extUrl;
  }

  public int extRefusedPort() {
    return extRefusedPort;
  }

  public int extTimeoutMs() {
    return extTimeoutMs;
  }

  public int extConnectTimeoutMs() {
    return extConnectTimeoutMs;
  }

  /** The fixed business instant, or null for the system clock. */
  public Instant fixedClock() {
    return fixedClock;
  }

  public String appName() {
    return appName;
  }

  public String appVersion() {
    return appVersion;
  }

  private static String text(Map<String, String> env, String key, String fallback) {
    String value = env.get(key);
    return value == null || value.trim().isEmpty() ? fallback : value.trim();
  }

  static boolean parseBoolean(String key, String value) {
    switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "true":
      case "1":
      case "yes":
      case "on":
        return true;
      case "false":
      case "0":
      case "no":
      case "off":
      case "":
        return false;
      default:
        throw new IllegalArgumentException(key + " must be true or false, got '" + value + "'");
    }
  }

  private static boolean bool(Map<String, String> env, String key) {
    String value = env.get(key);
    return value != null && parseBoolean(key, value);
  }

  private static int integer(Map<String, String> env, String key, int fallback, int min, int max) {
    String value = env.get(key);
    if (value == null || value.trim().isEmpty()) {
      return fallback;
    }
    int parsed;
    try {
      parsed = Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(key + " must be an integer, got '" + value + "'", e);
    }
    if (parsed < min || parsed > max) {
      throw new IllegalArgumentException(
          key + " must be between " + min + " and " + max + ", got " + parsed);
    }
    return parsed;
  }

  private static Instant parseInstant(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    try {
      return Instant.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(
          FIXED_CLOCK_PROPERTY
              + " must be an ISO-8601 instant such as 2026-01-15T00:00:00Z, got '"
              + value
              + "'",
          e);
    }
  }

  private static String trimTrailingSlash(String url) {
    String result = url;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
