/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric.redeploy;

import org.apache.commons.dbcp2.BasicDataSource;

/**
 * A web application class that keeps its connection pool in a static field, as applications do.
 * {@code RegistryClassLoaderLeakTest} loads it in a child class loader together with DBCP2 (never
 * through the test's own class loader), so it must not refer to anything of the agent or of the
 * test.
 */
public final class RedeployPoolApp {
  private static BasicDataSource dataSource;

  private RedeployPoolApp() {}

  /** Creates the application's pool (no connection is opened) and returns it. */
  public static Object start() {
    BasicDataSource ds = new BasicDataSource();
    ds.setMaxTotal(7);
    dataSource = ds;
    return dataSource;
  }
}
