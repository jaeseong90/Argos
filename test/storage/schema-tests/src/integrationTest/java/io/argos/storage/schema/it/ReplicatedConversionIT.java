/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ArgosClickHouse;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.schema.SchemaApplier;
import io.argos.storage.schema.SchemaParameters;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;

/**
 * 13 §17 item 7 / 13 §11 / A-9: the {@code convert_to_replicated} flag converts a MergeTree table
 * to ReplicatedMergeTree on the next server start when Keeper is configured (here the embedded
 * Keeper of a single test node; this keeper.xml is test-only). Views keep feeding the rollups.
 * config.d/argos.xml removes the interserver HTTP port; replication needs it, so the test-only
 * keeper.xml turns it back on (merged after argos.xml), as an A-9 installation would together with
 * interserver_http_credentials.
 */
@Tag("integration")
class ReplicatedConversionIT {

  private static final String KEEPER =
      """
      <clickhouse>
        <interserver_http_port>9009</interserver_http_port>
        <keeper_server>
          <tcp_port>9181</tcp_port>
          <server_id>1</server_id>
          <log_storage_path>/var/lib/clickhouse/coordination/log</log_storage_path>
          <snapshot_storage_path>/var/lib/clickhouse/coordination/snapshots</snapshot_storage_path>
          <raft_configuration>
            <server><id>1</id><hostname>localhost</hostname><port>9234</port></server>
          </raft_configuration>
        </keeper_server>
        <zookeeper>
          <node><host>localhost</host><port>9181</port></node>
        </zookeeper>
        <macros>
          <shard>01</shard>
          <replica>r1</replica>
        </macros>
      </clickhouse>
      """;

  @Test
  @DisplayName("13§17 #7 convert_to_replicated in the table directory converts xlog on restart")
  void convertsOnRestart() {
    String volume = "argos-wo03-replicated-" + UUID.randomUUID();
    SchemaParameters p = SchemaParameters.defaults().withTablePrefix("rep_");
    Workload w = Workload.generate(21, Instant.parse("2026-10-24T10:00:00Z"), 10, 100);
    try {
      String dataPath;
      try (ArgosClickHouse first = start(volume)) {
        ClickHouseHttp migrator = first.client(Account.MIGRATOR);
        SchemaApplier.apply(migrator, p);
        RollupViewsIT.insertBlocks(
            first.client(Account.WRITER), p, "xlog", Workload.XLOG_COLUMNS, w.xlog, 1000, "rep");
        dataPath =
            migrator.scalar(
                "SELECT data_paths[1] FROM system.tables WHERE database = 'argos' AND name = 'rep_xlog'");
        ExecResult touch = first.execAsClickHouse("touch", dataPath + "convert_to_replicated");
        assertThat(touch.getExitCode()).isZero();
      }

      try (ArgosClickHouse second = start(volume)) {
        ClickHouseHttp migrator = second.client(Account.MIGRATOR);
        List<String> engine =
            migrator
                .rows(
                    "SELECT engine, engine_full FROM system.tables WHERE database = 'argos'"
                        + " AND name = 'rep_xlog'")
                .get(0);
        Evidence.record("confirmations", "#7 engine after convert_to_replicated + restart", engine);
        assertThat(engine.get(0)).isEqualTo("ReplicatedMergeTree");
        assertThat(engine.get(1))
            .startsWith("ReplicatedMergeTree('/clickhouse/tables/{uuid}/{shard}', '{replica}')");
        assertThat(migrator.scalarLong("SELECT count() FROM argos.rep_xlog")).isEqualTo(1000);

        Workload more = Workload.generate(22, Instant.parse("2026-10-24T11:00:00Z"), 1, 100);
        RollupViewsIT.insertBlocks(
            second.client(Account.WRITER),
            p,
            "xlog",
            Workload.XLOG_COLUMNS,
            more.xlog,
            1000,
            "rep2");
        assertThat(migrator.scalarLong("SELECT count() FROM argos.rep_xlog")).isEqualTo(1100);
        assertThat(migrator.scalarLong("SELECT sum(count) FROM argos.rep_app_stat_1m"))
            .isEqualTo(1100);
        assertThat(
                second
                    .execAsClickHouse("test", "-e", dataPath + "convert_to_replicated")
                    .getExitCode())
            .isNotZero();
      }
    } finally {
      ArgosClickHouse.removeVolume(volume);
    }
  }

  private static ArgosClickHouse start(String volume) {
    return ArgosClickHouse.builder()
        .dataVolume(volume)
        .extraConfig("config.d/keeper.xml", KEEPER)
        .start();
  }
}
