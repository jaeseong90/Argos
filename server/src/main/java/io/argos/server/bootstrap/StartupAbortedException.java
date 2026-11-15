/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.bootstrap;

import org.springframework.boot.ExitCodeGenerator;

/**
 * The start was aborted by a failed {@link StartupPhase}. {@link StartupSequence} has already
 * logged the single ERROR line; {@link StartupFailureReporter} tells Spring Boot so that it does
 * not log the failure again, and {@link #getExitCode()} makes the process exit with {@link
 * #EXIT_CODE}.
 *
 * <p>The message is the sanitized one-line reason (no secret values); the cause of the failed step
 * is attached for debugging only.
 */
public final class StartupAbortedException extends RuntimeException implements ExitCodeGenerator {

  /** Process exit code of an aborted start. */
  public static final int EXIT_CODE = 1;

  private static final long serialVersionUID = 1L;

  private final PhaseOrder phase;
  private final String step;

  StartupAbortedException(PhaseOrder phase, String step, String message, Throwable cause) {
    super(message, cause);
    this.phase = phase;
    this.step = step;
  }

  /** The phase that failed. */
  public PhaseOrder phase() {
    return phase;
  }

  /** The name of the step that failed ({@link StartupPhase#name()}). */
  public String step() {
    return step;
  }

  @Override
  public int getExitCode() {
    return EXIT_CODE;
  }
}
