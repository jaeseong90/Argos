/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.demo;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The settlement batch of the demo (17 §3.9: "@Scheduled 2 minutes"): a Spring {@code @Scheduled}
 * method, which the agent records as a BATCH transaction (WO-18). First run about 30 s after the
 * start ({@code DEMO_BATCH_INITIAL_DELAY}), then every {@code DEMO_BATCH_PERIOD} (default 120s;
 * durations like {@code 30s}, {@code 2m} or {@code PT2M}). A run before the data is ready is
 * skipped.
 */
@Component
public class SettlementBatch {

  private static final Logger LOG = LoggerFactory.getLogger(SettlementBatch.class);

  private final PaymentJdbc payments;
  private final DemoDatabase database;
  private final AtomicLong runs = new AtomicLong();

  public SettlementBatch(PaymentJdbc payments, DemoDatabase database) {
    this.payments = payments;
    this.database = database;
  }

  @Scheduled(
      initialDelayString = "${demo.batch.initial-delay:30s}",
      fixedDelayString = "${demo.batch.period:120s}")
  public void settle() {
    if (!database.isReady()) {
      LOG.info("Settlement batch skipped: demo data not ready yet");
      return;
    }
    long run = runs.get();
    int shipped = payments.settle(run);
    runs.incrementAndGet();
    LOG.info("Settlement batch run {} shipped {} orders", run + 1, shipped);
  }

  /** Completed runs since the start. */
  public long runs() {
    return runs.get();
  }
}
