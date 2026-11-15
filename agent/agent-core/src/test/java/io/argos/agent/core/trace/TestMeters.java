/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

/** Test access to {@link ThreadMeter} (its constructor is package-private). */
public final class TestMeters {
  private TestMeters() {}

  /** A meter whose values a test sets; it measures. */
  public abstract static class Base extends ThreadMeter {
    protected Base() {}

    @Override
    public boolean measures() {
      return true;
    }
  }
}
