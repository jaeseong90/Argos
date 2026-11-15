/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Connection pool libraries whose pools the pool module registers with {@link
 * Tracer#registerPool(Object, int)} (10 §6.4, F-A10). The numbers are agent-internal: no protocol
 * or storage field carries them; the core picks the getters of each kind ({@code
 * core.metric.PoolRegistry}) and labels the 10 §11 {@code jdbc_pool_*} series with the pool name.
 *
 * <p>Addition to 10 §3 (WO-13).
 */
public final class PoolKind {
  /** {@code com.zaxxer.hikari.pool.HikariPool}, registered at the exit of its constructor. */
  public static final int HIKARI = 1;

  /**
   * {@code org.apache.tomcat.jdbc.pool.ConnectionPool}, registered at the exit of its constructor.
   */
  public static final int TOMCAT_JDBC = 2;

  /**
   * {@code org.apache.commons.dbcp2.BasicDataSource}, registered at the exit of {@code
   * createDataSource()} (the moment its pool exists); the data source is the registered object.
   */
  public static final int DBCP2 = 3;

  /** Number of kinds plus one (kinds are 1-based). */
  public static final int COUNT = 4;

  private static final String[] NAMES = {"unknown", "hikari", "tomcat-jdbc", "dbcp2"};

  private PoolKind() {}

  /** True for {@link #HIKARI}, {@link #TOMCAT_JDBC} and {@link #DBCP2}. */
  public static boolean valid(int kind) {
    return kind > 0 && kind < COUNT;
  }

  /**
   * The label of {@code kind} ({@code hikari}, {@code tomcat-jdbc}, {@code dbcp2}) or {@code
   * unknown}.
   */
  public static String name(int kind) {
    return valid(kind) ? NAMES[kind] : NAMES[0];
  }
}
