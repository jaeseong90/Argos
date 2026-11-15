/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres;

import com.zaxxer.hikari.HikariDataSource;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.SecretValues;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PostgreSQL data sources of the server (13 §12, §13.1; C-11, ADR-0010 C-97).
 *
 * <ul>
 *   <li>The runtime pool ({@link #dataSource}, the application's only {@code DataSource} bean,
 *       which also keeps Spring Boot's DataSource auto-configuration away): HikariCP with the
 *       runtime account {@code argos.postgres.user} (default {@code argos_app}). It is created
 *       without a password and does not connect when the context starts; {@link
 *       PostgresRuntimePoolPhase} sets the password and opens the first connection after the
 *       PostgreSQL migration, so nothing reaches the database through it before the schema exists.
 *   <li>The migration data source ({@link #openOwner}): the schema owner {@code
 *       argos.postgres.owner.*}, at most two connections, opened by a migration phase and closed at
 *       the end of that phase; the server never keeps an owner connection.
 * </ul>
 *
 * Passwords come from {@code *-password-file} (preferred) or {@code *-password} ({@link
 * SecretValues}); the credential policy is checked earlier, in the configuration phase.
 */
@Configuration(proxyBeanMethods = false)
public class PostgresDataSources {

  /** Pool name and {@code application_name} of the runtime pool. */
  public static final String RUNTIME_POOL = "argos-server";

  /** Pool name and {@code application_name} of the migration data source. */
  public static final String MIGRATION_POOL = "argos-server-migration";

  /** Upper bound of runtime connections (12 §11: the server's PostgreSQL work is light). */
  static final int RUNTIME_MAX_CONNECTIONS = 10;

  /**
   * The runtime pool of {@code argos_app}. Lazy: the pool starts with its first {@code
   * getConnection()}, which {@link PostgresRuntimePoolPhase} makes after the migration.
   */
  @Bean(destroyMethod = "close")
  public HikariDataSource dataSource(ArgosProperties properties) {
    ArgosProperties.Postgres postgres = properties.postgres();
    HikariDataSource ds = new HikariDataSource();
    ds.setPoolName(RUNTIME_POOL);
    ds.setJdbcUrl(postgres.url());
    ds.setUsername(postgres.app().user());
    ds.setMaximumPoolSize(RUNTIME_MAX_CONNECTIONS);
    ds.setMinimumIdle(2);
    ds.setConnectionTimeout(10_000);
    ds.addDataSourceProperty("ApplicationName", RUNTIME_POOL);
    return ds;
  }

  /**
   * A data source of the schema owner for one migration phase; the caller closes it.
   *
   * @throws IllegalStateException with an operator-facing message (no secret) when the URL or the
   *     password is missing or the password file cannot be read
   */
  public static HikariDataSource openOwner(ArgosProperties.Postgres postgres) {
    ArgosProperties.Credential owner = postgres.owner();
    HikariDataSource ds = new HikariDataSource();
    ds.setPoolName(MIGRATION_POOL);
    ds.setJdbcUrl(requireUrl(postgres));
    ds.setUsername(owner.user());
    ds.setPassword(password("argos.postgres.owner", owner));
    ds.setMaximumPoolSize(2);
    ds.setMinimumIdle(0);
    ds.setConnectionTimeout(30_000);
    ds.addDataSourceProperty("ApplicationName", MIGRATION_POOL);
    return ds;
  }

  /** The JDBC URL, required. */
  static String requireUrl(ArgosProperties.Postgres postgres) {
    String url = postgres.url();
    if (url == null || url.isBlank()) {
      throw new IllegalStateException(
          "argos.postgres.url is required (e.g. jdbc:postgresql://postgres:5432/argos)");
    }
    return url;
  }

  /** The password of an account, from its file or value key. */
  static String password(String prefix, ArgosProperties.Credential credential) {
    try {
      return SecretValues.password(prefix, credential)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "No password for "
                          + prefix
                          + " (user "
                          + credential.user()
                          + "): set "
                          + prefix
                          + ".password-file"));
    } catch (SecretValues.SecretException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }
}
