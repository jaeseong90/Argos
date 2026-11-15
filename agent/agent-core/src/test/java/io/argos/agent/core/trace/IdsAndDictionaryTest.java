/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.hash.Xxh3;
import io.argos.agent.core.sql.JdbcUrlParser;
import io.argos.agent.core.sql.SqlIds;
import io.argos.agent.core.sql.SqlNormalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** tx_id (10 §9.5, C-91), trace ids, xxh3 dictionary ids (C-91, T-ACC-08) and their caps. */
class IdsAndDictionaryTest {

  /** A wall clock that only moves when the test moves it. */
  static final class ManualClock implements TxIdGenerator.MillisClock {
    long millis = TxIdGenerator.EPOCH_MS + 1_000_000;

    @Override
    public long currentTimeMillis() {
      return millis;
    }
  }

  @Test
  @Tag("req:D-11")
  @DisplayName(
      "T-ACC-11 (unit) tx_id: unique and increasing across 4 threads; time, node and sequence"
          + " decode from the id within 1 ms of creation")
  void txIdsUniqueAndIncreasing() throws InterruptedException {
    final TxIdGenerator generator = new TxIdGenerator(0xABC);
    final List<long[]> perThread = new ArrayList<long[]>();
    final List<long[]> windows = new ArrayList<long[]>();
    List<Thread> threads = new ArrayList<Thread>();
    for (int t = 0; t < 4; t++) {
      final long[] ids = new long[5_000];
      final long[] window = new long[3];
      perThread.add(ids);
      windows.add(window);
      Thread thread =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  for (int i = 0; i < ids.length; i++) {
                    long before = System.currentTimeMillis();
                    ids[i] = generator.next();
                    if (i == ids.length / 2) {
                      window[0] = before;
                      window[1] = ids[i];
                      window[2] = System.currentTimeMillis();
                    }
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
    int notIncreasing = 0;
    int otherNode = 0;
    for (long[] ids : perThread) {
      for (int i = 0; i < ids.length; i++) {
        if (i > 0 && ids[i] <= ids[i - 1]) {
          notIncreasing++;
        }
        if (TxIdGenerator.nodeOf(ids[i]) != 0xABC) {
          otherNode++;
        }
        all.add(ids[i]);
      }
    }
    assertThat(notIncreasing).isZero();
    assertThat(otherNode).isZero();
    assertThat(all).hasSize(4 * 5_000);
    for (long[] window : windows) {
      assertThat(TxIdGenerator.epochMillisOf(window[1])).isBetween(window[0] - 1, window[2] + 1);
    }
  }

  @Test
  @Tag("req:D-11")
  @DisplayName(
      "10 §9.5: 2,048 ids in one millisecond, then the generator waits for the next ms (park),"
          + " never borrowing it; the time prefix equals the clock")
  void waitsForTheNextMillisecond() {
    final ManualClock clock = new ManualClock();
    final AtomicInteger waits = new AtomicInteger();
    TxIdGenerator generator =
        new TxIdGenerator(
            3,
            clock,
            new TxIdGenerator.Waiter() {
              @Override
              public void awaitNextMillisecond() {
                // The real waiter parks 1 µs; here every 5th wait the clock ticks.
                if (waits.incrementAndGet() % 5 == 0) {
                  clock.millis++;
                }
              }
            });
    long start = clock.millis;
    long last = 0;
    for (int i = 0; i < 2048 * 3; i++) {
      long id = generator.next();
      assertThat(id).isGreaterThan(last);
      assertThat(TxIdGenerator.epochMillisOf(id)).isEqualTo(clock.millis);
      last = id;
    }
    assertThat(clock.millis).isEqualTo(start + 2);
    assertThat(waits.get()).isEqualTo(10);
    assertThat(generator.waits()).isEqualTo(10);
    assertThat(TxIdGenerator.sequenceOf(last)).isEqualTo(2047);
  }

  @Test
  @DisplayName(
      "clock going backwards keeps the last ms; used up while behind, the next ms is taken without"
          + " waiting")
  void clockBackwards() {
    final ManualClock clock = new ManualClock();
    final AtomicInteger waits = new AtomicInteger();
    TxIdGenerator generator =
        new TxIdGenerator(
            1,
            clock,
            new TxIdGenerator.Waiter() {
              @Override
              public void awaitNextMillisecond() {
                waits.incrementAndGet();
                clock.millis++;
              }
            });
    long first = generator.next();
    long lastMillis = clock.millis;
    clock.millis -= 60_000;
    long last = first;
    for (int i = 0; i < 2048 * 2; i++) {
      long id = generator.next();
      assertThat(id).isGreaterThan(last);
      last = id;
    }
    assertThat(waits.get()).as("never waits out a backward jump").isZero();
    assertThat(TxIdGenerator.epochMillisOf(first)).isEqualTo(lastMillis);
    assertThat(TxIdGenerator.epochMillisOf(last)).isEqualTo(lastMillis + 2);
  }

  @Test
  @DisplayName(
      "C-91 node id: provisional, then SecureRandom after premain, then instance_id mod 4096 wins")
  void nodeIds() {
    TxIdGenerator generator = new TxIdGenerator();
    assertThat(generator.nodeSource()).isEqualTo(TxIdGenerator.NODE_PROVISIONAL);
    assertThat(generator.nodeId()).isBetween(0L, 4095L);
    assertThat(generator.reseedFromSecureRandom()).isTrue();
    assertThat(generator.nodeSource()).isEqualTo(TxIdGenerator.NODE_SECURE_RANDOM);
    assertThat(generator.reseedFromSecureRandom()).isFalse();
    generator.setNodeId(4096L + 17);
    assertThat(generator.nodeId()).isEqualTo(17);
    assertThat(generator.nodeSource()).isEqualTo(TxIdGenerator.NODE_SERVER);
    assertThat(TxIdGenerator.nodeOf(generator.next())).isEqualTo(17);
    TxIdGenerator assigned = new TxIdGenerator();
    assigned.setNodeId(5);
    assertThat(assigned.reseedFromSecureRandom()).as("server node wins").isFalse();
    assertThat(assigned.nodeId()).isEqualTo(5);
    Set<Long> nodes = new HashSet<Long>();
    for (int i = 0; i < 64; i++) {
      nodes.add(TxIdGenerator.provisionalNode());
    }
    assertThat(nodes.size()).as("provisional nodes vary").isGreaterThan(32);
  }

  @Test
  @Tag("req:N-30")
  @DisplayName(
      "C-91 provisional node: the process id comes from /proc/self where it exists, 0 elsewhere")
  void provisionalNodeProcessIdIsFree() {
    long pid = TxIdGenerator.processId();
    if (new java.io.File("/proc/self").exists()) {
      String jvmName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
      assertThat(pid).isEqualTo(Long.parseLong(jvmName.substring(0, jvmName.indexOf('@'))));
    } else {
      assertThat(pid).isZero();
    }
  }

  @Test
  @DisplayName("trace ids: upper 32 bits are Unix seconds, ids are hex encoded, span ids non-zero")
  void traceIds() {
    long epoch = 1_800_000_000_000L;
    long high = TraceIds.newTraceIdHigh(epoch);
    assertThat(high >>> 32).isEqualTo(epoch / 1000);
    assertThat(TraceIds.hex16(0x1fL)).isEqualTo("000000000000001f");
    assertThat(TraceIds.hex32(-1L, 1L)).isEqualTo("ffffffffffffffff0000000000000001");
    Set<Long> spans = new HashSet<Long>();
    for (int i = 0; i < 1000; i++) {
      long span = TraceIds.newSpanId();
      assertThat(span).isNotZero();
      spans.add(span);
      assertThat(TraceIds.newTraceIdLow()).isNotZero();
    }
    assertThat(spans).hasSize(1000);
  }

  @Test
  @Tag("req:D-08")
  @DisplayName(
      "T-ACC-08 (agent part) dictionary ids are xxh3_64 of the UTF-8 text: the same in every"
          + " instance and run (pinned against python-xxhash 3.5.0), placeholders hashed alike")
  void idsAreStableXxh3() {
    String[] texts = {
      "select * from orders where id = ?",
      "GET /api/orders/{id}",
      Dictionary.UNPARSED,
      Dictionary.OVERFLOW,
      Dictionary.OTHER,
      "tomcat-handler-",
      "jdbc:h2:mem:shop",
      "select * from 주문 where 이름 = ?"
    };
    long[] expected = {
      3270221528232971219L,
      7251816341193748035L,
      -8381133540167091367L,
      8735778657162749041L,
      -5780773081292588755L,
      4186129339897060167L,
      8463808590689801705L,
      -6446792998634793579L
    };
    for (int i = 0; i < texts.length; i++) {
      assertThat(Dictionary.hash64(texts[i])).as(texts[i]).isEqualTo(expected[i]);
      assertThat(new Dictionary().id(DictKind.SERVICE, texts[i])).isEqualTo(expected[i]);
      assertThat(Xxh3.hash64(texts[i])).isEqualTo(expected[i]);
    }
    // Two "instances" (fresh dictionaries and SQL caches) give the same sql_id for one statement.
    SqlIds first = new SqlIds(new Dictionary(), SqlNormalizer.defaults());
    SqlIds second = new SqlIds(new Dictionary(), SqlNormalizer.defaults());
    assertThat(first.id("select * from orders where id = 42"))
        .isEqualTo(second.id("select  *  from orders where id = 7"))
        .isEqualTo(expected[0]);
    assertThat(SqlNormalizer.UNPARSED).isEqualTo(Dictionary.UNPARSED);
  }

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "dictionary caps per kind (10 §10.4) and in bytes (8 MB); overflow keeps the id and counts"
          + " dictionary_overflow{kind}")
  void dictionaryCaps() {
    AgentCounters counters = new AgentCounters();
    Dictionary dictionary = new Dictionary(counters);
    long id = dictionary.id(DictKind.SERVICE, "GET /x");
    assertThat(dictionary.id(DictKind.SERVICE, "GET /x")).isEqualTo(id);
    assertThat(dictionary.text(id)).isEqualTo("GET /x");
    assertThat(dictionary.id(DictKind.SERVICE, null)).isZero();
    assertThat(dictionary.text(0)).isNull();
    int capacity = Dictionary.capacity(DictKind.HOST);
    assertThat(capacity).isEqualTo(1_000);
    for (int i = 0; i < capacity + 5; i++) {
      dictionary.id(DictKind.HOST, "host-" + i);
    }
    long overflowId = dictionary.id(DictKind.HOST, "host-overflow");
    assertThat(overflowId).isEqualTo(Dictionary.hash64("host-overflow"));
    assertThat(dictionary.text(overflowId)).isNull();
    assertThat(dictionary.size(DictKind.HOST)).isEqualTo(capacity);
    assertThat(dictionary.overflow(DictKind.HOST)).isEqualTo(6);
    assertThat(counters.dictionaryOverflow(DictKind.HOST)).isEqualTo(6);
    // The placeholders a full cap maps to stay resolvable and are not counted as overflow.
    long other = dictionary.id(DictKind.HOST, Dictionary.OTHER);
    assertThat(other).isEqualTo(Dictionary.hash64(Dictionary.OTHER));
    assertThat(dictionary.text(other)).isEqualTo(Dictionary.OTHER);
    assertThat(dictionary.id(DictKind.HOST, Dictionary.OVERFLOW)).isNotZero();
    assertThat(dictionary.text(Dictionary.hash64(Dictionary.OVERFLOW)))
        .isEqualTo(Dictionary.OVERFLOW);
    assertThat(dictionary.size(DictKind.HOST)).isEqualTo(capacity);
    assertThat(counters.dictionaryOverflow(DictKind.HOST)).isEqualTo(6);
    assertThat(Dictionary.capacity(DictKind.SERVICE)).isEqualTo(10_000);
    assertThat(Dictionary.capacity(DictKind.SQL)).isEqualTo(10_000);
    assertThat(Dictionary.capacity(DictKind.CLASS)).isEqualTo(20_000);
    assertThat(Dictionary.capacity(DictKind.METHOD)).isEqualTo(20_000);
    assertThat(Dictionary.capacity(DictKind.THREAD)).isEqualTo(1_000);
    assertThat(Dictionary.capacity(DictKind.FRAME)).isEqualTo(50_000);

    // Byte budget: 100 KB, texts of ~10 KB.
    Dictionary small = new Dictionary(counters, 100_000);
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 5_000; i++) {
      text.append('t');
    }
    int kept = 0;
    for (int i = 0; i < 20; i++) {
      long textId = small.id(DictKind.SQL, text.toString() + i);
      if (small.text(textId) != null) {
        kept++;
      }
    }
    assertThat(kept).isEqualTo(9);
    assertThat(small.estimatedBytes()).isLessThanOrEqualTo(100_000);
    assertThat(counters.dictionaryOverflow(DictKind.SQL)).isEqualTo(11);

    // One text: at most 65,536 characters kept, the id is the hash of the whole text.
    StringBuilder huge = new StringBuilder();
    for (int i = 0; i < Dictionary.MAX_TEXT_CHARS + 10; i++) {
      huge.append('h');
    }
    long hugeId = dictionary.id(DictKind.SQL, huge.toString());
    assertThat(hugeId).isEqualTo(Dictionary.hash64(huge.toString()));
    assertThat(dictionary.text(hugeId)).hasSize(Dictionary.MAX_TEXT_CHARS);
    assertThat(dictionary.truncated(hugeId)).isTrue();
    assertThat(dictionary.truncated(id)).isFalse();
  }

  @Test
  @Tag("req:SEC-35")
  @DisplayName(
      "JDBC URLs lose credentials and parameters before they become dictionary texts (WO-13:"
          + " the DB_TARGET form <vendor>://<host>:<port>/<database> of JdbcUrlParser)")
  void jdbcUrlsWithoutCredentials() {
    assertThat(JdbcUrlParser.targetText("jdbc:mysql://scott:tiger@db:3306/shop?useSSL=true"))
        .isEqualTo("mysql://db:3306/shop");
    assertThat(JdbcUrlParser.targetText("jdbc:oracle:thin:scott/tiger@db:1521/ORCL"))
        .isEqualTo("oracle://db:1521/ORCL");
    assertThat(JdbcUrlParser.targetText("jdbc:oracle:thin:@//db:1521/svc"))
        .isEqualTo("oracle://db:1521/svc");
    assertThat(JdbcUrlParser.targetText("jdbc:sqlserver://db:1433;user=sa;password=x"))
        .isEqualTo("sqlserver://db:1433/");
    assertThat(JdbcUrlParser.targetText("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"))
        .isEqualTo("h2:mem:test");
    assertThat(JdbcUrlParser.targetText("jdbc:postgresql://db/shop"))
        .isEqualTo("postgresql://db:5432/shop");
  }
}
