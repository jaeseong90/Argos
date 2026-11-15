/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.SampleApp;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared launch code of the transport tests: the trivial app (runs to completion, so its shutdown
 * hook runs on every platform; 18 §5.2) and the first jar app of {@code -Pagent.e2e} under load,
 * both with the agent's default {@code grpc} exporter against a {@link FakeCollector}. Public
 * harness API only.
 */
final class TransportRuns {
  static final String TRIVIAL_MAIN = "example.trivial.TrivialApp";

  /**
   * Time the trivial app stays alive: long enough for a session on a busy machine and JDK 8 (the
   * first connect took 6 s while two other builds ran on the shared laptop).
   */
  static final String STAY_ALIVE_MILLIS = "12000";

  private TransportRuns() {}

  static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  static Path releaseJar() {
    return Path.of(required("argos.test.releaseAgentJar"));
  }

  /** The release jar plus the test-only module that makes transactions of the trivial app. */
  static Path probeJar() {
    return Path.of(required("argos.test.probeAgentJar"));
  }

  /** {@code -Pagent.attach.jdks} (default 8 and 21). */
  static List<Integer> attachJdks() {
    return Arrays.stream(System.getProperty("argos.test.jdks", "8,21").split(",", -1))
        .map(String::trim)
        .map(Integer::parseInt)
        .toList();
  }

  /** JDK 21 when it is an attach JDK (the default), else the last attach JDK. */
  static int mainJdk() {
    List<Integer> jdks = attachJdks();
    return jdks.contains(21) ? 21 : jdks.get(jdks.size() - 1);
  }

  static SampleApp trivialApp() {
    return SampleApp.mainClass("trivial", TRIVIAL_MAIN, required("argos.test.trivialAppClasses"));
  }

  /** The trivial app on {@code jdk} with the probe jar, staying alive {@code stayAliveMillis}. */
  static AppLauncher trivial(Path dir, int jdk, FakeCollector collector, String stayAliveMillis) {
    return trivial(dir, Jdk.of(jdk), probeJar(), collector, stayAliveMillis);
  }

  static AppLauncher trivial(
      Path dir, Jdk jdk, Path agentJar, FakeCollector collector, String stayAliveMillis) {
    AppLauncher launcher =
        AppLauncher.of(trivialApp(), jdk)
            .withAgent(agentJar)
            .collector(collector)
            .args(dir.resolve("out").resolve("results.properties").toString(), stayAliveMillis)
            .workDir(dir);
    return launcher;
  }

  /** Runs to the end; a time-out or a non-zero exit fails the calling test. */
  static ExitedApp runToExit(AppLauncher launcher) throws Exception {
    ExitedApp run = launcher.runToExit(Duration.ofMinutes(2));
    assertThat(run.timedOut()).as(run.describe()).isFalse();
    assertThat(run.exitCode()).as(run.describe()).isZero();
    return run;
  }

  /** The first {@code <app>@<jdk>} of {@code -Pagent.e2e} that is a jar app, or null. */
  static String firstJarCombo() {
    return Arrays.stream(System.getProperty("argos.test.e2e", "").split(",", -1))
        .map(String::strip)
        .filter(item -> item.contains("@"))
        .filter(item -> !SampleApp.named(appOf(item)).isWar())
        .findFirst()
        .orElse(null);
  }

  static String appOf(String combo) {
    return combo.substring(0, combo.indexOf('@'));
  }

  static int jdkOf(String combo) {
    return Integer.parseInt(combo.substring(combo.indexOf('@') + 1));
  }

  /** Occurrences of {@code regex} in {@code text}. */
  static int count(String text, String regex) {
    Matcher matcher = Pattern.compile(regex).matcher(text);
    int found = 0;
    while (matcher.find()) {
      found++;
    }
    return found;
  }

  /** Fails unless {@code settings} of a run can be read back in the assertion message. */
  static String describe(Map<String, String> settings) {
    return settings.toString();
  }

  /** The value of the agent's WARN about a connection failure (SessionClient). */
  static final String CONNECT_FAILED = "collector connection failed";

  /** The agent's WARN on every connect with skip-verify (16 §3.4). */
  static final String SKIP_VERIFY_WARN = "TLS verification disabled (insecure-skip-verify=true)";

  /** The agent's WARN on every plaintext connect (16 §3.4). */
  static final String PLAINTEXT_WARN = "server.tls is false";

  /** The agent's line for an established session (the protocol in parentheses). */
  static final String SESSION_ESTABLISHED = "collector session established";

  /**
   * Deletes a run's directory. On Windows a process that was just stopped can hold its files for a
   * moment (and with a virus scanner for longer), which makes JUnit's own {@code @TempDir} cleanup
   * fail a passed test: the tests use {@code CleanupMode.NEVER} and this, which retries for 30 s
   * and only reports what is left.
   */
  static void deleteRun(Path dir) {
    long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (true) {
      try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(dir)) {
        walk.sorted(java.util.Comparator.reverseOrder())
            .forEach(
                path -> {
                  try {
                    java.nio.file.Files.deleteIfExists(path);
                  } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                  }
                });
        return;
      } catch (java.nio.file.NoSuchFileException e) {
        return;
      } catch (java.io.IOException | java.io.UncheckedIOException e) {
        if (System.nanoTime() > end) {
          System.out.println("[transport] left in the temp directory: " + dir + " (" + e + ")");
          return;
        }
        try {
          Thread.sleep(200);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  /** Waits (polling) until {@code condition} is true or {@code timeout} passed. */
  static boolean await(java.util.function.BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long end = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < end) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.sleep(50);
    }
    return condition.getAsBoolean();
  }
}
