/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.core;

/** AGT-02 violation: agent code writing to the application's stdout/stderr (F-A35). */
public final class PrintsToConsole {
  public void report(String message, Exception error) {
    System.out.println(message);
    error.printStackTrace();
  }
}
