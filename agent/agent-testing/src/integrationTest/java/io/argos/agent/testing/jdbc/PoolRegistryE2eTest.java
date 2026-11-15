/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Message;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.jdbc.JdbcRuns.Combo;
import io.argos.agent.testing.jdbc.JdbcRuns.Profile;
import io.argos.agent.testing.jdbc.JdbcRuns.Run;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.InstanceUpdate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T-AG-062f, pool and Tomcat part of T-AG-062 end to end (F-A10, DSN-10-12): every shop jar app of
 * {@code -Pagent.e2e} with {@code SAMPLE_POOL=hikari|tomcat|dbcp2} on PostgreSQL (with {@code
 * -Pagent.jdbc.containers}) or in-memory H2, Spring Boot's defaults (JMX off: the apps never enable
 * {@code spring.jmx}, Hikari's {@code registerMbeans} or Tomcat JDBC's {@code jmxEnabled}):
 *
 * <ul>
 *   <li>the registry snapshot of the development self-diagnostics file lists the pool by its name
 *       and kind with plausible values (max = SAMPLE_POOL_MAX 10, active and idle within it;
 *       DBCP2's waiting and total missing, not zero);
 *   <li>the started Tomcat connector is listed with the app's port and its thread and connection
 *       values;
 *   <li>the FakeCollector received an {@code InstanceUpdate} with the connector name and the
 *       instance name {@code host:port} (the harness sets no {@code app.instance}).
 * </ul>
 *
 * T-AG-062g, end to end too: with {@code SAMPLE_SECOND_POOL=true} (HikariCP and Tomcat JDBC) the
 * app has two pools on the same database, {@code shop-primary} (its own, SAMPLE_POOL_MAX 10) and
 * {@code shop-secondary}, which set up the schema with 2 connections, was closed and was created
 * again with 3: the registry lists both by name, the second one once and with the restarted pool's
 * maximum (re-registered, the closed pool replaced). A second class loader and a failing getter are
 * the pool module's tests with the real libraries (T-AG-062d); metric transport is WO-16.
 *
 * <p>T-AG-032d (end-to-end part of T-AG-032 per pool library, ADR-0010 C-143 (4)): the DBC step of
 * the JPA find carries the name of the registered pool it borrowed from, for HikariCP, Tomcat JDBC,
 * DBCP2 and the two-pool apps ({@code shop-primary}).
 */
@Tag("req:F-A10")
@Tag("req:F-A03")
@Tag("req:DSN-10-12")
@ExtendWith(JdbcRuns.class)
class PoolRegistryE2eTest {
  record PoolCase(Profile profile, String kind, String namePrefix) {}

  static final List<PoolCase> POOLS =
      List.of(
          new PoolCase(Profile.DEFAULT, "hikari", "HikariPool-"),
          new PoolCase(Profile.POOL_TOMCAT, "tomcat-jdbc", "Tomcat Connection Pool["),
          new PoolCase(Profile.POOL_DBCP2, "dbcp2", "BasicDataSource-"));

  @TestFactory
  @DisplayName(
      "T-AG-062f pool and Tomcat registries without JMX: getter values by name, DBC step name,"
          + " InstanceUpdate host:port")
  Stream<DynamicTest> registries() {
    JdbcDatabase db = JdbcDatabase.POSTGRES.enabled() ? JdbcDatabase.POSTGRES : JdbcDatabase.H2;
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : JdbcRuns.jarCombos()) {
      for (PoolCase pool : POOLS) {
        tests.add(
            DynamicTest.dynamicTest(
                "T-AG-062f "
                    + combo
                    + " "
                    + db.id
                    + " SAMPLE_POOL="
                    + pool.kind()
                    + ": pool and connector values from the getters",
                () -> check(JdbcRuns.run(combo, db, pool.profile()), pool)));
      }
    }
    return tests.stream();
  }

  @TestFactory
  @DisplayName(
      "T-AG-062g two pools of one app told apart by name, a restarted pool re-registered"
          + " (SAMPLE_SECOND_POOL)")
  Stream<DynamicTest> twoPools() {
    JdbcDatabase db = JdbcDatabase.POSTGRES.enabled() ? JdbcDatabase.POSTGRES : JdbcDatabase.H2;
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : JdbcRuns.jarCombos()) {
      for (PoolCase pool :
          List.of(
              new PoolCase(Profile.SECOND_POOL, "hikari", "shop-"),
              new PoolCase(Profile.SECOND_POOL_TOMCAT, "tomcat-jdbc", "shop-"))) {
        tests.add(
            DynamicTest.dynamicTest(
                "T-AG-062g "
                    + combo
                    + " "
                    + db.id
                    + " SAMPLE_POOL="
                    + pool.kind()
                    + " SAMPLE_SECOND_POOL=true: shop-primary and the restarted shop-secondary",
                () -> checkTwoPools(JdbcRuns.run(combo, db, pool.profile()), pool)));
      }
    }
    return tests.stream();
  }

  @TestFactory
  @DisplayName(
      "T-AG-032d the DBC step of a request carries the name of the pool it borrowed from, for every"
          + " pool library (ADR-0010 C-143 (4))")
  Stream<DynamicTest> dbcStepNames() {
    JdbcDatabase db = JdbcDatabase.POSTGRES.enabled() ? JdbcDatabase.POSTGRES : JdbcDatabase.H2;
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : JdbcRuns.jarCombos()) {
      for (PoolCase pool :
          List.of(
              POOLS.get(0),
              POOLS.get(1),
              POOLS.get(2),
              new PoolCase(Profile.SECOND_POOL, "hikari", "shop-primary"),
              new PoolCase(Profile.SECOND_POOL_TOMCAT, "tomcat-jdbc", "shop-primary"))) {
        tests.add(
            DynamicTest.dynamicTest(
                "T-AG-032d "
                    + combo
                    + " "
                    + db.id
                    + " "
                    + pool.profile().name().toLowerCase(Locale.ROOT)
                    + " ("
                    + pool.kind()
                    + "): one DBC step named "
                    + pool.namePrefix()
                    + "...",
                () -> checkDbcName(JdbcRuns.run(combo, db, pool.profile()), pool)));
      }
    }
    return tests.stream();
  }

  /**
   * T-AG-032d: one DBC step for the JPA find, named after the registered pool the request used
   * ({@code shop-primary} when the app has two pools).
   */
  static void checkDbcName(Run run, PoolCase pool) throws Exception {
    TransactionRecord find = run.request("GET", "/api/orders/1", 200);
    SelfDiagnostics diagnostics = run.freshSelfDiagnostics();
    List<String> names = diagnostics.pools().stream().map(SelfDiagnostics.Gauges::name).toList();
    String expected =
        names.stream()
            .filter(name -> name.startsWith(pool.namePrefix()))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "no registered pool named " + pool.namePrefix() + "... among " + names));
    assertThat(find.steps("DBC"))
        .as("DBC step of %s on %s (registered pools %s)", find, run.name, names)
        .singleElement()
        .satisfies(step -> assertThat(step.name()).as("DBC step name").isEqualTo(expected));
    System.out.println("[wo13] T-AG-032d " + run.name + ": DBC step " + expected);
  }

  static void checkTwoPools(Run run, PoolCase pool) throws Exception {
    TransactionRecord find = run.request("GET", "/api/orders/1", 200);
    SelfDiagnostics diagnostics = run.freshSelfDiagnostics();
    List<SelfDiagnostics.Gauges> pools = diagnostics.pools();
    assertThat(pools)
        .as("pools of %s", run.name)
        .extracting(SelfDiagnostics.Gauges::name)
        .containsExactlyInAnyOrder("shop-primary", "shop-secondary");
    for (SelfDiagnostics.Gauges registered : pools) {
      assertThat(registered.kind()).as("%s", registered).isEqualTo(pool.kind());
      long expectedMax = registered.name().equals("shop-primary") ? 10 : 3;
      assertThat(registered.value("max"))
          .as("max of %s (shop-secondary: 3 after the restart, 2 before)", registered)
          .isEqualTo(expectedMax);
      assertThat(registered.value("active")).isBetween(0L, expectedMax);
      assertThat(registered.value("idle")).isBetween(0L, expectedMax);
    }
    assertThat(find.status()).as("the request of %s", run.name).isEqualTo(200);
    assertThat(diagnostics.counter("argos_agent_registry_getter_errors_total{registry=\"pool\"}"))
        .isZero();
    System.out.println("[wo13] T-AG-062g " + run.name + ": pools " + pools);
  }

  static void check(Run run, PoolCase pool) throws Exception {
    TransactionRecord find = run.request("GET", "/api/orders/1", 200);
    SelfDiagnostics diagnostics = run.freshSelfDiagnostics();
    List<SelfDiagnostics.Gauges> pools = diagnostics.pools();
    assertThat(pools).as("pools of %s", run.name).hasSize(1);
    SelfDiagnostics.Gauges registered = pools.get(0);
    assertThat(registered.kind()).isEqualTo(pool.kind());
    assertThat(registered.name()).startsWith(pool.namePrefix());
    assertThat(registered.value("max")).as("SAMPLE_POOL_MAX default").isEqualTo(10);
    long active = registered.value("active");
    long idle = registered.value("idle");
    assertThat(active).isBetween(0L, 10L);
    assertThat(idle).isBetween(0L, 10L);
    assertThat(active + idle).as("active + idle of %s", registered).isLessThanOrEqualTo(10);
    if (pool.kind().equals("dbcp2")) {
      assertThat(registered.has("waiting")).as("DBCP2 has no waiting getter: missing").isFalse();
      assertThat(registered.has("total")).isFalse();
    } else {
      assertThat(registered.value("waiting")).isZero();
      assertThat(registered.value("total")).isBetween(active, 10L);
    }
    assertThat(find.status()).as("the request of %s", run.name).isEqualTo(200);

    List<SelfDiagnostics.Gauges> connectors = diagnostics.connectors();
    assertThat(connectors).as("connectors of %s", run.name).hasSize(1);
    SelfDiagnostics.Gauges connector = connectors.get(0);
    int port = run.app.port();
    assertThat(connector.name()).contains(Integer.toString(port));
    assertThat(connector.value("port")).isEqualTo(port);
    assertThat(connector.value("threads_current")).isPositive();
    assertThat(connector.value("threads_busy")).isBetween(0L, connector.value("threads_current"));
    assertThat(connector.value("threads_max")).as("Spring Boot's default").isEqualTo(200);
    assertThat(connector.value("connections_max")).isPositive();
    assertThat(connector.value("connections_current")).isGreaterThanOrEqualTo(0);
    assertThat(diagnostics.counter("argos_agent_registry_getter_errors_total{registry=\"pool\"}"))
        .isZero();
    assertThat(diagnostics.counter("argos_agent_registry_getter_errors_total{registry=\"tomcat\"}"))
        .isZero();

    // The session sends changed values only: the connector list and the instance name may arrive
    // in separate InstanceUpdate messages (AgentSession).
    InstanceUpdate connectorsUpdate =
        awaitInstanceUpdate(
            run, update -> update.getConnectorsList().contains(connector.name()), "connectors");
    InstanceUpdate nameUpdate =
        awaitInstanceUpdate(
            run,
            update -> update.hasInstanceName() && update.getInstanceName().endsWith(":" + port),
            "instance_name <host>:" + port);
    assertThat(nameUpdate.getInstanceName()).isNotEqualTo(":" + port);
    System.out.println(
        "[wo13] T-AG-062f "
            + run.name
            + ": pool "
            + registered
            + ", connector "
            + connector
            + ", InstanceUpdate instance_name="
            + nameUpdate.getInstanceName()
            + " connectors="
            + connectorsUpdate.getConnectorsList());
  }

  /** The last InstanceUpdate {@code wanted} accepts (sent on start and on reconnects). */
  static InstanceUpdate awaitInstanceUpdate(Run run, Predicate<InstanceUpdate> wanted, String what)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (true) {
      InstanceUpdate found = null;
      for (Message message : run.collector.sessionMessages()) {
        if (message instanceof AgentMessage agent
            && agent.hasInstanceUpdate()
            && wanted.test(agent.getInstanceUpdate())) {
          found = agent.getInstanceUpdate();
        }
      }
      if (found != null || System.nanoTime() > deadline) {
        assertThat(found)
            .as(
                "InstanceUpdate with %s among %s",
                what, Map.of("session messages", run.collector.sessionMessages().size()))
            .isNotNull();
        return found;
      }
      Thread.sleep(250);
    }
  }
}
