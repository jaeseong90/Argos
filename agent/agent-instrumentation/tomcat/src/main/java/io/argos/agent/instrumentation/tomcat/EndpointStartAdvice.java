/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.tomcat;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the exit of {@code org.apache.tomcat.util.net.AbstractEndpoint.start()} (10 §6.4):
 * when the endpoint started (no exception) it is registered with the core's Tomcat registry, which
 * reads its getters later on an agent thread and publishes the connector list and port ({@code
 * InstanceUpdate}) without blocking this thread.
 */
public final class EndpointStartAdvice {
  private EndpointStartAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.This Object endpoint, @Advice.Thrown Throwable error) {
    if (Argos.skip(TomcatModule.MODULE_ID)) {
      return;
    }
    if (error == null) {
      Argos.tracer().registerConnector(endpoint);
    }
  }
}
