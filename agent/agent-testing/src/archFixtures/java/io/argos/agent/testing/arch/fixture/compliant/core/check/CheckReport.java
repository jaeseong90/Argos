/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.core.check;

/** Output of `java -jar argos-agent.jar --check` (F-A27): may print to stdout (AGT-02). */
public final class CheckReport {
  public void print(String report) {
    System.out.println(report);
  }
}
