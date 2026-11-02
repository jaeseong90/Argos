/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ArgosClickHouse;
import io.argos.storage.clickhouse.MemoryBudget;
import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.ColdStrategy;
import io.argos.storage.queries.Measurement;
import io.argos.storage.queries.QueryFile;
import io.argos.storage.queries.QueryLog;
import io.argos.storage.queries.QueryRunner;
import io.argos.storage.queries.Server;
import io.argos.storage.queries.VariantResult;
import io.argos.storage.queries.dataset.CiDataset;
import io.argos.storage.schema.SchemaApplier;
import io.argos.storage.schema.SchemaParameters;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The {@code restart} cold strategy on a server with a named data volume: the cold run of a variant
 * follows a server restart (empty mark cache), the data and system.query_log survive the restart,
 * and the warm runs follow without one.
 */
@Tag("integration")
class ColdRestartIT {

  @Test
  @DisplayName("cold strategy restart: cold run after a restart on the data volume, query_log kept")
  void coldRunAfterRestart() throws InterruptedException {
    String volume = "argos-wo03-queries-cold-" + UUID.randomUUID().toString().substring(0, 8);
    SchemaParameters schema = SchemaParameters.defaults().withTablePrefix("cold_");
    try (Server server = Server.container(volume, 2 * MemoryBudget.GIB, schema.tablePrefix())) {
      SchemaApplier.apply(server.client(Account.MIGRATOR), schema);
      Instant end = Instant.now().truncatedTo(ChronoUnit.HOURS);
      CiDataset.generate(
              new CiDataset.Config(
                  3, end.minus(Duration.ofHours(2)), Duration.ofHours(2), 1.0, 2, 5000))
          .load(server.client(Account.WRITER), schema);
      QueryRunner runner =
          new QueryRunner(
              server,
              schema,
              QueryRunner.Options.defaults()
                  .withCold(ColdStrategy.parse("restart"))
                  .withWarmRuns(2),
              null);
      BenchContext ctx = BenchContext.resolve(runner, Fixture.catalog());
      QueryFile q01 = ctx.file("Q01");
      long uptimeBefore = uptime(server);
      while (uptimeBefore < 15) {
        Thread.sleep(1000);
        uptimeBefore = uptime(server);
      }
      VariantResult r = runner.run(q01, q01.variant("1h"), runner.resolve(q01, ctx.shared()));
      assertThat(server.canRestart()).isTrue();
      assertThat(uptime(server))
          .as("server uptime after the cold run (restarted)")
          .isLessThan(uptimeBefore);
      assertThat(r.runs()).hasSize(3);
      assertThat(r.runs().get(0).phase()).isEqualTo("cold");
      Map<String, QueryLog.Entry> log = runner.collectQueryLog();
      for (Measurement m : r.runs()) {
        assertThat(log).as(m.queryId()).containsKey(m.queryId());
      }
      Evidence.record(
          "cold-restart",
          "Q01/1h cold / warm median (ms, tiny data, reference only)",
          r.metric("cold_ms") + " / " + r.metric("warm_ms"));
    } finally {
      ArgosClickHouse.removeVolume(volume);
    }
  }

  private static long uptime(Server server) {
    return server.client(Account.READER).scalarLong("SELECT uptime()");
  }
}
