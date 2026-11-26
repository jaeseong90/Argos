/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import java.util.Arrays;

/**
 * Entry point of {@code serverLoadTest} (18 §8, WO-29). Arguments are {@code --key=value} pairs
 * ({@link LoadOptions#parse}); the Gradle task passes the profile, the machine profile, the boot
 * jar, the report directory and {@code -Pserver.load.args}. Exit code 0 when every invariant
 * passed, 1 when one failed or the run aborted, 2 on a usage error.
 */
public final class ServerLoadMain {

  private ServerLoadMain() {}

  /** Runs one load test. */
  public static void main(String[] args) throws Exception {
    LoadOptions options;
    try {
      options = LoadOptions.parse(Arrays.asList(args));
    } catch (IllegalArgumentException e) {
      System.err.println("serverLoadTest: " + e.getMessage());
      System.exit(2);
      return;
    }
    if (options.machineProfile() == null || options.machineProfile().isBlank()) {
      System.err.println("serverLoadTest: --machine-profile is required (18 §2.2)");
      System.exit(2);
      return;
    }
    LoadRunner.log(
        "profile "
            + options.profile().id()
            + " on machine profile "
            + options.machineProfile()
            + (options.fullScale() ? " (N-06 at full scale)" : " (timing verdicts INVALID)"));
    int code = new LoadRunner(options).run();
    System.exit(code);
  }
}
