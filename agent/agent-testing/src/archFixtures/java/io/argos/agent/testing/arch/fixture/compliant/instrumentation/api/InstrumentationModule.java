/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.instrumentation.api;

import java.util.List;

/** Instrumentation framework API (10 §5.1), the only instrumentation package core may use. */
public interface InstrumentationModule {
  String id();

  int moduleId();

  List<String> helperClassNames();
}
