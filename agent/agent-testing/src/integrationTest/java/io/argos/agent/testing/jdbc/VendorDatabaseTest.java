/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.jdbc.JdbcRuns.Combo;
import io.argos.agent.testing.jdbc.JdbcRuns.Profile;
import io.argos.agent.testing.jdbc.JdbcRuns.Run;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * The vendor databases without a container image (18 §6.3, ADR-0010 C-149 (4), WO-50): in-house
 * Oracle 19c, Tibero, Altibase, CUBRID and DB2 are reached only on trusted hosts through {@code
 * ARGOS_TEST_<DB>_URL}, {@code _USER} and {@code _PASSWORD}; they are human checks (사람 확인) and skip
 * with the reason when the URL is not set, so the compatibility table shows △.
 *
 * <p>Oracle 19c runs through {@link JdbcStepsTest} as the {@code oracle} database when {@code
 * ARGOS_TEST_ORACLE_URL} is set (ojdbc11 of shop-boot3). Tibero, Altibase, CUBRID and DB2 run here
 * on shop-boot3 ({@code SAMPLE_DB=tibero|altibase|cubrid|db2}): their driver jars are not
 * redistributable or not in the build (docs/licenses/dev-only.md {@code vendor:} rows), so the
 * runner provides them in the folder {@code ARGOS_TEST_JDBC_DRIVER_DIR}, which the app gets as
 * {@code SAMPLE_JDBC_DRIVER_DIR} and loads itself ({@code VendorDriver} of sample-apps common).
 * With the URL set, a missing or empty driver folder fails the test (a misconfigured trusted
 * runner, not a skip). Each database gets the checks of {@link JdbcStepsTest} (T-AG-030 one SQL
 * step, T-AG-032 DBC/COMMIT/ROLLBACK/batch, T-AG-035a DB_TARGET shape, T-SEC-3Aa seeds) and, with
 * the routing data source and driver wrapper, T-AG-031 (one SQL step through five levels).
 */
@Tag("req:F-A03")
@Tag("req:D-24")
class VendorDatabaseTest {
  static final List<String> VENDORS = List.of("ORACLE", "TIBERO", "ALTIBASE", "CUBRID", "DB2");

  @TestFactory
  @DisplayName("T-AG-030 vendor databases on trusted hosts (human check, ARGOS_TEST_<DB>_URL)")
  Stream<DynamicNode> vendors() {
    List<DynamicNode> nodes = new ArrayList<>();
    for (String vendor : VENDORS) {
      String id = vendor.toLowerCase(Locale.ROOT);
      String url = System.getenv("ARGOS_TEST_" + vendor + "_URL");
      if (vendor.equals("ORACLE") || url == null || url.isBlank()) {
        nodes.add(
            DynamicTest.dynamicTest(
                "T-AG-030 " + id + " on a trusted host", () -> skipReason(vendor, url)));
        continue;
      }
      JdbcDatabase db = JdbcDatabase.valueOf(vendor);
      Combo combo = shopBoot3();
      nodes.add(
          DynamicTest.dynamicTest(
              "T-AG-030 / T-AG-032 / T-AG-035a / T-SEC-3Aa "
                  + id
                  + " "
                  + combo
                  + ": one SQL step per JPA find, DBC/COMMIT/ROLLBACK/batch, DB_TARGET shape,"
                  + " masked seeds",
              () -> {
                try (Run run = start(combo, db, Profile.DEFAULT)) {
                  JdbcStepsTest.oneSqlStep(run);
                  JdbcStepsTest.connectionsAndTransactions(run);
                  JdbcStepsTest.target(run);
                  JdbcStepsTest.seeds(run);
                }
              }));
      nodes.add(
          DynamicTest.dynamicTest(
              "T-AG-031 "
                  + id
                  + " "
                  + combo
                  + ": routing data source, transaction-aware proxy, pool, driver wrapper and"
                  + " the vendor driver: one SQL step",
              () -> {
                try (Run run = start(combo, db, Profile.NESTED)) {
                  JdbcNesting.oneStepThroughFiveLevels(run, true);
                }
              }));
    }
    return nodes.stream();
  }

  static void skipReason(String vendor, String url) {
    String id = vendor.toLowerCase(Locale.ROOT);
    String variable = "ARGOS_TEST_" + vendor + "_URL";
    Assumptions.assumeTrue(
        url != null && !url.isBlank(),
        variable
            + " is not set: "
            + id
            + " is a human check on a trusted host (ADR-0010 C-149 (4), compatibility table △)");
    Assumptions.assumeTrue(
        false,
        "in-house Oracle 19c runs as the 'oracle' database of JdbcStepsTest when "
            + variable
            + " is set");
  }

  /** shop-boot3, the app with every JDBC driver of the suite, on the JDK of -Pagent.e2e. */
  static Combo shopBoot3() {
    return JdbcRuns.jarCombos().stream()
        .filter(c -> c.app().equals("shop-boot3"))
        .findFirst()
        .orElse(new Combo("shop-boot3", 21));
  }

  /** The vendor driver folder of the runner: set, a directory, with at least one jar. */
  static Path driverDir(JdbcDatabase db) {
    String dir = System.getenv(JdbcDatabase.DRIVER_DIR);
    assertThat(dir)
        .as(
            "%s is set, so the runner must provide the %s driver jar in %s",
            db.vendorVariable("URL"), db.id, JdbcDatabase.DRIVER_DIR)
        .isNotBlank();
    File[] jars = new File(dir).listFiles((parent, name) -> name.endsWith(".jar"));
    assertThat(jars).as("jar files in %s (%s)", JdbcDatabase.DRIVER_DIR, dir).isNotEmpty();
    return Path.of(dir).toAbsolutePath();
  }

  /** Starts shop-boot3 with the agent on the in-house database {@code db}. */
  static Run start(Combo combo, JdbcDatabase db, Profile profile) throws Exception {
    JdbcDatabase.Server server = db.inHouse();
    Path work = Files.createTempDirectory("wo50-vendor-");
    String name = combo.app() + "-jdk" + combo.jdk() + "-" + db.id + "-" + profile;
    AppLauncher launcher =
        AppLauncher.of(SampleApp.named(combo.app()), Jdk.of(combo.jdk()))
            .withEnv("SAMPLE_DB", db.id)
            .withEnv("SAMPLE_DB_URL", server.url)
            .withEnv("SAMPLE_JDBC_DRIVER_DIR", driverDir(db).toString())
            .workDir(work.resolve(name.toLowerCase(Locale.ROOT)))
            .readyTimeout(Duration.ofMinutes(4))
            .withAgent(
                Path.of(JdbcRuns.required("argos.test.releaseAgentJar")),
                new LinkedHashMap<>(profile.agentSettings()));
    if (server.user != null) {
      launcher.withEnv("SAMPLE_DB_USER", server.user);
    }
    if (server.password != null) {
      launcher.withEnv("SAMPLE_DB_PASSWORD", server.password);
    }
    profile.appEnv().forEach(launcher::withEnv);
    FakeCollector collector = FakeCollector.builder().tls(true).start();
    launcher.collector(collector);
    try {
      RunningApp running = launcher.start(Duration.ofMinutes(4));
      System.out.println("[wo50-vendor] " + name + ": port " + running.port());
      return new Run("wo50-" + name, combo, db, server, profile, running, collector);
    } catch (Exception | Error e) {
      collector.close();
      throw e;
    }
  }
}
