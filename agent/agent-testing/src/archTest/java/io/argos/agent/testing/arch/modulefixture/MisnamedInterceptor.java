/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.modulefixture;

import net.bytebuddy.asm.Advice;

/** Violating advice fixture: an advice class whose name does not end with "Advice". */
public final class MisnamedInterceptor {
  private MisnamedInterceptor() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter() {}
}
