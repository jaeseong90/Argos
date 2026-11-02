/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.HttpSettings;
import io.argos.agent.core.sql.SqlNormalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** dict(SQL, raw) normalizes before hashing; the raw-text cache stays bounded. */
@Tag("req:SEC-35")
class SqlIdsTest {
  @Test
  void sqlDictionaryHoldsNormalizedTextOnly() {
    Dictionary dictionary = new Dictionary();
    TracerImpl tracer =
        new TracerImpl(
            dictionary,
            new TracerImpl.Sink() {
              @Override
              public void accept(Transaction tx) {}
            },
            null,
            new TxIdGenerator(),
            100,
            100);
    long a = tracer.dict(DictKind.SQL, "select * from t where email = 'kim@example.test'");
    long b = tracer.dict(DictKind.SQL, "select *  from t where email = 'lee@example.test'");
    assertThat(a).isEqualTo(b);
    assertThat(dictionary.text(a)).isEqualTo("select * from t where email = ?");
    // Other kinds are kept as given.
    long host = tracer.dict(DictKind.HOST, "db.example.test");
    assertThat(dictionary.text(host)).isEqualTo("db.example.test");

    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    int seq = tracer.stepStart(ctx, 1, a, 0L, System.nanoTime());
    assertThat(seq).isZero();
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
  }

  @Test
  void cacheIsBounded() {
    SqlIds ids = new SqlIds(new Dictionary(), SqlNormalizer.defaults());
    for (int i = 0; i < SqlIds.MAX_ENTRIES + 500; i++) {
      ids.id("select " + i + " from t where c" + i + " = 1");
    }
    assertThat(ids.cachedEntries()).isLessThanOrEqualTo(SqlIds.MAX_ENTRIES);
    assertThat(ids.clears()).isGreaterThanOrEqualTo(1);
    // Long statements are normalized but not cached.
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
  }

  @Test
  void cachedAndUncachedIdsAgree() {
    SqlIds ids = new SqlIds(new Dictionary(), SqlNormalizer.defaults());
    long first = ids.id("select 1");
    long second = ids.id("select 1");
    long other = ids.id("select 2");
    assertThat(first).isEqualTo(second).isEqualTo(other);
  }

  @Test
  void asyncStepSetting() {
    Map<String, String> values = new HashMap<String, String>();
    List<String> warnings = new ArrayList<String>();
    assertThat(HttpSettings.capture(AgentConfig.of(values), warnings).asyncStep()).isFalse();
    values.put("async.step", "true");
    assertThat(HttpSettings.capture(AgentConfig.of(values), warnings).asyncStep()).isTrue();
    assertThat(HttpSettings.capture(AgentConfig.of(values), warnings).remoteIpMode())
        .isEqualTo(io.argos.agent.bootstrap.CaptureConfig.DEFAULT.remoteIpMode());
    assertThat(warnings).isEqualTo(Collections.<String>emptyList());
  }
}
