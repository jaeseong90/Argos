/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

/**
 * The single thread the session runs on: Netty's {@code argos-io} event loop in the agent (10 §10.4
 * "argos-io 루프 위 비동기 스트림"; no extra thread or timer, 10 §14), a manual loop in tests.
 */
interface Loop {
  /** A scheduled task. */
  interface Task {
    void cancel();
  }

  /** Runs {@code task} on the loop thread (later, even when called from it). */
  void execute(Runnable task);

  /** Runs {@code task} on the loop thread after {@code delayMillis}. */
  Task schedule(Runnable task, long delayMillis);

  /** True on the loop thread. */
  boolean inLoop();
}
