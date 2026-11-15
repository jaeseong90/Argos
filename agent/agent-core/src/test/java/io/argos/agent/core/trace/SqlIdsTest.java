/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.HttpSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * dict(SQL, raw) normalizes before hashing. The raw-text cache moved to {@code core.sql.SqlIds}
 * with WO-13; its bounds are tested in {@code io.argos.agent.core.sql.SqlIdsTest}.
 */
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
  void asyncStepSetting() {
    Map<String, String> values = new HashMap<String, String>();
    List<String> warnings = new ArrayList<String>();
    assertThat(HttpSettings.capture(AgentConfig.of(values)).asyncStep()).isFalse();
    values.put("async.step", "true");
    assertThat(HttpSettings.capture(AgentConfig.of(values)).asyncStep()).isTrue();
    assertThat(HttpSettings.capture(AgentConfig.of(values)).remoteIpMode())
        .isEqualTo(io.argos.agent.bootstrap.CaptureConfig.DEFAULT.remoteIpMode());
    assertThat(warnings).isEqualTo(Collections.<String>emptyList());
  }
}
