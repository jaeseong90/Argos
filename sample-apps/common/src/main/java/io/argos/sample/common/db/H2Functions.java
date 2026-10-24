/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.db;

/**
 * Java functions registered in H2 by schema-h2.sql ({@code CREATE ALIAS ... FOR}). H2 has no sleep
 * function, and {@code Thread.sleep} is overloaded with one parameter since JDK 19, which H2 cannot
 * alias.
 */
public final class H2Functions {

  private H2Functions() {}

  /** Sleeps in the database session (GET /api/slow?sql=1 on H2). */
  public static void sleepMillis(long millis) throws InterruptedException {
    Thread.sleep(millis);
  }
}
