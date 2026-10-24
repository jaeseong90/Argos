/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.jpa;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Counts the SQL statements Hibernate prepares on the current thread. Registered in
 * application.properties ({@code hibernate.session_factory.statement_inspector}); {@link
 * OrderQueries} resets the count at the start of a request and reports it as {@code sqlStatements},
 * so the smoke checks the 1 and 1 + 20 statements of 18 §4.2 on the JPA path the app really serves.
 * The SQL is returned unchanged.
 */
public final class SqlStatementCounter implements StatementInspector {

  private static final long serialVersionUID = 1L;

  /** One counter per thread (request threads, virtual or platform); never shared. */
  private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);

  /** Starts counting for the current thread's request. */
  public static void reset() {
    COUNT.get()[0] = 0;
  }

  /** Statements Hibernate prepared on the current thread since {@link #reset()}. */
  public static int count() {
    return COUNT.get()[0];
  }

  @Override
  public String inspect(String sql) {
    COUNT.get()[0]++;
    return sql;
  }
}
