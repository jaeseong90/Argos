/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.bootstrap;

/**
 * One step of the server start sequence (DSN-12-15). Every implementation is a Spring bean; {@link
 * StartupSequence} runs all of them synchronously while the application context refreshes, ordered
 * by {@link #order()} and, within one phase, by {@link org.springframework.core.Ordered} or {@link
 * org.springframework.core.annotation.Order} and then by {@link #name()}.
 *
 * <p>Rules for implementations (later work orders plug in here, never by reordering Spring
 * auto-configuration):
 *
 * <ul>
 *   <li>Do the work in {@link #run()}, not in a constructor or {@code @PostConstruct}: beans are
 *       created before the sequence runs, so a constructor must not touch PostgreSQL, ClickHouse,
 *       the WAL or the network.
 *   <li>Fail by throwing. A {@link StartupPhaseException} carries an operator-facing message that
 *       is logged as the single ERROR line of the failed start; the message must not contain secret
 *       values (passwords, tokens, keys, connection strings with credentials). Any other exception
 *       is reported by its class name and message.
 *   <li>Do not log the duration; the sequence logs one INFO line per step with its duration.
 * </ul>
 */
public interface StartupPhase {

  /** The phase this step belongs to. */
  PhaseOrder order();

  /**
   * Short, stable step name for the start log, e.g. {@code postgres-flyway}. Unique within the
   * application context. Defaults to the simple class name.
   */
  default String name() {
    return getClass().getSimpleName();
  }

  /**
   * Runs the step. Blocks until it is complete.
   *
   * @throws Exception when the server must not start; the sequence stops and the start fails
   */
  void run() throws Exception;
}
