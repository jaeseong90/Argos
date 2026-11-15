/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CaptureConfig;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into the {@code set*(int, value[, ..])} parameter setters of {@code PreparedStatement}
 * and {@code CallableStatement} implementations (10 §6.3, F-A04, SEC-3A, 16 §6.7). Always
 * installed; in the default {@code masked} mode it returns at once, before the value is even boxed.
 * Otherwise the core renders the value for the mode ({@code Tracer.sqlParam}: type whitelist, no
 * {@code toString()} of other objects, no stream or LOB access, masking, 256 characters, 64
 * indexes) into the statement's {@code SqlState}.
 */
public final class SetParameterAdvice {
  private SetParameterAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(
      @Advice.This Object statement,
      @Advice.Argument(0) int index,
      @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) Object value,
      @Advice.Origin("#m") String setter) {
    if (Argos.skip(JdbcModule.MODULE_ID)) {
      return;
    }
    if (Argos.tracer().capture().sqlParamMode() == CaptureConfig.SQL_PARAMS_MASKED) {
      return;
    }
    JdbcHelper.parameter(statement, index, value, setter);
  }
}
