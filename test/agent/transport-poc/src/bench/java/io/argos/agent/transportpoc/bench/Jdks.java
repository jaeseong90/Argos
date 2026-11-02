/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.bench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** JDK helpers: tool paths and the exact runtime version string of a JDK home. */
final class Jdks {
  private static final boolean WINDOWS =
      System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

  private Jdks() {}

  static Path tool(Path home, String name) {
    Path bin = home.resolve("bin").resolve(WINDOWS ? name + ".exe" : name);
    if (!Files.exists(bin)) {
      Path jre = home.resolve("jre").resolve("bin").resolve(WINDOWS ? name + ".exe" : name);
      if (Files.exists(jre)) {
        return jre;
      }
    }
    return bin;
  }

  /** The {@code java -version} output (first two lines joined), or the error. */
  static String version(Path home) {
    try {
      Process process =
          new ProcessBuilder(tool(home, "java").toString(), "-version")
              .redirectErrorStream(true)
              .start();
      String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      process.waitFor(30, TimeUnit.SECONDS);
      String[] lines = text.trim().split("\\R", -1);
      return lines.length > 1 ? lines[0].trim() + " / " + lines[1].trim() : text.trim();
    } catch (Exception e) {
      return "unavailable: " + e;
    }
  }

  /** Major version from a JDK name such as "8", "21" or "8u252". */
  static int major(String name) {
    int u = name.indexOf('u');
    return Integer.parseInt(u < 0 ? name : name.substring(0, u));
  }
}
