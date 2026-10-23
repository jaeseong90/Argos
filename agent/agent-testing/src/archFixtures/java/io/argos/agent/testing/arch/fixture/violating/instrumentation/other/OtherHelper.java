/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.other;

/** Helper of another instrumentation module: injected only for that module's advice. */
public final class OtherHelper {
  private OtherHelper() {}

  public static void record(Object value) {
    if (value == null) {
      throw new IllegalArgumentException("value");
    }
  }
}
