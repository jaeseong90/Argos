/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Outcome classes of external calls for {@link Tracer#recordExtStat} (F-A05, 10 §6.5, protocol
 * {@code FailureKind}).
 */
public final class FailureKind {
  public static final int NONE = 0;
  public static final int TIMEOUT = 1;
  public static final int CONNECT_FAIL = 2;
  public static final int HTTP_5XX = 3;
  public static final int OTHER = 4;

  private FailureKind() {}
}
