/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.ReaderQueries;
import io.argos.server.storage.query.RollupAliases;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.querydata.QueryTestData;
import io.argos.server.web.RequestIdFilter;
import io.argos.server.web.RequestIds;
import io.argos.storage.clickhouse.Account;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The reader profile probe of {@link QueryExecutor} (ADR-0007 C-64, T-ST-053): the first statement
 * of a new executor reads the profile's limits. It is the server's own statement: it runs under an
 * {@code internal-<n>} query id even when a request triggers it, and it carries a {@code SETTINGS}
 * clause like every other statement, so the statements under a request id are exactly the
 * request's, each with its limits (no order dependence between tests that read {@code
 * system.query_log}).
 */
@Tag("integration")
@Tag("req:SEC-76")
class ReaderProfileProbeIT {

  @Test
  @DisplayName(
      "T-ST-053a the reader profile probe runs as argos_reader under an internal- query id with"
          + " SETTINGS max_execution_time; the triggering request's query id prefix holds only its"
          + " own statement")
  void probeIsInternalAndLimited() throws Exception {
    QueryTestData.Shared shared = QueryTestData.shared();
    ArgosProperties properties = shared.server().bean(ArgosProperties.class);
    RollupAliases aliases = shared.server().bean(RollupAliases.class);
    ReaderQueries queries =
        new ReaderQueries(
            ReaderQueries.load("sqltest", ReaderProfileProbeIT.class.getClassLoader()),
            ServerTestEnvironment.DATABASE,
            "",
            aliases::physical);
    long since = Instant.now().getEpochSecond() - 1;
    // A new executor: its first statement triggers the probe inside the request.
    QueryExecutor executor = new QueryExecutor(properties.clickhouse(), queries);
    String requestId = new RequestIds().next();
    MDC.put(RequestIdFilter.MDC_KEY, requestId);
    try {
      executor.query(
          "count_xlog_app",
          QueryParams.create().uint32("app", 777_778),
          QueryOptions.defaults().withTimeoutSeconds(3));
    } finally {
      MDC.remove(RequestIdFilter.MDC_KEY);
      executor.destroy();
    }

    List<List<String>> probes = List.of();
    List<List<String>> request = List.of();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (System.nanoTime() < deadline) {
      // Test SQL with server values only (an epoch second and a checked request id).
      probes =
          shared
              .env()
              .clickHouse(Account.READER)
              .rows(
                  "SELECT query_id, user, replaceAll(query, '\\n', ' ')"
                      + " FROM system.query_log WHERE type = 'QueryFinish'"
                      + " AND event_date >= yesterday() AND query_start_time >= toDateTime("
                      + since
                      + ") AND position(query, 'getSetting(''max_rows_to_read'')') > 0"
                      + " AND position(query, 'system.query_log') = 0 ORDER BY event_time");
      request =
          shared
              .env()
              .clickHouse(Account.READER)
              .rows(
                  "SELECT query_id, user, replaceAll(query, '\\n', ' ')"
                      + " FROM system.query_log WHERE type = 'QueryFinish'"
                      + " AND event_date >= yesterday() AND startsWith(query_id, '"
                      + requestId.replaceAll("[^A-Za-z0-9_-]", "")
                      + "-') ORDER BY event_time");
      if (!probes.isEmpty() && !request.isEmpty()) {
        break;
      }
      TimeUnit.MILLISECONDS.sleep(500);
    }

    assertThat(probes).as("reader profile probes since the test started").isNotEmpty();
    for (List<String> row : probes) {
      assertThat(row.get(0)).as("probe query id").startsWith("internal-");
      assertThat(row.get(1)).isEqualTo("argos_reader");
      assertThat(row.get(2)).contains("SETTINGS max_execution_time = 10");
    }
    assertThat(request).as("statements under the request id").hasSize(1);
    assertThat(request.get(0).get(1)).isEqualTo("argos_reader");
    assertThat(request.get(0).get(2))
        .doesNotContain("getSetting(")
        .contains("SETTINGS max_execution_time = 3, max_rows_to_read = ");
  }
}
