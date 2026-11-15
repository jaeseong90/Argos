/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.clickhouse;

import com.clickhouse.client.api.Client;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.SecretValues;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Creates ClickHouse client-v2 clients for the three accounts of 13 §7.3 (C-11, ADR-0007 C-60):
 * {@code argos_writer} (batch writer, WO-21), {@code argos_reader} (queries, WO-23) and {@code
 * argos_migrator} (migration runner, rebuild, deletion mutations). The caller owns and closes the
 * client.
 *
 * <p>The client talks HTTP to {@code argos.clickhouse.url} only (no other host, no telemetry). The
 * password comes from {@code argos.clickhouse.<account>.password-file} or {@code .password} ({@link
 * SecretValues}).
 */
public final class ClickHouseClients {

  /** Client name reported to ClickHouse ({@code system.query_log.http_user_agent}). */
  public static final String CLIENT_NAME = "argos-server";

  private ClickHouseClients() {}

  /** The three accounts. */
  public enum Account {
    /** INSERT on the five raw tables. */
    WRITER("writer"),
    /** Read-only queries. */
    READER("reader"),
    /** DDL. */
    MIGRATOR("migrator");

    private final String key;

    Account(String key) {
      this.key = key;
    }

    /** Configuration key prefix, e.g. {@code argos.clickhouse.migrator}. */
    public String prefix() {
      return "argos.clickhouse." + key;
    }

    ArgosProperties.Credential credential(ArgosProperties.ClickHouse clickhouse) {
      return switch (this) {
        case WRITER -> clickhouse.writer();
        case READER -> clickhouse.reader();
        case MIGRATOR -> clickhouse.migrator();
      };
    }
  }

  /** Connect timeout of {@link #open(ArgosProperties.ClickHouse, Account, Duration)}. */
  public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /**
   * A client of {@code account} with a connect timeout of {@link #CONNECT_TIMEOUT}.
   *
   * @param socketTimeout upper bound of one response (longest expected statement plus a margin)
   * @throws IllegalStateException with an operator-facing message (no secret) when the URL or the
   *     password is missing or the password file cannot be read
   */
  public static Client open(
      ArgosProperties.ClickHouse clickhouse, Account account, Duration socketTimeout) {
    return open(clickhouse, account, CONNECT_TIMEOUT, socketTimeout);
  }

  /**
   * A client of {@code account}.
   *
   * @param connectTimeout upper bound of opening a connection
   * @param socketTimeout upper bound of one response (longest expected statement plus a margin)
   * @throws IllegalStateException with an operator-facing message (no secret) when the URL or the
   *     password is missing or the password file cannot be read
   */
  public static Client open(
      ArgosProperties.ClickHouse clickhouse,
      Account account,
      Duration connectTimeout,
      Duration socketTimeout) {
    String url = clickhouse.url();
    if (url == null || url.isBlank()) {
      throw new IllegalStateException(
          "argos.clickhouse.url is required (e.g. http://clickhouse:8123)");
    }
    ArgosProperties.Credential credential = account.credential(clickhouse);
    String password;
    try {
      password =
          SecretValues.password(account.prefix(), credential)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "No password for "
                              + account.prefix()
                              + " (user "
                              + credential.user()
                              + "): set "
                              + account.prefix()
                              + ".password-file"));
    } catch (SecretValues.SecretException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
    return new Client.Builder()
        .addEndpoint(url)
        .setUsername(credential.user())
        .setPassword(password)
        .setClientName(CLIENT_NAME)
        .setConnectTimeout(connectTimeout.toMillis(), ChronoUnit.MILLIS)
        .setSocketTimeout(socketTimeout.toMillis(), ChronoUnit.MILLIS)
        .setMaxRetries(0)
        .build();
  }
}
