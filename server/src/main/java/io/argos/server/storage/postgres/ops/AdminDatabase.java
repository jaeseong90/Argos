/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.ops;

import io.argos.server.config.ArgosProperties;
import io.argos.server.config.SecretValues;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The PostgreSQL side of the rollup operations outside the start sequence ({@code argos-admin
 * rollup rebuild}, {@code consistency check}, {@code apdex reapply} and the nightly consistency
 * check, WO-27): one connection of the runtime account {@code argos.postgres.user} (default {@code
 * argos_app}), held for the whole command or run.
 *
 * <ul>
 *   <li>{@link #tryRollupLock()}: the session-level advisory lock {@link #ROLLUP_LOCK_KEY}. A
 *       rollup rebuild writes the staging table {@code <t>_rebuild}; two rebuilds of the same day
 *       would replace each other's staging table, so every rebuild (operator command and nightly
 *       consistency check) holds this lock. It is released when the connection closes.
 *   <li>{@link #appliedClickHouseVersions()}: the successfully applied ClickHouse schema versions
 *       ({@code ch_schema_history}, read-only for {@code argos_app}). A rebuild with rollup SELECTs
 *       newer than the schema would write other columns than the tables have.
 *   <li>{@link #rollupAliases()}: the logical-to-physical rollup names of {@code
 *       settings.rollup_alias} (13 §13.2 step 3).
 *   <li>{@link #appIds(int)}: the applications the nightly check reports on.
 *   <li>{@link #apdex(int)}: the current Apdex T of an application and its service overrides
 *       ({@code apdex reapply}, D-17).
 *   <li>{@link #reconcileResults()}: {@code reconcile_results} (V005).
 * </ul>
 *
 * Messages of {@link AdminDatabaseException} are one line without secret values.
 */
public final class AdminDatabase implements AutoCloseable {

  /**
   * The advisory lock of rollup rebuilds ("ARGOROLL"; other keys of the server: the migration lock
   * of {@code PostgresMigrationStore}, the registration queue lock).
   */
  public static final long ROLLUP_LOCK_KEY = 0x4152474F_524F4C4CL;

  /**
   * Most service Apdex overrides of one application that count (the ingest path's cap, {@code
   * ApdexOverrides.MAX_PER_APP}: the first ones by {@code service_id}).
   */
  public static final int MAX_OVERRIDES_PER_APP = 1_000;

  /** {@code application_name} of the connection. */
  public static final String APPLICATION_NAME = "argos-admin";

  private static final Pattern TABLE = Pattern.compile("[A-Za-z0-9_]{1,128}");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Connection connection;

  private AdminDatabase(Connection connection) {
    this.connection = connection;
  }

  /** A failed PostgreSQL operation; the message is one line without secret values. */
  public static final class AdminDatabaseException extends Exception {

    private static final long serialVersionUID = 1L;

    AdminDatabaseException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Opens the connection of the runtime account.
   *
   * @throws AdminDatabaseException when the URL or password is missing or the connection fails
   */
  public static AdminDatabase open(ArgosProperties.Postgres postgres)
      throws AdminDatabaseException {
    String url = postgres.url();
    if (url == null || url.isBlank()) {
      throw new AdminDatabaseException(
          "argos.postgres.url is required (e.g. jdbc:postgresql://postgres:5432/argos)", null);
    }
    ArgosProperties.Credential app = postgres.app();
    String password;
    try {
      password =
          SecretValues.password("argos.postgres", app)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "No password for argos.postgres (user "
                              + app.user()
                              + "): set argos.postgres.password-file"));
    } catch (SecretValues.SecretException | IllegalStateException e) {
      throw new AdminDatabaseException(e.getMessage(), e);
    }
    Properties properties = new Properties();
    properties.setProperty("user", app.user());
    properties.setProperty("password", password);
    properties.setProperty("ApplicationName", APPLICATION_NAME);
    properties.setProperty("connectTimeout", "10");
    properties.setProperty("socketTimeout", "60");
    try {
      Connection connection = DriverManager.getConnection(url, properties);
      connection.setAutoCommit(true);
      return new AdminDatabase(connection);
    } catch (SQLException e) {
      throw new AdminDatabaseException(
          "PostgreSQL: cannot connect as " + app.user() + ": " + oneLine(e), e);
    }
  }

  /** Takes the rollup lock without waiting; false when another rebuild holds it. */
  public boolean tryRollupLock() throws AdminDatabaseException {
    try (PreparedStatement st = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
      st.setLong(1, ROLLUP_LOCK_KEY);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    } catch (SQLException e) {
      throw new AdminDatabaseException("PostgreSQL: rollup lock: " + oneLine(e), e);
    }
  }

  /** Versions of {@code ch_schema_history} with {@code success = true}. */
  public Set<String> appliedClickHouseVersions() throws AdminDatabaseException {
    Set<String> versions = new LinkedHashSet<>();
    try (Statement st = connection.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT version FROM argos.ch_schema_history WHERE success ORDER BY version")) {
      while (rs.next()) {
        versions.add(rs.getString(1));
      }
    } catch (SQLException e) {
      throw new AdminDatabaseException(
          "PostgreSQL: read argos.ch_schema_history: " + oneLine(e), e);
    }
    return versions;
  }

  /**
   * The valid entries of {@code settings.rollup_alias} (logical name to physical table); values
   * that are not plain table names are skipped.
   */
  public Map<String, String> rollupAliases() throws AdminDatabaseException {
    String text = null;
    try (Statement st = connection.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT value::text FROM argos.settings WHERE key = 'rollup_alias'")) {
      if (rs.next()) {
        text = rs.getString(1);
      }
    } catch (SQLException e) {
      throw new AdminDatabaseException("PostgreSQL: read argos.settings: " + oneLine(e), e);
    }
    return text == null ? Map.of() : parseAliases(text);
  }

  /** The entries of the JSON object {@code text} whose key and value are plain table names. */
  static Map<String, String> parseAliases(String text) {
    JsonNode root;
    try {
      root = JSON.readTree(text);
    } catch (JacksonException e) {
      return Map.of();
    }
    if (root == null || !root.isObject()) {
      return Map.of();
    }
    Map<String, String> aliases = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> e : root.properties()) {
      JsonNode value = e.getValue();
      String physical = value != null && value.isString() ? value.asString() : null;
      if (TABLE.matcher(e.getKey()).matches()
          && physical != null
          && TABLE.matcher(physical).matches()) {
        aliases.put(e.getKey(), physical);
      }
    }
    return Collections.unmodifiableMap(aliases);
  }

  /** The rows of the consistency check on this connection. */
  public ReconcileResultRepository reconcileResults() {
    return new ReconcileResultRepository(connection);
  }

  /** The ids of {@code argos.apps}, ascending, at most {@code limit}. */
  public List<Integer> appIds(int limit) throws AdminDatabaseException {
    List<Integer> ids = new ArrayList<>();
    try (PreparedStatement st =
        connection.prepareStatement("SELECT id FROM argos.apps ORDER BY id LIMIT ?")) {
      st.setInt(1, limit);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getInt(1));
        }
      }
    } catch (SQLException e) {
      throw new AdminDatabaseException("PostgreSQL: read argos.apps: " + oneLine(e), e);
    }
    return ids;
  }

  /**
   * The Apdex T of an application now (D-17): {@code apps.apdex_t_ms} and its {@code
   * service_apdex_overrides}, the first {@link #MAX_OVERRIDES_PER_APP} by {@code service_id} as the
   * ingest path reads them ({@code JdbcIngestStore}, {@code ApdexOverrides.MAX_PER_APP}).
   *
   * @param appTMs the application's T
   * @param overrides service id (the signed 64-bit value of the unsigned ClickHouse key) to T
   */
  public record AppApdex(int appTMs, Map<Long, Integer> overrides) {

    public AppApdex {
      overrides = Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
    }

    /** The T of a service: its override, otherwise the application's. */
    public int tFor(long serviceId) {
      return overrides.getOrDefault(serviceId, appTMs);
    }
  }

  /** The current Apdex T of {@code appId}, empty when there is no such application. */
  public Optional<AppApdex> apdex(int appId) throws AdminDatabaseException {
    Integer appT = null;
    Map<Long, Integer> overrides = new LinkedHashMap<>();
    try {
      try (PreparedStatement st =
          connection.prepareStatement("SELECT apdex_t_ms FROM argos.apps WHERE id = ?")) {
        st.setInt(1, appId);
        try (ResultSet rs = st.executeQuery()) {
          if (rs.next()) {
            appT = rs.getInt(1);
          }
        }
      }
      if (appT == null) {
        return Optional.empty();
      }
      try (PreparedStatement st =
          connection.prepareStatement(
              "SELECT service_id, apdex_t_ms FROM argos.service_apdex_overrides"
                  + " WHERE app_id = ? ORDER BY service_id LIMIT ?")) {
        st.setInt(1, appId);
        st.setInt(2, MAX_OVERRIDES_PER_APP);
        try (ResultSet rs = st.executeQuery()) {
          while (rs.next()) {
            overrides.put(rs.getLong(1), rs.getInt(2));
          }
        }
      }
    } catch (SQLException e) {
      throw new AdminDatabaseException("PostgreSQL: read the Apdex T: " + oneLine(e), e);
    }
    return Optional.of(new AppApdex(appT, overrides));
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      // Closing releases the session lock; a failure here leaves nothing to clean up.
    }
  }

  static String oneLine(SQLException e) {
    String message = e.getMessage();
    String text =
        message == null || message.isBlank()
            ? e.getClass().getSimpleName()
            : message.replaceAll("[\\r\\n\\t]+", " ").strip();
    return text.length() > 300 ? text.substring(0, 297) + "..." : text;
  }
}
