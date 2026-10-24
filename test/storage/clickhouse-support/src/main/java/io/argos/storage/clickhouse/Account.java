/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.clickhouse;

/**
 * The three ClickHouse accounts of {@code deploy/clickhouse/users.d/argos.xml} (DSN-13-12, C-11).
 */
public enum Account {
  /** INSERT on argos.* only: the batch writer (13 §7.4). */
  WRITER("argos_writer", "ARGOS_CH_WRITER_PASSWORD_SHA256"),
  /** SELECT, readonly, query limits: the query API (13 §6, SEC-76). */
  READER("argos_reader", "ARGOS_CH_READER_PASSWORD_SHA256"),
  /** DDL: migration runner, rollup rebuild, deletion mutations, emergency DROP PARTITION. */
  MIGRATOR("argos_migrator", "ARGOS_CH_MIGRATOR_PASSWORD_SHA256");

  private final String user;
  private final String passwordHashVariable;

  Account(String user, String passwordHashVariable) {
    this.user = user;
    this.passwordHashVariable = passwordHashVariable;
  }

  /** ClickHouse user name. */
  public String user() {
    return user;
  }

  /** Environment variable that carries the SHA-256 (hex) of the password (from_env). */
  public String passwordHashVariable() {
    return passwordHashVariable;
  }
}
