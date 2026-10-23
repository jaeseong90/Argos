/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.bootstrap;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * B-1 violation: java.sql belongs to the platform class loader on JDK 9+, which classes in the
 * bootstrap class loader cannot see (10 §4.2).
 */
public final class UsesJavaSql {
  public boolean isClosed(Connection connection) throws SQLException {
    return connection.isClosed();
  }
}
