/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.instrumentation.httpclient.demo11;

import io.argos.agent.testing.arch.fixture.compliant.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Advice of a module with a hyphenated Gradle name (httpclient-demo11, package httpclient.demo11,
 * like httpclient-jdk11): its allow-list entry uses the Gradle name.
 */
public final class Demo11HeaderAdvice {
  static final int MODULE_ID = 11;

  private Demo11HeaderAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(value = 0, readOnly = false) String[] headers) {
    if (Argos.skip(MODULE_ID)) {
      return;
    }
    headers = Demo11Helper.withHeader(headers);
  }
}
