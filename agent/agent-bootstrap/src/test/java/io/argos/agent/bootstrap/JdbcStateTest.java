/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The WO-13 bootstrap additions (10 §3 extension): the JDBC store values {@link SqlState} and
 * {@link FetchState} (A-9 types with bounded bind parameters, SEC-3A, 16 §6.7), {@link PoolKind}
 * and the no-op answers of {@link NoopTracer} for the new {@link Tracer} methods.
 */
class JdbcStateTest {

  @Test
  @Tag("req:SEC-3A")
  @DisplayName(
      "T-AG-033a SqlState keeps bind values bounded: indexes 1..64, 256 characters per value, 4,096"
          + " together, only the first parameter set of a batch")
  void sqlStateParameters() {
    SqlState state = new SqlState("select * from t where a = ? and b = ?", 7L);
    assertThat(state.rawSql).startsWith("select");
    assertThat(state.dbId).isEqualTo(7L);
    assertThat(state.seq).isEqualTo(-1);
    assertThat(state.paramCount()).isZero();

    state.param(1, "'alice'");
    state.param(2, repeat('x', 1_000));
    assertThat(state.param(1)).isEqualTo("'alice'");
    assertThat(state.param(2)).hasSize(SqlState.MAX_VALUE_CHARS);
    assertThat(state.paramCount()).isEqualTo(2);
    state.param(0, "zero");
    state.param(SqlState.MAX_PARAMS + 1, "65");
    assertThat(state.param(0)).isNull();
    assertThat(state.param(SqlState.MAX_PARAMS + 1)).isNull();
    state.param(SqlState.MAX_PARAMS, "64");
    assertThat(state.param(SqlState.MAX_PARAMS)).isEqualTo("64");
    assertThat(state.paramCount()).isEqualTo(SqlState.MAX_PARAMS);

    state.param(2, null);
    assertThat(state.param(2)).isNull();
    assertThat(state.paramChars()).isEqualTo("'alice'".length() + 2);

    SqlState full = new SqlState();
    for (int i = 1; i <= SqlState.MAX_PARAMS; i++) {
      full.param(i, repeat('v', SqlState.MAX_VALUE_CHARS));
    }
    assertThat(full.paramChars()).isLessThanOrEqualTo(SqlState.MAX_PARAM_CHARS);
    assertThat(full.paramCount()).isEqualTo(SqlState.MAX_PARAM_CHARS / SqlState.MAX_VALUE_CHARS);
    full.param(1, "short");
    assertThat(full.param(1)).as("a replacement that fits is kept").isEqualTo("short");

    SqlState batch = new SqlState();
    batch.param(1, "first");
    assertThat(batch.addBatch()).isEqualTo(1);
    assertThat(batch.paramsFrozen()).isTrue();
    batch.param(1, "second");
    batch.clearParams();
    assertThat(batch.param(1)).as("only the first set of a batch").isEqualTo("first");
    assertThat(batch.addBatch()).isEqualTo(2);
    assertThat(batch.endBatch()).isEqualTo(2);
    assertThat(batch.batchCount).isZero();
    assertThat(batch.paramsFrozen()).isFalse();
    batch.clearParams();
    assertThat(batch.paramCount()).isZero();
    assertThat(batch.paramChars()).isZero();
  }

  @Test
  @DisplayName(
      "FetchState counts rows of next() calls that returned one and times from the first call;"
          + " reported once")
  void fetchState() {
    FetchState fetch = new FetchState(3);
    assertThat(fetch.seq).isEqualTo(3);
    assertThat(fetch.started()).isFalse();
    assertThat(fetch.fetchNanos(100)).isZero();
    fetch.next(-50, true);
    fetch.next(-20, true);
    fetch.next(10, false);
    assertThat(fetch.rows).isEqualTo(2);
    assertThat(fetch.firstNextNanos).isEqualTo(-50);
    assertThat(fetch.lastNextNanos).isEqualTo(10);
    assertThat(fetch.fetchNanos(30)).isEqualTo(80);
    assertThat(fetch.fetchNanos(-100)).as("never negative").isZero();
    assertThat(fetch.markReported()).isTrue();
    assertThat(fetch.markReported()).isFalse();
    assertThat(new FetchState().seq).isEqualTo(-1);
  }

  @Test
  @Tag("req:SEC-3A")
  @DisplayName(
      "T-AG-033b SqlState detail: '<index>=<value>' in index order, gaps left out, null when"
          + " empty; endBatch drops the batch text of a plain statement")
  void sqlStateDetailAndBatchText() {
    SqlState state = new SqlState();
    assertThat(state.paramDetail()).isNull();
    state.param(3, "'c'");
    state.param(1, "[String(5)]");
    assertThat(state.paramDetail()).isEqualTo("1=[String(5)], 3='c'");
    state.clearParams();
    assertThat(state.paramDetail()).isNull();
    state.batchSql = "insert into t values (1)";
    state.addBatch();
    assertThat(state.endBatch()).isEqualTo(1);
    assertThat(state.batchSql).isNull();
    assertThat(state.fetch).isNull();
    assertThat(state.paramWhitelistKnown).isFalse();
  }

  @Test
  @DisplayName(
      "FetchState per-row path: begin() at the first next(), row(false) marks the end once; the"
          + " report ends at the last next() or at the close/next execution")
  void fetchStateRows() {
    FetchState fetch = new FetchState(5, 77L);
    assertThat(fetch.txId).isEqualTo(77L);
    assertThat(fetch.needsStart()).isTrue();
    fetch.begin(100);
    fetch.begin(200);
    assertThat(fetch.firstNextNanos).isEqualTo(100);
    assertThat(fetch.needsStart()).isFalse();
    fetch.row(true);
    fetch.row(true);
    assertThat(fetch.ended()).isFalse();
    assertThat(fetch.endNanos(1_000)).as("not ended: the report time").isEqualTo(1_000);
    fetch.row(false);
    long end = fetch.lastNextNanos;
    fetch.row(false);
    assertThat(fetch.lastNextNanos).as("only the first false call is timed").isEqualTo(end);
    assertThat(fetch.rows).isEqualTo(2);
    assertThat(fetch.ended()).isTrue();
    assertThat(fetch.endNanos(Long.MAX_VALUE)).isEqualTo(end);
  }

  @Test
  @DisplayName(
      "DbInfo: a connection's target, else the last target of its data source; resolve() passes"
          + " a known target on to the data source")
  void dbInfo() {
    DbInfo source = new DbInfo();
    DbInfo first = new DbInfo(source);
    assertThat(first.knownTarget()).isZero();
    first.resolve(42L);
    assertThat(first.resolved).isTrue();
    assertThat(source.dbId).isEqualTo(42L);
    DbInfo second = new DbInfo(source);
    assertThat(second.resolved).isFalse();
    assertThat(second.knownTarget()).as("the data source's last target").isEqualTo(42L);
    second.resolve(0L);
    assertThat(source.dbId).as("an unknown target is not passed on").isEqualTo(42L);
    assertThat(new DbInfo().knownTarget()).isZero();
  }

  @Test
  @DisplayName(
      "PoolKind numbers and labels are pinned; NoopTracer ignores the WO-13 calls (id 0, no state)")
  void poolKindAndNoop() throws Exception {
    assertThat(Arrays.asList(PoolKind.HIKARI, PoolKind.TOMCAT_JDBC, PoolKind.DBCP2, PoolKind.COUNT))
        .containsExactly(1, 2, 3, 4);
    assertThat(PoolKind.name(PoolKind.HIKARI)).isEqualTo("hikari");
    assertThat(PoolKind.name(PoolKind.TOMCAT_JDBC)).isEqualTo("tomcat-jdbc");
    assertThat(PoolKind.name(PoolKind.DBCP2)).isEqualTo("dbcp2");
    assertThat(PoolKind.name(0)).isEqualTo("unknown");
    assertThat(PoolKind.name(4)).isEqualTo("unknown");
    assertThat(PoolKind.valid(0)).isFalse();
    assertThat(PoolKind.valid(3)).isTrue();

    NoopTracer noop = NoopTracer.INSTANCE;
    noop.stepBatch(null, 1, 10);
    noop.registerPool(new Object(), PoolKind.HIKARI);
    noop.registerConnector(new Object());
    assertThat(noop.poolNameId(new Object())).isZero();
    assertThat(noop.sqlId("select 1", 7L)).isZero();
    SqlState state = new SqlState("select ?", 0L);
    noop.sqlParam(state, 1, "secret", "setString");
    assertThat(state.paramCount()).isZero();
    // Every Tracer method has a NoopTracer body (no abstract method left).
    assertThat(Tracer.class.getMethod("stepBatch", TraceContext.class, int.class, int.class))
        .isNotNull();
    assertThat(
            NoopTracer.class.getMethod("registerPool", Object.class, int.class).getDeclaringClass())
        .isEqualTo(NoopTracer.class);
  }

  private static String repeat(char c, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, c);
    return new String(chars);
  }
}
