/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

/** A wrong command line or missing input file (exit code 2). */
final class UsageException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  UsageException(String message) {
    super(message);
  }
}
