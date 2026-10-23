/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.core.CoreService;

/** B-2 violation: a helper (injected into the application class loader) that references core. */
public final class CoreReferenceHelper {
  private CoreReferenceHelper() {}

  public static void submit(Object value) {
    CoreService.submit(value);
  }
}
