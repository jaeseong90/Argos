/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

/** A compliant helper used by the violating advice classes. */
public final class DemoHelper {
  private DemoHelper() {}

  public static void record(Object value) {
    if (value == null) {
      throw new IllegalArgumentException("value");
    }
  }

  public static Object value() {
    return DemoHelper.class;
  }
}
