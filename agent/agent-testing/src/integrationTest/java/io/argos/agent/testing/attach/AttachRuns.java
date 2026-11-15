/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.attach;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.SampleApp;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Shared launch and assertion code of the attach test classes ({@link AgentAttachTest}, {@link
 * ReleaseGateAttachTest}, {@link FailSafeAttachTest}): {@code example.trivial.TrivialApp} ({@link
 * SampleApp#mainClass}) with {@code -javaagent} on each JDK of {@code -Pagent.attach.jdks} (default
 * 8 and 21), run to completion by {@link AppLauncher#runToExit} with the harness presets ({@code
 * -XX:-UsePerfData}, C-27 verification, JDK 25 {@code --sun-misc-unsafe-memory-access=deny}). The
 * classes are split by requirement because JUnit gives a dynamic test the tags of its factory and
 * class only (test/README.md §5).
 */
final class AttachRuns {
  static final String DONE = "trivial-app done";
  static final String MAIN = "example.trivial.TrivialApp";

  /** The JVM's CDS notice when a jar is appended to the bootstrap class path (not agent output). */
  static final Pattern CDS_NOTICE =
      Pattern.compile(
          "(OpenJDK|Java HotSpot\\(TM\\)) 64-Bit Server VM warning: Sharing is only supported for"
              + " boot loader classes because bootstrap classpath has been appended");

  static final List<String> UNSAFE_PATHS =
      List.of(
          "ClassInjector$UsingUnsafe",
          "ClassInjector$UsingReflection",
          "NexusAccessor",
          "LambdaMetafactoryFactory$Loader");

  static final String TRANSFORM_LIST_HEADER =
      "# Argos transformed classes: <class> <modules> <loader> [retransformed]";

  private AttachRuns() {}

  static List<Integer> jdks() {
    return Arrays.stream(System.getProperty("argos.test.jdks", "8,21").split(",", -1))
        .map(String::trim)
        .map(Integer::parseInt)
        .toList();
  }

  static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  static Path jar(String property) {
    return Path.of(required("argos.test." + property));
  }

  /**
   * One run in {@code <work>/jdk<N>-<name>}: trivial app, its results file, the agent jar, watched
   * temp directories.
   */
  static ExitedApp launch(
      Path work,
      int jdk,
      String name,
      Path agentJar,
      List<String> options,
      boolean verification,
      String... extraArgs)
      throws Exception {
    Path dir = work.resolve("jdk" + jdk + "-" + name);
    SampleApp app = SampleApp.mainClass("trivial", MAIN, required("argos.test.trivialAppClasses"));
    AppLauncher launcher =
        AppLauncher.of(app, Jdk.of(jdk))
            .withJvmOptions(options.toArray(String[]::new))
            .bytecodeVerification(verification)
            .watchSystemTemp(true)
            .args(dir.resolve("out").resolve("results.properties").toString())
            .args(extraArgs)
            .workDir(dir);
    if (agentJar != null) {
      launcher.withAgent(agentJar);
    }
    ExitedApp run = launcher.runToExit(Duration.ofMinutes(2));
    assertThat(run.timedOut()).as("JDK %d %s finished within 120 s", jdk, name).isFalse();
    return run;
  }

  /**
   * {@link #launch} with agent settings that replace the harness defaults of the same key ({@code
   * log.dir}, ...); JVM options cannot, because the harness defaults follow them on the command
   * line.
   */
  static ExitedApp launchWithSettings(
      Path work,
      int jdk,
      String name,
      Path agentJar,
      Map<String, String> settings,
      List<String> options,
      String... extraArgs)
      throws Exception {
    Path dir = work.resolve("jdk" + jdk + "-" + name);
    SampleApp app = SampleApp.mainClass("trivial", MAIN, required("argos.test.trivialAppClasses"));
    ExitedApp run =
        AppLauncher.of(app, Jdk.of(jdk))
            .withJvmOptions(options.toArray(String[]::new))
            .watchSystemTemp(true)
            .args(dir.resolve("out").resolve("results.properties").toString())
            .args(extraArgs)
            .workDir(dir)
            .withAgent(agentJar, settings)
            .runToExit(Duration.ofMinutes(2));
    assertThat(run.timedOut()).as("JDK %d %s finished within 120 s", jdk, name).isFalse();
    return run;
  }

  /** The raw JSONL lines of {@code argos.test.exporter=file:<run>/out}. */
  static List<String> transactions(ExitedApp run) {
    return lines(run.transactions().file());
  }

  static List<String> lines(Path file) {
    try {
      return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<String> withRun(List<String> options, String run) {
    return options.stream().map(option -> option.replace("%s", run)).toList();
  }

  static void assertCleanRun(ExitedApp run) {
    assertThat(run.exitCode()).as(run.describe()).isZero();
    assertThat(run.stdout().strip()).as(run.describe()).isEqualTo(DONE);
    assertThat(run.stdout().lines().count()).as(run.describe()).isEqualTo(1);
    assertThat(run.stderrLines())
        .as(run.describe())
        .allSatisfy(line -> assertThat(line).matches(CDS_NOTICE));
    assertThat(run.tempEvents()).as("events in the watched temp directory").isEmpty();
    assertThat(run.systemTempAdded())
        .as("agent-like entries added to the system temp dir")
        .isEmpty();
    try (Stream<Path> files = Files.list(run.dir().resolve("agent"))) {
      assertThat(files.map(p -> p.getFileName().toString()).toList())
          .as("nothing extracted next to the agent jar")
          .containsExactly("argos-agent.jar");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static void assertIsolation(Properties results, String bootstrapLoader) {
    assertThat(results.getProperty("bootstrapLoader")).isEqualTo(bootstrapLoader);
    assertThat(results.getProperty("coreLoader")).isEqualTo("invisible");
    assertThat(results.getProperty("apiLoader")).isEqualTo("invisible");
    assertThat(results.getProperty("shadedLoader")).isEqualTo("invisible");
    assertThat(results.getProperty("byteBuddyLoader")).isEqualTo("invisible");
    // Package-private in the bootstrap package: visible as a class, not instantiable.
    assertThat(results.getProperty("agentClassLoaderLoader")).isEqualTo("bootstrap");
    assertThat(results.getProperty("coreClassResource")).isEqualTo("false");
    assertThat(results.getProperty("moduleServiceFile")).isEqualTo("false");
    assertThat(results.getProperty("handled")).isEqualTo("3");
    assertThat(results.getProperty("asyncRuns")).isEqualTo("3");
    assertThat(results.getProperty("agentThreads")).contains("argos-logger");
  }

  /** The module ids of the agent's start banner ({@code modules=[a,b]}). */
  static List<String> modules(String log) {
    Matcher matcher = Pattern.compile("modules=\\[([^\\]]*)\\]").matcher(log);
    assertThat(matcher.find()).as("start banner with modules=[...] in%n%s", log).isTrue();
    return matcher.group(1).isEmpty() ? List.of() : List.of(matcher.group(1).split(",", -1));
  }

  static Predicate<String> isProbe() {
    return module -> module.startsWith("test-probe");
  }

  static void reportStartup(ExitedApp run) {
    Matcher started = Pattern.compile("started in ([0-9.]+) ms").matcher(run.agentLog());
    if (started.find()) {
      System.out.println(
          "[attach] JDK "
              + run.jdk()
              + " "
              + run.dir().getFileName()
              + ": agent start "
              + started.group(1)
              + " ms");
    }
  }
}
