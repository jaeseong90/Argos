/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.instrumentation.api;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Applies advice by class name (10 §5.1); the call A-7 checks. */
public interface TypeTransformer {
  void applyAdvice(ElementMatcher<? super MethodDescription> methods, String adviceClassName);
}
