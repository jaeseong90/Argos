/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.spring;

import io.argos.sample.common.db.JdbcAccess;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * {@link JdbcAccess} of the Spring apps: connections through {@link DataSourceUtils}, so that
 * {@link io.argos.sample.common.db.ShopJdbc} joins the current Spring transaction (JPA or JDBC
 * transaction manager) and POST /api/tx/commit|rollback commit or roll back its statements.
 */
public final class SpringJdbcAccess implements JdbcAccess {

  private final DataSource dataSource;

  public SpringJdbcAccess(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public Connection acquire() {
    return DataSourceUtils.getConnection(dataSource);
  }

  @Override
  public void release(Connection connection) {
    DataSourceUtils.releaseConnection(connection, dataSource);
  }
}
