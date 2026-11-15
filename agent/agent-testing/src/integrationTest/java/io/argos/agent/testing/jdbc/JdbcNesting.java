/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jdbc;

import static io.argos.agent.testing.jdbc.JdbcRuns.number;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.baseline.TransformList;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.jdbc.JdbcRuns.Run;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import java.time.Duration;
import java.util.List;

/**
 * The D-24 nesting fixture (T-AG-031, T-ACC-24, T-AG-032c): SAMPLE_ROUTING_DS=true and
 * SAMPLE_DRIVER_WRAPPER=true put five JDBC implementations under every statement ({@code
 * SampleRoutingDataSource (AbstractRoutingDataSource) -> TransactionAwareDataSourceProxy -> pool ->
 * WrappingDriver -> driver}, ADR-0004 C-40); one statement must still be one SQL step and {@code
 * sql_count} 1 (T-AG-031, T-ACC-24: 18 appendix A and §10), one connection one DBC step named by
 * the pool (T-AG-032c: 10 §6.3 "라우팅 DataSource 중첩 getConnection DBC 1개", ADR-0010 C-143 (4)).
 */
final class JdbcNesting {
  /** The wrapper classes the agent must transform (outside io.argos on purpose). */
  static final List<String> WRAPPER_CLASSES =
      List.of(
          "example.jdbcwrapper.WrappedConnection",
          "example.jdbcwrapper.AbstractWrappedStatement",
          "example.jdbcwrapper.AbstractWrappedPreparedStatement");

  private JdbcNesting() {}

  static void oneStepThroughFiveLevels(Run run, boolean levels) throws Exception {
    HttpResult runtime = run.call("GET", "/api/runtime");
    assertThat(runtime.status()).isEqualTo(200);
    String body = runtime.body();
    assertThat(body)
        .as("GET /api/runtime of %s", run.name)
        .contains("\"routingDataSource\":true")
        .contains("\"driverWrapper\":true")
        .contains("WrappingDriver (java.sql.Driver wrapper)");

    TransactionRecord find = run.request("GET", "/api/orders/1", 200);
    assertThat(find.steps("SQL")).as("SQL steps through five levels: %s", find).hasSize(1);
    assertThat(number(find, "sql_count")).as("sql_count of %s", find).isEqualTo(1);

    TransactionRecord page = run.request("GET", "/api/orders?page=0", 200);
    assertThat(page.steps("SQL")).as("SQL steps of the page endpoint: %s", page).hasSize(3);
    assertThat(number(page, "sql_count")).isEqualTo(3);

    TransactionRecord statement = run.request("GET", "/api/stmt?q=kim", 200);
    assertThat(statement.steps("SQL")).as("plain Statement: %s", statement).hasSize(1);

    TransactionRecord commit = run.request("POST", "/api/tx/commit", 200);
    assertThat(commit.steps("SQL")).hasSize(2);
    assertThat(commit.steps("COMMIT")).as("one COMMIT through the levels: %s", commit).hasSize(1);

    HttpResult after = run.call("GET", "/api/runtime");
    assertThat(JdbcNesting.jsonLong(after.body(), "driverWrapperConnections"))
        .as("connections through the driver wrapper")
        .isPositive();
    assertThat(JdbcNesting.jsonLong(after.body(), "routingLookups"))
        .as("connections through the routing data source")
        .isPositive();
    if (levels) {
      TransformList transformed =
          run.app.awaitTransformList(
              list -> list.classesOf("jdbc").containsAll(WRAPPER_CLASSES), Duration.ofSeconds(15));
      assertThat(transformed.classesOf("jdbc"))
          .as("the driver wrapper is instrumented like a third-party JDBC wrapper (10 §6.3)")
          .containsAll(WRAPPER_CLASSES);
    }
  }

  /**
   * T-AG-032c: the connection a JPA find takes through the routing data source, the
   * transaction-aware proxy, the pool and the driver wrapper is one DBC step, named after the pool
   * the registry knows (the innermost known pool, ADR-0010 C-143 (4)).
   */
  static void oneDbcStepThroughFiveLevels(Run run) throws Exception {
    TransactionRecord find = run.request("GET", "/api/orders/1", 200);
    SelfDiagnostics diagnostics = run.freshSelfDiagnostics();
    assertThat(diagnostics.pools()).as("pools of %s", run.name).hasSize(1);
    String pool = diagnostics.pools().get(0).name();
    assertThat(find.steps("DBC"))
        .as("DBC steps through the routing chain of %s (pool %s): %s", run.name, pool, find)
        .singleElement()
        .satisfies(step -> assertThat(step.name()).as("DBC step name").isEqualTo(pool));
  }

  /** A number field of a flat JSON body ({@code "name":123}). */
  static long jsonLong(String body, String name) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(name) + "\"\\s*:\\s*(\\d+)")
            .matcher(body);
    assertThat(matcher.find()).as("field %s in %s", name, body).isTrue();
    return Long.parseLong(matcher.group(1));
  }
}
