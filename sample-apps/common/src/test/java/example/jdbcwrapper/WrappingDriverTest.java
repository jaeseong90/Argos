/* SPDX-License-Identifier: Apache-2.0 */
package example.jdbcwrapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SAMPLE_DRIVER_WRAPPER's driver (ADR-0004 C-40) over in-memory H2: it accepts only its own URLs,
 * finds the real driver as a JDBC service, and every connection and statement is a delegating
 * wrapper that answers {@code getConnection()} with the wrapping connection and unwraps to the
 * driver's objects.
 */
class WrappingDriverTest {
  private static final String REAL = "jdbc:h2:mem:wrapper-test;DB_CLOSE_DELAY=-1";

  private static Connection open() throws SQLException {
    Properties account = new Properties();
    account.setProperty("user", "sa");
    account.setProperty("password", "");
    return new WrappingDriver().connect(WrappingDriver.wrap(REAL), account);
  }

  @Test
  @DisplayName("Only jdbc:argos-wrapper: URLs; other URLs get null (java.sql.Driver contract)")
  void urls() throws SQLException {
    WrappingDriver driver = new WrappingDriver();
    assertThat(driver.acceptsURL(WrappingDriver.wrap(REAL))).isTrue();
    assertThat(driver.acceptsURL(REAL)).isFalse();
    assertThat(driver.connect(REAL, new Properties())).isNull();
    assertThatThrownBy(
            () -> driver.connect(WrappingDriver.wrap("jdbc:nosuchdb://h/x"), new Properties()))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("jdbc:nosuchdb")
        .hasMessageNotContaining("//h/x");
  }

  @Test
  @DisplayName(
      "Connections and statements are wrappers over the H2 objects; the count goes up per"
          + " connection")
  void wraps() throws SQLException {
    long before = WrappingDriver.connections();
    try (Connection connection = open()) {
      assertThat(WrappingDriver.connections()).isEqualTo(before + 1);
      assertThat(connection).isInstanceOf(WrappedConnection.class);
      assertThat(connection.isWrapperFor(org.h2.jdbc.JdbcConnection.class)).isTrue();
      assertThat(connection.unwrap(org.h2.jdbc.JdbcConnection.class))
          .isInstanceOf(org.h2.jdbc.JdbcConnection.class);
      assertThat(connection.getMetaData().getURL())
          .as("the real URL")
          .startsWith("jdbc:h2:mem:wrapper-test");
      try (Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery("SELECT 1")) {
        assertThat(statement).isInstanceOf(WrappedStatement.class);
        assertThat(statement.getConnection()).isSameAs(connection);
        assertThat(rs.next()).isTrue();
      }
      try (PreparedStatement statement = connection.prepareStatement("SELECT ? + 1")) {
        assertThat(statement).isInstanceOf(WrappedPreparedStatement.class);
        statement.setInt(1, 41);
        try (ResultSet rs = statement.executeQuery()) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getInt(1)).isEqualTo(42);
        }
        assertThat(statement.getConnection()).isSameAs(connection);
      }
      try (CallableStatement statement = connection.prepareCall("{call ABS(-3)}")) {
        assertThat(statement).isInstanceOf(WrappedCallableStatement.class);
        assertThat(statement.unwrap(CallableStatement.class)).isSameAs(statement);
      }
    }
  }
}
