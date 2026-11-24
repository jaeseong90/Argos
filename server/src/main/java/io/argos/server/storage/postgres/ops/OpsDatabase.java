/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.ops;

import io.argos.server.audit.AuditSink;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.SecretValues;
import io.argos.server.storage.postgres.audit.PostgresAuditSink;
import io.argos.server.storage.postgres.auth.SettingsRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The PostgreSQL side of {@code argos-admin retention show|apply} (WO-28, 17 §4.4, §9.1): one
 * connection of the runtime account {@code argos.postgres.user} (default {@code argos_app}), held
 * for the command, with the settings table (the retention set through the API) and the PostgreSQL
 * audit sink of the server over it. Messages of {@link OpsDatabaseException} are one line without
 * secret values.
 */
public final class OpsDatabase implements AutoCloseable {

  /** {@code application_name} of the connection. */
  public static final String APPLICATION_NAME = "argos-admin";

  private final SingleConnectionDataSource dataSource;

  private OpsDatabase(SingleConnectionDataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** A failed connection; the message is one line without secret values. */
  public static final class OpsDatabaseException extends Exception {

    private static final long serialVersionUID = 1L;

    OpsDatabaseException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Opens the connection of the runtime account.
   *
   * @throws OpsDatabaseException when the URL or password is missing or the connection fails
   */
  public static OpsDatabase open(ArgosProperties.Postgres postgres) throws OpsDatabaseException {
    String url = postgres.url();
    if (url == null || url.isBlank()) {
      throw new OpsDatabaseException(
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
      throw new OpsDatabaseException(e.getMessage(), e);
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
      return new OpsDatabase(new SingleConnectionDataSource(connection, true));
    } catch (SQLException e) {
      throw new OpsDatabaseException(
          "PostgreSQL: cannot connect as " + app.user() + ": " + oneLine(e), e);
    }
  }

  /** The settings table over this connection. */
  public SettingsRepository settings() {
    return new SettingsRepository(dataSource);
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
