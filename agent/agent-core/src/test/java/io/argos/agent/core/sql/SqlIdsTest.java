/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.SqlState;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.trace.TxIdGenerator;
import java.lang.ref.WeakReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SqlIds (10 §9.4 cache, D-22) and the tracer's SQL entry points ({@code dict(SQL, ..)}, {@code
 * sqlId}, {@code dbId}, {@code sqlParam}): normalized text only in the dictionary, the dialect of
 * the target, the bounded approximate LRU, the {@code <unparsed>} fail-safe (SEC-35).
 */
@Tag("req:SEC-35")
@Tag("req:D-22")
class SqlIdsTest {
  private static TracerImpl tracer(Dictionary dictionary, AgentCounters counters, SqlNormalizer n) {
    return TracerImpl.builder()
        .dictionary(dictionary)
        .counters(counters)
        .txIds(new TxIdGenerator())
        .sink(
            new TracerImpl.Sink() {
              @Override
              public void accept(Transaction tx) {}
            })
        .sqlNormalizer(n)
        .build();
  }

  @Test
  @DisplayName(
      "the cache is bounded (10,000 entries in two generations, long statements not cached) and"
          + " cached ids equal computed ones")
  void cacheIsBounded() {
    SqlIds ids = new SqlIds(new Dictionary(), SqlNormalizer.defaults());
    for (int i = 0; i < SqlIds.MAX_ENTRIES + 500; i++) {
      ids.id("select " + i + " from t where c" + i + " = 1");
    }
    assertThat(ids.cachedEntries()).isLessThanOrEqualTo(SqlIds.MAX_ENTRIES);
    assertThat(ids.clears()).isGreaterThanOrEqualTo(1);
    StringBuilder longSql = new StringBuilder("select ");
    for (int i = 0; i < 2000; i++) {
      longSql.append("c").append(i).append(", ");
    }
    longSql.append("1 from t");
    int before = ids.cachedEntries();
    long id = ids.id(longSql.toString());
    assertThat(id).isNotZero();
    assertThat(ids.cachedEntries()).isEqualTo(before);
    assertThat(ids.id(null)).isZero();

    SqlIds fresh = new SqlIds(new Dictionary(), SqlNormalizer.defaults());
    long first = fresh.id("select 1");
    assertThat(fresh.id("select 1")).isEqualTo(first).isEqualTo(fresh.id("select 2"));
  }

  @Test
  @DisplayName(
      "approximate LRU: an entry used in every generation survives rotations, an unused one is"
          + " dropped after two")
  void leastRecentlyUsedIsDropped() {
    SqlIds ids = new SqlIds(new Dictionary(), SqlNormalizer.defaults());
    String hot = "select * from hot where id = 1";
    String cold = "select * from cold where id = 1";
    ids.id(hot);
    ids.id(cold);
    for (int round = 0; round < 3; round++) {
      for (int i = 0; i < SqlIds.GENERATION_ENTRIES; i++) {
        ids.id("select " + round + "_" + i + " from filler");
      }
      ids.id(hot);
    }
    assertThat(ids.clears()).isGreaterThanOrEqualTo(2);
    assertThat(cachedIn(ids, hot)).as("hot entry cached").isTrue();
    assertThat(cachedIn(ids, cold)).as("cold entry dropped").isFalse();
  }

  /**
   * True when {@code raw} is answered from the cache: a cached raw text is not normalized again.
   */
  private static boolean cachedIn(SqlIds ids, String raw) {
    return cachedIn(ids, raw, SqlDialect.UNKNOWN);
  }

  private static boolean cachedIn(SqlIds ids, String raw, int dialect) {
    int before = ids.cachedEntries();
    long rotations = ids.clears();
    ids.id(raw, dialect);
    // A miss inserts a new entry (or rotates); a hit in the young generation changes nothing.
    return ids.cachedEntries() == before && ids.clears() == rotations;
  }

  @Test
  @DisplayName(
      "T-SEC-35d the cache is keyed by length, dialect and two hashes, not by the raw text: a"
          + " statement with literals is unreachable from the cache once normalized, an equal copy"
          + " still hits, and each dialect has its own entry")
  void cacheKeepsNoRawText() throws InterruptedException {
    SqlIds ids = new SqlIds(new Dictionary(), SqlNormalizer.defaults());
    String literal = "select * from customer where rrn = '900101-1234567' and id = 42";
    String raw = new String(literal.toCharArray());
    long id = ids.id(raw, SqlDialect.MYSQL);
    WeakReference<String> held = new WeakReference<String>(raw);
    raw = null;
    for (int i = 0; i < 50 && held.get() != null; i++) {
      System.gc();
      Thread.sleep(20);
    }
    assertThat(held.get()).as("raw text collected: nothing in the cache refers to it").isNull();
    String copy = new String(literal.toCharArray());
    assertThat(cachedIn(ids, copy, SqlDialect.MYSQL)).as("an equal copy hits").isTrue();
    assertThat(ids.id(copy, SqlDialect.MYSQL)).isEqualTo(id);

    // The dialect is part of the key: the same text reads differently per target.
    String quoted = "select \"Name\" from t";
    long mysql = ids.id(quoted, SqlDialect.MYSQL);
    long postgres = ids.id(quoted, SqlDialect.POSTGRESQL);
    assertThat(mysql).isNotEqualTo(postgres);
    assertThat(cachedIn(ids, quoted, SqlDialect.MYSQL)).isTrue();
    assertThat(cachedIn(ids, quoted, SqlDialect.POSTGRESQL)).isTrue();
    assertThat(ids.id(quoted, SqlDialect.MYSQL)).isEqualTo(mysql);
    assertThat(ids.id(quoted, SqlDialect.POSTGRESQL)).isEqualTo(postgres);

    // Keys: equal for equal text, different for a different dialect or any different character
    // (unpaired surrogates included, which UTF-8 would turn into the same '?').
    assertThat(SqlIds.Key.of(literal, 0)).isEqualTo(SqlIds.Key.of(new String(literal), 0));
    assertThat(SqlIds.Key.of(literal, 0).hashCode())
        .isEqualTo(SqlIds.Key.of(new String(literal), 0).hashCode());
    assertThat(SqlIds.Key.of(literal, 0)).isNotEqualTo(SqlIds.Key.of(literal, 1));
    assertThat(SqlIds.Key.of("select 1", 0)).isNotEqualTo(SqlIds.Key.of("select 2", 0));
    assertThat(SqlIds.Key.of("select '\uD800'", 0))
        .isNotEqualTo(SqlIds.Key.of("select '\uDC00'", 0))
        .isNotEqualTo(SqlIds.Key.of("select '?'", 0));
    assertThat(SqlIds.Key.of("ab", 0)).isNotEqualTo(SqlIds.Key.of("ba", 0));
    assertThat(SqlIds.Key.of("", 0)).isEqualTo(SqlIds.Key.of("", 0));
  }

  @Test
  @DisplayName(
      "dict(SQL, raw) and sqlId(raw, dbId) keep normalized text only; the dialect of the target"
          + " decides how double quotes read; a statement over sql.max-length is kept truncated")
  void tracerEntryPoints() {
    Dictionary dictionary = new Dictionary();
    AgentCounters counters = new AgentCounters();
    TracerImpl tracer = tracer(dictionary, counters, new SqlNormalizer(true, 40, false));
    long a = tracer.dict(DictKind.SQL, "select * from t where email = 'kim@example.test'");
    long b = tracer.dict(DictKind.SQL, "select *  from t where email = 'lee@example.test'");
    assertThat(a).isEqualTo(b);
    assertThat(dictionary.text(a)).isEqualTo("select * from t where email = ?");

    long pg = tracer.dbId("jdbc:postgresql://scott:tiger@pg:5432/shop?password=x");
    assertThat(dictionary.text(pg)).isEqualTo("postgresql://pg:5432/shop");
    long my = tracer.dbId("jdbc:mysql://db/shop");
    String sql = "select \"Name\" from t";
    assertThat(dictionary.text(tracer.sqlId(sql, pg))).isEqualTo("select \"Name\" from t");
    assertThat(dictionary.text(tracer.sqlId(sql, my))).isEqualTo("select ? from t");
    assertThat(dictionary.text(tracer.sqlId(sql, 0L))).isEqualTo("select ? from t");
    assertThat(tracer.sqlId(null, pg)).isZero();
    assertThat(tracer.dbId(null)).isZero();

    long cut = tracer.sqlId("select * from orders where a = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'", pg);
    assertThat(dictionary.text(cut)).isEqualTo("select * from orders where a = ?");
    assertThat(dictionary.truncated(cut)).isTrue();
    assertThat(tracer.dbIdentity().dialectOf(pg)).isEqualTo(SqlDialect.POSTGRESQL);
  }

  @Test
  @DisplayName(
      "T-SEC-35 failure injection: a normalizer without budget gives <unparsed>, counts"
          + " sql_unparsed_total, and the dictionary never holds the raw text or its literals")
  void failureInjection() {
    Dictionary dictionary = new Dictionary();
    AgentCounters counters = new AgentCounters();
    TracerImpl tracer =
        tracer(dictionary, counters, new SqlNormalizer(true, SqlNormalizer.MAX_LENGTH, false, 0));
    String raw = "select * from customer where rrn = '900101-1234567' and email = 'kim@x.com'";
    long id = tracer.sqlId(raw, 0L);
    assertThat(id).isEqualTo(Dictionary.hash64(SqlNormalizer.UNPARSED));
    assertThat(dictionary.text(id)).isEqualTo(SqlNormalizer.UNPARSED);
    assertThat(dictionary.text(Dictionary.hash64(raw))).isNull();
    assertThat(counters.sqlUnparsed()).isEqualTo(1);
    assertThat(tracer.dict(DictKind.SQL, raw)).as("cached").isEqualTo(id);
    assertThat(counters.sqlUnparsed()).isEqualTo(1);
  }

  @Test
  @Tag("req:SEC-3A")
  @DisplayName(
      "T-AG-033b sqlParam renders through the capture modes of the tracer and never throws")
  void tracerSqlParam() {
    TracerImpl tracer = tracer(new Dictionary(), new AgentCounters(), SqlNormalizer.defaults());
    SqlState state = new SqlState("select * from t where email = ?", 0L);
    tracer.sqlParam(state, 1, "kim@example.test", "setString");
    assertThat(state.paramCount()).as("masked by default").isZero();
    tracer.setCapture(CaptureConfig.builder().sqlParamMode(CaptureConfig.SQL_PARAMS_FULL).build());
    tracer.sqlParam(state, 1, "kim@example.test", "setString");
    assertThat(state.param(1)).isEqualTo("'<email>'");
    tracer.sqlParam(null, 1, "x", "setString");
    tracer.sqlParam(state, 2, null, null);
    assertThat(state.param(2)).isEqualTo("NULL");
  }
}
