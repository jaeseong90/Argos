/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.FailureKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-ACC-10 (docs/18 section 10, D-10): an error transaction has one criterion, an unhandled
 * exception or HTTP 5xx. 4xx is not an error (it is a filter of its own). Runs the real {@link
 * TracerImpl} end path that sets the transaction's error flag (the {@code error} field the batch
 * encoder and the JSONL exporter send) and the external-call classifier {@link HttpFailures}.
 */
@Tag("req:D-10")
class ErrorClassificationTest {
  private final List<Transaction> ended = new ArrayList<Transaction>();
  private final AgentControl control = TestControls.newControl();
  private final TracerImpl tracer =
      new TracerImpl(
          new Dictionary(),
          new TracerImpl.Sink() {
            @Override
            public void accept(Transaction tx) {
              ended.add(tx);
            }
          },
          control,
          new TxIdGenerator(7),
          1000,
          3);

  @AfterEach
  void reset() {
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
  }

  private int errorCount() {
    int errors = 0;
    for (Transaction tx : ended) {
      if (tx.context().error) {
        errors++;
      }
    }
    return errors;
  }

  @Test
  @DisplayName("T-ACC-10 100 transactions ending in 4xx (400..499) are not errors: error count 0")
  void clientErrorsAreNotErrors() {
    for (int status = 400; status < 500; status++) {
      TraceContext ctx = tracer.begin(Kind.HTTP, "GET /orders/" + status, 1_000);
      tracer.end(ctx, status, null, null, null, 2_000);
    }

    assertThat(ended).hasSize(100);
    assertThat(errorCount()).isZero();
    for (int i = 0; i < ended.size(); i++) {
      assertThat(ended.get(i).context().status).isEqualTo(400 + i);
      assertThat(ended.get(i).errorClass()).isNull();
    }
  }

  @Test
  @DisplayName("T-ACC-10 every 5xx status (500..599) without an exception is an error")
  void serverErrorsAreErrors() {
    for (int status = 500; status < 600; status++) {
      TraceContext ctx = tracer.begin(Kind.HTTP, "GET /pay/" + status, 1_000);
      tracer.end(ctx, status, null, null, null, 2_000);
    }

    assertThat(ended).hasSize(100);
    assertThat(errorCount()).isEqualTo(100);
  }

  @Test
  @DisplayName(
      "T-ACC-10 an uncaught exception is an error whatever the status (200, 0 = no response, 404),"
          + " with its class recorded")
  void uncaughtExceptionIsAnError() {
    int[] statuses = {200, 0, 404};
    for (int status : statuses) {
      TraceContext ctx = tracer.begin(Kind.HTTP, "POST /checkout", 1_000);
      tracer.end(ctx, status, null, null, new IllegalStateException("boom"), 2_000);
    }
    TraceContext batch = tracer.begin(Kind.BATCH, "nightly-job", 1_000);
    tracer.end(batch, 0, null, null, new IllegalArgumentException("bad row"), 2_000);

    assertThat(ended).hasSize(4);
    assertThat(errorCount()).isEqualTo(4);
    for (Transaction tx : ended) {
      assertThat(tx.errorClass()).endsWith("Exception");
    }
  }

  @Test
  @DisplayName(
      "T-ACC-10 an exception reported by class name only (servlet error attribute) is an error")
  void exceptionByClassNameIsAnError() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /report", 1_000);
    tracer.end(ctx, 200, "com.acme.ReportFailure", "report failed", null, 2_000);

    assertThat(ended).hasSize(1);
    assertThat(ended.get(0).context().error).isTrue();
  }

  @Test
  @DisplayName(
      "T-ACC-10 a 2xx or 3xx transaction stays successful even with a failed step inside (the"
          + " single criterion: no unhandled exception, no 5xx)")
  void failedStepDoesNotMakeAnErrorTransaction() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /search", 1_000);
    int sql = tracer.stepStart(ctx, StepType.SQL, 11L, 0L, 1_100);
    tracer.stepEnd(ctx, sql, 0, true, 0, 1_200);
    int call = tracer.stepStart(ctx, StepType.HTTP, 12L, 0L, 1_300);
    tracer.stepEnd(ctx, call, 503, true, 0, 1_400);
    tracer.end(ctx, 200, null, null, null, 2_000);
    TraceContext redirect = tracer.begin(Kind.HTTP, "GET /old", 1_000);
    tracer.end(redirect, 302, null, null, null, 2_000);

    assertThat(ended).hasSize(2);
    assertThat(errorCount()).isZero();
  }

  @Test
  @DisplayName(
      "T-ACC-10 external calls follow the same rule (HttpFailures): 4xx is no failure, 5xx is"
          + " HTTP_5XX, an exception is a failure")
  void externalCallsUseTheSameRule() {
    for (int status = 400; status < 500; status++) {
      assertThat(HttpFailures.classify(status, null)).isEqualTo(FailureKind.NONE);
    }
    assertThat(HttpFailures.classify(200, null)).isEqualTo(FailureKind.NONE);
    for (int status = 500; status < 600; status++) {
      assertThat(HttpFailures.classify(status, null)).isEqualTo(FailureKind.HTTP_5XX);
    }
    assertThat(HttpFailures.classify(404, new IllegalStateException("x")))
        .isEqualTo(FailureKind.OTHER);
    assertThat(HttpFailures.classify(0, new SocketTimeoutException("read timed out")))
        .isEqualTo(FailureKind.TIMEOUT);
  }
}
