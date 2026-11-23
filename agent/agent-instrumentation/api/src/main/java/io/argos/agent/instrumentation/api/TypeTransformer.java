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

  /**
   * Asks for the context field of DSN-02-05 (10 §6.6 "필드 주입") on the matched type: a {@code
   * volatile TraceContext argos$ctx} field and an implementation of the bootstrap interface {@code
   * ContextCarrier}, which the task stores of {@code ContextStore} then use instead of their weak
   * map.
   *
   * <p>The core adds them only where the class format may still change: a class being defined for
   * the first time, by an application class loader, that is a concrete class (not an interface,
   * annotation, enum or record) and not serializable. On retransformation, for already loaded, JDK
   * or bootstrap classes, and for every other type this does nothing, and the stores fall back to
   * the weak map. Advice code does not depend on which path a type took.
   */
  default void injectContextField() {}
}
