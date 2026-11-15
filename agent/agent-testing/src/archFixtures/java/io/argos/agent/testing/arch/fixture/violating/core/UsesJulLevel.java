/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.core;

import java.util.logging.Level;

/** AGT-02 violation: JUL's values are for the JUL bridge only (ADR-0005 decision 4). */
public final class UsesJulLevel {
  public int info() {
    return Level.INFO.intValue();
  }
}
