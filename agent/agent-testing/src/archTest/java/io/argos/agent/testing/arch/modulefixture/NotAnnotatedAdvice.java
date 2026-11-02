/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.modulefixture;

/** Violating advice fixture: no @Advice.OnMethodEnter/OnMethodExit method. */
public final class NotAnnotatedAdvice {
  private NotAnnotatedAdvice() {}

  public static void enter() {}
}
