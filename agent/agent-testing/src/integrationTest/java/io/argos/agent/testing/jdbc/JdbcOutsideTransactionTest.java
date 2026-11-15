/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.jdbc.JdbcRuns.Combo;
import io.argos.agent.testing.jdbc.JdbcRuns.Profile;
import io.argos.agent.testing.jdbc.JdbcRuns.Run;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T-AG-036, JDBC part (D-20): SQL the app runs outside any transaction (the schema and data the
 * sample apps create at startup, before the first request: DDL, a count, batch inserts) creates no
 * step and no transaction, and raises {@code argos_agent_sql_outside_tx_total} in the agent's
 * self-diagnostics (in-memory H2, every shop jar app of {@code -Pagent.e2e}).
 */
@Tag("req:D-20")
@ExtendWith(JdbcRuns.class)
class JdbcOutsideTransactionTest {
  /** Startup SQL of ShopSchema on H2 at least: 6 objects, the alias and the emptiness count. */
  static final long MIN_STARTUP_STATEMENTS = 8;

  @TestFactory
  @DisplayName("T-AG-036 SQL outside a transaction: no step, counted in sql_outside_tx_total")
  Stream<DynamicTest> startupSql() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : JdbcRuns.jarCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-036 " + combo + ": the startup schema and data SQL is counted, never recorded",
              () -> check(JdbcRuns.run(combo, JdbcDatabase.H2, Profile.DEFAULT))));
    }
    return tests.stream();
  }

  static void check(Run run) throws Exception {
    // One request makes sure the agent is up and writes a fresh snapshot after the startup.
    run.request("GET", "/api/orders/1", 200);
    SelfDiagnostics diagnostics = run.freshSelfDiagnostics();
    long outside = diagnostics.value("sql_outside_tx_total");
    assertThat(outside)
        .as("argos_agent_sql_outside_tx_total of %s after the startup SQL", run.name)
        .isGreaterThanOrEqualTo(MIN_STARTUP_STATEMENTS);
    for (TransactionRecord tx : run.collected().transactions()) {
      assertThat(tx.kind()).as("kind of %s", tx).isEqualTo("HTTP");
      for (TransactionRecord.StepRecord step : tx.steps("SQL")) {
        String name = step.name() == null ? "" : step.name().toUpperCase(Locale.ROOT);
        assertThat(name)
            .as("startup SQL attached to a request transaction: %s", step)
            .doesNotStartWith("CREATE")
            .doesNotStartWith("INSERT INTO CUSTOMERS")
            .doesNotStartWith("INSERT INTO ORDER_ITEMS");
      }
    }
    System.out.println("[wo13] T-AG-036 " + run.name + ": sql_outside_tx_total " + outside);
  }
}
