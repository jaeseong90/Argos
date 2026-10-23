/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.core;

/** A core class (agent class loader) that advice and helper code must not reference (B-2). */
public final class CoreService {
  private static int submitted;

  private CoreService() {}

  public static void submit(Object value) {
    if (value != null) {
      submitted++;
    }
  }

  public static int submitted() {
    return submitted;
  }
}
