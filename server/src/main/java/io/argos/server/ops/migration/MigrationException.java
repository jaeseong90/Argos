/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

/**
 * A migration that must stop the start: invalid or changed migration files, a statement that
 * failed, a database that cannot be reached. The message is one operator-facing line without secret
 * values (statements and file names only; never passwords or connection strings with credentials).
 */
public class MigrationException extends Exception {

  private static final long serialVersionUID = 1L;

  /** A refusal without an underlying exception (lint, checksum, unknown version). */
  public MigrationException(String message) {
    super(message);
  }

  /** A failure caused by another exception (database error). */
  public MigrationException(String message, Throwable cause) {
    super(message, cause);
  }

  /** The message of {@code failure} reduced to one line of at most {@code max} characters. */
  static String oneLine(Throwable failure, int max) {
    String message = failure.getMessage();
    String text =
        message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : message.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ").strip();
    return text.length() <= max ? text : text.substring(0, max - 3) + "...";
  }
}
