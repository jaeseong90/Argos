/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import java.io.PrintStream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Output conventions of the WO-28 {@code argos-admin} commands (17 §4.4): the result on standard
 * output (a table, or one JSON object with {@code --json}), messages on standard error. A failure
 * with {@code --json} prints {@code {"command", "error", "code"}} on standard output as well, so a
 * script that parses standard output always gets one object.
 */
final class CliOutput {

  /** The JSON mapper of the commands. */
  static final JsonMapper JSON = JsonMapper.builder().build();

  private CliOutput() {}

  /** A new JSON result object with {@code command}. */
  static ObjectNode result(String command) {
    ObjectNode node = JSON.createObjectNode();
    node.put("command", command);
    return node;
  }

  /** Prints a JSON result object on one line. */
  static void print(ObjectNode node, PrintStream out) {
    out.println(JSON.writeValueAsString(node));
    out.flush();
  }

  /**
   * Reports a failure.
   *
   * @param json whether {@code --json} was given
   * @param command the subcommand
   * @param message one line without a secret
   * @param code a short machine-readable code ({@code database}, {@code not_found}, ...)
   * @param exit the exit code to return
   * @return {@code exit}
   */
  static int fail(
      boolean json,
      String command,
      String message,
      String code,
      PrintStream out,
      PrintStream err,
      int exit) {
    if (json) {
      ObjectNode node = result(command);
      node.put("error", message);
      node.put("code", code);
      print(node, out);
    }
    err.println("argos-admin " + command + ": " + message);
    err.flush();
    return exit;
  }
}
