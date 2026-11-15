/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.arch.fixture;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Negative fixture of SRV-01: JDBC outside the storage packages (never production code). */
public final class JdbcOutsideStorage {

  private JdbcOutsideStorage() {}

  /** Uses JDBC directly. */
  public static boolean valid(DataSource dataSource) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      return c.isValid(1);
    }
  }
}
