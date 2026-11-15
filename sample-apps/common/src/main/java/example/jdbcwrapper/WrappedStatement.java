/* SPDX-License-Identifier: Apache-2.0 */
package example.jdbcwrapper;

/** A plain {@link java.sql.Statement} of {@link WrappedConnection}, delegating to the driver's. */
public final class WrappedStatement extends AbstractWrappedStatement<java.sql.Statement> {

  WrappedStatement(WrappedConnection connection, java.sql.Statement delegate) {
    super(connection, delegate);
  }
}
