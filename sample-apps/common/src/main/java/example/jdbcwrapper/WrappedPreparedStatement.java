/* SPDX-License-Identifier: Apache-2.0 */
package example.jdbcwrapper;

/**
 * A {@link java.sql.PreparedStatement} of {@link WrappedConnection}, delegating to the driver's.
 */
public final class WrappedPreparedStatement
    extends AbstractWrappedPreparedStatement<java.sql.PreparedStatement> {

  WrappedPreparedStatement(WrappedConnection connection, java.sql.PreparedStatement delegate) {
    super(connection, delegate);
  }
}
