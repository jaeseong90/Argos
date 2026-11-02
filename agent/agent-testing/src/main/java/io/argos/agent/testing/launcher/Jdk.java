/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A JDK that sample apps run on (18 §5.2): feature version and its {@code java} executable.
 *
 * <p>Resolution of {@link #of(int)}, first match wins:
 *
 * <ol>
 *   <li>system property {@code argos.test.java<N>} (the path of {@code java}; agent-testing's
 *       Gradle test tasks set it from the Gradle toolchain of JDK N, see the README);
 *   <li>environment variable {@code ARGOS_TEST_JDK_<N>} (a JDK home);
 *   <li>{@code ~/.argos-test/jdks/<N>} (a JDK home, 18 §5.2).
 * </ol>
 *
 * @param feature feature version ({@code 8}, {@code 21}, ...)
 * @param java the {@code java} executable
 */
public record Jdk(int feature, Path java) {
  public static final int J8 = 8;
  public static final int J11 = 11;
  public static final int J17 = 17;
  public static final int J21 = 21;
  public static final int J25 = 25;

  /** The JDK of feature version {@code feature}; fails with the places it looked at. */
  public static Jdk of(int feature) {
    String property = System.getProperty("argos.test.java" + feature);
    if (property != null && !property.isBlank()) {
      return new Jdk(feature, Path.of(property));
    }
    String variable = System.getenv("ARGOS_TEST_JDK_" + feature);
    if (variable != null && !variable.isBlank()) {
      return new Jdk(feature, executable(Path.of(variable)));
    }
    Path home = Path.of(System.getProperty("user.home"), ".argos-test", "jdks", "" + feature);
    if (Files.isDirectory(home)) {
      return new Jdk(feature, executable(home));
    }
    throw new IllegalStateException(
        "JDK "
            + feature
            + " not found: set -Dargos.test.java"
            + feature
            + "=<java executable> (Gradle: -Pagent.attach.jdks / -Pagent.e2e add toolchains),"
            + " ARGOS_TEST_JDK_"
            + feature
            + "=<JDK home> or install it in "
            + home);
  }

  /** {@code <home>/bin/java[.exe]}. */
  static Path executable(Path home) {
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    return home.resolve("bin").resolve(windows ? "java.exe" : "java");
  }

  /** The JDK home ({@code java}'s {@code bin/..}). */
  public Path home() {
    return java.toAbsolutePath().getParent().getParent();
  }

  /** A tool of this JDK ({@code jcmd}, {@code javap}, {@code keytool}). */
  public Path tool(String name) {
    Path bin = java.toAbsolutePath().getParent();
    Path exe = bin.resolve(name + ".exe");
    return Files.exists(exe) ? exe : bin.resolve(name);
  }

  @Override
  public String toString() {
    return "JDK " + feature;
  }
}
