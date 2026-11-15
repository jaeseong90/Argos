/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.bootstrap;

/**
 * Thrown by a {@link StartupPhase} to refuse the start with an operator-facing reason. The message
 * becomes part of the single ERROR line of the failed start, so it must be one line and must not
 * contain secret values. The cause, if any, is kept for debugging but never logged above DEBUG.
 */
public class StartupPhaseException extends Exception {

  private static final long serialVersionUID = 1L;

  /** A refusal without an underlying exception, e.g. a forbidden password. */
  public StartupPhaseException(String message) {
    super(message);
  }

  /** A refusal caused by another exception, e.g. a failed migration statement. */
  public StartupPhaseException(String message, Throwable cause) {
    super(message, cause);
  }
}
