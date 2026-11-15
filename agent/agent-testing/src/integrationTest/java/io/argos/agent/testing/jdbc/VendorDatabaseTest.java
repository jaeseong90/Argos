/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jdbc;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * The vendor databases without a container image (18 §6.3, ADR-0010 C-149 (4)): in-house Oracle
 * 19c, Tibero, Altibase, CUBRID and DB2 are reached only on trusted hosts through {@code
 * ARGOS_TEST_<DB>_URL}, {@code _USER} and {@code _PASSWORD}; they are human checks (사람 확인) and skip
 * with the reason when the URL is not set, so the compatibility table shows △.
 *
 * <p>Oracle 19c runs through {@link JdbcStepsTest} as the {@code oracle} database when {@code
 * ARGOS_TEST_ORACLE_URL} is set (ojdbc11 of shop-boot3). Tibero, Altibase, CUBRID and DB2 need
 * their vendor driver jar (not redistributable, docs/licenses/dev-only.md {@code vendor:} rows) and
 * a {@code SAMPLE_DB} value with its schema script, which come with the 9-database matrix (WO-50):
 * with the URL set they skip with that reason instead of passing.
 */
@Tag("req:F-A03")
class VendorDatabaseTest {
  static final List<String> VENDORS = List.of("ORACLE", "TIBERO", "ALTIBASE", "CUBRID", "DB2");

  @TestFactory
  @DisplayName("T-AG-030 vendor databases on trusted hosts (human check, ARGOS_TEST_<DB>_URL)")
  Stream<DynamicTest> vendors() {
    return VENDORS.stream()
        .map(
            vendor ->
                DynamicTest.dynamicTest(
                    "T-AG-030 " + vendor.toLowerCase(Locale.ROOT) + " on a trusted host",
                    () -> check(vendor)));
  }

  static void check(String vendor) {
    String variable = "ARGOS_TEST_" + vendor + "_URL";
    String url = System.getenv(variable);
    Assumptions.assumeTrue(
        url != null && !url.isBlank(),
        variable
            + " is not set: "
            + vendor.toLowerCase(Locale.ROOT)
            + " is a human check on a trusted host (ADR-0010 C-149 (4), compatibility table △)");
    Assumptions.assumeTrue(
        vendor.equals("ORACLE"),
        vendor.toLowerCase(Locale.ROOT)
            + ": the sample apps have no SAMPLE_DB value and no driver for it yet (vendor jar"
            + " outside the repository, 9-database matrix of WO-50)");
    Assumptions.assumeTrue(
        false,
        "in-house Oracle 19c runs as the 'oracle' database of JdbcStepsTest when "
            + variable
            + " is set");
  }
}
