/* SPDX-License-Identifier: Apache-2.0 */
package example.jdbcwrapper;

/**
 * The {@link java.sql.Statement} methods of the statement wrappers of {@link WrappingDriver}: every
 * method delegates to the driver's statement, {@code getConnection()} answers the wrapping
 * connection. The execute methods are declared here (an abstract class with concrete methods, as
 * wrapper libraries have them). Generated from the JDBC 4.2 interface (Java 8).
 *
 * @param <S> the driver's statement type
 */
// Delegates every JDBC 4.2 method, the deprecated ones included.
@SuppressWarnings("deprecation")
abstract class AbstractWrappedStatement<S extends java.sql.Statement>
    implements java.sql.Statement {
  final WrappedConnection connection;
  final S delegate;

  AbstractWrappedStatement(WrappedConnection connection, S delegate) {
    this.connection = connection;
    this.delegate = delegate;
  }

  @Override
  public void addBatch(String a0) throws java.sql.SQLException {
    delegate.addBatch(a0);
  }

  @Override
  public void cancel() throws java.sql.SQLException {
    delegate.cancel();
  }

  @Override
  public void clearBatch() throws java.sql.SQLException {
    delegate.clearBatch();
  }

  @Override
  public void clearWarnings() throws java.sql.SQLException {
    delegate.clearWarnings();
  }

  @Override
  public void closeOnCompletion() throws java.sql.SQLException {
    delegate.closeOnCompletion();
  }

  @Override
  public void close() throws java.sql.SQLException {
    delegate.close();
  }

  @Override
  public int[] executeBatch() throws java.sql.SQLException {
    return delegate.executeBatch();
  }

  @Override
  public java.sql.ResultSet executeQuery(String a0) throws java.sql.SQLException {
    return delegate.executeQuery(a0);
  }

  @Override
  public int executeUpdate(String a0, int[] a1) throws java.sql.SQLException {
    return delegate.executeUpdate(a0, a1);
  }

  @Override
  public int executeUpdate(String a0, String[] a1) throws java.sql.SQLException {
    return delegate.executeUpdate(a0, a1);
  }

  @Override
  public int executeUpdate(String a0, int a1) throws java.sql.SQLException {
    return delegate.executeUpdate(a0, a1);
  }

  @Override
  public int executeUpdate(String a0) throws java.sql.SQLException {
    return delegate.executeUpdate(a0);
  }

  @Override
  public boolean execute(String a0, int[] a1) throws java.sql.SQLException {
    return delegate.execute(a0, a1);
  }

  @Override
  public boolean execute(String a0, String[] a1) throws java.sql.SQLException {
    return delegate.execute(a0, a1);
  }

  @Override
  public boolean execute(String a0, int a1) throws java.sql.SQLException {
    return delegate.execute(a0, a1);
  }

  @Override
  public boolean execute(String a0) throws java.sql.SQLException {
    return delegate.execute(a0);
  }

  @Override
  public java.sql.Connection getConnection() throws java.sql.SQLException {
    return connection;
  }

  @Override
  public int getFetchDirection() throws java.sql.SQLException {
    return delegate.getFetchDirection();
  }

  @Override
  public int getFetchSize() throws java.sql.SQLException {
    return delegate.getFetchSize();
  }

  @Override
  public java.sql.ResultSet getGeneratedKeys() throws java.sql.SQLException {
    return delegate.getGeneratedKeys();
  }

  @Override
  public int getMaxFieldSize() throws java.sql.SQLException {
    return delegate.getMaxFieldSize();
  }

  @Override
  public int getMaxRows() throws java.sql.SQLException {
    return delegate.getMaxRows();
  }

  @Override
  public boolean getMoreResults() throws java.sql.SQLException {
    return delegate.getMoreResults();
  }

  @Override
  public boolean getMoreResults(int a0) throws java.sql.SQLException {
    return delegate.getMoreResults(a0);
  }

  @Override
  public int getQueryTimeout() throws java.sql.SQLException {
    return delegate.getQueryTimeout();
  }

  @Override
  public int getResultSetConcurrency() throws java.sql.SQLException {
    return delegate.getResultSetConcurrency();
  }

  @Override
  public int getResultSetHoldability() throws java.sql.SQLException {
    return delegate.getResultSetHoldability();
  }

  @Override
  public int getResultSetType() throws java.sql.SQLException {
    return delegate.getResultSetType();
  }

  @Override
  public java.sql.ResultSet getResultSet() throws java.sql.SQLException {
    return delegate.getResultSet();
  }

  @Override
  public int getUpdateCount() throws java.sql.SQLException {
    return delegate.getUpdateCount();
  }

  @Override
  public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
    return delegate.getWarnings();
  }

  @Override
  public boolean isCloseOnCompletion() throws java.sql.SQLException {
    return delegate.isCloseOnCompletion();
  }

  @Override
  public boolean isClosed() throws java.sql.SQLException {
    return delegate.isClosed();
  }

  @Override
  public boolean isPoolable() throws java.sql.SQLException {
    return delegate.isPoolable();
  }

  @Override
  public boolean isWrapperFor(Class<?> a0) throws java.sql.SQLException {
    return a0.isInstance(this) || delegate.isWrapperFor(a0);
  }

  @Override
  public void setCursorName(String a0) throws java.sql.SQLException {
    delegate.setCursorName(a0);
  }

  @Override
  public void setEscapeProcessing(boolean a0) throws java.sql.SQLException {
    delegate.setEscapeProcessing(a0);
  }

  @Override
  public void setFetchDirection(int a0) throws java.sql.SQLException {
    delegate.setFetchDirection(a0);
  }

  @Override
  public void setFetchSize(int a0) throws java.sql.SQLException {
    delegate.setFetchSize(a0);
  }

  @Override
  public void setMaxFieldSize(int a0) throws java.sql.SQLException {
    delegate.setMaxFieldSize(a0);
  }

  @Override
  public void setMaxRows(int a0) throws java.sql.SQLException {
    delegate.setMaxRows(a0);
  }

  @Override
  public void setPoolable(boolean a0) throws java.sql.SQLException {
    delegate.setPoolable(a0);
  }

  @Override
  public void setQueryTimeout(int a0) throws java.sql.SQLException {
    delegate.setQueryTimeout(a0);
  }

  @Override
  public <T> T unwrap(Class<T> a0) throws java.sql.SQLException {
    return a0.isInstance(this) ? a0.cast(this) : delegate.unwrap(a0);
  }
}
