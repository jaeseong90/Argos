/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common;

import io.argos.sample.common.db.JdbcAccess;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;

/**
 * Test helper: a {@link JdbcAccess} whose connections record every executed statement (execute,
 * executeQuery, executeUpdate, executeBatch), the way an agent counts SQL steps.
 */
final class CountingDataSource implements JdbcAccess {

  private final DataSource target;
  private final List<String> executed = Collections.synchronizedList(new ArrayList<String>());

  CountingDataSource(DataSource target) {
    this.target = target;
  }

  @Override
  public Connection acquire() throws SQLException {
    Connection connection = target.getConnection();
    return proxy(
        Connection.class,
        (method, args) -> {
          Object result = invoke(connection, method, args);
          if (result instanceof PreparedStatement) {
            return proxy(PreparedStatement.class, recording(result, (String) args[0]));
          }
          if (result instanceof Statement) {
            return proxy(Statement.class, recording(result, null));
          }
          return result;
        });
  }

  @Override
  public void release(Connection connection) throws SQLException {
    connection.close();
  }

  List<String> executed() {
    synchronized (executed) {
      return new ArrayList<>(executed);
    }
  }

  void reset() {
    executed.clear();
  }

  private InvocationHandler recording(Object statement, String preparedSql) {
    return (proxy, method, args) -> {
      if (method.getName().startsWith("execute")) {
        String sql = preparedSql;
        if (sql == null) {
          sql = args == null || args.length == 0 ? "<batch>" : (String) args[0];
        }
        executed.add(sql);
      }
      return invoke(statement, method, args);
    };
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private interface Handler {
    Object handle(Method method, Object[] args) throws Throwable;
  }

  private static <T> T proxy(Class<T> type, Handler handler) {
    return proxy(type, (InvocationHandler) (p, method, args) -> handler.handle(method, args));
  }

  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(
        Proxy.newProxyInstance(
            CountingDataSource.class.getClassLoader(), new Class<?>[] {type}, handler));
  }
}
