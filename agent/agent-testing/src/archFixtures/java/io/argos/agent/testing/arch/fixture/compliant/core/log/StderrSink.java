/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.core.log;

/** The logger's limited stderr fallback: the one place allowed to write to stderr (AGT-02). */
public final class StderrSink {
  private int remaining = 10;

  public void write(String line) {
    if (remaining > 0) {
      remaining--;
      System.err.println(line);
    }
  }
}
