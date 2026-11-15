/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.core.transport.Queues;
import java.util.function.BooleanSupplier;

/**
 * An exporter that drains the queues itself (the gRPC transport, WO-11): batches stay in their
 * queues until it has an in-flight slot (11 §6), so it cannot be handed drained lists like the test
 * exporters. {@link ExportWorker} runs {@link #run} on {@code argos-sender} instead of its own
 * drain loop and calls {@link #shutdown} from the shutdown hook. The list methods of {@link
 * Exporter} are not used for such an exporter.
 */
public interface QueueExporter extends Exporter {
  /** The {@code argos-sender} loop: returns when {@code running} turns false. */
  void run(Queues queues, BooleanSupplier running);

  /** Wakes {@link #run} (a stop was requested). */
  void wake();

  /**
   * The shutdown hook's part (10 §10.2): final messages, then the queued batches within {@code
   * flushMillis} when {@code flush} is true, then the connection is closed. {@code flush} is false
   * when {@link #run} has not returned yet: its thread still owns the sender's single-threaded
   * state, so the hook thread must not send from it. Never throws.
   */
  void shutdown(long flushMillis, boolean flush);

  /** Transaction records the destination acknowledged. */
  long deliveredTransactions();
}
