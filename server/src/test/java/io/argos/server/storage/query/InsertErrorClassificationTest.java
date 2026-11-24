/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Which insert failures refuse a block's content (ADR-0010 C-158 (3); WO-28): schema, type and
 * parse codes are deterministic, everything else (connection, timeout, memory, too many parts,
 * authentication, a missing table, a syntax error of the server's own statement) is transient.
 */
@Tag("req:D-06")
class InsertErrorClassificationTest {

  private static IllegalStateException ch(int code, String name) {
    return new IllegalStateException(
        "Code: " + code + ". DB::Exception: something. (" + name + ")");
  }

  @Test
  @DisplayName("T-SV-046e schema, type and parse codes are deterministic, in any cause depth")
  void deterministic() {
    assertThat(ClickHouseErrors.deterministicInsertCode(ch(33, "CANNOT_READ_ALL_DATA")))
        .isEqualTo(33);
    assertThat(ClickHouseErrors.deterministicInsertCode(ch(27, "CANNOT_PARSE_INPUT")))
        .isEqualTo(27);
    assertThat(ClickHouseErrors.deterministicInsertCode(ch(53, "TYPE_MISMATCH"))).isEqualTo(53);
    assertThat(ClickHouseErrors.deterministicInsertCode(ch(16, "NO_SUCH_COLUMN_IN_TABLE")))
        .isEqualTo(16);
    assertThat(
            ClickHouseErrors.deterministicInsertCode(
                new ExecutionException(
                    new RuntimeException("wrapped", ch(6, "CANNOT_PARSE_TEXT")))))
        .isEqualTo(6);
    assertThat(ClickHouseErrors.DETERMINISTIC_INSERT_CODES)
        .contains(6, 16, 20, 26, 27, 32, 33, 38, 41, 47, 50, 53, 70, 72, 117, 349);
  }

  @Test
  @DisplayName("T-SV-046e transient failures are not deterministic (retried without end)")
  void transientFailures() {
    for (int code : new int[] {241, 252, 159, 202, 209, 210, 192, 194, 516, 60, 81, 62, 999}) {
      assertThat(ClickHouseErrors.deterministicInsertCode(ch(code, "X")))
          .as("code " + code)
          .isZero();
    }
    assertThat(ClickHouseErrors.deterministicInsertCode(new ConnectException("refused"))).isZero();
    assertThat(ClickHouseErrors.deterministicInsertCode(new TimeoutException())).isZero();
    assertThat(ClickHouseErrors.deterministicInsertCode(new IOException("reset"))).isZero();
  }
}
