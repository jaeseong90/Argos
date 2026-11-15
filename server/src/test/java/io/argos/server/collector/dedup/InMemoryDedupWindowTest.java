/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.collector.api.DedupKey;
import io.argos.server.collector.api.DedupWindow;
import io.argos.server.collector.api.DedupWindow.Claim;
import io.argos.server.testing.collector.ContractVectors;
import io.argos.server.wal.api.WalRecordType;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The deduplication window (C-3, DSN-11-02, DSN-12-03, 12 §3.2): window of 1,000 written seqs per
 * (instance, epoch, kind), last_seq, atomic claim, restore after a restart, bounded memory.
 */
@Tag("req:D-13")
@Tag("req:DSN-12-03")
class InMemoryDedupWindowTest {

  private static final Map<String, WalRecordType> KINDS =
      Map.of(
          "TRANSACTIONS", WalRecordType.TX,
          "PROFILES", WalRecordType.PROFILE,
          "METRICS", WalRecordType.METRIC,
          "STATS", WalRecordType.STAT);

  @Test
  @DisplayName(
      "11 §16 contract vectors 'dedup' replayed on the window as AgentServiceImpl drives it")
  void contractVector() {
    DedupWindow window = new InMemoryDedupWindow();
    JsonNode steps = ContractVectors.section("dedup").get(0).get("steps");
    int replayed = 0;
    for (JsonNode step : steps) {
      String kind = step.get("kind").asString();
      if (!KINDS.containsKey(kind)) {
        // ACTIVE_SNAPSHOT is volatile: not deduplicated (12 §3.2); the service acks it as sent.
        continue;
      }
      long epoch = step.get("agent_epoch").asLong();
      if (step.has("seq_from")) {
        for (long seq = step.get("seq_from").asLong(); seq <= step.get("seq_to").asLong(); seq++) {
          Outcome o = send(window, new DedupKey(7, epoch, KINDS.get(kind), seq), false);
          assertThat(o.status).isEqualTo("ACK_OK");
          assertThat(o.lastSeq).isEqualTo(seq);
        }
        replayed++;
        continue;
      }
      DedupKey key = new DedupKey(7, epoch, KINDS.get(kind), step.get("seq").asLong());
      boolean fault = step.has("fault");
      Outcome o = send(window, key, fault);
      if (fault) {
        assertThat(o.status).isEqualTo(step.get("expected_code").asString());
      } else {
        assertThat(o.status).as(step.toString()).isEqualTo(step.get("expected_status").asString());
        assertThat(o.lastSeq).as(step.toString()).isEqualTo(step.get("expected_last_seq").asLong());
      }
      replayed++;
    }
    assertThat(replayed).isEqualTo(steps.size() - 1);
  }

  /** What AgentServiceImpl answers for {@code key}; {@code failWrite} = the WAL append failed. */
  private static Outcome send(DedupWindow window, DedupKey key, boolean failWrite) {
    Claim claim = window.claim(key);
    if (claim == Claim.DUPLICATE) {
      return new Outcome("ACK_DUPLICATE", window.lastSeq(key));
    }
    assertThat(claim).isEqualTo(Claim.NEW);
    if (failWrite) {
      window.release(key);
      return new Outcome("UNAVAILABLE", window.lastSeq(key));
    }
    window.mark(key);
    return new Outcome("ACK_OK", key.seq());
  }

  private record Outcome(String status, long lastSeq) {}

  @Test
  @DisplayName("C-3 the key includes instance, epoch and kind")
  void keyParts() {
    DedupWindow window = new InMemoryDedupWindow();
    window.restore(new DedupKey(1, 7, WalRecordType.TX, 5));
    assertThat(window.seen(new DedupKey(1, 7, WalRecordType.TX, 5))).isTrue();
    assertThat(window.seen(new DedupKey(2, 7, WalRecordType.TX, 5))).isFalse();
    assertThat(window.seen(new DedupKey(1, 8, WalRecordType.TX, 5))).isFalse();
    assertThat(window.seen(new DedupKey(1, 7, WalRecordType.PROFILE, 5))).isFalse();
    assertThat(window.lastSeq(1, 7, WalRecordType.TX)).isEqualTo(5);
    assertThat(window.lastSeq(1, 7, WalRecordType.METRIC)).isZero();
  }

  @Test
  @DisplayName("12 §3.2 a seq that left the 1,000 most recent written seqs is new again")
  void windowOfThousand() {
    DedupWindow window = new InMemoryDedupWindow();
    for (long seq = 1; seq <= 1_000; seq++) {
      window.restore(new DedupKey(3, 1, WalRecordType.METRIC, seq));
    }
    assertThat(window.claim(new DedupKey(3, 1, WalRecordType.METRIC, 1)))
        .isEqualTo(Claim.DUPLICATE);
    assertThat(window.claim(new DedupKey(3, 1, WalRecordType.METRIC, 1_000)))
        .isEqualTo(Claim.DUPLICATE);
    window.restore(new DedupKey(3, 1, WalRecordType.METRIC, 1_001));
    assertThat(window.seen(new DedupKey(3, 1, WalRecordType.METRIC, 1))).isFalse();
    assertThat(window.seen(new DedupKey(3, 1, WalRecordType.METRIC, 2))).isTrue();
    // out of order writes below the maximum are found as well
    window.restore(new DedupKey(3, 1, WalRecordType.METRIC, 1));
    assertThat(window.seen(new DedupKey(3, 1, WalRecordType.METRIC, 1))).isTrue();
    assertThat(window.seen(new DedupKey(3, 1, WalRecordType.METRIC, 2))).isFalse();
    assertThat(window.lastSeq(3, 1, WalRecordType.METRIC)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "12 §3.2 a key claimed by a call waiting for its fsync is IN_FLIGHT for a concurrent resend")
  void inFlight() {
    DedupWindow window = new InMemoryDedupWindow();
    DedupKey key = new DedupKey(4, 9, WalRecordType.STAT, 1);
    assertThat(window.claim(key)).isEqualTo(Claim.NEW);
    assertThat(window.claim(key)).isEqualTo(Claim.IN_FLIGHT);
    window.release(key);
    assertThat(window.claim(key)).as("released: a retry is new").isEqualTo(Claim.NEW);
    window.mark(key);
    assertThat(window.claim(key)).isEqualTo(Claim.DUPLICATE);
  }

  @Test
  @DisplayName("12 §3.2 concurrent claims of one key: exactly one call writes")
  void concurrentClaims() throws Exception {
    DedupWindow window = new InMemoryDedupWindow();
    DedupKey key = new DedupKey(5, 1, WalRecordType.TX, 42);
    AtomicInteger news = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      Future<?>[] futures = new Future<?>[8];
      for (int i = 0; i < futures.length; i++) {
        futures[i] =
            pool.submit(
                () -> {
                  start.await();
                  if (window.claim(key) == Claim.NEW) {
                    news.incrementAndGet();
                  }
                  return null;
                });
      }
      start.countDown();
      for (Future<?> f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(news).hasValue(1);
  }

  @Test
  @DisplayName("12 §3.4 restore rebuilds the window and last_seq of the WAL records in WAL order")
  void restoreLikeMark() {
    InMemoryDedupWindow live = new InMemoryDedupWindow();
    InMemoryDedupWindow recovered = new InMemoryDedupWindow();
    long[] order = {1, 2, 3, 7, 5, 4};
    for (long seq : order) {
      DedupKey key = new DedupKey(6, 2, WalRecordType.PROFILE, seq);
      assertThat(live.claim(key)).isEqualTo(Claim.NEW);
      live.mark(key);
      recovered.restore(key);
    }
    for (long seq = 0; seq <= 8; seq++) {
      DedupKey key = new DedupKey(6, 2, WalRecordType.PROFILE, seq);
      assertThat(recovered.seen(key)).as("seq %d", seq).isEqualTo(live.seen(key));
    }
    assertThat(recovered.lastSeq(6, 2, WalRecordType.PROFILE)).isEqualTo(4);
  }

  @Test
  @DisplayName("12 §11 memory is bounded: keys beyond the maximum are evicted")
  void boundedKeys() {
    InMemoryDedupWindow window = new InMemoryDedupWindow(100, Duration.ofHours(1));
    for (int instance = 0; instance < 1_000; instance++) {
      window.restore(new DedupKey(instance, 1, WalRecordType.TX, 1));
    }
    assertThat(window.size()).isLessThanOrEqualTo(100);
  }

  @Test
  @DisplayName(
      "12 §11 the key bound covers every admitted session with two epochs of four kinds"
          + " (MAX_KEYS >= MAX_SESSIONS x 4 x 2)")
  void boundCoversSessions() {
    assertThat(InMemoryDedupWindow.MAX_KEYS)
        .isGreaterThanOrEqualTo(
            (long) io.argos.server.session.HelloService.MAX_SESSIONS
                * InMemoryDedupWindow.KINDS
                * InMemoryDedupWindow.EPOCHS_PER_INSTANCE);
    assertThat(InMemoryDedupWindow.KINDS)
        .isEqualTo(
            (int)
                java.util.Arrays.stream(WalRecordType.values())
                    .filter(WalRecordType::isData)
                    .count());
  }

  @Test
  @DisplayName(
      "D-13 an instance that changes its epoch thousands of times never evicts another instance's"
          + " written seqs (at most two epochs per instance)")
  void epochChurnKeepsOtherInstances() {
    // Room for two instances of 8 keys each: with keys per (instance, epoch, kind) alone the
    // churning instance would push instance B out after a few epochs.
    InMemoryDedupWindow window = new InMemoryDedupWindow(16, Duration.ofHours(1));
    for (long seq = 1; seq <= 10; seq++) {
      DedupKey b = new DedupKey(2, 99, WalRecordType.TX, seq);
      assertThat(window.claim(b)).isEqualTo(Claim.NEW);
      window.mark(b);
    }
    for (long epoch = 1; epoch <= 5_000; epoch++) {
      for (WalRecordType type : KINDS.values()) {
        DedupKey a = new DedupKey(1, epoch, type, 1);
        assertThat(window.claim(a)).isEqualTo(Claim.NEW);
        window.mark(a);
      }
      if (epoch % 500 == 0) {
        assertThat(window.claim(new DedupKey(2, 99, WalRecordType.TX, epoch % 10 + 1)))
            .as("instance B after %d epochs of A", epoch)
            .isEqualTo(Claim.DUPLICATE);
      }
    }
    for (long seq = 1; seq <= 10; seq++) {
      assertThat(window.claim(new DedupKey(2, 99, WalRecordType.TX, seq)))
          .as("seq %d of instance B", seq)
          .isEqualTo(Claim.DUPLICATE);
    }
    assertThat(window.lastSeq(2, 99, WalRecordType.TX)).isEqualTo(10);
    assertThat(window.size())
        .as("instance A: 2 epochs x 4 kinds; instance B: 1 key")
        .isEqualTo(InMemoryDedupWindow.KEYS_PER_INSTANCE + 1);
  }

  @Test
  @DisplayName(
      "DSN-12-03 a third epoch of an instance drops only the instance's oldest epoch; the previous"
          + " epoch stays")
  void previousEpochStays() {
    DedupWindow window = new InMemoryDedupWindow();
    for (long epoch = 1; epoch <= 2; epoch++) {
      for (long seq = 1; seq <= 3; seq++) {
        window.mark(claimed(window, new DedupKey(5, epoch, WalRecordType.TX, seq)));
        window.mark(claimed(window, new DedupKey(5, epoch, WalRecordType.PROFILE, seq)));
      }
    }
    window.restore(new DedupKey(6, 1, WalRecordType.TX, 1));
    window.mark(claimed(window, new DedupKey(5, 3, WalRecordType.TX, 1)));

    for (WalRecordType type : new WalRecordType[] {WalRecordType.TX, WalRecordType.PROFILE}) {
      assertThat(window.seen(new DedupKey(5, 2, type, 3))).as("previous epoch, %s", type).isTrue();
      assertThat(window.lastSeq(5, 2, type)).isEqualTo(3);
      assertThat(window.seen(new DedupKey(5, 1, type, 1))).as("oldest epoch, %s", type).isFalse();
      assertThat(window.lastSeq(5, 1, type)).isZero();
    }
    assertThat(window.seen(new DedupKey(5, 3, WalRecordType.TX, 1))).isTrue();
    assertThat(window.seen(new DedupKey(6, 1, WalRecordType.TX, 1)))
        .as("another instance with the same epoch number")
        .isTrue();
    assertThat(window.claim(new DedupKey(5, 1, WalRecordType.TX, 1)))
        .as("a seq of the dropped epoch is new again")
        .isEqualTo(Claim.NEW);
  }

  private static DedupKey claimed(DedupWindow window, DedupKey key) {
    assertThat(window.claim(key)).isEqualTo(Claim.NEW);
    return key;
  }

  @Test
  @DisplayName("unsigned u64 seqs and epochs (11 §6) are compared as unsigned values")
  void unsignedSeqs() {
    DedupWindow window = new InMemoryDedupWindow();
    long big = -2L; // 2^64 - 2 as an unsigned value
    window.restore(new DedupKey(8, -1L, WalRecordType.TX, 1));
    window.restore(new DedupKey(8, -1L, WalRecordType.TX, big));
    assertThat(window.seen(new DedupKey(8, -1L, WalRecordType.TX, big))).isTrue();
    assertThat(window.seen(new DedupKey(8, -1L, WalRecordType.TX, 1))).isTrue();
    assertThat(window.seen(new DedupKey(8, -1L, WalRecordType.TX, -1L))).isFalse();
  }
}
