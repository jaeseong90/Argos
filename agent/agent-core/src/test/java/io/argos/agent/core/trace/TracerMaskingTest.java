/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.masking.Masker;
import io.argos.agent.core.transport.Queues;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The end of a transaction with a user masking pattern that fails (SEC-36, 16 §6.6): the masker
 * fails closed and the transaction still reaches the transaction queue (N-05: counted drops only).
 */
@Tag("req:SEC-36")
class TracerMaskingTest {
  @AfterEach
  void restoreMasker() {
    Masker.install(null);
  }

  @Test
  @DisplayName(
      "T-SEC-36 an exception message that overflows a user pattern's stack is <masked> and the"
          + " transaction is queued")
  void failingUserPatternKeepsTheTransaction() {
    Masker.install(Masker.of(Collections.singletonList("(?:a|b)*c"), new ArrayList<String>()));
    long failedBefore = Masker.budgetExceededTotal();
    AgentCounters counters = new AgentCounters();
    Queues queues = Queues.create(100, 1L << 30, 1L << 30, 120, counters, null);
    TracerImpl tracer =
        TracerImpl.builder().counters(counters).queues(queues).txIds(new TxIdGenerator(5)).build();
    StringBuilder message = new StringBuilder("lookup failed for kim@example.test ");
    while (message.length() < Masker.MAX_INPUT_CHARS) {
      message.append("ab");
    }

    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /mask", 0);
    tracer.end(ctx, 500, null, null, new IllegalStateException(message.toString()), 5_000_000L);

    final List<Transaction> queued = new ArrayList<Transaction>();
    queues
        .transaction()
        .drain(
            10,
            new Consumer<Object>() {
              @Override
              public void accept(Object item) {
                queued.add((Transaction) item);
              }
            });
    assertThat(queued).hasSize(1);
    Transaction tx = queued.get(0);
    assertThat(tx.context()).isSameAs(ctx);
    assertThat(tx.errorClass()).isEqualTo("java.lang.IllegalStateException");
    assertThat(tx.errorMessage()).isEqualTo(Masker.BUDGET_EXCEEDED);
    assertThat(Masker.budgetExceededTotal()).isEqualTo(failedBefore + 1);
  }
}
