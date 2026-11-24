/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.guard.AgentCounters;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-0010 C-125 (3) (WO-18): {@code capture.user-id.source: mdc:<key>} values go through the same
 * rules as the other sources, and a transaction that ends without one counts {@code
 * argos_agent_user_id_skipped_total{reason="mdc_absent"}}; {@code mdc_unsupported} is gone.
 */
@Tag("req:SEC-32")
class UserIdsTest {
  private static final byte[] SALT = "user-ids-test-salt".getBytes(StandardCharsets.UTF_8);

  private final AgentCounters counters = new AgentCounters();
  private final UserIds ids = new UserIds(counters, () -> SALT);

  private static CaptureConfig mdc(int mode) {
    return CaptureConfig.builder().userIdMode(mode).userIdSource("mdc:user").build();
  }

  @Test
  @DisplayName(
      "C-125 (3) mdc:user plain: the first usable value of the transaction is kept, sanitized; no"
          + " mdc_absent count")
  void plainFirstValueWins() {
    Transaction tx = transaction();
    CaptureConfig capture = mdc(CaptureConfig.USER_ID_PLAIN);
    ids.apply(tx, capture, " \u0007 ");
    assertThat(tx.userIdDecided()).as("nothing usable yet").isFalse();
    ids.apply(tx, capture, " kim\u0007 ");
    ids.apply(tx, capture, "lee");
    ids.atEnd(tx, capture);
    assertThat(tx.userId()).isEqualTo("kim");
    assertThat(counters.userIdSkipped(AgentCounters.USER_ID_SKIPPED_MDC_ABSENT)).isZero();
  }

  @Test
  @DisplayName(
      "C-125 (3) mdc:user hashed: the salted hash of the normalized value, never the value")
  void hashed() {
    Transaction tx = transaction();
    CaptureConfig capture = mdc(CaptureConfig.USER_ID_HASHED);
    ids.apply(tx, capture, "kim");
    assertThat(tx.userId()).isEqualTo(ids.hash("kim")).hasSize(UserIds.HASH_CHARS);
    assertThat(tx.userId()).doesNotContain("kim");
  }

  @Test
  @DisplayName(
      "C-125 (3) a transaction without an MDC value counts mdc_absent once at its end (no WARN);"
          + " other sources and mode off count nothing")
  void absentAtEnd() {
    Transaction none = transaction();
    ids.atEnd(none, mdc(CaptureConfig.USER_ID_PLAIN));
    assertThat(none.userId()).isNull();
    assertThat(counters.userIdSkipped(AgentCounters.USER_ID_SKIPPED_MDC_ABSENT)).isEqualTo(1);

    ids.atEnd(transaction(), mdc(CaptureConfig.USER_ID_OFF));
    ids.atEnd(
        transaction(),
        CaptureConfig.builder()
            .userIdMode(CaptureConfig.USER_ID_PLAIN)
            .userIdSource("header:X-User-Id")
            .build());
    assertThat(counters.userIdSkipped(AgentCounters.USER_ID_SKIPPED_MDC_ABSENT)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "C-125 (3) hashed without a salt: no_salt decides the transaction, mdc_absent is not counted"
          + " as well")
  void noSaltIsNotAbsent() {
    UserIds unsalted = new UserIds(counters, () -> new byte[0]);
    Transaction tx = transaction();
    CaptureConfig capture = mdc(CaptureConfig.USER_ID_HASHED);
    unsalted.apply(tx, capture, "kim");
    unsalted.atEnd(tx, capture);
    assertThat(tx.userId()).isNull();
    assertThat(counters.userIdSkipped(AgentCounters.USER_ID_SKIPPED_NO_SALT)).isEqualTo(1);
    assertThat(counters.userIdSkipped(AgentCounters.USER_ID_SKIPPED_MDC_ABSENT)).isZero();
  }

  @Test
  @DisplayName(
      "C-125 (3) the tracer: setUserId with an mdc: source records the value, a transaction that"
          + " ends without one counts mdc_absent, and the reason set has no mdc_unsupported")
  void tracerEnd() {
    List<Transaction> ended = new ArrayList<Transaction>();
    TracerImpl tracer =
        TracerImpl.builder().counters(counters).sink(ended::add).userIdSalts(() -> SALT).build();
    AgentControl control = TestControls.newControl();
    control.setCapture(mdc(CaptureConfig.USER_ID_PLAIN));
    try {
      TraceContext with = tracer.begin(Kind.HTTP, "GET /a", System.nanoTime());
      tracer.setUserId(with, "kim");
      tracer.end(with, 200, null, null, null, System.nanoTime());
      TraceContext without = tracer.begin(Kind.HTTP, "GET /b", System.nanoTime());
      tracer.end(without, 200, null, null, null, System.nanoTime());
    } finally {
      control.setCapture(CaptureConfig.DEFAULT);
    }
    assertThat(ended).extracting(Transaction::userId).containsExactly("kim", null);
    assertThat(counters.userIdSkipped(AgentCounters.USER_ID_SKIPPED_MDC_ABSENT)).isEqualTo(1);
    assertThat(AgentCounters.USER_ID_SKIPPED_REASONS).containsExactly("no_salt", "mdc_absent");
  }

  private static Transaction transaction() {
    TraceContext ctx =
        new TraceContext(1, 2, 3, 4, 0, Kind.HTTP, 0, 0, Thread.currentThread(), false, -1, -1);
    return new Transaction(ctx, 10, "t", "t", 0);
  }
}
