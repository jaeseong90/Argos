/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

/** Invalid command line arguments (exit code {@link AdminCli#EXIT_USAGE}). */
final class CliUsageException extends Exception {

  private static final long serialVersionUID = 1L;

  CliUsageException(String message) {
    super(message);
  }
}
