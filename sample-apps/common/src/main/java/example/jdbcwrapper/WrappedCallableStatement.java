/* SPDX-License-Identifier: Apache-2.0 */
package example.jdbcwrapper;

/**
 * A {@link java.sql.CallableStatement} of {@link WrappedConnection}, delegating to the driver's
 * statement. Generated from the JDBC 4.2 interface (Java 8).
 */
// Delegates every JDBC 4.2 method, the deprecated ones included.
@SuppressWarnings("deprecation")
public final class WrappedCallableStatement
    extends AbstractWrappedPreparedStatement<java.sql.CallableStatement>
    implements java.sql.CallableStatement {

  WrappedCallableStatement(WrappedConnection connection, java.sql.CallableStatement delegate) {
    super(connection, delegate);
  }

  @Override
  public java.sql.Array getArray(String a0) throws java.sql.SQLException {
    return delegate.getArray(a0);
  }

  @Override
  public java.sql.Array getArray(int a0) throws java.sql.SQLException {
    return delegate.getArray(a0);
  }

  @Override
  public java.math.BigDecimal getBigDecimal(String a0) throws java.sql.SQLException {
    return delegate.getBigDecimal(a0);
  }

  @Override
  public java.math.BigDecimal getBigDecimal(int a0, int a1) throws java.sql.SQLException {
    return delegate.getBigDecimal(a0, a1);
  }

  @Override
  public java.math.BigDecimal getBigDecimal(int a0) throws java.sql.SQLException {
    return delegate.getBigDecimal(a0);
  }

  @Override
  public java.sql.Blob getBlob(String a0) throws java.sql.SQLException {
    return delegate.getBlob(a0);
  }

  @Override
  public java.sql.Blob getBlob(int a0) throws java.sql.SQLException {
    return delegate.getBlob(a0);
  }

  @Override
  public boolean getBoolean(String a0) throws java.sql.SQLException {
    return delegate.getBoolean(a0);
  }

  @Override
  public boolean getBoolean(int a0) throws java.sql.SQLException {
    return delegate.getBoolean(a0);
  }

  @Override
  public byte getByte(String a0) throws java.sql.SQLException {
    return delegate.getByte(a0);
  }

  @Override
  public byte getByte(int a0) throws java.sql.SQLException {
    return delegate.getByte(a0);
  }

  @Override
  public byte[] getBytes(String a0) throws java.sql.SQLException {
    return delegate.getBytes(a0);
  }

  @Override
  public byte[] getBytes(int a0) throws java.sql.SQLException {
    return delegate.getBytes(a0);
  }

  @Override
  public java.io.Reader getCharacterStream(String a0) throws java.sql.SQLException {
    return delegate.getCharacterStream(a0);
  }

  @Override
  public java.io.Reader getCharacterStream(int a0) throws java.sql.SQLException {
    return delegate.getCharacterStream(a0);
  }

  @Override
  public java.sql.Clob getClob(String a0) throws java.sql.SQLException {
    return delegate.getClob(a0);
  }

  @Override
  public java.sql.Clob getClob(int a0) throws java.sql.SQLException {
    return delegate.getClob(a0);
  }

  @Override
  public java.sql.Date getDate(String a0, java.util.Calendar a1) throws java.sql.SQLException {
    return delegate.getDate(a0, a1);
  }

  @Override
  public java.sql.Date getDate(String a0) throws java.sql.SQLException {
    return delegate.getDate(a0);
  }

  @Override
  public java.sql.Date getDate(int a0, java.util.Calendar a1) throws java.sql.SQLException {
    return delegate.getDate(a0, a1);
  }

  @Override
  public java.sql.Date getDate(int a0) throws java.sql.SQLException {
    return delegate.getDate(a0);
  }

  @Override
  public double getDouble(String a0) throws java.sql.SQLException {
    return delegate.getDouble(a0);
  }

  @Override
  public double getDouble(int a0) throws java.sql.SQLException {
    return delegate.getDouble(a0);
  }

  @Override
  public float getFloat(String a0) throws java.sql.SQLException {
    return delegate.getFloat(a0);
  }

  @Override
  public float getFloat(int a0) throws java.sql.SQLException {
    return delegate.getFloat(a0);
  }

  @Override
  public int getInt(String a0) throws java.sql.SQLException {
    return delegate.getInt(a0);
  }

  @Override
  public int getInt(int a0) throws java.sql.SQLException {
    return delegate.getInt(a0);
  }

  @Override
  public long getLong(String a0) throws java.sql.SQLException {
    return delegate.getLong(a0);
  }

  @Override
  public long getLong(int a0) throws java.sql.SQLException {
    return delegate.getLong(a0);
  }

  @Override
  public java.io.Reader getNCharacterStream(String a0) throws java.sql.SQLException {
    return delegate.getNCharacterStream(a0);
  }

  @Override
  public java.io.Reader getNCharacterStream(int a0) throws java.sql.SQLException {
    return delegate.getNCharacterStream(a0);
  }

  @Override
  public java.sql.NClob getNClob(String a0) throws java.sql.SQLException {
    return delegate.getNClob(a0);
  }

  @Override
  public java.sql.NClob getNClob(int a0) throws java.sql.SQLException {
    return delegate.getNClob(a0);
  }

  @Override
  public String getNString(String a0) throws java.sql.SQLException {
    return delegate.getNString(a0);
  }

  @Override
  public String getNString(int a0) throws java.sql.SQLException {
    return delegate.getNString(a0);
  }

  @Override
  public <T> T getObject(String a0, Class<T> a1) throws java.sql.SQLException {
    return delegate.getObject(a0, a1);
  }

  @Override
  public Object getObject(String a0, java.util.Map<String, Class<?>> a1)
      throws java.sql.SQLException {
    return delegate.getObject(a0, a1);
  }

  @Override
  public Object getObject(String a0) throws java.sql.SQLException {
    return delegate.getObject(a0);
  }

  @Override
  public <T> T getObject(int a0, Class<T> a1) throws java.sql.SQLException {
    return delegate.getObject(a0, a1);
  }

  @Override
  public Object getObject(int a0, java.util.Map<String, Class<?>> a1) throws java.sql.SQLException {
    return delegate.getObject(a0, a1);
  }

  @Override
  public Object getObject(int a0) throws java.sql.SQLException {
    return delegate.getObject(a0);
  }

  @Override
  public java.sql.Ref getRef(String a0) throws java.sql.SQLException {
    return delegate.getRef(a0);
  }

  @Override
  public java.sql.Ref getRef(int a0) throws java.sql.SQLException {
    return delegate.getRef(a0);
  }

  @Override
  public java.sql.RowId getRowId(String a0) throws java.sql.SQLException {
    return delegate.getRowId(a0);
  }

  @Override
  public java.sql.RowId getRowId(int a0) throws java.sql.SQLException {
    return delegate.getRowId(a0);
  }

  @Override
  public java.sql.SQLXML getSQLXML(String a0) throws java.sql.SQLException {
    return delegate.getSQLXML(a0);
  }

  @Override
  public java.sql.SQLXML getSQLXML(int a0) throws java.sql.SQLException {
    return delegate.getSQLXML(a0);
  }

  @Override
  public short getShort(String a0) throws java.sql.SQLException {
    return delegate.getShort(a0);
  }

  @Override
  public short getShort(int a0) throws java.sql.SQLException {
    return delegate.getShort(a0);
  }

  @Override
  public String getString(String a0) throws java.sql.SQLException {
    return delegate.getString(a0);
  }

  @Override
  public String getString(int a0) throws java.sql.SQLException {
    return delegate.getString(a0);
  }

  @Override
  public java.sql.Time getTime(String a0, java.util.Calendar a1) throws java.sql.SQLException {
    return delegate.getTime(a0, a1);
  }

  @Override
  public java.sql.Time getTime(String a0) throws java.sql.SQLException {
    return delegate.getTime(a0);
  }

  @Override
  public java.sql.Time getTime(int a0, java.util.Calendar a1) throws java.sql.SQLException {
    return delegate.getTime(a0, a1);
  }

  @Override
  public java.sql.Time getTime(int a0) throws java.sql.SQLException {
    return delegate.getTime(a0);
  }

  @Override
  public java.sql.Timestamp getTimestamp(String a0, java.util.Calendar a1)
      throws java.sql.SQLException {
    return delegate.getTimestamp(a0, a1);
  }

  @Override
  public java.sql.Timestamp getTimestamp(String a0) throws java.sql.SQLException {
    return delegate.getTimestamp(a0);
  }

  @Override
  public java.sql.Timestamp getTimestamp(int a0, java.util.Calendar a1)
      throws java.sql.SQLException {
    return delegate.getTimestamp(a0, a1);
  }

  @Override
  public java.sql.Timestamp getTimestamp(int a0) throws java.sql.SQLException {
    return delegate.getTimestamp(a0);
  }

  @Override
  public java.net.URL getURL(String a0) throws java.sql.SQLException {
    return delegate.getURL(a0);
  }

  @Override
  public java.net.URL getURL(int a0) throws java.sql.SQLException {
    return delegate.getURL(a0);
  }

  @Override
  public void registerOutParameter(String a0, int a1, String a2) throws java.sql.SQLException {
    delegate.registerOutParameter(a0, a1, a2);
  }

  @Override
  public void registerOutParameter(String a0, int a1, int a2) throws java.sql.SQLException {
    delegate.registerOutParameter(a0, a1, a2);
  }

  @Override
  public void registerOutParameter(String a0, int a1) throws java.sql.SQLException {
    delegate.registerOutParameter(a0, a1);
  }

  @Override
  public void registerOutParameter(int a0, int a1, String a2) throws java.sql.SQLException {
    delegate.registerOutParameter(a0, a1, a2);
  }

  @Override
  public void registerOutParameter(int a0, int a1, int a2) throws java.sql.SQLException {
    delegate.registerOutParameter(a0, a1, a2);
  }

  @Override
  public void registerOutParameter(int a0, int a1) throws java.sql.SQLException {
    delegate.registerOutParameter(a0, a1);
  }

  @Override
  public void setAsciiStream(String a0, java.io.InputStream a1, int a2)
      throws java.sql.SQLException {
    delegate.setAsciiStream(a0, a1, a2);
  }

  @Override
  public void setAsciiStream(String a0, java.io.InputStream a1, long a2)
      throws java.sql.SQLException {
    delegate.setAsciiStream(a0, a1, a2);
  }

  @Override
  public void setAsciiStream(String a0, java.io.InputStream a1) throws java.sql.SQLException {
    delegate.setAsciiStream(a0, a1);
  }

  @Override
  public void setBigDecimal(String a0, java.math.BigDecimal a1) throws java.sql.SQLException {
    delegate.setBigDecimal(a0, a1);
  }

  @Override
  public void setBinaryStream(String a0, java.io.InputStream a1, int a2)
      throws java.sql.SQLException {
    delegate.setBinaryStream(a0, a1, a2);
  }

  @Override
  public void setBinaryStream(String a0, java.io.InputStream a1, long a2)
      throws java.sql.SQLException {
    delegate.setBinaryStream(a0, a1, a2);
  }

  @Override
  public void setBinaryStream(String a0, java.io.InputStream a1) throws java.sql.SQLException {
    delegate.setBinaryStream(a0, a1);
  }

  @Override
  public void setBlob(String a0, java.io.InputStream a1, long a2) throws java.sql.SQLException {
    delegate.setBlob(a0, a1, a2);
  }

  @Override
  public void setBlob(String a0, java.io.InputStream a1) throws java.sql.SQLException {
    delegate.setBlob(a0, a1);
  }

  @Override
  public void setBlob(String a0, java.sql.Blob a1) throws java.sql.SQLException {
    delegate.setBlob(a0, a1);
  }

  @Override
  public void setBoolean(String a0, boolean a1) throws java.sql.SQLException {
    delegate.setBoolean(a0, a1);
  }

  @Override
  public void setByte(String a0, byte a1) throws java.sql.SQLException {
    delegate.setByte(a0, a1);
  }

  @Override
  public void setBytes(String a0, byte[] a1) throws java.sql.SQLException {
    delegate.setBytes(a0, a1);
  }

  @Override
  public void setCharacterStream(String a0, java.io.Reader a1, int a2)
      throws java.sql.SQLException {
    delegate.setCharacterStream(a0, a1, a2);
  }

  @Override
  public void setCharacterStream(String a0, java.io.Reader a1, long a2)
      throws java.sql.SQLException {
    delegate.setCharacterStream(a0, a1, a2);
  }

  @Override
  public void setCharacterStream(String a0, java.io.Reader a1) throws java.sql.SQLException {
    delegate.setCharacterStream(a0, a1);
  }

  @Override
  public void setClob(String a0, java.io.Reader a1, long a2) throws java.sql.SQLException {
    delegate.setClob(a0, a1, a2);
  }

  @Override
  public void setClob(String a0, java.io.Reader a1) throws java.sql.SQLException {
    delegate.setClob(a0, a1);
  }

  @Override
  public void setClob(String a0, java.sql.Clob a1) throws java.sql.SQLException {
    delegate.setClob(a0, a1);
  }

  @Override
  public void setDate(String a0, java.sql.Date a1, java.util.Calendar a2)
      throws java.sql.SQLException {
    delegate.setDate(a0, a1, a2);
  }

  @Override
  public void setDate(String a0, java.sql.Date a1) throws java.sql.SQLException {
    delegate.setDate(a0, a1);
  }

  @Override
  public void setDouble(String a0, double a1) throws java.sql.SQLException {
    delegate.setDouble(a0, a1);
  }

  @Override
  public void setFloat(String a0, float a1) throws java.sql.SQLException {
    delegate.setFloat(a0, a1);
  }

  @Override
  public void setInt(String a0, int a1) throws java.sql.SQLException {
    delegate.setInt(a0, a1);
  }

  @Override
  public void setLong(String a0, long a1) throws java.sql.SQLException {
    delegate.setLong(a0, a1);
  }

  @Override
  public void setNCharacterStream(String a0, java.io.Reader a1, long a2)
      throws java.sql.SQLException {
    delegate.setNCharacterStream(a0, a1, a2);
  }

  @Override
  public void setNCharacterStream(String a0, java.io.Reader a1) throws java.sql.SQLException {
    delegate.setNCharacterStream(a0, a1);
  }

  @Override
  public void setNClob(String a0, java.io.Reader a1, long a2) throws java.sql.SQLException {
    delegate.setNClob(a0, a1, a2);
  }

  @Override
  public void setNClob(String a0, java.io.Reader a1) throws java.sql.SQLException {
    delegate.setNClob(a0, a1);
  }

  @Override
  public void setNClob(String a0, java.sql.NClob a1) throws java.sql.SQLException {
    delegate.setNClob(a0, a1);
  }

  @Override
  public void setNString(String a0, String a1) throws java.sql.SQLException {
    delegate.setNString(a0, a1);
  }

  @Override
  public void setNull(String a0, int a1, String a2) throws java.sql.SQLException {
    delegate.setNull(a0, a1, a2);
  }

  @Override
  public void setNull(String a0, int a1) throws java.sql.SQLException {
    delegate.setNull(a0, a1);
  }

  @Override
  public void setObject(String a0, Object a1, int a2, int a3) throws java.sql.SQLException {
    delegate.setObject(a0, a1, a2, a3);
  }

  @Override
  public void setObject(String a0, Object a1, int a2) throws java.sql.SQLException {
    delegate.setObject(a0, a1, a2);
  }

  @Override
  public void setObject(String a0, Object a1) throws java.sql.SQLException {
    delegate.setObject(a0, a1);
  }

  @Override
  public void setRowId(String a0, java.sql.RowId a1) throws java.sql.SQLException {
    delegate.setRowId(a0, a1);
  }

  @Override
  public void setSQLXML(String a0, java.sql.SQLXML a1) throws java.sql.SQLException {
    delegate.setSQLXML(a0, a1);
  }

  @Override
  public void setShort(String a0, short a1) throws java.sql.SQLException {
    delegate.setShort(a0, a1);
  }

  @Override
  public void setString(String a0, String a1) throws java.sql.SQLException {
    delegate.setString(a0, a1);
  }

  @Override
  public void setTime(String a0, java.sql.Time a1, java.util.Calendar a2)
      throws java.sql.SQLException {
    delegate.setTime(a0, a1, a2);
  }

  @Override
  public void setTime(String a0, java.sql.Time a1) throws java.sql.SQLException {
    delegate.setTime(a0, a1);
  }

  @Override
  public void setTimestamp(String a0, java.sql.Timestamp a1, java.util.Calendar a2)
      throws java.sql.SQLException {
    delegate.setTimestamp(a0, a1, a2);
  }

  @Override
  public void setTimestamp(String a0, java.sql.Timestamp a1) throws java.sql.SQLException {
    delegate.setTimestamp(a0, a1);
  }

  @Override
  public void setURL(String a0, java.net.URL a1) throws java.sql.SQLException {
    delegate.setURL(a0, a1);
  }

  @Override
  public boolean wasNull() throws java.sql.SQLException {
    return delegate.wasNull();
  }
}
