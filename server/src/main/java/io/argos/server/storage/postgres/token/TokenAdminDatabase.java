/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.token;

import io.argos.server.audit.AuditSink;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.SecretValues;
import io.argos.server.storage.postgres.audit.PostgresAuditSink;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The PostgreSQL side of {@code argos-admin token create} (ADR-0010 C-173 (4)): one connection of
 * the runtime account {@code argos.postgres.user} (default {@code argos_app}, which may insert into
 * {@code apps}, {@code agent_tokens} and {@code audit_logs}), held for the command, with the token
 * store and the PostgreSQL audit sink of the server over it. The command runs next to a running
 * server (in its container) and needs nothing else of it.
 */
public final class TokenAdminDatabase implements AutoCloseable {

  /** {@code application_name} of the connection. */
  public static final String APPLICATION_NAME = "argos-admin";

  private final SingleConnectionDataSource dataSource;

  private TokenAdminDatabase(SingleConnectionDataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** A failed connection; the message is one line without secret values. */
  public static final class TokenAdminDatabaseException extends Exception {

    private static final long serialVersionUID = 1L;

    TokenAdminDatabaseException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Opens the connection of the runtime account.
   *
   * @throws TokenAdminDatabaseException when the URL or password is missing or the connection fails
   */
  public static TokenAdminDatabase open(ArgosProperties.Postgres postgres)
      throws TokenAdminDatabaseException {
    String url = postgres.url();
    if (url == null || url.isBlank()) {
      throw new TokenAdminDatabaseException(
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
      throw new TokenAdminDatabaseException(e.getMessage(), e);
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
      return new TokenAdminDatabase(new SingleConnectionDataSource(connection, true));
    } catch (SQLException e) {
      throw new TokenAdminDatabaseException(
          "PostgreSQL: cannot connect as " + app.user() + ": " + oneLine(e), e);
    }
  }

  /** The token store over this connection. */
  public AgentTokenStore tokens() {
    return new AgentTokenStore(dataSource);
  }

  /** The PostgreSQL audit sink over this connection. */
  public AuditSink auditSink() {
    return new PostgresAuditSink(dataSource);
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
