/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.compliant.bootstrap.Argos;
import io.argos.agent.testing.arch.fixture.compliant.bootstrap.TraceContext;

/** Helper injected into the application class loader: bootstrap types only (10 §5.4, B-2). */
public final class DemoHelper {
  private DemoHelper() {}

  public static TraceContext begin(Object request) {
    return Argos.tracer().begin(String.valueOf(request), System.nanoTime());
  }

  public static void end(TraceContext context, Throwable error) {
    if (context != null) {
      Argos.tracer().end(context, error, System.nanoTime());
    }
  }

  public static String[] withHeader(String[] headers) {
    String[] copy = new String[headers.length + 1];
    System.arraycopy(headers, 0, copy, 0, headers.length);
    copy[headers.length] = "traceparent";
    return copy;
  }
}
