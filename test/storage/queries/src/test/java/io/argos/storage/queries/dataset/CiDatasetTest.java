/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.queries.dataset.CiDataset.Block;
import io.argos.storage.queries.dataset.TxModel.Tx;
import io.argos.storage.schema.SchemaParameters;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The deterministic CI dataset and its RowBinary encoding (no Docker). */
class CiDatasetTest {

  private static final SchemaParameters P = SchemaParameters.defaults().withTablePrefix("t_");
  private static final CiDataset.Config SMALL =
      new CiDataset.Config(
          11, Instant.parse("2026-10-22T20:00:00Z"), Duration.ofHours(6), 2.0, 2, 2000);

  @Test
  @DisplayName("Same seed and range: byte-identical blocks and tokens")
  void deterministic() {
    List<Block> a = CiDataset.generate(SMALL).blocks(P);
    List<Block> b = CiDataset.generate(SMALL).blocks(P);
    assertThat(a).hasSameSizeAs(b);
    for (int i = 0; i < a.size(); i++) {
      assertThat(a.get(i).token()).isEqualTo(b.get(i).token());
      assertThat(Arrays.equals(a.get(i).body(), b.get(i).body())).isTrue();
    }
  }

  @Test
  @DisplayName(
      "Blocks carry argos:<table>:<block_id> tokens; one block per table is re-sent; one crosses midnight")
  void blocks() {
    List<Block> blocks = CiDataset.generate(SMALL).blocks(P);
    Set<String> tokens = new HashSet<>();
    int resent = 0;
    for (Block b : blocks) {
      assertThat(b.token())
          .matches(
              "argos:"
                  + b.table()
                  + ":[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
      assertThat(b.qualifiedTable()).isEqualTo("argos.t_" + b.table());
      if (!tokens.add(b.token())) {
        resent++;
      }
    }
    assertThat(resent).isGreaterThanOrEqualTo(5);
    assertThat(
            blocks.stream().filter(b -> b.table().equals("xlog")).anyMatch(Block::crossesMidnight))
        .isTrue();
  }

  @Test
  @DisplayName(
      "D-12 by construction, tx_id carries the ts, trace id the Unix second, clock skew of two instances")
  void transactions() {
    CiDataset d = CiDataset.generate(SMALL);
    List<Tx> txs = d.transactions();
    assertThat(txs.size()).isBetween(43_000, 50_000);
    Set<Long> ids = new HashSet<>();
    for (Tx tx : txs) {
      assertThat((tx.txId() >>> 23) + TxModel.TX_EPOCH_MS).isEqualTo(tx.tsMs());
      assertThat(java.nio.ByteBuffer.wrap(tx.traceId()).getInt() & 0xFFFFFFFFL)
          .isBetween(SMALL.start().getEpochSecond() - 120, SMALL.end().getEpochSecond() + 120);
      assertThat(tx.sqlIds().length).isLessThanOrEqualTo(tx.sqls().size());
      ids.add(tx.txId());
    }
    assertThat(ids).hasSameSizeAs(txs);
    assertThat(d.model().skewedInstances()).hasSize(2);
    double meanDistinct = txs.stream().mapToInt(t -> t.sqlIds().length).average().orElse(0);
    assertThat(meanDistinct).isBetween(2.0, 4.0);
    double errorRate = txs.stream().filter(Tx::error).count() / (double) txs.size();
    assertThat(errorRate).isBetween(0.005, 0.02);
  }

  @Test
  @DisplayName("Extra blocks stay inside their window")
  void extraBlocks() {
    Instant from = Instant.parse("2026-10-24T10:00:00Z");
    CiDataset.Extra e = CiDataset.extra(P, 5, from, from.plusSeconds(600), 1000);
    assertThat(e.transactions()).hasSize(1000);
    assertThat(e.perMinute().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1000);
    assertThat(e.blocks())
        .containsKeys("xlog", "profile_step", "metric", "sql_stat_raw", "ext_stat_raw");
    for (Block b : e.blocks().values()) {
      assertThat(b.minTsMs()).isGreaterThanOrEqualTo(from.toEpochMilli());
      assertThat(b.maxTsMs()).isLessThan(from.plusSeconds(600).toEpochMilli());
    }
  }

  @Test
  @DisplayName("RowBinary: little-endian integers, LEB128 lengths")
  void rowBinary() {
    RowBinary w = new RowBinary();
    w.u16(0x0102).u32(1).string("é").u64Array(new long[] {1}).u8(255).endRow();
    assertThat(w.toByteArray())
        .containsExactly(0x02, 0x01, 1, 0, 0, 0, 2, 0xC3, 0xA9, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0xFF);
    assertThat(w.rows()).isEqualTo(1);
    RowBinary big = new RowBinary();
    big.string("x".repeat(300));
    assertThat(Arrays.copyOf(big.toByteArray(), 2)).containsExactly(0xAC, 0x02);
  }
}
