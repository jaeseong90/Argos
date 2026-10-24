/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.sample.common.db.JdbcAccess;
import io.argos.sample.common.db.PoolExhauster;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PoolExhauster reports what the holders really got, not only that they finished. */
class PoolExhausterTest {

  @Test
  @DisplayName("Holders that cannot get a connection: allHeld=false, failedHolders=maxPoolSize")
  void failedHoldersAreNotHeld() throws Exception {
    AtomicInteger holderAttempts = new AtomicInteger();
    JdbcAccess holdersFail =
        new JdbcAccess() {
          @Override
          public Connection acquire() throws SQLException {
            // Holder threads fail; the request thread's (maxPoolSize + 1)th connection works.
            if (Thread.currentThread().getName().startsWith("pool-holder-")) {
              holderAttempts.incrementAndGet();
              throw new SQLException("injected: pool unavailable");
            }
            return workingConnection();
          }

          @Override
          public void release(Connection connection) throws SQLException {
            connection.close();
          }
        };
    PoolExhauster exhauster = new PoolExhauster(holdersFail, 3);
    Map<String, Object> result = exhauster.exhaust(50);
    assertThat(result)
        .containsEntry("maxPoolSize", 3)
        .containsEntry("allHeld", false)
        .containsEntry("held", 0)
        .containsEntry("failedHolders", 3);
    assertThat(holderAttempts).hasValue(3);
  }

  /** A connection whose statements execute without a database (no driver needed). */
  private static Connection workingConnection() {
    Statement statement =
        (Statement)
            Proxy.newProxyInstance(
                PoolExhausterTest.class.getClassLoader(),
                new Class<?>[] {Statement.class},
                (proxy, method, args) -> method.getName().equals("execute") ? (Object) true : null);
    return (Connection)
        Proxy.newProxyInstance(
            PoolExhausterTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "getAutoCommit":
                  return true;
                case "createStatement":
                  return statement;
                default:
                  return null;
              }
            });
  }
}
