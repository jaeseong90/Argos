/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.core;

import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.DemoInstrumentation;

/** B-3 violation: core refers to an individual instrumentation module instead of the API. */
public final class ReferencesModule {
  public String moduleId() {
    return new DemoInstrumentation().id();
  }
}
