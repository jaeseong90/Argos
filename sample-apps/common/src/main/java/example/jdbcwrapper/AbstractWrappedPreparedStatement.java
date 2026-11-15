/* SPDX-License-Identifier: Apache-2.0 */
package example.jdbcwrapper;

/**
 * The {@link java.sql.PreparedStatement} methods of the prepared and callable statement wrappers of
 * {@link WrappingDriver}, delegating to the driver's statement. Generated from the JDBC 4.2
 * interface (Java 8).
 *
 * @param <P> the driver's statement type
 */
// Delegates every JDBC 4.2 method, the deprecated ones included.
@SuppressWarnings("deprecation")
abstract class AbstractWrappedPreparedStatement<P extends java.sql.PreparedStatement>
    extends AbstractWrappedStatement<P> implements java.sql.PreparedStatement {

  AbstractWrappedPreparedStatement(WrappedConnection connection, P delegate) {
    super(connection, delegate);
  }

  @Override
  public void addBatch() throws java.sql.SQLException {
    delegate.addBatch();
  }

  @Override
  public void clearParameters() throws java.sql.SQLException {
    delegate.clearParameters();
  }

  @Override
  public java.sql.ResultSet executeQuery() throws java.sql.SQLException {
    return delegate.executeQuery();
  }

  @Override
  public int executeUpdate() throws java.sql.SQLException {
    return delegate.executeUpdate();
  }

  @Override
  public boolean execute() throws java.sql.SQLException {
    return delegate.execute();
  }

  @Override
  public java.sql.ResultSetMetaData getMetaData() throws java.sql.SQLException {
    return delegate.getMetaData();
  }

  @Override
  public java.sql.ParameterMetaData getParameterMetaData() throws java.sql.SQLException {
    return delegate.getParameterMetaData();
  }

  @Override
  public void setArray(int a0, java.sql.Array a1) throws java.sql.SQLException {
    delegate.setArray(a0, a1);
  }

  @Override
  public void setAsciiStream(int a0, java.io.InputStream a1, int a2) throws java.sql.SQLException {
    delegate.setAsciiStream(a0, a1, a2);
  }

  @Override
  public void setAsciiStream(int a0, java.io.InputStream a1, long a2) throws java.sql.SQLException {
    delegate.setAsciiStream(a0, a1, a2);
  }

  @Override
  public void setAsciiStream(int a0, java.io.InputStream a1) throws java.sql.SQLException {
    delegate.setAsciiStream(a0, a1);
  }

  @Override
  public void setBigDecimal(int a0, java.math.BigDecimal a1) throws java.sql.SQLException {
    delegate.setBigDecimal(a0, a1);
  }

  @Override
  public void setBinaryStream(int a0, java.io.InputStream a1, int a2) throws java.sql.SQLException {
    delegate.setBinaryStream(a0, a1, a2);
  }

  @Override
  public void setBinaryStream(int a0, java.io.InputStream a1, long a2)
      throws java.sql.SQLException {
    delegate.setBinaryStream(a0, a1, a2);
  }

  @Override
  public void setBinaryStream(int a0, java.io.InputStream a1) throws java.sql.SQLException {
    delegate.setBinaryStream(a0, a1);
  }

  @Override
  public void setBlob(int a0, java.io.InputStream a1, long a2) throws java.sql.SQLException {
    delegate.setBlob(a0, a1, a2);
  }

  @Override
  public void setBlob(int a0, java.io.InputStream a1) throws java.sql.SQLException {
    delegate.setBlob(a0, a1);
  }

  @Override
  public void setBlob(int a0, java.sql.Blob a1) throws java.sql.SQLException {
    delegate.setBlob(a0, a1);
  }

  @Override
  public void setBoolean(int a0, boolean a1) throws java.sql.SQLException {
    delegate.setBoolean(a0, a1);
  }

  @Override
  public void setByte(int a0, byte a1) throws java.sql.SQLException {
    delegate.setByte(a0, a1);
  }

  @Override
  public void setBytes(int a0, byte[] a1) throws java.sql.SQLException {
    delegate.setBytes(a0, a1);
  }

  @Override
  public void setCharacterStream(int a0, java.io.Reader a1, int a2) throws java.sql.SQLException {
    delegate.setCharacterStream(a0, a1, a2);
  }

  @Override
  public void setCharacterStream(int a0, java.io.Reader a1, long a2) throws java.sql.SQLException {
    delegate.setCharacterStream(a0, a1, a2);
  }

  @Override
  public void setCharacterStream(int a0, java.io.Reader a1) throws java.sql.SQLException {
    delegate.setCharacterStream(a0, a1);
  }

  @Override
  public void setClob(int a0, java.io.Reader a1, long a2) throws java.sql.SQLException {
    delegate.setClob(a0, a1, a2);
  }

  @Override
  public void setClob(int a0, java.io.Reader a1) throws java.sql.SQLException {
    delegate.setClob(a0, a1);
  }

  @Override
  public void setClob(int a0, java.sql.Clob a1) throws java.sql.SQLException {
    delegate.setClob(a0, a1);
  }

  @Override
  public void setDate(int a0, java.sql.Date a1, java.util.Calendar a2)
      throws java.sql.SQLException {
    delegate.setDate(a0, a1, a2);
  }

  @Override
  public void setDate(int a0, java.sql.Date a1) throws java.sql.SQLException {
    delegate.setDate(a0, a1);
  }

  @Override
  public void setDouble(int a0, double a1) throws java.sql.SQLException {
    delegate.setDouble(a0, a1);
  }

  @Override
  public void setFloat(int a0, float a1) throws java.sql.SQLException {
    delegate.setFloat(a0, a1);
  }

  @Override
  public void setInt(int a0, int a1) throws java.sql.SQLException {
    delegate.setInt(a0, a1);
  }

  @Override
  public void setLong(int a0, long a1) throws java.sql.SQLException {
    delegate.setLong(a0, a1);
  }

  @Override
  public void setNCharacterStream(int a0, java.io.Reader a1, long a2) throws java.sql.SQLException {
    delegate.setNCharacterStream(a0, a1, a2);
  }

  @Override
  public void setNCharacterStream(int a0, java.io.Reader a1) throws java.sql.SQLException {
    delegate.setNCharacterStream(a0, a1);
  }

  @Override
  public void setNClob(int a0, java.io.Reader a1, long a2) throws java.sql.SQLException {
    delegate.setNClob(a0, a1, a2);
  }

  @Override
  public void setNClob(int a0, java.io.Reader a1) throws java.sql.SQLException {
    delegate.setNClob(a0, a1);
  }

  @Override
  public void setNClob(int a0, java.sql.NClob a1) throws java.sql.SQLException {
    delegate.setNClob(a0, a1);
  }

  @Override
  public void setNString(int a0, String a1) throws java.sql.SQLException {
    delegate.setNString(a0, a1);
  }

  @Override
  public void setNull(int a0, int a1, String a2) throws java.sql.SQLException {
    delegate.setNull(a0, a1, a2);
  }

  @Override
  public void setNull(int a0, int a1) throws java.sql.SQLException {
    delegate.setNull(a0, a1);
  }

  @Override
  public void setObject(int a0, Object a1, int a2, int a3) throws java.sql.SQLException {
    delegate.setObject(a0, a1, a2, a3);
  }

  @Override
  public void setObject(int a0, Object a1, int a2) throws java.sql.SQLException {
    delegate.setObject(a0, a1, a2);
  }

  @Override
  public void setObject(int a0, Object a1) throws java.sql.SQLException {
    delegate.setObject(a0, a1);
  }

  @Override
  public void setRef(int a0, java.sql.Ref a1) throws java.sql.SQLException {
    delegate.setRef(a0, a1);
  }

  @Override
  public void setRowId(int a0, java.sql.RowId a1) throws java.sql.SQLException {
    delegate.setRowId(a0, a1);
  }

  @Override
  public void setSQLXML(int a0, java.sql.SQLXML a1) throws java.sql.SQLException {
    delegate.setSQLXML(a0, a1);
  }

  @Override
  public void setShort(int a0, short a1) throws java.sql.SQLException {
    delegate.setShort(a0, a1);
  }

  @Override
  public void setString(int a0, String a1) throws java.sql.SQLException {
    delegate.setString(a0, a1);
  }

  @Override
  public void setTime(int a0, java.sql.Time a1, java.util.Calendar a2)
      throws java.sql.SQLException {
    delegate.setTime(a0, a1, a2);
  }

  @Override
  public void setTime(int a0, java.sql.Time a1) throws java.sql.SQLException {
    delegate.setTime(a0, a1);
  }

  @Override
  public void setTimestamp(int a0, java.sql.Timestamp a1, java.util.Calendar a2)
      throws java.sql.SQLException {
    delegate.setTimestamp(a0, a1, a2);
  }

  @Override
  public void setTimestamp(int a0, java.sql.Timestamp a1) throws java.sql.SQLException {
    delegate.setTimestamp(a0, a1);
  }

  @Override
  public void setURL(int a0, java.net.URL a1) throws java.sql.SQLException {
    delegate.setURL(a0, a1);
  }

  @Override
  public void setUnicodeStream(int a0, java.io.InputStream a1, int a2)
      throws java.sql.SQLException {
    delegate.setUnicodeStream(a0, a1, a2);
  }
}
