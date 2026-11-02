/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.api;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Applies advice to the methods of a matched type (10 §5.1); implemented by the core.
 *
 * <p>The advice class is given by name: the core reads its class file from the agent jar and
 * inlines it (DSN-02-01), so the advice class itself is never loaded into the application's class
 * loader. Every advice gets an exception handler that counts suppressed exceptions against the
 * module ({@code Argos.adviceError}).
 */
public interface TypeTransformer {
  /**
   * Inlines the advice class {@code adviceClassName} (binary name, a class of the same module) into
   * every method of the matched type that {@code methods} matches.
   */
  void applyAdvice(ElementMatcher<? super MethodDescription> methods, String adviceClassName);
}
