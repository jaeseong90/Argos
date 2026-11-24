/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.custom.testagent;

import java.lang.instrument.Instrumentation;

/**
 * Test-only {@code -javaagent} (build.gradle.kts {@code testAgentJar}): keeps the JVM's {@link
 * Instrumentation} so that the module tests can install the custom module with the core's installer
 * and retransform fixture classes with the core's {@code Retransformer}. Does nothing else.
 */
public final class InstrumentationHolderAgent {
  private static volatile Instrumentation instrumentation;

  private InstrumentationHolderAgent() {}

  public static void premain(String args, Instrumentation inst) {
    instrumentation = inst;
  }

  /** The instrumentation, or null when the JVM was started without the holder agent. */
  public static Instrumentation instrumentation() {
    return instrumentation;
  }
}
