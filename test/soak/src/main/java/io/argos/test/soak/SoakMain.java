/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

/**
 * Entry point of {@code ./gradlew soakTest} (18 §11.2, WO-52). Exit codes: 0 no criterion failed
 * (INVALID rows included), 1 a criterion failed or the run aborted, 2 bad options.
 */
public final class SoakMain {

  private SoakMain() {}

  public static void main(String[] args) throws Exception {
    SoakOptions options;
    try {
      options = SoakOptions.parse(args);
    } catch (IllegalArgumentException e) {
      System.err.println("soakTest: " + e.getMessage());
      System.exit(2);
      return;
    }
    if (options.machineProfile().isEmpty()) {
      System.err.println("soakTest: --machine-profile (-Pbench.machine-profile) is required");
      System.exit(2);
      return;
    }
    System.exit(new SoakRunner(options).run());
  }
}
