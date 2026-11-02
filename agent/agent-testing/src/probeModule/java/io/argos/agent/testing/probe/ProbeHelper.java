/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.probe;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;

/**
 * Helper injected into the application class loader (10 §5.4): bootstrap API only, no state. The
 * attach tests check that it is defined in the application loader, not in the agent loader.
 */
public final class ProbeHelper {
  private ProbeHelper() {}

  public static TraceContext begin(String name) {
    Tracer tracer = Argos.tracer();
    TraceContext context = tracer.begin(Kind.HTTP, "GET /probe", System.nanoTime());
    if (context != null) {
      tracer.setServiceName(context, "GET /probe/" + name, true);
      tracer.setHttp(context, "GET", "/probe/" + name, "127.0.0.1", null);
      tracer.stepDetail(
          context,
          tracer.stepStart(
              context,
              StepType.METHOD,
              tracer.dict(DictKind.METHOD, "probe helper loader"),
              0L,
              System.nanoTime()),
          // Where the helper was defined: the application class loader, never the agent's.
          String.valueOf(ProbeHelper.class.getClassLoader()));
    }
    return context;
  }

  public static void end(TraceContext context, Throwable error) {
    Argos.tracer().end(context, error == null ? 200 : 500, null, null, error, System.nanoTime());
  }

  public static int sqlStart(TraceContext context, String sql) {
    Tracer tracer = Argos.tracer();
    return tracer.stepStart(
        context, StepType.SQL, tracer.dict(DictKind.SQL, sql), 0L, System.nanoTime());
  }

  public static void sqlEnd(TraceContext context, int seq, Throwable error) {
    Argos.tracer().stepEnd(context, seq, 0, error != null, 0L, System.nanoTime());
  }
}
