/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** tx_id (DSN-02-09), trace ids, dictionary ids and JDBC URL sanitising. */
class IdsAndDictionaryTest {

  @Test
  @Tag("req:D-11")
  @DisplayName("tx_id: unique and increasing across threads; time and node decode from the id")
  void txIdsUniqueAndIncreasing() throws InterruptedException {
    final TxIdGenerator generator = new TxIdGenerator(0xABC);
    final List<List<Long>> perThread = new ArrayList<List<Long>>();
    List<Thread> threads = new ArrayList<Thread>();
    for (int t = 0; t < 4; t++) {
      final List<Long> ids = new ArrayList<Long>();
      perThread.add(ids);
      Thread thread =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  for (int i = 0; i < 20_000; i++) {
                    ids.add(generator.next());
                  }
                }
              });
      threads.add(thread);
      thread.start();
    }
    for (Thread thread : threads) {
      thread.join();
    }
    Set<Long> all = new HashSet<Long>();
    for (List<Long> ids : perThread) {
      for (int i = 1; i < ids.size(); i++) {
        assertThat(ids.get(i)).isGreaterThan(ids.get(i - 1));
      }
      all.addAll(ids);
    }
    assertThat(all).hasSize(80_000);
    long id = generator.next();
    assertThat((id >>> 11) & 0xFFF).isEqualTo(0xABC);
    assertThat(TxIdGenerator.epochMillisOf(id))
        .isCloseTo(System.currentTimeMillis(), org.assertj.core.data.Offset.offset(5_000L));
  }

  @Test
  @DisplayName("tx_id never waits: exhausted sequence or a clock going back borrows the next ms")
  void txIdBorrowsMilliseconds() {
    TxIdGenerator generator = new TxIdGenerator(1);
    long now = TxIdGenerator.EPOCH_MS + 1_000_000;
    long last = 0;
    for (int i = 0; i < 2048 * 3; i++) {
      long id = generator.next(now);
      assertThat(id).isGreaterThan(last);
      last = id;
    }
    assertThat(TxIdGenerator.epochMillisOf(last)).isEqualTo(now + 2);
    long back = generator.next(now - 60_000);
    assertThat(back).isGreaterThan(last);
  }

  @Test
  @DisplayName("trace ids: upper 32 bits are Unix seconds, ids are hex encoded, span ids non-zero")
  void traceIds() {
    long epoch = 1_800_000_000_000L;
    long high = TraceIds.newTraceIdHigh(epoch);
    assertThat(high >>> 32).isEqualTo(epoch / 1000);
    assertThat(TraceIds.hex16(0x1fL)).isEqualTo("000000000000001f");
    assertThat(TraceIds.hex32(-1L, 1L)).isEqualTo("ffffffffffffffff0000000000000001");
    for (int i = 0; i < 1000; i++) {
      assertThat(TraceIds.newSpanId()).isNotZero();
    }
  }

  @Test
  @DisplayName("dictionary hash = FNV-1a 64 over UTF-8 (Korean, emoji, unpaired surrogates)")
  void hashMatchesUtf8() {
    List<String> samples = new ArrayList<String>();
    Collections.addAll(
        samples,
        "",
        "GET /api/orders/{id}",
        "select * from 주문 where 이름 = ?",
        "emoji 😀 end",
        "unpaired \uD83D end",
        "low \uDE00 only",
        "éࠀ￿");
    for (String sample : samples) {
      assertThat(Dictionary.hash64(sample)).as(sample).isEqualTo(reference(sample));
    }
  }

  private static long reference(String text) {
    long hash = 0xcbf29ce484222325L;
    for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
      hash = (hash ^ (b & 0xff)) * 0x100000001b3L;
    }
    return hash == 0 ? 1 : hash;
  }

  @Test
  @DisplayName("dictionary keeps texts per kind up to its capacity; ids stay stable when full")
  void dictionaryCapacity() {
    Dictionary dictionary = new Dictionary();
    long id = dictionary.id(DictKind.SERVICE, "GET /x");
    assertThat(dictionary.id(DictKind.SERVICE, "GET /x")).isEqualTo(id);
    assertThat(dictionary.text(id)).isEqualTo("GET /x");
    assertThat(dictionary.id(DictKind.SERVICE, null)).isZero();
    assertThat(dictionary.text(0)).isNull();
    int capacity = Dictionary.capacity(DictKind.HOST);
    for (int i = 0; i < capacity + 5; i++) {
      dictionary.id(DictKind.HOST, "host-" + i);
    }
    long overflowId = dictionary.id(DictKind.HOST, "host-overflow");
    assertThat(overflowId).isEqualTo(Dictionary.hash64("host-overflow"));
    assertThat(dictionary.text(overflowId)).isNull();
    assertThat(dictionary.overflow(DictKind.HOST)).isEqualTo(6);
  }

  @Test
  @Tag("req:SEC-35")
  @DisplayName("JDBC URLs lose credentials and parameters before they become dictionary texts")
  void jdbcUrlsWithoutCredentials() {
    assertThat(JdbcUrls.withoutCredentials("jdbc:mysql://scott:tiger@db:3306/shop?useSSL=true"))
        .isEqualTo("jdbc:mysql://db:3306/shop");
    assertThat(JdbcUrls.withoutCredentials("jdbc:oracle:thin:scott/tiger@db:1521/ORCL"))
        .isEqualTo("jdbc:oracle:thin:@db:1521/ORCL");
    assertThat(JdbcUrls.withoutCredentials("jdbc:oracle:thin:@//db:1521/svc"))
        .isEqualTo("jdbc:oracle:thin:@//db:1521/svc");
    assertThat(JdbcUrls.withoutCredentials("jdbc:sqlserver://db:1433;user=sa;password=x"))
        .isEqualTo("jdbc:sqlserver://db:1433");
    assertThat(JdbcUrls.withoutCredentials("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"))
        .isEqualTo("jdbc:h2:mem:test");
    assertThat(JdbcUrls.withoutCredentials("jdbc:postgresql://db/shop"))
        .isEqualTo("jdbc:postgresql://db/shop");
  }
}
