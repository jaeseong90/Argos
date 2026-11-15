/* SPDX-License-Identifier: Apache-2.0 */
package example.jdbcwrapper;

/**
 * A {@link java.sql.Connection} of {@link WrappingDriver}: every method delegates to the driver's
 * connection; statements are returned wrapped ({@link WrappedStatement}, {@link
 * WrappedPreparedStatement}, {@link WrappedCallableStatement}), so that a pool over this connection
 * nests three JDBC implementations (pool proxy, wrapper, driver) like P6Spy or log4jdbc (ADR-0004
 * C-40). The delegating methods are generated from the JDBC 4.2 interface (Java 8).
 */
// Delegates every JDBC 4.2 method, the deprecated ones included.
@SuppressWarnings("deprecation")
public final class WrappedConnection implements java.sql.Connection {
  private final java.sql.Connection delegate;

  WrappedConnection(java.sql.Connection delegate) {
    this.delegate = delegate;
  }

  @Override
  public void abort(java.util.concurrent.Executor a0) throws java.sql.SQLException {
    delegate.abort(a0);
  }

  @Override
  public void clearWarnings() throws java.sql.SQLException {
    delegate.clearWarnings();
  }

  @Override
  public void close() throws java.sql.SQLException {
    delegate.close();
  }

  @Override
  public void commit() throws java.sql.SQLException {
    delegate.commit();
  }

  @Override
  public java.sql.Array createArrayOf(String a0, Object[] a1) throws java.sql.SQLException {
    return delegate.createArrayOf(a0, a1);
  }

  @Override
  public java.sql.Blob createBlob() throws java.sql.SQLException {
    return delegate.createBlob();
  }

  @Override
  public java.sql.Clob createClob() throws java.sql.SQLException {
    return delegate.createClob();
  }

  @Override
  public java.sql.NClob createNClob() throws java.sql.SQLException {
    return delegate.createNClob();
  }

  @Override
  public java.sql.SQLXML createSQLXML() throws java.sql.SQLException {
    return delegate.createSQLXML();
  }

  @Override
  public java.sql.Statement createStatement() throws java.sql.SQLException {
    return new WrappedStatement(this, delegate.createStatement());
  }

  @Override
  public java.sql.Statement createStatement(int a0, int a1, int a2) throws java.sql.SQLException {
    return new WrappedStatement(this, delegate.createStatement(a0, a1, a2));
  }

  @Override
  public java.sql.Statement createStatement(int a0, int a1) throws java.sql.SQLException {
    return new WrappedStatement(this, delegate.createStatement(a0, a1));
  }

  @Override
  public java.sql.Struct createStruct(String a0, Object[] a1) throws java.sql.SQLException {
    return delegate.createStruct(a0, a1);
  }

  @Override
  public boolean getAutoCommit() throws java.sql.SQLException {
    return delegate.getAutoCommit();
  }

  @Override
  public String getCatalog() throws java.sql.SQLException {
    return delegate.getCatalog();
  }

  @Override
  public java.util.Properties getClientInfo() throws java.sql.SQLException {
    return delegate.getClientInfo();
  }

  @Override
  public String getClientInfo(String a0) throws java.sql.SQLException {
    return delegate.getClientInfo(a0);
  }

  @Override
  public int getHoldability() throws java.sql.SQLException {
    return delegate.getHoldability();
  }

  @Override
  public java.sql.DatabaseMetaData getMetaData() throws java.sql.SQLException {
    return delegate.getMetaData();
  }

  @Override
  public int getNetworkTimeout() throws java.sql.SQLException {
    return delegate.getNetworkTimeout();
  }

  @Override
  public String getSchema() throws java.sql.SQLException {
    return delegate.getSchema();
  }

  @Override
  public int getTransactionIsolation() throws java.sql.SQLException {
    return delegate.getTransactionIsolation();
  }

  @Override
  public java.util.Map<String, Class<?>> getTypeMap() throws java.sql.SQLException {
    return delegate.getTypeMap();
  }

  @Override
  public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
    return delegate.getWarnings();
  }

  @Override
  public boolean isClosed() throws java.sql.SQLException {
    return delegate.isClosed();
  }

  @Override
  public boolean isReadOnly() throws java.sql.SQLException {
    return delegate.isReadOnly();
  }

  @Override
  public boolean isValid(int a0) throws java.sql.SQLException {
    return delegate.isValid(a0);
  }

  @Override
  public boolean isWrapperFor(Class<?> a0) throws java.sql.SQLException {
    return a0.isInstance(this) || delegate.isWrapperFor(a0);
  }

  @Override
  public String nativeSQL(String a0) throws java.sql.SQLException {
    return delegate.nativeSQL(a0);
  }

  @Override
  public java.sql.CallableStatement prepareCall(String a0, int a1, int a2, int a3)
      throws java.sql.SQLException {
    return delegate.prepareCall(a0, a1, a2, a3);
  }

  @Override
  public java.sql.CallableStatement prepareCall(String a0, int a1, int a2)
      throws java.sql.SQLException {
    return new WrappedCallableStatement(this, delegate.prepareCall(a0, a1, a2));
  }

  @Override
  public java.sql.CallableStatement prepareCall(String a0) throws java.sql.SQLException {
    return new WrappedCallableStatement(this, delegate.prepareCall(a0));
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(String a0, int[] a1)
      throws java.sql.SQLException {
    return new WrappedPreparedStatement(this, delegate.prepareStatement(a0, a1));
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(String a0, String[] a1)
      throws java.sql.SQLException {
    return new WrappedPreparedStatement(this, delegate.prepareStatement(a0, a1));
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(String a0, int a1, int a2, int a3)
      throws java.sql.SQLException {
    return new WrappedPreparedStatement(this, delegate.prepareStatement(a0, a1, a2, a3));
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(String a0, int a1, int a2)
      throws java.sql.SQLException {
    return new WrappedPreparedStatement(this, delegate.prepareStatement(a0, a1, a2));
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(String a0, int a1)
      throws java.sql.SQLException {
    return new WrappedPreparedStatement(this, delegate.prepareStatement(a0, a1));
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(String a0) throws java.sql.SQLException {
    return new WrappedPreparedStatement(this, delegate.prepareStatement(a0));
  }

  @Override
  public void releaseSavepoint(java.sql.Savepoint a0) throws java.sql.SQLException {
    delegate.releaseSavepoint(a0);
  }

  @Override
  public void rollback() throws java.sql.SQLException {
    delegate.rollback();
  }

  @Override
  public void rollback(java.sql.Savepoint a0) throws java.sql.SQLException {
    delegate.rollback(a0);
  }

  @Override
  public void setAutoCommit(boolean a0) throws java.sql.SQLException {
    delegate.setAutoCommit(a0);
  }

  @Override
  public void setCatalog(String a0) throws java.sql.SQLException {
    delegate.setCatalog(a0);
  }

  @Override
  public void setClientInfo(String a0, String a1) throws java.sql.SQLClientInfoException {
    delegate.setClientInfo(a0, a1);
  }

  @Override
  public void setClientInfo(java.util.Properties a0) throws java.sql.SQLClientInfoException {
    delegate.setClientInfo(a0);
  }

  @Override
  public void setHoldability(int a0) throws java.sql.SQLException {
    delegate.setHoldability(a0);
  }

  @Override
  public void setNetworkTimeout(java.util.concurrent.Executor a0, int a1)
      throws java.sql.SQLException {
    delegate.setNetworkTimeout(a0, a1);
  }

  @Override
  public void setReadOnly(boolean a0) throws java.sql.SQLException {
    delegate.setReadOnly(a0);
  }

  @Override
  public java.sql.Savepoint setSavepoint() throws java.sql.SQLException {
    return delegate.setSavepoint();
  }

  @Override
  public java.sql.Savepoint setSavepoint(String a0) throws java.sql.SQLException {
    return delegate.setSavepoint(a0);
  }

  @Override
  public void setSchema(String a0) throws java.sql.SQLException {
    delegate.setSchema(a0);
  }

  @Override
  public void setTransactionIsolation(int a0) throws java.sql.SQLException {
    delegate.setTransactionIsolation(a0);
  }

  @Override
  public void setTypeMap(java.util.Map<String, Class<?>> a0) throws java.sql.SQLException {
    delegate.setTypeMap(a0);
  }

  @Override
  public <T> T unwrap(Class<T> a0) throws java.sql.SQLException {
    return a0.isInstance(this) ? a0.cast(this) : delegate.unwrap(a0);
  }
}
