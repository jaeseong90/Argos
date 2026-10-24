/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.db;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * How {@link ShopJdbc} obtains and returns connections. Spring apps use {@code SpringJdbcAccess}
 * (transaction-bound connections through DataSourceUtils); everything else uses {@link #of}.
 */
public interface JdbcAccess {

  Connection acquire() throws SQLException;

  void release(Connection connection) throws SQLException;

  /** Plain access: {@code getConnection()} and {@code close()}. */
  static JdbcAccess of(DataSource dataSource) {
    return new JdbcAccess() {
      @Override
      public Connection acquire() throws SQLException {
        return dataSource.getConnection();
      }

      @Override
      public void release(Connection connection) throws SQLException {
        connection.close();
      }
    };
  }
}
