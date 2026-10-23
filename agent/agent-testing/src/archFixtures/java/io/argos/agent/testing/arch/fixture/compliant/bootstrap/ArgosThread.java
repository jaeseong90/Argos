/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.bootstrap;

/** Marker superclass of every agent thread (10 §3). */
public class ArgosThread extends Thread {
  public ArgosThread(Runnable task, String name) {
    super(task, name);
  }

  public static boolean isAgentThread() {
    return Thread.currentThread() instanceof ArgosThread;
  }
}
