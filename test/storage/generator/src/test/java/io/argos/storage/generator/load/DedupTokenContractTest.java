/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.storage.generator.Generator;
import io.argos.storage.generator.GeneratorConfig;
import io.argos.storage.generator.RunRange;
import io.argos.storage.generator.model.StatRow;
import io.argos.storage.generator.model.Topology;
import io.argos.storage.generator.rows.Row;
import io.argos.storage.generator.rows.RowBinaryWriter;
import io.argos.storage.generator.rows.Table;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deduplication token contract (13 §5.1, C-2, D-13) as the generator implements it: token
 * format, uniqueness, re-sends with the same token and bytes, determinism across runs, distinct
 * tokens across runs and WALs, the writer's INTENT / COMMIT / recovery sequence and the flush rule.
 */
class DedupTokenContractTest {

  private static final Instant START = Instant.parse("2026-03-01T23:58:00Z");

  private static GeneratorConfig small() {
    return GeneratorConfig.defaults()
        .with("tps", "50")
        .with("start", START.toString())
        .with("scale", "0.0000925925926") // 4 minutes of the 30-day shape
        .with("metric-series", "10")
        .with("block-rows", "2000")
        .with("duplicate-rate", "0.2");
  }

  private static CountingInserter bulk(GeneratorConfig config) throws Exception {
    CountingInserter inserter = new CountingInserter(true);
    PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    new Generator(config, inserter, null, quiet).run(START.toEpochMilli(), null);
    return inserter;
  }

  @Test
  @DisplayName("Bulk tokens: argos:<table>:<block_id>, unique, re-sends repeat the previous token")
  void bulkTokens() throws Exception {
    CountingInserter sent = bulk(small());
    int resends = 0;
    for (Table table : Table.ALL) {
      List<String> tokens = sent.tokens(table);
      assertThat(tokens).isNotEmpty();
      Set<String> distinct = new LinkedHashSet<>();
      String previous = null;
      for (String token : tokens) {
        assertThat(token).matches(DedupToken.FORMAT).startsWith("argos:" + table.name() + ":");
        if (!distinct.add(token)) {
          assertThat(token).as("a re-send directly follows its block").isEqualTo(previous);
          resends++;
        }
        previous = token;
      }
      // The first block of every table is re-sent (force-duplicate).
      assertThat(tokens.get(1)).isEqualTo(tokens.get(0));
    }
    assertThat(resends).isGreaterThan(Table.ALL.size());
  }

  @Test
  @DisplayName("Same seed and start: identical blocks and tokens; other seed: no shared token")
  void determinism() throws Exception {
    CountingInserter a = bulk(small());
    CountingInserter b = bulk(small());
    CountingInserter c = bulk(small().with("seed", "7"));
    for (Table table : Table.ALL) {
      assertThat(b.tokens(table)).isEqualTo(a.tokens(table));
      Set<String> other = new HashSet<>(c.tokens(table));
      other.retainAll(a.tokens(table));
      assertThat(other).as("tokens shared with another seed").isEmpty();
    }
    Map<String, String> da = a.digests();
    assertThat(b.digests()).isEqualTo(da);
    assertThat(c.digests().get("xlog")).isNotEqualTo(da.get("xlog"));
  }

  @Test
  @DisplayName("WAL block ids: <walId>-<segment>-<offset>, increasing, unique per WAL lifetime")
  void walBlockIds() {
    WalSimulator wal = new WalSimulator("w1");
    long first = wal.append(100);
    long last = wal.append(100);
    WalSimulator.Intent i1 = wal.intent(Table.XLOG, first, last, 2);
    WalSimulator.Intent i2 = wal.intent(Table.XLOG, last, last, 1);
    assertThat(i1.blockId()).matches("w1-00000001-[0-9a-f]{8}");
    assertThat(i1.token()).isEqualTo("argos:xlog:" + i1.blockId()).matches(DedupToken.FORMAT);
    assertThat(i2.blockId()).isGreaterThan(i1.blockId());
    assertThat(wal.uncommitted()).containsExactly(i1, i2);
    wal.commit(i1);
    assertThat(wal.uncommitted()).containsExactly(i2);
    assertThatThrownBy(() -> wal.commit(i1)).isInstanceOf(IllegalStateException.class);
    WalSimulator other = new WalSimulator("w2");
    WalSimulator.Intent j1 = other.intent(Table.XLOG, other.append(100), other.append(100), 2);
    assertThat(j1.token()).isNotEqualTo(i1.token());
    // Segment rotation at 256 MiB.
    WalSimulator big = new WalSimulator("w3");
    for (int i = 0; i < 300; i++) {
      big.append(1 << 20);
    }
    assertThat(big.intent(Table.METRIC, 0, 0, 0).blockId()).startsWith("w3-00000002-");
  }

  @Test
  @DisplayName("Retries keep the token; a failure after the retries stops the writer")
  void retriesKeepToken() throws Exception {
    List<String> attempts = new ArrayList<>();
    AtomicInteger failuresLeft = new AtomicInteger(2);
    Inserter flaky =
        new Inserter() {
          @Override
          public long insert(Block block) {
            attempts.add(block.token());
            if (failuresLeft.getAndDecrement() > 0) {
              throw new IllegalStateException("simulated failure");
            }
            return block.rows();
          }

          @Override
          public String describe() {
            return "flaky";
          }
        };
    LoaderStats stats = new LoaderStats();
    BlockSender sender = new BlockSender(flaky, stats, 3, 1, 1, 0, false, () -> 0L);
    Block block = new Block(Table.XLOG, 5, "x1", 1, new byte[] {1}, new long[0], 0);
    sender.send(block);
    assertThat(attempts).containsExactly(block.token(), block.token(), block.token());
    failuresLeft.set(100);
    assertThatThrownBy(() -> sender.send(block))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("after 4 attempts");
  }

  @Test
  @DisplayName(
      "Replay writer: 2 s / N rows flush, INTENT before insert, COMMIT after, restarts recover")
  void replayWriter() throws Exception {
    GeneratorConfig config =
        GeneratorConfig.defaults()
            .with("flush-rows", "100")
            .with("flush-interval-ms", "2000")
            .with("duplicate-rate", "0")
            .with("force-duplicate", "false");
    AtomicLong clock = new AtomicLong(1_000_000L);
    CountingInserter inserter = new CountingInserter(true);
    LoaderStats stats = new LoaderStats();
    WalSimulator wal = new WalSimulator("wtest");
    Topology topology =
        Topology.build(
            config, RunRange.of(config, START.toEpochMilli(), START.toEpochMilli() + 60_000));
    Topology.Instance instance = topology.instances.get(0);
    long[] crashAt = {clock.get() + 10, clock.get() + 20};
    ReplayLoader loader = new ReplayLoader(config, wal, inserter, stats, clock::get, crashAt);
    // 100 + 100 rows: two blocks by row count; 50 rows: one block by the flush interval.
    for (int batch = 0; batch < 2; batch++) {
      for (int i = 0; i < 100; i++) {
        int n = batch * 100 + i;
        loader.accept(Table.XLOG, statRow(instance, n), clock.get() + n);
      }
      awaitBlocks(stats, batch + 1);
    }
    for (int n = 200; n < 250; n++) {
      loader.accept(Table.XLOG, statRow(instance, n), clock.get() + n);
    }
    Thread.sleep(100);
    assertThat(stats.blocks(Table.XLOG)).isEqualTo(2);
    assertThat(loader.queuedRows()).isEqualTo(50);
    clock.addAndGet(2_000);
    awaitBlocks(stats, 3);
    loader.close();
    assertThat(wal.uncommitted()).isEmpty();
    assertThat(wal.commits()).isEqualTo(3);
    List<String> tokens = inserter.tokens(Table.XLOG);
    // Block 1 (crash before the insert): inserted once, by the recovery.
    // Block 2 (crash after the insert, before COMMIT): the recovery re-sends the same token.
    assertThat(loader.crashes()).containsExactly(1, 1);
    assertThat(tokens).hasSize(4);
    assertThat(tokens.get(3)).isEqualTo(tokens.get(2));
    assertThat(new HashSet<>(tokens)).hasSize(3);
    assertThat(stats.recoverySends(Table.XLOG)).isEqualTo(1);
    assertThat(inserter.rows(Table.XLOG)).isEqualTo(300); // 250 + the re-sent 50
    assertThat(stats.rows(Table.XLOG)).isEqualTo(250);
  }

  private static void awaitBlocks(LoaderStats stats, int blocks) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5_000;
    while (stats.blocks(Table.XLOG) < blocks && System.currentTimeMillis() < deadline) {
      Thread.sleep(5);
    }
    assertThat(stats.blocks(Table.XLOG)).isEqualTo(blocks);
  }

  private static Row statRow(Topology.Instance instance, int i) {
    StatRow row = StatRow.ext(instance, 1, i);
    row.tsMs = 1_000L * i;
    return new Row() {
      @Override
      public void write(RowBinaryWriter out) {
        row.write(out);
      }

      @Override
      public long tsMs() {
        return row.tsMs;
      }
    };
  }
}
