/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.probe;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.shaded.net.bytebuddy.asm.Advice;

/** SQL step per {@code Store.query(String)}; the step sequence travels in an advice local. */
public final class QueryAdvice {
  private QueryAdvice() {}

  // The advice local is written here and read by exit(); Error Prone cannot see that link.
  @SuppressWarnings("UnusedVariable")
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static TraceContext enter(
      @Advice.Argument(0) String sql, @Advice.Local("argosSeq") int seq) {
    if (Argos.skip(ProbeModule.MODULE_ID)) {
      return null;
    }
    TraceContext context = Argos.tracer().current();
    if (context == null) {
      return null;
    }
    seq = ProbeHelper.sqlStart(context, sql);
    return context;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter TraceContext context,
      @Advice.Local("argosSeq") int seq,
      @Advice.Thrown Throwable error) {
    if (context == null) {
      return;
    }
    ProbeHelper.sqlEnd(context, seq, error);
  }
}
