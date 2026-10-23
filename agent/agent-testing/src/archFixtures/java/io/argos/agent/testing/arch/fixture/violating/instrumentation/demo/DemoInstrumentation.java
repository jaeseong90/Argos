/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.instrumentation.api.InstrumentationModule;

/** Module definition referenced from core by ReferencesModule (B-3 violation fixture). */
public final class DemoInstrumentation implements InstrumentationModule {
  @Override
  public String id() {
    return "demo";
  }
}
