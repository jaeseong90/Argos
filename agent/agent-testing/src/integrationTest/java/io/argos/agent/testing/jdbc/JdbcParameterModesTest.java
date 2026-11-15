/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.export.TransactionRecord.StepRecord;
import io.argos.agent.testing.jdbc.JdbcRuns.Combo;
import io.argos.agent.testing.jdbc.JdbcRuns.Profile;
import io.argos.agent.testing.jdbc.JdbcRuns.Run;
import io.argos.sample.common.seed.Seeds;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T-AG-033 end to end (F-A04, SEC-3A agent side, 16 §6.6/§6.7): the three {@code
 * capture.sql-parameters} modes on the seed endpoints of the reference app (in-memory H2, the first
 * shop jar app of {@code -Pagent.e2e}), read from the SQL steps' {@code detail} the FakeCollector
 * received. Every seed endpoint binds its value to {@code email = ?}.
 *
 * <ul>
 *   <li>{@code masked} (default): no bound value at all, neither the seed nor its masked form.
 *   <li>{@code partial} with {@code whitelist-columns=email}: the whitelisted column's value after
 *       the 16 §6.6 mask set ({@code ARGOS_SEED.kim@example.test} gives {@code <email>}; an XSS
 *       payload, which no pattern matches, is sent as it is).
 *   <li>{@code partial} with another whitelisted column: type and length only.
 *   <li>{@code full}: every value after the 16 §6.6 mask set (the server drops it until envelope
 *       encryption, ADR-0010 C-148, WO-71).
 * </ul>
 *
 * In every mode the agent log carries no seed (SEC-26). The type allow-list, LOB and stream values,
 * the 256-character and 64-index bounds are the JDBC module's unit tests (T-AG-033 letters there).
 */
@Tag("req:F-A04")
@Tag("req:SEC-3A")
@ExtendWith(JdbcRuns.class)
class JdbcParameterModesTest {
  static final String EMAIL_SEED = "/seed/email?email=" + Seeds.EMAIL.replace("@", "%40");
  static final String XSS_SEED = "/seed/xss-sql?i=0";

  @TestFactory
  @DisplayName("T-AG-033 bind parameter modes masked, partial and full end to end (16 §6.7)")
  Stream<DynamicTest> modes() {
    Combo combo = JdbcRuns.jarCombos().get(0);
    List<DynamicTest> tests = new ArrayList<>();
    tests.add(
        DynamicTest.dynamicTest(
            "T-AG-033 " + combo + " masked (default): no bound value in any SQL step",
            () -> {
              Run run = JdbcRuns.run(combo, JdbcDatabase.H2, Profile.DEFAULT);
              for (String path : List.of(EMAIL_SEED, XSS_SEED)) {
                String details = details(run, path);
                assertThat(details)
                    .as("details of %s in masked mode", path)
                    .doesNotContain(Seeds.SENTINEL)
                    .doesNotContain("kim@example.test")
                    .doesNotContain("<email>");
              }
              cleanLog(run);
            }));
    tests.add(
        DynamicTest.dynamicTest(
            "T-AG-033 "
                + combo
                + " partial, whitelisted column email: the value after the 16 §6.6"
                + " mask set",
            () -> {
              Run run = JdbcRuns.run(combo, JdbcDatabase.H2, Profile.PARTIAL);
              assertThat(details(run, EMAIL_SEED))
                  .contains("<email>")
                  .doesNotContain("kim@example.test");
              assertThat(details(run, XSS_SEED))
                  .as("a whitelisted value no pattern matches is sent")
                  .contains(Seeds.SENTINEL);
              cleanLog(run);
            }));
    tests.add(
        DynamicTest.dynamicTest(
            "T-AG-033 " + combo + " partial, another whitelisted column: type and length only",
            () -> {
              Run run = JdbcRuns.run(combo, JdbcDatabase.H2, Profile.PARTIAL_OTHER_COLUMN);
              for (String path : List.of(EMAIL_SEED, XSS_SEED)) {
                assertThat(details(run, path))
                    .as("details of %s, email not whitelisted", path)
                    .contains("String")
                    .doesNotContain(Seeds.SENTINEL)
                    .doesNotContain("kim@example.test")
                    .doesNotContain("<email>");
              }
              cleanLog(run);
            }));
    tests.add(
        DynamicTest.dynamicTest(
            "T-AG-033 " + combo + " full: every value after the 16 §6.6 mask set",
            () -> {
              Run run = JdbcRuns.run(combo, JdbcDatabase.H2, Profile.FULL);
              assertThat(details(run, EMAIL_SEED))
                  .contains("<email>")
                  .doesNotContain("kim@example.test");
              assertThat(details(run, XSS_SEED)).contains(Seeds.SENTINEL);
              cleanLog(run);
            }));
    return tests.stream();
  }

  /** The joined {@code detail} of the SQL steps of one request of {@code path}. */
  static String details(Run run, String path) throws Exception {
    TransactionRecord tx = run.request("GET", path, 200);
    List<StepRecord> sql = tx.steps("SQL");
    assertThat(sql).as("SQL steps of %s: %s", path, tx).isNotEmpty();
    String joined =
        String.join(" | ", sql.stream().map(StepRecord::detail).filter(Objects::nonNull).toList());
    System.out.println("[wo13] T-AG-033 " + run.name + " " + path + " details: " + mask(joined));
    return joined;
  }

  /** The agent log of the run has no seed (SEC-26), whatever the mode sends. */
  static void cleanLog(Run run) {
    String log = run.app.agentLog();
    assertThat(log).isNotEmpty();
    assertThat(log.contains(Seeds.SENTINEL)).as("ARGOS_SEED in the agent log").isFalse();
    assertThat(log.contains("kim@example.test")).as("seed e-mail in the agent log").isFalse();
  }

  /** The console line never shows a seed value: only its masked or typed form. */
  static String mask(String details) {
    return details.replace(Seeds.SENTINEL, "<sentinel>");
  }
}
