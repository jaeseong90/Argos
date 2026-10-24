/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.smoke;

/**
 * Entry point of the sample-app smoke runner. Exit code 0: every catalog entry passed; 1: a failure
 * (details in the report); 2: bad command line. See {@link SmokeOptions#USAGE}.
 */
public final class SmokeMain {

  private SmokeMain() {}

  public static void main(String[] args) throws Exception {
    SmokeOptions options;
    try {
      options = SmokeOptions.parse(args);
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      System.exit(2);
      return;
    }
    int code = new SmokeRun(options).run();
    if (code != 0) {
      System.exit(code);
    }
  }
}
