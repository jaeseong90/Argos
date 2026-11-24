/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.ops;

import io.argos.server.audit.AuditRecord;
import io.argos.server.audit.AuditSink;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.SecretValues;
import io.argos.server.secrets.MasterKeyRing;
import io.argos.server.storage.postgres.audit.PostgresAuditSink;
import io.argos.server.storage.postgres.auth.UserRepository;
import io.argos.server.storage.postgres.command.CommandRepository;
import io.argos.server.storage.postgres.instance.InstanceRetentionRepository;
import io.argos.server.storage.postgres.secrets.SecretsRekeyService;
import io.argos.server.storage.postgres.token.AgentTokenStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The PostgreSQL side of the {@code argos-admin} commands of WO-28 ({@code cleanup}, {@code token
 * revoke|rotate}, {@code setup-token regenerate}, {@code bundle}; 17 §4.4): one connection of the
 * runtime account {@code argos.postgres.user} (default {@code argos_app}), held for the command,
 * with the server's repositories over it and a few fixed read queries for the diagnostic bundle.
 * Every statement is a constant; no caller passes SQL. Messages of {@link
 * AdminCommandDatabaseException} are one line without secret values.
 *
 * <p>{@link #lazyAuditSink} is the audit sink of a command that may run while PostgreSQL is down:
 * it connects at each write, so a failure sends the record to the fallback file instead of losing
 * it (16 §11.3).
 */
public final class AdminCommandDatabase implements AutoCloseable {

  /** {@code application_name} of the connection. */
  public static final String APPLICATION_NAME = "argos-admin";

  /** Dictionary rows deleted per statement of {@link #deleteDictionaryUnseenSince}. */
  static final int DICTIONARY_BATCH = 10_000;

  /** Rows a diagnostic query returns at most. */
  public static final int DIAGNOSTIC_ROWS = 10_000;

  private static final Pattern PREFIX = Pattern.compile("argos_[A-Za-z0-9_-]{8}");

  private final SingleConnectionDataSource dataSource;
  private final JdbcTemplate jdbc;

  private AdminCommandDatabase(SingleConnectionDataSource dataSource) {
    this.dataSource = dataSource;
    this.jdbc = new JdbcTemplate(dataSource);
    this.jdbc.setQueryTimeout(300);
  }

  /** A failed connection; the message is one line without secret values. */
  public static final class AdminCommandDatabaseException extends Exception {

    private static final long serialVersionUID = 1L;

    AdminCommandDatabaseException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Opens the connection of the runtime account.
   *
   * @throws AdminCommandDatabaseException when the URL or password is missing or the connection
   *     fails
   */
  public static AdminCommandDatabase open(ArgosProperties.Postgres postgres)
      throws AdminCommandDatabaseException {
    String url = postgres.url();
    if (url == null || url.isBlank()) {
      throw new AdminCommandDatabaseException(
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
      throw new AdminCommandDatabaseException(e.getMessage(), e);
    }
    try {
      Connection connection = DriverManager.getConnection(url, properties(app.user(), password));
      connection.setAutoCommit(true);
      return new AdminCommandDatabase(new SingleConnectionDataSource(connection, true));
    } catch (SQLException e) {
      throw new AdminCommandDatabaseException(
          "PostgreSQL: cannot connect as " + app.user() + ": " + oneLine(e), e);
    }
  }

  private static Properties properties(String user, String password) {
    Properties p = new Properties();
    p.setProperty("user", user);
    p.setProperty("password", password);
    p.setProperty("ApplicationName", APPLICATION_NAME);
    p.setProperty("connectTimeout", "10");
    p.setProperty("socketTimeout", "330");
    return p;
  }

  /**
   * The PostgreSQL audit sink of a command, connecting at each write as the runtime account.
   * Without a URL or password every write fails (and the record goes to the fallback file).
   */
  public static AuditSink lazyAuditSink(ArgosProperties.Postgres postgres) {
    String url = postgres.url();
    String password;
    try {
      password = SecretValues.password("argos.postgres", postgres.app()).orElse(null);
    } catch (SecretValues.SecretException e) {
      password = null;
    }
    if (url == null || url.isBlank() || password == null) {
      return new UnavailableSink();
    }
    DriverManagerDataSource ds = new DriverManagerDataSource(url);
    Properties p = properties(postgres.app().user(), password);
    p.setProperty("socketTimeout", "30");
    ds.setConnectionProperties(p);
    return new PostgresAuditSink(ds);
  }

  /** The primary sink without PostgreSQL settings: every write fails. */
  private static final class UnavailableSink implements AuditSink {
    @Override
    public void write(AuditRecord record) {
      throw new IllegalStateException("argos.postgres is not configured");
    }

    @Override
    public boolean primary() {
      return true;
    }
  }

  // --- repositories over this connection ------------------------------------------------------

  /** The PostgreSQL audit sink over this connection. */
  public AuditSink auditSink() {
    return new PostgresAuditSink(dataSource);
  }

  /** {@code users}. */
  public UserRepository users() {
    return new UserRepository(dataSource);
  }

  /** {@code agent_tokens}. */
  public AgentTokenStore tokens() {
    return new AgentTokenStore(dataSource);
  }

  /** The instance archive and purge statements of the daily job. */
  public InstanceRetentionRepository instanceRetention() {
    return new InstanceRetentionRepository(dataSource);
  }

  /** {@code commands} (retention statements of the hourly command retention). */
  public CommandRepository commands() {
    return new CommandRepository(dataSource);
  }

  /**
   * The re-encryption of the stored secrets over this connection ({@code secrets rekey}): the
   * runtime account may update the secret columns.
   */
  public SecretsRekeyService secretsRekey(MasterKeyRing ring) {
    return new SecretsRekeyService(dataSource, ring);
  }

  // --- statements of the commands -------------------------------------------------------------

  /**
   * The instances the running server sees connected ({@code status} UP or STALE, not archived): the
   * command line has no session registry, so these count as live and are never archived or deleted.
   */
  public Set<Integer> connectedInstanceIds() {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT id FROM argos.instances WHERE archived_at IS NULL AND status IN ('UP',"
                + " 'STALE')",
            Integer.class));
  }

  /**
   * Deletes dictionary entries not seen since {@code cutoff} (SEC-13, 17 §9.3), in batches of
   * {@value #DICTIONARY_BATCH} rows.
   *
   * @return the rows deleted
   */
  public long deleteDictionaryUnseenSince(Instant cutoff) {
    long total = 0;
    while (true) {
      int n =
          jdbc.update(
              "DELETE FROM argos.dictionary WHERE ctid IN (SELECT ctid FROM argos.dictionary WHERE"
                  + " last_seen < ? LIMIT "
                  + DICTIONARY_BATCH
                  + ")",
              Timestamp.from(cutoff));
      total += n;
      if (n < DICTIONARY_BATCH) {
        return total;
      }
    }
  }

  /** The ids of the unrevoked tokens with {@code prefix} ({@code argos_} and 8 characters). */
  public List<UUID> tokenIdsByPrefix(String prefix) {
    if (!PREFIX.matcher(prefix).matches()) {
      return List.of();
    }
    return jdbc.queryForList(
        "SELECT id FROM argos.agent_tokens WHERE token_prefix = ? AND status IN ('ACTIVE', 'GRACE')"
            + " ORDER BY issued_at",
        UUID.class,
        prefix);
  }

  // --- diagnostic bundle (read only) ----------------------------------------------------------

  /** {@code ch_schema_history}, oldest first. */
  public List<Map<String, Object>> clickHouseSchemaHistory() {
    return rows(
        "SELECT version, description, checksum, applied_at, duration_ms, success, error FROM"
            + " argos.ch_schema_history ORDER BY applied_at, version LIMIT "
            + DIAGNOSTIC_ROWS);
  }

  /** The PostgreSQL server version and the database size. */
  public List<Map<String, Object>> serverInfo() {
    return rows(
        "SELECT current_setting('server_version') AS server_version,"
            + " pg_database_size(current_database()) AS database_bytes");
  }

  /**
   * Per instance: id, application, environment, name, host, agent and JVM version, status, last
   * heartbeat, archive time (no token, no collected data).
   */
  public List<Map<String, Object>> instanceSummary() {
    return rows(
        "SELECT i.id, i.app_id, a.name AS app, a.env, i.name, i.host, i.agent_version,"
            + " i.protocol_version, i.jvm_version, i.status, i.warn_flags, i.first_seen_at,"
            + " i.last_seen_at, i.archived_at FROM argos.instances i JOIN argos.apps a ON a.id ="
            + " i.app_id ORDER BY i.id LIMIT "
            + DIAGNOSTIC_ROWS);
  }

  /** Per agent token: prefix only, application, status, dates. */
  public List<Map<String, Object>> tokenSummary() {
    return rows(
        "SELECT token_prefix, app_id, status, issued_at, expires_at, grace_until, revoked_at,"
            + " last_used_at FROM argos.agent_tokens ORDER BY issued_at LIMIT "
            + DIAGNOSTIC_ROWS);
  }

  private List<Map<String, Object>> rows(String sql) {
    return jdbc.query(
        sql,
        (ResultSet rs, int n) -> {
          ResultSetMetaData md = rs.getMetaData();
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= md.getColumnCount(); i++) {
            row.put(md.getColumnLabel(i), value(rs.getObject(i)));
          }
          return row;
        });
  }

  private static Object value(Object v) throws SQLException {
    if (v == null) {
      return null;
    }
    if (v instanceof Timestamp t) {
      return t.toInstant().toString();
    }
    if (v instanceof OffsetDateTime o) {
      return o.withOffsetSameInstant(ZoneOffset.UTC).toInstant().toString();
    }
    if (v instanceof java.sql.Array a) {
      Object[] items = (Object[]) a.getArray();
      List<String> out = new ArrayList<>();
      for (Object item : items) {
        out.add(String.valueOf(item));
      }
      return out;
    }
    if (v instanceof Number || v instanceof Boolean || v instanceof String) {
      return v;
    }
    return v.toString();
  }

  @Override
  public void close() {
    dataSource.destroy();
  }

  private static String oneLine(SQLException e) {
    String message = e.getMessage();
    String text =
        message == null || message.isBlank()
            ? e.getClass().getSimpleName()
            : message.replaceAll("[\\r\\n\\t]+", " ").strip();
    return text.length() > 300 ? text.substring(0, 297) + "..." : text;
  }
}
